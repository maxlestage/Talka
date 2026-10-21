//! L'export statique pour **GitHub Pages** : les mêmes pages et fichiers que
//! le serveur, sous le chemin du dépôt (`/Talka`), avec une application qui
//! parle au relais d'un serveur Talka déployé ailleurs (Heroku…), en HTTPS.
//!
//! ```sh
//! BASE_PATH=/Talka TALKA_API_URL=https://talka.herokuapp.com talka export dist
//! ```
//!
//! GitHub Pages n'envoie pas d'en-têtes sur mesure : la politique de sécurité
//! de chaque page est donc écrite dans la page (`<meta http-equiv>`).

use std::path::Path;

use paquets::App;
use paquets::active::pwa::Manifest;

use crate::{Site, assets};

/// Le site pour GitHub Pages, d'après `BASE_PATH` et `TALKA_API_URL`.
pub fn site_from_env() -> Site {
    let clean = |name: &str| {
        std::env::var(name)
            .unwrap_or_default()
            .trim()
            .trim_end_matches('/')
            .to_owned()
    };
    let base = clean("BASE_PATH");
    let base = if base.is_empty() || base.starts_with('/') {
        base
    } else {
        format!("/{base}")
    };
    Site {
        base,
        api: clean("TALKA_API_URL"),
        export: true,
    }
}

/// Le manifeste de l'application installable, sous le chemin du site.
pub fn manifest(site: &Site) -> Manifest {
    Manifest::new("Talka")
        .short_name("Talka")
        .description("Une messagerie chiffrée où un code remplace le numéro de téléphone.")
        .start_url(site.url("/app/"))
        .scope(site.url("/"))
        .display("standalone")
        .lang("fr")
        .theme_color("#0e6b63")
        .background_color("#0b141a")
        .icon(site.url("/icons/icon-192.png"), "192x192", "image/png")
        .icon(site.url("/icons/icon-512.png"), "512x512", "image/png")
        .maskable_icon(
            site.url("/icons/icon-maskable-512.png"),
            "512x512",
            "image/png",
        )
}

/// Écrit le site dans `dir`.
pub async fn run(dir: &Path, site: &Site) -> std::io::Result<()> {
    if !assets::has_client() {
        return Err(std::io::Error::other(
            "le navigateur n'est pas compilé : lancez `cargo xtask web` avant l'export",
        ));
    }
    let mut app = App::new();
    app.security(None);
    app.quiet();
    crate::files(&mut app);
    crate::pages(&mut app, site);
    let client = app.test().await;

    let write = |path: &str, bytes: &[u8]| -> std::io::Result<()> {
        let file = dir.join(path.trim_start_matches('/'));
        if let Some(parent) = file.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(file, bytes)
    };

    for (from, to) in [
        ("/", "index.html"),
        ("/app", "app/index.html"),
        ("/confidentialite", "confidentialite/index.html"),
        ("/404", "404.html"),
    ] {
        let res = client.get(from).await;
        let csp = res
            .header("content-security-policy")
            .unwrap_or_default()
            // Sans effet dans une balise <meta> (et signalé en console).
            .replace("; frame-ancestors 'none'", "");
        let html = res.text().replacen(
            "<head>",
            &format!(
                r#"<head><meta http-equiv="Content-Security-Policy" content="{}"/>"#,
                csp.replace('"', "&quot;")
            ),
            1,
        );
        write(to, html.as_bytes())?;
    }
    for (path, bytes) in assets::FILES {
        write(&assets::url(path), bytes)?;
    }
    for path in [
        "logo.svg",
        "icons/icon-192.png",
        "icons/icon-512.png",
        "icons/icon-maskable-512.png",
        "icons/apple-touch-icon.png",
    ] {
        write(path, assets::get(path).unwrap_or_default())?;
    }
    write("manifest.webmanifest", manifest(site).to_json().as_bytes())?;
    write("talka-sw.js", crate::push::SERVICE_WORKER.as_bytes())?;
    // Les fichiers commençant par « _ » ou « . » passent tels quels.
    write(".nojekyll", b"")?;
    println!(
        "Site exporté dans {} (chemin « {} », relais « {} »).",
        dir.display(),
        if site.base.is_empty() {
            "/"
        } else {
            &site.base
        },
        if site.api.is_empty() {
            "à choisir au premier lancement"
        } else {
            &site.api
        }
    );
    Ok(())
}
