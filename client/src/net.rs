//! La connexion au relais : une WebSocket qui se présente (signature du
//! défi), se reconnecte toute seule, et transporte les enveloppes chiffrées.
//!
//! Les réponses du relais (`ack`, `error`, `keys`, `bundle`) réveillent la
//! tâche qui les attend ; les messages reçus et la présence vont au cœur
//! (`core`).

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};

use serde_json::{Value, json};
use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use web_sys::{MessageEvent, WebSocket};

use crate::crypto::Identity;
use crate::dom::{Deferred, Sender, clear_timer, listen_forever, meta, now, timer, window};
use crate::model::Contact;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum State {
    Offline,
    Connecting,
    Online,
}

/// La réponse du relais à un envoi.
pub enum Ack {
    Ok,
    Err(String),
}

type Handler = Closure<dyn FnMut(JsValue)>;

thread_local! {
    static WS: RefCell<Option<WebSocket>> = const { RefCell::new(None) };
    static STATE: Cell<State> = const { Cell::new(State::Offline) };
    static IDENTITY: RefCell<Option<Identity>> = const { RefCell::new(None) };
    static HIDDEN: Cell<bool> = const { Cell::new(false) };
    static RETRY: Cell<u32> = const { Cell::new(0) };
    static TIMER: Cell<i32> = const { Cell::new(0) };
    static SKEW: Cell<f64> = const { Cell::new(0.0) };
    static BOOT: RefCell<String> = const { RefCell::new(String::new()) };
    /// La clé VAPID du relais (`ready.push`) : vide s'il n'envoie pas de notifications.
    static PUSH_KEY: RefCell<String> = const { RefCell::new(String::new()) };
    /// Le relais sait-il livrer en expéditeur scellé (`ready.sealed`) ?
    static SEALED: Cell<bool> = const { Cell::new(false) };
    /// La connexion anonyme (expéditeur scellé), ouverte à la demande.
    static ANON: RefCell<Option<WebSocket>> = const { RefCell::new(None) };
    static ANON_READY: Cell<bool> = const { Cell::new(false) };
    static ANON_WAITING: RefCell<Vec<Sender<bool>>> = const { RefCell::new(Vec::new()) };
    static ANON_PENDING: RefCell<HashMap<String, Sender<Ack>>> = RefCell::new(HashMap::new());
    static ANON_HANDLERS: RefCell<Vec<Handler>> = const { RefCell::new(Vec::new()) };
    static STOPPED: Cell<bool> = const { Cell::new(false) };
    static PENDING: RefCell<HashMap<String, Sender<Ack>>> = RefCell::new(HashMap::new());
    static LOOKUPS: RefCell<HashMap<String, Vec<Sender<Value>>>> = RefCell::new(HashMap::new());
    static BUNDLES: RefCell<HashMap<String, Vec<Sender<Value>>>> = RefCell::new(HashMap::new());
    /// Le relais connaît-il les paquets v3 ? (`None` : pas encore su.)
    static RELAY_V3: Cell<Option<bool>> = const { Cell::new(None) };
    static WATCHED: RefCell<HashSet<String>> = RefCell::new(HashSet::new());
    static HANDLERS: RefCell<Vec<Handler>> = const { RefCell::new(Vec::new()) };
}

pub fn state() -> State {
    STATE.with(Cell::get)
}

/// L'identifiant du démarrage du relais connecté (vide : relais ancien).
pub fn boot() -> String {
    BOOT.with(|b| b.borrow().clone())
}

/// L'heure du relais (les horodatages des messages).
pub fn server_now() -> f64 {
    now() + SKEW.with(Cell::get)
}

fn set_state(s: State) {
    if STATE.with(|c| c.replace(s)) != s {
        crate::core::on_net(s);
    }
}

/// L'adresse de la WebSocket : le relais de la page (`<meta name="talka-api">`,
/// GitHub Pages), celui choisi dans les réglages, ou le site lui-même.
pub fn ws_url() -> String {
    let base = crate::net::api_base();
    let base = if base.is_empty() {
        window().location().origin().unwrap_or_default()
    } else {
        base
    };
    let ws = if let Some(rest) = base.strip_prefix("https://") {
        format!("wss://{rest}")
    } else if let Some(rest) = base.strip_prefix("http://") {
        format!("ws://{rest}")
    } else {
        base
    };
    format!("{ws}/ws")
}

/// L'adresse HTTP du relais (vide : le même site).
pub fn api_base() -> String {
    let saved = crate::core::settings().server;
    let base = if saved.is_empty() {
        meta("talka-api")
    } else {
        saved
    };
    base.trim_end_matches('/').to_owned()
}

pub fn start(identity: Identity, hidden: bool) {
    IDENTITY.with(|i| *i.borrow_mut() = Some(identity));
    HIDDEN.with(|h| h.set(hidden));
    listen_forever(&window(), "online", |_| connect_soon(0));
    listen_forever(&crate::dom::document(), "visibilitychange", |_| {
        if crate::dom::visible() && state() == State::Offline {
            connect_soon(0);
        }
    });
    connect();
}

pub fn stop() {
    STOPPED.with(|s| s.set(true));
    if let Some(ws) = ANON.with(|w| w.borrow_mut().take()) {
        let _ = ws.close();
    }
    if let Some(ws) = WS.with(|w| w.borrow_mut().take()) {
        let _ = ws.close();
    }
}

fn connect_soon(ms: i32) {
    if STOPPED.with(Cell::get) {
        return;
    }
    clear_timer(TIMER.with(Cell::get));
    TIMER.with(|t| t.set(timer(ms, connect)));
}

fn schedule_retry() {
    let n = RETRY.with(|r| {
        let n = r.get();
        r.set((n + 1).min(5));
        n
    });
    let base = (1000.0 * 2f64.powi(n as i32)).min(30_000.0);
    connect_soon((base * (0.7 + js_sys::Math::random() * 0.6)) as i32);
}

fn connect() {
    if STOPPED.with(Cell::get)
        || WS.with(|w| w.borrow().as_ref().is_some_and(|w| w.ready_state() <= 1))
    {
        return;
    }
    let Ok(ws) = WebSocket::new(&ws_url()) else {
        schedule_retry();
        return;
    };
    set_state(State::Connecting);
    let on_message = Closure::<dyn FnMut(JsValue)>::new(|e: JsValue| {
        if let Some(text) = e
            .dyn_ref::<MessageEvent>()
            .and_then(|m| m.data().as_string())
        {
            on_text(&text);
        }
    });
    let me = ws.clone();
    let on_close = Closure::<dyn FnMut(JsValue)>::new(move |_| {
        let current = WS.with(|w| w.borrow().as_ref().map(|w| w == &me).unwrap_or(false));
        if !current {
            return;
        }
        WS.with(|w| *w.borrow_mut() = None);
        set_state(State::Offline);
        for (_, s) in PENDING.with(|p| p.borrow_mut().drain().collect::<Vec<_>>()) {
            s.send(Some(Ack::Err("offline".into())));
        }
        for waiting in [&LOOKUPS, &BUNDLES] {
            for (_, list) in waiting.with(|l| l.borrow_mut().drain().collect::<Vec<_>>()) {
                for s in list {
                    s.send(None);
                }
            }
        }
        schedule_retry();
    });
    ws.set_onmessage(Some(on_message.as_ref().unchecked_ref()));
    ws.set_onclose(Some(on_close.as_ref().unchecked_ref()));
    HANDLERS.with(|h| {
        let mut h = h.borrow_mut();
        // Les écouteurs des connexions précédentes ne servent plus.
        let n = h.len();
        if n > 8 {
            h.drain(..n - 8);
        }
        h.push(on_message);
        h.push(on_close);
    });
    WS.with(|w| *w.borrow_mut() = Some(ws));
}

fn raw(v: &Value) -> bool {
    WS.with(|w| match w.borrow().as_ref() {
        Some(ws) if ws.ready_state() == 1 && state() == State::Online => {
            ws.send_with_str(&v.to_string()).is_ok()
        }
        _ => false,
    })
}

fn on_text(text: &str) {
    let Ok(msg) = serde_json::from_str::<Value>(text) else {
        return;
    };
    match msg["t"].as_str().unwrap_or("") {
        "challenge" => {
            if let Some(t) = msg["time"].as_f64() {
                SKEW.with(|s| s.set(t - now()));
            }
            let nonce = msg["nonce"].as_str().unwrap_or("");
            let hello = IDENTITY.with(|i| {
                i.borrow().as_ref().map(|id| {
                    json!({ "t": "hello", "sign": id.sign, "dh": id.dh, "sig": id.sign_challenge(nonce), "hidden": HIDDEN.with(Cell::get) })
                })
            });
            if let Some(hello) = hello {
                WS.with(|w| {
                    if let Some(ws) = w.borrow().as_ref() {
                        let _ = ws.send_with_str(&hello.to_string());
                    }
                });
            }
        }
        "ready" => {
            RETRY.with(|r| r.set(0));
            let boot = msg["boot"].as_str().unwrap_or("").to_owned();
            BOOT.with(|b| *b.borrow_mut() = boot);
            SEALED.with(|s| s.set(msg["sealed"].as_bool().unwrap_or(false)));
            let push = msg["push"].as_str().unwrap_or("").to_owned();
            PUSH_KEY.with(|k| *k.borrow_mut() = push);
            RELAY_V3.with(|r| r.set(None));
            set_state(State::Online);
            crate::dom::spawn(crate::push::sync());
            let watched: Vec<String> = WATCHED.with(|w| w.borrow().iter().cloned().collect());
            if !watched.is_empty() {
                raw(&json!({ "t": "watch", "codes": watched }));
            }
        }
        "ack" | "error" => {
            let id = msg["id"].as_str().unwrap_or("").to_owned();
            if let Some(s) = PENDING.with(|p| p.borrow_mut().remove(&id)) {
                let ack = if msg["t"] == "ack" {
                    Ack::Ok
                } else {
                    Ack::Err(msg["error"].as_str().unwrap_or("error").to_owned())
                };
                s.send(Some(ack));
            }
        }
        "msg" => crate::core::on_envelope(msg),
        "keys" => {
            let code = msg["code"].as_str().unwrap_or("").to_owned();
            let found = (!msg["unknown"].as_bool().unwrap_or(false)).then_some(msg);
            for s in LOOKUPS
                .with(|l| l.borrow_mut().remove(&code))
                .unwrap_or_default()
            {
                s.send(found.clone());
            }
        }
        "bundle" => {
            let code = msg["code"].as_str().unwrap_or("").to_owned();
            let found = Some(msg);
            for s in BUNDLES
                .with(|l| l.borrow_mut().remove(&code))
                .unwrap_or_default()
            {
                s.send(found.clone());
            }
        }
        // La réponse au `ping` qui suit la publication du paquet : si elle
        // arrive avant `published`, le relais ne connaît pas v3 (les
        // réponses arrivent dans l'ordre des demandes).
        "pong" => {
            if RELAY_V3.with(Cell::get).is_none() {
                RELAY_V3.with(|r| r.set(Some(false)));
                for (_, list) in BUNDLES.with(|l| l.borrow_mut().drain().collect::<Vec<_>>()) {
                    for s in list {
                        s.send(None);
                    }
                }
            }
        }
        "published" => {
            RELAY_V3.with(|r| r.set(Some(true)));
            if msg["ok"] != true {
                crate::dom::warn(&format!(
                    "v3 : paquet de clés refusé par le relais ({})",
                    msg["error"].as_str().unwrap_or("?")
                ));
            }
        }
        "presence" => crate::core::on_presence(&msg),
        _ => {}
    }
}

/// La clé VAPID du relais, vide s'il n'envoie pas de notifications.
pub fn push_key() -> String {
    PUSH_KEY.with(|k| k.borrow().clone())
}

/// Donne (ou retire) au relais l'adresse Web Push de ce navigateur.
/// Les nonces de nos jetons de remise révoqués : le relais refuse ensuite
/// les dépôts anonymes qui les présentent (la liste remplace la précédente).
pub fn revoke(nonces: &[String]) -> bool {
    raw(&json!({ "t": "revoke", "nonces": nonces }))
}

/// Abonne (ou désabonne) ce navigateur : son adresse, et ses clés Web Push
/// (`p256dh`, `auth`, base64url) pour que le relais y chiffre les aperçus.
pub fn push_endpoint(endpoint: &str, p256dh: &str, auth: &str, on: bool) -> bool {
    raw(&json!({ "t": "push", "endpoint": endpoint, "p256dh": p256dh, "auth": auth, "on": on }))
}

/// Envoie une enveloppe ; tenue quand le relais l'a reçue. `wake` : un vrai
/// message, qui réveille un destinataire hors ligne (pas un accusé de
/// lecture, une réaction ou un profil).
pub async fn send(
    id: &str,
    to: &str,
    body: &str,
    live: bool,
    wake: bool,
    apercu: Option<&str>,
) -> Ack {
    let d = Deferred::<Ack>::new();
    let mut msg = json!({ "t": "send", "id": id, "to": to, "body": body, "live": live });
    if wake {
        msg["wake"] = json!(true);
        if let Some(a) = apercu {
            msg["apercu"] = json!(a);
        }
    }
    if !raw(&msg) {
        return Ack::Err("offline".into());
    }
    PENDING.with(|p| p.borrow_mut().insert(id.to_owned(), d.sender()));
    let s = d.sender();
    let key = id.to_owned();
    let t = timer(30_000, move || {
        PENDING.with(|p| p.borrow_mut().remove(&key));
        s.send(Some(Ack::Err("timeout".into())));
    });
    let out = d.wait().await.unwrap_or(Ack::Err("offline".into()));
    clear_timer(t);
    out
}

// ----- L'expéditeur scellé ---------------------------------------------------
//
// Une seconde WebSocket, qui ne se présente pas (`{"t":"anon"}`) : le relais
// ne sait pas qui l'a ouverte. Elle ne sert qu'à déposer des enveloppes
// scellées, avec le jeton de remise du destinataire.

/// Le relais sait-il livrer en expéditeur scellé ?
pub fn sealed_supported() -> bool {
    SEALED.with(Cell::get) && state() == State::Online
}

fn open_anon() {
    let Ok(ws) = WebSocket::new(&ws_url()) else {
        anon_done(false);
        return;
    };
    let me = ws.clone();
    let on_message = Closure::<dyn FnMut(JsValue)>::new(move |e: JsValue| {
        let Some(text) = e
            .dyn_ref::<MessageEvent>()
            .and_then(|m| m.data().as_string())
        else {
            return;
        };
        let Ok(msg) = serde_json::from_str::<Value>(&text) else {
            return;
        };
        match msg["t"].as_str().unwrap_or("") {
            "challenge" => {
                let _ = me.send_with_str(&json!({ "t": "anon" }).to_string());
            }
            "ready" => {
                ANON_READY.with(|r| r.set(true));
                anon_done(true);
            }
            "ack" | "error" => {
                let id = msg["id"].as_str().unwrap_or("").to_owned();
                if let Some(s) = ANON_PENDING.with(|p| p.borrow_mut().remove(&id)) {
                    s.send(Some(if msg["t"] == "ack" {
                        Ack::Ok
                    } else {
                        Ack::Err(msg["error"].as_str().unwrap_or("error").to_owned())
                    }));
                }
            }
            _ => {}
        }
    });
    let me = ws.clone();
    let on_close = Closure::<dyn FnMut(JsValue)>::new(move |_| {
        if !ANON.with(|w| w.borrow().as_ref() == Some(&me)) {
            return;
        }
        ANON.with(|w| *w.borrow_mut() = None);
        ANON_READY.with(|r| r.set(false));
        anon_done(false);
        for (_, s) in ANON_PENDING.with(|p| p.borrow_mut().drain().collect::<Vec<_>>()) {
            s.send(Some(Ack::Err("offline".into())));
        }
    });
    ws.set_onmessage(Some(on_message.as_ref().unchecked_ref()));
    ws.set_onclose(Some(on_close.as_ref().unchecked_ref()));
    ANON_HANDLERS.with(|h| {
        let mut h = h.borrow_mut();
        let n = h.len();
        if n > 4 {
            h.drain(..n - 4);
        }
        h.push(on_message);
        h.push(on_close);
    });
    ANON.with(|w| *w.borrow_mut() = Some(ws));
}

fn anon_done(ok: bool) {
    for s in ANON_WAITING.with(|w| w.borrow_mut().drain(..).collect::<Vec<_>>()) {
        s.send(Some(ok));
    }
}

/// La connexion anonyme, prête (ouverte au besoin, 10 secondes au plus).
async fn anon_ready() -> bool {
    if ANON_READY.with(Cell::get)
        && ANON.with(|w| w.borrow().as_ref().is_some_and(|w| w.ready_state() == 1))
    {
        return true;
    }
    let d = Deferred::<bool>::new();
    ANON_WAITING.with(|w| w.borrow_mut().push(d.sender()));
    if ANON.with(|w| w.borrow().is_none()) {
        open_anon();
    }
    let s = d.sender();
    let t = timer(10_000, move || s.send(Some(false)));
    let ok = d.wait().await.unwrap_or(false);
    clear_timer(t);
    ok
}

/// Dépose une enveloppe scellée pour `peer`, avec son jeton de remise.
pub async fn send_sealed(
    id: &str,
    peer: &Contact,
    sealed: &str,
    live: bool,
    wake: bool,
    apercu: Option<&str>,
) -> Ack {
    if !anon_ready().await {
        return Ack::Err("offline".into());
    }
    let mut msg = json!({
        "t": "send", "id": id, "to": peer.code, "sign": peer.sign, "dh": peer.dh,
        "jeton": peer.jeton, "body": sealed, "live": live,
    });
    if wake {
        msg["wake"] = json!(true);
        if let Some(a) = apercu {
            msg["apercu"] = json!(a);
        }
    }
    let sent = ANON.with(|w| {
        w.borrow()
            .as_ref()
            .is_some_and(|ws| ws.send_with_str(&msg.to_string()).is_ok())
    });
    if !sent {
        return Ack::Err("offline".into());
    }
    let d = Deferred::<Ack>::new();
    ANON_PENDING.with(|p| p.borrow_mut().insert(id.to_owned(), d.sender()));
    let s = d.sender();
    let key = id.to_owned();
    let t = timer(30_000, move || {
        ANON_PENDING.with(|p| p.borrow_mut().remove(&key));
        s.send(Some(Ack::Err("timeout".into())));
    });
    let out = d.wait().await.unwrap_or(Ack::Err("offline".into()));
    clear_timer(t);
    out
}

pub fn got(numbers: &[u64]) {
    if !numbers.is_empty() {
        raw(&json!({ "t": "got", "n": numbers }));
    }
}

/// Les clés publiques d'un code (`None` si le relais ne le connaît pas, ou
/// sans réseau).
pub async fn lookup(code: &str) -> Option<Value> {
    if !raw(&json!({ "t": "lookup", "code": code })) {
        return None;
    }
    let d = Deferred::<Value>::new();
    LOOKUPS.with(|l| {
        l.borrow_mut()
            .entry(code.to_owned())
            .or_default()
            .push(d.sender())
    });
    let s = d.sender();
    let t = timer(15_000, move || s.send(None));
    let out = d.wait().await;
    clear_timer(t);
    out
}

/// Publie le paquet de clés v3 (base64) et sa signature par la clé du code.
pub fn publish_bundle(bundle: &str, sig: &str) -> bool {
    raw(&json!({ "t": "publish-bundle", "bundle": bundle, "sig": sig }))
        && raw(&json!({ "t": "ping" }))
}

/// La réponse du relais à la demande du paquet de clés v3 d'un code
/// (`"none": true` s'il n'en a pas ; `None` : pas de réponse, sans réseau ou
/// avec un relais d'avant v3). Le paquet est à vérifier : `v3::verify_bundle`.
pub async fn fetch_bundle(code: &str) -> Option<Value> {
    if RELAY_V3.with(Cell::get) == Some(false)
        || !raw(&json!({ "t": "fetch-bundle", "code": code }))
    {
        return None;
    }
    let d = Deferred::<Value>::new();
    BUNDLES.with(|l| {
        l.borrow_mut()
            .entry(code.to_owned())
            .or_default()
            .push(d.sender())
    });
    let s = d.sender();
    let t = timer(10_000, move || s.send(None));
    let out = d.wait().await;
    clear_timer(t);
    out
}

pub fn watch(codes: &[String]) {
    let fresh: Vec<String> = WATCHED.with(|w| {
        let mut w = w.borrow_mut();
        codes
            .iter()
            .filter(|c| w.insert((*c).clone()))
            .cloned()
            .collect()
    });
    if !fresh.is_empty() {
        raw(&json!({ "t": "watch", "codes": fresh }));
    }
}

/// Masquer « en ligne » : se reconnecter avec le nouveau réglage.
pub fn set_hidden(hidden: bool) {
    if HIDDEN.with(|h| h.replace(hidden)) != hidden
        && let Some(ws) = WS.with(|w| w.borrow().clone())
    {
        let _ = ws.close();
    }
}

/// Une requête GET au relais, en JSON (`/api/ice`).
pub async fn get_json(path: &str) -> Option<Value> {
    let init = web_sys::RequestInit::new();
    init.set_cache(web_sys::RequestCache::NoStore);
    let url = format!("{}{path}", api_base());
    let res = wasm_bindgen_futures::JsFuture::from(window().fetch_with_str_and_init(&url, &init))
        .await
        .ok()?;
    let res: web_sys::Response = res.unchecked_into();
    if !res.ok() {
        return None;
    }
    let text = wasm_bindgen_futures::JsFuture::from(res.text().ok()?)
        .await
        .ok()?
        .as_string()?;
    serde_json::from_str(&text).ok()
}
