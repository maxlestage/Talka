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

/// Un abonnement Web Push : l'adresse, et les clés du navigateur (RFC 8291)
/// pour lui faire passer un aperçu chiffré ; sans clés, un réveil vide.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Subscription {
    pub endpoint: String,
    pub p256dh: Vec<u8>,
    pub auth: Vec<u8>,
}

/// Un aperçu, tel que l'expéditeur l'a chiffré pour la clé de notification
/// du destinataire : le relais ne peut pas le lire. 2 Kio au plus.
pub const MAX_APERCU: usize = 2048;

/// Chiffre `plain` pour le navigateur de `p256dh`/`auth` (RFC 8291,
/// « aes128gcm » de RFC 8188) : ce que seul ce navigateur ouvre, le service
/// de notifications ne voyant qu'un bloc chiffré.
pub fn encrypt(p256dh: &[u8], auth: &[u8], plain: &[u8]) -> Option<Vec<u8>> {
    let mut seed = [0u8; 32];
    let mut salt = [0u8; 16];
    getrandom::getrandom(&mut seed).ok()?;
    getrandom::getrandom(&mut salt).ok()?;
    let secret = p256::SecretKey::from_slice(&seed).ok()?;
    encrypt_with(p256dh, auth, plain, &secret, salt)
}

// `Nonce::from_slice` : l'API d'aes-gcm 0.10.
#[allow(deprecated)]
fn encrypt_with(
    p256dh: &[u8],
    auth: &[u8],
    plain: &[u8],
    secret: &p256::SecretKey,
    salt: [u8; 16],
) -> Option<Vec<u8>> {
    use aes_gcm::aead::{Aead, KeyInit};
    use aes_gcm::{Aes128Gcm, Nonce};
    use hkdf::Hkdf;
    use p256::elliptic_curve::sec1::ToEncodedPoint;
    use sha2::Sha256;

    if auth.len() != 16 || plain.len() > 3800 {
        return None;
    }
    let ua = p256::PublicKey::from_sec1_bytes(p256dh).ok()?;
    let ua_raw = ua.to_encoded_point(false);
    let as_raw = secret.public_key().to_encoded_point(false);
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), ua.as_affine());
    // IKM = HKDF(auth, ECDH, « WebPush: info » ‖ 0 ‖ clé du navigateur ‖ la nôtre)
    let mut info = b"WebPush: info\0".to_vec();
    info.extend_from_slice(ua_raw.as_bytes());
    info.extend_from_slice(as_raw.as_bytes());
    let mut ikm = [0u8; 32];
    Hkdf::<Sha256>::new(Some(auth), shared.raw_secret_bytes())
        .expand(&info, &mut ikm)
        .ok()?;
    let prk = Hkdf::<Sha256>::new(Some(&salt), &ikm);
    let mut cek = [0u8; 16];
    let mut nonce = [0u8; 12];
    prk.expand(b"Content-Encoding: aes128gcm\0", &mut cek)
        .ok()?;
    prk.expand(b"Content-Encoding: nonce\0", &mut nonce).ok()?;
    // Un seul enregistrement : le texte, puis le délimiteur 0x02.
    let mut record = plain.to_vec();
    record.push(2);
    let ct = Aes128Gcm::new_from_slice(&cek)
        .ok()?
        .encrypt(Nonce::from_slice(&nonce), record.as_slice())
        .ok()?;
    // En-tête : sel (16) ‖ taille d'enregistrement (4) ‖ longueur de clé (1) ‖ clé.
    let mut out = salt.to_vec();
    out.extend_from_slice(&4096u32.to_be_bytes());
    out.push(65);
    out.extend_from_slice(as_raw.as_bytes());
    out.extend(ct);
    Some(out)
}

/// Envoie un réveil à `sub` : vide, ou avec `payload` (chiffré pour son
/// navigateur) quand il en a les clés.
pub async fn wake(vapid: &'static Vapid, sub: Subscription, payload: Option<String>) -> Outcome {
    let endpoint = sub.endpoint.clone();
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
        let body = payload
            .filter(|_| !sub.p256dh.is_empty())
            .and_then(|p| encrypt(&sub.p256dh, &sub.auth, p.as_bytes()));
        let req = agent
            .post(&endpoint)
            .set("TTL", &TTL_SECS.to_string())
            .set("Urgency", "high")
            .set("Authorization", &auth);
        let res = match body {
            // Chaque aperçu compte : pas de « Topic » qui les fusionnerait.
            Some(body) => req
                .set("Content-Encoding", "aes128gcm")
                .set("Content-Type", "application/octet-stream")
                .send_bytes(&body),
            // Plusieurs réveils vides en attente n'en font qu'un.
            None => req.set("Topic", "talka").send_bytes(&[]),
        };
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
// Le relais n'apporte qu'un réveil, et parfois un aperçu que l'expéditeur a
// chiffré pour la clé de notification de cet appareil : le relais ne peut pas
// le lire. Cette clé est tirée et gardée ici (IndexedDB, non exportable) ;
// seule sa moitié publique sort, pour les contacts. L'aperçu n'est montré
// que s'il vient vraiment d'un de vos contacts (sa clé d'identité entre
// dans le déchiffrement) : Talka donne ici leur carnet (code, clé, nom que
// vous leur avez donné), gardé chiffré par une autre clé non exportable.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));

const b64 = (u8) => btoa(String.fromCharCode(...u8));
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const texte = (s) => new TextEncoder().encode(s);

function db() {
  return new Promise((ok, ko) => {
    const r = indexedDB.open("talka-notifications", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("cles");
    r.onsuccess = () => ok(r.result);
    r.onerror = () => ko(r.error);
  });
}

async function lire(k) {
  const d = await db();
  return new Promise((ok, ko) => {
    const r = d.transaction("cles").objectStore("cles").get(k);
    r.onsuccess = () => ok(r.result);
    r.onerror = () => ko(r.error);
  });
}

async function ecrire(k, v) {
  const d = await db();
  return new Promise((ok, ko) => {
    const t = d.transaction("cles", "readwrite");
    t.objectStore("cles").put(v, k);
    t.oncomplete = () => ok();
    t.onerror = () => ko(t.error);
  });
}

// La clé de notification : tirée une fois, la partie privée jamais exportée.
async function cle() {
  let k = await lire("nk");
  if (!k) {
    const p = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, false, ["deriveBits"]);
    k = { priv: p.privateKey, pub: b64(new Uint8Array(await crypto.subtle.exportKey("raw", p.publicKey))) };
    await ecrire("nk", k);
  }
  return k;
}

// Le carnet : vos contacts, chiffrés par une clé AES non exportable.
async function carnetCle() {
  let k = await lire("ck");
  if (!k) {
    k = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    await ecrire("ck", k);
  }
  return k;
}

async function garderCarnet(list) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await carnetCle(), texte(JSON.stringify(list)));
  await ecrire("carnet", { iv, data });
}

async function carnet() {
  const c = await lire("carnet");
  if (!c) return {};
  const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: c.iv }, await carnetCle(), c.data);
  const out = {};
  for (const e of JSON.parse(new TextDecoder().decode(pt))) out[e.code] = e;
  return out;
}

const point = (raw) => crypto.subtle.importKey("raw", raw, { name: "ECDH", namedCurve: "P-256" }, false, []);

// Ouvre un aperçu : 0x02 ‖ code (10) ‖ éphémère (65) ‖ iv (12) ‖ chiffré,
// comme talka_web::crypto::seal_apercu (ECDH éphémère et ECDH avec la clé
// d'identité de l'expéditeur, HKDF-SHA256, AES-256-GCM). Seulement d'un
// contact du carnet ; sinon `null`.
async function ouvrir(sealed) {
  const k = await lire("nk");
  if (!k) return null;
  const b = unb64(sealed);
  if (b.length < 1 + 10 + 65 + 12 + 16 || b[0] !== 2) return null;
  const from = new TextDecoder().decode(b.slice(1, 11));
  const contact = (await carnet())[from];
  if (!contact) return null;
  const eph = b.slice(11, 76);
  const e1 = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: await point(eph) }, k.priv, 256));
  const e2 = new Uint8Array(await crypto.subtle.deriveBits({ name: "ECDH", public: await point(unb64(contact.dh)) }, k.priv, 256));
  const ikm = new Uint8Array(64);
  ikm.set(e1, 0);
  ikm.set(e2, 32);
  const hk = await crypto.subtle.importKey("raw", ikm, "HKDF", false, ["deriveKey"]);
  const salt = new Uint8Array(130);
  salt.set(eph, 0);
  salt.set(unb64(k.pub), 65);
  const aes = await crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt, info: texte("talka-apercu-v2:" + from) },
    hk, { name: "AES-GCM", length: 256 }, false, ["decrypt"]);
  const pt = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: b.slice(76, 88), additionalData: texte("talka-apercu:" + from) }, aes, b.slice(88));
  return { a: JSON.parse(new TextDecoder().decode(pt)), contact };
}

// L'application demande la clé publique (pour la donner à ses contacts).
// Et lui donne le carnet de vos contacts (à chaque connexion, et quand il
// change).
self.addEventListener("message", (e) => {
  if (e.data && e.data.t === "nk" && e.ports[0]) {
    e.waitUntil(cle().then((k) => e.ports[0].postMessage({ nk: k.pub }), () => e.ports[0].postMessage({})));
  }
  if (e.data && e.data.t === "carnet" && Array.isArray(e.data.list)) {
    const list = e.data.list
      .filter((c) => typeof c.code === "string" && c.code.length === 10 && typeof c.dh === "string")
      .map((c) => ({ code: c.code, dh: c.dh, name: String(c.name || "").slice(0, 80) }));
    e.waitUntil(garderCarnet(list));
  }
});

self.addEventListener("push", (e) => {
  e.waitUntil((async () => {
    const app = new URL("../app", self.registration.scope).href;
    const wins = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    // Talka est à l'écran : il s'en occupe lui-même.
    if (wins.some((w) => w.visibilityState === "visible" && w.url.startsWith(app))) return;
    let titre = "Talka", corps = "Nouveau message", tag = "talka";
    try {
      const d = e.data ? e.data.json() : null;
      const o = d && d.a ? await ouvrir(d.a) : null;
      if (o) {
        // Le nom que vous lui avez donné, sinon celui qu'il s'est choisi.
        const nom = o.contact.name || (o.a.n ? "~" + o.a.n : "Talka");
        titre = (nom + (o.a.g ? " · " + o.a.g : "")).slice(0, 80);
        corps = String(o.a.t || corps).slice(0, 200);
        const c = String(o.a.c || "");
        tag = "talka-" + (c.startsWith("g:") ? c : o.contact.code);
      }
    } catch (_) {
      // Illisible : le réveil seul.
    }
    await self.registration.showNotification(titre, {
      body: corps,
      tag,
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
