//! La clé du relais (v6, docs/SECURITE.md §7.16) : le relais officiel signe
//! sa réponse à chaque connexion et l'ordre « cet appareil est retiré ». Les
//! clients retiennent sa clé publique à la première connexion : un faux
//! relais (un certificat TLS frauduleux, un réseau qui intercepte) ne peut
//! plus leur faire tout effacer.
//!
//! `TALKA_SERVER_KEY` : 32 octets en base64 (`talka server-key` en tire
//! une). Sans elle, le relais ne signe rien, comme avant.

use std::sync::OnceLock;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};

pub struct ServerKey {
    signing: SigningKey,
    /// La clé publique, non compressée (65 octets), en base64.
    pub public: String,
}

impl ServerKey {
    pub fn from_secret(secret_b64: &str) -> Option<ServerKey> {
        let bytes = B64.decode(secret_b64.trim()).ok()?;
        let signing = SigningKey::from_slice(&bytes).ok()?;
        let public = B64.encode(signing.verifying_key().to_encoded_point(false).as_bytes());
        Some(ServerKey { signing, public })
    }

    /// Une clé neuve : (secret, clé publique), en base64.
    pub fn generate() -> Option<(String, String)> {
        let mut bytes = [0u8; 32];
        getrandom::getrandom(&mut bytes).ok()?;
        let key = ServerKey::from_secret(&B64.encode(bytes))?;
        Some((B64.encode(bytes), key.public))
    }

    /// La signature `r ‖ s` (64 octets, comme WebCrypto), en base64.
    pub fn sign(&self, message: &[u8]) -> String {
        let signature: Signature = self.signing.sign(message);
        B64.encode(signature.to_bytes())
    }
}

/// Ce que signe le relais en acceptant une connexion : le défi qu'il vient
/// d'envoyer, et à qui il répond.
pub fn ready_message(nonce: &str, code: &str, dev: &str) -> Vec<u8> {
    format!("talka-relais-v6:pret:{nonce}:{code}/{dev}").into_bytes()
}

/// Ce que signe le relais pour dire à un appareil qu'il est retiré.
pub fn unlinked_message(code: &str, dev: &str) -> Vec<u8> {
    format!("talka-relais-v6:retire:{code}/{dev}").into_bytes()
}

/// La clé du relais, lue une fois dans `TALKA_SERVER_KEY`.
pub fn server_key() -> Option<&'static ServerKey> {
    static KEY: OnceLock<Option<ServerKey>> = OnceLock::new();
    KEY.get_or_init(|| {
        let secret = std::env::var("TALKA_SERVER_KEY").ok()?;
        let key = ServerKey::from_secret(&secret);
        if key.is_none() {
            eprintln!("⚠️  TALKA_SERVER_KEY illisible : le relais ne signe rien");
        }
        key
    })
    .as_ref()
}

/// `{"t":"unlinked"}`, signé si le relais a sa clé.
pub fn unlinked(code: &str, dev: &str) -> serde_json::Value {
    let mut msg = serde_json::json!({ "t": "unlinked" });
    if let Some(key) = server_key() {
        msg["sig"] = serde_json::json!(key.sign(&unlinked_message(code, dev)));
    }
    msg
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signatures_verify_with_the_public_key() {
        let (secret, public) = ServerKey::generate().unwrap();
        let key = ServerKey::from_secret(&secret).unwrap();
        assert_eq!(key.public, public);
        let pk = B64.decode(&public).unwrap();
        let msg = ready_message("n0nce", "K7Q2MX9PRT", "00000000000000a1");
        assert!(crate::code::verify(&pk, &msg, &key.sign(&msg)));
        assert!(!crate::code::verify(&pk, b"autre chose", &key.sign(&msg)));
        assert!(ServerKey::from_secret("pas une clé").is_none());
    }
}
