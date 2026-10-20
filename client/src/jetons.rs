//! Les jetons de [Brocolis 🥦](https://github.com/maxlestage/messagerie-) :
//! une seconde couche de chiffrement, par-dessus celle de Talka, avec les
//! clés que l'on partage dans l'app Brocolis. Exactement le format de
//! `Shared/BrocolisCrypto.swift` :
//!
//! - une clé : 32 octets (AES-256), en base64 ;
//! - `[[BROCOLIS:v1:<base64>]]` : AES-256-GCM « combined »
//!   (nonce 12 octets ‖ chiffré ‖ tag 16 octets), pour une seule clé ;
//! - `[[BROCOLIS:v2:<base64>]]` : une enveloppe pour plusieurs clés :
//!   `0x01`, N, puis N clés de message emballées (60 octets chacune), puis le
//!   corps chiffré sous la clé de message ;
//! - `((…))` : seuls ces passages sont chiffrés, le reste part en clair.
//!
//! Compilé aussi hors du navigateur : l'iPhone (TalkaKit) est recoupé avec
//! ces jetons par les vecteurs de test.

// `Nonce::from_slice` : l'API d'aes-gcm 0.10.
#![allow(deprecated)]

use aes_gcm::aead::Aead;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};

use crate::crypto::{b64, random_bytes, unb64};

pub const HEAD: &str = "[[BROCOLIS:";
const V1: &str = "v1:";
const V2: &str = "v2:";
const END: &str = "]]";
const MARK_OPEN: &str = "((";
const MARK_CLOSE: &str = "))";
const WRAPPED: usize = 12 + 32 + 16;

pub type Key = [u8; 32];

/// Une clé Brocolis en base64 (32 octets), ou `None`.
pub fn key_from_b64(text: &str) -> Option<Key> {
    unb64(text.trim())?.try_into().ok()
}

/// Retrouve une clé dans un texte collé (l'invitation de l'app Brocolis
/// entière, par exemple) : le premier mot qui est une clé.
pub fn find_key(text: &str) -> Option<String> {
    text.split(|c: char| c.is_whitespace() || "«»\"'“”()[]<>".contains(c))
        .find(|w| key_from_b64(w).is_some())
        .map(|w| w.trim().to_owned())
}

/// L'empreinte d'une clé, à comparer de vive voix (`AB12-CD34-EF56-7890`) :
/// les 8 premiers octets de son SHA-256, comme les empreintes de l'app
/// Brocolis. Deux appareils qui affichent la même ont la même clé.
pub fn fingerprint(key_b64: &str) -> Option<String> {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(key_from_b64(key_b64)?);
    let hex: String = digest[..8].iter().map(|b| format!("{b:02X}")).collect();
    Some(
        hex.as_bytes()
            .chunks(4)
            .map(|c| String::from_utf8_lossy(c).into_owned())
            .collect::<Vec<_>>()
            .join("-"),
    )
}

/// Une nouvelle clé, en base64.
pub fn generate_key() -> String {
    b64(&random_bytes(32))
}

pub fn has_token(text: &str) -> bool {
    text.contains(HEAD)
}

fn seal_raw(key: &Key, plain: &[u8]) -> Option<Vec<u8>> {
    let nonce = random_bytes(12);
    let mut out = nonce.clone();
    out.extend(
        Aes256Gcm::new_from_slice(key)
            .ok()?
            .encrypt(Nonce::from_slice(&nonce), plain)
            .ok()?,
    );
    Some(out)
}

fn open_raw(key: &[u8], combined: &[u8]) -> Option<Vec<u8>> {
    if combined.len() < 12 + 16 {
        return None;
    }
    let (nonce, body) = combined.split_at(12);
    Aes256Gcm::new_from_slice(key)
        .ok()?
        .decrypt(Nonce::from_slice(nonce), body)
        .ok()
}

/// Un jeton pour `text` : `v1` pour une clé (lisible par toutes les
/// versions de Brocolis), `v2` pour plusieurs.
pub fn seal_token(text: &str, keys: &[Key]) -> Option<String> {
    match keys {
        [] => None,
        [key] => Some(format!(
            "{HEAD}{V1}{}{END}",
            b64(&seal_raw(key, text.as_bytes())?)
        )),
        _ if keys.len() > 255 => None,
        _ => {
            let message_key = random_bytes(32);
            let message_key: Key = message_key.try_into().ok()?;
            let mut payload = vec![1u8, keys.len() as u8];
            for key in keys {
                payload.extend(seal_raw(key, &message_key)?);
            }
            payload.extend(seal_raw(&message_key, text.as_bytes())?);
            Some(format!("{HEAD}{V2}{}{END}", b64(&payload)))
        }
    }
}

/// Chiffre un message : les passages `((…))` s'il y en a, sinon tout le
/// texte. Un texte qui porte déjà un jeton (collé depuis le clavier
/// Brocolis) part tel quel.
pub fn seal(text: &str, keys: &[Key]) -> String {
    if text.is_empty() || keys.is_empty() || has_token(text) {
        return text.to_owned();
    }
    let mut out = String::new();
    let mut rest = text;
    let mut marked = false;
    while let Some(start) = rest.find(MARK_OPEN) {
        let after = &rest[start + MARK_OPEN.len()..];
        let Some(end) = after.find(MARK_CLOSE) else {
            break;
        };
        out.push_str(&rest[..start]);
        match seal_token(&after[..end], keys) {
            Some(token) => out.push_str(&token),
            None => out.push_str(&rest[start..start + MARK_OPEN.len() + end + MARK_CLOSE.len()]),
        }
        marked = true;
        rest = &after[end + MARK_CLOSE.len()..];
    }
    if !marked {
        return seal_token(text, keys).unwrap_or_else(|| text.to_owned());
    }
    out.push_str(rest);
    out
}

fn open_v2(payload: &[u8], keys: &[Key]) -> Option<Vec<u8>> {
    let (&format, rest) = payload.split_first()?;
    let (&n, rest) = rest.split_first()?;
    if format != 1 || rest.len() < n as usize * WRAPPED + 12 + 16 {
        return None;
    }
    let (wraps, body) = rest.split_at(n as usize * WRAPPED);
    wraps.chunks(WRAPPED).find_map(|wrapped| {
        keys.iter().find_map(|key| {
            let message_key = open_raw(key, wrapped)?;
            open_raw(&message_key, body)
        })
    })
}

fn open_token(inner: &str, keys: &[Key]) -> Option<String> {
    let plain = if let Some(b) = inner.strip_prefix(V1) {
        let combined = unb64(b)?;
        keys.iter().find_map(|key| open_raw(key, &combined))?
    } else {
        open_v2(&unb64(inner.strip_prefix(V2)?)?, keys)?
    };
    String::from_utf8(plain).ok()
}

/// Ce que donne un texte une fois ses jetons ouverts.
#[derive(Debug, Default, PartialEq)]
pub struct Opened {
    pub text: String,
    /// Les jetons ouverts.
    pub opened: usize,
    /// Ceux qu'aucune de vos clés n'ouvre (laissés tels quels).
    pub locked: usize,
}

/// Ouvre les jetons de `text` avec toutes vos clés.
pub fn open(text: &str, keys: &[Key]) -> Opened {
    let mut out = Opened::default();
    let mut rest = text;
    while let Some(start) = rest.find(HEAD) {
        let after = &rest[start + HEAD.len()..];
        let Some(end) = after.find(END) else {
            break;
        };
        out.text.push_str(&rest[..start]);
        match open_token(&after[..end], keys) {
            Some(plain) => {
                out.text.push_str(&plain);
                out.opened += 1;
            }
            None => {
                out.text
                    .push_str(&rest[start..start + HEAD.len() + end + END.len()]);
                out.locked += 1;
            }
        }
        rest = &after[end + END.len()..];
    }
    out.text.push_str(rest);
    out
}

/// Un texte prêt à afficher : un jeton qu'aucune de vos clés n'ouvre
/// devient « 🔒🥦 ».
pub fn masked(text: &str) -> std::borrow::Cow<'_, str> {
    if !has_token(text) {
        return text.into();
    }
    let mut out = String::new();
    let mut rest = text;
    while let Some(start) = rest.find(HEAD) {
        let Some(end) = rest[start..].find(END) else {
            break;
        };
        out.push_str(&rest[..start]);
        out.push_str("🔒🥦");
        rest = &rest[start + end + END.len()..];
    }
    out.push_str(rest);
    out.into()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(n: u8) -> Key {
        [n; 32]
    }

    #[test]
    fn one_key_makes_v1_and_opens() {
        let token = seal("Salut 🥦", &[key(1)]);
        assert!(token.starts_with("[[BROCOLIS:v1:") && token.ends_with("]]"));
        let o = open(&token, &[key(9), key(1)]);
        assert_eq!((o.text.as_str(), o.opened, o.locked), ("Salut 🥦", 1, 0));
    }

    #[test]
    fn several_keys_make_one_v2_everyone_reads() {
        let token = seal("À tous", &[key(1), key(2), key(3)]);
        assert!(token.starts_with("[[BROCOLIS:v2:"));
        for k in 1..=3 {
            assert_eq!(open(&token, &[key(k)]).text, "À tous");
        }
        let other = open(&token, &[key(4)]);
        assert_eq!((other.text, other.opened, other.locked), (token, 0, 1));
    }

    #[test]
    fn only_marked_parts_are_sealed() {
        let text = seal("Rendez-vous ((au parc)) à ((18 h)) !", &[key(1)]);
        assert!(text.starts_with("Rendez-vous [[BROCOLIS:v1:"));
        assert!(text.ends_with("]] !") && !text.contains("parc"));
        let o = open(&text, &[key(1)]);
        assert_eq!(
            (o.text.as_str(), o.opened),
            ("Rendez-vous au parc à 18 h !", 2)
        );
    }

    #[test]
    fn existing_tokens_and_empty_text_pass_through() {
        let token = seal("x", &[key(1)]);
        assert_eq!(seal(&token, &[key(2)]), token);
        assert_eq!(seal("", &[key(1)]), "");
        assert_eq!(seal("clair", &[]), "clair");
    }

    #[test]
    fn opens_a_token_made_by_brocolis() {
        // Un jeton v1 fait à la main, comme CryptoKit : nonce ‖ chiffré ‖ tag.
        let k = key(7);
        let nonce = [3u8; 12];
        let mut combined = nonce.to_vec();
        combined.extend(
            Aes256Gcm::new_from_slice(&k)
                .unwrap()
                .encrypt(Nonce::from_slice(&nonce), b"brocolis".as_slice())
                .unwrap(),
        );
        let token = format!("avant [[BROCOLIS:v1:{}]] après", b64(&combined));
        assert_eq!(open(&token, &[k]).text, "avant brocolis après");
    }

    #[test]
    fn opens_a_v2_made_by_the_brocolis_app() {
        // Fait par `BrocolisCrypto.encryptTokenMulti` (l'app Brocolis, telle
        // quelle) pour deux clés ; la seconde suffit à l'ouvrir.
        let token = "[[BROCOLIS:v2:AQLvnoxtj3mqrlMIb1HabsfA84XQ4aZVWz8VLKLbBBRDFX2RZlp6az6BeW8WOMl5FTYB94D0rDj6fDJYoNN7ChsNt7sw0O9LKxrI0KCf7Mc0w0x7mIAiuEK2GEtWrVaagyR3wxedqSiL4QrBphtW7PNxDrLzxNe53E58AnsGGchl3SjQ5700f6rruRdr66oPOJOMYxtHEHd7nqKZ1PAEoDBJD9w=]]";
        let k = key_from_b64("Ie2Z71cbvIw+OLOnnmIdfTenOUIxKShDsFQhDH5C7Uw=").unwrap();
        assert_eq!(open(token, &[key(1), k]).text, "Pour vous deux");
    }

    #[test]
    fn finds_a_key_in_a_pasted_invitation() {
        let k = generate_key();
        let invite = format!("3) Colle CETTE clé :\n\n{k}\n\n(C'est notre clé commune.)");
        assert_eq!(find_key(&invite), Some(k));
        assert_eq!(find_key("pas de clé ici"), None);
    }

    #[test]
    fn fingerprints_are_short_and_stable() {
        let k = b64(&[7u8; 32]);
        let f = fingerprint(&k).unwrap();
        assert_eq!(f.len(), 19);
        assert_eq!(f, fingerprint(&k).unwrap());
        assert_ne!(f, fingerprint(&b64(&[8u8; 32])).unwrap());
        assert_eq!(fingerprint("pas une clé"), None);
    }

    #[test]
    fn locked_tokens_are_masked() {
        let token = seal("secret", &[key(1)]);
        assert_eq!(masked(&format!("Voici {token} !")), "Voici 🔒🥦 !");
        assert_eq!(masked("rien à cacher"), "rien à cacher");
    }

    #[test]
    fn broken_tokens_stay_as_they_are() {
        for text in [
            "[[BROCOLIS:v1:pas-du-base64]]",
            "[[BROCOLIS:v3:AAAA]]",
            "[[BROCOLIS:v1:AAAA",
        ] {
            let o = open(text, &[key(1)]);
            assert_eq!(o.text, text);
            assert_eq!(o.opened, 0);
        }
    }
}
