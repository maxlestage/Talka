//! Les messages « double cliquet, clé partagée » du clavier Brocolis 🥦 :
//! des jetons v3 (`[[BROCOLIS:v3:…]]`) en mode PSK. Le premier message ouvre
//! une session à partir d'une clé Brocolis partagée ; les suivants changent
//! de clé à chaque fois (double cliquet). Talka les lit comme l'app Brocolis
//! (`BrocolisV3.open`) : une session connue par son identifiant, sinon un
//! premier message essayé avec chaque clé de la discussion.
//!
//! Talka écrit aussi dans ce format, pour que le clavier « déchiffre » ce
//! qui part d'ici : une discussion à deux, avec une seule clé Brocolis,
//! envoie par la session de cette clé (celle que l'autre a ouverte, sinon une
//! neuve). Et il garde le jeton d'origine de chaque message ouvert ou
//! scellé, pour « Copier chiffré 🥦 » (à coller dans le clavier).
//!
//! L'état (les sessions, la garde contre le rejeu des premiers messages, les
//! jetons d'origine) reste sur cet appareil : il ne va pas dans la sauvegarde, comme les
//! sessions v3 de Talka (restauré plus tard, il reprendrait des clés de
//! message déjà servies). Le même que l'iPhone (`BrocolisPsk`).

use brocolis_proto::{Content, Erreur, ReplayGuard, Session, Token, hasard, jeton_v3};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// La ligne de l'état dans le magasin (`kv`).
pub const KV: &str = "brocolis3";

/// Au plus tant de sessions gardées (les plus anciennes partent).
const MAX_SESSIONS: usize = 64;
/// Au plus tant de jetons d'origine gardés (les plus anciens partent).
const MAX_ORIGINALS: usize = 500;

#[derive(Default, Serialize, Deserialize)]
struct Stored {
    #[serde(default)]
    sessions: Vec<Value>,
    #[serde(default)]
    guard: ReplayGuard,
    /// Les messages déjà reçus ont été rouverts une fois (ceux d'avant que
    /// Talka sache lire ces jetons).
    #[serde(default)]
    scanned: bool,
    /// La session de chaque clé (son identifiant de clé → la session).
    #[serde(default)]
    by_key: Vec<(String, String)>,
    /// Les jetons d'origine des messages ouverts ou scellés ici.
    #[serde(default)]
    originals: Vec<(String, String)>,
}

/// Les sessions PSK ouvertes par des messages reçus.
#[derive(Default)]
pub struct Psk {
    sessions: Vec<Session>,
    guard: ReplayGuard,
    scanned: bool,
    by_key: Vec<(String, String)>,
    originals: Vec<(String, String)>,
    dirty: bool,
}

impl Psk {
    /// Relit l'état gardé ; vide s'il n'y en a pas (ou s'il est illisible).
    pub fn from_json(json: Option<&str>) -> Psk {
        let stored: Stored = json
            .and_then(|j| serde_json::from_str(j).ok())
            .unwrap_or_default();
        Psk {
            sessions: stored
                .sessions
                .iter()
                .filter_map(|v| Session::from_json(&v.to_string()).ok())
                .collect(),
            guard: stored.guard,
            scanned: stored.scanned,
            by_key: stored.by_key,
            originals: stored.originals,
            dirty: false,
        }
    }

    pub fn to_json(&self) -> String {
        let stored = Stored {
            sessions: self
                .sessions
                .iter()
                .filter_map(|s| serde_json::from_str(&s.to_json()).ok())
                .collect(),
            guard: self.guard.clone(),
            scanned: self.scanned,
            by_key: self.by_key.clone(),
            originals: self.originals.clone(),
        };
        serde_json::to_string(&stored).unwrap_or_default()
    }

    /// Les messages déjà reçus restent à rouvrir une fois ; `true` la première
    /// fois seulement (l'état passe alors à « fait », à écrire).
    pub fn first_scan(&mut self) -> bool {
        if self.scanned {
            return false;
        }
        self.scanned = true;
        self.dirty = true;
        true
    }

    /// L'état a changé depuis la dernière fois (à écrire).
    pub fn take_dirty(&mut self) -> bool {
        std::mem::take(&mut self.dirty)
    }

    /// Ouvre les jetons v3 de `text` avec les sessions connues, ou un premier
    /// message avec l'une des `keys` (les clés Brocolis de la discussion).
    /// Renvoie le texte (jetons ouverts remplacés par leur clair) et le
    /// nombre de jetons ouverts. Un jeton qui ne s'ouvre pas reste tel quel.
    pub fn open(&mut self, text: &str, keys: &[[u8; 32]], now: u64) -> (String, usize) {
        let mut out = text.to_owned();
        let mut opened = 0;
        for (raw, token) in jeton_v3::find_all(text) {
            let Ok(token) = token else { continue };
            if let Some(plain) = self.open_token(&token, keys, now) {
                out = out.replacen(raw, &plain, 1);
                opened += 1;
            }
        }
        (out, opened)
    }

    fn open_token(&mut self, token: &Token, keys: &[[u8; 32]], now: u64) -> Option<String> {
        let id = token.session_id()?;
        let rng = &mut hasard::system();
        if let Some(s) = self.sessions.iter_mut().find(|s| s.session_id() == id) {
            match s.decrypt(token, now, rng) {
                Ok(content) => {
                    self.dirty = true;
                    return Some(text_of(content));
                }
                // L'autre a ouvert une session neuve (il avait perdu la sienne) :
                // un premier message s'essaie comme tel, plus bas.
                Err(Erreur::NouvelleSession) => {}
                Err(_) => return None,
            }
        }
        let Token::Initial(first) = token else {
            return None;
        };
        if first.mode != brocolis_proto::Mode::Psk || keys.is_empty() {
            return None;
        }
        // Clé par clé, comme l'app Brocolis : on sait laquelle a ouvert.
        for key in keys {
            match Session::accept_psk(&[*key], first, &mut self.guard, now, rng) {
                Ok((session, content)) => {
                    self.keep(session, key);
                    return Some(text_of(content));
                }
                Err(Erreur::SessionInconnue) => continue,
                Err(_) => return None,
            }
        }
        None
    }

    /// Garde une session, et en fait celle de `key` (la plus récente sert).
    fn keep(&mut self, session: Session, key: &[u8; 32]) {
        let sid = hex(&session.session_id());
        self.sessions
            .retain(|s| s.session_id() != session.session_id());
        self.sessions.push(session);
        if self.sessions.len() > MAX_SESSIONS {
            self.sessions.remove(0);
        }
        let kid = key_id(key);
        self.by_key.retain(|(k, _)| *k != kid);
        self.by_key.push((kid, sid));
        self.dirty = true;
    }

    /// Chiffre `text` au format du clavier (« double cliquet, clé
    /// partagée ») par la session de `key` : celle que l'autre a ouverte, ou
    /// une neuve. Les passages `((…))` seuls s'il y en a (comme
    /// `jetons::seal`). `None` si rien n'a pu être chiffré.
    pub fn seal(&mut self, text: &str, key: &[u8; 32]) -> Option<String> {
        let kid = key_id(key);
        let current = self
            .by_key
            .iter()
            .find(|(k, _)| *k == kid)
            .map(|(_, sid)| sid.clone());
        let known = current.and_then(|sid| {
            self.sessions
                .iter()
                .position(|s| hex(&s.session_id()) == sid)
        });
        let i = match known {
            Some(i) => i,
            None => {
                let s = Session::initiate_psk(key, &mut hasard::system()).ok()?;
                self.keep(s, key);
                self.sessions.len() - 1
            }
        };
        let session = &mut self.sessions[i];
        let mut failed = false;
        let sealed = crate::jetons::seal_with(text, |clear| {
            let t = session.encrypt(clear).ok();
            failed |= t.is_none();
            t
        });
        self.dirty = true;
        (!failed && sealed != text).then_some(sealed)
    }

    /// Retient le jeton d'origine du message `id` (ouvert ou scellé ici).
    pub fn remember(&mut self, id: &str, token: &str) {
        self.originals.retain(|(k, _)| k != id);
        self.originals.push((id.to_owned(), token.to_owned()));
        if self.originals.len() > MAX_ORIGINALS {
            self.originals.remove(0);
        }
        self.dirty = true;
    }

    /// Le jeton d'origine du message `id`, s'il est connu.
    pub fn original(&self, id: &str) -> Option<&str> {
        self.originals
            .iter()
            .rev()
            .find(|(k, _)| k == id)
            .map(|(_, t)| t.as_str())
    }
}

/// L'identifiant d'une clé (8 premiers octets de son SHA-256, en hex) : la
/// clé elle-même ne va pas dans cet état.
fn key_id(key: &[u8; 32]) -> String {
    use sha2::{Digest, Sha256};
    hex(&Sha256::digest(key)[..8])
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Le clair d'un message ; une chaîne de groupe (type 0x03) ne s'affiche pas.
fn text_of(content: Content) -> String {
    match content {
        Content::Text(t) => t,
        Content::Distribution(_) => String::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Ce que fait le clavier Brocolis : une session PSK, puis des messages.
    fn keyboard(key: &[u8; 32]) -> Session {
        Session::initiate_psk(key, &mut hasard::system()).unwrap()
    }

    #[test]
    fn reads_the_keyboards_double_ratchet() {
        let key = [7u8; 32];
        let other = [9u8; 32];
        let mut kb = keyboard(&key);
        let first = kb.encrypt("Bonjour 🥦").unwrap();
        let second = kb.encrypt("Encore un").unwrap();

        let mut psk = Psk::default();
        // Sans la bonne clé : rien ne s'ouvre, le texte reste tel quel.
        assert_eq!(psk.open(&first, &[other], 100), (first.clone(), 0));
        // Avec elle : le premier message ouvre la session…
        assert_eq!(
            psk.open(&format!("Lis : {first}"), &[other, key], 100),
            ("Lis : Bonjour 🥦".to_owned(), 1)
        );
        assert!(psk.take_dirty());
        // … et les suivants s'ouvrent par elle, même sans clé.
        assert_eq!(psk.open(&second, &[], 101).1, 1);
        // Un premier message rejoué ne rouvre rien.
        assert_eq!(psk.open(&first, &[key], 102).1, 0);

        // L'état survit à une relecture (le navigateur rouvert).
        let mut back = Psk::from_json(Some(&psk.to_json()));
        let third = kb.encrypt("Après rechargement").unwrap();
        assert_eq!(back.open(&third, &[], 103).0, "Après rechargement");
        // Une réponse de Talka par cette session se lirait chez le clavier.
        assert_eq!(back.sessions.len(), 1);
        // Les messages d'avant ne se rouvrent qu'une fois.
        assert!(back.first_scan());
        assert!(!Psk::from_json(Some(&back.to_json())).first_scan());
    }

    #[test]
    fn talka_writes_what_the_keyboard_reads() {
        let key = [7u8; 32];
        let mut talka = Psk::default();
        let first = talka.seal("Depuis Talka 🥦", &key).unwrap();
        let second = talka.seal("Rendez-vous ((au parc)) !", &key).unwrap();
        assert!(first.starts_with("[[BROCOLIS:v3:"));
        assert!(second.starts_with("Rendez-vous [[BROCOLIS:v3:") && !second.contains("parc"));
        // Le clavier (la référence) ouvre le premier message avec la clé…
        let Ok(Token::Initial(i)) = Token::decode(&first) else {
            panic!()
        };
        let rng = &mut hasard::system();
        let (mut kb, c) =
            Session::accept_psk(&[key], &i, &mut ReplayGuard::default(), 0, rng).unwrap();
        assert_eq!(text_of(c), "Depuis Talka 🥦");
        // … puis la suite par la session.
        let token = second
            .trim_start_matches("Rendez-vous ")
            .trim_end_matches(" !");
        assert_eq!(text_of(kb.decrypt_text(token, 0, rng).unwrap()), "au parc");
        // Sa réponse revient par la même session chez Talka, qui continue.
        let reply = kb.encrypt("Bien reçu").unwrap();
        assert_eq!(talka.open(&reply, &[], 1).0, "Bien reçu");
        assert_eq!(talka.sessions.len(), 1);
        assert!(talka.seal("Encore", &key).is_some());
        assert_eq!(talka.sessions.len(), 1);
    }

    #[test]
    fn a_keyboard_session_is_answered_in_it() {
        let key = [7u8; 32];
        let mut kb = keyboard(&key);
        let mut talka = Psk::default();
        talka.open(&kb.encrypt("Salut").unwrap(), &[key], 0);
        // Talka répond par la session que le clavier a ouverte.
        let answer = talka.seal("Salut à toi", &key).unwrap();
        assert_eq!(
            text_of(kb.decrypt_text(&answer, 0, &mut hasard::system()).unwrap()),
            "Salut à toi"
        );
        assert_eq!(talka.sessions.len(), 1);
    }

    #[test]
    fn originals_are_kept_for_copying() {
        let mut psk = Psk::default();
        psk.remember("c/1", "[[BROCOLIS:v3:AQ]]");
        let back = Psk::from_json(Some(&psk.to_json()));
        assert_eq!(back.original("c/1"), Some("[[BROCOLIS:v3:AQ]]"));
        assert_eq!(back.original("c/2"), None);
    }

    #[test]
    fn plain_text_and_bad_state_are_harmless() {
        let mut psk = Psk::from_json(Some("pas du json"));
        assert_eq!(
            psk.open("rien à ouvrir", &[[1; 32]], 0),
            ("rien à ouvrir".to_owned(), 0)
        );
        assert_eq!(psk.open("[[BROCOLIS:v3:AAAA]]", &[[1; 32]], 0).1, 0);
        assert!(!psk.take_dirty());
    }
}
