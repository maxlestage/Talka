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
//!   Paquets, l'application elle-même est un module JavaScript (`web/app/`).

pub mod assets;
pub mod code;
pub mod relay;
pub mod site;

use paquets::prelude::*;
use paquets::securite::prelude::*;
use paquets::vitesse::{Request, Response};

pub use relay::Relay;

/// Caméra et micro (appels, vocaux) pour la page elle-même, rien d'autre.
pub const PERMISSIONS_POLICY: &str = "accelerometer=(), camera=(self), display-capture=(self), \
geolocation=(), gyroscope=(), magnetometer=(), microphone=(self), midi=(), payment=(), \
usb=(), xr-spatial-tracking=()";

/// Derrière le proxy d'un hébergeur (Heroku, Render, Fly.io), ou si
/// `TALKA_BEHIND_PROXY` est défini.
pub fn behind_proxy() -> bool {
    ["DYNO", "RENDER", "FLY_APP_NAME", "TALKA_BEHIND_PROXY"]
        .iter()
        .any(|v| std::env::var_os(v).is_some())
}

/// Le bouclier de Paquets, réglé pour une messagerie : HTTPS imposé derrière
/// un proxy, caméra et micro permis à la page, une limite de débit assez
/// large pour charger l'application d'un coup.
pub fn shield() -> Shield {
    let mut shield = paquets::shield();
    if behind_proxy() {
        shield = shield.client_ip(ClientIpSource::XForwardedFor { trusted_hops: 0 });
    }
    let mut shield = shield
        .guard(RateLimit::new(Quota::per_second(30).burst(200)))
        .guard(SecurityHeaders::new().permissions_policy(PERMISSIONS_POLICY));
    if behind_proxy() {
        shield = shield.guard(HttpsRedirect::new());
    }
    shield.build()
}

/// La politique de sécurité d'une page, élargie à ce dont l'application a besoin :
/// la WebSocket du relais (`connect-src`), les photos et vocaux déchiffrés
/// (`blob:` et `data:`), les flux des appels.
pub fn policy(csp: &str, host: Option<&str>) -> String {
    let mut connect = vec!["'self'".to_owned()];
    if let Some(host) = host {
        connect.push(format!("wss://{host}"));
        connect.push(format!("ws://{host}"));
    }
    csp.replace(
        "connect-src 'self'",
        &format!("connect-src {}", connect.join(" ")),
    )
    .replace(
        "img-src 'self' data: https:",
        "img-src 'self' data: blob: https:; media-src 'self' data: blob:; worker-src 'self'",
    )
}

/// Une page, avec sa politique de sécurité élargie.
pub fn respond(page: Page, req: &Request) -> Response {
    let host = req
        .host()
        .filter(|h| {
            h.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"-.:[]".contains(&b))
        })
        .map(str::to_owned);
    let res = page.into_response();
    let Some(csp) = res.get_header("content-security-policy") else {
        return res;
    };
    let csp = policy(csp, host.as_deref());
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
    let relay = Relay::default();
    app.state(relay);

    // Le relais : la seule chose que fait le serveur.
    app.ws("/ws", |req, socket| async move {
        req.state::<Relay>().clone().serve(socket).await;
    });

    // Les pages.
    app.get(
        "/",
        |req: Request| async move { respond(site::home(), &req) },
    );
    app.get("/confidentialite", |req: Request| async move {
        respond(site::privacy(), &req)
    });
    app.get("/app", |req: Request| async move {
        respond(site::app_shell(), &req)
    });
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

    // Les fichiers du navigateur.
    app.get("/s/*path", assets::serve);
    app.get("/logo.svg", assets::fixed);
    app.get("/icons/*path", assets::fixed);

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
            .theme_color("#008069")
            .background_color("#0b141a")
            .icon("/icons/icon-192.png", "192x192", "image/png")
            .icon("/icons/icon-512.png", "512x512", "image/png")
            .maskable_icon("/icons/icon-maskable-512.png", "512x512", "image/png"),
    );
    app.fallback(|req: Request| async move { respond(site::not_found(), &req) });
    app
}
