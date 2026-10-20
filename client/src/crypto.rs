//! Les clés et le chiffrement de Talka, en Rust : compilé en WebAssembly pour
//! le navigateur, et normalement pour les tests du serveur.
//!
//! - L'identité : une clé ECDSA P-256 (signer le défi du relais) et une clé
//!   ECDH P-256 (chiffrer), tirées sur l'appareil.
//! - Le code : 50 bits de `SHA-256(signature ‖ chiffrement)` en 10
//!   caractères, exactement comme `talka::code::derive` côté serveur.
//! - Une conversation à deux : ECDH → HKDF-SHA256 → AES-256-GCM, avec les
//!   deux codes en données associées (un message ne peut être ni renvoyé à
//!   quelqu'un d'autre, ni attribué à un autre).

// `Nonce::from_slice` : l'API d'aes-gcm 0.10.
#![allow(deprecated)]

use std::cell::RefCell;
use std::collections::HashMap;

use aes_gcm::aead::{Aead, Payload};
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use hkdf::Hkdf;
use p256::ecdsa::signature::{Signer, Verifier};
use p256::ecdsa::{Signature, SigningKey, VerifyingKey};
use p256::{PublicKey, SecretKey};
use rand_core::{OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Les 32 caractères d'un code : ni `0`/`O`, ni `1`/`I`.
pub const ALPHABET: &[u8; 32] = b"23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

/// Le code de deux clés publiques brutes (65 octets chacune).
pub fn code_of(sign: &[u8], dh: &[u8]) -> String {
    let mut hash = Sha256::new();
    hash.update(sign);
    hash.update(dh);
    let digest = hash.finalize();
    (0..10)
        .map(|i| {
            let mut v = 0usize;
            for b in 0..5 {
                let bit = i * 5 + b;
                v = (v << 1) | ((digest[bit / 8] >> (7 - bit % 8)) & 1) as usize;
            }
            ALPHABET[v] as char
        })
        .collect()
}

/// `K7Q2MX9PRT` → `K7Q2M-X9PRT`.
pub fn pretty(code: &str) -> String {
    if code.len() == 10 {
        format!("{}-{}", &code[..5], &code[5..])
    } else {
        code.to_owned()
    }
}

/// Ce que l'on tape (minuscules, tirets, espaces) → un code, ou rien.
pub fn normalize(text: &str) -> Option<String> {
    let code: String = text
        .chars()
        .filter(|c| !matches!(c, '-' | ' '))
        .map(|c| c.to_ascii_uppercase())
        .collect();
    (code.len() == 10 && code.bytes().all(|b| ALPHABET.contains(&b))).then_some(code)
}

pub fn b64(bytes: &[u8]) -> String {
    B64.encode(bytes)
}

pub fn unb64(text: &str) -> Option<Vec<u8>> {
    B64.decode(text).ok()
}

pub fn random_bytes(n: usize) -> Vec<u8> {
    let mut out = vec![0u8; n];
    OsRng.fill_bytes(&mut out);
    out
}

/// Un identifiant aléatoire, en hexadécimal.
pub fn random_id(bytes: usize) -> String {
    random_bytes(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// L'identité de l'appareil, telle qu'elle est gardée (et sauvegardée).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Identity {
    pub code: String,
    /// Les clés publiques brutes, en base64.
    pub sign: String,
    pub dh: String,
    /// Les clés privées (32 octets), en base64.
    pub sign_priv: String,
    pub dh_priv: String,
    pub created_at: f64,
}

fn raw(key: &PublicKey) -> Vec<u8> {
    use p256::elliptic_curve::sec1::ToEncodedPoint;
    key.to_encoded_point(false).as_bytes().to_vec()
}

impl Identity {
    pub fn create(now: f64) -> Identity {
        let sign = SecretKey::random(&mut OsRng);
        let dh = SecretKey::random(&mut OsRng);
        let (sign_raw, dh_raw) = (raw(&sign.public_key()), raw(&dh.public_key()));
        Identity {
            code: code_of(&sign_raw, &dh_raw),
            sign: b64(&sign_raw),
            dh: b64(&dh_raw),
            sign_priv: b64(&sign.to_bytes()),
            dh_priv: b64(&dh.to_bytes()),
            created_at: now,
        }
    }

    fn sign_key(&self) -> Option<SigningKey> {
        SigningKey::from_slice(&unb64(&self.sign_priv)?).ok()
    }

    fn dh_key(&self) -> Option<SecretKey> {
        SecretKey::from_slice(&unb64(&self.dh_priv)?).ok()
    }

    /// Les clés de cette identité vont-elles bien ensemble ?
    pub fn is_valid(&self) -> bool {
        let (Some(s), Some(d)) = (self.sign_key(), self.dh_key()) else {
            return false;
        };
        let s = raw(&PublicKey::from(s.verifying_key()));
        let d = raw(&d.public_key());
        b64(&s) == self.sign && b64(&d) == self.dh && code_of(&s, &d) == self.code
    }

    /// La signature du défi du relais : `talka-auth:<nonce>`, `r ‖ s` en base64.
    pub fn sign_challenge(&self, nonce: &str) -> String {
        self.sign(format!("talka-auth:{nonce}").as_bytes())
    }

    /// Une signature ECDSA P-256 de la clé du code (`r ‖ s` en base64,
    /// comme WebCrypto) : le défi du relais, le paquet de clés v3.
    pub fn sign(&self, message: &[u8]) -> String {
        let Some(key) = self.sign_key() else {
            return String::new();
        };
        let sig: Signature = key.sign(message);
        b64(&sig.to_bytes())
    }
}

/// Vérifie une signature `r ‖ s` (comme le relais).
pub fn verify(sign_b64: &str, message: &[u8], sig_b64: &str) -> bool {
    let (Some(key), Some(sig)) = (unb64(sign_b64), unb64(sig_b64)) else {
        return false;
    };
    let (Ok(key), Ok(sig)) = (
        VerifyingKey::from_sec1_bytes(&key),
        Signature::from_slice(&sig),
    ) else {
        return false;
    };
    key.verify(message, &sig).is_ok()
}

/// Les clés publiques d'un contact correspondent-elles à son code ?
pub fn verify_keys(code: &str, sign: &str, dh: &str) -> bool {
    match (unb64(sign), unb64(dh)) {
        (Some(s), Some(d)) => {
            s.len() == 65
                && d.len() == 65
                && PublicKey::from_sec1_bytes(&d).is_ok()
                && code_of(&s, &d) == code
        }
        _ => false,
    }
}

/// Le numéro de sécurité d'une conversation, le même des deux côtés.
pub fn safety_number(a: &str, b: &str) -> String {
    let (a, b) = if a < b { (a, b) } else { (b, a) };
    let h = Sha256::digest(format!("talka-safety:{a}:{b}").as_bytes());
    (0..12)
        .map(|i| {
            format!(
                "{:05}",
                (((h[i * 2] as u32) << 8) | h[i * 2 + 1] as u32) % 100_000
            )
        })
        .collect::<Vec<_>>()
        .join(" ")
}

thread_local! {
    static PAIR_KEYS: RefCell<HashMap<String, [u8; 32]>> = RefCell::new(HashMap::new());
}

/// La clé d'une conversation à deux : ECDH, puis HKDF-SHA256.
fn pair_key(me: &Identity, peer_code: &str, peer_dh: &str) -> Option<[u8; 32]> {
    let cache = format!("{}:{peer_code}:{peer_dh}", me.code);
    if let Some(k) = PAIR_KEYS.with(|m| m.borrow().get(&cache).copied()) {
        return Some(k);
    }
    let public = PublicKey::from_sec1_bytes(&unb64(peer_dh)?).ok()?;
    let secret = me.dh_key()?;
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), public.as_affine());
    let (a, b) = if me.code.as_str() < peer_code {
        (me.code.as_str(), peer_code)
    } else {
        (peer_code, me.code.as_str())
    };
    let salt = format!("talka:{a}:{b}");
    let hk = Hkdf::<Sha256>::new(Some(salt.as_bytes()), shared.raw_secret_bytes());
    let mut key = [0u8; 32];
    hk.expand(b"talka-message-v1", &mut key).ok()?;
    PAIR_KEYS.with(|m| m.borrow_mut().insert(cache, key));
    Some(key)
}

/// Chiffre un texte (du JSON) pour un contact : base64(iv ‖ chiffré).
pub fn seal(me: &Identity, peer_code: &str, peer_dh: &str, plain: &str) -> Option<String> {
    let key = pair_key(me, peer_code, peer_dh)?;
    let cipher = Aes256Gcm::new_from_slice(&key).ok()?;
    let iv = random_bytes(12);
    let ad = format!("{}>{peer_code}", me.code);
    let ct = cipher
        .encrypt(
            Nonce::from_slice(&iv),
            Payload {
                msg: plain.as_bytes(),
                aad: ad.as_bytes(),
            },
        )
        .ok()?;
    let mut out = iv;
    out.extend(ct);
    Some(b64(&out))
}

/// Déchiffre ce qu'un contact a chiffré pour nous.
pub fn open(me: &Identity, peer_code: &str, peer_dh: &str, body: &str) -> Option<String> {
    let key = pair_key(me, peer_code, peer_dh)?;
    let cipher = Aes256Gcm::new_from_slice(&key).ok()?;
    let bytes = unb64(body)?;
    if bytes.len() < 28 {
        return None;
    }
    let ad = format!("{peer_code}>{}", me.code);
    let pt = cipher
        .decrypt(
            Nonce::from_slice(&bytes[..12]),
            Payload {
                msg: &bytes[12..],
                aad: ad.as_bytes(),
            },
        )
        .ok()?;
    String::from_utf8(pt).ok()
}

// ----- Le code de verrouillage et la sauvegarde ------------------------------

pub const PIN_ROUNDS: u32 = 120_000;
pub const BACKUP_ROUNDS: u32 = 200_000;

fn pbkdf2(secret: &str, salt: &[u8], rounds: u32) -> [u8; 32] {
    let mut out = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<Sha256>(secret.as_bytes(), salt, rounds, &mut out);
    out
}

/// Le code de verrouillage, haché : jamais gardé en clair.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PinHash {
    pub salt: String,
    pub hash: String,
}

pub fn hash_pin(pin: &str) -> PinHash {
    let salt = random_bytes(16);
    PinHash {
        hash: b64(&pbkdf2(pin, &salt, PIN_ROUNDS)),
        salt: b64(&salt),
    }
}

pub fn check_pin(pin: &str, stored: &PinHash) -> bool {
    let Some(salt) = unb64(&stored.salt) else {
        return false;
    };
    b64(&pbkdf2(pin, &salt, PIN_ROUNDS)) == stored.hash
}

/// Une sauvegarde : un mot de passe → PBKDF2 → AES-256-GCM.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Backup {
    pub talka: u32,
    pub salt: String,
    pub iv: String,
    pub data: String,
}

pub fn encrypt_backup(password: &str, plain: &str) -> Option<Backup> {
    let salt = random_bytes(16);
    let iv = random_bytes(12);
    let key = pbkdf2(password, &salt, BACKUP_ROUNDS);
    let ct = Aes256Gcm::new_from_slice(&key)
        .ok()?
        .encrypt(Nonce::from_slice(&iv), plain.as_bytes())
        .ok()?;
    Some(Backup {
        talka: 2,
        salt: b64(&salt),
        iv: b64(&iv),
        data: b64(&ct),
    })
}

pub fn decrypt_backup(password: &str, file: &Backup) -> Result<String, &'static str> {
    if file.talka != 2 {
        return Err("Ce fichier n'est pas une sauvegarde Talka.");
    }
    let bad = "Ce fichier n'est pas une sauvegarde Talka.";
    let salt = unb64(&file.salt).ok_or(bad)?;
    let iv = unb64(&file.iv).ok_or(bad)?;
    let data = unb64(&file.data).ok_or(bad)?;
    let key = pbkdf2(password, &salt, BACKUP_ROUNDS);
    let pt = Aes256Gcm::new_from_slice(&key)
        .map_err(|_| bad)?
        .decrypt(Nonce::from_slice(&iv), data.as_slice())
        .map_err(|_| "Mot de passe incorrect.")?;
    String::from_utf8(pt).map_err(|_| bad)
}

// ----- Les clés Brocolis, scellées par le code de verrouillage --------------

/// Plus lent que la vérification du code : c'est lui qui protège les clés
/// au repos.
pub const BROCOLIS_ROUNDS: u32 = 300_000;
const BROCOLIS_AD: &[u8] = b"talka-brocolis-keys";

/// Les clés Brocolis de l'appareil, chiffrées par le code de verrouillage :
/// PBKDF2-SHA256 → AES-256-GCM (`data` : nonce ‖ chiffré ‖ tag).
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct SealedKeys {
    pub salt: String,
    pub data: String,
}

pub fn pin_key(pin: &str, salt: &[u8]) -> [u8; 32] {
    pbkdf2(pin, salt, BROCOLIS_ROUNDS)
}

pub fn seal_keys(key: &[u8; 32], salt: &str, plain: &str) -> Option<SealedKeys> {
    let nonce = random_bytes(12);
    let ct = Aes256Gcm::new_from_slice(key)
        .ok()?
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plain.as_bytes(),
                aad: BROCOLIS_AD,
            },
        )
        .ok()?;
    Some(SealedKeys {
        salt: salt.to_owned(),
        data: b64(&[nonce, ct].concat()),
    })
}

pub fn open_keys(key: &[u8; 32], sealed: &SealedKeys) -> Option<String> {
    let data = unb64(&sealed.data)?;
    if data.len() < 12 + 16 {
        return None;
    }
    let (nonce, ct) = data.split_at(12);
    let pt = Aes256Gcm::new_from_slice(key)
        .ok()?
        .decrypt(
            Nonce::from_slice(nonce),
            Payload {
                msg: ct,
                aad: BROCOLIS_AD,
            },
        )
        .ok()?;
    String::from_utf8(pt).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn brocolis_keys_open_only_with_the_right_pin() {
        let salt = random_bytes(16);
        let key = pin_key("2580", &salt);
        let sealed = seal_keys(&key, &b64(&salt), "[{\"id\":\"k1\"}]").unwrap();
        // Des guillemets n'apparaissent jamais en base64 : « k1 » seul, si.
        assert!(!sealed.data.contains("\"k1\""));
        assert_eq!(
            open_keys(&key, &sealed).as_deref(),
            Some("[{\"id\":\"k1\"}]")
        );
        assert_eq!(open_keys(&pin_key("0000", &salt), &sealed), None);
    }

    #[test]
    fn code_vector() {
        // Le même vecteur que `talka::code::derive`.
        assert_eq!(code_of(&[], &[]), "WGSEAJNSZJ");
        assert_eq!(normalize("wgsea-jnszj").as_deref(), Some("WGSEAJNSZJ"));
        assert_eq!(normalize("WGSEA-JNSZ0"), None);
        assert_eq!(pretty("WGSEAJNSZJ"), "WGSEA-JNSZJ");
    }

    #[test]
    fn identities_talk() {
        let a = Identity::create(0.0);
        let b = Identity::create(0.0);
        assert!(a.is_valid() && b.is_valid());
        assert!(verify_keys(&a.code, &a.sign, &a.dh));
        assert!(!verify_keys(&a.code, &b.sign, &b.dh));
        let sealed = seal(&a, &b.code, &b.dh, "{\"k\":\"msg\"}").unwrap();
        assert_eq!(
            open(&b, &a.code, &a.dh, &sealed).as_deref(),
            Some("{\"k\":\"msg\"}")
        );
        // Renvoyé à quelqu'un d'autre, ou attribué à un autre : illisible.
        let c = Identity::create(0.0);
        assert!(open(&c, &a.code, &a.dh, &sealed).is_none());
        assert!(open(&a, &b.code, &b.dh, &sealed).is_none());
        let sig = a.sign_challenge("abc");
        assert!(verify(&a.sign, b"talka-auth:abc", &sig));
        assert!(!verify(&a.sign, b"talka-auth:abd", &sig));
        assert_eq!(
            safety_number(&a.code, &b.code),
            safety_number(&b.code, &a.code)
        );
    }

    #[test]
    fn pin_and_backup() {
        let h = hash_pin("2580");
        assert!(check_pin("2580", &h));
        assert!(!check_pin("1111", &h));
        let b = encrypt_backup("motdepasse", "{\"x\":1}").unwrap();
        assert_eq!(decrypt_backup("motdepasse", &b).unwrap(), "{\"x\":1}");
        assert!(decrypt_backup("autre", &b).is_err());
    }
}
