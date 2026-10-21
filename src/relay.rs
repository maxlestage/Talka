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
//! | → | `{"t":"send","id","to","body","live","wake"}` | `live` : seulement si le destinataire est en ligne (saisie, appels) ; `wake` : un vrai message, qui réveille un destinataire hors ligne (voir `push`) |
//! | ← | `{"t":"ack","id","ts","online"}` | le message est arrivé au serveur |
//! | ← | `{"t":"msg","n","id","from","body","ts"}` | un message reçu |
//! | → | `{"t":"got","n":[…]}` | rangé : le serveur l'oublie |
//! | → | `{"t":"lookup","code"}` | ← `{"t":"keys","code","sign","dh"}` (ou `"unknown":true`) |
//! | → | `{"t":"watch","codes":[…]}` | ← `{"t":"presence","code","online","seen"}`, maintenant et à chaque changement |
//! | ← | `{"t":"error","id","error"}` | un envoi refusé |
//! | → | `{"t":"publish-bundle","bundle","sig"}` | le paquet de clés v3 (base64), signé par la clé du code ; il remplace le précédent |
//! | ← | `{"t":"published","ok"}` | et `"error":"size"` ou `"signature"` s'il est refusé |
//! | → | `{"t":"fetch-bundle","code"}` | ← `{"t":"bundle","code","sign","dh","bundle","sig"}` (ou `"none":true`) |
//! | → | `{"t":"push","endpoint","on"}` | l'adresse Web Push de cet appareil (`"on":false` pour l'oublier) ; ← `{"t":"push","ok"}` |
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
/// Au plus 60 envois par seconde et par connexion.
const SENDS_PER_SECOND: u32 = 60;
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
    tx: mpsc::UnboundedSender<String>,
    hidden: bool,
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
    push: HashMap<String, Vec<String>>,
    /// code → son dernier réveil.
    woke: HashMap<String, Instant>,
    next_n: u64,
    next_conn: u64,
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
                    let _ = conn.tx.send(msg.clone());
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
        // Plus de place : les plus vieux messages, toutes boîtes confondues.
        while self.total_bytes > TOTAL_MAX_BYTES {
            let Some(code) = self
                .mailboxes
                .iter()
                .filter_map(|(code, mb)| mb.queue.front().map(|s| (code, s.at)))
                .min_by_key(|(_, at)| *at)
                .map(|(code, _)| code.clone())
            else {
                break;
            };
            let mb = self.mailboxes.get_mut(&code).expect("mailbox exists");
            if let Some(s) = mb.queue.pop_front() {
                mb.bytes -= s.json.len();
                self.total_bytes -= s.json.len();
            }
            if mb.queue.is_empty() {
                self.mailboxes.remove(&code);
            }
        }
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

    fn register(
        &self,
        code: &str,
        sign: String,
        dh: String,
        hidden: bool,
    ) -> (u64, mpsc::UnboundedReceiver<String>) {
        let (tx, rx) = mpsc::unbounded_channel();
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
        st.online.entry(code.to_owned()).or_default().push(Conn {
            id,
            tx: tx.clone(),
            hidden,
        });
        if !was_visible && !hidden {
            st.notify_watchers(code);
        }
        // Les messages en attente partent tout de suite.
        st.evict_expired();
        if let Some(mb) = st.mailboxes.get(code) {
            for s in &mb.queue {
                let _ = tx.send(s.json.clone());
            }
        }
        (id, rx)
    }

    fn unregister(&self, code: &str, id: u64) {
        let mut st = self.lock();
        let was_visible = st.is_visible(code);
        if let Some(conns) = st.online.get_mut(code) {
            conns.retain(|c| c.id != id);
            if conns.is_empty() {
                st.online.remove(code);
            }
        }
        st.watchers.retain(|_, set| {
            set.remove(&(code.to_owned(), id));
            !set.is_empty()
        });
        if !st.online.contains_key(code) {
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
        from: &str,
        to: &str,
        id: &str,
        body: &str,
        live: bool,
        wake: bool,
    ) -> (u64, bool) {
        // Sans clé VAPID, personne à réveiller.
        let wake = wake && crate::push::vapid().is_some();
        let (ts, online, endpoints) = self.store(from, to, id, body, live, wake);
        if let Some(vapid) = crate::push::vapid() {
            for endpoint in endpoints {
                let relay = self.clone();
                let to = to.to_owned();
                tokio::spawn(async move {
                    if crate::push::wake(vapid, endpoint.clone()).await
                        == crate::push::Outcome::Gone
                    {
                        relay.unpush(&to, &endpoint);
                    }
                });
            }
        }
        (ts, online)
    }

    fn store(
        &self,
        from: &str,
        to: &str,
        id: &str,
        body: &str,
        live: bool,
        wake: bool,
    ) -> (u64, bool, Vec<String>) {
        let ts = now_ms();
        let mut st = self.lock();
        let online = st.online.contains_key(to);
        if live {
            // Pas de boîte : seulement les connexions ouvertes.
            if let Some(conns) = st.online.get(to) {
                let json = json!({ "t": "msg", "id": id, "from": from, "body": body, "ts": ts, "live": true }).to_string();
                for conn in conns {
                    let _ = conn.tx.send(json.clone());
                }
            }
            return (ts, online, Vec::new());
        }
        st.next_n += 1;
        let n = st.next_n;
        let json = json!({ "t": "msg", "n": n, "id": id, "from": from, "body": body, "ts": ts })
            .to_string();
        if let Some(conns) = st.online.get(to) {
            for conn in conns {
                let _ = conn.tx.send(json.clone());
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
        let mut freed = 0;
        while mb.queue.len() > MAILBOX_MAX_COUNT || mb.bytes > MAILBOX_MAX_BYTES {
            let Some(s) = mb.queue.pop_front() else { break };
            mb.bytes -= s.json.len();
            freed += s.json.len();
        }
        st.total_bytes = st.total_bytes + size - freed;
        if st.total_bytes > TOTAL_MAX_BYTES {
            st.evict_expired();
        }
        let mut endpoints = Vec::new();
        if wake && !online && st.push.contains_key(to) {
            let recent = st.woke.get(to).is_some_and(|at| at.elapsed() < WAKE_EVERY);
            if !recent {
                st.woke.insert(to.to_owned(), Instant::now());
                endpoints = st.push[to].clone();
            }
        }
        (ts, online, endpoints)
    }

    /// L'adresse Web Push d'un appareil de `code`.
    fn push(&self, code: &str, endpoint: &str) -> bool {
        if crate::push::audience(endpoint).is_none() {
            return false;
        }
        let mut st = self.lock();
        let list = st.push.entry(code.to_owned()).or_default();
        list.retain(|e| e != endpoint);
        list.push(endpoint.to_owned());
        if list.len() > MAX_PUSH {
            list.remove(0);
        }
        true
    }

    /// Oublie une adresse Web Push (désactivée, ou disparue).
    fn unpush(&self, code: &str, endpoint: &str) {
        let mut st = self.lock();
        if let Some(list) = st.push.get_mut(code) {
            list.retain(|e| e != endpoint);
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
        codes
            .iter()
            .take(MAX_WATCH)
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
        let hidden = hello["hidden"].as_bool().unwrap_or(false);
        let mut ready = json!({ "t": "ready", "code": me, "time": now_ms(), "boot": boot_id() });
        if let Some(vapid) = crate::push::vapid() {
            ready["push"] = json!(vapid.public);
        }
        let ready = ready.to_string();
        if tx.send(ready).await.is_err() {
            return;
        }
        let (conn_id, mut outbox) = self.register(&me, sign_b64, dh_b64, hidden);

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
                        let _ = conn.tx.send(text.clone());
                    }
                }
            }
        };

        let mut window = Instant::now();
        let mut sends = 0u32;
        while let Some(Ok(message)) = rx.recv().await {
            let text = match message {
                Message::Text(text) => text,
                Message::Close(_) => break,
                _ => continue,
            };
            let Ok(msg) = serde_json::from_str::<Value>(&text) else {
                continue;
            };
            match msg["t"].as_str().unwrap_or("") {
                "send" => {
                    if window.elapsed() >= Duration::from_secs(1) {
                        window = Instant::now();
                        sends = 0;
                    }
                    sends += 1;
                    let id = msg["id"].as_str().unwrap_or("").to_owned();
                    let body = msg["body"].as_str().unwrap_or("");
                    let to = msg["to"].as_str().and_then(code::normalize);
                    let error = if sends > SENDS_PER_SECOND {
                        Some("rate")
                    } else if id.is_empty() || id.len() > 64 {
                        Some("id")
                    } else if body.is_empty() || body.len() > MAX_BODY {
                        Some("size")
                    } else if to.is_none() {
                        Some("code")
                    } else {
                        None
                    };
                    if let Some(error) = error {
                        reply(json!({ "t": "error", "id": id, "error": error }).to_string());
                        continue;
                    }
                    let to = to.expect("checked above");
                    let live = msg["live"].as_bool().unwrap_or(false);
                    let wake = msg["wake"].as_bool().unwrap_or(false);
                    let (ts, online) = self.deliver(&me, &to, &id, body, live, wake);
                    reply(json!({ "t": "ack", "id": id, "ts": ts, "online": online }).to_string());
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
                    let ok = if msg["on"].as_bool().unwrap_or(true) {
                        crate::push::vapid().is_some() && self.push(&me, endpoint)
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

    #[test]
    fn push_addresses_are_checked_and_capped() {
        let relay = Relay::default();
        assert!(!relay.push("AAAAAAAAAA", "https://evil.example/x"));
        assert!(!relay.push("AAAAAAAAAA", "http://fcm.googleapis.com/fcm/send/x"));
        for i in 0..6 {
            assert!(relay.push("AAAAAAAAAA", &format!("{FCM}{i}")));
        }
        // La même adresse redonnée ne compte qu'une fois, et passe en dernier.
        assert!(relay.push("AAAAAAAAAA", &format!("{FCM}3")));
        let list = relay.lock().push["AAAAAAAAAA"].clone();
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
        relay.push(to, &format!("{FCM}b"));
        // Un accusé, une réaction (`wake` absent) : pas de réveil.
        assert!(
            relay
                .store("AAAAAAAAAA", to, "r1", "x", false, false)
                .2
                .is_empty()
        );
        // « écrit… », un signal d'appel (`live`) : jamais.
        assert!(
            relay
                .store("AAAAAAAAAA", to, "l1", "x", true, true)
                .2
                .is_empty()
        );
        // Un vrai message : un réveil…
        assert_eq!(
            relay.store("AAAAAAAAAA", to, "m1", "x", false, true).2,
            vec![format!("{FCM}b")]
        );
        // … puis plus rien pendant 20 secondes.
        assert!(
            relay
                .store("AAAAAAAAAA", to, "m2", "x", false, true)
                .2
                .is_empty()
        );
        relay
            .lock()
            .woke
            .insert(to.to_owned(), Instant::now() - WAKE_EVERY);
        assert_eq!(
            relay
                .store("AAAAAAAAAA", to, "m3", "x", false, true)
                .2
                .len(),
            1
        );
        // Un code sans adresse : personne à réveiller.
        assert!(
            relay
                .store("AAAAAAAAAA", "CCCCCCCCCC", "m4", "x", false, true)
                .2
                .is_empty()
        );
    }

    #[test]
    fn a_connected_code_is_not_woken() {
        let relay = Relay::default();
        let to = "BBBBBBBBBB";
        relay.push(to, &format!("{FCM}b"));
        let (_id, _rx) = relay.register(to, String::new(), String::new(), true);
        assert!(
            relay
                .store("AAAAAAAAAA", to, "m1", "x", false, true)
                .2
                .is_empty()
        );
    }
}
