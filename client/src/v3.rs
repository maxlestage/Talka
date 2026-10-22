//! Le protocole v3 de Talka : des sessions à double cliquet (une clé neuve
//! par message), établies en hybride X25519 + ML-KEM-768, avec
//! [`brocolis_proto`], l'implémentation de référence de
//! [`docs/SECURITE.md`](../../docs/SECURITE.md). Compilé aussi hors du
//! navigateur : les tests du serveur font parler deux clients par le vrai
//! relais.
//!
//! - **Un appareil v3 par compte** ([`Device`] : IK X25519 + Ed25519, SPK,
//!   PQPK ML-KEM-768), créé au premier démarrage, gardé dans `kv/v3_device`,
//!   ses clés pré-signées renouvelées chaque semaine ([`Engine::maintain`]).
//! - **Le paquet de clés est lié au code** : ses octets
//!   ([`brocolis_proto::KeyBundle::to_bytes`]) sont signés par l'identité
//!   ECDSA P-256 de Talka (celle dont le code est l'empreinte), après le
//!   contexte [`BUNDLE_CONTEXT`]. Un paquet n'est accepté que si cette
//!   signature vérifie avec la clé de signature du contact, elle-même liée à
//!   son code ([`crypto::verify_keys`]) : pas d'empreinte de plus à comparer.
//! - **Envoi** : le `Wire` JSON est chiffré par la session du contact (un
//!   jeton `[[BROCOLIS:v3:…]]`), puis ce jeton part dans l'enveloppe Talka
//!   habituelle ([`crypto::seal`], les deux codes en données associées) : le
//!   relais ne voit aucune différence. Sans paquet ni session (un contact qui
//!   n'a pas la mise à jour), l'ancien format, exactement. L'iPhone parle le
//!   même protocole (`ios/Sources/TalkaKit/V3.swift`, `tests/ios_v3.rs`).
//! - **Réception** : une enveloppe dont le contenu est un jeton v3 passe par
//!   la session (ou [`Device::accept`] pour un premier message). Un rejeu est
//!   ignoré sans bruit.
//!
//! Les sessions : AD = IK de l'initiateur ‖ IK du répondeur, donc deux
//! identifiants de session au plus entre deux appareils (celle que j'ai
//! ouverte, celle qu'il a ouverte). Les deux sont gardées : quand les deux
//! côtés écrivent le premier message en même temps (deux contacts qui
//! s'ajoutent, chacun envoie son profil), chacun lit l'autre, et l'envoi
//! suit la dernière session qui a servi.

use std::collections::{HashMap, HashSet};

pub use brocolis_proto::KeyBundle;
pub use brocolis_proto::session::MAX_PLAINTEXT;
use brocolis_proto::{Content, Device, Erreur, Session, Token, hasard, jeton_v3};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::crypto::{self, Identity, b64, unb64};
use crate::pq6;

/// Ce que signe l'identité Talka : ce contexte, puis les octets du paquet.
pub const BUNDLE_CONTEXT: &[u8] = b"talka-v3-paquet:";

/// Un paquet de clés fait 166 octets (sans ML-KEM) ou 1 418 (avec) ; le
/// relais en accepte jusqu'à 2 Kio (base64 et signature à part).
pub const MAX_BUNDLE: usize = 2048;

/// La clé de l'appareil v3 dans `kv`.
pub const DEVICE_KEY: &str = "v3_device";

/// Le préfixe des sessions d'un contact dans `kv` (`v3:<code>`). Elles ne
/// partent pas dans la sauvegarde : un état de cliquet restauré en retard
/// réutiliserait des clés de message déjà servies.
pub const SESSION_PREFIX: &str = "v3:";

/// Ce que signe l'identité Talka pour publier un paquet.
pub fn bundle_message(bundle: &[u8]) -> Vec<u8> {
    [BUNDLE_CONTEXT, bundle].concat()
}

/// Ce que signe l'identité Talka pour publier le paquet d'un appareil (v4,
/// `docs/SECURITE.md` §7.11) : le paquet est lié au code **et** à
/// l'appareil.
pub fn device_bundle_message(dev: &str, bundle: &[u8]) -> Vec<u8> {
    [format!("talka-v4-appareil:{dev}:").as_bytes(), bundle].concat()
}

/// Le paquet d'un appareil (v4), comme `verify_bundle`.
pub fn verify_device_bundle(
    code: &str,
    sign: &str,
    dh: &str,
    dev: &str,
    bundle: &str,
    sig: &str,
) -> Option<KeyBundle> {
    if !crypto::is_dev(dev)
        || !crypto::verify_keys(code, sign, dh)
        || bundle.len() > MAX_BUNDLE.div_ceil(3) * 4
    {
        return None;
    }
    let bytes = unb64(bundle)?;
    if bytes.len() > MAX_BUNDLE || !crypto::verify(sign, &device_bundle_message(dev, &bytes), sig) {
        return None;
    }
    KeyBundle::from_bytes(&bytes).ok()
}

/// L'heure du protocole : des secondes Unix (Talka compte en millisecondes).
pub fn secs(ms: f64) -> u64 {
    (ms / 1000.0).max(0.0) as u64
}

/// Un paquet publié sur le relais : accepté seulement si `sign` et `dh` sont
/// bien les clés de `code`, et si `sig` (ECDSA P-256, `r ‖ s`) est leur
/// signature du paquet. Le paquet lui-même est vérifié (ses signatures
/// Ed25519, ses clés).
pub fn verify_bundle(
    code: &str,
    sign: &str,
    dh: &str,
    bundle: &str,
    sig: &str,
) -> Option<KeyBundle> {
    if !crypto::verify_keys(code, sign, dh) || bundle.len() > MAX_BUNDLE.div_ceil(3) * 4 {
        return None;
    }
    let bytes = unb64(bundle)?;
    if bytes.len() > MAX_BUNDLE || !crypto::verify(sign, &bundle_message(&bytes), sig) {
        return None;
    }
    KeyBundle::from_bytes(&bytes).ok()
}

/// Vrai si ce texte (le contenu d'une enveloppe Talka) est un jeton v3.
pub fn is_token(text: &str) -> bool {
    text.starts_with(jeton_v3::HEAD) && text.ends_with("]]")
}

/// Une enveloppe ouverte.
#[derive(Debug, PartialEq, Eq)]
pub enum Opened {
    /// L'ancien format : le `Wire` JSON.
    Legacy(String),
    /// Un `Wire` JSON sorti d'une session v3.
    V3(String),
    /// Déjà lu (rejeu, doublon du relais) : à ignorer sans bruit.
    Replay,
    /// Illisible : l'enveloppe n'est pas pour nous, ou la session est perdue.
    Failed(Failure),
}

/// Faut-il réparer la session (l'oublier et renvoyer notre profil) ? Seulement
/// quand elle est **perdue** : session inconnue, ou clé pré-signée déjà
/// oubliée. Un déchiffrement raté sur une session connue est un rejeu (le
/// relais peut renvoyer un vieux message, y compris d'une chaîne plus
/// ancienne que celles que la session retient) ou une falsification : on
/// l'ignore. Sinon, quiconque rejoue un vieux message forcerait une remise à
/// zéro de la session.
pub fn needs_repair(e: &Erreur) -> bool {
    matches!(e, Erreur::SessionInconnue | Erreur::CleInconnue)
}

/// Pourquoi une enveloppe n'a pas pu être ouverte.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum Failure {
    /// Le chiffrement de Talka (pas pour nous, ou abîmée).
    Envelope,
    /// Le jeton v3 : session inconnue ou perdue, message altéré…
    V3(Erreur),
}

/// Ce qui part : le corps de l'enveloppe, et s'il porte un jeton v3.
#[derive(Debug)]
pub struct Sealed {
    pub body: String,
    pub v3: bool,
}

/// La protection d'une discussion avec un contact.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Protection {
    /// Une session à double cliquet ; `pq` : établie avec ML-KEM.
    Ratchet { pq: bool },
    /// L'ancien chiffrement (le contact n'a pas la mise à jour).
    Legacy,
}

/// Les sessions avec un contact (deux au plus : voir le module).
#[derive(Default)]
struct Peer {
    sessions: Vec<Session>,
    /// La session qui sert à l'envoi (la dernière qui a servi).
    current: Option<[u8; 8]>,
    pq: PqPeer,
}

/// Le cliquet post-quantique avec un appareil (v6, [`pq6`]).
#[derive(Default, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
struct PqPeer {
    /// Sa clé ML-KEM annoncée : identifiant, clé (base64), création.
    id: String,
    ek: String,
    at: u64,
    /// La dernière des nôtres dont il s'est servi.
    told: String,
    /// La dernière des nôtres que nous lui avons annoncée, et quand.
    sent: String,
    sent_at: u64,
}

impl PqPeer {
    fn is_empty(&self) -> bool {
        *self == PqPeer::default()
    }
}

/// Les sessions d'un contact, telles qu'elles sont gardées (`kv/v3:<code>`).
#[derive(Default, Serialize, Deserialize)]
#[serde(default)]
struct StoredPeer {
    /// L'identifiant de la session d'envoi, en hexadécimal.
    current: String,
    /// Chaque session, dans le JSON de `brocolis_proto`.
    sessions: Vec<Value>,
    #[serde(skip_serializing_if = "PqPeer::is_empty")]
    pq: PqPeer,
}

fn hex(id: &[u8; 8]) -> String {
    id.iter().map(|b| format!("{b:02x}")).collect()
}

impl Peer {
    fn from_json(json: &str) -> Peer {
        let Ok(stored) = serde_json::from_str::<StoredPeer>(json) else {
            return Peer::default();
        };
        let sessions: Vec<Session> = stored
            .sessions
            .iter()
            .filter_map(|v| Session::from_json(&v.to_string()).ok())
            .take(2)
            .collect();
        let current = sessions
            .iter()
            .map(Session::session_id)
            .find(|id| hex(id) == stored.current);
        Peer {
            sessions,
            current,
            pq: stored.pq,
        }
    }

    fn to_json(&self) -> String {
        let stored = StoredPeer {
            current: self.current.as_ref().map(hex).unwrap_or_default(),
            sessions: self
                .sessions
                .iter()
                .filter_map(|s| serde_json::from_str(&s.to_json()).ok())
                .collect(),
            pq: self.pq.clone(),
        };
        serde_json::to_string(&stored).unwrap_or_default()
    }

    fn get(&mut self, id: &[u8; 8]) -> Option<&mut Session> {
        self.sessions.iter_mut().find(|s| s.session_id() == *id)
    }

    fn get_ref(&self, id: &[u8; 8]) -> Option<&Session> {
        self.sessions.iter().find(|s| s.session_id() == *id)
    }

    /// La session d'envoi : la courante, sinon la plus récente.
    fn sending(&mut self) -> Option<&mut Session> {
        let id = self
            .current
            .or_else(|| self.sessions.last().map(Session::session_id))?;
        self.get(&id)
    }

    /// Range une session (elle remplace celle de même identifiant) et en
    /// fait la session d'envoi.
    fn put(&mut self, s: Session) {
        let id = s.session_id();
        self.sessions.retain(|x| x.session_id() != id);
        self.sessions.push(s);
        if self.sessions.len() > 2 {
            self.sessions.remove(0);
        }
        self.current = Some(id);
    }
}

/// L'état v3 de l'appareil : son [`Device`] et ses sessions, contact par
/// contact. Pur : il ne garde rien lui-même ; ce qui a changé se lit avec
/// [`Engine::take_dirty`], pour être écrit avec le message qui l'a changé.
pub struct Engine {
    device: Device,
    peers: HashMap<String, Peer>,
    loaded: HashSet<String>,
    device_dirty: bool,
    dirty: HashSet<String>,
    /// Les clés ML-KEM du cliquet post-quantique (v6) ; `None` : pas de
    /// cliquet post-quantique (il s'active avec [`Engine::start_pq`]).
    pq: Option<pq6::Keys>,
    pq_dirty: bool,
    /// L'heure (secondes) de la dernière opération.
    clock: u64,
}

/// Ce qu'il faut garder après une opération.
#[derive(Debug, Default)]
pub struct Dirty {
    /// L'appareil (`kv/v3_device`), s'il a changé.
    pub device: Option<String>,
    /// Les clés du cliquet post-quantique (`kv/pq6`), si elles ont changé.
    pub pq: Option<String>,
    /// Les sessions des contacts qui ont changé (`kv/v3:<code>`).
    pub peers: Vec<(String, String)>,
}

impl Engine {
    /// L'appareil gardé, ou un neuf (avec ML-KEM) s'il n'y en a pas encore
    /// (ou s'il est illisible). Un appareil neuf est à garder tout de suite.
    pub fn start(device_json: Option<&str>, now: u64) -> Engine {
        let device = device_json.and_then(|j| Device::from_json(j).ok());
        let created = device.is_none();
        let device = device.unwrap_or_else(|| Device::generate(&mut hasard::system(), now, true));
        Engine {
            device,
            peers: HashMap::new(),
            loaded: HashSet::new(),
            device_dirty: created,
            dirty: HashSet::new(),
            pq: None,
            pq_dirty: false,
            clock: now,
        }
    }

    /// Active le cliquet post-quantique (v6, §7.17) avec les clés gardées
    /// (`kv/pq6`), ou de nouvelles.
    pub fn start_pq(&mut self, json: Option<&str>, now: u64) {
        self.pq = Some(pq6::Keys::from_json(json));
        self.maintain_pq(now);
    }

    /// Fait tourner la clé ML-KEM qui a servi (une fois par heure au plus),
    /// et efface celles qui ont fait leur temps.
    pub fn maintain_pq(&mut self, now: u64) {
        self.clock = self.clock.max(now);
        if let Some(keys) = &mut self.pq
            && keys.maintain(now, pq6::random_seed)
        {
            self.pq_dirty = true;
        }
    }

    /// L'annonce de notre clé ML-KEM, à joindre au message pour `addr` s'il
    /// ne s'en est pas encore servi (au plus une fois par heure, tant qu'il
    /// ne s'en sert pas : une version qui ne sait pas la lire).
    fn pq_advert_for(&mut self, addr: &str) -> Option<Value> {
        let keys = self.pq.as_ref()?;
        let current = keys.current()?.id.clone();
        let advert = keys.advert()?;
        let now = self.clock;
        let peer = self.peers.entry(addr.to_owned()).or_default();
        let due =
            peer.pq.sent != current || now.saturating_sub(peer.pq.sent_at) >= pq6::ROTATE_SECS;
        if peer.pq.told == current || !due {
            return None;
        }
        peer.pq.sent = current;
        peer.pq.sent_at = now;
        self.dirty.insert(addr.to_owned());
        Some(advert)
    }

    /// Retient la clé ML-KEM annoncée dans un `Wire` reçu de `addr` (la
    /// plus récente).
    fn pq_learn(&mut self, addr: &str, wire: &str) {
        if self.pq.is_none() || !wire.contains("\"pq6\"") {
            return;
        }
        let Ok(v) = serde_json::from_str::<Value>(wire) else {
            return;
        };
        let Some((id, ek, at)) = pq6::parse_advert(&v["pq6"]) else {
            return;
        };
        let peer = self.peers.entry(addr.to_owned()).or_default();
        if peer.pq.id != id && at >= peer.pq.at {
            peer.pq.id = id;
            peer.pq.ek = ek;
            peer.pq.at = at;
            self.dirty.insert(addr.to_owned());
        }
    }

    /// Le cliquet post-quantique est-il en place avec `addr` (nous
    /// enveloppons pour lui) ?
    pub fn pq_with(&self, addr: &str) -> bool {
        self.pq.is_some() && self.peers.get(addr).is_some_and(|p| !p.pq.ek.is_empty())
    }

    /// Avec un compte : avec l'un de ses appareils (en mémoire).
    pub fn pq_with_any(&self, code: &str) -> bool {
        self.peers.keys().any(|addr| {
            (addr == code || addr.split_once('/').is_some_and(|(c, _)| c == code))
                && self.pq_with(addr)
        })
    }

    /// Les clés pré-signées : renouvelées chaque semaine, les anciennes
    /// effacées un mois après. `true` : le paquet a changé (à republier).
    pub fn maintain(&mut self, now: u64) -> bool {
        if self.device.needs_rotation(now) {
            self.device.rotate(&mut hasard::system(), now);
            self.device_dirty = true;
            return true;
        }
        false
    }

    /// Le paquet à publier sur le relais, et sa signature par l'identité
    /// Talka : (paquet, signature), en base64.
    pub fn published(&self, me: &Identity) -> Option<(String, String)> {
        let bytes = self.device.bundle().ok()?.to_bytes();
        let sig = me.sign(&bundle_message(&bytes));
        (!sig.is_empty()).then(|| (b64(&bytes), sig))
    }

    /// Le paquet de cet appareil (v4), signé pour lui : (paquet, signature).
    pub fn published_device(&self, me: &Identity, dev: &str) -> Option<(String, String)> {
        let bytes = self.device.bundle().ok()?.to_bytes();
        let sig = me.sign(&device_bundle_message(dev, &bytes));
        (!sig.is_empty()).then(|| (b64(&bytes), sig))
    }

    /// Les sessions d'un contact sont-elles déjà en mémoire ?
    pub fn is_loaded(&self, code: &str) -> bool {
        self.loaded.contains(code)
    }

    /// Les sessions gardées d'un contact (`None` : aucune). Sans effet si
    /// elles sont déjà en mémoire.
    pub fn load(&mut self, code: &str, json: Option<&str>) {
        if self.loaded.insert(code.to_owned())
            && let Some(peer) = json.map(Peer::from_json)
        {
            self.peers.insert(code.to_owned(), peer);
        }
    }

    /// Ce qui a changé depuis le dernier appel.
    pub fn take_dirty(&mut self) -> Dirty {
        let device =
            std::mem::take(&mut self.device_dirty).then(|| self.device.to_json().to_string());
        let pq = if std::mem::take(&mut self.pq_dirty) {
            self.pq.as_ref().map(pq6::Keys::to_json)
        } else {
            None
        };
        let peers = std::mem::take(&mut self.dirty)
            .into_iter()
            .map(|code| {
                let json = self.peers.get(&code).map(Peer::to_json).unwrap_or_default();
                (code, json)
            })
            .collect();
        Dirty { device, pq, peers }
    }

    /// La protection actuelle avec un contact : sa session d'envoi, sinon
    /// `None` (pas encore de session).
    pub fn session_protection(&self, code: &str) -> Option<Protection> {
        let peer = self.peers.get(code)?;
        let id = peer
            .current
            .or_else(|| peer.sessions.last().map(Session::session_id))?;
        let s = peer.sessions.iter().find(|s| s.session_id() == id)?;
        Some(Protection::Ratchet {
            pq: s.is_post_quantum(),
        })
    }

    /// Une session existe-t-elle avec ce contact ?
    pub fn has_session(&self, code: &str) -> bool {
        self.peers.get(code).is_some_and(|p| !p.sessions.is_empty())
    }

    /// Oublie les sessions d'un contact : le prochain envoi en ouvre une
    /// neuve (après une session perdue de l'un ou l'autre côté).
    pub fn forget(&mut self, code: &str) {
        if self.peers.remove(code).is_some() {
            self.dirty.insert(code.to_owned());
        }
    }

    /// Chiffre `plain` (un `Wire` JSON) pour un contact : par sa session, ou
    /// une session neuve vers son paquet (`bundle`, vérifié) ; sinon, ou si
    /// le texte dépasse ce que porte un jeton v3 (200 Kio : photos, vocaux,
    /// fichiers), l'ancien format.
    pub fn seal(
        &mut self,
        me: &Identity,
        peer_code: &str,
        peer_dh: &str,
        bundle: Option<&KeyBundle>,
        plain: &str,
    ) -> Option<Sealed> {
        self.seal_to(me, peer_code, peer_code, peer_dh, bundle, plain)
    }

    /// Comme `seal`, pour un appareil d'un compte (v4) : la session est celle
    /// de son adresse (`addr`, `<code>/<dev>`), l'enveloppe celle du code.
    pub fn seal_to(
        &mut self,
        me: &Identity,
        addr: &str,
        peer_code: &str,
        peer_dh: &str,
        bundle: Option<&KeyBundle>,
        plain: &str,
    ) -> Option<Sealed> {
        let legacy =
            || crypto::seal(me, peer_code, peer_dh, plain).map(|body| Sealed { body, v3: false });
        if plain.len() > MAX_PLAINTEXT {
            return legacy();
        }
        // v6 : notre clé ML-KEM, s'il ne la connaît pas encore.
        let advert = self.pq_advert_for(addr);
        let with_advert = advert.and_then(|a| pq6::with_advert(plain, &a));
        let plain = match &with_advert {
            Some(p) if p.len() <= MAX_PLAINTEXT => p.as_str(),
            _ => plain,
        };
        let token = self.encrypt(addr, bundle, plain);
        match token {
            Some(token) => {
                let token = self.pq_wrap(addr, token);
                crypto::seal(me, peer_code, peer_dh, &token).map(|body| Sealed { body, v3: true })
            }
            None => legacy(),
        }
    }

    /// Enveloppe le jeton pour la clé ML-KEM de `addr`, si nous l'avons.
    fn pq_wrap(&self, addr: &str, token: String) -> String {
        let Some(peer) = self.peers.get(addr).filter(|_| self.pq.is_some()) else {
            return token;
        };
        if peer.pq.ek.is_empty() {
            return token;
        }
        let mut m = [0u8; 32];
        let mut nonce = [0u8; 12];
        m.copy_from_slice(&crypto::random_bytes(32));
        nonce.copy_from_slice(&crypto::random_bytes(12));
        pq6::wrap(&peer.pq.ek, &token, &m, &nonce).unwrap_or(token)
    }

    fn encrypt(&mut self, code: &str, bundle: Option<&KeyBundle>, plain: &str) -> Option<String> {
        let peer = self.peers.entry(code.to_owned()).or_default();
        if let Some(s) = peer.sending() {
            match s.encrypt(plain) {
                Ok(token) => {
                    self.dirty.insert(code.to_owned());
                    return Some(token);
                }
                // Chaîne épuisée ou état abîmé : une session neuve, si l'on
                // a le paquet.
                Err(_) => peer.sessions.clear(),
            }
        }
        let bundle = bundle?;
        let mut s = self
            .device
            .initiate(bundle, bundle.pq.is_some(), &mut hasard::system())
            .ok()?;
        let token = s.encrypt(plain).ok()?;
        peer.put(s);
        self.dirty.insert(code.to_owned());
        Some(token)
    }

    /// Ouvre une enveloppe reçue de `peer_code` : l'ancien format, ou un
    /// jeton v3 (par sa session, ou un premier message).
    pub fn open(
        &mut self,
        me: &Identity,
        peer_code: &str,
        peer_dh: &str,
        body: &str,
        now: u64,
    ) -> Opened {
        self.open_from(me, peer_code, peer_code, peer_dh, body, now)
    }

    /// Comme `open`, d'un appareil d'un compte (v4) : la session est celle
    /// de son adresse (`addr`, `<code>/<dev>`).
    pub fn open_from(
        &mut self,
        me: &Identity,
        addr: &str,
        peer_code: &str,
        peer_dh: &str,
        body: &str,
        now: u64,
    ) -> Opened {
        let Some(mut plain) = crypto::open(me, peer_code, peer_dh, body) else {
            return Opened::Failed(Failure::Envelope);
        };
        self.maintain_pq(now);
        // v6 : enveloppé par notre clé ML-KEM.
        let mut used = None;
        if pq6::is_wrapped(&plain) {
            let Some(keys) = &mut self.pq else {
                return Opened::Failed(Failure::V3(Erreur::CleInconnue));
            };
            match keys.open(&plain) {
                Ok((token, id, first)) => {
                    plain = token;
                    used = Some(id);
                    // Une clé qui sert pour la première fois : à garder.
                    self.pq_dirty |= first;
                }
                // Une clé effacée (ou d'avant une restauration) : comme une
                // session perdue, il faut réparer (lui renvoyer notre clé).
                Err(true) => return Opened::Failed(Failure::V3(Erreur::CleInconnue)),
                Err(false) => return Opened::Failed(Failure::V3(Erreur::Format)),
            }
        }
        if !is_token(&plain) {
            return Opened::Legacy(plain);
        }
        match self.decrypt(addr, &plain, now) {
            Ok(Content::Text(wire)) => {
                if let Some(id) = used {
                    let peer = self.peers.entry(addr.to_owned()).or_default();
                    if peer.pq.told != id {
                        peer.pq.told = id;
                        self.dirty.insert(addr.to_owned());
                    }
                }
                self.pq_learn(addr, &wire);
                Opened::V3(wire)
            }
            // Talka ne se sert pas (encore) des chaînes de groupe.
            Ok(Content::Distribution(_)) => Opened::Failed(Failure::V3(Erreur::Format)),
            Err(Erreur::Rejoue) => Opened::Replay,
            Err(e) => Opened::Failed(Failure::V3(e)),
        }
    }

    fn decrypt(&mut self, code: &str, text: &str, now: u64) -> Result<Content, Erreur> {
        let token = Token::decode(text)?;
        let id = token.session_id().ok_or(Erreur::SessionInconnue)?;
        let rng = &mut hasard::system();
        // v4 : une session ouverte avant de connaître ses appareils est
        // rangée sous son code ; elle passe à l'adresse de l'appareil
        // (`<code>/<dev>`) dès qu'un message dit lequel c'est.
        if let Some((base, _)) = code.split_once('/')
            && !self
                .peers
                .get(code)
                .is_some_and(|p| p.get_ref(&id).is_some())
            && let Some(old) = self.peers.get_mut(base)
            && let Some(pos) = old.sessions.iter().position(|s| s.session_id() == id)
        {
            let s = old.sessions.remove(pos);
            if old.current == Some(id) {
                old.current = None;
            }
            self.dirty.insert(base.to_owned());
            self.peers.entry(code.to_owned()).or_default().put(s);
            self.dirty.insert(code.to_owned());
        }
        let peer = self.peers.entry(code.to_owned()).or_default();
        if let Some(s) = peer.get(&id) {
            match s.decrypt(&token, now, rng) {
                Ok(content) => {
                    peer.current = Some(id);
                    self.dirty.insert(code.to_owned());
                    return Ok(content);
                }
                // Le correspondant a ouvert une session neuve (il avait
                // perdu la sienne) : elle remplace l'ancienne.
                Err(Erreur::NouvelleSession) => {}
                Err(e) => return Err(e),
            }
        }
        let Token::Initial(first) = &token else {
            return Err(Erreur::SessionInconnue);
        };
        let (s, content) = self.device.accept(first, now, rng)?;
        peer.put(s);
        self.device_dirty = true;
        self.dirty.insert(code.to_owned());
        Ok(content)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Side {
        id: Identity,
        engine: Engine,
    }

    fn side() -> Side {
        Side {
            id: Identity::create(0.0),
            engine: Engine::start(None, 1_000),
        }
    }

    fn bundle_of(s: &Side) -> KeyBundle {
        let (bundle, sig) = s.engine.published(&s.id).unwrap();
        verify_bundle(&s.id.code, &s.id.sign, &s.id.dh, &bundle, &sig).unwrap()
    }

    fn send(from: &mut Side, to: &Side, bundle: bool, plain: &str) -> Sealed {
        let b = bundle.then(|| bundle_of(to));
        from.engine
            .seal(&from.id, &to.id.code, &to.id.dh, b.as_ref(), plain)
            .unwrap()
    }

    fn recv(to: &mut Side, from: &Side, s: &Sealed) -> Opened {
        to.engine
            .open(&to.id, &from.id.code, &from.id.dh, &s.body, 2_000)
    }

    /// Le corps v3 d'une enveloppe (pour voir s'il est enveloppé en v6).
    fn inner(to: &Side, from: &Side, s: &Sealed) -> String {
        crypto::open(&to.id, &from.id.code, &from.id.dh, &s.body).unwrap()
    }

    fn text(o: Opened) -> String {
        let Opened::V3(wire) = o else { panic!("{o:?}") };
        serde_json::from_str::<Value>(&wire).unwrap()["k"].to_string()
    }

    #[test]
    fn the_post_quantum_ratchet_starts_rotates_and_heals() {
        let (mut a, mut b) = (side(), side());
        a.engine.start_pq(None, 1_000);
        b.engine.start_pq(None, 1_000);
        // A écrit le premier : son annonce part, rien n'est enveloppé.
        let m1 = send(&mut a, &b, true, "{\"k\":1}");
        assert!(is_token(&inner(&b, &a, &m1)));
        assert_eq!(text(recv(&mut b, &a, &m1)), "1");
        assert!(b.engine.pq_with(&a.id.code));
        // B répond, enveloppé pour la clé de A, avec sa propre annonce.
        let m2 = send(&mut b, &a, false, "{\"k\":2}");
        assert!(pq6::is_wrapped(&inner(&a, &b, &m2)));
        assert_eq!(text(recv(&mut a, &b, &m2)), "2");
        // A sait que B a sa clé : plus d'annonce ; et A enveloppe aussi.
        let m3 = send(&mut a, &b, false, "{\"k\":3}");
        let plain3 = inner(&b, &a, &m3);
        assert!(pq6::is_wrapped(&plain3));
        assert!(plain3.len() < 3_000, "sans annonce : {}", plain3.len());
        assert_eq!(text(recv(&mut b, &a, &m3)), "3");
        // Une heure après, la clé de A tourne ; B l'apprend et s'en sert.
        let old = a.engine.pq.as_ref().unwrap().current().unwrap().id.clone();
        a.engine.maintain_pq(1_000 + pq6::ROTATE_SECS);
        assert_ne!(a.engine.pq.as_ref().unwrap().current().unwrap().id, old);
        let m4 = send(&mut a, &b, false, "{\"k\":4}");
        assert_eq!(text(recv(&mut b, &a, &m4)), "4");
        let m5 = send(&mut b, &a, false, "{\"k\":5}");
        assert_eq!(text(recv(&mut a, &b, &m5)), "5");
        let new = a.engine.pq.as_ref().unwrap().current().unwrap().id.clone();
        assert_eq!(a.engine.peers[&b.id.code].pq.told, new);
        // Tout se garde et se relit.
        let dirty = a.engine.take_dirty();
        let keys = dirty.pq.expect("clés à garder");
        let peer = dirty
            .peers
            .iter()
            .find(|(c, _)| *c == b.id.code)
            .unwrap()
            .1
            .clone();
        let device = a.engine.device.to_json().to_string();
        let mut again = Engine::start(Some(&device), 1_000);
        again.start_pq(Some(&keys), 1_000 + pq6::ROTATE_SECS);
        again.load(&b.id.code, Some(&peer));
        a.engine = again;
        let m6 = send(&mut b, &a, false, "{\"k\":6}");
        assert_eq!(text(recv(&mut a, &b, &m6)), "6");
        // Ses clés perdues (une restauration) : à réparer, comme une session.
        a.engine.pq = Some(pq6::Keys::default());
        a.engine.maintain_pq(10_000);
        let m7 = send(&mut b, &a, false, "{\"k\":7}");
        assert_eq!(
            recv(&mut a, &b, &m7),
            Opened::Failed(Failure::V3(Erreur::CleInconnue))
        );
    }

    #[test]
    fn without_the_post_quantum_ratchet_nothing_changes() {
        let (mut a, mut b) = (side(), side());
        a.engine.start_pq(None, 1_000);
        // B (une version d'avant, ou un iPhone sans ML-KEM) ignore l'annonce.
        let m1 = send(&mut a, &b, true, "{\"k\":1}");
        assert_eq!(text(recv(&mut b, &a, &m1)), "1");
        let m2 = send(&mut b, &a, false, "{\"k\":2}");
        assert!(is_token(&inner(&a, &b, &m2)));
        assert_eq!(text(recv(&mut a, &b, &m2)), "2");
        // A n'enveloppe rien pour lui.
        let m3 = send(&mut a, &b, false, "{\"k\":3}");
        assert!(is_token(&inner(&b, &a, &m3)));
        assert!(!a.engine.pq_with(&b.id.code));
    }

    #[test]
    fn an_old_replayed_message_never_resets_the_session() {
        // Un message d'une chaîne plus vieille que celles que la session
        // retient (16 cliquets), rejoué par le relais : refusé, sans
        // réparation (sinon rejouer suffirait à faire tomber la session).
        let (mut a, mut b) = (side(), side());
        let first = send(&mut a, &b, true, "{\"k\":\"bonjour\"}");
        assert!(matches!(recv(&mut b, &a, &first), Opened::V3(_)));
        let old = send(&mut a, &b, false, "{\"k\":\"vieux\"}");
        assert!(matches!(recv(&mut b, &a, &old), Opened::V3(_)));
        for i in 0..20 {
            let r = send(&mut b, &a, false, &format!("{{\"r\":{i}}}"));
            assert!(matches!(recv(&mut a, &b, &r), Opened::V3(_)));
            let m = send(&mut a, &b, false, &format!("{{\"m\":{i}}}"));
            assert!(matches!(recv(&mut b, &a, &m), Opened::V3(_)));
        }
        match recv(&mut b, &a, &old) {
            Opened::Replay => {}
            Opened::Failed(Failure::V3(e)) => assert!(!needs_repair(&e), "{e}"),
            other => panic!("{other:?}"),
        }
        // La session tient toujours.
        let after = send(&mut a, &b, false, "{\"k\":\"après\"}");
        assert_eq!(
            recv(&mut b, &a, &after),
            Opened::V3("{\"k\":\"après\"}".into())
        );
    }

    #[test]
    fn only_a_lost_session_is_repaired() {
        assert!(needs_repair(&Erreur::SessionInconnue));
        assert!(needs_repair(&Erreur::CleInconnue));
        assert!(!needs_repair(&Erreur::Dechiffrement));
        assert!(!needs_repair(&Erreur::Rejoue));
    }

    #[test]
    fn bundle_is_bound_to_the_talka_code() {
        let a = side();
        let b = side();
        let (bundle, sig) = a.engine.published(&a.id).unwrap();
        assert_eq!(
            unb64(&bundle).unwrap().len(),
            brocolis_proto::paquet::BUNDLE_LEN_PQ
        );
        assert!(verify_bundle(&a.id.code, &a.id.sign, &a.id.dh, &bundle, &sig).is_some());
        // Signé par un autre, ou présenté sous un autre code : refusé.
        let (_, other) = b.engine.published(&b.id).unwrap();
        assert!(verify_bundle(&a.id.code, &a.id.sign, &a.id.dh, &bundle, &other).is_none());
        assert!(verify_bundle(&b.id.code, &b.id.sign, &b.id.dh, &bundle, &sig).is_none());
        assert!(verify_bundle(&b.id.code, &a.id.sign, &a.id.dh, &bundle, &sig).is_none());
        // Un octet changé : refusé.
        let mut bytes = unb64(&bundle).unwrap();
        bytes[40] ^= 1;
        assert!(verify_bundle(&a.id.code, &a.id.sign, &a.id.dh, &b64(&bytes), &sig).is_none());
        // Re-signé par la bonne identité, il reste refusé (Ed25519).
        let resigned = a.id.sign(&bundle_message(&bytes));
        assert!(verify_bundle(&a.id.code, &a.id.sign, &a.id.dh, &b64(&bytes), &resigned).is_none());
    }

    #[test]
    fn first_message_replies_disorder_and_replay() {
        let (mut a, mut b) = (side(), side());
        let m1 = send(&mut a, &b, true, "{\"k\":\"un\"}");
        let m2 = send(&mut a, &b, false, "{\"k\":\"deux\"}");
        assert!(m1.v3 && m2.v3);
        // Dans le désordre : le second premier message d'abord.
        assert_eq!(recv(&mut b, &a, &m2), Opened::V3("{\"k\":\"deux\"}".into()));
        assert_eq!(recv(&mut b, &a, &m1), Opened::V3("{\"k\":\"un\"}".into()));
        // Un doublon (le relais renvoie) : ignoré.
        assert_eq!(recv(&mut b, &a, &m1), Opened::Replay);
        assert_eq!(
            b.engine.session_protection(&a.id.code),
            Some(Protection::Ratchet { pq: true })
        );
        // La réponse, sans paquet : la session suffit.
        let r = send(&mut b, &a, false, "{\"k\":\"réponse\"}");
        assert!(r.v3);
        assert_eq!(
            recv(&mut a, &b, &r),
            Opened::V3("{\"k\":\"réponse\"}".into())
        );
        let m3 = send(&mut a, &b, false, "{\"k\":\"trois\"}");
        assert_eq!(
            recv(&mut b, &a, &m3),
            Opened::V3("{\"k\":\"trois\"}".into())
        );
        assert_eq!(recv(&mut b, &a, &m3), Opened::Replay);
        // Pas pour lui : illisible.
        let mut c = side();
        assert_eq!(recv(&mut c, &a, &m3), Opened::Failed(Failure::Envelope));
    }

    #[test]
    fn legacy_without_bundle_and_for_large_payloads() {
        let (mut a, mut b) = (side(), side());
        let old = send(&mut a, &b, false, "{\"k\":\"ancien\"}");
        assert!(!old.v3);
        // Exactement l'ancien format : `crypto::open` le lit seul.
        assert_eq!(
            crypto::open(&b.id, &a.id.code, &a.id.dh, &old.body).as_deref(),
            Some("{\"k\":\"ancien\"}")
        );
        assert_eq!(
            recv(&mut b, &a, &old),
            Opened::Legacy("{\"k\":\"ancien\"}".into())
        );
        // Une session existe, mais une photo de 300 Kio ne tient pas dans un
        // jeton v3 : elle part dans l'ancien format.
        let _ = send(&mut a, &b, true, "{}");
        let big = format!("{{\"data\":\"{}\"}}", "x".repeat(300 * 1024));
        let sealed = send(&mut a, &b, false, &big);
        assert!(!sealed.v3);
        assert_eq!(recv(&mut b, &a, &sealed), Opened::Legacy(big));
    }

    #[test]
    fn both_sides_start_at_once() {
        let (mut a, mut b) = (side(), side());
        let from_a = send(&mut a, &b, true, "{\"k\":\"a\"}");
        let from_b = send(&mut b, &a, true, "{\"k\":\"b\"}");
        assert_eq!(
            recv(&mut b, &a, &from_a),
            Opened::V3("{\"k\":\"a\"}".into())
        );
        assert_eq!(
            recv(&mut a, &b, &from_b),
            Opened::V3("{\"k\":\"b\"}".into())
        );
        for i in 0..4 {
            let x = send(&mut a, &b, false, &format!("{{\"a\":{i}}}"));
            let y = send(&mut b, &a, false, &format!("{{\"b\":{i}}}"));
            assert_eq!(recv(&mut a, &b, &y), Opened::V3(format!("{{\"b\":{i}}}")));
            assert_eq!(recv(&mut b, &a, &x), Opened::V3(format!("{{\"a\":{i}}}")));
        }
    }

    #[test]
    fn states_survive_a_reload_and_a_lost_session_heals() {
        let (mut a, mut b) = (side(), side());
        let m = send(&mut a, &b, true, "{\"k\":1}");
        assert!(matches!(recv(&mut b, &a, &m), Opened::V3(_)));
        // Ce qui a changé, et rien de plus.
        let dirty = b.engine.take_dirty();
        assert!(dirty.device.is_some());
        assert_eq!(dirty.peers.len(), 1);
        assert!(b.engine.take_dirty().peers.is_empty());
        // Relu depuis ce qui a été gardé.
        let device = dirty.device.unwrap();
        let mut b2 = Side {
            id: b.id.clone(),
            engine: Engine::start(Some(&device), 2_000),
        };
        assert!(!b2.engine.is_loaded(&a.id.code));
        b2.engine.load(&a.id.code, Some(&dirty.peers[0].1));
        assert!(b2.engine.take_dirty().device.is_none());
        let r = send(&mut b2, &a, false, "{\"k\":2}");
        assert!(r.v3);
        assert_eq!(recv(&mut a, &b2, &r), Opened::V3("{\"k\":2}".into()));
        // Le premier message rejoué après relecture : toujours ignoré.
        assert_eq!(recv(&mut b2, &a, &m), Opened::Replay);

        // B perd ses sessions (données effacées) : le message suivant de A
        // est illisible, B en ouvre une neuve, que A accepte.
        let mut b3 = Side {
            id: b.id.clone(),
            engine: Engine::start(Some(&device), 3_000),
        };
        let lost = send(&mut a, &b3, false, "{\"k\":3}");
        assert!(matches!(
            recv(&mut b3, &a, &lost),
            Opened::Failed(Failure::V3(_))
        ));
        let heal = send(&mut b3, &a, true, "{\"k\":\"profil\"}");
        assert_eq!(
            recv(&mut a, &b3, &heal),
            Opened::V3("{\"k\":\"profil\"}".into())
        );
        let after = send(&mut a, &b3, false, "{\"k\":4}");
        assert_eq!(recv(&mut b3, &a, &after), Opened::V3("{\"k\":4}".into()));
    }

    /// v4 : le paquet d'un appareil est lié à cet appareil ; une session par
    /// appareil d'un même compte (les adresses `<code>/<dev>`).
    #[test]
    fn device_bundles_and_sessions_per_device() {
        const D1: &str = "00000000000000b1";
        const D2: &str = "00000000000000b2";
        let mut a = side();
        let mut b1 = side();
        // B2 : un autre appareil du compte de B1 (la même identité, son
        // propre appareil v3).
        let mut b2 = Side {
            id: b1.id.clone(),
            engine: Engine::start(None, 1_000),
        };
        let b = b1.id.clone();
        let (bundle, sig) = b1.engine.published_device(&b, D1).unwrap();
        assert!(verify_device_bundle(&b.code, &b.sign, &b.dh, D1, &bundle, &sig).is_some());
        // Présenté pour un autre appareil, ou comme un paquet d'avant : refusé.
        assert!(verify_device_bundle(&b.code, &b.sign, &b.dh, D2, &bundle, &sig).is_none());
        assert!(verify_bundle(&b.code, &b.sign, &b.dh, &bundle, &sig).is_none());
        let p1 = verify_device_bundle(&b.code, &b.sign, &b.dh, D1, &bundle, &sig).unwrap();
        let (bundle2, sig2) = b2.engine.published_device(&b, D2).unwrap();
        let p2 = verify_device_bundle(&b.code, &b.sign, &b.dh, D2, &bundle2, &sig2).unwrap();
        // A écrit à chacun : une session par adresse, l'enveloppe des codes.
        let (addr1, addr2) = (format!("{}/{D1}", b.code), format!("{}/{D2}", b.code));
        let m1 = a
            .engine
            .seal_to(&a.id, &addr1, &b.code, &b.dh, Some(&p1), "{\"k\":1}")
            .unwrap();
        let m2 = a
            .engine
            .seal_to(&a.id, &addr2, &b.code, &b.dh, Some(&p2), "{\"k\":2}")
            .unwrap();
        assert!(m1.v3 && m2.v3);
        let from = format!("{}/00000000000000a1", a.id.code);
        let (ac, adh) = (a.id.code.clone(), a.id.dh.clone());
        let open = |side: &mut Side, body: &str| {
            side.engine
                .open_from(&side.id, &from, &ac, &adh, body, 2_000)
        };
        assert_eq!(open(&mut b1, &m1.body), Opened::V3("{\"k\":1}".into()));
        assert_eq!(open(&mut b2, &m2.body), Opened::V3("{\"k\":2}".into()));
        // Chacun ne lit que la sienne.
        assert!(matches!(open(&mut b1, &m2.body), Opened::Failed(_)));
    }

    /// v4 : B (un appareil) écrit à A sans connaître ses appareils (à son
    /// code) ; A, qui sait de quel appareil vient B, range la session sous
    /// son adresse ; la réponse de A, dans cette session, se lit chez B
    /// qui la cherche sous l'adresse de l'appareil de A.
    #[test]
    fn a_session_opened_before_knowing_devices_moves_to_the_device() {
        let (mut a, mut b) = (side(), side());
        let a_dev = format!("{}/00000000000000a1", a.id.code);
        let b_dev = format!("{}/00000000000000b1", b.id.code);
        // B écrit à « A » (le code seul), avec le paquet de l'appareil de A.
        let first = b
            .engine
            .seal(
                &b.id,
                &a.id.code,
                &a.id.dh,
                Some(&bundle_of(&a)),
                "{\"k\":1}",
            )
            .unwrap();
        // A sait que c'est l'appareil b1 : la session est à l'adresse de b1.
        assert_eq!(
            a.engine
                .open_from(&a.id, &b_dev, &b.id.code, &b.id.dh, &first.body, 2_000),
            Opened::V3("{\"k\":1}".into())
        );
        let reply = a
            .engine
            .seal_to(&a.id, &b_dev, &b.id.code, &b.id.dh, None, "{\"k\":2}")
            .unwrap();
        assert!(reply.v3);
        // B lit la réponse « de l'appareil a1 » : la session, rangée sous le
        // code de A, passe à cette adresse.
        assert_eq!(
            b.engine
                .open_from(&b.id, &a_dev, &a.id.code, &a.id.dh, &reply.body, 2_000),
            Opened::V3("{\"k\":2}".into())
        );
        assert!(b.engine.has_session(&a_dev));
        assert!(!b.engine.has_session(&a.id.code));
        let again = b
            .engine
            .seal_to(&b.id, &a_dev, &a.id.code, &a.id.dh, None, "{\"k\":3}")
            .unwrap();
        assert!(again.v3);
        assert_eq!(
            a.engine
                .open_from(&a.id, &b_dev, &b.id.code, &b.id.dh, &again.body, 2_000),
            Opened::V3("{\"k\":3}".into())
        );
    }

    #[test]
    fn rotation_keeps_old_first_messages_readable() {
        let (mut a, mut b) = (side(), side());
        let old_bundle = bundle_of(&b);
        assert!(!b.engine.maintain(1_000));
        assert!(
            b.engine
                .maintain(1_000 + brocolis_proto::paquet::PREKEY_ROTATION)
        );
        assert_ne!(bundle_of(&b), old_bundle);
        let m = a
            .engine
            .seal(&a.id, &b.id.code, &b.id.dh, Some(&old_bundle), "{}")
            .unwrap();
        assert_eq!(recv(&mut b, &a, &m), Opened::V3("{}".into()));
    }
}
