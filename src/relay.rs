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
//! | ← | `{"t":"ready","code","time"}` | puis chaque message en attente |
//! | → | `{"t":"send","id","to","body","live"}` | `live` : seulement si le destinataire est en ligne (saisie, appels) |
//! | ← | `{"t":"ack","id","ts","online"}` | le message est arrivé au serveur |
//! | ← | `{"t":"msg","n","id","from","body","ts"}` | un message reçu |
//! | → | `{"t":"got","n":[…]}` | rangé : le serveur l'oublie |
//! | → | `{"t":"lookup","code"}` | ← `{"t":"keys","code","sign","dh"}` (ou `"unknown":true`) |
//! | → | `{"t":"watch","codes":[…]}` | ← `{"t":"presence","code","online","seen"}`, maintenant et à chaque changement |
//! | ← | `{"t":"error","id","error"}` | un envoi refusé |

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

struct Conn {
    id: u64,
    tx: mpsc::UnboundedSender<String>,
    hidden: bool,
}

#[derive(Default)]
struct State {
    online: HashMap<String, Vec<Conn>>,
    keys: HashMap<String, Keys>,
    seen: HashMap<String, u64>,
    mailboxes: HashMap<String, Mailbox>,
    total_bytes: usize,
    /// code suivi → connexions qui le suivent (code, identifiant de connexion).
    watchers: HashMap<String, HashSet<(String, u64)>>,
    next_n: u64,
    next_conn: u64,
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
                st.seen.remove(&c);
            }
        }
        st.keys.insert(code.to_owned(), Keys { sign, dh });
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
    /// connexions ouvertes. Renvoie (horodatage, destinataire en ligne).
    fn deliver(&self, from: &str, to: &str, id: &str, body: &str, live: bool) -> (u64, bool) {
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
            return (ts, online);
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
        (ts, online)
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
        let ready = json!({ "t": "ready", "code": me, "time": now_ms() }).to_string();
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
                    let (ts, online) = self.deliver(&me, &to, &id, body, live);
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
                "ping" => reply(json!({ "t": "pong", "time": now_ms() }).to_string()),
                _ => {}
            }
        }
        self.unregister(&me, conn_id);
        writer.abort();
    }
}
