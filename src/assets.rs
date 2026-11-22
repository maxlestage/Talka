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

/// Les fichiers texte, compressés une fois à la compilation (`build.rs`) :
/// `(chemin, Brotli, gzip)`, vides si le navigateur n'a pas été compilé.
/// Le binaire du navigateur passe ainsi de 2,9 Mo à 0,77 Mo sur le réseau.
const COMPRESSED: &[(&str, &[u8], &[u8])] = &[
    (
        WASM_JS,
        include_bytes!(concat!(env!("OUT_DIR"), "/talka_web.js.br")),
        include_bytes!(concat!(env!("OUT_DIR"), "/talka_web.js.gz")),
    ),
    (
        WASM,
        include_bytes!(concat!(env!("OUT_DIR"), "/talka_web_bg.wasm.br")),
        include_bytes!(concat!(env!("OUT_DIR"), "/talka_web_bg.wasm.gz")),
    ),
    (
        "app.css",
        include_bytes!(concat!(env!("OUT_DIR"), "/app.css.br")),
        include_bytes!(concat!(env!("OUT_DIR"), "/app.css.gz")),
    ),
    (
        "site.css",
        include_bytes!(concat!(env!("OUT_DIR"), "/site.css.br")),
        include_bytes!(concat!(env!("OUT_DIR"), "/site.css.gz")),
    ),
    (
        "logo.svg",
        include_bytes!(concat!(env!("OUT_DIR"), "/logo.svg.br")),
        include_bytes!(concat!(env!("OUT_DIR"), "/logo.svg.gz")),
    ),
];

/// Ce que le navigateur accepte (`Accept-Encoding`), dans l'ordre de nos
/// préférences : Brotli, puis gzip. Un codage noté `q=0` est refusé.
fn accepts(header: &str, coding: &str) -> bool {
    header.split(',').any(|part| {
        let mut it = part.split(';');
        let name = it.next().unwrap_or("").trim();
        let refused = it.any(|p| {
            p.trim()
                .strip_prefix("q=")
                .and_then(|q| q.trim().parse::<f32>().ok())
                .is_some_and(|q| q <= 0.0)
        });
        name.eq_ignore_ascii_case(coding) && !refused
    })
}

/// Le contenu d'un fichier à envoyer, et son `Content-Encoding` : la version
/// compressée que le navigateur accepte, sinon le fichier tel quel.
pub fn negotiate(
    path: &str,
    accept_encoding: &str,
) -> Option<(&'static [u8], Option<&'static str>)> {
    let plain = get(path)?;
    let compressed = COMPRESSED
        .iter()
        .find(|(p, _, _)| *p == path)
        .and_then(|(_, br, gz)| {
            [(*br, "br"), (*gz, "gzip")]
                .into_iter()
                .find(|(bytes, coding)| !bytes.is_empty() && accepts(accept_encoding, coding))
        });
    Some(match compressed {
        Some((bytes, coding)) => (bytes, Some(coding)),
        None => (plain, None),
    })
}

/// Un fichier : compressé si le navigateur le veut, et sans être recopié.
fn respond(req: &Request, path: &str, cache: &str) -> Response {
    let accept = req.header("accept-encoding").unwrap_or("");
    let Some((bytes, coding)) = negotiate(path, accept) else {
        return Response::new().status(404).text("introuvable");
    };
    let mut res = Response::new()
        .header("content-type", content_type(path))
        .header("cache-control", cache);
    if COMPRESSED.iter().any(|(p, _, _)| *p == path) {
        res = res.header("vary", "accept-encoding");
    }
    if let Some(coding) = coding {
        res = res.header("content-encoding", coding);
    }
    res.send(bytes)
}

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
    let cache = if version == self::version() {
        "public, max-age=31536000, immutable"
    } else {
        "no-cache"
    };
    respond(&req, file, cache)
}

/// Les icônes et le logo à leur adresse fixe (manifeste, favicon).
pub async fn fixed(req: Request) -> Response {
    let file = req.path().trim_start_matches('/').to_owned();
    if get(&file).is_none() {
        return Response::new().status(404);
    }
    respond(&req, &file, "public, max-age=86400")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    #[test]
    fn the_browser_gets_the_best_coding_it_accepts() {
        assert!(accepts("gzip, deflate, br, zstd", "br"));
        assert!(accepts("GZIP", "gzip"));
        assert!(accepts("br;q=0.5, gzip;q=1", "br"));
        assert!(!accepts("gzip, br;q=0", "br"));
        assert!(!accepts("gzip;q=0.0", "gzip"));
        assert!(!accepts("", "gzip"));
        assert!(!accepts("x-br", "br"));
        for (path, br, gz) in COMPRESSED {
            let plain = get(path).unwrap();
            let pick = |accept: &str| negotiate(path, accept).unwrap();
            assert_eq!(pick(""), (plain, None));
            assert_eq!(pick("identity"), (plain, None));
            if plain.is_empty() {
                // Le navigateur n'a pas été compilé : rien à compresser.
                assert_eq!(pick("gzip, br"), (plain, None));
                continue;
            }
            assert_eq!(pick("gzip, deflate, br"), (*br, Some("br")));
            assert_eq!(pick("gzip, deflate"), (*gz, Some("gzip")));
            assert_eq!(pick("gzip, br;q=0"), (*gz, Some("gzip")));
        }
        assert_eq!(negotiate("introuvable.css", "br"), None);
        // Une image n'est pas recompressée.
        let png = "icons/icon-192.png";
        assert_eq!(negotiate(png, "br"), Some((get(png).unwrap(), None)));
    }

    /// Ce qui part compressé redonne exactement le fichier, et pèse moins.
    #[test]
    fn compressed_files_are_the_files() {
        for (path, br, gz) in COMPRESSED {
            let plain = get(path).unwrap();
            if plain.is_empty() {
                assert!(br.is_empty() && gz.is_empty());
                continue;
            }
            let mut out = Vec::new();
            brotli::Decompressor::new(*br, 4096)
                .read_to_end(&mut out)
                .unwrap();
            assert_eq!(out, plain, "{path} (Brotli)");
            out.clear();
            flate2::read::GzDecoder::new(*gz)
                .read_to_end(&mut out)
                .unwrap();
            assert_eq!(out, plain, "{path} (gzip)");
            assert!(br.len() < plain.len() && gz.len() < plain.len(), "{path}");
        }
    }
}
