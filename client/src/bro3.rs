//! Les messages « double cliquet, clé partagée » du clavier Brocolis 🥦 :
//! des jetons v3 (`[[BROCOLIS:v3:…]]`) en mode PSK. Le premier message ouvre
//! une session à partir d'une clé Brocolis partagée ; les suivants changent
//! de clé à chaque fois (double cliquet). Talka les lit comme l'app Brocolis
//! (`BrocolisV3.open`) : une session connue par son identifiant, sinon un
//! premier message essayé avec chaque clé de la discussion.
//!
//! L'état (les sessions, la garde contre le rejeu des premiers messages)
//! reste sur cet appareil : il ne va pas dans la sauvegarde, comme les
//! sessions v3 de Talka (restauré plus tard, il reprendrait des clés de
//! message déjà servies). Le même que l'iPhone (`BrocolisPsk`).

use brocolis_proto::{Content, Erreur, ReplayGuard, Session, Token, hasard, jeton_v3};
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// La ligne de l'état dans le magasin (`kv`).
pub const KV: &str = "brocolis3";

/// Au plus tant de sessions gardées (les plus anciennes partent).
const MAX_SESSIONS: usize = 64;

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
}

/// Les sessions PSK ouvertes par des messages reçus.
#[derive(Default)]
pub struct Psk {
    sessions: Vec<Session>,
    guard: ReplayGuard,
    scanned: bool,
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
        let (session, content) =
            Session::accept_psk(keys, first, &mut self.guard, now, rng).ok()?;
        self.sessions
            .retain(|s| s.session_id() != session.session_id());
        self.sessions.push(session);
        if self.sessions.len() > MAX_SESSIONS {
            self.sessions.remove(0);
        }
        self.dirty = true;
        Some(text_of(content))
    }
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
