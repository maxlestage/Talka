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

/// L'empreinte des clés d'un code, mise dans les liens d'invitation (96
/// bits, base64url, 16 caractères). Le code seul ne compte que 50 bits :
/// avec l'empreinte, quelqu'un qui trouverait d'autres clés donnant le même
/// code est démasqué à l'ajout.
pub fn fingerprint(sign: &str, dh: &str) -> String {
    let h = Sha256::digest(format!("talka-empreinte:{sign}:{dh}").as_bytes());
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&h[..12])
}

/// Une empreinte bien formée (16 caractères base64url).
pub fn valid_fingerprint(fp: &str) -> bool {
    fp.len() == 16
        && fp
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Le numéro de sécurité d'une conversation, le même des deux côtés. Il
/// porte sur les clés publiques complètes (`code`, `sign`, `dh` de chacun),
/// pas seulement sur les codes : un code ne compte que 50 bits, et
/// quelqu'un qui trouverait d'autres clés donnant le même code aurait un
/// autre numéro.
pub fn safety_number(a: (&str, &str, &str), b: (&str, &str, &str)) -> String {
    let (a, b) = if a.0 < b.0 { (a, b) } else { (b, a) };
    let h = Sha256::digest(
        format!(
            "talka-safety-v2:{}:{}:{}|{}:{}:{}",
            a.0, a.1, a.2, b.0, b.1, b.2
        )
        .as_bytes(),
    );
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

// ----- L'expéditeur scellé (v4) -----------------------------------------------
//
// Le relais ne doit plus savoir qui écrit. L'enveloppe ordinaire (ci-dessus,
// v3 compris) est mise dans une seconde enveloppe, ouverte seulement par le
// destinataire : clé éphémère P-256, ECDH avec sa clé de chiffrement,
// HKDF-SHA256, AES-256-GCM. Elle contient le code de l'expéditeur ; c'est
// l'enveloppe intérieure qui le prouve (sa clé de paire et ses données
// associées « expéditeur>destinataire »), comme avant.
//
// Pour ne pas ouvrir à n'importe qui un envoi anonyme, le relais exige un
// jeton de remise : signé par la clé du code destinataire, donné seulement à
// ses contacts (dans son profil chiffré). Le relais le vérifie sans rien
// garder (il survit à ses redémarrages).

/// Ce que signe un code pour son jeton de remise.
pub fn jeton_message(code: &str, nonce: &str) -> Vec<u8> {
    format!("talka-jeton:{code}:{nonce}").into_bytes()
}

/// L'ensemble de l'enveloppe scellée (en clair, une fois ouverte).
#[derive(Serialize, Deserialize)]
struct SealedSender {
    from: String,
    /// L'appareil de l'expéditeur (v4) ; absent d'un ancien client.
    #[serde(default, skip_serializing_if = "String::is_empty")]
    fd: String,
    body: String,
    /// Du remplissage (v6) : l'enveloppe a la taille d'un palier, pas celle
    /// du message. Ignoré à l'ouverture (un ancien client l'ignore aussi).
    #[serde(default, skip_serializing_if = "String::is_empty")]
    p: String,
}

/// Une boîte de couverture (v6, §7.15) : à nous seuls (`secret` ne quitte
/// pas l'appareil), indiscernable d'une boîte tournante pour le relais.
pub fn cover_box(secret: &[u8], day: u64, i: usize) -> String {
    use hmac::{Hmac, Mac};
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(secret).expect("toute longueur");
    mac.update(format!("talka-couverture:{day}:{i}").as_bytes());
    b64url(&mac.finalize().into_bytes()[..16])
}

/// Les paliers de taille des enveloppes scellées (v6, docs/SECURITE.md
/// §7.15) : 1 Kio, 2, 4… 256 Kio, puis des multiples de 256 Kio, sans
/// dépasser 5 Mio (en base64, l'enveloppe reste sous la limite du relais ;
/// au-delà, la taille reste celle du message).
pub fn sealed_bucket(n: usize) -> usize {
    const STEP: usize = 256 * 1024;
    const MAX: usize = 5 * 1024 * 1024;
    let b = if n <= STEP {
        n.max(1024).next_power_of_two()
    } else {
        n.div_ceil(STEP) * STEP
    };
    if b > MAX { n } else { b }
}

/// Le clair d'une enveloppe scellée, rempli jusqu'à son palier.
fn sealed_plain(from: &str, fd: &str, body: &str) -> Option<String> {
    let mut s = SealedSender {
        from: from.to_owned(),
        fd: fd.to_owned(),
        body: body.to_owned(),
        p: String::new(),
    };
    let base = serde_json::to_string(&s).ok()?.len();
    // `,"p":""` : 7 octets, puis autant de « 0 » qu'il faut (un au moins).
    let target = sealed_bucket(base + 8);
    s.p = "0".repeat(target - base - 7);
    serde_json::to_string(&s).ok()
}

fn sealed_key(shared: &[u8], eph: &[u8], to_dh: &[u8], to: &str) -> Option<Aes256Gcm> {
    let salt = [eph, to_dh].concat();
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared);
    let mut key = [0u8; 32];
    hk.expand(format!("talka-scelle-v1:{to}").as_bytes(), &mut key)
        .ok()?;
    Aes256Gcm::new_from_slice(&key).ok()
}

impl Identity {
    /// L'ancien jeton de remise, le même pour tous les contacts : révoqué
    /// auprès du relais depuis que chacun a le sien (`jeton_for`).
    pub fn jeton(&self) -> String {
        let Some(dh) = unb64(&self.dh_priv) else {
            return String::new();
        };
        let digest = Sha256::digest([b"talka-jeton-nonce:".as_slice(), &dh].concat());
        self.jeton_with(&b64(&digest[..16]))
    }

    /// Le nonce du jeton de remise donné à `contact`, à l'époque `epoch`
    /// (elle avance quand on le bloque : l'ancien jeton est révoqué). Tiré
    /// de la clé privée : rien à garder.
    pub fn jeton_nonce(&self, contact: &str, epoch: u32) -> String {
        let Some(dh) = unb64(&self.dh_priv) else {
            return String::new();
        };
        let digest = Sha256::digest(
            [
                b"talka-jeton-nonce:".as_slice(),
                &dh,
                format!(":{contact}:{epoch}").as_bytes(),
            ]
            .concat(),
        );
        b64(&digest[..16])
    }

    /// Le jeton de remise de ce code pour `contact` : `nonce.signature`. Un
    /// jeton par contact : le relais peut refuser celui d'un contact bloqué
    /// sans gêner les autres.
    pub fn jeton_for(&self, contact: &str, epoch: u32) -> String {
        self.jeton_with(&self.jeton_nonce(contact, epoch))
    }

    fn jeton_with(&self, nonce: &str) -> String {
        if nonce.is_empty() {
            return String::new();
        }
        let sig = self.sign(&jeton_message(&self.code, nonce));
        if sig.is_empty() {
            return String::new();
        }
        format!("{nonce}.{sig}")
    }
}

/// Le jeton est-il bien signé par la clé du code `to` (dont on donne les deux
/// clés publiques) ? Ce que vérifie le relais (`talka::code::verify_jeton`).
pub fn verify_jeton(to: &str, sign: &str, dh: &str, jeton: &str) -> bool {
    let (Some(s), Some(d)) = (unb64(sign), unb64(dh)) else {
        return false;
    };
    let Some((nonce, sig)) = jeton.split_once('.') else {
        return false;
    };
    if s.len() != 65 || d.len() != 65 || code_of(&s, &d) != to || nonce.len() > 64 {
        return false;
    }
    let (Ok(key), Some(sig)) = (
        VerifyingKey::from_sec1_bytes(&s),
        unb64(sig).and_then(|b| Signature::from_slice(&b).ok()),
    ) else {
        return false;
    };
    key.verify(&jeton_message(to, nonce), &sig).is_ok()
}

/// Scelle `body` (une enveloppe ordinaire de `from` pour `to`) :
/// base64(éphémère (65) ‖ iv (12) ‖ chiffré).
pub fn seal_sender(from: &str, to: &str, to_dh: &str, body: &str) -> Option<String> {
    seal_sender_from(from, "", to, to_dh, body)
}

/// Comme `seal_sender`, en disant aussi de quel appareil (`fd`, v4) vient
/// l'enveloppe.
pub fn seal_sender_from(from: &str, fd: &str, to: &str, to_dh: &str, body: &str) -> Option<String> {
    let to_dh_raw = unb64(to_dh)?;
    let public = PublicKey::from_sec1_bytes(&to_dh_raw).ok()?;
    let eph = SecretKey::random(&mut OsRng);
    let eph_raw = raw(&eph.public_key());
    let shared = p256::ecdh::diffie_hellman(eph.to_nonzero_scalar(), public.as_affine());
    let cipher = sealed_key(shared.raw_secret_bytes(), &eph_raw, &to_dh_raw, to)?;
    let plain = sealed_plain(from, fd, body)?;
    let iv = random_bytes(12);
    let ad = format!("talka-scelle:{to}");
    let ct = cipher
        .encrypt(
            Nonce::from_slice(&iv),
            Payload {
                msg: plain.as_bytes(),
                aad: ad.as_bytes(),
            },
        )
        .ok()?;
    let mut out = eph_raw;
    out.extend(iv);
    out.extend(ct);
    Some(b64(&out))
}

/// Ouvre une enveloppe scellée pour nous : (code de l'expéditeur, enveloppe
/// ordinaire). L'expéditeur n'est prouvé qu'en ouvrant ensuite celle-ci.
pub fn open_sealed(me: &Identity, sealed: &str) -> Option<(String, String)> {
    open_sealed_from(me, sealed).map(|(from, _, body)| (from, body))
}

/// Ouvre une enveloppe scellée : (code de l'expéditeur, son appareil (vide
/// pour un ancien client), enveloppe ordinaire).
pub fn open_sealed_from(me: &Identity, sealed: &str) -> Option<(String, String, String)> {
    let bytes = unb64(sealed)?;
    if bytes.len() < 65 + 12 + 16 {
        return None;
    }
    let (eph_raw, rest) = bytes.split_at(65);
    let public = PublicKey::from_sec1_bytes(eph_raw).ok()?;
    let secret = me.dh_key()?;
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), public.as_affine());
    let cipher = sealed_key(
        shared.raw_secret_bytes(),
        eph_raw,
        &unb64(&me.dh)?,
        &me.code,
    )?;
    let ad = format!("talka-scelle:{}", me.code);
    let pt = cipher
        .decrypt(
            Nonce::from_slice(&rest[..12]),
            Payload {
                msg: &rest[12..],
                aad: ad.as_bytes(),
            },
        )
        .ok()?;
    let s: SealedSender = serde_json::from_slice(&pt).ok()?;
    let from = normalize(&s.from).filter(|c| *c == s.from)?;
    let fd = if is_dev(&s.fd) { s.fd } else { String::new() };
    Some((from, fd, s.body))
}

// ----- Plusieurs appareils et boîtes tournantes (v4) -------------------------
//
// `docs/SECURITE.md` §7.11 à §7.13.

/// Le relais de Talka : un seul. Son adresse entre dans la dérivation des
/// boîtes tournantes, qui ne valent que pour lui.
pub const OFFICIAL_RELAY: &str = "https://talka-maxlestage-e497dd868a8c.herokuapp.com";

/// Un identifiant d'appareil : 16 caractères hexadécimaux minuscules.
pub fn is_dev(dev: &str) -> bool {
    dev.len() == 16
        && dev
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// Un nouvel identifiant d'appareil (8 octets aléatoires).
pub fn new_dev() -> String {
    random_bytes(8).iter().map(|b| format!("{b:02x}")).collect()
}

/// Le jour d'un horodatage (ms Unix, UTC) : les boîtes tournantes changent
/// chaque jour.
pub fn day(ms: f64) -> u64 {
    (ms.max(0.0) / 86_400_000.0) as u64
}

/// La clé des boîtes entre deux codes : ECDH de leurs clés d'identité, puis
/// HKDF-SHA256 lié au relais (§7.13). Seuls les deux savent la calculer.
pub fn boites_key(me: &Identity, peer_code: &str, peer_dh: &str) -> Option<[u8; 32]> {
    let public = PublicKey::from_sec1_bytes(&unb64(peer_dh)?).ok()?;
    let secret = me.dh_key()?;
    let shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), public.as_affine());
    let (a, b) = if me.code.as_str() < peer_code {
        (me.code.as_str(), peer_code)
    } else {
        (peer_code, me.code.as_str())
    };
    let salt = format!("talka-boites-v1:{OFFICIAL_RELAY}");
    let hk = Hkdf::<Sha256>::new(Some(salt.as_bytes()), shared.raw_secret_bytes());
    let mut key = [0u8; 32];
    hk.expand(format!("{a}:{b}").as_bytes(), &mut key).ok()?;
    Some(key)
}

/// La boîte de `jour` où l'on écrit à l'appareil `dev` de `to` :
/// base64url(HMAC-SHA256(clé, "talka-boite:<to>/<dev>:<jour>")[0..16]).
pub fn boite(key: &[u8; 32], to: &str, dev: &str, day: u64) -> String {
    use hmac::{Hmac, Mac};
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("toute longueur");
    mac.update(format!("talka-boite:{to}/{dev}:{day}").as_bytes());
    b64url(&mac.finalize().into_bytes()[..16])
}

pub fn b64url(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

pub fn unb64url(text: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(text.trim_end_matches('='))
        .ok()
}

/// Le préfixe d'un lien de liaison (QR code).
pub const LINK_PREFIX: &str = "talka-lien:";

/// Ce que montre le nouvel appareil (§7.12) : son `dev`, l'identifiant de
/// liaison et sa clé publique éphémère.
#[derive(Clone, Debug, PartialEq)]
pub struct LinkOffer {
    pub dev: String,
    pub lid: [u8; 16],
    pub lk: Vec<u8>,
}

/// Le côté secret de la liaison, gardé par le nouvel appareil le temps
/// qu'elle se fasse.
pub struct LinkSecret {
    pub offer: LinkOffer,
    key: SecretKey,
}

impl LinkOffer {
    /// `talka-lien:<base64url(0x01 ‖ dev ‖ lid ‖ lk)>`.
    pub fn text(&self) -> String {
        let dev: Vec<u8> = (0..8)
            .filter_map(|i| u8::from_str_radix(&self.dev[i * 2..i * 2 + 2], 16).ok())
            .collect();
        let bytes = [[1u8].as_slice(), &dev, &self.lid, &self.lk].concat();
        format!("{LINK_PREFIX}{}", b64url(&bytes))
    }

    /// Lit un lien de liaison (collé, ou lu dans un QR code).
    pub fn parse(text: &str) -> Option<LinkOffer> {
        let rest = text.trim().strip_prefix(LINK_PREFIX)?;
        let bytes = unb64url(rest)?;
        if bytes.len() != 1 + 8 + 16 + 65 || bytes[0] != 1 {
            return None;
        }
        PublicKey::from_sec1_bytes(&bytes[25..]).ok()?;
        Some(LinkOffer {
            dev: bytes[1..9].iter().map(|b| format!("{b:02x}")).collect(),
            lid: bytes[9..25].try_into().ok()?,
            lk: bytes[25..].to_vec(),
        })
    }

    /// La boîte de liaison, où le nouvel appareil attend.
    pub fn boite(&self) -> String {
        b64url(&Sha256::digest([b"talka-lien:".as_slice(), &self.lid].concat())[..16])
    }
}

impl LinkSecret {
    pub fn new() -> LinkSecret {
        let key = SecretKey::random(&mut OsRng);
        let mut lid = [0u8; 16];
        OsRng.fill_bytes(&mut lid);
        LinkSecret {
            offer: LinkOffer {
                dev: new_dev(),
                lid,
                lk: raw(&key.public_key()),
            },
            key,
        }
    }

    /// La clé de liaison, à partir de la clé éphémère de l'appareil du compte.
    pub fn key(&self, ek: &[u8]) -> Option<[u8; 32]> {
        let public = PublicKey::from_sec1_bytes(ek).ok()?;
        let shared = p256::ecdh::diffie_hellman(self.key.to_nonzero_scalar(), public.as_affine());
        link_key(
            shared.raw_secret_bytes(),
            &self.offer.lid,
            ek,
            &self.offer.lk,
        )
    }
}

impl Default for LinkSecret {
    fn default() -> Self {
        Self::new()
    }
}

fn link_key(shared: &[u8], lid: &[u8], ek: &[u8], lk: &[u8]) -> Option<[u8; 32]> {
    let hk = Hkdf::<Sha256>::new(Some(lid), shared);
    let mut key = [0u8; 32];
    hk.expand(&[b"talka-lien-v1:".as_slice(), ek, lk].concat(), &mut key)
        .ok()?;
    Some(key)
}

/// L'appareil du compte répond à une offre : (sa clé éphémère publique, la
/// clé de liaison).
pub fn link_answer(offer: &LinkOffer) -> Option<(Vec<u8>, [u8; 32])> {
    let public = PublicKey::from_sec1_bytes(&offer.lk).ok()?;
    let eph = SecretKey::random(&mut OsRng);
    let ek = raw(&eph.public_key());
    let shared = p256::ecdh::diffie_hellman(eph.to_nonzero_scalar(), public.as_affine());
    let key = link_key(shared.raw_secret_bytes(), &offer.lid, &ek, &offer.lk)?;
    Some((ek, key))
}

/// Le code de vérification d'une liaison, le même sur les deux appareils.
pub fn link_verification(ek: &[u8], lk: &[u8]) -> String {
    let d = Sha256::digest([b"talka-lien-verif:".as_slice(), ek, lk].concat());
    let n = u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 1_000_000;
    format!("{:03} {:03}", n / 1000, n % 1000)
}

/// La taille d'un morceau de liaison (en clair).
pub const LINK_CHUNK: usize = 2 * 1024 * 1024;

/// Chiffre ce que la liaison transporte, en morceaux :
/// `{"e","i","n","c"}` (JSON), à déposer dans la boîte de liaison.
pub fn link_seal(key: &[u8; 32], lid: &[u8; 16], ek: &[u8], plain: &[u8]) -> Option<Vec<String>> {
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    let chunks: Vec<&[u8]> = if plain.is_empty() {
        vec![plain]
    } else {
        plain.chunks(LINK_CHUNK).collect()
    };
    let n = chunks.len();
    let lid64 = b64url(lid);
    chunks
        .iter()
        .enumerate()
        .map(|(i, chunk)| {
            let iv = random_bytes(12);
            let ad = format!("talka-lien:{lid64}:{i}/{n}");
            let ct = cipher
                .encrypt(
                    Nonce::from_slice(&iv),
                    Payload {
                        msg: chunk,
                        aad: ad.as_bytes(),
                    },
                )
                .ok()?;
            Some(
                serde_json::json!({ "e": b64(ek), "i": i, "n": n, "c": b64(&[iv, ct].concat()) })
                    .to_string(),
            )
        })
        .collect()
}

/// Ouvre un morceau de liaison : (indice, nombre, clair).
pub fn link_open(
    key: &[u8; 32],
    lid: &[u8; 16],
    chunk: &serde_json::Value,
) -> Option<(usize, usize, Vec<u8>)> {
    let i = chunk["i"].as_u64()? as usize;
    let n = chunk["n"].as_u64()? as usize;
    if n == 0 || i >= n || n > 64 {
        return None;
    }
    let bytes = unb64(chunk["c"].as_str()?)?;
    if bytes.len() < 12 + 16 {
        return None;
    }
    let ad = format!("talka-lien:{}:{i}/{n}", b64url(lid));
    let pt = Aes256Gcm::new_from_slice(key)
        .ok()?
        .decrypt(
            Nonce::from_slice(&bytes[..12]),
            Payload {
                msg: &bytes[12..],
                aad: ad.as_bytes(),
            },
        )
        .ok()?;
    Some((i, n, pt))
}

// ----- L'aperçu des notifications -------------------------------------------
//
// Ce que la notification affiche (« Léa : Bonjour »), chiffré par
// l'expéditeur pour la clé de notification du destinataire : une clé P-256
// tirée et gardée par le service worker du navigateur (non exportable), dont
// la moitié publique voyage dans le profil chiffré. Le service worker l'ouvre
// avec WebCrypto.
//
// Authentifié : la clé mêle un ECDH éphémère et un ECDH avec la clé
// d'identité (`dh`) de l'expéditeur. Seul qui détient la clé privée d'un
// contact peut faire afficher un aperçu en son nom ; le service worker ne
// connaît que vos contacts (leur `dh`, donnés par Talka). Sans
// confidentialité persistante : la clé de notification ne change pas. Elle
// ne sert qu'à cet aperçu, jamais aux messages.
//
// base64(0x02 ‖ code de l'expéditeur (10) ‖ éphémère (65) ‖ iv (12) ‖ chiffré ‖ tag)

const APERCU_V: u8 = 2;

fn apercu_key(
    eph_shared: &[u8],
    static_shared: &[u8],
    eph: &[u8],
    nk: &[u8],
    from: &str,
) -> Option<Aes256Gcm> {
    let salt = [eph, nk].concat();
    let ikm = [eph_shared, static_shared].concat();
    let mut key = [0u8; 32];
    Hkdf::<Sha256>::new(Some(&salt), &ikm)
        .expand(format!("talka-apercu-v2:{from}").as_bytes(), &mut key)
        .ok()?;
    Aes256Gcm::new_from_slice(&key).ok()
}

/// Une clé de notification valable : un point P-256 (65 octets, base64).
pub fn valid_nk(nk: &str) -> bool {
    unb64(nk).is_some_and(|b| b.len() == 65 && PublicKey::from_sec1_bytes(&b).is_ok())
}

/// Chiffre un aperçu (du JSON) de `me` pour la clé de notification `nk`.
pub fn seal_apercu(me: &Identity, nk: &str, plain: &str) -> Option<String> {
    let nk_raw = unb64(nk)?;
    let public = PublicKey::from_sec1_bytes(&nk_raw).ok()?;
    if me.code.len() != 10 {
        return None;
    }
    let eph = SecretKey::random(&mut OsRng);
    let eph_raw = raw(&eph.public_key());
    let eph_shared = p256::ecdh::diffie_hellman(eph.to_nonzero_scalar(), public.as_affine());
    let static_shared =
        p256::ecdh::diffie_hellman(me.dh_key()?.to_nonzero_scalar(), public.as_affine());
    let cipher = apercu_key(
        eph_shared.raw_secret_bytes(),
        static_shared.raw_secret_bytes(),
        &eph_raw,
        &nk_raw,
        &me.code,
    )?;
    let iv = random_bytes(12);
    let aad = format!("talka-apercu:{}", me.code);
    let ct = cipher
        .encrypt(
            Nonce::from_slice(&iv),
            Payload {
                msg: plain.as_bytes(),
                aad: aad.as_bytes(),
            },
        )
        .ok()?;
    let mut out = vec![APERCU_V];
    out.extend(me.code.as_bytes());
    out.extend(eph_raw);
    out.extend(iv);
    out.extend(ct);
    Some(b64(&out))
}

/// Ouvre un aperçu avec la clé privée de notification (32 octets) : ce que
/// fait le service worker, ici pour les tests et les vecteurs. `dh_of`
/// donne la clé `dh` d'un contact connu ; un inconnu, un faussaire ou un
/// aperçu abîmé : `None`. Renvoie (code de l'expéditeur, aperçu).
pub fn open_apercu(
    nk_priv: &[u8],
    sealed: &str,
    dh_of: impl Fn(&str) -> Option<String>,
) -> Option<(String, String)> {
    let bytes = unb64(sealed)?;
    if bytes.len() < 1 + 10 + 65 + 12 + 16 || bytes[0] != APERCU_V {
        return None;
    }
    let from = std::str::from_utf8(&bytes[1..11]).ok()?.to_owned();
    let from_dh = PublicKey::from_sec1_bytes(&unb64(&dh_of(&from)?)?).ok()?;
    let secret = SecretKey::from_slice(nk_priv).ok()?;
    let (eph_raw, rest) = bytes[11..].split_at(65);
    let eph = PublicKey::from_sec1_bytes(eph_raw).ok()?;
    let eph_shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), eph.as_affine());
    let static_shared = p256::ecdh::diffie_hellman(secret.to_nonzero_scalar(), from_dh.as_affine());
    let cipher = apercu_key(
        eph_shared.raw_secret_bytes(),
        static_shared.raw_secret_bytes(),
        eph_raw,
        &raw(&secret.public_key()),
        &from,
    )?;
    let aad = format!("talka-apercu:{from}");
    let pt = cipher
        .decrypt(
            Nonce::from_slice(&rest[..12]),
            Payload {
                msg: &rest[12..],
                aad: aad.as_bytes(),
            },
        )
        .ok()?;
    Some((from, String::from_utf8(pt).ok()?))
}

// ----- La mémoire de l'appareil, chiffrée au repos ---------------------------
//
// Chaque valeur gardée (messages, contacts, clés…) est chiffrée avant d'aller
// sur le disque : AES-256-GCM avec la clé de l'appareil, liée à sa clé de
// ligne (une valeur ne peut pas être déplacée sur une autre). Talka la
// déchiffre en mémoire pour l'afficher.

/// Le début d'une valeur chiffrée (octets) : `t1` puis iv (12) ‖ chiffré.
pub const ROW_MAGIC: &[u8; 2] = b"t1";

fn row_aad(name: &str) -> Vec<u8> {
    format!("talka-repos:{name}").into_bytes()
}

/// Chiffre la valeur `value` de la ligne `name` avec la clé de l'appareil.
pub fn seal_row(key: &[u8], name: &str, value: &str) -> Option<Vec<u8>> {
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    let iv = random_bytes(12);
    let aad = row_aad(name);
    let ct = cipher
        .encrypt(
            Nonce::from_slice(&iv),
            Payload {
                msg: value.as_bytes(),
                aad: &aad,
            },
        )
        .ok()?;
    let mut out = ROW_MAGIC.to_vec();
    out.extend(iv);
    out.extend(ct);
    Some(out)
}

/// Déchiffre une valeur gardée ; `None` si elle est abîmée, d'une autre
/// ligne ou d'une autre clé.
pub fn open_row(key: &[u8], name: &str, sealed: &[u8]) -> Option<String> {
    let rest = sealed.strip_prefix(ROW_MAGIC)?;
    if rest.len() < 12 + 16 {
        return None;
    }
    let cipher = Aes256Gcm::new_from_slice(key).ok()?;
    let aad = row_aad(name);
    let pt = cipher
        .decrypt(
            Nonce::from_slice(&rest[..12]),
            Payload {
                msg: &rest[12..],
                aad: &aad,
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

/// Argon2id (RFC 9106) : lent et gourmand en mémoire, pour qu'un code
/// volé avec les fichiers ne se devine pas à la chaîne (une carte graphique
/// n'y gagne presque rien). 19 Mio, 2 passes : les réglages minimum de
/// l'OWASP, environ une demi-seconde dans un navigateur.
pub const ARGON_M: u32 = 19_456;
pub const ARGON_T: u32 = 2;

/// 32 octets tirés d'un code par Argon2id.
pub fn argon2id(secret: &str, salt: &[u8], m: u32, t: u32) -> Option<[u8; 32]> {
    let params = argon2::Params::new(m, t, 1, Some(32)).ok()?;
    let mut out = [0u8; 32];
    argon2::Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params)
        .hash_password_into(secret.as_bytes(), salt, &mut out)
        .ok()?;
    Some(out)
}

/// Le code de verrouillage, haché : jamais gardé en clair. `v` 2 : Argon2id
/// (`m`, `t`) ; absent : PBKDF2 (les codes d'avant, rehachés au premier
/// déverrouillage).
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct PinHash {
    pub salt: String,
    pub hash: String,
    #[serde(default, skip_serializing_if = "is_zero_u32")]
    pub v: u32,
    #[serde(default, skip_serializing_if = "is_zero_u32")]
    pub m: u32,
    #[serde(default, skip_serializing_if = "is_zero_u32")]
    pub t: u32,
}

fn is_zero_u32(n: &u32) -> bool {
    *n == 0
}

/// Les nouveaux codes : 6 chiffres au moins (les anciens de 4 ou 5 restent
/// acceptés au déverrouillage).
pub const PIN_MIN: usize = 6;
pub const PIN_MAX: usize = 12;

pub fn hash_pin(pin: &str) -> PinHash {
    let salt = random_bytes(16);
    PinHash {
        hash: b64(&argon2id(pin, &salt, ARGON_M, ARGON_T).unwrap_or_default()),
        salt: b64(&salt),
        v: 2,
        m: ARGON_M,
        t: ARGON_T,
    }
}

/// Comparaison en temps constant.
fn same(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

pub fn check_pin(pin: &str, stored: &PinHash) -> bool {
    let (Some(salt), Some(hash)) = (unb64(&stored.salt), unb64(&stored.hash)) else {
        return false;
    };
    let got = match stored.v {
        0 | 1 => pbkdf2(pin, &salt, PIN_ROUNDS),
        2 if stored.m >= 8 && stored.m <= 1 << 20 && (1..=10).contains(&stored.t) => {
            match argon2id(pin, &salt, stored.m, stored.t) {
                Some(h) => h,
                None => return false,
            }
        }
        _ => return false,
    };
    same(&got, &hash)
}

/// Un code d'avant (PBKDF2) : à rehacher avec Argon2id.
pub fn pin_is_old(stored: &PinHash) -> bool {
    stored.v < 2
}

// ----- La clé de l'appareil, protégée par le code ou une passkey -------------
//
// La clé de l'appareil (qui chiffre la mémoire, voir `seal_row`) peut être
// chiffrée en plus par une clé que seul l'utilisateur apporte : Argon2id du
// code de verrouillage, ou la sortie PRF d'une passkey. Sans elle, les
// fichiers de Talka ne s'ouvrent pas, même avec le navigateur.

/// La clé tirée de la sortie PRF d'une passkey (32 octets ou plus).
pub fn passkey_key(prf: &[u8]) -> Option<[u8; 32]> {
    let mut key = [0u8; 32];
    Hkdf::<Sha256>::new(Some(b"talka-passkey"), prf)
        .expand(b"talka-cle-v1", &mut key)
        .ok()?;
    Some(key)
}

/// Chiffre la clé de l'appareil par la clé de l'utilisateur :
/// iv (12) ‖ chiffré ‖ tag.
pub fn lock_device_key(user_key: &[u8], device_key: &[u8]) -> Option<Vec<u8>> {
    let iv = random_bytes(12);
    let ct = Aes256Gcm::new_from_slice(user_key)
        .ok()?
        .encrypt(
            Nonce::from_slice(&iv),
            Payload {
                msg: device_key,
                aad: b"talka-cle",
            },
        )
        .ok()?;
    Some([iv, ct].concat())
}

/// La clé de l'appareil, si c'est la bonne clé de l'utilisateur.
pub fn unlock_device_key(user_key: &[u8], locked: &[u8]) -> Option<Vec<u8>> {
    if locked.len() != 12 + 32 + 16 {
        return None;
    }
    Aes256Gcm::new_from_slice(user_key)
        .ok()?
        .decrypt(
            Nonce::from_slice(&locked[..12]),
            Payload {
                msg: &locked[12..],
                aad: b"talka-cle",
            },
        )
        .ok()
}

/// Une sauvegarde : un mot de passe → Argon2id (`talka` 3 ; PBKDF2 pour
/// les fichiers d'avant, `talka` 2) → AES-256-GCM. Elle contient la clé
/// d'identité : Argon2id la rend bien plus chère à attaquer hors ligne.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Backup {
    pub talka: u32,
    pub salt: String,
    pub iv: String,
    pub data: String,
    #[serde(default, skip_serializing_if = "is_zero_u32")]
    pub m: u32,
    #[serde(default, skip_serializing_if = "is_zero_u32")]
    pub t: u32,
}

/// Argon2id pour la sauvegarde : 46 Mio, 1 passe (une option de l'OWASP).
pub const BACKUP_M: u32 = 47_104;
pub const BACKUP_T: u32 = 1;

pub fn encrypt_backup(password: &str, plain: &str) -> Option<Backup> {
    let salt = random_bytes(16);
    let iv = random_bytes(12);
    let key = argon2id(password, &salt, BACKUP_M, BACKUP_T)?;
    let ct = Aes256Gcm::new_from_slice(&key)
        .ok()?
        .encrypt(Nonce::from_slice(&iv), plain.as_bytes())
        .ok()?;
    Some(Backup {
        talka: 3,
        salt: b64(&salt),
        iv: b64(&iv),
        data: b64(&ct),
        m: BACKUP_M,
        t: BACKUP_T,
    })
}

pub fn decrypt_backup(password: &str, file: &Backup) -> Result<String, &'static str> {
    let bad = "Ce fichier n'est pas une sauvegarde Talka.";
    let salt = unb64(&file.salt).ok_or(bad)?;
    let iv = unb64(&file.iv).ok_or(bad)?;
    let data = unb64(&file.data).ok_or(bad)?;
    let key = match file.talka {
        2 => pbkdf2(password, &salt, BACKUP_ROUNDS),
        3 if (8..=1 << 21).contains(&file.m) && (1..=10).contains(&file.t) => {
            argon2id(password, &salt, file.m, file.t).ok_or(bad)?
        }
        3 => return Err("Cette sauvegarde demande trop de mémoire pour être ouverte."),
        _ => return Err(bad),
    };
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
    fn sealed_envelopes_come_in_a_few_sizes() {
        assert_eq!(sealed_bucket(10), 1024);
        assert_eq!(sealed_bucket(1024), 1024);
        assert_eq!(sealed_bucket(1025), 2048);
        assert_eq!(sealed_bucket(200_000), 262_144);
        assert_eq!(sealed_bucket(300_000), 524_288);
        assert_eq!(sealed_bucket(6_000_000), 6_000_000);
        for len in [0, 100, 900, 1_000, 5_000, 70_000] {
            let plain = sealed_plain("K7Q2MX9PRT", "00000000000000a1", &"x".repeat(len)).unwrap();
            assert_eq!(plain.len(), sealed_bucket(plain.len()), "{len}");
            assert!(plain.len() >= len);
        }
        // Deux messages de tailles proches : la même enveloppe.
        let a = sealed_plain("K7Q2MX9PRT", "", "court").unwrap();
        let b = sealed_plain("K7Q2MX9PRT", "", &"un peu plus long".repeat(20)).unwrap();
        assert_eq!(a.len(), b.len());
        // Le remplissage ne change rien à ce qu'on lit (et un ancien client
        // l'ignore : champ inconnu).
        let me = Identity::create(0.0);
        let sealed = seal_sender_from("K7Q2MX9PRT", "", &me.code, &me.dh, "corps").unwrap();
        assert_eq!(
            open_sealed_from(&me, &sealed),
            Some(("K7Q2MX9PRT".into(), String::new(), "corps".into()))
        );
    }

    #[test]
    fn sealed_sender_hides_the_author_but_cannot_lie_about_it() {
        let (a, b, c) = (
            Identity::create(0.0),
            Identity::create(0.0),
            Identity::create(0.0),
        );
        let inner = seal(&a, &b.code, &b.dh, "{\"t\":\"msg\"}").unwrap();
        let sealed = seal_sender(&a.code, &b.code, &b.dh, &inner).unwrap();
        // Rien du code de l'expéditeur ne se voit de l'extérieur.
        assert!(!sealed.contains(&a.code));
        assert!(!String::from_utf8_lossy(&unb64(&sealed).unwrap()).contains(&a.code));
        let (from, body) = open_sealed(&b, &sealed).unwrap();
        assert_eq!(from, a.code);
        assert_eq!(
            open(&b, &from, &a.dh, &body).as_deref(),
            Some("{\"t\":\"msg\"}")
        );
        // Seul le destinataire l'ouvre.
        assert!(open_sealed(&c, &sealed).is_none());
        assert!(open_sealed(&a, &sealed).is_none());
        // Abîmée : refusée.
        let mut bytes = unb64(&sealed).unwrap();
        let last = bytes.len() - 1;
        bytes[last] ^= 1;
        assert!(open_sealed(&b, &b64(&bytes)).is_none());
        // C se fait passer pour A : l'enveloppe scellée s'ouvre, mais
        // l'enveloppe intérieure (clé de paire C–B) ne s'ouvre pas au nom de A.
        let forged_inner = seal(&c, &b.code, &b.dh, "{\"t\":\"msg\"}").unwrap();
        let forged = seal_sender(&a.code, &b.code, &b.dh, &forged_inner).unwrap();
        let (claimed, body) = open_sealed(&b, &forged).unwrap();
        assert_eq!(claimed, a.code);
        assert!(open(&b, &claimed, &a.dh, &body).is_none());
        // Deux scellements du même message ne se ressemblent pas.
        assert_ne!(
            sealed,
            seal_sender(&a.code, &b.code, &b.dh, &inner).unwrap()
        );
    }

    #[test]
    fn notification_preview() {
        let (a, b) = (Identity::create(0.0), Identity::create(0.0));
        let nk = SecretKey::random(&mut OsRng);
        let nk_pub = b64(&raw(&nk.public_key()));
        let plain = r#"{"n":"Léa","t":"Bonjour 👋"}"#;
        let sealed = seal_apercu(&a, &nk_pub, plain).unwrap();
        assert!(!sealed.contains("Bonjour"));
        let contacts = |code: &str| (code == a.code).then(|| a.dh.clone());
        assert_eq!(
            open_apercu(&nk.to_bytes(), &sealed, contacts),
            Some((a.code.clone(), plain.to_owned()))
        );
        // Un inconnu ne fait rien afficher.
        assert!(open_apercu(&nk.to_bytes(), &sealed, |_| None).is_none());
        // B se fait passer pour A (il met le code de A) : refusé.
        let mut forged = Identity::create(0.0);
        forged.code = a.code.clone();
        let fake = seal_apercu(&forged, &nk_pub, plain).unwrap();
        assert!(open_apercu(&nk.to_bytes(), &fake, contacts).is_none());
        // A sous le nom de B, ou une autre clé de notification : refusé.
        let swapped = {
            let mut raw_bytes = unb64(&sealed).unwrap();
            raw_bytes[1..11].copy_from_slice(b.code.as_bytes());
            b64(&raw_bytes)
        };
        assert!(open_apercu(&nk.to_bytes(), &swapped, |_| Some(b.dh.clone())).is_none());
        let other = SecretKey::random(&mut OsRng);
        assert!(open_apercu(&other.to_bytes(), &sealed, contacts).is_none());
        assert!(seal_apercu(&a, "pas une clé", "x").is_none());
        assert!(valid_nk(&nk_pub));
        assert!(!valid_nk("pas une clé") && !valid_nk(&b64(&[4; 65])));
    }

    #[test]
    fn pin_argon2_and_old_pbkdf2() {
        let h = hash_pin("246813");
        assert_eq!((h.v, h.m, h.t), (2, ARGON_M, ARGON_T));
        assert!(check_pin("246813", &h) && !check_pin("246812", &h));
        assert!(!pin_is_old(&h));
        // Un code d'avant (PBKDF2, sans `v`) se vérifie toujours.
        let salt = random_bytes(16);
        let old: PinHash = serde_json::from_value(serde_json::json!({
            "salt": b64(&salt), "hash": b64(&pbkdf2("2580", &salt, PIN_ROUNDS)),
        }))
        .unwrap();
        assert!(check_pin("2580", &old) && !check_pin("2581", &old) && pin_is_old(&old));
        // Des réglages absurdes (un fichier trafiqué) : refusé, sans calcul.
        let mut bad = h.clone();
        bad.m = u32::MAX;
        assert!(!check_pin("246813", &bad));
        // Argon2id lui-même est vérifié contre l'implémentation Swift (pure,
        // vérifiée par les vecteurs de RFC 9106) : `pin` dans vectors.json.
        let k = argon2id("password", b"somesalt", 32, 2).unwrap();
        assert_ne!(k, argon2id("password", b"somesalt", 32, 3).unwrap());
    }

    #[test]
    fn backup_argon2_and_old_pbkdf2() {
        let file = encrypt_backup("une longue phrase secrète", "{\"x\":1}").unwrap();
        assert_eq!((file.talka, file.m, file.t), (3, BACKUP_M, BACKUP_T));
        assert_eq!(
            decrypt_backup("une longue phrase secrète", &file).as_deref(),
            Ok("{\"x\":1}")
        );
        assert!(decrypt_backup("une autre phrase", &file).is_err());
        let mut greedy = file.clone();
        greedy.m = u32::MAX;
        assert!(decrypt_backup("une longue phrase secrète", &greedy).is_err());
        // Une sauvegarde d'avant (PBKDF2) s'ouvre toujours.
        let (salt, iv) = (random_bytes(16), random_bytes(12));
        let key = pbkdf2("motdepasse", &salt, BACKUP_ROUNDS);
        let ct = Aes256Gcm::new_from_slice(&key)
            .unwrap()
            .encrypt(Nonce::from_slice(&iv), b"{}".as_slice())
            .unwrap();
        let old: Backup = serde_json::from_value(serde_json::json!({
            "talka": 2, "salt": b64(&salt), "iv": b64(&iv), "data": b64(&ct),
        }))
        .unwrap();
        assert_eq!(decrypt_backup("motdepasse", &old).as_deref(), Ok("{}"));
    }

    #[test]
    fn device_key_locked_by_the_user() {
        let device = random_bytes(32);
        let code = argon2id("246813", b"0123456789abcdef", 64, 1).unwrap();
        let locked = lock_device_key(&code, &device).unwrap();
        assert_eq!(unlock_device_key(&code, &locked), Some(device.clone()));
        let wrong = argon2id("246812", b"0123456789abcdef", 64, 1).unwrap();
        assert!(unlock_device_key(&wrong, &locked).is_none());
        let pk = passkey_key(&[7; 32]).unwrap();
        let locked = lock_device_key(&pk, &device).unwrap();
        assert_eq!(unlock_device_key(&pk, &locked), Some(device));
        assert!(unlock_device_key(&passkey_key(&[8; 32]).unwrap(), &locked).is_none());
    }

    #[test]
    fn rows_at_rest() {
        let key = random_bytes(32);
        let json = r#"{"text":"Bonjour 👋","from":"KD6YMWRFXX"}"#;
        let sealed = seal_row(&key, "messages/c|m1", json).unwrap();
        assert!(sealed.starts_with(ROW_MAGIC));
        assert!(!String::from_utf8_lossy(&sealed).contains("Bonjour"));
        assert_ne!(
            sealed,
            seal_row(&key, "messages/c|m1", json).unwrap(),
            "un iv neuf"
        );
        assert_eq!(
            open_row(&key, "messages/c|m1", &sealed).as_deref(),
            Some(json)
        );
        // Une autre ligne, une autre clé, un octet changé : illisible.
        assert!(open_row(&key, "messages/c|m2", &sealed).is_none());
        assert!(open_row(&random_bytes(32), "messages/c|m1", &sealed).is_none());
        let mut bad = sealed.clone();
        bad[20] ^= 1;
        assert!(open_row(&key, "messages/c|m1", &bad).is_none());
        assert!(open_row(&key, "x", b"{}").is_none());
    }

    #[test]
    fn a_token_per_contact() {
        let a = Identity::create(0.0);
        let j1 = a.jeton_for("BBBBBBBBBB", 0);
        assert_eq!(j1, a.jeton_for("BBBBBBBBBB", 0), "toujours le même jeton");
        assert!(verify_jeton(&a.code, &a.sign, &a.dh, &j1));
        // Un autre contact, une autre époque : un autre nonce.
        let n = |c: &str, e: u32| a.jeton_nonce(c, e);
        assert_ne!(n("BBBBBBBBBB", 0), n("CCCCCCCCCC", 0));
        assert_ne!(n("BBBBBBBBBB", 0), n("BBBBBBBBBB", 1));
        assert_eq!(j1.split('.').next(), Some(n("BBBBBBBBBB", 0).as_str()));
        assert_ne!(
            a.jeton().split('.').next(),
            Some(n("BBBBBBBBBB", 0).as_str())
        );
    }

    #[test]
    fn delivery_token() {
        let (a, b) = (Identity::create(0.0), Identity::create(0.0));
        let j = a.jeton();
        assert_eq!(j, a.jeton(), "toujours le même jeton");
        assert!(verify_jeton(&a.code, &a.sign, &a.dh, &j));
        // Pas pour un autre code, ni avec d'autres clés.
        assert!(!verify_jeton(&b.code, &b.sign, &b.dh, &j));
        assert!(!verify_jeton(&a.code, &b.sign, &a.dh, &j));
        assert!(!verify_jeton(&a.code, &a.sign, &a.dh, &b.jeton()));
        assert!(!verify_jeton(&a.code, &a.sign, &a.dh, "pas-un-jeton"));
        let (nonce, sig) = j.split_once('.').unwrap();
        assert!(!verify_jeton(
            &a.code,
            &a.sign,
            &a.dh,
            &format!("x{nonce}.{sig}")
        ));
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
        let fp = fingerprint(&a.sign, &a.dh);
        assert!(valid_fingerprint(&fp) && fp != fingerprint(&b.sign, &b.dh));
        assert!(!valid_fingerprint("trop/court") && !valid_fingerprint(&format!("{fp}x")));
        let (ka, kb) = (
            (a.code.as_str(), a.sign.as_str(), a.dh.as_str()),
            (b.code.as_str(), b.sign.as_str(), b.dh.as_str()),
        );
        assert_eq!(safety_number(ka, kb), safety_number(kb, ka));
        // D'autres clés sous le même code : un autre numéro.
        assert_ne!(
            safety_number(ka, kb),
            safety_number(ka, (b.code.as_str(), a.sign.as_str(), b.dh.as_str()))
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
