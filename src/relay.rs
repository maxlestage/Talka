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
//! | → | `{"t":"revoke","nonces":[…]}` | les nonces des jetons de remise révoqués par ce code (contacts bloqués) ; la liste remplace la précédente |
//! | → | `hello` avec `"dev"` | (v4) un appareil du compte : ses messages ont leur boîte (`<code>/<dev>`) ; un envoi peut viser un appareil (`"dev"` dans `send`) ; ce qui est livré dit de quel appareil il vient (`"fd"`) |
//! | → | `{"t":"publish-bundle","bundle","sig","dev"}` | (v4) le paquet de cet appareil, signé avec `talka-v4-appareil:<dev>:` ; `fetch-bundle` les rend tous (`"devices":[{"dev","bundle","sig"}]`) |
//! | → | `{"t":"unlink","dev"}` | (v4) retire un appareil du compte : son paquet, sa boîte, ses connexions ; son `hello` reçoit `{"t":"unlinked"}` puis est refusé (4006) |
//! | → (anonyme) | `{"t":"send","id","boite","body","live","wake","apercu","ttl"}` | (v4) un dépôt dans une boîte aux lettres tournante (ou de liaison) : ni code, ni jeton ; `ttl` : secondes de garde (30 jours au plus) |
//! | → (anonyme) | `{"t":"boites","list":[…],"push"}` | (v4) s'abonne à des boîtes : ← ce qui attend (`{"t":"msg","n","id","boite","body","ts","sealed":true}`), puis ce qui arrive ; `push` : l'abonnement Web Push à réveiller pour elles ; `got` les oublie |
//!
//! Une boîte (ou toutes ensemble) pleine refuse un envoi (`"error":"full"`) ;
//! une adresse IP n'ouvre pas plus de `MAX_PER_IP` connexions à la fois.
//!
//! L'expéditeur scellé (voir `docs/SECURITE.md`, §7.9) : sur une connexion
//! anonyme, le relais ne sait pas qui écrit ; il vérifie seulement que
//! l'expéditeur détient un jeton de remise du destinataire (un par contact,
//! révocable) et qu'il n'est pas révoqué, et livre sans `from`. `ready.sealed` annonce
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
//! nouvelle connexion du même appareil (un appareil v3 le republie
//! aussitôt), et gardé quand il se déconnecte.
//!
//! Plusieurs appareils (v4, `docs/SECURITE.md` §7.11) : chacun a sa boîte et
//! son paquet ; un envoi sans `dev` (un ancien client) est copié dans la
//! boîte de chaque appareil connu du code. Les boîtes aux lettres tournantes
//! (§7.13) ne sont liées à aucun code : seuls l'expéditeur et le
//! destinataire savent les calculer.

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
/// Un envoi : 8 Mio au plus (une photo, un vocal, un fichier de 3 Mo, même
/// scellé).
pub const MAX_BODY: usize = 8 * 1024 * 1024;
/// Au plus 60 envois (et 60 autres demandes) par seconde et par connexion.
const SENDS_PER_SECOND: u32 = 60;
/// Et au plus 64 Mio envoyés par minute et par connexion.
const BYTES_PER_MINUTE: usize = 64 * 1024 * 1024;
/// Ce qui attend de partir vers un appareil : au-delà (il ne lit plus), la
/// connexion est coupée plutôt que de remplir la mémoire du relais.
const OUT_QUEUE: usize = 4_096;
/// Les connexions anonymes ouvertes en même temps, et leur durée sans envoi.
const MAX_ANON: usize = 20_000;
/// Les connexions ouvertes depuis une même adresse IP (un /64 en IPv6) :
/// assez pour un grand réseau derrière une seule adresse, pas pour remplir
/// le relais à soi seul.
const MAX_PER_IP: usize = 100;
/// Les jetons de remise révoqués qu'un code peut donner au relais.
const MAX_REVOKED: usize = 1_000;
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
/// Les appareils d'un compte (v4) : 6 au plus.
const MAX_DEVICES: usize = 6;
/// Les boîtes tournantes suivies par une connexion anonyme, et par demande.
const MAX_BOITES: usize = 20_000;
const MAX_BOITES_ASKED: usize = 8_000;
/// Les boîtes tournantes à réveiller (tous appareils), et pour combien de
/// temps (le jour, la veille et le lendemain suffisent).
const MAX_BOITE_PUSH: usize = 500_000;
const BOITE_PUSH_TTL: Duration = Duration::from_secs(3 * 24 * 3600);
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
    /// Combien de temps le garder (`MAILBOX_TTL` au plus).
    ttl: Duration,
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
    /// L'appareil (v4), vide pour un ancien client.
    dev: String,
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
    /// code → appareil (vide : le paquet d'avant la v4) → son paquet.
    bundles: HashMap<String, HashMap<String, Bundle>>,
    /// code → ses appareils connus depuis le démarrage (v4).
    devices: HashMap<String, HashSet<String>>,
    /// code → ses appareils retirés (`unlink`).
    banned: HashMap<String, HashSet<String>>,
    /// Les connexions anonymes qui suivent des boîtes tournantes.
    anon_conns: HashMap<u64, Conn>,
    /// boîte tournante → les connexions anonymes qui la suivent.
    boite_subs: HashMap<String, HashSet<u64>>,
    /// boîte tournante → l'abonnement Web Push à réveiller, et depuis quand.
    boite_push: HashMap<String, (Arc<crate::push::Subscription>, Instant)>,
    seen: HashMap<String, u64>,
    mailboxes: HashMap<String, Mailbox>,
    total_bytes: usize,
    /// code suivi → connexions qui le suivent (code, identifiant de connexion).
    watchers: HashMap<String, HashSet<(String, u64)>>,
    /// boîte (`<code>` ou `<code>/<dev>`) → ses adresses Web Push, la plus
    /// récente en dernier.
    push: HashMap<String, Vec<crate::push::Subscription>>,
    /// boîte → son dernier réveil.
    woke: HashMap<String, Instant>,
    /// Les réveils de la seconde en cours (tous codes confondus).
    wakes: Limit,
    /// Les connexions anonymes ouvertes.
    anon: usize,
    /// Les connexions ouvertes, par adresse IP.
    per_ip: HashMap<std::net::IpAddr, usize>,
    /// code → les nonces de ses jetons de remise révoqués (un contact
    /// bloqué) : redonnés par l'appareil à chaque connexion.
    revoked: HashMap<String, HashSet<String>>,
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

/// Un dépôt rangé : (horodatage, destinataire en ligne, les adresses Web Push
/// à réveiller avec leur boîte).
type Stored3 = (u64, bool, Vec<(String, crate::push::Subscription)>);

/// Qui envoie : une connexion présentée (son code, et son appareil en v4),
/// ou une connexion anonyme (`None`).
type Sender<'a> = Option<(&'a str, &'a str)>;

/// Un message livré : avec son expéditeur (et son appareil, `fd`), ou scellé
/// (`"sealed":true`, l'expéditeur est dans le corps, que seul le
/// destinataire ouvre).
fn envelope(from: Sender, mut msg: Value) -> String {
    match from {
        Some((from, fd)) => {
            msg["from"] = json!(from);
            if !fd.is_empty() {
                msg["fd"] = json!(fd);
            }
        }
        None => msg["sealed"] = json!(true),
    }
    msg.to_string()
}

/// La boîte d'un appareil (v4), ou celle du code.
fn box_key(code: &str, dev: &str) -> String {
    if dev.is_empty() {
        code.to_owned()
    } else {
        format!("{code}/{dev}")
    }
}

/// La boîte d'une boîte aux lettres tournante (aucun code ne commence par `@`).
fn boite_key(addr: &str) -> String {
    format!("@{addr}")
}

/// Où va un dépôt.
#[derive(Clone, Copy, Debug, PartialEq)]
enum Dest<'a> {
    /// Un code : tous ses appareils (un ancien client écrit ainsi).
    Code(&'a str),
    /// Un appareil d'un code (v4).
    Device(&'a str, &'a str),
    /// Une boîte tournante (v4), ou de liaison.
    Boite(&'a str),
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
            mb.queue.retain(|s| {
                let keep = s.at.elapsed() <= s.ttl;
                if !keep {
                    mb.bytes -= s.json.len();
                    freed += s.json.len();
                }
                keep
            });
            !mb.queue.is_empty()
        });
        self.total_bytes -= freed;
        self.boite_push
            .retain(|_, (_, at)| at.elapsed() < BOITE_PUSH_TTL);
    }

    /// Les boîtes où déposer pour `dest`, et les connexions à prévenir
    /// tout de suite (code, identifiant de connexion ; 0 pour une connexion
    /// anonyme, voir `anon_targets`).
    fn boxes(&self, dest: Dest) -> Vec<String> {
        match dest {
            Dest::Device(code, dev) => vec![box_key(code, dev)],
            Dest::Boite(addr) => vec![boite_key(addr)],
            Dest::Code(code) => {
                // Un ancien client : une copie pour chaque appareil connu ;
                // la boîte du code s'il n'y en a pas, ou si un ancien
                // appareil (sans `dev`) est en ligne.
                let mut out: Vec<String> = self
                    .devices
                    .get(code)
                    .map(|set| {
                        let mut devs: Vec<&String> = set.iter().collect();
                        devs.sort();
                        devs.into_iter().map(|d| box_key(code, d)).collect()
                    })
                    .unwrap_or_default();
                let legacy_online = self
                    .online
                    .get(code)
                    .is_some_and(|conns| conns.iter().any(|c| c.dev.is_empty()));
                if out.is_empty() || legacy_online {
                    out.push(code.to_owned());
                }
                out
            }
        }
    }

    /// Les connexions qui lisent une boîte : celles de l'appareil (ou, pour
    /// la boîte d'un code, ses anciens appareils), ou les connexions
    /// anonymes abonnées à une boîte tournante.
    fn readers(&self, key: &str) -> Vec<&Conn> {
        if let Some(addr) = key.strip_prefix('@') {
            return self
                .boite_subs
                .get(addr)
                .map(|ids| {
                    ids.iter()
                        .filter_map(|id| self.anon_conns.get(id))
                        .collect()
                })
                .unwrap_or_default();
        }
        let (code, dev) = key.split_once('/').unwrap_or((key, ""));
        self.online
            .get(code)
            .map(|conns| {
                conns
                    .iter()
                    .filter(|c| c.dev == dev || (dev.is_empty() && !self.has_box(code, &c.dev)))
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Cet appareil a-t-il déjà sa propre boîte ? (Sinon il lit celle du code.)
    fn has_box(&self, code: &str, dev: &str) -> bool {
        !dev.is_empty() && self.devices.get(code).is_some_and(|s| s.contains(dev))
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
        dev: &str,
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
                st.devices.remove(&c);
                st.seen.remove(&c);
                st.push
                    .retain(|k, _| k.split('/').next() != Some(c.as_str()));
                st.woke
                    .retain(|k, _| k.split('/').next() != Some(c.as_str()));
            }
        }
        st.keys.insert(code.to_owned(), Keys { sign, dh });
        // Un appareil v3 republie son paquet aussitôt connecté ; un appareil
        // qui ne le fait pas (une ancienne version avec la même identité)
        // ne doit pas recevoir de jetons v3 qu'il ne saurait pas lire.
        if let Some(b) = st.bundles.get_mut(code) {
            b.remove(dev);
        }
        if !dev.is_empty() {
            st.devices
                .entry(code.to_owned())
                .or_default()
                .insert(dev.to_owned());
        }
        let was_visible = st.is_visible(code);
        let conn = Conn {
            id,
            tx,
            hidden,
            dev: dev.to_owned(),
            overflow: overflow.clone(),
        };
        // Masqué : on ne garde pas non plus l'heure de sa dernière connexion.
        if hidden {
            st.seen.remove(code);
        }
        // Les messages en attente partent tout de suite : ceux de sa boîte,
        // et ceux de la boîte du code (déposés avant que l'appareil soit
        // connu).
        st.evict_expired();
        let mut pending: Vec<&Stored> = Vec::new();
        for key in [box_key(code, dev), code.to_owned()] {
            if let Some(mb) = st.mailboxes.get(&key) {
                pending.extend(mb.queue.iter());
            }
            if dev.is_empty() {
                break;
            }
        }
        pending.sort_by_key(|s| s.n);
        for s in pending {
            conn.send(s.json.clone());
        }
        st.online.entry(code.to_owned()).or_default().push(conn);
        if !was_visible && !hidden {
            st.notify_watchers(code);
        }
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

    /// Dépose un message pour `dest` : dans sa boîte (une par appareil), et
    /// tout de suite sur les connexions qui la lisent ; `wake` : réveille un
    /// appareil qui n'en a aucune. Renvoie (horodatage, destinataire en
    /// ligne).
    #[allow(clippy::too_many_arguments)]
    fn deliver(
        &self,
        from: Sender,
        dest: Dest,
        id: &str,
        body: &str,
        live: bool,
        wake: Wake,
        ttl: Duration,
    ) -> Result<(u64, bool), &'static str> {
        // Sans clé VAPID, personne à réveiller.
        let wake = if crate::push::vapid().is_some() {
            wake
        } else {
            Wake::No
        };
        let (ts, online, subs) = self.store(from, dest, id, body, live, &wake, ttl)?;
        if let Some(vapid) = crate::push::vapid() {
            // L'aperçu chiffré par l'expéditeur, que le navigateur ouvrira.
            let payload = match &wake {
                Wake::Apercu(a) => Some(json!({ "a": a }).to_string()),
                _ => None,
            };
            for (key, sub) in subs {
                let relay = self.clone();
                let payload = payload.clone();
                tokio::spawn(async move {
                    let endpoint = sub.endpoint.clone();
                    if crate::push::wake(vapid, sub, payload).await == crate::push::Outcome::Gone {
                        relay.unpush(&key, &endpoint);
                    }
                });
            }
        }
        Ok((ts, online))
    }

    /// Range un dépôt ; renvoie (horodatage, en ligne, les adresses Web Push
    /// à réveiller avec leur boîte).
    #[allow(clippy::too_many_arguments)]
    fn store(
        &self,
        from: Sender,
        dest: Dest,
        id: &str,
        body: &str,
        live: bool,
        wake: &Wake,
        ttl: Duration,
    ) -> Result<Stored3, &'static str> {
        let ts = now_ms();
        let mut st = self.lock();
        let keys = st.boxes(dest);
        let online = keys.iter().any(|k| !st.readers(k).is_empty());
        let boite = match dest {
            Dest::Boite(addr) => Some(addr),
            _ => None,
        };
        let message = |n: Option<u64>, live: bool| {
            let mut m = json!({ "t": "msg", "id": id, "body": body, "ts": ts });
            if let Some(n) = n {
                m["n"] = json!(n);
            }
            if live {
                m["live"] = json!(true);
            }
            if let Some(addr) = boite {
                m["boite"] = json!(addr);
            }
            envelope(from, m)
        };
        if live {
            // Pas de boîte : seulement les connexions ouvertes.
            let json = message(None, true);
            for key in &keys {
                for conn in st.readers(key) {
                    conn.send(json.clone());
                }
            }
            return Ok((ts, online, Vec::new()));
        }
        // Plus de place : refusé (l'expéditeur réessaiera), jamais en faisant
        // de la place avec les messages des autres.
        let room = |st: &State| {
            st.total_bytes + body.len() * keys.len() <= TOTAL_MAX_BYTES
                && keys.iter().all(|k| {
                    st.mailboxes.get(k).is_none_or(|mb| {
                        mb.queue.len() < MAILBOX_MAX_COUNT
                            && mb.bytes + body.len() <= MAILBOX_MAX_BYTES
                    })
                })
        };
        if !room(&st) {
            st.evict_expired();
            if !room(&st) {
                return Err("full");
            }
        }
        let mut subs = Vec::new();
        for key in &keys {
            st.next_n += 1;
            let n = st.next_n;
            let json = message(Some(n), false);
            let readers: Vec<&Conn> = st.readers(key);
            let read_now = !readers.is_empty();
            for conn in readers {
                conn.send(json.clone());
            }
            let size = json.len();
            let mb = st.mailboxes.entry(key.clone()).or_default();
            mb.queue.push_back(Stored {
                n,
                json,
                at: Instant::now(),
                ttl: ttl.min(MAILBOX_TTL),
            });
            mb.bytes += size;
            st.total_bytes += size;
            if *wake == Wake::No || read_now {
                continue;
            }
            // Qui réveiller : les adresses de cet appareil (ou du code), ou
            // celle donnée avec la boîte tournante.
            let targets: Vec<crate::push::Subscription> = match key.strip_prefix('@') {
                Some(addr) => st
                    .boite_push
                    .get(addr)
                    .map(|(sub, _)| vec![(**sub).clone()])
                    .unwrap_or_default(),
                None => st.push.get(key).cloned().unwrap_or_default(),
            };
            if targets.is_empty() {
                continue;
            }
            // Un réveil vide toutes les 20 s au plus ; avec un aperçu, chaque
            // message compte (une seconde au moins entre deux).
            let every = if matches!(wake, Wake::Apercu(_)) {
                APERCU_EVERY
            } else {
                WAKE_EVERY
            };
            let recent = st.woke.get(key).is_some_and(|at| at.elapsed() < every);
            // Et pas plus de `WAKES_PER_SECOND` en tout : le relais ne sert
            // pas à bombarder les services de notifications.
            if !recent && st.wakes.allow_n(WAKES_PER_SECOND) {
                st.woke.insert(key.clone(), Instant::now());
                subs.extend(targets.into_iter().map(|sub| (key.clone(), sub)));
            }
        }
        Ok((ts, online, subs))
    }

    /// L'abonnement Web Push d'un appareil : `key` est sa boîte (`<code>`,
    /// ou `<code>/<dev>` en v4).
    fn push(&self, key: &str, sub: crate::push::Subscription) -> bool {
        if crate::push::audience(&sub.endpoint).is_none() {
            return false;
        }
        let mut st = self.lock();
        let list = st.push.entry(key.to_owned()).or_default();
        list.retain(|e| e.endpoint != sub.endpoint);
        list.push(sub);
        if list.len() > MAX_PUSH {
            list.remove(0);
        }
        true
    }

    /// Oublie une adresse Web Push (désactivée, ou disparue) ; `key` : une
    /// boîte, ou `@<boîte tournante>`.
    fn unpush(&self, key: &str, endpoint: &str) {
        let mut st = self.lock();
        if let Some(addr) = key.strip_prefix('@') {
            if st
                .boite_push
                .get(addr)
                .is_some_and(|(sub, _)| sub.endpoint == endpoint)
            {
                // L'adresse a disparu : plus aucune boîte ne la réveille.
                st.boite_push.retain(|_, (sub, _)| sub.endpoint != endpoint);
            }
            return;
        }
        if let Some(list) = st.push.get_mut(key) {
            list.retain(|e| e.endpoint != endpoint);
            if list.is_empty() {
                st.push.remove(key);
            }
        }
    }

    /// Le destinataire a rangé ces messages : ils quittent ses boîtes
    /// (`keys` : celles qu'il lit).
    fn forget(&self, keys: &[String], numbers: &[u64]) {
        let mut st = self.lock();
        let numbers: HashSet<u64> = numbers.iter().copied().collect();
        for key in keys {
            let Some(mb) = st.mailboxes.get_mut(key) else {
                continue;
            };
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
                st.mailboxes.remove(key);
            }
            st.total_bytes -= freed;
        }
    }

    fn lookup(&self, code: &str) -> Value {
        let st = self.lock();
        match st.keys.get(code) {
            Some(k) => json!({ "t": "keys", "code": code, "sign": k.sign, "dh": k.dh }),
            None => json!({ "t": "keys", "code": code, "unknown": true }),
        }
    }

    /// Garde le paquet de clés v3 de `code` (de son appareil `dev` en v4),
    /// s'il est signé par sa clé (`sign`, brute) et pas trop gros. Il
    /// remplace le précédent de cet appareil.
    fn publish(
        &self,
        code: &str,
        sign: &[u8],
        dev: &str,
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
        let message = if dev.is_empty() {
            code::bundle_message(&bytes)
        } else {
            code::device_bundle_message(dev, &bytes)
        };
        if !code::verify(sign, &message, sig) {
            return Err("signature");
        }
        let mut st = self.lock();
        if st.banned.get(code).is_some_and(|b| b.contains(dev)) {
            return Err("dev");
        }
        if !st.keys.contains_key(code) {
            return Ok(());
        }
        let devices = st.bundles.entry(code.to_owned()).or_default();
        if !dev.is_empty()
            && !devices.contains_key(dev)
            && devices.keys().filter(|d| !d.is_empty()).count() >= MAX_DEVICES
        {
            return Err("devices");
        }
        devices.insert(
            dev.to_owned(),
            Bundle {
                bundle: bundle.to_owned(),
                sig: sig.to_owned(),
            },
        );
        if !dev.is_empty() {
            st.devices
                .entry(code.to_owned())
                .or_default()
                .insert(dev.to_owned());
        }
        Ok(())
    }

    /// Les paquets de `code` : celui d'avant la v4 (pour les anciens
    /// clients), et ceux de ses appareils.
    fn bundle(&self, code: &str) -> Value {
        let st = self.lock();
        let Some(k) = st.keys.get(code) else {
            return json!({ "t": "bundle", "code": code, "none": true });
        };
        let all = st.bundles.get(code);
        let mut devices: Vec<Value> = all
            .map(|m| {
                m.iter()
                    .filter(|(d, _)| !d.is_empty())
                    .map(|(d, b)| json!({ "dev": d, "bundle": b.bundle, "sig": b.sig }))
                    .collect()
            })
            .unwrap_or_default();
        devices.sort_by(|a, b| a["dev"].as_str().cmp(&b["dev"].as_str()));
        let mut out =
            json!({ "t": "bundle", "code": code, "sign": k.sign, "dh": k.dh, "devices": devices });
        match all.and_then(|m| m.get("")) {
            Some(b) => {
                out["bundle"] = json!(b.bundle);
                out["sig"] = json!(b.sig);
            }
            None if out["devices"].as_array().is_none_or(Vec::is_empty) => {
                out = json!({ "t": "bundle", "code": code, "none": true });
            }
            None => out["none"] = json!(true),
        }
        out
    }

    /// Retire un appareil du compte `code` : son paquet, sa boîte, ses
    /// adresses Web Push ; ses connexions sont coupées et son `hello`
    /// refusé (jusqu'au redémarrage du relais).
    fn unlink(&self, code: &str, dev: &str) {
        if !code::valid_dev(dev) {
            return;
        }
        let mut st = self.lock();
        st.banned
            .entry(code.to_owned())
            .or_default()
            .insert(dev.to_owned());
        if let Some(b) = st.bundles.get_mut(code) {
            b.remove(dev);
        }
        if let Some(d) = st.devices.get_mut(code) {
            d.remove(dev);
        }
        let key = box_key(code, dev);
        if let Some(mb) = st.mailboxes.remove(&key) {
            st.total_bytes -= mb.bytes;
        }
        st.push.remove(&key);
        if let Some(conns) = st.online.get(code) {
            for c in conns.iter().filter(|c| c.dev == dev) {
                c.send(json!({ "t": "unlinked" }).to_string());
                c.overflow.store(true, std::sync::atomic::Ordering::Relaxed);
            }
        }
    }

    fn is_banned(&self, code: &str, dev: &str) -> bool {
        !dev.is_empty()
            && self
                .lock()
                .banned
                .get(code)
                .is_some_and(|b| b.contains(dev))
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
    /// Ce jeton a-t-il été révoqué par son destinataire ?
    fn is_revoked(&self, to: &str, jeton: &str) -> bool {
        let nonce = jeton.split_once('.').map_or(jeton, |(n, _)| n);
        self.lock()
            .revoked
            .get(to)
            .is_some_and(|set| set.contains(nonce))
    }

    /// Les jetons révoqués de `code` (la liste entière, qui remplace la
    /// précédente).
    fn revoke(&self, code: &str, nonces: &[String]) {
        let set: HashSet<String> = nonces
            .iter()
            .filter(|n| !n.is_empty() && n.len() <= 64)
            .take(MAX_REVOKED)
            .cloned()
            .collect();
        let mut st = self.lock();
        if set.is_empty() {
            st.revoked.remove(code);
        } else {
            st.revoked.insert(code.to_owned(), set);
        }
    }

    /// Une connexion de plus depuis `ip` ; `false` si cette adresse en a
    /// déjà trop.
    fn admit(&self, ip: Option<std::net::IpAddr>) -> bool {
        let Some(ip) = ip else { return true };
        let mut st = self.lock();
        let n = st.per_ip.entry(ip).or_default();
        if *n >= MAX_PER_IP {
            return false;
        }
        *n += 1;
        true
    }

    fn release(&self, ip: Option<std::net::IpAddr>) {
        let Some(ip) = ip else { return };
        let mut st = self.lock();
        if let Some(n) = st.per_ip.get_mut(&ip) {
            *n = n.saturating_sub(1);
            if *n == 0 {
                st.per_ip.remove(&ip);
            }
        }
    }

    fn send(&self, from: Sender, msg: &Value, limit: &mut Limit) -> Value {
        let id = msg["id"].as_str().unwrap_or("").to_owned();
        let body = msg["body"].as_str().unwrap_or("");
        let to = msg["to"].as_str().and_then(code::normalize);
        let dev = msg["dev"].as_str().unwrap_or("");
        let boite = msg["boite"].as_str().unwrap_or("");
        let field = |name: &str| msg[name].as_str().unwrap_or("");
        let error = if !limit.allow() || !limit.allow_bytes(body.len()) {
            Some("rate")
        } else if id.is_empty() || id.len() > 64 {
            Some("id")
        } else if body.is_empty() || body.len() > MAX_BODY {
            Some("size")
        } else if !boite.is_empty() {
            // Une boîte tournante : connaître son adresse suffit (seuls
            // l'expéditeur et le destinataire savent la calculer).
            (!code::valid_boite(boite)).then_some("boite")
        } else if to.is_none() {
            Some("code")
        } else if (!dev.is_empty() && !code::valid_dev(dev))
            || self.is_banned(to.as_deref().unwrap_or(""), dev)
        {
            Some("dev")
        } else if from.is_none()
            && (!code::verify_jeton(
                to.as_deref().unwrap_or(""),
                field("sign"),
                field("dh"),
                field("jeton"),
            ) || self.is_revoked(to.as_deref().unwrap_or(""), field("jeton")))
        {
            Some("jeton")
        } else {
            None
        };
        if let Some(error) = error {
            return json!({ "t": "error", "id": id, "error": error });
        }
        let dest = if !boite.is_empty() {
            Dest::Boite(boite)
        } else if dev.is_empty() {
            Dest::Code(to.as_deref().expect("checked above"))
        } else {
            Dest::Device(to.as_deref().expect("checked above"), dev)
        };
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
        // Combien de temps garder (une liaison : une heure).
        let ttl = msg["ttl"].as_u64().map_or(MAILBOX_TTL, Duration::from_secs);
        match self.deliver(from, dest, &id, body, live, wake, ttl) {
            Ok((ts, online)) => json!({ "t": "ack", "id": id, "ts": ts, "online": online }),
            Err(error) => json!({ "t": "error", "id": id, "error": error }),
        }
    }

    /// Une connexion anonyme s'abonne à des boîtes tournantes : ce qui y
    /// attend part tout de suite.
    fn subscribe(&self, conn_id: u64, msg: &Value) {
        let mut st = self.lock();
        let already = st
            .boite_subs
            .values()
            .filter(|ids| ids.contains(&conn_id))
            .count();
        let room = MAX_BOITES.saturating_sub(already);
        let list: Vec<String> = msg["list"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(Value::as_str)
                    .filter(|a| code::valid_boite(a))
                    .take(MAX_BOITES_ASKED.min(room))
                    .map(str::to_owned)
                    .collect()
            })
            .unwrap_or_default();
        // L'abonnement Web Push à réveiller pour ces boîtes (un navigateur).
        let push = msg["push"]["endpoint"].as_str().and_then(|endpoint| {
            let key = |name: &str| {
                let v = msg["push"][name]
                    .as_str()
                    .unwrap_or("")
                    .trim_end_matches('=');
                URL_B64.decode(v).unwrap_or_default()
            };
            let sub = crate::push::Subscription {
                endpoint: endpoint.to_owned(),
                p256dh: key("p256dh"),
                auth: key("auth"),
            };
            (crate::push::vapid().is_some() && crate::push::audience(endpoint).is_some())
                .then(|| Arc::new(sub))
        });
        st.evict_expired();
        let mut pending: Vec<String> = Vec::new();
        for addr in &list {
            st.boite_subs
                .entry(addr.clone())
                .or_default()
                .insert(conn_id);
            if let Some(sub) = &push
                && (st.boite_push.len() < MAX_BOITE_PUSH || st.boite_push.contains_key(addr))
            {
                st.boite_push
                    .insert(addr.clone(), (sub.clone(), Instant::now()));
            }
            if let Some(mb) = st.mailboxes.get(&boite_key(addr)) {
                pending.extend(mb.queue.iter().map(|s| s.json.clone()));
            }
        }
        if let Some(conn) = st.anon_conns.get(&conn_id) {
            for json in pending {
                conn.send(json);
            }
        }
    }

    /// Les boîtes tournantes que suit une connexion anonyme.
    fn subscribed(&self, conn_id: u64) -> Vec<String> {
        self.lock()
            .boite_subs
            .iter()
            .filter(|(_, ids)| ids.contains(&conn_id))
            .map(|(addr, _)| boite_key(addr))
            .collect()
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
        let ready = json!({ "t": "ready", "anon": true, "time": now_ms(), "boot": boot_id(), "boites": true });
        if tx.send(ready.to_string()).await.is_err() {
            return;
        }
        // Ce que le relais lui pousse (les boîtes tournantes qu'elle suit).
        let (out_tx, mut outbox) = mpsc::channel(OUT_QUEUE);
        let overflow = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let conn_id = {
            let mut st = self.lock();
            st.next_conn += 1;
            let id = st.next_conn;
            st.anon_conns.insert(
                id,
                Conn {
                    id,
                    tx: out_tx,
                    hidden: true,
                    dev: String::new(),
                    overflow: overflow.clone(),
                },
            );
            id
        };
        let mut limit = Limit::default();
        let mut asks = Limit::default();
        let mut ping = tokio::time::interval(PING_EVERY);
        ping.tick().await;
        // Fermée au bout de `ANON_IDLE` sans envoi, sauf si elle suit des
        // boîtes (elle reçoit).
        let mut last = Instant::now();
        let mut listening = false;
        loop {
            if (!listening && last.elapsed() > ANON_IDLE)
                || overflow.load(std::sync::atomic::Ordering::Relaxed)
            {
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
                    let kind = msg["t"].as_str().unwrap_or("");
                    if kind != "send" && !asks.allow() {
                        continue;
                    }
                    let answer = match kind {
                        "send" => {
                            last = Instant::now();
                            self.send(None, &msg, &mut limit)
                        }
                        "boites" => {
                            listening = true;
                            self.subscribe(conn_id, &msg);
                            continue;
                        }
                        "got" => {
                            let numbers: Vec<u64> = msg["n"]
                                .as_array()
                                .map(|a| a.iter().filter_map(Value::as_u64).collect())
                                .unwrap_or_default();
                            self.forget(&self.subscribed(conn_id), &numbers);
                            continue;
                        }
                        "ping" => json!({ "t": "pong", "time": now_ms() }),
                        _ => continue,
                    };
                    if tx.send(answer.to_string()).await.is_err() {
                        break;
                    }
                }
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
        let mut st = self.lock();
        st.anon_conns.remove(&conn_id);
        st.boite_subs.retain(|_, ids| {
            ids.remove(&conn_id);
            !ids.is_empty()
        });
    }

    /// Une connexion : la présentation, puis la boucle des messages.
    /// Une connexion, depuis `ip` (l'adresse du client, vue à travers le
    /// proxy ; `None` si inconnue) : au plus `MAX_PER_IP` à la fois.
    pub async fn serve(self, socket: WebSocket, ip: Option<std::net::IpAddr>) {
        if !self.admit(ip) {
            let (mut tx, _) = socket.split();
            let _ = tx.close(4005, "ip").await;
            return;
        }
        self.clone().serve_conn(socket).await;
        self.release(ip);
    }

    async fn serve_conn(self, socket: WebSocket) {
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
        // L'appareil (v4) : un identifiant valable, et pas retiré du compte.
        let dev = field("dev");
        if !dev.is_empty() && !code::valid_dev(&dev) {
            let _ = tx.close(4001, "dev").await;
            return;
        }
        // Retiré pendant qu'il était hors ligne : il l'apprend d'abord par un
        // message (les clients ne lisent pas les codes de fermeture), puis il
        // est coupé ; il efface alors tout.
        if self.is_banned(&me, &dev) {
            let _ = tx.send(json!({ "t": "unlinked" }).to_string()).await;
            let _ = tx.close(4006, "unlinked").await;
            return;
        }
        let mut ready = json!({ "t": "ready", "code": me, "time": now_ms(), "boot": boot_id(), "sealed": true, "v4": true });
        if let Some(vapid) = crate::push::vapid() {
            ready["push"] = json!(vapid.public);
        }
        let ready = ready.to_string();
        if tx.send(ready).await.is_err() {
            return;
        }
        let (conn_id, mut outbox, overflow) = self.register(&me, sign_b64, dh_b64, hidden, &dev);

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
                    let answer = self.send(Some((&me, &dev)), &msg, &mut limit);
                    reply(answer.to_string());
                }
                "got" => {
                    let numbers: Vec<u64> = msg["n"]
                        .as_array()
                        .map(|a| a.iter().filter_map(Value::as_u64).collect())
                        .unwrap_or_default();
                    let mut keys = vec![me.clone()];
                    if !dev.is_empty() {
                        keys.push(box_key(&me, &dev));
                    }
                    self.forget(&keys, &numbers);
                }
                "unlink" => {
                    let target = msg["dev"].as_str().unwrap_or("");
                    if target != dev {
                        self.unlink(&me, target);
                    }
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
                    // Un appareil v4 publie le sien (`dev`) ; il peut aussi
                    // publier, sans `dev`, celui que liront les anciens
                    // clients.
                    let target = msg["dev"].as_str().unwrap_or("");
                    let answer = match self.publish(
                        &me,
                        &sign,
                        if target == dev { target } else { "" },
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
                    let key = box_key(&me, &dev);
                    let ok = if msg["on"].as_bool().unwrap_or(true) {
                        crate::push::vapid().is_some() && self.push(&key, sub)
                    } else {
                        self.unpush(&key, endpoint);
                        true
                    };
                    reply(json!({ "t": "push", "ok": ok }).to_string());
                }
                "revoke" => {
                    let nonces: Vec<String> = msg["nonces"]
                        .as_array()
                        .map(|a| {
                            a.iter()
                                .filter_map(|v| v.as_str().map(str::to_owned))
                                .collect()
                        })
                        .unwrap_or_default();
                    self.revoke(&me, &nonces);
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
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code(to),
                    "r1",
                    "x",
                    false,
                    &Wake::No,
                    MAILBOX_TTL
                )
                .unwrap()
                .2
                .is_empty()
        );
        // « écrit… », un signal d'appel (`live`) : jamais.
        assert!(
            relay
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code(to),
                    "l1",
                    "x",
                    true,
                    &Wake::Empty,
                    MAILBOX_TTL
                )
                .unwrap()
                .2
                .is_empty()
        );
        // Un vrai message : un réveil…
        assert_eq!(
            relay
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code(to),
                    "m1",
                    "x",
                    false,
                    &Wake::Empty,
                    MAILBOX_TTL
                )
                .unwrap()
                .2,
            vec![("BBBBBBBBBB".to_owned(), sub(&format!("{FCM}b")))]
        );
        // Avec un aperçu, chaque message compte : une seconde suffit.
        relay
            .lock()
            .woke
            .insert(to.to_owned(), Instant::now() - APERCU_EVERY);
        assert_eq!(
            relay
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code(to),
                    "m1b",
                    "x",
                    false,
                    &Wake::Apercu("a".into()),
                    MAILBOX_TTL
                )
                .unwrap()
                .2
                .len(),
            1
        );
        // … puis plus rien pendant 20 secondes.
        assert!(
            relay
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code(to),
                    "m2",
                    "x",
                    false,
                    &Wake::Empty,
                    MAILBOX_TTL
                )
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
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code(to),
                    "m3",
                    "x",
                    false,
                    &Wake::Empty,
                    MAILBOX_TTL
                )
                .unwrap()
                .2
                .len(),
            1
        );
        // Un code sans adresse : personne à réveiller.
        assert!(
            relay
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code("CCCCCCCCCC"),
                    "m4",
                    "x",
                    false,
                    &Wake::Empty,
                    MAILBOX_TTL
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
        let (_id, _rx, _) = relay.register(to, String::new(), String::new(), true, "");
        assert!(
            relay
                .store(
                    Some(("AAAAAAAAAA", "")),
                    Dest::Code(to),
                    "m1",
                    "x",
                    false,
                    &Wake::Empty,
                    MAILBOX_TTL
                )
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
                    .store(
                        None,
                        Dest::Code(to),
                        &format!("m{i}"),
                        "x",
                        false,
                        &Wake::No,
                        MAILBOX_TTL
                    )
                    .is_ok()
            );
        }
        assert_eq!(
            relay
                .store(
                    None,
                    Dest::Code(to),
                    "trop",
                    "x",
                    false,
                    &Wake::No,
                    MAILBOX_TTL
                )
                .err(),
            Some("full")
        );
        assert_eq!(relay.lock().mailboxes[to].queue.len(), MAILBOX_MAX_COUNT);
        // Les autres boîtes, elles, reçoivent toujours.
        assert!(
            relay
                .store(
                    None,
                    Dest::Code("CCCCCCCCCC"),
                    "m",
                    "x",
                    false,
                    &Wake::No,
                    MAILBOX_TTL
                )
                .is_ok()
        );
        // Toutes les boîtes ensemble : pareil.
        relay.lock().total_bytes = TOTAL_MAX_BYTES;
        assert_eq!(
            relay
                .store(
                    None,
                    Dest::Code("DDDDDDDDDD"),
                    "m",
                    "x",
                    false,
                    &Wake::No,
                    MAILBOX_TTL
                )
                .err(),
            Some("full")
        );
    }

    /// Un code déjà lié à des clés ne passe pas à d'autres.
    #[test]
    fn a_code_keeps_its_keys() {
        let relay = Relay::default();
        let (_id, _rx, _) = relay.register("BBBBBBBBBB", "s1".into(), "d1".into(), false, "");
        assert!(!relay.taken("BBBBBBBBBB", "s1", "d1"));
        assert!(relay.taken("BBBBBBBBBB", "s2", "d1"));
        assert!(relay.taken("BBBBBBBBBB", "s1", "d2"));
        assert!(!relay.taken("CCCCCCCCCC", "s2", "d2"));
    }

    /// Masqué : pas d'heure de dernière connexion pour les autres.
    #[test]
    fn hidden_codes_leave_no_last_seen() {
        let relay = Relay::default();
        let (id, _rx, _) = relay.register("BBBBBBBBBB", "s".into(), "d".into(), true, "");
        relay.unregister("BBBBBBBBBB", id);
        assert!(!relay.lock().seen.contains_key("BBBBBBBBBB"));
        let (id, _rx, _) = relay.register("BBBBBBBBBB", "s".into(), "d".into(), false, "");
        relay.unregister("BBBBBBBBBB", id);
        assert!(relay.lock().seen.contains_key("BBBBBBBBBB"));
    }

    /// Un appareil qui ne lit plus : sa file se remplit, la connexion est
    /// marquée pour être coupée (la mémoire du relais ne grossit pas).
    #[test]
    fn a_stalled_connection_is_cut() {
        let relay = Relay::default();
        let (id, _rx, overflow) = relay.register("BBBBBBBBBB", "s".into(), "d".into(), false, "");
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
                .store(
                    None,
                    Dest::Code(&to),
                    "m",
                    "x",
                    false,
                    &Wake::Empty,
                    MAILBOX_TTL,
                )
                .unwrap()
                .2
                .len();
        }
        assert_eq!(woken, WAKES_PER_SECOND as usize);
    }

    /// Un jeton révoqué par son destinataire ne permet plus d'écrire.
    #[test]
    fn revoked_jetons_are_refused() {
        let relay = Relay::default();
        assert!(!relay.is_revoked("BBBBBBBBBB", "abc.sig"));
        relay.revoke("BBBBBBBBBB", &["abc".into(), "".into()]);
        assert!(relay.is_revoked("BBBBBBBBBB", "abc.sig"));
        assert!(!relay.is_revoked("BBBBBBBBBB", "abd.sig"));
        assert!(!relay.is_revoked("CCCCCCCCCC", "abc.sig"));
        // La liste redonnée remplace l'ancienne.
        relay.revoke("BBBBBBBBBB", &[]);
        assert!(!relay.is_revoked("BBBBBBBBBB", "abc.sig"));
    }

    fn drain(rx: &mut mpsc::Receiver<String>) -> Vec<Value> {
        let mut out = Vec::new();
        while let Ok(text) = rx.try_recv() {
            out.push(serde_json::from_str(&text).unwrap());
        }
        out
    }

    const D1: &str = "00000000000000d1";
    const D2: &str = "00000000000000d2";

    /// v4 : chaque appareil a sa boîte ; un ancien client (sans `dev`) écrit
    /// à tous ; un envoi peut viser un appareil ; `got` n'efface que la
    /// copie de l'appareil qui l'a rangée.
    #[test]
    fn each_device_has_its_own_mailbox() {
        let relay = Relay::default();
        let b = "BBBBBBBBBB";
        let (_i1, mut rx1, _) = relay.register(b, "s".into(), "d".into(), false, D1);
        let (i2, mut rx2, _) = relay.register(b, "s".into(), "d".into(), false, D2);
        relay.unregister(b, i2);
        // Un ancien client : une copie par appareil (D2 hors ligne la trouve
        // à son retour).
        relay
            .store(
                Some(("AAAAAAAAAA", "")),
                Dest::Code(b),
                "m1",
                "x",
                false,
                &Wake::No,
                MAILBOX_TTL,
            )
            .unwrap();
        let got1 = drain(&mut rx1);
        assert_eq!(got1.len(), 1);
        assert_eq!(got1[0]["from"], "AAAAAAAAAA");
        assert!(got1[0].get("fd").is_none());
        // Un appareil v4 vise D1 : D2 n'en sait rien ; `fd` dit d'où il vient.
        relay
            .store(
                Some(("AAAAAAAAAA", "00000000000000a1")),
                Dest::Device(b, D1),
                "m2",
                "y",
                false,
                &Wake::No,
                MAILBOX_TTL,
            )
            .unwrap();
        let got1b = drain(&mut rx1);
        assert_eq!(got1b.len(), 1);
        assert_eq!(got1b[0]["fd"], "00000000000000a1");
        // D1 range tout : sa boîte se vide, pas celle de D2.
        let ns: Vec<u64> = got1
            .iter()
            .chain(&got1b)
            .map(|m| m["n"].as_u64().unwrap())
            .collect();
        relay.forget(&[b.to_owned(), box_key(b, D1)], &ns);
        assert!(!relay.lock().mailboxes.contains_key(&box_key(b, D1)));
        assert_eq!(relay.lock().mailboxes[&box_key(b, D2)].queue.len(), 1);
        let (_i2, mut rx2b, _) = relay.register(b, "s".into(), "d".into(), false, D2);
        let back = drain(&mut rx2b);
        assert_eq!(back.len(), 1);
        assert_eq!(back[0]["id"], "m1");
        assert!(drain(&mut rx2).is_empty());
    }

    /// v4 : un appareil retiré perd sa boîte, ne se reconnecte plus, ne
    /// reçoit plus rien ; on ne peut plus lui écrire.
    #[test]
    fn an_unlinked_device_is_gone() {
        let relay = Relay::default();
        let b = "BBBBBBBBBB";
        let (_i1, _rx1, _) = relay.register(b, "s".into(), "d".into(), false, D1);
        let (_i2, mut rx2, cut) = relay.register(b, "s".into(), "d".into(), false, D2);
        relay.unlink(b, D2);
        assert!(cut.load(std::sync::atomic::Ordering::Relaxed));
        assert_eq!(drain(&mut rx2)[0]["t"], "unlinked");
        assert!(relay.is_banned(b, D2));
        assert!(!relay.is_banned(b, D1));
        relay
            .store(None, Dest::Code(b), "m", "x", false, &Wake::No, MAILBOX_TTL)
            .unwrap();
        assert!(!relay.lock().mailboxes.contains_key(&box_key(b, D2)));
        let mut limit = Limit::default();
        let refused = relay.send(
            Some(("AAAAAAAAAA", "")),
            &json!({ "t": "send", "id": "x", "to": b, "dev": D2, "body": "b" }),
            &mut limit,
        );
        assert_eq!(refused["error"], "dev");
    }

    /// v4 : une boîte tournante n'est liée à aucun code ; une connexion
    /// anonyme abonnée reçoit ce qui attendait, puis ce qui arrive ; `got`
    /// l'oublie ; un dépôt de liaison ne dure qu'une heure.
    #[test]
    fn rotating_boxes_need_no_code() {
        let relay = Relay::default();
        let addr = "AAAAAAAAAAAAAAAAAAAAAA";
        let mut limit = Limit::default();
        let ack = relay.send(
            None,
            &json!({ "t": "send", "id": "1", "boite": addr, "body": "scellé", "ttl": 3600 }),
            &mut limit,
        );
        assert_eq!(ack["t"], "ack");
        assert_eq!(ack["online"], false);
        assert_eq!(
            relay.send(
                None,
                &json!({ "t": "send", "id": "2", "boite": "court", "body": "x" }),
                &mut limit
            )["error"],
            "boite"
        );
        // Une connexion anonyme s'abonne : le dépôt part tout de suite.
        let (tx, mut rx) = mpsc::channel(16);
        let overflow = Arc::new(std::sync::atomic::AtomicBool::new(false));
        relay.lock().anon_conns.insert(
            77,
            Conn {
                id: 77,
                tx,
                hidden: true,
                dev: String::new(),
                overflow,
            },
        );
        relay.subscribe(
            77,
            &json!({ "t": "boites", "list": [addr, "pas-une-boite"] }),
        );
        let first = drain(&mut rx);
        assert_eq!(first.len(), 1);
        assert_eq!(first[0]["boite"], addr);
        assert_eq!(first[0]["sealed"], true);
        assert!(first[0].get("from").is_none());
        // Ce qui arrive ensuite aussi.
        let ack = relay.send(
            None,
            &json!({ "t": "send", "id": "3", "boite": addr, "body": "encore" }),
            &mut limit,
        );
        assert_eq!(ack["online"], true);
        let second = drain(&mut rx);
        assert_eq!(second.len(), 1);
        // Rangés : oubliés.
        let ns = [
            first[0]["n"].as_u64().unwrap(),
            second[0]["n"].as_u64().unwrap(),
        ];
        relay.forget(&relay.subscribed(77), &ns);
        assert!(!relay.lock().mailboxes.contains_key(&boite_key(addr)));
        // Un dépôt d'une heure, au-delà : effacé.
        relay.send(
            None,
            &json!({ "t": "send", "id": "4", "boite": addr, "body": "lien", "ttl": 3600 }),
            &mut limit,
        );
        {
            let mut st = relay.lock();
            let mb = st.mailboxes.get_mut(&boite_key(addr)).unwrap();
            assert_eq!(mb.queue[0].ttl, Duration::from_secs(3600));
            // (Sans attendre une heure : le même dépôt, déjà trop vieux.)
            mb.queue[0].ttl = Duration::ZERO;
            std::thread::sleep(Duration::from_millis(2));
            st.evict_expired();
            assert!(!st.mailboxes.contains_key(&boite_key(addr)));
        }
    }

    /// Au plus `MAX_PER_IP` connexions depuis une même adresse.
    #[test]
    fn connections_per_ip_are_capped() {
        let relay = Relay::default();
        let ip: std::net::IpAddr = "203.0.113.7".parse().unwrap();
        for _ in 0..MAX_PER_IP {
            assert!(relay.admit(Some(ip)));
        }
        assert!(!relay.admit(Some(ip)));
        assert!(relay.admit(Some("203.0.113.8".parse().unwrap())));
        relay.release(Some(ip));
        assert!(relay.admit(Some(ip)));
        assert!(relay.admit(None));
    }
}
