//! Les fichiers du navigateur, rangés dans le binaire : un seul fichier à
//! déployer, qui marche d'où qu'on le lance.
//!
//! - le navigateur, en Rust compilé en WebAssembly (`client/`, voir
//!   `cargo xtask web`), et la colle générée par wasm-bindgen pour le charger ;
//! - les styles, le logo et les icônes (`web/`).
//!
//! Ils sont servis sous `/s/<version>/…`, où la version est l'empreinte de
//! tous les fichiers : une mise à jour change toutes les adresses, et le
//! module et son binaire restent ensemble dans la même version. Le
//! navigateur peut donc les garder un an.

use std::sync::OnceLock;

use paquets::vitesse::{Request, Response};
use sha2::{Digest, Sha256};

/// Le module du navigateur et son binaire.
pub const WASM_JS: &str = "talka_web.js";
pub const WASM: &str = "talka_web_bg.wasm";

/// `(chemin, contenu)` de chaque fichier servi.
pub const FILES: &[(&str, &[u8])] = &[
    (
        WASM_JS,
        include_bytes!(concat!(env!("OUT_DIR"), "/talka_web.js")),
    ),
    (
        WASM,
        include_bytes!(concat!(env!("OUT_DIR"), "/talka_web_bg.wasm")),
    ),
    ("app.css", include_bytes!("../web/app.css")),
    ("site.css", include_bytes!("../web/site.css")),
    ("logo.svg", include_bytes!("../web/logo.svg")),
    (
        "icons/icon-192.png",
        include_bytes!("../web/icons/icon-192.png"),
    ),
    (
        "icons/icon-512.png",
        include_bytes!("../web/icons/icon-512.png"),
    ),
    (
        "icons/icon-maskable-512.png",
        include_bytes!("../web/icons/icon-maskable-512.png"),
    ),
    (
        "icons/apple-touch-icon.png",
        include_bytes!("../web/icons/apple-touch-icon.png"),
    ),
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

pub fn content_type(path: &str) -> &'static str {
    match path.rsplit('.').next() {
        Some("css") => "text/css; charset=utf-8",
        Some("js") => "text/javascript; charset=utf-8",
        Some("wasm") => "application/wasm",
        Some("svg") => "image/svg+xml",
        Some("png") => "image/png",
        _ => "application/octet-stream",
    }
}

/// Le contenu d'un fichier.
pub fn get(path: &str) -> Option<&'static [u8]> {
    FILES.iter().find(|(p, _)| *p == path).map(|(_, b)| *b)
}

/// Le navigateur a-t-il été compilé (`cargo xtask web`) ?
pub fn has_client() -> bool {
    get(WASM).is_some_and(|b| !b.is_empty())
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
