//! # Talka 💬
//!
//! Une messagerie façon WhatsApp, écrite avec [Paquets](https://github.com/maxlestage/Paquets) :
//!
//! - un **code** (`K7Q2M-X9PRT`) remplace le numéro de téléphone : c'est
//!   l'empreinte des clés de l'appareil (voir [`code`]) ;
//! - **aucune base de données** : discussions, contacts, photos, statuts et
//!   clés sont gardés dans le navigateur (IndexedDB) ; le serveur ne fait que
//!   relayer des jetons chiffrés de bout en bout (voir [`relay`]) ;
//! - le site de présentation (`/`) et l'application (`/app`) sont des pages
//!   Paquets ; l'application elle-même est écrite en Rust, compilée en
//!   WebAssembly (`client/`) ;
//! - `talka export <dossier>` écrit la version GitHub Pages (voir [`export`]).

pub mod assets;
pub mod code;
pub mod export;
pub mod kv;
pub mod push;
pub mod relay;
pub mod serverkey;
pub mod site;

use paquets::prelude::*;
use paquets::securite::prelude::*;
use paquets::vitesse::{Request, Response};

pub use relay::Relay;

/// Caméra et micro (appels, vocaux) pour la page elle-même, rien d'autre.
pub const PERMISSIONS_POLICY: &str = "accelerometer=(), camera=(self), display-capture=(self), \
geolocation=(), gyroscope=(), magnetometer=(), microphone=(self), midi=(), payment=(), \
usb=(), xr-spatial-tracking=()";

/// Où sont servies les pages, et où est le relais.
#[derive(Clone, Debug, Default)]
pub struct Site {
    /// Le chemin du site : vide sur le serveur, `/Talka` sur GitHub Pages.
    pub base: String,
    /// Le relais, s'il est ailleurs (GitHub Pages) : `https://talka-maxlestage-e497dd868a8c.herokuapp.com`.
    pub api: String,
    /// L'export statique (GitHub Pages) : pas de serveur derrière les pages.
    pub export: bool,
}

impl Site {
    /// Le site servi par le serveur lui-même.
    pub fn server() -> Site {
        Site::default()
    }

    /// Une adresse du site : `url("/app/")` → `/Talka/app/`.
    pub fn url(&self, path: &str) -> String {
        format!("{}{path}", self.base)
    }
}

/// Les sites autorisés à utiliser ce relais depuis un autre domaine (la
/// version GitHub Pages) : `TALKA_ALLOWED_ORIGINS`, séparés par des virgules.
pub fn allowed_origins() -> Vec<String> {
    std::env::var("TALKA_ALLOWED_ORIGINS")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| "https://maxlestage.github.io".to_owned())
        .split(',')
        .map(|o| o.trim().trim_end_matches('/').to_owned())
        .filter(|o| o.starts_with("https://") || o.starts_with("http://localhost"))
        .collect()
}

/// Derrière le proxy d'un hébergeur (Heroku, Render, Fly.io), ou si
/// `TALKA_BEHIND_PROXY` est défini.
pub fn behind_proxy() -> bool {
    ["DYNO", "RENDER", "FLY_APP_NAME", "TALKA_BEHIND_PROXY"]
        .iter()
        .any(|v| std::env::var_os(v).is_some())
}

/// Le bouclier de Paquets, réglé pour une messagerie : HTTPS imposé derrière
/// un proxy, caméra et micro permis à la page, une limite de débit assez
/// large pour charger l'application d'un coup, et la version GitHub Pages
/// autorisée (CORS, CSRF).
pub fn shield() -> Shield {
    let mut cors = Cors::new()
        .allow_headers(["content-type"])
        .max_age(std::time::Duration::from_secs(3600));
    let mut csrf = Csrf::new();
    for origin in allowed_origins() {
        cors = cors.allow_origin(&origin);
        csrf = csrf.trusted_origin(&origin);
    }
    let mut shield = paquets::shield();
    if behind_proxy() {
        shield = shield.client_ip(ClientIpSource::XForwardedFor { trusted_hops: 0 });
    }
    let mut shield = shield
        .guard(cors)
        .guard(csrf)
        .guard(RateLimit::new(Quota::per_second(30).burst(200)))
        .guard(SecurityHeaders::new().permissions_policy(PERMISSIONS_POLICY));
    if behind_proxy() {
        shield = shield.guard(HttpsRedirect::new());
    }
    shield.build()
}

/// La politique de sécurité d'une page, élargie à ce dont l'application a besoin :
/// le relais et sa WebSocket (`connect-src`, ici ou ailleurs), les photos et
/// vocaux déchiffrés (`blob:` et `data:`), les flux des appels.
pub fn policy(csp: &str, site: &Site, host: Option<&str>) -> String {
    let mut connect = vec!["'self'".to_owned()];
    if let Some(host) = host {
        connect.push(format!("wss://{host}"));
        connect.push(format!("ws://{host}"));
    }
    if let Some(rest) = site.api.strip_prefix("https://") {
        connect.push(site.api.clone());
        connect.push(format!("wss://{rest}"));
    } else if let Some(rest) = site.api.strip_prefix("http://") {
        connect.push(site.api.clone());
        connect.push(format!("ws://{rest}"));
    }
    if site.export && site.api.is_empty() {
        // Le relais sera choisi au premier lancement.
        connect.push("https:".into());
        connect.push("wss:".into());
    }
    csp.replace(
        "connect-src 'self'",
        &format!("connect-src {}", connect.join(" ")),
    )
    .replace(
        "img-src 'self' data: https:",
        // Aucune image distante : un avatar ou une photo pointant ailleurs
        // trahirait l'adresse IP de qui la regarde.
        "img-src 'self' data: blob:; media-src 'self' data: blob:; worker-src 'self'",
    )
}

/// Une page, avec sa politique de sécurité élargie.
pub fn respond(page: Page, site: &Site, req: &Request) -> Response {
    with_policy(page.into_response(), site, req)
}

/// La politique de sécurité de `res`, élargie pour l'hôte de la requête.
fn with_policy(res: Response, site: &Site, req: &Request) -> Response {
    let host = req
        .host()
        .filter(|h| {
            h.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-.:[]".contains(&b))
        })
        .map(str::to_owned);
    let Some(csp) = res.get_header("content-security-policy") else {
        return res;
    };
    let csp = policy(csp, site, host.as_deref());
    res.header("content-security-policy", csp)
}

/// Les serveurs STUN/TURN des appels : `TALKA_ICE_SERVERS` (JSON
/// `RTCIceServer[]`), sinon un serveur STUN public.
fn ice_servers() -> serde_json::Value {
    std::env::var("TALKA_ICE_SERVERS")
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_else(|| {
            json!([{ "urls": ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] }])
        })
}

/// Le QR code d'un lien d'invitation, en SVG.
fn qr_svg(link: &str) -> Option<String> {
    use qrcode::render::svg;
    let code = qrcode::QrCode::with_error_correction_level(link, qrcode::EcLevel::M).ok()?;
    Some(
        code.render::<svg::Color<'_>>()
            .min_dimensions(240, 240)
            .quiet_zone(true)
            .dark_color(svg::Color("#0b141a"))
            .light_color(svg::Color("#ffffff"))
            .build(),
    )
}

/// L'application complète : le site, l'application, le relais.
pub fn app() -> App {
    let mut app = App::new();
    if behind_proxy() {
        app.trust_proxy(1);
    }
    app.security(shield());
    // Les pages (l'accueil : 23 Ko), le JSON et les QR codes, compressés
    // selon `Accept-Encoding` ; ce qui l'est déjà (les fichiers du
    // navigateur, compressés à la compilation) passe tel quel.
    app.middleware(paquets::vitesse::middleware::compress());
    // Avec son double dans le magasin clé-valeur de Nuage DB, s'il est
    // branché (`TALKA_KV_URL`, voir `kv`).
    let relay = Relay::from_env();
    app.state(relay);

    // Le relais : la seule chose que fait le serveur.
    app.ws("/ws", |req, socket| async move {
        // L'adresse du client (vue à travers le proxy par le bouclier), un
        // /64 en IPv6 : on en limite les connexions.
        let ip = req
            .extensions()
            .get::<paquets::securite::ClientIp>()
            .map(|c| c.0)
            .or_else(|| req.remote_addr().map(|a| a.ip()))
            .map(|ip| paquets::securite::ip::mask(ip, 64));
        req.state::<Relay>().clone().serve(socket, ip).await;
    });

    // Les pages.
    pages(&mut app, &Site::server());
    // Un lien d'invitation : `/c/K7Q2MX9PRT` ouvre l'application sur ce contact.
    app.get("/c/:code", |req: Request| async move {
        let target = match req.param("code").and_then(code::normalize) {
            Some(code) => format!("/app#add={code}"),
            None => "/app".to_owned(),
        };
        Response::new()
            .status(302)
            .header("location", target)
            .header("cache-control", "no-store")
    });
    // Avec l'empreinte des clés : `/c/<empreinte>/<code>`.
    app.get("/c/:fp/:code", |req: Request| async move {
        let fp = req.param("fp").unwrap_or("");
        let valid = fp.len() == 16
            && fp
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
        let target = match req.param("code").and_then(code::normalize) {
            Some(code) if valid => format!("/app#k={fp}&add={code}"),
            Some(code) => format!("/app#add={code}"),
            None => "/app".to_owned(),
        };
        Response::new()
            .status(302)
            .header("location", target)
            .header("cache-control", "no-store")
    });

    // Les fichiers du navigateur.
    files(&mut app);

    // Une petite API, sans données personnelles.
    app.get("/api/ice", |_| async {
        Response::new()
            .header("cache-control", "no-store")
            .json(json!({ "iceServers": ice_servers() }))
    });
    app.get("/api/stats", |req: Request| async move {
        let relay = req.state::<Relay>();
        Response::new()
            .header("cache-control", "no-store")
            .json(json!({
                "online": relay.online_count(),
                "waiting": relay.waiting_count(),
                // Le double des boîtes dans Nuage DB : `ok`, `retard`, ou `off`.
                "kv": relay.kv_status().as_str(),
                "version": assets::version(),
            }))
    });
    app.get("/api/qr/:code", |req: Request| async move {
        let Some(code) = req.param("code").and_then(code::normalize) else {
            return Response::new().status(404).text("code invalide");
        };
        let host = req.host().unwrap_or("localhost");
        let scheme = if behind_proxy() || req.header("x-forwarded-proto") == Some("https") {
            "https"
        } else {
            "http"
        };
        match qr_svg(&format!("{scheme}://{host}/c/{code}")) {
            Some(svg) => Response::new()
                .header("content-type", "image/svg+xml")
                .header("cache-control", "public, max-age=86400")
                .send(svg),
            None => Response::new().status(500),
        }
    });
    app.health("/health");

    app.pwa(
        Pwa::new("Talka")
            .short_name("Talka")
            .description("Une messagerie chiffrée où un code remplace le numéro de téléphone.")
            .start_url("/app")
            .scope("/")
            .display("standalone")
            .lang("fr")
            .theme_color("#0e6b63")
            .background_color("#0b141a")
            .icon("/icons/icon-192.png", "192x192", "image/png")
            .icon("/icons/icon-512.png", "512x512", "image/png")
            .maskable_icon("/icons/icon-maskable-512.png", "512x512", "image/png"),
    );
    app
}

/// Les pages, servies pour `site` (le serveur, ou l'export statique).
pub fn pages(app: &mut App, site: &Site) {
    let s = site.clone();
    let kept = Kept::default();
    app.get("/", move |req: Request| {
        let (s, kept) = (s.clone(), kept.clone());
        async move { serve_page(&kept, || site::home(&s), &s, &req).await }
    });
    // `/app/` et `/confidentialite/` y mènent aussi (Vitesse ignore la barre finale).
    let s = site.clone();
    let kept = Kept::default();
    app.get("/confidentialite", move |req: Request| {
        let (s, kept) = (s.clone(), kept.clone());
        async move { serve_page(&kept, || site::privacy(&s), &s, &req).await }
    });
    let s = site.clone();
    let kept = Kept::default();
    app.get("/app", move |req: Request| {
        let (s, kept) = (s.clone(), kept.clone());
        async move { serve_page(&kept, || site::app_shell(&s), &s, &req).await }
    });
    let s = site.clone();
    let kept = Kept::default();
    app.fallback(move |req: Request| {
        let (s, kept) = (s.clone(), kept.clone());
        async move { serve_page(&kept, || site::not_found(&s), &s, &req).await }
    });
}

/// Une page faite une fois, et compressée une fois au mieux (Brotli 11,
/// gzip 9) : elle ne dépend que du site, fixé au démarrage. Faite et
/// compressée (Brotli 5) à chaque visite, l'accueil coûtait 1,1 ms et
/// pesait 6,9 Ko.
struct Built {
    status: u16,
    headers: Vec<(String, String)>,
    plain: paquets::vitesse::Bytes,
    br: paquets::vitesse::Bytes,
    gz: paquets::vitesse::Bytes,
}

type Kept = std::sync::Arc<tokio::sync::OnceCell<Built>>;

impl Built {
    async fn of(res: Response) -> Built {
        let status = res.status_code().as_u16();
        let headers = res
            .headers()
            .iter()
            .filter(|(name, _)| *name != paquets::vitesse::header::CONTENT_LENGTH)
            .filter_map(|(name, value)| {
                Some((name.as_str().to_owned(), value.to_str().ok()?.to_owned()))
            })
            .collect();
        let plain = res.into_body().to_bytes().await.unwrap_or_default();
        let bytes = plain.clone();
        let (br, gz) = tokio::task::spawn_blocking(move || compressed(&bytes))
            .await
            .unwrap_or_default();
        Built {
            status,
            headers,
            plain,
            br: br.into(),
            gz: gz.into(),
        }
    }

    /// La page, dans le codage que le navigateur accepte.
    fn response(&self, accept_encoding: &str) -> Response {
        let mut res = Response::new().status(self.status);
        for (name, value) in &self.headers {
            res = res.append_header(name.as_str(), value.as_str());
        }
        res = res.header("vary", "accept-encoding");
        let coded = [(&self.br, "br"), (&self.gz, "gzip")]
            .into_iter()
            .find(|(bytes, coding)| !bytes.is_empty() && assets::accepts(accept_encoding, coding));
        match coded {
            Some((bytes, coding)) => res.header("content-encoding", coding).send(bytes.clone()),
            None => res.send(self.plain.clone()),
        }
    }
}

/// Brotli 11 et gzip 9 (vides si ça ne gagne rien).
fn compressed(bytes: &[u8]) -> (Vec<u8>, Vec<u8>) {
    use std::io::Write;
    let mut br = Vec::new();
    {
        let mut writer = brotli::CompressorWriter::new(&mut br, 4096, 11, 22);
        if writer.write_all(bytes).is_err() {
            return Default::default();
        }
    }
    let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::best());
    let gz = match gz.write_all(bytes).and_then(|()| gz.finish()) {
        Ok(gz) => gz,
        Err(_) => return Default::default(),
    };
    let worth = |c: Vec<u8>| if c.len() < bytes.len() { c } else { Vec::new() };
    (worth(br), worth(gz))
}

/// Une page du site, faite et compressée à sa première visite.
async fn serve_page(
    kept: &Kept,
    page: impl FnOnce() -> Page,
    site: &Site,
    req: &Request,
) -> Response {
    let built = kept.get_or_init(|| Built::of(page().into_response())).await;
    with_policy(
        built.response(req.header("accept-encoding").unwrap_or("")),
        site,
        req,
    )
}

/// Les fichiers du navigateur.
pub fn files(app: &mut App) {
    app.get("/s/*path", assets::serve);
    app.get("/logo.svg", assets::fixed);
    // Revérifié à chaque ouverture de l'application (`no-cache`) : avec son
    // empreinte, le navigateur reçoit un 304 vide tant qu'il n'a pas changé,
    // au lieu de ses 7 Ko.
    app.get("/talka-sw.js", |req: Request| async move {
        let etag = push::service_worker_etag();
        let unchanged = req
            .header("if-none-match")
            .is_some_and(|tags| tags.split(',').any(|t| t.trim() == etag || t.trim() == "*"));
        let res = Response::new()
            .header("cache-control", "no-cache")
            .header("etag", etag);
        if unchanged {
            res.status(304)
        } else {
            res.header("content-type", "text/javascript; charset=utf-8")
                .send(push::SERVICE_WORKER)
        }
    });
    app.get("/icons/*path", assets::fixed);
}
