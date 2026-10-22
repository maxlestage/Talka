//! Le code Talka : ce qui remplace le numéro de téléphone.
//!
//! Chaque appareil tire deux clés P-256 avec WebCrypto : une pour signer
//! (ECDSA), une pour chiffrer (ECDH). Le code est l'empreinte des deux clés
//! publiques : 50 bits de `SHA-256(signature ‖ chiffrement)`, écrits avec
//! 32 caractères sans ambiguïté (ni `0`/`O`, ni `1`/`I`), par exemple
//! `K7Q2M-X9PRT`. Un code désigne donc une paire de clés et une seule :
//! en ajoutant un contact par son code, l'appareil vérifie lui-même les clés
//! que lui donne le serveur, qui ne peut pas les remplacer.

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use p256::ecdsa::signature::Verifier;
use p256::ecdsa::{Signature, VerifyingKey};
use sha2::{Digest, Sha256};

/// Les 32 caractères d'un code.
pub const ALPHABET: &[u8; 32] = b"23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

/// Le nombre de caractères d'un code (sans le tiret).
pub const LEN: usize = 10;

/// Le code des deux clés publiques (format brut non compressé, 65 octets).
pub fn derive(sign: &[u8], dh: &[u8]) -> String {
    let mut hash = Sha256::new();
    hash.update(sign);
    hash.update(dh);
    let digest = hash.finalize();
    let mut out = String::with_capacity(LEN);
    for i in 0..LEN {
        let mut v = 0usize;
        for b in 0..5 {
            let bit = i * 5 + b;
            let set = (digest[bit / 8] >> (7 - bit % 8)) & 1;
            v = (v << 1) | set as usize;
        }
        out.push(ALPHABET[v] as char);
    }
    out
}

/// Un code bien formé, sans tiret ni minuscule : `k7q2m-x9prt` → `K7Q2MX9PRT`.
pub fn normalize(code: &str) -> Option<String> {
    let code: String = code
        .chars()
        .filter(|c| !matches!(c, '-' | ' '))
        .map(|c| c.to_ascii_uppercase())
        .collect();
    (code.len() == LEN && code.bytes().all(|b| ALPHABET.contains(&b))).then_some(code)
}

/// Une clé publique P-256 brute, en base64 : 65 octets commençant par `0x04`.
pub fn public_key(b64: &str) -> Option<Vec<u8>> {
    let bytes = B64.decode(b64).ok()?;
    (bytes.len() == 65 && bytes[0] == 4 && VerifyingKey::from_sec1_bytes(&bytes).is_ok())
        .then_some(bytes)
}

/// Vérifie la signature WebCrypto (`r ‖ s`, 64 octets) de `message`.
pub fn verify(sign: &[u8], message: &[u8], signature_b64: &str) -> bool {
    let Ok(key) = VerifyingKey::from_sec1_bytes(sign) else {
        return false;
    };
    let Some(signature) = B64
        .decode(signature_b64)
        .ok()
        .and_then(|s| Signature::from_slice(&s).ok())
    else {
        return false;
    };
    key.verify(message, &signature).is_ok()
}

/// Ce que signe l'appareil pour prouver qu'il détient la clé de son code.
pub fn challenge_message(nonce: &str) -> Vec<u8> {
    format!("talka-auth:{nonce}").into_bytes()
}

/// Ce que signe un code pour son jeton de remise (l'expéditeur scellé) :
/// comme `talka_web::crypto::jeton_message`.
pub fn jeton_message(code: &str, nonce: &str) -> Vec<u8> {
    format!("talka-jeton:{code}:{nonce}").into_bytes()
}

/// Le jeton de remise `nonce.signature` est-il signé par la clé du code
/// `to`, dont on donne les deux clés publiques ? Rien n'est gardé : le
/// jeton se vérifie seul, même après un redémarrage.
pub fn verify_jeton(to: &str, sign_b64: &str, dh_b64: &str, jeton: &str) -> bool {
    let (Some(sign), Some(dh)) = (public_key(sign_b64), public_key(dh_b64)) else {
        return false;
    };
    let Some((nonce, sig)) = jeton.split_once('.') else {
        return false;
    };
    nonce.len() <= 64 && derive(&sign, &dh) == to && verify(&sign, &jeton_message(to, nonce), sig)
}

/// Ce que signe l'appareil pour publier son paquet de clés v3 (comme
/// `talka_web::v3::bundle_message`) : il est ainsi lié à son code.
pub fn bundle_message(bundle: &[u8]) -> Vec<u8> {
    [b"talka-v3-paquet:".as_slice(), bundle].concat()
}

/// Ce que signe un appareil v4 pour publier son paquet (comme
/// `talka_web::v3::device_bundle_message`) : lié au code **et** à
/// l'appareil.
pub fn device_bundle_message(dev: &str, bundle: &[u8]) -> Vec<u8> {
    [format!("talka-v4-appareil:{dev}:").as_bytes(), bundle].concat()
}

/// Un identifiant d'appareil : 16 caractères hexadécimaux (8 octets).
pub fn valid_dev(dev: &str) -> bool {
    dev.len() == 16
        && dev
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

/// Une boîte aux lettres tournante (ou de liaison) : 16 octets en base64url
/// sans remplissage (22 caractères).
pub fn valid_boite(addr: &str) -> bool {
    addr.len() == 22
        && addr
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_are_stable_and_normalized() {
        let a = derive(&[4; 65], &[5; 65]);
        assert_eq!(a.len(), LEN);
        assert_eq!(a, derive(&[4; 65], &[5; 65]));
        assert_ne!(a, derive(&[5; 65], &[4; 65]));
        let pretty = format!("{}-{}", &a[..5], &a[5..]).to_lowercase();
        assert_eq!(normalize(&pretty), Some(a));
        assert_eq!(normalize("ABCDE-FGHI0"), None); // `0` n'existe pas
        assert_eq!(normalize("ABC"), None);
    }

    #[test]
    fn devices_and_boxes() {
        assert!(valid_dev("0123456789abcdef"));
        assert!(!valid_dev("0123456789ABCDEF"));
        assert!(!valid_dev("0123456789abcde"));
        assert!(!valid_dev("0123456789abcdeg"));
        assert!(valid_boite("AAAAAAAAAAAAAAAAAAAA-_"));
        assert!(!valid_boite("AAAAAAAAAAAAAAAAAAAA+/"));
        assert!(!valid_boite("AAAA"));
        assert_ne!(
            device_bundle_message("0123456789abcdef", b"x"),
            bundle_message(b"x")
        );
    }

    #[test]
    fn known_vector() {
        // Le même calcul que `codeOf` dans web/app/crypto.js.
        assert_eq!(derive(&[], &[]), "WGSEAJNSZJ");
    }
}
