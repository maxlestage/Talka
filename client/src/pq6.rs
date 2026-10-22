//! Le cliquet post-quantique de Talka (v6, `docs/SECURITE.md` §7.17).
//!
//! La session v3 est établie en hybride X25519 + ML-KEM-768, mais ensuite
//! son cliquet ne renouvelle ses secrets que par X25519 : quelqu'un qui
//! volerait l'état d'une session, puis enregistrerait la suite pour la
//! casser un jour avec un ordinateur quantique, la lirait. Cette couche-ci
//! guérit aussi contre lui :
//!
//! - **Chaque appareil a une clé ML-KEM-768 qui tourne** : une neuve dès
//!   que la courante a servi et a plus d'une heure. Les anciennes restent
//!   30 jours (des messages en retard), puis sont effacées.
//! - **Il l'annonce** dans ses messages v3 (le champ `pq6` du `Wire`,
//!   `{"id","ek","at"}`) à chaque appareil qui ne s'en est pas encore servi.
//!   Un ancien client ignore ce champ.
//! - **Chaque jeton v3 vers un appareil dont on connaît la clé est enveloppé**
//!   par une encapsulation neuve : `[[TALKA:PQ6:<base64>]]`, avec
//!   `1 ‖ id (8) ‖ CT (1 088) ‖ nonce (12) ‖ AES-256-GCM(K, jeton)`,
//!   `K = HKDF-SHA256(SS, info = "talka-pq6-cle:" ‖ id ‖ CT)`, données
//!   associées `"talka-pq6:" ‖ id`.
//!
//! Un message enveloppé ne s'ouvre qu'avec la clé ML-KEM du moment : une
//! fois qu'elle a tourné, voler l'appareil (ou l'état de la session) ne
//! suffit plus pour lire la suite, même avec un ordinateur quantique.

// `Nonce::from_slice` : l'API d'aes-gcm 0.10.
#![allow(deprecated)]

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use brocolis_proto::paquet::{mlkem_decapsulate, mlkem_encapsulate, mlkem_public};
use hkdf::Hkdf;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

use crate::crypto::{b64, unb64};

/// Le début d'un jeton enveloppé.
pub const HEAD: &str = "[[TALKA:PQ6:";
/// Les clés de cet appareil dans `kv` : propres à l'appareil, ni dans la
/// sauvegarde ni dans une liaison.
pub const KEYS: &str = "pq6";
/// Une clé qui a servi tourne après une heure.
pub const ROTATE_SECS: u64 = 60 * 60;
/// Une clé remplacée est gardée 30 jours, puis effacée.
pub const KEEP_SECS: u64 = 30 * 24 * 60 * 60;
/// Au plus tant de clés gardées (les plus anciennes partent d'abord).
pub const MAX_KEYS: usize = 200;

const EK_LEN: usize = 1184;
const CT_LEN: usize = 1088;
const VERSION: u8 = 1;

/// L'identifiant d'une clé publique : `SHA-256("talka-pq6-id:" ‖ ek)[0..8]`.
pub fn key_id(ek: &[u8]) -> [u8; 8] {
    let digest = Sha256::new()
        .chain_update(b"talka-pq6-id:")
        .chain_update(ek)
        .finalize();
    let mut id = [0u8; 8];
    id.copy_from_slice(&digest[..8]);
    id
}

/// Une graine ML-KEM neuve.
pub fn random_seed() -> [u8; 64] {
    let mut seed = [0u8; 64];
    seed.copy_from_slice(&crate::crypto::random_bytes(64));
    seed
}

pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Une clé ML-KEM de cet appareil.
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct Key {
    /// Son identifiant, en hexadécimal.
    pub id: String,
    /// Sa graine (64 octets), en base64.
    pub seed: String,
    /// Sa création (secondes).
    pub at: u64,
    /// Son remplacement (secondes), si elle n'est plus la courante.
    #[serde(default)]
    pub replaced: Option<u64>,
    /// Un appareil s'en est-il déjà servi ?
    #[serde(default)]
    pub used: bool,
}

impl Key {
    pub fn from_seed(seed: &[u8; 64], at: u64) -> Key {
        Key {
            id: hex(&key_id(&mlkem_public(seed))),
            seed: b64(seed),
            at,
            replaced: None,
            used: false,
        }
    }

    fn seed(&self) -> Option<[u8; 64]> {
        unb64(&self.seed)?.try_into().ok()
    }
}

/// Les clés de cet appareil (`kv/pq6`) : la dernière est la courante.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct Keys {
    pub keys: Vec<Key>,
}

impl Keys {
    pub fn from_json(json: Option<&str>) -> Keys {
        json.and_then(|j| serde_json::from_str(j).ok())
            .unwrap_or_default()
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }

    pub fn current(&self) -> Option<&Key> {
        self.keys.last()
    }

    /// Une clé neuve si aucune, ou si la courante a servi et a plus d'une
    /// heure ; les clés remplacées depuis plus de 30 jours sont effacées.
    /// `true` : quelque chose a changé (à garder).
    pub fn maintain(&mut self, now: u64, seed: impl FnOnce() -> [u8; 64]) -> bool {
        let mut changed = false;
        let stale = self
            .current()
            .is_none_or(|k| k.used && now.saturating_sub(k.at) >= ROTATE_SECS);
        if stale {
            if let Some(k) = self.keys.last_mut() {
                k.replaced = Some(now);
            }
            self.keys.push(Key::from_seed(&seed(), now));
            changed = true;
        }
        let before = self.keys.len();
        self.keys
            .retain(|k| k.replaced.is_none_or(|r| now.saturating_sub(r) < KEEP_SECS));
        while self.keys.len() > MAX_KEYS {
            self.keys.remove(0);
        }
        changed || self.keys.len() != before
    }

    /// Ce que l'on annonce : `{"id","ek","at"}` de la clé courante.
    pub fn advert(&self) -> Option<Value> {
        let k = self.current()?;
        let ek = mlkem_public(&k.seed()?);
        Some(json!({ "id": k.id, "ek": b64(&ek), "at": k.at }))
    }

    /// Ouvre un jeton enveloppé : `Ok((jeton, id de la clé, première fois
    /// qu'elle sert))`. `Err(true)` :
    /// la clé est inconnue (effacée, ou d'avant une restauration) ;
    /// `Err(false)` : le message est abîmé.
    pub fn open(&mut self, wrapped: &str) -> Result<(String, String, bool), bool> {
        let bytes = wrapped
            .strip_prefix(HEAD)
            .and_then(|s| s.strip_suffix("]]"))
            .and_then(unb64)
            .ok_or(false)?;
        if bytes.len() < 1 + 8 + CT_LEN + 12 + 16 || bytes[0] != VERSION {
            return Err(false);
        }
        let id = &bytes[1..9];
        let ct = &bytes[9..9 + CT_LEN];
        let nonce = &bytes[9 + CT_LEN..9 + CT_LEN + 12];
        let sealed = &bytes[9 + CT_LEN + 12..];
        let id_hex = hex(id);
        let key = self.keys.iter_mut().find(|k| k.id == id_hex).ok_or(true)?;
        let seed = key.seed().ok_or(true)?;
        let ss = mlkem_decapsulate(&seed, ct).map_err(|_| false)?;
        let plain = cipher(&ss[..], id, ct)
            .decrypt(
                Nonce::from_slice(nonce),
                Payload {
                    msg: sealed,
                    aad: &aad(id),
                },
            )
            .map_err(|_| false)?;
        let text = String::from_utf8(plain).map_err(|_| false)?;
        let first = !std::mem::replace(&mut key.used, true);
        Ok((text, id_hex, first))
    }
}

fn aad(id: &[u8]) -> Vec<u8> {
    [b"talka-pq6:".as_slice(), id].concat()
}

fn cipher(ss: &[u8], id: &[u8], ct: &[u8]) -> Aes256Gcm {
    let info = [b"talka-pq6-cle:".as_slice(), id, ct].concat();
    let mut key = [0u8; 32];
    Hkdf::<Sha256>::new(None, ss)
        .expand(&info, &mut key)
        .expect("32 octets");
    Aes256Gcm::new_from_slice(&key).expect("clé de 32 octets")
}

/// La clé annoncée par un appareil : `(id, ek, at)`, si elle est bien formée.
pub fn parse_advert(v: &Value) -> Option<(String, String, u64)> {
    let id = v["id"].as_str()?;
    let ek = v["ek"].as_str()?;
    let bytes = unb64(ek)?;
    (bytes.len() == EK_LEN && hex(&key_id(&bytes)) == id)
        .then(|| (id.to_owned(), ek.to_owned(), v["at"].as_u64().unwrap_or(0)))
}

/// Enveloppe `token` pour la clé `ek` (en base64) ; `m` et `nonce` : du
/// hasard (fixés pour les vecteurs).
pub fn wrap(ek: &str, token: &str, m: &[u8; 32], nonce: &[u8; 12]) -> Option<String> {
    let ek = unb64(ek)?;
    let id = key_id(&ek);
    let (ct, ss) = mlkem_encapsulate(&ek, m).ok()?;
    let sealed = cipher(&ss[..], &id, &ct)
        .encrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: token.as_bytes(),
                aad: &aad(&id),
            },
        )
        .ok()?;
    let bytes = [&[VERSION][..], &id, &ct, nonce, &sealed].concat();
    Some(format!("{HEAD}{}]]", b64(&bytes)))
}

pub fn is_wrapped(text: &str) -> bool {
    text.starts_with(HEAD) && text.ends_with("]]")
}

/// Ajoute l'annonce `advert` au `Wire` JSON `plain` (s'il en est un).
pub fn with_advert(plain: &str, advert: &Value) -> Option<String> {
    let mut wire: Value = serde_json::from_str(plain).ok()?;
    wire.as_object_mut()?.insert("pq6".into(), advert.clone());
    serde_json::to_string(&wire).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wrap_open_rotate_and_forget() {
        let mut keys = Keys::default();
        assert!(keys.maintain(1_000, || [1u8; 64]));
        let advert = keys.advert().unwrap();
        let (id, ek, at) = parse_advert(&advert).unwrap();
        assert_eq!(at, 1_000);
        assert_eq!(id, keys.current().unwrap().id);
        // Une clé qui n'a pas servi ne tourne pas.
        assert!(!keys.maintain(1_000 + ROTATE_SECS, || [2u8; 64]));

        let wrapped = wrap(&ek, "[[BROCOLIS:v3:abc]]", &[3u8; 32], &[4u8; 12]).unwrap();
        assert!(is_wrapped(&wrapped));
        assert_eq!(
            keys.open(&wrapped),
            Ok(("[[BROCOLIS:v3:abc]]".into(), id.clone(), true))
        );
        assert_eq!(
            keys.open(&wrapped),
            Ok(("[[BROCOLIS:v3:abc]]".into(), id.clone(), false))
        );
        // Abîmé : refusé, sans être pris pour une clé inconnue.
        let mut bad = unb64(&wrapped[HEAD.len()..wrapped.len() - 2]).unwrap();
        let last = bad.len() - 1;
        bad[last] ^= 1;
        assert_eq!(keys.open(&format!("{HEAD}{}]]", b64(&bad))), Err(false));

        // Elle a servi : elle tourne après une heure, l'ancienne s'ouvre encore.
        assert!(!keys.maintain(1_000 + ROTATE_SECS - 1, || [2u8; 64]));
        assert!(keys.maintain(1_000 + ROTATE_SECS, || [2u8; 64]));
        assert_ne!(keys.current().unwrap().id, id);
        assert!(keys.open(&wrapped).is_ok());
        // 30 jours après : effacée.
        assert!(keys.maintain(1_000 + ROTATE_SECS + KEEP_SECS, || [5u8; 64]));
        assert_eq!(keys.open(&wrapped), Err(true));
        assert_eq!(Keys::from_json(Some(&keys.to_json())), keys);
    }

    #[test]
    fn adverts_are_checked() {
        let mut keys = Keys::default();
        keys.maintain(1, || [1u8; 64]);
        let mut advert = keys.advert().unwrap();
        assert!(parse_advert(&advert).is_some());
        advert["id"] = json!("0000000000000000");
        assert!(parse_advert(&advert).is_none());
        let wire =
            with_advert(r#"{"type":"text","text":"Salut"}"#, &keys.advert().unwrap()).unwrap();
        let v: Value = serde_json::from_str(&wire).unwrap();
        assert_eq!(v["text"], "Salut");
        assert!(parse_advert(&v["pq6"]).is_some());
        assert!(with_advert("pas du JSON", &advert).is_none());
        // Un `Wire` avec l'annonce se lit comme avant.
        let wire = crate::model::Wire::new(crate::model::Body::Delete { id: "m1".into() });
        let plain = with_advert(&serde_json::to_string(&wire).unwrap(), &advert).unwrap();
        let back: crate::model::Wire = serde_json::from_str(&plain).unwrap();
        assert!(matches!(back.body, crate::model::Body::Delete { id } if id == "m1"));
    }
}
