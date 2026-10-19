//! Les tâches de Talka, écrites en Rust.
//!
//! ```sh
//! rustup target add wasm32-unknown-unknown
//! cargo xtask web             # client/ → client/pkg/ (WebAssembly)
//! cargo xtask pages-enabled   # GitHub Actions : GitHub Pages est-il activé ?
//! cargo xtask api-url         # GitHub Actions : l'adresse du relais
//! cargo xtask heroku          # GitHub Actions : le relais sur Heroku
//! ```
//!
//! `web` compile la crate `talka-web` pour `wasm32-unknown-unknown`, puis
//! génère avec la bibliothèque de wasm-bindgen la colle qui permet au module
//! de parler au navigateur. Cette colle démarre le module d'elle-même : la
//! page n'a qu'à la charger, sans une ligne de JavaScript écrite à la main.

use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

use serde_json::Value;

mod heroku;

const USAGE: &str = "usage : cargo xtask web | pages-enabled | api-url | heroku";

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.first().map(String::as_str) {
        Some("web") => web(),
        Some("pages-enabled") => pages_enabled(),
        Some("api-url") => api_url(),
        Some("heroku") => heroku::deploy(),
        _ => Err(USAGE.to_owned()),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("xtask : {e}");
            ExitCode::FAILURE
        }
    }
}

/// Ajouté à la fin de la colle générée.
const BOOT: &str = "
// Démarrage du module, ajouté par `cargo xtask web`.
__wbg_init({ module_or_path: new URL(\"talka_web_bg.wasm\" + new URL(import.meta.url).search, import.meta.url) });
";

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .expect("racine du dépôt")
        .to_path_buf()
}

fn web() -> Result<(), String> {
    let root = root();
    let cargo = std::env::var("CARGO").unwrap_or_else(|_| "cargo".into());
    let status = Command::new(cargo)
        .current_dir(&root)
        .args([
            "build",
            "--package",
            "talka-web",
            "--lib",
            "--target",
            "wasm32-unknown-unknown",
            "--profile",
            "wasm-release",
        ])
        .status()
        .map_err(|e| format!("cargo : {e}"))?;
    if !status.success() {
        return Err("la compilation du navigateur a échoué".into());
    }
    // `CARGO_TARGET_DIR` s'il est fixé (le buildpack Heroku compile dans son
    // cache), sinon `target/`.
    let target = std::env::var_os("CARGO_TARGET_DIR")
        .map(|dir| root.join(dir))
        .unwrap_or_else(|| root.join("target"));
    let wasm = target.join("wasm32-unknown-unknown/wasm-release/talka_web.wasm");
    let out = root.join("client/pkg");
    std::fs::create_dir_all(&out).map_err(|e| e.to_string())?;
    let mut bindgen = wasm_bindgen_cli_support::Bindgen::new();
    bindgen
        .input_path(&wasm)
        .web(true)
        .map_err(|e| e.to_string())?
        .typescript(false);
    bindgen.generate(&out).map_err(|e| e.to_string())?;

    // La colle démarre le module elle-même : la page n'a besoin d'aucun
    // script en ligne.
    let glue = out.join("talka_web.js");
    let mut js = std::fs::read_to_string(&glue).map_err(|e| e.to_string())?;
    js.push_str(BOOT);
    std::fs::write(&glue, js).map_err(|e| e.to_string())?;
    for name in ["talka_web.js", "talka_web_bg.wasm"] {
        let size = std::fs::metadata(out.join(name))
            .map_err(|e| e.to_string())?
            .len();
        println!("client/pkg/{name} : {} Ko", size / 1024);
    }
    Ok(())
}

fn env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.trim().is_empty())
}

/// Ajoute une ligne à un fichier de GitHub Actions (`GITHUB_OUTPUT`…).
fn append(var: &str, line: &str) {
    use std::io::Write;
    if let Some(path) = env(var)
        && let Ok(mut f) = std::fs::OpenOptions::new()
            .append(true)
            .create(true)
            .open(path)
    {
        let _ = writeln!(f, "{line}");
    }
}

fn warning(title: &str, text: &str) {
    println!("::warning title={title}::{text}");
}

fn get(url: &str, auth: &str, accept: &str) -> Result<(u16, Value), String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .build()
        .into();
    let res = agent
        .get(url)
        .header("Authorization", auth)
        .header("Accept", accept)
        .header("User-Agent", "talka-xtask")
        .call()
        .map_err(|e| e.to_string())?;
    let status = res.status().as_u16();
    let text = res.into_body().read_to_string().unwrap_or_default();
    Ok((status, serde_json::from_str(&text).unwrap_or(Value::Null)))
}

/// GitHub Pages est-il activé, avec GitHub Actions comme source ?
fn pages_enabled() -> Result<(), String> {
    let repo = env("GITHUB_REPOSITORY").ok_or("GITHUB_REPOSITORY manque")?;
    let token = env("GH_TOKEN").ok_or("GH_TOKEN manque")?;
    let (status, body) = get(
        &format!("https://api.github.com/repos/{repo}/pages"),
        &format!("Bearer {token}"),
        "application/vnd.github+json",
    )?;
    let enabled = status == 200 && body["build_type"] == "workflow";
    append("GITHUB_OUTPUT", &format!("enabled={enabled}"));
    if !enabled {
        warning(
            "GitHub Pages est désactivé",
            "Settings → Pages → Source : GitHub Actions, puis relancez ce workflow.",
        );
        append(
            "GITHUB_STEP_SUMMARY",
            "### GitHub Pages n'est pas encore activé\nSettings → Pages → Build and deployment → Source : **GitHub Actions**, puis Actions → Pages → Run workflow.",
        );
    }
    Ok(())
}

/// L'adresse du relais Talka : `TALKA_API_URL`, sinon l'app Heroku
/// (`HEROKU_API_KEY`, et `HEROKU_APP_NAME` ou `talka-<propriétaire>`).
fn api_url() -> Result<(), String> {
    let mut url = env("TALKA_API_URL").unwrap_or_default();
    if url.is_empty()
        && env("HEROKU_API_KEY").is_some()
        && let Some(app) = heroku::app_name()
        && let Ok((200, info)) = heroku::heroku("GET", &format!("/apps/{app}"), None)
    {
        url = info["web_url"].as_str().unwrap_or_default().to_owned();
    }
    let url = url.trim_end_matches('/');
    if url.is_empty() {
        warning(
            "Pas de relais configuré",
            "L'application demandera l'adresse du serveur Talka. Déployez-le (Heroku) ou définissez la variable TALKA_API_URL.",
        );
    }
    append("GITHUB_OUTPUT", &format!("url={url}"));
    println!("Relais : {}", if url.is_empty() { "(aucun)" } else { url });
    Ok(())
}
