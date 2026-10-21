//! Le relais : la seule chose que fait le serveur de Talka.
//!
//! Il ne garde aucune base de données et ne peut lire aucun message : chaque
//! envoi est un jeton chiffré de bout en bout par les navigateurs. Il sait
//! seulement qui envoie (l'appareil a prouvé détenir la clé de son code) et à
//! quel code. Un message attend en mémoire, dans la boîte de son
//! destinataire, que celui-ci confirme l'avoir rangé dans son navigateur
//! (`got`) ; il est alors oublié. Les boîtes ont une taille et une durée
//! limitées, et disparaissent avec le serveur.
//!
//! Le protocole, en JSON sur la WebSocket `/ws` :
//!
//! | Sens | Message | Effet |
//! |---|---|---|
//! | ← | `{"t":"challenge","nonce"}` | à signer pour se présenter |
//! | → | `{"t":"hello","sign","dh","sig","hidden"}` | les deux clés publiques et la signature de `talka-auth:<nonce>` |
//! | ← | `{"t":"ready","code","time","boot","push"}` | puis chaque message en attente ; `push` : la clé VAPID, si le relais envoie des notifications |
//! | → | `{"t":"send","id","to","body","live","wake","apercu"}` | `live` : seulement si le destinataire est en ligne (saisie, appels) ; `wake` : un vrai message, qui réveille un destinataire hors ligne (voir `push`) ; `apercu` : ce que la notification affiche, chiffré par l'expéditeur pour le destinataire |
//! | ← | `{"t":"ack","id","ts","online"}` | le message est arrivé au serveur |
//! | ← | `{"t":"msg","n","id","from","body","ts"}` | un message reçu |
//! | → | `{"t":"got","n":[…]}` | rangé : le serveur l'oublie |
//! | → | `{"t":"lookup","code"}` | ← `{"t":"keys","code","sign","dh"}` (ou `"unknown":true`) |
//! | → | `{"t":"watch","codes":[…]}` | ← `{"t":"presence","code","online","seen"}`, maintenant et à chaque changement |
//! | ← | `{"t":"error","id","error"}` | un envoi refusé |
//! | → | `{"t":"publish-bundle","bundle","sig"}` | le paquet de clés v3 (base64), signé par la clé du code ; il remplace le précédent |
//! | ← | `{"t":"published","ok"}` | et `"error":"size"` ou `"signature"` s'il est refusé |
//! | → | `{"t":"fetch-bundle","code"}` | ← `{"t":"bundle","code","sign","dh","bundle","sig"}` (ou `"none":true`) |
//! | → | `{"t":"anon"}` (au lieu de `hello`) | une connexion anonyme, pour l'expéditeur scellé : ← `{"t":"ready","anon":true}` |
//! | → (anonyme) | `{"t":"send","id","to","sign","dh","jeton","body","live","wake","apercu"}` | `body` scellé ; `jeton` : le jeton de remise de `to`, signé par sa clé (`sign`, `dh` : ses clés publiques) ; ← `ack` ou `error` (`"jeton"`) |
//! | ← | `{"t":"msg","n","id","sealed":true,"body","ts"}` | un message scellé : l'expéditeur est dans `body`, que seul le destinataire ouvre |
//! | → | `{"t":"push","endpoint","p256dh","auth","on"}` | l'abonnement Web Push de cet appareil (`"on":false` pour l'oublier) ; ← `{"t":"push","ok"}` |
//!
//! L'expéditeur scellé (voir `docs/SECURITE.md`, §7.10) : sur une connexion
//! anonyme, le relais ne sait pas qui écrit ; il vérifie seulement que
//! l'expéditeur détient le jeton de remise du destinataire (que celui-ci ne
//! donne qu'à ses contacts), et livre sans `from`. `ready.sealed` annonce
//! qu'il sait le faire.
//!
//! Les notifications (voir `crate::push`) : quand un message marqué `wake`
//! arrive pour un code qui n'a aucune connexion ouverte, le relais envoie un
//! réveil vide à chacune de ses adresses Web Push (au plus une fois toutes
//! les 20 secondes). Les adresses ne vivent qu'en mémoire, comme le reste ;
//! un appareil redonne la sienne à chaque connexion.
//!
//! Le paquet v3 (voir `talka_web::v3`) : le relais vérifie seulement qu'il est
//! signé par la clé du code qui le publie (ECDSA P-256 de
//! `talka-v3-paquet:` ‖ paquet) et qu'il n'est pas trop gros ; c'est
//! l'appareil qui le lit, et qui revérifie tout. Il est oublié à chaque
//! nouvelle connexion du code (un appareil v3 le republie aussitôt), et
//! gardé quand le code se déconnecte.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as URL_B64;
use paquets::vitesse::ws::{Message, WebSocket};
use serde_json::{Value, json};
use tokio::sync::mpsc;

use crate::code;

/// Un message attend au plus 30 jours qu'on vienne le chercher.
const MAILBOX_TTL: Duration = Duration::from_secs(30 * 24 * 3600);
/// La boîte d'un code : 2 000 messages et 48 Mio au plus (les plus anciens partent).
const MAILBOX_MAX_COUNT: usize = 2_000;
const MAILBOX_MAX_BYTES: usize = 48 * 1024 * 1024;
/// Toutes les boîtes ensemble : 256 Mio au plus.
const TOTAL_MAX_BYTES: usize = 256 * 1024 * 1024;
/// Un envoi : 6 Mio au plus (une photo, un vocal, un petit fichier).
pub const MAX_BODY: usize = 6 * 1024 * 1024;
/// Au plus 60 envois (et 60 autres demandes) par seconde et par connexion.
const SENDS_PER_SECOND: u32 = 60;
/// Et au plus 64 Mio envoyés par minute et par connexion.
const BYTES_PER_MINUTE: usize = 64 * 1024 * 1024;
/// Ce qui attend de partir vers un appareil : au-delà (il ne lit plus), la
/// connexion est coupée plutôt que de remplir la mémoire du relais.
const OUT_QUEUE: usize = 4_096;
/// Les connexions anonymes ouvertes en même temps, et leur durée sans envoi.
const MAX_ANON: usize = 20_000;
const ANON_IDLE: Duration = Duration::from_secs(120);
/// Les réveils Web Push, tous codes confondus : 50 par seconde au plus.
const WAKES_PER_SECOND: u32 = 50;
/// Heroku ferme une connexion silencieuse au bout de 55 secondes.
const PING_EVERY: Duration = Duration::from_secs(25);
/// Les clés retenues : au plus 200 000 codes.
const MAX_KEYS: usize = 200_000;
/// Les codes suivis par une connexion.
const MAX_WATCH: usize = 2_000;
/// Les adresses Web Push d'un code (ses navigateurs) : 4 au plus.
const MAX_PUSH: usize = 4;
/// Un réveil par code toutes les 20 secondes au plus.
const WAKE_EVERY: Duration = Duration::from_secs(20);
/// Avec un aperçu (chaque message compte) : une seconde.
const APERCU_EVERY: Duration = Duration::from_secs(1);

/// Ce qu'un envoi demande au relais pour un destinataire hors ligne.
#[derive(Clone, Debug, PartialEq)]
enum Wake {
    /// Rien (un accusé, une réaction, un profil…).
    No,
    /// Un réveil vide : « Nouveau message ».
    Empty,
    /// Un réveil avec l'aperçu chiffré par l'expéditeur (illisible ici).
    Apercu(String),
}
/// Un paquet de clés v3 : 1 418 octets avec ML-KEM ; 2 Kio au plus.
pub const MAX_BUNDLE: usize = 2048;

/// L'heure en millisecondes, comme `Date.now()`.
pub fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as u64)
}

struct Stored {
    n: u64,
    json: String,
    at: Instant,
}

#[derive(Default)]
struct Mailbox {
    queue: VecDeque<Stored>,
    bytes: usize,
}

struct Keys {
    sign: String,
    dh: String,
}

/// Le dernier paquet de clés v3 publié par un code, et sa signature.
struct Bundle {
    bundle: String,
    sig: String,
}

struct Conn {
    id: u64,
    tx: mpsc::Sender<String>,
    hidden: bool,
    /// La file est pleine : la connexion se ferme.
    overflow: Arc<std::sync::atomic::AtomicBool>,
}

impl Conn {
    /// Met un message dans la file de la connexion ; pleine, elle est
    /// marquée pour être coupée.
    fn send(&self, text: String) {
        if self.tx.try_send(text).is_err() {
            self.overflow
                .store(true, std::sync::atomic::Ordering::Relaxed);
        }
    }
}

#[derive(Default)]
struct State {
    online: HashMap<String, Vec<Conn>>,
    keys: HashMap<String, Keys>,
    bundles: HashMap<String, Bundle>,
    seen: HashMap<String, u64>,
    mailboxes: HashMap<String, Mailbox>,
    total_bytes: usize,
    /// code suivi → connexions qui le suivent (code, identifiant de connexion).
    watchers: HashMap<String, HashSet<(String, u64)>>,
    /// code → ses adresses Web Push, la plus récente en dernier.
    push: HashMap<String, Vec<crate::push::Subscription>>,
    /// code → son dernier réveil.
    woke: HashMap<String, Instant>,
    /// Les réveils de la seconde en cours (tous codes confondus).
    wakes: Limit,
    /// Les connexions anonymes ouvertes.
    anon: usize,
    next_n: u64,
    next_conn: u64,
}

/// Au plus `SENDS_PER_SECOND` envois par seconde et `BYTES_PER_MINUTE`
/// octets par minute, par connexion.
struct Limit {
    window: Instant,
    sends: u32,
    minute: Instant,
    bytes: usize,
}

impl Default for Limit {
    fn default() -> Self {
        Limit {
            window: Instant::now(),
            sends: 0,
            minute: Instant::now(),
            bytes: 0,
        }
    }
}

impl Limit {
    fn allow(&mut self) -> bool {
        self.allow_n(SENDS_PER_SECOND)
    }

    fn allow_n(&mut self, per_second: u32) -> bool {
        if self.window.elapsed() >= Duration::from_secs(1) {
            self.window = Instant::now();
            self.sends = 0;
        }
        self.sends += 1;
        self.sends <= per_second
    }

    fn allow_bytes(&mut self, n: usize) -> bool {
        if self.minute.elapsed() >= Duration::from_secs(60) {
            self.minute = Instant::now();
            self.bytes = 0;
        }
        self.bytes += n;
        self.bytes <= BYTES_PER_MINUTE
    }
}

/// Un message livré : avec son expéditeur, ou scellé (`"sealed":true`,
/// l'expéditeur est dans le corps, que seul le destinataire ouvre).
fn envelope(from: Option<&str>, mut msg: Value) -> String {
    match from {
        Some(from) => msg["from"] = json!(from),
        None => msg["sealed"] = json!(true),
    }
    msg.to_string()
}

/// L'identifiant de ce démarrage du relais. Les boîtes aux lettres ne vivent
/// qu'en mémoire : quand il change (redémarrage, nouveau déploiement), ce qui
/// attendait un destinataire hors ligne est perdu, et les appareils renvoient
/// les messages qui n'ont pas été confirmés (`ready.boot`).
pub fn boot_id() -> &'static str {
    static BOOT: std::sync::OnceLock<String> = std::sync::OnceLock::new();
    BOOT.get_or_init(|| {
        let mut bytes = [0u8; 9];
        let _ = getrandom::getrandom(&mut bytes);
        B64.encode(bytes)
    })
}

/// Le relais, partagé par toutes les connexions (`app.state(Relay::default())`).
#[derive(Clone, Default)]
pub struct Relay {
    state: Arc<Mutex<State>>,
}

impl State {
    fn is_visible(&self, code: &str) -> bool {
        self.online
            .get(code)
            .is_some_and(|conns| conns.iter().any(|c| !c.hidden))
    }

    fn presence(&self, code: &str) -> String {
        json!({
            "t": "presence",
            "code": code,
            "online": self.is_visible(code),
            "seen": self.seen.get(code),
        })
        .to_string()
    }

    fn notify_watchers(&mut self, code: &str) {
        let msg = self.presence(code);
        let Some(watchers) = self.watchers.get(code) else {
            return;
        };
        for (watcher, conn_id) in watchers {
            if let Some(conns) = self.online.get(watcher) {
                for conn in conns.iter().filter(|c| c.id == *conn_id) {
                    conn.send(msg.clone());
                }
            }
        }
    }

    fn evict_expired(&mut self) {
        let mut freed = 0;
        self.mailboxes.retain(|_, mb| {
            while mb
                .queue
                .front()
                .is_some_and(|s| s.at.elapsed() > MAILBOX_TTL)
            {
                let s = mb.queue.pop_front().expect("front exists");
                mb.bytes -= s.json.len();
                freed += s.json.len();
            }
            !mb.queue.is_empty()
        });
        self.total_bytes -= freed;
    }
}

impl Relay {
    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Le nombre de codes connectés (pour `/api/stats`).
    pub fn online_count(&self) -> usize {
        self.lock().online.len()
    }

    /// Le nombre de messages qui attendent leur destinataire.
    pub fn waiting_count(&self) -> usize {
        self.lock().mailboxes.values().map(|m| m.queue.len()).sum()
    }

    /// Ce code est-il déjà lié à d'autres clés ? (Quelqu'un qui aurait
    /// trouvé d'autres clés au même code de 50 bits ne prend pas sa place.)
    fn taken(&self, code: &str, sign: &str, dh: &str) -> bool {
        self.lock()
            .keys
            .get(code)
            .is_some_and(|k| k.sign != sign || k.dh != dh)
    }

    fn register(
        &self,
        code: &str,
        sign: String,
        dh: String,
        hidden: bool,
    ) -> (
        u64,
        mpsc::Receiver<String>,
        Arc<std::sync::atomic::AtomicBool>,
    ) {
        let (tx, rx) = mpsc::channel(OUT_QUEUE);
        let overflow = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let mut st = self.lock();
        st.next_conn += 1;
        let id = st.next_conn;
        if st.keys.len() >= MAX_KEYS && !st.keys.contains_key(code) {
            // Les codes déconnectés depuis le plus longtemps cèdent leur place.
            let mut oldest: Vec<(String, u64)> = st
                .keys
                .keys()
                .filter(|c| !st.online.contains_key(*c))
                .map(|c| (c.clone(), st.seen.get(c).copied().unwrap_or(0)))
                .collect();
            oldest.sort_by_key(|(_, seen)| *seen);
            for (c, _) in oldest.into_iter().take(MAX_KEYS / 10) {
                st.keys.remove(&c);
                st.bundles.remove(&c);
                st.seen.remove(&c);
                st.push.remove(&c);
                st.woke.remove(&c);
            }
        }
        st.keys.insert(code.to_owned(), Keys { sign, dh });
        // Un appareil v3 republie son paquet aussitôt connecté ; un appareil
        // qui ne le fait pas (une ancienne version avec la même identité)
        // ne doit pas recevoir de jetons v3 qu'il ne saurait pas lire.
        st.bundles.remove(code);
        let was_visible = st.is_visible(code);
        let conn = Conn {
            id,
            tx,
            hidden,
            overflow: overflow.clone(),
        };
        // Masqué : on ne garde pas non plus l'heure de sa dernière connexion.
        if hidden {
            st.seen.remove(code);
        }
        if !was_visible && !hidden {
            st.notify_watchers(code);
        }
        // Les messages en attente partent tout de suite.
        st.evict_expired();
        if let Some(mb) = st.mailboxes.get(code) {
            for s in &mb.queue {
                conn.send(s.json.clone());
            }
        }
        st.online.entry(code.to_owned()).or_default().push(conn);
        (id, rx, overflow)
    }

    fn unregister(&self, code: &str, id: u64) {
        let mut st = self.lock();
        let was_visible = st.is_visible(code);
        let mut was_hidden = false;
        if let Some(conns) = st.online.get_mut(code) {
            was_hidden = conns.iter().any(|c| c.id == id && c.hidden);
            conns.retain(|c| c.id != id);
            if conns.is_empty() {
                st.online.remove(code);
            }
        }
        st.watchers.retain(|_, set| {
            set.remove(&(code.to_owned(), id));
            !set.is_empty()
        });
        // « Vu à » : seulement pour qui ne se cache pas.
        if !st.online.contains_key(code) && !was_hidden {
            st.seen.insert(code.to_owned(), now_ms());
        }
        if was_visible && !st.is_visible(code) {
            st.notify_watchers(code);
        }
    }

    /// Dépose un message pour `to` : dans sa boîte, et tout de suite sur ses
    /// connexions ouvertes ; `wake` : le réveille s'il n'en a aucune. Renvoie
    /// (horodatage, destinataire en ligne).
    fn deliver(
        &self,
        from: Option<&str>,
        to: &str,
        id: &str,
        body: &str,
        live: bool,
        wake: Wake,
    ) -> Result<(u64, bool), &'static str> {
        // Sans clé VAPID, personne à réveiller.
        let wake = if crate::push::vapid().is_some() {
            wake
        } else {
            Wake::No
        };
        let (ts, online, subs) = self.store(from, to, id, body, live, &wake)?;
        if let Some(vapid) = crate::push::vapid() {
            // L'aperçu chiffré par l'expéditeur, que le navigateur ouvrira.
            let payload = match &wake {
                Wake::Apercu(a) => Some(json!({ "a": a }).to_string()),
                _ => None,
            };
            for sub in subs {
                let relay = self.clone();
                let to = to.to_owned();
                let payload = payload.clone();
                tokio::spawn(async move {
                    let endpoint = sub.endpoint.clone();
                    if crate::push::wake(vapid, sub, payload).await == crate::push::Outcome::Gone {
                        relay.unpush(&to, &endpoint);
                    }
                });
            }
        }
        Ok((ts, online))
    }

    fn store(
        &self,
        from: Option<&str>,
        to: &str,
        id: &str,
        body: &str,
        live: bool,
        wake: &Wake,
    ) -> Result<(u64, bool, Vec<crate::push::Subscription>), &'static str> {
        let ts = now_ms();
        let mut st = self.lock();
        let online = st.online.contains_key(to);
        if live {
            // Pas de boîte : seulement les connexions ouvertes.
            if let Some(conns) = st.online.get(to) {
                let json = envelope(
                    from,
                    json!({ "t": "msg", "id": id, "body": body, "ts": ts, "live": true }),
                );
                for conn in conns {
                    conn.send(json.clone());
                }
            }
            return Ok((ts, online, Vec::new()));
        }
        // Plus de place : refusé (l'expéditeur réessaiera), jamais en faisant
        // de la place avec les messages des autres.
        let room = |st: &State| {
            let mb = st.mailboxes.get(to);
            st.total_bytes + body.len() <= TOTAL_MAX_BYTES
                && mb.is_none_or(|mb| {
                    mb.queue.len() < MAILBOX_MAX_COUNT && mb.bytes + body.len() <= MAILBOX_MAX_BYTES
                })
        };
        if !room(&st) {
            st.evict_expired();
            if !room(&st) {
                return Err("full");
            }
        }
        st.next_n += 1;
        let n = st.next_n;
        let json = envelope(
            from,
            json!({ "t": "msg", "n": n, "id": id, "body": body, "ts": ts }),
        );
        if let Some(conns) = st.online.get(to) {
            for conn in conns {
                conn.send(json.clone());
            }
        }
        let size = json.len();
        let mb = st.mailboxes.entry(to.to_owned()).or_default();
        mb.queue.push_back(Stored {
            n,
            json,
            at: Instant::now(),
        });
        mb.bytes += size;
        st.total_bytes += size;
        let mut subs = Vec::new();
        if *wake != Wake::No && !online && st.push.contains_key(to) {
            // Un réveil vide toutes les 20 s au plus ; avec un aperçu, chaque
            // message compte (une seconde au moins entre deux).
            let every = if matches!(wake, Wake::Apercu(_)) {
                APERCU_EVERY
            } else {
                WAKE_EVERY
            };
            let recent = st.woke.get(to).is_some_and(|at| at.elapsed() < every);
            // Et pas plus de `WAKES_PER_SECOND` en tout : le relais ne sert
            // pas à bombarder les services de notifications.
            if !recent && st.wakes.allow_n(WAKES_PER_SECOND) {
                st.woke.insert(to.to_owned(), Instant::now());
                subs = st.push[to].clone();
            }
        }
        Ok((ts, online, subs))
    }

    /// L'abonnement Web Push d'un appareil de `code`.
    fn push(&self, code: &str, sub: crate::push::Subscription) -> bool {
        if crate::push::audience(&sub.endpoint).is_none() {
            return false;
        }
        let mut st = self.lock();
        let list = st.push.entry(code.to_owned()).or_default();
        list.retain(|e| e.endpoint != sub.endpoint);
        list.push(sub);
        if list.len() > MAX_PUSH {
            list.remove(0);
        }
        true
    }

    /// Oublie une adresse Web Push (désactivée, ou disparue).
    fn unpush(&self, code: &str, endpoint: &str) {
        let mut st = self.lock();
        if let Some(list) = st.push.get_mut(code) {
            list.retain(|e| e.endpoint != endpoint);
            if list.is_empty() {
                st.push.remove(code);
            }
        }
    }

    /// Le destinataire a rangé ces messages : ils quittent sa boîte.
    fn forget(&self, code: &str, numbers: &[u64]) {
        let mut st = self.lock();
        let Some(mb) = st.mailboxes.get_mut(code) else {
            return;
        };
        let numbers: HashSet<u64> = numbers.iter().copied().collect();
        let mut freed = 0;
        mb.queue.retain(|s| {
            let keep = !numbers.contains(&s.n);
            if !keep {
                freed += s.json.len();
            }
            keep
        });
        mb.bytes -= freed;
        if mb.queue.is_empty() {
            st.mailboxes.remove(code);
        }
        st.total_bytes -= freed;
    }

    fn lookup(&self, code: &str) -> Value {
        let st = self.lock();
        match st.keys.get(code) {
            Some(k) => json!({ "t": "keys", "code": code, "sign": k.sign, "dh": k.dh }),
            None => json!({ "t": "keys", "code": code, "unknown": true }),
        }
    }

    /// Garde le paquet de clés v3 de `code`, s'il est signé par sa clé
    /// (`sign`, brute) et pas trop gros. Il remplace le précédent.
    fn publish(
        &self,
        code: &str,
        sign: &[u8],
        bundle: &str,
        sig: &str,
    ) -> Result<(), &'static str> {
        if bundle.is_empty() || bundle.len() > MAX_BUNDLE.div_ceil(3) * 4 || sig.len() > 128 {
            return Err("size");
        }
        let bytes = B64.decode(bundle).map_err(|_| "size")?;
        if bytes.len() > MAX_BUNDLE {
            return Err("size");
        }
        if !code::verify(sign, &code::bundle_message(&bytes), sig) {
            return Err("signature");
        }
        let mut st = self.lock();
        if st.keys.contains_key(code) {
            st.bundles.insert(
                code.to_owned(),
                Bundle {
                    bundle: bundle.to_owned(),
                    sig: sig.to_owned(),
                },
            );
        }
        Ok(())
    }

    fn bundle(&self, code: &str) -> Value {
        let st = self.lock();
        match (st.keys.get(code), st.bundles.get(code)) {
            (Some(k), Some(b)) => json!({
                "t": "bundle",
                "code": code,
                "sign": k.sign,
                "dh": k.dh,
                "bundle": b.bundle,
                "sig": b.sig,
            }),
            _ => json!({ "t": "bundle", "code": code, "none": true }),
        }
    }

    fn watch(&self, watcher: &str, conn_id: u64, codes: &[String]) -> Vec<String> {
        let mut st = self.lock();
        // `MAX_WATCH` codes suivis par connexion, en tout.
        let me = (watcher.to_owned(), conn_id);
        let already = st.watchers.values().filter(|s| s.contains(&me)).count();
        codes
            .iter()
            .take(MAX_WATCH.saturating_sub(already))
            .filter_map(|c| code::normalize(c))
            .map(|c| {
                st.watchers
                    .entry(c.clone())
                    .or_default()
                    .insert((watcher.to_owned(), conn_id));
                st.presence(&c)
            })
            .collect()
    }

    /// Un envoi : vérifié, puis déposé. `from` : l'expéditeur d'une connexion
    /// présentée ; `None` : une connexion anonyme (expéditeur scellé), qui
    /// doit montrer le jeton de remise du destinataire. Renvoie `ack` ou `error`.
    fn send(&self, from: Option<&str>, msg: &Value, limit: &mut Limit) -> Value {
        let id = msg["id"].as_str().unwrap_or("").to_owned();
        let body = msg["body"].as_str().unwrap_or("");
        let to = msg["to"].as_str().and_then(code::normalize);
        let field = |name: &str| msg[name].as_str().unwrap_or("");
        let error = if !limit.allow() || !limit.allow_bytes(body.len()) {
            Some("rate")
        } else if id.is_empty() || id.len() > 64 {
            Some("id")
        } else if body.is_empty() || body.len() > MAX_BODY {
            Some("size")
        } else if to.is_none() {
            Some("code")
        } else if from.is_none()
            && !code::verify_jeton(
                to.as_deref().unwrap_or(""),
                field("sign"),
                field("dh"),
                field("jeton"),
            )
        {
            Some("jeton")
        } else {
            None
        };
        if let Some(error) = error {
            return json!({ "t": "error", "id": id, "error": error });
        }
        let to = to.expect("checked above");
        let live = msg["live"].as_bool().unwrap_or(false);
        // L'aperçu, chiffré par l'expéditeur pour le destinataire (illisible ici).
        let apercu = msg["apercu"]
            .as_str()
            .filter(|a| !a.is_empty() && a.len() <= crate::push::MAX_APERCU);
        let wake = match (msg["wake"].as_bool().unwrap_or(false), apercu) {
            (false, _) => Wake::No,
            (true, None) => Wake::Empty,
            (true, Some(a)) => Wake::Apercu(a.to_owned()),
        };
        match self.deliver(from, &to, &id, body, live, wake) {
            Ok((ts, online)) => json!({ "t": "ack", "id": id, "ts": ts, "online": online }),
            Err(error) => json!({ "t": "error", "id": id, "error": error }),
        }
    }

    /// Une connexion anonyme : seulement des envois scellés (et `ping`).
    /// Le relais ne sait pas qui est derrière ; il ne la compte pas comme
    /// une présence.
    async fn serve_anon(
        self,
        mut tx: paquets::vitesse::ws::WebSocketSender,
        mut rx: paquets::vitesse::ws::WebSocketReceiver,
    ) {
        let admitted = {
            let mut st = self.lock();
            let ok = st.anon < MAX_ANON;
            if ok {
                st.anon += 1;
            }
            ok
        };
        if !admitted {
            let _ = tx.close(4003, "busy").await;
            return;
        }
        self.anon_loop(&mut tx, &mut rx).await;
        self.lock().anon -= 1;
    }

    async fn anon_loop(
        &self,
        tx: &mut paquets::vitesse::ws::WebSocketSender,
        rx: &mut paquets::vitesse::ws::WebSocketReceiver,
    ) {
        let ready = json!({ "t": "ready", "anon": true, "time": now_ms(), "boot": boot_id() });
        if tx.send(ready.to_string()).await.is_err() {
            return;
        }
        let mut limit = Limit::default();
        let mut ping = tokio::time::interval(PING_EVERY);
        ping.tick().await;
        // Fermée au bout de `ANON_IDLE` sans envoi.
        let mut last = Instant::now();
        loop {
            if last.elapsed() > ANON_IDLE {
                break;
            }
            tokio::select! {
                next = rx.recv() => {
                    let text = match next {
                        Some(Ok(Message::Text(text))) => text,
                        Some(Ok(Message::Close(_))) | None | Some(Err(_)) => break,
                        Some(Ok(_)) => continue,
                    };
                    let Ok(msg) = serde_json::from_str::<Value>(&text) else {
                        continue;
                    };
                    let answer = match msg["t"].as_str().unwrap_or("") {
                        "send" => {
                            last = Instant::now();
                            self.send(None, &msg, &mut limit)
                        }
                        "ping" if limit.allow() => json!({ "t": "pong", "time": now_ms() }),
                        _ => continue,
                    };
                    if tx.send(answer.to_string()).await.is_err() {
                        break;
                    }
                }
                _ = ping.tick() => {
                    if tx.send(Message::Ping(Default::default())).await.is_err() {
                        break;
                    }
                }
            }
        }
    }

    /// Une connexion : la présentation, puis la boucle des messages.
    pub async fn serve(self, socket: WebSocket) {
        let (mut tx, mut rx) = socket.split();
        let nonce = {
            let mut bytes = [0u8; 24];
            if getrandom::getrandom(&mut bytes).is_err() {
                return;
            }
            B64.encode(bytes)
        };
        let challenge = json!({ "t": "challenge", "nonce": nonce, "time": now_ms() }).to_string();
        if tx.send(challenge).await.is_err() {
            return;
        }

        // La présentation : 20 secondes pour prouver qu'on détient la clé du code.
        let hello = tokio::time::timeout(Duration::from_secs(20), async {
            while let Some(Ok(message)) = rx.recv().await {
                match message {
                    Message::Text(text) => return serde_json::from_str::<Value>(&text).ok(),
                    Message::Close(_) => return None,
                    _ => {}
                }
            }
            None
        })
        .await
        .ok()
        .flatten();
        if hello.as_ref().is_some_and(|h| h["t"] == "anon") {
            return self.serve_anon(tx, rx).await;
        }
        let Some(hello) = hello.filter(|h| h["t"] == "hello") else {
            let _ = tx.close(4000, "hello").await;
            return;
        };
        let field = |name: &str| hello[name].as_str().unwrap_or("").to_owned();
        let (sign_b64, dh_b64) = (field("sign"), field("dh"));
        let (Some(sign), Some(dh)) = (code::public_key(&sign_b64), code::public_key(&dh_b64))
        else {
            let _ = tx.close(4001, "keys").await;
            return;
        };
        if !code::verify(&sign, &code::challenge_message(&nonce), &field("sig")) {
            let _ = tx.close(4002, "signature").await;
            return;
        }
        let me = code::derive(&sign, &dh);
        if self.taken(&me, &sign_b64, &dh_b64) {
            let _ = tx.close(4004, "code").await;
            return;
        }
        let hidden = hello["hidden"].as_bool().unwrap_or(false);
        let mut ready = json!({ "t": "ready", "code": me, "time": now_ms(), "boot": boot_id(), "sealed": true });
        if let Some(vapid) = crate::push::vapid() {
            ready["push"] = json!(vapid.public);
        }
        let ready = ready.to_string();
        if tx.send(ready).await.is_err() {
            return;
        }
        let (conn_id, mut outbox, overflow) = self.register(&me, sign_b64, dh_b64, hidden);

        // Ce qui part vers l'appareil : les messages, et un ping régulier.
        let writer = tokio::spawn(async move {
            let mut ping = tokio::time::interval(PING_EVERY);
            ping.tick().await;
            loop {
                tokio::select! {
                    next = outbox.recv() => {
                        let Some(text) = next else { break };
                        if tx.send(text).await.is_err() {
                            break;
                        }
                    }
                    _ = ping.tick() => {
                        if tx.send(Message::Ping(Default::default())).await.is_err() {
                            break;
                        }
                    }
                }
            }
        });
        let reply = {
            let relay = self.clone();
            let me = me.clone();
            move |text: String| {
                let st = relay.lock();
                if let Some(conns) = st.online.get(&me) {
                    for conn in conns.iter().filter(|c| c.id == conn_id) {
                        conn.send(text.clone());
                    }
                }
            }
        };

        let mut limit = Limit::default();
        // Les autres demandes (clés, paquets, présence…) : 60 par seconde.
        let mut asks = Limit::default();
        while let Some(Ok(message)) = rx.recv().await {
            // L'appareil ne lit plus ce qu'on lui envoie : on coupe.
            if overflow.load(std::sync::atomic::Ordering::Relaxed) {
                break;
            }
            let text = match message {
                Message::Text(text) => text,
                Message::Close(_) => break,
                _ => continue,
            };
            let Ok(msg) = serde_json::from_str::<Value>(&text) else {
                continue;
            };
            let kind = msg["t"].as_str().unwrap_or("");
            if kind != "send" && !asks.allow() {
                continue;
            }
            match kind {
                "send" => {
                    let answer = self.send(Some(&me), &msg, &mut limit);
                    reply(answer.to_string());
                }
                "got" => {
                    let numbers: Vec<u64> = msg["n"]
                        .as_array()
                        .map(|a| a.iter().filter_map(Value::as_u64).collect())
                        .unwrap_or_default();
                    self.forget(&me, &numbers);
                }
                "lookup" => {
                    let answer = match msg["code"].as_str().and_then(code::normalize) {
                        Some(code) => self.lookup(&code),
                        None => json!({ "t": "keys", "code": msg["code"], "unknown": true }),
                    };
                    reply(answer.to_string());
                }
                "watch" => {
                    let codes: Vec<String> = msg["codes"]
                        .as_array()
                        .map(|a| {
                            a.iter()
                                .filter_map(|v| v.as_str().map(str::to_owned))
                                .collect()
                        })
                        .unwrap_or_default();
                    for presence in self.watch(&me, conn_id, &codes) {
                        reply(presence);
                    }
                }
                "publish-bundle" => {
                    let answer = match self.publish(
                        &me,
                        &sign,
                        msg["bundle"].as_str().unwrap_or(""),
                        msg["sig"].as_str().unwrap_or(""),
                    ) {
                        Ok(()) => json!({ "t": "published", "ok": true }),
                        Err(error) => json!({ "t": "published", "ok": false, "error": error }),
                    };
                    reply(answer.to_string());
                }
                "fetch-bundle" => {
                    let answer = match msg["code"].as_str().and_then(code::normalize) {
                        Some(code) => self.bundle(&code),
                        None => json!({ "t": "bundle", "code": msg["code"], "none": true }),
                    };
                    reply(answer.to_string());
                }
                "push" => {
                    let endpoint = msg["endpoint"].as_str().unwrap_or("");
                    let key = |name: &str| {
                        let v = msg[name].as_str().unwrap_or("").trim_end_matches('=');
                        URL_B64.decode(v).unwrap_or_default()
                    };
                    let sub = crate::push::Subscription {
                        endpoint: endpoint.to_owned(),
                        p256dh: key("p256dh"),
                        auth: key("auth"),
                    };
                    let ok = if msg["on"].as_bool().unwrap_or(true) {
                        crate::push::vapid().is_some() && self.push(&me, sub)
                    } else {
                        self.unpush(&me, endpoint);
                        true
                    };
                    reply(json!({ "t": "push", "ok": ok }).to_string());
                }
                "ping" => reply(json!({ "t": "pong", "time": now_ms() }).to_string()),
                _ => {}
            }
        }
        self.unregister(&me, conn_id);
        writer.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const FCM: &str = "https://fcm.googleapis.com/fcm/send/";

    fn sub(endpoint: &str) -> crate::push::Subscription {
        crate::push::Subscription {
            endpoint: endpoint.to_owned(),
            p256dh: Vec::new(),
            auth: Vec::new(),
        }
    }

    #[test]
    fn push_addresses_are_checked_and_capped() {
        let relay = Relay::default();
        assert!(!relay.push("AAAAAAAAAA", sub("https://evil.example/x")));
        assert!(!relay.push("AAAAAAAAAA", sub("http://fcm.googleapis.com/fcm/send/x")));
        for i in 0..6 {
            assert!(relay.push("AAAAAAAAAA", sub(&format!("{FCM}{i}"))));
        }
        // La même adresse redonnée ne compte qu'une fois, et passe en dernier.
        assert!(relay.push("AAAAAAAAAA", sub(&format!("{FCM}3"))));
        let list: Vec<String> = relay.lock().push["AAAAAAAAAA"]
            .iter()
            .map(|s| s.endpoint.clone())
            .collect();
        let tail: Vec<String> = (2..6)
            .filter(|i| *i != 3)
            .map(|i| format!("{FCM}{i}"))
            .collect();
        assert_eq!(list.len(), MAX_PUSH);
        assert_eq!(list[..3], tail[..]);
        assert_eq!(list[3], format!("{FCM}3"));
        for e in list {
            relay.unpush("AAAAAAAAAA", &e);
        }
        assert!(!relay.lock().push.contains_key("AAAAAAAAAA"));
    }

    #[test]
    fn only_real_messages_wake_an_offline_code_now_and_then() {
        let relay = Relay::default();
        let to = "BBBBBBBBBB";
        relay.push(to, sub(&format!("{FCM}b")));
        // Un accusé, une réaction (`wake` absent) : pas de réveil.
        assert!(
            relay
                .store(Some("AAAAAAAAAA"), to, "r1", "x", false, &Wake::No)
                .unwrap()
                .2
                .is_empty()
        );
        // « écrit… », un signal d'appel (`live`) : jamais.
        assert!(
            relay
                .store(Some("AAAAAAAAAA"), to, "l1", "x", true, &Wake::Empty)
                .unwrap()
                .2
                .is_empty()
        );
        // Un vrai message : un réveil…
        assert_eq!(
            relay
                .store(Some("AAAAAAAAAA"), to, "m1", "x", false, &Wake::Empty)
                .unwrap()
                .2,
            vec![sub(&format!("{FCM}b"))]
        );
        // Avec un aperçu, chaque message compte : une seconde suffit.
        relay
            .lock()
            .woke
            .insert(to.to_owned(), Instant::now() - APERCU_EVERY);
        assert_eq!(
            relay
                .store(
                    Some("AAAAAAAAAA"),
                    to,
                    "m1b",
                    "x",
                    false,
                    &Wake::Apercu("a".into())
                )
                .unwrap()
                .2
                .len(),
            1
        );
        // … puis plus rien pendant 20 secondes.
        assert!(
            relay
                .store(Some("AAAAAAAAAA"), to, "m2", "x", false, &Wake::Empty)
                .unwrap()
                .2
                .is_empty()
        );
        relay
            .lock()
            .woke
            .insert(to.to_owned(), Instant::now() - WAKE_EVERY);
        assert_eq!(
            relay
                .store(Some("AAAAAAAAAA"), to, "m3", "x", false, &Wake::Empty)
                .unwrap()
                .2
                .len(),
            1
        );
        // Un code sans adresse : personne à réveiller.
        assert!(
            relay
                .store(
                    Some("AAAAAAAAAA"),
                    "CCCCCCCCCC",
                    "m4",
                    "x",
                    false,
                    &Wake::Empty
                )
                .unwrap()
                .2
                .is_empty()
        );
    }

    #[test]
    fn a_connected_code_is_not_woken() {
        let relay = Relay::default();
        let to = "BBBBBBBBBB";
        relay.push(to, sub(&format!("{FCM}b")));
        let (_id, _rx, _) = relay.register(to, String::new(), String::new(), true);
        assert!(
            relay
                .store(Some("AAAAAAAAAA"), to, "m1", "x", false, &Wake::Empty)
                .unwrap()
                .2
                .is_empty()
        );
    }

    /// Une boîte pleine refuse (l'expéditeur réessaiera) ; elle ne jette
    /// jamais les messages qui attendent, ni ceux des autres.
    #[test]
    fn a_full_mailbox_refuses_instead_of_dropping() {
        let relay = Relay::default();
        let to = "BBBBBBBBBB";
        for i in 0..MAILBOX_MAX_COUNT {
            assert!(
                relay
                    .store(None, to, &format!("m{i}"), "x", false, &Wake::No)
                    .is_ok()
            );
        }
        assert_eq!(
            relay.store(None, to, "trop", "x", false, &Wake::No).err(),
            Some("full")
        );
        assert_eq!(relay.lock().mailboxes[to].queue.len(), MAILBOX_MAX_COUNT);
        // Les autres boîtes, elles, reçoivent toujours.
        assert!(
            relay
                .store(None, "CCCCCCCCCC", "m", "x", false, &Wake::No)
                .is_ok()
        );
        // Toutes les boîtes ensemble : pareil.
        relay.lock().total_bytes = TOTAL_MAX_BYTES;
        assert_eq!(
            relay
                .store(None, "DDDDDDDDDD", "m", "x", false, &Wake::No)
                .err(),
            Some("full")
        );
    }

    /// Un code déjà lié à des clés ne passe pas à d'autres.
    #[test]
    fn a_code_keeps_its_keys() {
        let relay = Relay::default();
        let (_id, _rx, _) = relay.register("BBBBBBBBBB", "s1".into(), "d1".into(), false);
        assert!(!relay.taken("BBBBBBBBBB", "s1", "d1"));
        assert!(relay.taken("BBBBBBBBBB", "s2", "d1"));
        assert!(relay.taken("BBBBBBBBBB", "s1", "d2"));
        assert!(!relay.taken("CCCCCCCCCC", "s2", "d2"));
    }

    /// Masqué : pas d'heure de dernière connexion pour les autres.
    #[test]
    fn hidden_codes_leave_no_last_seen() {
        let relay = Relay::default();
        let (id, _rx, _) = relay.register("BBBBBBBBBB", "s".into(), "d".into(), true);
        relay.unregister("BBBBBBBBBB", id);
        assert!(!relay.lock().seen.contains_key("BBBBBBBBBB"));
        let (id, _rx, _) = relay.register("BBBBBBBBBB", "s".into(), "d".into(), false);
        relay.unregister("BBBBBBBBBB", id);
        assert!(relay.lock().seen.contains_key("BBBBBBBBBB"));
    }

    /// Un appareil qui ne lit plus : sa file se remplit, la connexion est
    /// marquée pour être coupée (la mémoire du relais ne grossit pas).
    #[test]
    fn a_stalled_connection_is_cut() {
        let relay = Relay::default();
        let (id, _rx, overflow) = relay.register("BBBBBBBBBB", "s".into(), "d".into(), false);
        let st = relay.lock();
        let conn = st.online["BBBBBBBBBB"].iter().find(|c| c.id == id).unwrap();
        for _ in 0..OUT_QUEUE {
            conn.send("x".into());
        }
        assert!(!overflow.load(std::sync::atomic::Ordering::Relaxed));
        conn.send("x".into());
        assert!(overflow.load(std::sync::atomic::Ordering::Relaxed));
    }

    /// Les réveils, tous codes confondus, sont plafonnés.
    #[test]
    fn wakes_are_capped_overall() {
        let relay = Relay::default();
        let mut woken = 0;
        for i in 0..200 {
            let to = format!("B{i:09}");
            relay.push(&to, sub(&format!("{FCM}{i}")));
            woken += relay
                .store(None, &to, "m", "x", false, &Wake::Empty)
                .unwrap()
                .2
                .len();
        }
        assert_eq!(woken, WAKES_PER_SECOND as usize);
    }
}
