//! Range le navigateur compilé (`client/pkg/`, voir `cargo xtask web`) dans le
//! programme : un seul binaire à déployer. Sans lui (un `cargo test` avant la
//! compilation WebAssembly), des fichiers vides prennent sa place.
//!
//! Les fichiers texte du navigateur (le module, son binaire, les styles, le
//! logo) sont aussi compressés ici, une fois pour toutes, en Brotli et en
//! gzip : le serveur les envoie tels quels selon `Accept-Encoding`, sans rien
//! compresser à chaque requête (voir `src/assets.rs`).

use std::io::Write;
use std::path::Path;

const FILES: [&str; 2] = ["talka_web.js", "talka_web_bg.wasm"];
const WEB: [&str; 3] = ["app.css", "site.css", "logo.svg"];

fn main() {
    let out = std::env::var("OUT_DIR").expect("OUT_DIR");
    let out = Path::new(&out);
    for name in FILES {
        let source = Path::new("client/pkg").join(name);
        println!("cargo:rerun-if-changed={}", source.display());
        let target = out.join(name);
        if source.exists() {
            std::fs::copy(&source, &target).expect("copie du navigateur");
        } else {
            println!(
                "cargo:warning=client/pkg/{name} manque : lancez `cargo xtask web` pour compiler le navigateur"
            );
            std::fs::write(&target, b"").expect("fichier vide");
        }
        compress(&target, out, name);
    }
    for name in WEB {
        let source = Path::new("web").join(name);
        println!("cargo:rerun-if-changed={}", source.display());
        compress(&source, out, name);
    }
}

/// `<nom>.br` et `<nom>.gz` dans `out` ; vides pour un fichier vide.
fn compress(source: &Path, out: &Path, name: &str) {
    let bytes = std::fs::read(source).expect("fichier du navigateur");
    let (mut br, mut gz) = (Vec::new(), Vec::new());
    if !bytes.is_empty() {
        let params = brotli::enc::BrotliEncoderParams {
            quality: 11,
            lgwin: 24,
            ..Default::default()
        };
        brotli::BrotliCompress(&mut &bytes[..], &mut br, &params).expect("Brotli");
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
        enc.write_all(&bytes).expect("gzip");
        gz = enc.finish().expect("gzip");
    }
    std::fs::write(out.join(format!("{name}.br")), br).expect("écriture");
    std::fs::write(out.join(format!("{name}.gz")), gz).expect("écriture");
}
