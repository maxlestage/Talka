//! Range le navigateur compilé (`client/pkg/`, voir `cargo xtask web`) dans le
//! programme : un seul binaire à déployer. Sans lui (un `cargo test` avant la
//! compilation WebAssembly), des fichiers vides prennent sa place.

use std::path::Path;

const FILES: [&str; 2] = ["talka_web.js", "talka_web_bg.wasm"];

fn main() {
    let out = std::env::var("OUT_DIR").expect("OUT_DIR");
    for name in FILES {
        let source = Path::new("client/pkg").join(name);
        println!("cargo:rerun-if-changed={}", source.display());
        let target = Path::new(&out).join(name);
        if source.exists() {
            std::fs::copy(&source, &target).expect("copie du navigateur");
        } else {
            println!(
                "cargo:warning=client/pkg/{name} manque : lancez `cargo xtask web` pour compiler le navigateur"
            );
            std::fs::write(&target, b"").expect("fichier vide");
        }
    }
}
