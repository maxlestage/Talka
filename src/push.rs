//! Les notifications quand Talka est fermé (Web Push), sans rien dévoiler.
//!
//! Le relais n'envoie au service de notifications du navigateur (Google,
//! Mozilla, Apple, Microsoft) qu'un **réveil vide** : pas de contenu, pas
//! d'expéditeur, pas de nom de discussion. Le navigateur affiche « Nouveau
//! message » ; le message lui-même reste chiffré dans la boîte aux lettres,
//! et ne s'ouvre qu'en ouvrant Talka. Le service de notifications apprend
//! seulement qu'un appareil a reçu quelque chose, et quand.
//!
//! Il faut une clé VAPID (ECDSA P-256) pour signer ces réveils :
//! `TALKA_VAPID_KEY`, que `talka vapid` fabrique. Sans elle, il n'y a pas de
//! notifications quand Talka est fermé ; tout le reste fonctionne.

use std::sync::OnceLock;
use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64URL;
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use serde_json::json;

/// Une adresse de notification : 1 Kio au plus.
pub const MAX_ENDPOINT: usize = 1024;

/// Ce que le réveil laisse aux services de notifications : un jour pour
/// trouver l'appareil, puis il est oublié.
const TTL_SECS: u32 = 24 * 3600;

/// Les services de notifications des navigateurs : le relais n'envoie ses
/// réveils nulle part ailleurs.
const HOSTS: &[&str] = &[
    "fcm.googleapis.com",
    // Chromium (sans les services Google de Chrome).
    "jmt17.google.com",
    "updates.push.services.mozilla.com",
    "web.push.apple.com",
];
const HOST_SUFFIXES: &[&str] = &[
    ".push.apple.com",
    ".notify.windows.com",
    ".push.services.mozilla.com",
];

/// La clé qui signe les réveils.
pub struct Vapid {
    key: SigningKey,
    /// La clé publique (non compressée, 65 octets), en base64url : le
    /// navigateur l'utilise pour s'abonner (`applicationServerKey`).
    pub public: String,
    subject: String,
}

impl Vapid {
    /// Depuis la clé privée (32 octets, base64url).
    pub fn new(secret: &str, subject: &str) -> Option<Vapid> {
        let bytes = B64URL.decode(secret.trim().trim_end_matches('=')).ok()?;
        let key = SigningKey::from_slice(&bytes).ok()?;
        let public = B64URL.encode(key.verifying_key().to_encoded_point(false).as_bytes());
        Some(Vapid {
            key,
            public,
            subject: subject.to_owned(),
        })
    }

    /// Une nouvelle clé privée, en base64url (pour `TALKA_VAPID_KEY`).
    pub fn generate() -> Option<String> {
        for _ in 0..8 {
            let mut bytes = [0u8; 32];
            getrandom::getrandom(&mut bytes).ok()?;
            if SigningKey::from_slice(&bytes).is_ok() {
                return Some(B64URL.encode(bytes));
            }
        }
        None
    }

    /// L'en-tête `Authorization` d'un réveil vers `endpoint` (RFC 8292).
    pub fn authorization(&self, endpoint: &str, now_secs: u64) -> Option<String> {
        let audience = audience(endpoint)?;
        let header = B64URL.encode(br#"{"typ":"JWT","alg":"ES256"}"#);
        let claims = B64URL.encode(
            json!({ "aud": audience, "exp": now_secs + 12 * 3600, "sub": self.subject })
                .to_string(),
        );
        let input = format!("{header}.{claims}");
        let sig: Signature = self.key.sign(input.as_bytes());
        let token = format!("{input}.{}", B64URL.encode(sig.to_bytes()));
        Some(format!("vapid t={token}, k={}", self.public))
    }
}

/// La clé du relais (`TALKA_VAPID_KEY`), si elle est configurée.
pub fn vapid() -> Option<&'static Vapid> {
    static VAPID: OnceLock<Option<Vapid>> = OnceLock::new();
    VAPID
        .get_or_init(|| {
            let secret = std::env::var("TALKA_VAPID_KEY").ok()?;
            let subject = std::env::var("TALKA_VAPID_SUBJECT")
                .ok()
                .filter(|s| !s.trim().is_empty())
                .unwrap_or_else(|| "https://maxlestage.github.io/Talka/".to_owned());
            let vapid = Vapid::new(&secret, &subject);
            if vapid.is_none() {
                eprintln!(
                    "⚠️  TALKA_VAPID_KEY illisible : pas de notifications quand Talka est fermé"
                );
            }
            vapid
        })
        .as_ref()
}

/// L'origine d'une adresse de notification acceptée (`https://hôte`), ou
/// `None` si elle ne mène pas à un service de notifications connu.
pub fn audience(endpoint: &str) -> Option<String> {
    if endpoint.len() > MAX_ENDPOINT {
        return None;
    }
    let rest = endpoint.strip_prefix("https://")?;
    let host = rest.split(['/', '?', '#']).next()?;
    // Pas d'utilisateur, pas de port, pas de majuscules piégées.
    if host.is_empty()
        || !host
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'.' || b == b'-')
    {
        return None;
    }
    let known = HOSTS.contains(&host)
        || HOST_SUFFIXES
            .iter()
            .any(|s| host.ends_with(s) && host.len() > s.len());
    known.then(|| format!("https://{host}"))
}

/// Ce qu'a répondu le service de notifications.
#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    Sent,
    /// L'abonnement n'existe plus (404, 410) : on l'oublie.
    Gone,
    Failed,
}

/// Envoie un réveil vide à `endpoint`.
pub async fn wake(vapid: &'static Vapid, endpoint: String) -> Outcome {
    tokio::task::spawn_blocking(move || {
        static AGENT: OnceLock<ureq::Agent> = OnceLock::new();
        let agent = AGENT.get_or_init(|| {
            ureq::AgentBuilder::new()
                .timeout(Duration::from_secs(10))
                .redirects(0)
                .build()
        });
        let Some(auth) = vapid.authorization(&endpoint, crate::relay::now_ms() / 1000) else {
            return Outcome::Gone;
        };
        let res = agent
            .post(&endpoint)
            .set("TTL", &TTL_SECS.to_string())
            .set("Urgency", "high")
            // Plusieurs réveils en attente n'en font qu'un.
            .set("Topic", "talka")
            .set("Authorization", &auth)
            .send_bytes(&[]);
        match res {
            Ok(_) => Outcome::Sent,
            Err(ureq::Error::Status(404 | 410, _)) => Outcome::Gone,
            Err(_) => Outcome::Failed,
        }
    })
    .await
    .unwrap_or(Outcome::Failed)
}

/// Le service worker des notifications : il affiche le réveil, et ouvre
/// Talka quand on touche la notification. Servi à `talka-sw.js`, à la racine
/// du site, et enregistré pour la portée `talka-push/`, où il n'y a aucune
/// page : il ne contrôle rien (le service worker hors ligne reste seul à la
/// racine).
pub const SERVICE_WORKER: &str = r#"// Talka : les notifications quand l'application est fermée.
// Le relais n'envoie qu'un réveil vide : ni expéditeur, ni texte. Le message
// reste chiffré dans la boîte aux lettres, et ne s'ouvre qu'avec Talka.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("push", (e) => {
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    // Talka est à l'écran : il s'en occupe lui-même.
    if (wins.some((w) => w.visibilityState === "visible")) return;
    await self.registration.showNotification("Talka", {
      body: "Nouveau message",
      tag: "talka",
      renotify: true,
      icon: new URL("../icons/icon-192.png", self.registration.scope).href,
    });
  })());
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  e.waitUntil((async () => {
    const app = new URL("../app", self.registration.scope).href;
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const open = wins.find((w) => w.url.startsWith(app));
    return open ? open.focus() : self.clients.openWindow(app);
  })());
});
"#;

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::VerifyingKey;
    use p256::ecdsa::signature::Verifier;

    #[test]
    fn only_known_push_services() {
        assert_eq!(
            audience("https://fcm.googleapis.com/fcm/send/abc:def").as_deref(),
            Some("https://fcm.googleapis.com")
        );
        assert!(audience("https://web.push.apple.com/QK1x").is_some());
        assert!(audience("https://wns2-par02p.notify.windows.com/w/?token=x").is_some());
        assert!(audience("https://updates.push.services.mozilla.com/wpush/v2/x").is_some());
        for bad in [
            "http://fcm.googleapis.com/x",
            "https://fcm.googleapis.com.evil.example/x",
            "https://evil.example/fcm.googleapis.com",
            "https://user@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x",
            "https://FCM.googleapis.com/x",
            "https://.push.apple.com/x",
            "https://localhost/x",
            "https://127.0.0.1/x",
            "",
        ] {
            assert!(audience(bad).is_none(), "{bad}");
        }
        let long = format!("https://fcm.googleapis.com/{}", "a".repeat(MAX_ENDPOINT));
        assert!(audience(&long).is_none());
    }

    #[test]
    fn signed_vapid_token() {
        let secret = Vapid::generate().unwrap();
        let v = Vapid::new(&secret, "https://example.org/").unwrap();
        let auth = v
            .authorization("https://fcm.googleapis.com/fcm/send/x", 1_000)
            .unwrap();
        let (token, k) = auth
            .strip_prefix("vapid t=")
            .unwrap()
            .split_once(", k=")
            .unwrap();
        assert_eq!(k, v.public);
        let parts: Vec<&str> = token.split('.').collect();
        assert_eq!(parts.len(), 3);
        let claims: serde_json::Value =
            serde_json::from_slice(&B64URL.decode(parts[1]).unwrap()).unwrap();
        assert_eq!(claims["aud"], "https://fcm.googleapis.com");
        assert_eq!(claims["exp"], 1_000 + 12 * 3600);
        assert_eq!(claims["sub"], "https://example.org/");
        let key = VerifyingKey::from_sec1_bytes(&B64URL.decode(k).unwrap()).unwrap();
        let sig = Signature::from_slice(&B64URL.decode(parts[2]).unwrap()).unwrap();
        assert!(
            key.verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &sig)
                .is_ok()
        );
        assert!(Vapid::new("pas une clé", "x").is_none());
        assert!(v.authorization("https://evil.example/x", 0).is_none());
    }
}
