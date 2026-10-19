//! Les fichiers du navigateur (`web/`), rangés dans le binaire : un seul
//! fichier à déployer, qui marche d'où qu'on le lance.
//!
//! Ils sont servis sous `/s/<version>/…`, où la version est l'empreinte de
//! tous les fichiers : une mise à jour change toutes les adresses, et les
//! modules JavaScript, qui s'importent les uns les autres par des chemins
//! relatifs, restent ensemble dans la même version. Le navigateur peut donc
//! les garder un an.

use std::sync::OnceLock;

use paquets::vitesse::{Request, Response};
use sha2::{Digest, Sha256};

macro_rules! files {
    ($($path:literal),* $(,)?) => {
        &[$(($path, include_bytes!(concat!("../web/", $path)) as &[u8])),*]
    };
}

/// `(chemin, contenu)` de chaque fichier de `web/`.
pub const FILES: &[(&str, &[u8])] = files![
    "app.css",
    "site.css",
    "logo.svg",
    "app/main.js",
    "app/db.js",
    "app/crypto.js",
    "app/net.js",
    "app/core.js",
    "app/ui.js",
    "app/conv.js",
    "app/dom.js",
    "app/media.js",
    "app/calls.js",
    "app/emoji.js",
    "app/sounds.js",
    "app/lock.js",
    "site.js",
    "icons/icon-192.png",
    "icons/icon-512.png",
    "icons/icon-maskable-512.png",
    "icons/apple-touch-icon.png",
];

/// L'empreinte de tous les fichiers : 10 caractères hexadécimaux.
pub fn version() -> &'static str {
    static VERSION: OnceLock<String> = OnceLock::new();
    VERSION.get_or_init(|| {
        let mut hash = Sha256::new();
        for (path, bytes) in FILES {
            hash.update(path.as_bytes());
            hash.update(bytes);
        }
        hash.finalize()[..5]
            .iter()
            .map(|b| format!("{b:02x}"))
            .collect()
    })
}

/// L'adresse versionnée d'un fichier : `url("app.css")` → `/s/1a2b3c4d5e/app.css`.
pub fn url(path: &str) -> String {
    format!("/s/{}/{path}", version())
}

fn content_type(path: &str) -> &'static str {
    match path.rsplit('.').next() {
        Some("css") => "text/css; charset=utf-8",
        Some("js") => "text/javascript; charset=utf-8",
        Some("svg") => "image/svg+xml",
        Some("png") => "image/png",
        _ => "application/octet-stream",
    }
}

/// Le contenu d'un fichier de `web/`.
pub fn get(path: &str) -> Option<&'static [u8]> {
    FILES.iter().find(|(p, _)| *p == path).map(|(_, b)| *b)
}

/// `GET /s/:version/*` : une ancienne version reçoit les fichiers actuels.
pub async fn serve(req: Request) -> Response {
    let path = req.path();
    let rest = path.strip_prefix("/s/").unwrap_or("");
    let (version, file) = rest.split_once('/').unwrap_or(("", ""));
    let Some(bytes) = get(file) else {
        return Response::new().status(404).text("introuvable");
    };
    let cache = if version == self::version() {
        "public, max-age=31536000, immutable"
    } else {
        "no-cache"
    };
    Response::new()
        .header("content-type", content_type(file))
        .header("cache-control", cache)
        .send(bytes.to_vec())
}

/// Les icônes et le logo à leur adresse fixe (manifeste, favicon).
pub async fn fixed(req: Request) -> Response {
    let file = req.path().trim_start_matches('/');
    match get(file) {
        Some(bytes) => Response::new()
            .header("content-type", content_type(file))
            .header("cache-control", "public, max-age=86400")
            .send(bytes.to_vec()),
        None => Response::new().status(404),
    }
}
