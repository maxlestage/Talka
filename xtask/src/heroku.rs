//! Le relais Talka sur Heroku, par l'API Platform (sans la CLI Heroku, ce qui
//! se configure depuis un téléphone) : l'app est créée si besoin, réglée pour
//! le buildpack emk/rust (`RustConfig` : le navigateur puis le serveur) et
//! pour accepter la version GitHub Pages, puis construite depuis les sources
//! du dépôt.
//!
//! `HEROKU_API_KEY` (secret), `HEROKU_APP_NAME` (facultatif : `talka-<propriétaire>`
//! par défaut), `HEROKU_REGION` (facultatif : `eu`).

use std::process::Command;
use std::time::Duration;

use serde_json::{Value, json};

use crate::{append, env, warning};

fn git(args: &[&str]) -> Result<String, String> {
    let out = Command::new("git")
        .args(args)
        .output()
        .map_err(|e| format!("git : {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "git {} : {}",
            args.join(" "),
            String::from_utf8_lossy(&out.stderr)
        ));
    }
    Ok(String::from_utf8_lossy(&out.stdout).trim().to_owned())
}

fn agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .http_status_as_error(false)
        .timeout_global(Some(Duration::from_secs(120)))
        .build()
        .into()
}

/// Le nom de l'app Heroku : `HEROKU_APP_NAME`, sinon `talka-<propriétaire du dépôt>`.
pub fn app_name() -> Option<String> {
    env("HEROKU_APP_NAME").or_else(|| {
        env("GITHUB_REPOSITORY_OWNER").map(|o| {
            let o: String = o
                .to_lowercase()
                .chars()
                .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
                .collect();
            format!("talka-{o}").chars().take(30).collect()
        })
    })
}

/// Une requête à l'API GitHub, avec le jeton du workflow.
fn github(method: &str, path: &str, body: Option<Value>) -> Result<(u16, Value), String> {
    let token = env("GH_TOKEN")
        .or_else(|| env("GITHUB_TOKEN"))
        .ok_or("GH_TOKEN manque")?;
    let url = format!("https://api.github.com{path}");
    let agent = agent();
    let auth = format!("Bearer {token}");
    let res = match (method, body) {
        ("GET", _) => agent
            .get(&url)
            .header("Authorization", &auth)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "talka-xtask")
            .call(),
        (_, body) => agent
            .post(&url)
            .header("Authorization", &auth)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "talka-xtask")
            .send_json(body.unwrap_or(json!({}))),
    }
    .map_err(|e| e.to_string())?;
    let status = res.status().as_u16();
    let text = res.into_body().read_to_string().unwrap_or_default();
    Ok((status, serde_json::from_str(&text).unwrap_or(Value::Null)))
}

fn repository() -> Result<String, String> {
    env("GITHUB_REPOSITORY").ok_or_else(|| "GITHUB_REPOSITORY manque".into())
}

/// Lance un workflow sur `master`, en réessayant : juste après la fusion,
/// GitHub peut ne pas encore connaître un workflow ajouté par la branche.
fn dispatch(workflow: &str) -> Result<(), String> {
    let repo = repository()?;
    for attempt in 1..=6 {
        let (status, body) = github(
            "POST",
            &format!("/repos/{repo}/actions/workflows/{workflow}/dispatches"),
            Some(json!({ "ref": "master" })),
        )?;
        if status == 204 {
            println!("{workflow} lancé.");
            return Ok(());
        }
        println!("{workflow} : {status} {body} (essai {attempt})");
        std::thread::sleep(Duration::from_secs(10));
    }
    warning(
        "Déploiement",
        &format!("impossible de lancer {workflow} : Actions → {workflow} → Run workflow."),
    );
    Ok(())
}

/// Une requête à l'API Platform de Heroku.
pub fn heroku(method: &str, path: &str, body: Option<Value>) -> Result<(u16, Value), String> {
    let key = env("HEROKU_API_KEY").ok_or("HEROKU_API_KEY manque")?;
    let url = format!("https://api.heroku.com{path}");
    let agent = agent();
    let auth = format!("Bearer {key}");
    let accept = "application/vnd.heroku+json; version=3";
    let res = match method {
        "GET" => agent
            .get(&url)
            .header("Authorization", &auth)
            .header("Accept", accept)
            .call(),
        "PATCH" => agent
            .patch(&url)
            .header("Authorization", &auth)
            .header("Accept", accept)
            .send_json(body.unwrap_or(json!({}))),
        "PUT" => agent
            .put(&url)
            .header("Authorization", &auth)
            .header("Accept", accept)
            .send_json(body.unwrap_or(json!({}))),
        _ => agent
            .post(&url)
            .header("Authorization", &auth)
            .header("Accept", accept)
            .send_json(body.unwrap_or(json!({}))),
    }
    .map_err(|e| e.to_string())?;
    let status = res.status().as_u16();
    let text = res.into_body().read_to_string().unwrap_or_default();
    Ok((status, serde_json::from_str(&text).unwrap_or(Value::Null)))
}

/// La pile Heroku et le buildpack Rust : il compile le serveur avec
/// `cargo build --release`, précédé du navigateur (voir
/// `RustConfig` à la racine).
const STACK: &str = "heroku-24";
const BUILDPACK: &str = "emk/rust";

pub fn deploy() -> Result<(), String> {
    let (Some(_), Some(app)) = (env("HEROKU_API_KEY"), app_name()) else {
        warning(
            "Heroku non configuré",
            "Ajoutez le secret HEROKU_API_KEY (Settings → Secrets and variables → Actions) pour mettre le relais Talka en ligne.",
        );
        append(
            "GITHUB_STEP_SUMMARY",
            "### Heroku n'est pas configuré\nAjoutez le secret **HEROKU_API_KEY**, puis relancez ce workflow.",
        );
        return Ok(());
    };
    let region = env("HEROKU_REGION").unwrap_or_else(|| "eu".into());
    let (status, info) = heroku("GET", &format!("/apps/{app}"), None)?;
    let info = match status {
        200 => info,
        404 => {
            println!("Création de l'app {app} ({region})…");
            let (status, info) = heroku(
                "POST",
                "/apps",
                Some(json!({ "name": app, "region": region, "stack": STACK })),
            )?;
            if status >= 300 {
                return Err(format!("création refusée ({status}) : {info}"));
            }
            info
        }
        401 => return Err("Heroku refuse la clé d'API (HEROKU_API_KEY).".into()),
        403 => {
            return Err(format!(
                "ce compte Heroku ne peut pas utiliser l'app « {app} » (nom déjà pris ?)."
            ));
        }
        other => return Err(format!("réponse inattendue de Heroku ({other}) : {info}")),
    };
    // Une app créée pour l'image Docker (pile « container ») repasse sur la
    // pile des buildpacks ; la nouvelle pile vaut dès la prochaine construction.
    if info["build_stack"]["name"] == "container" {
        let (status, body) = heroku(
            "PATCH",
            &format!("/apps/{app}"),
            Some(json!({ "build_stack": STACK })),
        )?;
        if status >= 300 {
            return Err(format!("changement de pile refusé ({status}) : {body}"));
        }
    }
    let (status, body) = heroku(
        "PUT",
        &format!("/apps/{app}/buildpack-installations"),
        Some(json!({ "updates": [{ "buildpack": BUILDPACK }] })),
    )?;
    if status >= 300 {
        return Err(format!("buildpack refusé ({status}) : {body}"));
    }
    // La version GitHub Pages a le droit d'appeler l'API.
    if let Some(owner) = env("GITHUB_REPOSITORY_OWNER") {
        heroku(
            "PATCH",
            &format!("/apps/{app}/config-vars"),
            Some(
                json!({ "TALKA_ALLOWED_ORIGINS": format!("https://{}.github.io", owner.to_lowercase()) }),
            ),
        )?;
    }
    // Les sources, telles que dans le dépôt.
    let archive = std::env::temp_dir().join("talka-source.tgz");
    git(&[
        "archive",
        "--format=tar.gz",
        "-o",
        archive.to_str().unwrap_or("talka-source.tgz"),
        "HEAD",
    ])?;
    let (_, sources) = heroku("POST", "/sources", None)?;
    let put = sources["source_blob"]["put_url"]
        .as_str()
        .ok_or("put_url")?;
    let get = sources["source_blob"]["get_url"]
        .as_str()
        .ok_or("get_url")?;
    let bytes = std::fs::read(&archive).map_err(|e| e.to_string())?;
    let res = agent()
        .put(put)
        .header("Content-Type", "")
        .send(&bytes[..])
        .map_err(|e| e.to_string())?;
    if !res.status().is_success() {
        return Err(format!("envoi des sources : {}", res.status()));
    }
    println!("Construction sur Heroku (la première prend plusieurs minutes)…");
    let version = env("GITHUB_SHA").unwrap_or_default();
    let (status, build) = heroku(
        "POST",
        &format!("/apps/{app}/builds"),
        Some(json!({ "source_blob": { "url": get, "version": version } })),
    )?;
    if status >= 300 {
        return Err(format!("construction refusée ({status}) : {build}"));
    }
    let id = build["id"].as_str().ok_or("build id")?.to_owned();
    if let Some(stream) = build["output_stream_url"].as_str()
        && let Ok(res) = agent().get(stream).call()
    {
        let mut body = res.into_body();
        let mut reader = body.as_reader();
        let _ = std::io::copy(&mut reader, &mut std::io::stdout());
    }
    let mut result = "pending".to_owned();
    for _ in 0..240 {
        let (_, b) = heroku("GET", &format!("/apps/{app}/builds/{id}"), None)?;
        result = b["status"].as_str().unwrap_or("pending").to_owned();
        if result != "pending" {
            break;
        }
        std::thread::sleep(Duration::from_secs(5));
    }
    if result != "succeeded" {
        return Err(format!(
            "la construction Heroku s'est terminée avec le statut « {result} »"
        ));
    }
    let (_, info) = heroku("GET", &format!("/apps/{app}"), None)?;
    let url = info["web_url"].as_str().unwrap_or_default();
    check_online(url)?;
    append(
        "GITHUB_STEP_SUMMARY",
        &format!("### 🚀 Relais Talka en ligne : [{url}]({url})"),
    );
    println!("En ligne : {url}");
    // Le site GitHub Pages doit connaître l'adresse du relais : on le republie.
    dispatch("pages.yml")
}

/// Le relais répond-il vraiment ? `/health`, puis la page de l'application
/// (le navigateur compilé est rangé dans le binaire). Heroku redémarre l'app
/// après la construction : on lui laisse jusqu'à deux minutes.
fn check_online(url: &str) -> Result<(), String> {
    let base = url.trim_end_matches('/');
    for path in ["/health", "/app"] {
        let mut last = String::new();
        let ok = (0..24).any(|attempt| {
            if attempt > 0 {
                std::thread::sleep(Duration::from_secs(5));
            }
            match agent().get(&format!("{base}{path}")).call() {
                Ok(res) if res.status().is_success() => true,
                Ok(res) => {
                    last = res.status().to_string();
                    false
                }
                Err(e) => {
                    last = e.to_string();
                    false
                }
            }
        });
        if !ok {
            return Err(format!(
                "le relais déployé ne répond pas sur {path} ({last})"
            ));
        }
        println!("{path} : OK");
    }
    Ok(())
}
