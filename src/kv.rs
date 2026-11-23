//! Le magasin clé-valeur de [Nuage DB](https://github.com/maxlestage/Nuage)
//! (`/v1/kv`) : un double de la mémoire du relais, gardé ailleurs.
//!
//! Sans lui, tout ce que retient le relais (voir [`crate::relay`]) disparaît
//! avec lui : à chaque redémarrage, à chaque déploiement, à chaque mise en
//! veille de l'hébergeur. Avec lui (`TALKA_KV_URL` et `TALKA_KV_TOKEN`), le
//! relais y recopie au fil de l'eau ce qu'il retient, et le relit au
//! démarrage : les messages qui attendent un destinataire hors ligne, les
//! clés publiques et les paquets de clés des codes, leurs appareils, leurs
//! adresses de notification, leurs jetons révoqués.
//!
//! Ce magasin est lui aussi **en mémoire vive** : Nuage DB n'en écrit rien
//! sur disque, et son propre redémarrage le vide (le relais le remplit alors
//! de nouveau avec ce qu'il a en mémoire). Ce n'est donc pas une base de
//! données : c'est une seconde mémoire, qui ne tombe pas en même temps que
//! la première.
//!
//! **Scellé.** Nuage DB ne voit ni les codes, ni les boîtes, ni ce qu'elles
//! contiennent : chaque nom de clé est une empreinte (HKDF-SHA256, tronquée
//! à 128 bits) et chaque valeur est chiffrée (AES-256-GCM, le nom de la clé
//! en données associées), avec des clés dérivées de `TALKA_KV_KEY`, que seul
//! le relais connaît (à défaut, du jeton). Les messages, eux, étaient déjà
//! chiffrés de bout en bout : le relais ne peut toujours pas les lire.
//!
//! **Sans attendre.** Le relais ne parle pas à Nuage DB pendant qu'il sert :
//! il met ce qu'il change dans une file, qu'un fil d'arrière-plan envoie par
//! lots (une seule requête pour tout ce qui s'est accumulé). Si Nuage DB ne
//! répond plus, le relais continue en mémoire, et recopie tout à son retour.
//! Ce qui n'a pas eu le temps de partir quand le relais tombe est renvoyé
//! par les appareils, comme avant (`ready.boot`).

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as URL_B64;
use hkdf::Hkdf;
use serde_json::{Value, json};
use sha2::Sha256;

/// Toutes les clés de Talka dans le magasin commencent ainsi.
const PREFIX: &str = "t1:";
/// Qui écrit en ce moment : change à chaque démarrage du relais, et disparaît
/// avec la mémoire de Nuage DB (le relais sait alors qu'il faut tout recopier).
const EPOCH: &str = "t1:epoch";
/// Un lot : 256 commandes et 4 Mio au plus (Nuage DB en accepte 16).
const BATCH_COUNT: usize = 256;
const BATCH_BYTES: usize = 4 * 1024 * 1024;
/// Les valeurs relues au démarrage, par requête, et les requêtes en même
/// temps.
const READ_COUNT: usize = 32;
const READERS: usize = 8;
/// Sans rien à écrire, le relais vérifie de temps en temps que Nuage DB n'a
/// pas redémarré.
const IDLE: Duration = Duration::from_secs(20);
/// Après tant d'échecs de suite, la file est abandonnée : tout sera recopié.
const MAX_FAILURES: u32 = 3;

/// L'état du double, pour `/api/stats`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Status {
    /// Pas de magasin : le relais ne garde qu'en mémoire.
    Off = 0,
    /// Le magasin a tout ce que le relais lui a confié.
    Ok = 1,
    /// Nuage DB ne répond pas, ou vient de redémarrer : la recopie attend.
    Late = 2,
}

impl Status {
    pub fn as_str(self) -> &'static str {
        match self {
            Status::Off => "off",
            Status::Ok => "ok",
            Status::Late => "retard",
        }
    }
}

/// Ce que le relais change, à recopier.
#[derive(Clone, Debug, PartialEq)]
pub enum Op {
    /// Garde `value` sous (`kind`, `name`) pendant `ttl`.
    Set {
        kind: char,
        name: String,
        value: Value,
        ttl: Duration,
    },
    /// L'oublie.
    Del { kind: char, name: String },
}

impl Op {
    /// Ce que pèse l'opération dans un lot, à peu près.
    fn weight(&self) -> usize {
        match self {
            Op::Set { value, .. } => sealed_size(value.to_string().len()),
            Op::Del { .. } => 64,
        }
    }
}

/// Ce qu'occupe dans le magasin une valeur de `len` octets, une fois scellée
/// (base64 du chiffré, nom de la clé, et ce que Nuage DB compte par clé).
pub fn sealed_size(len: usize) -> usize {
    (len + 28).div_ceil(3) * 4 + 256
}

/// Ce qu'un fil de lecture a relu : les fiches, et les noms illisibles.
type Read = (Vec<Value>, Vec<String>);

/// Le magasin clé-valeur d'une application de Nuage DB.
pub struct Kv {
    url: String,
    token: String,
    names: Hkdf<Sha256>,
    cipher: Aes256Gcm,
}

impl Kv {
    /// Le magasin de l'application dont `token` est le jeton, sur le serveur
    /// Nuage DB `url` ; `secret` scelle ce qui y est écrit.
    pub fn new(url: &str, token: &str, secret: &str) -> Kv {
        let root = Hkdf::<Sha256>::new(Some(b"talka-kv-v1"), secret.as_bytes());
        let mut names = [0u8; 32];
        let mut values = [0u8; 32];
        root.expand(b"noms", &mut names).expect("32 octets");
        root.expand(b"valeurs", &mut values).expect("32 octets");
        Kv {
            url: url.trim_end_matches('/').to_owned(),
            token: token.to_owned(),
            names: Hkdf::<Sha256>::from_prk(&names).expect("32 octets"),
            cipher: Aes256Gcm::new_from_slice(&values).expect("32 octets"),
        }
    }

    /// `TALKA_KV_URL` (un serveur Nuage DB, en HTTPS) et `TALKA_KV_TOKEN` (le
    /// jeton d'une application réservée à Talka) ; `TALKA_KV_KEY` : le secret
    /// qui scelle (à défaut, le jeton).
    pub fn from_env() -> Option<Kv> {
        let var = |name: &str| {
            std::env::var(name)
                .ok()
                .map(|v| v.trim().to_owned())
                .filter(|v| !v.is_empty())
        };
        let (url, token) = (var("TALKA_KV_URL")?, var("TALKA_KV_TOKEN")?);
        if !(url.starts_with("https://") || url.starts_with("http://")) {
            eprintln!(
                "⚠️  TALKA_KV_URL n'est pas une adresse http(s) : le relais ne garde qu'en mémoire"
            );
            return None;
        }
        let secret = var("TALKA_KV_KEY").unwrap_or_else(|| token.clone());
        Some(Kv::new(&url, &token, &secret))
    }

    /// Le nom, dans le magasin, de ce que le relais appelle (`kind`, `name`).
    fn key(&self, kind: char, name: &str) -> String {
        let mut tag = [0u8; 16];
        let mut utf8 = [0u8; 4];
        self.names
            .expand_multi_info(
                &[kind.encode_utf8(&mut utf8).as_bytes(), name.as_bytes()],
                &mut tag,
            )
            .expect("16 octets");
        format!("{PREFIX}{kind}{}", URL_B64.encode(tag))
    }

    // `Nonce::from_slice` : l'API d'aes-gcm 0.10.
    #[allow(deprecated)]
    fn seal(&self, key: &str, plain: &[u8]) -> Option<String> {
        let mut nonce = [0u8; 12];
        getrandom::getrandom(&mut nonce).ok()?;
        let sealed = self
            .cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: plain,
                    aad: key.as_bytes(),
                },
            )
            .ok()?;
        let mut out = nonce.to_vec();
        out.extend(sealed);
        Some(B64.encode(out))
    }

    #[allow(deprecated)]
    fn open(&self, key: &str, sealed: &str) -> Option<Vec<u8>> {
        let bytes = B64.decode(sealed).ok()?;
        if bytes.len() < 12 {
            return None;
        }
        let (nonce, sealed) = bytes.split_at(12);
        self.cipher
            .decrypt(
                Nonce::from_slice(nonce),
                Payload {
                    msg: sealed,
                    aad: key.as_bytes(),
                },
            )
            .ok()
    }

    /// La commande qui recopie `op`.
    fn command(&self, op: &Op) -> Option<Value> {
        Some(match op {
            Op::Set {
                kind,
                name,
                value,
                ttl,
            } => {
                let key = self.key(*kind, name);
                let sealed = self.seal(&key, value.to_string().as_bytes())?;
                json!(["SET", key, sealed, "PX", (ttl.as_millis() as u64).max(1)])
            }
            Op::Del { kind, name } => json!(["DEL", self.key(*kind, name)]),
        })
    }

    fn agent() -> &'static ureq::Agent {
        static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
        AGENT.get_or_init(|| {
            ureq::AgentBuilder::new()
                .timeout_connect(Duration::from_secs(5))
                .timeout(Duration::from_secs(30))
                .user_agent("talka-relais")
                .build()
        })
    }

    /// Des commandes, en un aller-retour ; leurs réponses, dans l'ordre.
    fn run(&self, commands: Vec<Value>) -> Result<Vec<Value>, String> {
        let count = commands.len();
        let answer = json_of(
            Kv::agent()
                .post(&format!("{}/v1/kv", self.url))
                .set("authorization", &format!("Bearer {}", self.token))
                .set("content-type", "application/json")
                .send_string(&json!({ "commands": commands }).to_string()),
        )?;
        match answer["results"].as_array() {
            Some(results) if results.len() == count => Ok(results.clone()),
            _ => Err("réponse inattendue de Nuage DB".into()),
        }
    }

    /// La mémoire du magasin, en octets (`NUAGE_KV_MB` sur le serveur).
    pub fn capacity(&self) -> Result<usize, String> {
        let answer = json_of(
            Kv::agent()
                .get(&format!("{}/v1/kv", self.url))
                .query("prefix", EPOCH)
                .query("limit", "1")
                .set("authorization", &format!("Bearer {}", self.token))
                .call(),
        )?;
        answer["stats"]["max_bytes"]
            .as_u64()
            .map(|n| n as usize)
            .ok_or_else(|| "réponse inattendue de Nuage DB".into())
    }

    /// Les clés de Talka dans le magasin.
    fn keys(&self) -> Result<Vec<String>, String> {
        let results = self.run(vec![json!(["KEYS", format!("{PREFIX}*")])])?;
        Ok(results[0]
            .as_array()
            .map(|keys| {
                keys.iter()
                    .filter_map(Value::as_str)
                    .map(str::to_owned)
                    .collect()
            })
            .unwrap_or_default())
    }

    /// Tout ce que le relais avait confié au magasin. Ce qui ne s'ouvre pas
    /// (écrit avec une autre clé) est effacé. Les lectures partent sur
    /// `READERS` fils à la fois : le relais ne répond pas avant d'avoir tout
    /// relu, et chaque lot est un aller-retour réseau.
    pub fn load(&self) -> Result<Vec<Value>, String> {
        let keys: Vec<String> = self.keys()?.into_iter().filter(|k| k != EPOCH).collect();
        let chunks: Vec<&[String]> = keys.chunks(READ_COUNT).collect();
        let next = std::sync::atomic::AtomicUsize::new(0);
        let read = |records: &mut Vec<Value>, stale: &mut Vec<String>| -> Result<(), String> {
            loop {
                let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                let Some(chunk) = chunks.get(i) else {
                    return Ok(());
                };
                let mut command = vec![json!("MGET")];
                command.extend(chunk.iter().map(|k| json!(k)));
                let mut results = self.run(vec![Value::Array(command)])?;
                let values = match results.first_mut().map(Value::take) {
                    Some(Value::Array(values)) => values,
                    _ => Vec::new(),
                };
                for (key, value) in chunk.iter().zip(values) {
                    // Expirée entre les deux requêtes : rien à relire.
                    let Some(sealed) = value.as_str() else {
                        continue;
                    };
                    match self
                        .open(key, sealed)
                        .and_then(|plain| serde_json::from_slice::<Value>(&plain).ok())
                    {
                        Some(record) => records.push(record),
                        None => stale.push(key.clone()),
                    }
                }
            }
        };
        let readers = READERS.min(chunks.len()).max(1);
        let parts: Vec<Result<Read, String>> = std::thread::scope(|scope| {
            let handles: Vec<_> = (0..readers)
                .map(|_| {
                    scope.spawn(|| {
                        let (mut records, mut stale) = (Vec::new(), Vec::new());
                        read(&mut records, &mut stale).map(|()| (records, stale))
                    })
                })
                .collect();
            handles
                .into_iter()
                .map(|h| {
                    h.join()
                        .unwrap_or_else(|_| Err("lecture interrompue".into()))
                })
                .collect()
        });
        let mut records = Vec::with_capacity(keys.len());
        let mut stale = Vec::new();
        for part in parts {
            let (r, s) = part?;
            records.extend(r);
            stale.extend(s);
        }
        for chunk in stale.chunks(BATCH_COUNT) {
            let mut command = vec![json!("DEL")];
            command.extend(chunk.iter().map(|k| json!(k)));
            self.run(vec![Value::Array(command)])?;
        }
        Ok(records)
    }

    /// Efface tout ce que Talka a dans le magasin.
    fn wipe(&self) -> Result<(), String> {
        let keys = self.keys()?;
        for chunk in keys.chunks(BATCH_COUNT) {
            let mut command = vec![json!("DEL")];
            command.extend(chunk.iter().map(|k| json!(k)));
            self.run(vec![Value::Array(command)])?;
        }
        Ok(())
    }

    /// Écrit `ops`, par lots.
    fn write(&self, ops: &[Op]) -> Result<(), String> {
        let mut batch = Vec::new();
        let mut bytes = 0;
        for op in ops {
            let Some(command) = self.command(op) else {
                continue;
            };
            bytes += op.weight();
            batch.push(command);
            if batch.len() >= BATCH_COUNT || bytes >= BATCH_BYTES {
                self.run(std::mem::take(&mut batch))?;
                bytes = 0;
            }
        }
        if !batch.is_empty() {
            self.run(batch)?;
        }
        Ok(())
    }
}

/// La réponse de Nuage DB, en JSON (64 Mio au plus).
fn json_of(answer: Result<ureq::Response, ureq::Error>) -> Result<Value, String> {
    use std::io::Read;
    let mut body = Vec::new();
    answer
        .map_err(|e| brief(&e))?
        .into_reader()
        .take(64 * 1024 * 1024)
        .read_to_end(&mut body)
        .map_err(|e| format!("Nuage DB ne répond pas ({})", e.kind()))?;
    serde_json::from_slice(&body).map_err(|_| "réponse inattendue de Nuage DB".to_owned())
}

/// Une erreur, sans l'adresse ni le jeton.
fn brief(e: &ureq::Error) -> String {
    match e {
        ureq::Error::Status(code, _) => format!("Nuage DB a répondu {code}"),
        ureq::Error::Transport(t) => format!("Nuage DB ne répond pas ({})", t.kind()),
    }
}

/// La file vers le magasin : ce que le relais y met part en arrière-plan.
pub struct Mirror {
    tx: mpsc::Sender<Op>,
    status: Arc<AtomicU8>,
}

impl Mirror {
    pub fn set(&self, kind: char, name: &str, value: Value, ttl: Duration) {
        let _ = self.tx.send(Op::Set {
            kind,
            name: name.to_owned(),
            value,
            ttl,
        });
    }

    pub fn del(&self, kind: char, name: &str) {
        let _ = self.tx.send(Op::Del {
            kind,
            name: name.to_owned(),
        });
    }

    pub fn status(&self) -> Status {
        match self.status.load(Ordering::Relaxed) {
            1 => Status::Ok,
            _ => Status::Late,
        }
    }
}

/// Lance la recopie vers `kv`. `snapshot` rend tout ce que le relais retient
/// (pour tout recopier après une panne de Nuage DB), ou `None` quand le
/// relais n'existe plus : le fil s'arrête. `synced` : le magasin vient d'être
/// relu, il a déjà tout.
pub fn mirror(
    kv: Kv,
    synced: bool,
    snapshot: impl Fn() -> Option<Vec<Op>> + Send + 'static,
) -> Mirror {
    let (tx, rx) = mpsc::channel();
    let status = Arc::new(AtomicU8::new(
        if synced { Status::Ok } else { Status::Late } as u8,
    ));
    let shared = status.clone();
    let spawned = std::thread::Builder::new()
        .name("talka-kv".into())
        .spawn(move || work(&kv, &rx, synced, &snapshot, &shared));
    if spawned.is_err() {
        eprintln!(
            "⚠️  la recopie vers Nuage DB n'a pas pu démarrer : le relais ne garde qu'en mémoire"
        );
    }
    Mirror { tx, status }
}

fn work(
    kv: &Kv,
    rx: &mpsc::Receiver<Op>,
    mut synced: bool,
    snapshot: &dyn Fn() -> Option<Vec<Op>>,
    status: &AtomicU8,
) {
    let epoch = {
        let mut bytes = [0u8; 12];
        let _ = getrandom::getrandom(&mut bytes);
        URL_B64.encode(bytes)
    };
    // Le magasin relu au démarrage est le nôtre, désormais.
    if synced && kv.run(vec![json!(["SET", EPOCH, epoch])]).is_err() {
        synced = false;
    }
    let mut pending: Vec<Op> = Vec::new();
    let mut failures = 0u32;
    let mut told = false;
    loop {
        status.store(
            if synced { Status::Ok } else { Status::Late } as u8,
            Ordering::Relaxed,
        );
        if !synced {
            // Tout recopier : ce qui attendait dans la file est déjà dans la
            // mémoire du relais, donc dans ce que rend `snapshot` ; ce qui y
            // entre pendant la recopie partira après, dans l'ordre.
            pending.clear();
            loop {
                match rx.try_recv() {
                    Ok(_) => {}
                    Err(mpsc::TryRecvError::Empty) => break,
                    Err(mpsc::TryRecvError::Disconnected) => return,
                }
            }
            // On efface d'abord : si Nuage DB ne répond pas, inutile de
            // relire toute la mémoire du relais.
            let copied = kv.wipe().and_then(|()| match snapshot() {
                Some(all) => kv.write(&all).map(|()| true),
                None => Ok(false),
            });
            let copied = match copied {
                Ok(false) => return,
                Ok(true) => kv.run(vec![json!(["SET", EPOCH, epoch])]).map(|_| ()),
                Err(e) => Err(e),
            };
            match copied {
                Ok(()) => {
                    if told {
                        println!("✅ Nuage DB répond de nouveau : tout y est recopié");
                    }
                    (synced, failures, told) = (true, 0, false);
                }
                Err(e) => {
                    if !told {
                        eprintln!(
                            "⚠️  {e} : le relais continue en mémoire, et recopiera tout à son retour"
                        );
                        told = true;
                    }
                    failures = failures.saturating_add(1);
                    std::thread::sleep(backoff(failures));
                }
            }
            continue;
        }
        if pending.is_empty() {
            match rx.recv_timeout(IDLE) {
                Ok(op) => pending.push(op),
                Err(RecvTimeoutError::Timeout) => {}
                Err(RecvTimeoutError::Disconnected) => return,
            }
        }
        let mut bytes: usize = pending.iter().map(Op::weight).sum();
        while pending.len() < BATCH_COUNT && bytes < BATCH_BYTES {
            match rx.try_recv() {
                Ok(op) => {
                    bytes += op.weight();
                    pending.push(op);
                }
                Err(_) => break,
            }
        }
        // D'abord : le magasin est-il toujours celui qu'on a rempli ?
        let mut commands = vec![json!(["GET", EPOCH])];
        commands.extend(pending.iter().filter_map(|op| kv.command(op)));
        match kv.run(commands) {
            Ok(results) if results[0].as_str() == Some(epoch.as_str()) => {
                pending.clear();
                failures = 0;
            }
            // Nuage DB a redémarré (sa mémoire est vide) : tout recopier.
            Ok(_) => synced = false,
            Err(_) => {
                failures += 1;
                if failures >= MAX_FAILURES {
                    synced = false;
                } else {
                    std::thread::sleep(backoff(failures));
                }
            }
        }
    }
}

/// 1 s, 2 s, 4 s… 30 s au plus.
fn backoff(failures: u32) -> Duration {
    Duration::from_secs((1u64 << failures.saturating_sub(1).min(5)).min(30))
}

/// Un faux Nuage DB pour les tests : `/v1/kv` en mémoire, sans expiration.
#[cfg(test)]
pub(crate) mod fake {
    use std::collections::BTreeMap;
    use std::io::{Read, Write};
    use std::net::{TcpListener, TcpStream};
    use std::sync::{Arc, Mutex};
    use std::time::{Duration, Instant};

    use serde_json::{Value, json};

    type Store = Arc<Mutex<BTreeMap<String, String>>>;

    pub struct Nuage {
        pub url: String,
        store: Store,
    }

    impl Nuage {
        pub fn start() -> Nuage {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let url = format!("http://{}", listener.local_addr().unwrap());
            let store = Store::default();
            let shared = store.clone();
            std::thread::spawn(move || {
                for stream in listener.incoming().flatten() {
                    let store = shared.clone();
                    std::thread::spawn(move || serve(stream, &store));
                }
            });
            Nuage { url, store }
        }

        pub fn kv(&self) -> super::Kv {
            super::Kv::new(&self.url, "jeton", "secret")
        }

        /// Les clés de cette sorte (`m` : les messages…).
        pub fn count(&self, kind: char) -> usize {
            let prefix = format!("{}{kind}", super::PREFIX);
            let store = self.store.lock().unwrap();
            store.keys().filter(|k| k.starts_with(&prefix)).count()
        }

        /// Tout ce que le magasin contient, noms et valeurs.
        pub fn dump(&self) -> String {
            format!("{:?}", self.store.lock().unwrap())
        }

        /// Nuage DB redémarre : sa mémoire est vide.
        pub fn restart(&self) {
            self.store.lock().unwrap().clear();
        }

        /// Attend (5 s au plus) que la recopie en soit là.
        pub fn settle(&self, what: &str, done: impl Fn(&Nuage) -> bool) {
            let start = Instant::now();
            while !done(self) {
                assert!(
                    start.elapsed() < Duration::from_secs(5),
                    "jamais recopié : {what}"
                );
                std::thread::sleep(Duration::from_millis(5));
            }
        }
    }

    fn serve(mut stream: TcpStream, store: &Store) {
        loop {
            let mut head = Vec::new();
            let mut byte = [0u8; 1];
            while !head.ends_with(b"\r\n\r\n") {
                match stream.read(&mut byte) {
                    Ok(1) => head.push(byte[0]),
                    _ => return,
                }
            }
            let head = String::from_utf8_lossy(&head).to_ascii_lowercase();
            let length = head
                .lines()
                .find_map(|l| l.strip_prefix("content-length:"))
                .and_then(|v| v.trim().parse::<usize>().ok())
                .unwrap_or(0);
            let mut body = vec![0u8; length];
            if stream.read_exact(&mut body).is_err() {
                return;
            }
            let answer = if head.starts_with("get") {
                json!({ "keys": [], "count": 0, "stats": { "max_bytes": 32 << 20 } })
            } else {
                let asked: Value = serde_json::from_slice(&body).unwrap_or_default();
                let mut store = store.lock().unwrap();
                let results: Vec<Value> = asked["commands"]
                    .as_array()
                    .map(|commands| commands.iter().map(|c| run(&mut store, c)).collect())
                    .unwrap_or_default();
                json!({ "results": results })
            }
            .to_string();
            let head = format!(
                "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\n\r\n",
                answer.len()
            );
            if stream
                .write_all(format!("{head}{answer}").as_bytes())
                .is_err()
            {
                return;
            }
        }
    }

    fn run(store: &mut BTreeMap<String, String>, command: &Value) -> Value {
        let args: Vec<String> = command
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|v| v.as_str().map_or_else(|| v.to_string(), str::to_owned))
                    .collect()
            })
            .unwrap_or_default();
        match args.first().map(String::as_str) {
            Some("SET") => {
                store.insert(args[1].clone(), args[2].clone());
                json!("OK")
            }
            Some("GET") => store.get(&args[1]).map_or(Value::Null, |v| json!(v)),
            Some("DEL") => json!(
                args[1..]
                    .iter()
                    .filter(|k| store.remove(*k).is_some())
                    .count()
            ),
            Some("MGET") => args[1..]
                .iter()
                .map(|k| store.get(k).map_or(Value::Null, |v| json!(v)))
                .collect(),
            Some("KEYS") => {
                let prefix = args[1].trim_end_matches('*');
                store
                    .keys()
                    .filter(|k| k.starts_with(prefix))
                    .map(|k| json!(k))
                    .collect()
            }
            _ => json!({ "error": "ERR unknown command" }),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_and_values_are_sealed() {
        let kv = Kv::new("http://localhost", "jeton", "secret");
        let key = kv.key('m', "K7Q2MX9PRT#12");
        // Le nom ne dit rien du code, et ne change pas d'une fois à l'autre.
        assert!(key.starts_with("t1:m") && !key.contains("K7Q2"));
        assert_eq!(key, kv.key('m', "K7Q2MX9PRT#12"));
        assert_ne!(key, kv.key('k', "K7Q2MX9PRT#12"));
        assert_ne!(key, kv.key('m', "K7Q2MX9PRT#13"));
        // La valeur ne s'ouvre qu'avec la même clé, sous le même nom.
        let sealed = kv.seal(&key, b"bonjour").unwrap();
        assert!(!sealed.contains("bonjour"));
        assert_ne!(sealed, kv.seal(&key, b"bonjour").unwrap());
        assert_eq!(kv.open(&key, &sealed).as_deref(), Some(&b"bonjour"[..]));
        assert_eq!(kv.open(&kv.key('m', "autre"), &sealed), None);
        let other = Kv::new("http://localhost", "jeton", "autre secret");
        assert_eq!(other.open(&key, &sealed), None);
        assert_ne!(key, other.key('m', "K7Q2MX9PRT#12"));
    }

    #[test]
    fn commands_carry_no_name_and_an_expiry() {
        let kv = Kv::new("http://localhost", "jeton", "secret");
        let set = kv
            .command(&Op::Set {
                kind: 'k',
                name: "K7Q2MX9PRT".into(),
                value: json!({ "c": "K7Q2MX9PRT" }),
                ttl: Duration::from_secs(60),
            })
            .unwrap();
        assert_eq!(set[0], "SET");
        assert_eq!(set[1], kv.key('k', "K7Q2MX9PRT"));
        assert_eq!((&set[3], &set[4]), (&json!("PX"), &json!(60_000)));
        assert!(!set.to_string().contains("K7Q2MX9PRT"));
        let del = kv
            .command(&Op::Del {
                kind: 'k',
                name: "K7Q2MX9PRT".into(),
            })
            .unwrap();
        assert_eq!(del, json!(["DEL", kv.key('k', "K7Q2MX9PRT")]));
    }

    /// Le démarrage relit tout, sur plusieurs fils à la fois, et efface ce
    /// qui a été écrit avec une autre clé.
    #[test]
    fn everything_is_read_back_and_the_unreadable_is_dropped() {
        let nuage = fake::Nuage::start();
        let kv = nuage.kv();
        let other = Kv::new(&nuage.url, "jeton", "autre secret");
        let set = |kv: &Kv, i: usize| {
            kv.command(&Op::Set {
                kind: 'k',
                name: format!("C{i:09}"),
                value: json!({ "k": "k", "c": format!("C{i:09}") }),
                ttl: Duration::from_secs(60),
            })
            .unwrap()
        };
        // Plus de lots que de fils, et un dernier lot incomplet.
        let total = READ_COUNT * READERS * 3 + 7;
        let commands: Vec<Value> = (0..total).map(|i| set(&kv, i)).collect();
        for chunk in commands.chunks(BATCH_COUNT) {
            kv.run(chunk.to_vec()).unwrap();
        }
        let stale: Vec<Value> = (0..40).map(|i| set(&other, total + i)).collect();
        other.run(stale).unwrap();
        assert_eq!(nuage.count('k'), total + 40);
        let records = kv.load().unwrap();
        let mut codes: Vec<String> = records
            .iter()
            .map(|r| r["c"].as_str().unwrap().to_owned())
            .collect();
        codes.sort();
        let expected: Vec<String> = (0..total).map(|i| format!("C{i:09}")).collect();
        assert_eq!(codes, expected);
        assert_eq!(nuage.count('k'), total);
        assert!(Kv::new(&nuage.url, "jeton", "secret").load().unwrap().len() == total);
    }

    #[test]
    fn retries_wait_longer_each_time() {
        assert_eq!(backoff(1), Duration::from_secs(1));
        assert_eq!(backoff(3), Duration::from_secs(4));
        assert_eq!(backoff(40), Duration::from_secs(30));
    }
}
