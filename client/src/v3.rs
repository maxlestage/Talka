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
//!   n'a pas la mise à jour, l'iPhone actuel), l'ancien format, exactement.
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
use brocolis_proto::session::MAX_PLAINTEXT;
use brocolis_proto::{Content, Device, Erreur, Session, Token, hasard, jeton_v3};
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::crypto::{self, Identity, b64, unb64};

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
}

/// Les sessions d'un contact, telles qu'elles sont gardées (`kv/v3:<code>`).
#[derive(Default, Serialize, Deserialize)]
#[serde(default)]
struct StoredPeer {
    /// L'identifiant de la session d'envoi, en hexadécimal.
    current: String,
    /// Chaque session, dans le JSON de `brocolis_proto`.
    sessions: Vec<Value>,
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
        Peer { sessions, current }
    }

    fn to_json(&self) -> String {
        let stored = StoredPeer {
            current: self.current.as_ref().map(hex).unwrap_or_default(),
            sessions: self
                .sessions
                .iter()
                .filter_map(|s| serde_json::from_str(&s.to_json()).ok())
                .collect(),
        };
        serde_json::to_string(&stored).unwrap_or_default()
    }

    fn get(&mut self, id: &[u8; 8]) -> Option<&mut Session> {
        self.sessions.iter_mut().find(|s| s.session_id() == *id)
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
}

/// Ce qu'il faut garder après une opération.
#[derive(Debug, Default)]
pub struct Dirty {
    /// L'appareil (`kv/v3_device`), s'il a changé.
    pub device: Option<String>,
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
        }
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
        let peers = std::mem::take(&mut self.dirty)
            .into_iter()
            .map(|code| {
                let json = self.peers.get(&code).map(Peer::to_json).unwrap_or_default();
                (code, json)
            })
            .collect();
        Dirty { device, peers }
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
        let legacy =
            || crypto::seal(me, peer_code, peer_dh, plain).map(|body| Sealed { body, v3: false });
        if plain.len() > MAX_PLAINTEXT {
            return legacy();
        }
        let token = self.encrypt(peer_code, bundle, plain);
        match token {
            Some(token) => {
                crypto::seal(me, peer_code, peer_dh, &token).map(|body| Sealed { body, v3: true })
            }
            None => legacy(),
        }
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
        let Some(plain) = crypto::open(me, peer_code, peer_dh, body) else {
            return Opened::Failed(Failure::Envelope);
        };
        if !is_token(&plain) {
            return Opened::Legacy(plain);
        }
        match self.decrypt(peer_code, &plain, now) {
            Ok(Content::Text(wire)) => Opened::V3(wire),
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
