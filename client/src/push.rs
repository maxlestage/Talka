//! Les notifications quand Talka est fermé (Web Push).
//!
//! Le relais annonce sa clé VAPID (`ready.push`) s'il sait réveiller les
//! navigateurs. Avec « Notifications » activé (et permises), ce navigateur
//! s'abonne auprès de son service de notifications et donne l'adresse au
//! relais, à chaque connexion. Son service worker a sa propre portée
//! (`talka-push/`, sans aucune page) : il ne remplace pas celui qui garde
//! l'application hors ligne. Il tire aussi une clé de notification (P-256,
//! jamais exportée) dont la moitié publique part chez les contacts : leur
//! Talka chiffre pour elle l'aperçu (« Léa : Bonjour »), le relais le
//! transmet sans le lire et le service worker (`talka-sw.js`) l'ouvre ici.
//! Sans aperçu, la notification dit seulement « Nouveau message » ; le
//! message, lui, reste chiffré dans la boîte aux lettres jusqu'à
//! l'ouverture de Talka.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64URL;
use js_sys::{Object, Reflect, Uint8Array};
use wasm_bindgen::prelude::*;

use crate::core;
use crate::dom::{await_promise, call, get, meta, window};
use crate::net;

/// Le relais et ce navigateur savent-ils faire des notifications app fermée ?
pub fn available() -> bool {
    let nav = get(&window(), "navigator");
    !net::push_key().is_empty()
        && !get(&nav, "serviceWorker").is_undefined()
        && !get(&window(), "PushManager").is_undefined()
}

fn granted() -> bool {
    web_sys::Notification::permission() == web_sys::NotificationPermission::Granted
}

/// Abonne ou désabonne ce navigateur selon le réglage « Notifications ».
/// Appelé à chaque connexion au relais et quand le réglage change.
pub async fn sync() {
    if !available() {
        return;
    }
    let want = core::settings().notify && granted();
    let _ = apply(want).await;
}

async fn apply(want: bool) -> Result<(), JsValue> {
    let base = meta("talka-base");
    let scope = format!("{base}/talka-push/");
    let sw = get(&get(&window(), "navigator"), "serviceWorker");
    let reg = if want {
        let opts = Object::new();
        Reflect::set(&opts, &"scope".into(), &scope.clone().into())?;
        await_promise(call(
            &sw,
            "register",
            &[format!("{base}/talka-sw.js").into(), opts.into()],
        ))
        .await?;
        // Le service worker est actif (l'abonnement en a besoin).
        await_promise(Ok(get(&sw, "ready"))).await?
    } else {
        await_promise(call(&sw, "getRegistration", &[scope.into()])).await?
    };
    if reg.is_undefined() || reg.is_null() {
        return Ok(());
    }
    let pm = get(&reg, "pushManager");
    let mut sub = await_promise(call(&pm, "getSubscription", &[])).await?;
    if !want {
        if !sub.is_null() {
            if let Some(endpoint) = get(&sub, "endpoint").as_string() {
                net::push_endpoint(&endpoint, "", "", false);
            }
            let _ = await_promise(call(&sub, "unsubscribe", &[])).await;
        }
        return Ok(());
    }
    let key = B64URL
        .decode(net::push_key())
        .map_err(|_| JsValue::from_str("clé"))?;
    // Abonné avec une autre clé (le relais en a changé) : on se réabonne.
    if !sub.is_null() {
        let current = get(&get(&sub, "options"), "applicationServerKey");
        let same = !current.is_null()
            && !current.is_undefined()
            && Uint8Array::new(&current).to_vec() == key;
        if !same {
            let _ = await_promise(call(&sub, "unsubscribe", &[])).await;
            sub = JsValue::NULL;
        }
    }
    if sub.is_null() {
        let opts = Object::new();
        Reflect::set(&opts, &"userVisibleOnly".into(), &true.into())?;
        Reflect::set(
            &opts,
            &"applicationServerKey".into(),
            &Uint8Array::from(key.as_slice()).into(),
        )?;
        sub = await_promise(call(&pm, "subscribe", &[opts.into()])).await?;
    }
    if let Some(endpoint) = get(&sub, "endpoint").as_string() {
        net::push_endpoint(
            &endpoint,
            &sub_key(&sub, "p256dh"),
            &sub_key(&sub, "auth"),
            true,
        );
    }
    if let Some(nk) = notification_key(&reg).await {
        core::set_nk(nk).await;
    }
    share_contacts(&reg);
    clear_shown(&reg).await;
    Ok(())
}

/// Une clé de l'abonnement (`p256dh` ou `auth`), en base64url.
fn sub_key(sub: &JsValue, name: &str) -> String {
    call(sub, "getKey", &[name.into()])
        .ok()
        .filter(|k| !k.is_null() && !k.is_undefined())
        .map(|k| B64URL.encode(Uint8Array::new(&k).to_vec()))
        .unwrap_or_default()
}

thread_local! {
    static CARNET_PENDING: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// Le carnet du service worker : vos contacts (code, clé `dh`, le nom que
/// vous leur avez donné). Il n'affiche l'aperçu que d'un contact du carnet.
fn share_contacts(reg: &JsValue) {
    let worker = get(reg, "active");
    if worker.is_null() || worker.is_undefined() {
        return;
    }
    let list: Vec<serde_json::Value> = core::contacts()
        .into_iter()
        .filter(|c| c.saved && !c.blocked && !c.dh.is_empty())
        .map(|c| serde_json::json!({ "code": c.code, "dh": c.dh, "name": c.name }))
        .collect();
    let msg = serde_json::json!({ "t": "carnet", "list": list });
    if let Ok(v) = js_sys::JSON::parse(&msg.to_string()) {
        let _ = call(&worker, "postMessage", &[v]);
    }
}

/// Le carnet a changé (un contact ajouté, renommé, bloqué…) : le service
/// worker le reçoit à nouveau, si les notifications sont actives.
pub async fn contacts_changed() {
    // Une fois pour une rafale de changements.
    if CARNET_PENDING.with(|p| p.replace(true)) {
        return;
    }
    crate::dom::sleep(2_000).await;
    CARNET_PENDING.with(|p| p.set(false));
    if !available() || !core::settings().notify || !granted() {
        return;
    }
    let sw = get(&get(&window(), "navigator"), "serviceWorker");
    let scope = format!("{}/talka-push/", meta("talka-base"));
    if let Ok(reg) = await_promise(call(&sw, "getRegistration", &[scope.into()])).await
        && !reg.is_undefined()
        && !reg.is_null()
    {
        share_contacts(&reg);
    }
}

/// La clé publique de notification, demandée au service worker.
async fn notification_key(reg: &JsValue) -> Option<String> {
    let worker = get(reg, "active");
    if worker.is_null() || worker.is_undefined() {
        return None;
    }
    let channel = web_sys::MessageChannel::new().ok()?;
    // Un ancien service worker ne répond pas : on n'attend pas plus de 5 s.
    let reply = js_sys::Promise::new(&mut |ok, _| {
        let late = ok.clone();
        let f = Closure::once_into_js(move |e: JsValue| {
            let _ = ok.call1(&JsValue::NULL, &get(&e, "data"));
        });
        channel.port1().set_onmessage(Some(f.unchecked_ref()));
        let t = Closure::once_into_js(move || {
            let _ = late.call1(&JsValue::NULL, &JsValue::NULL);
        });
        let _ = window()
            .set_timeout_with_callback_and_timeout_and_arguments_0(t.unchecked_ref(), 5_000);
    });
    let msg = Object::new();
    Reflect::set(&msg, &"t".into(), &"nk".into()).ok()?;
    call(
        &worker,
        "postMessage",
        &[msg.into(), js_sys::Array::of1(&channel.port2()).into()],
    )
    .ok()?;
    let data = await_promise(Ok(reply.into())).await.ok()?;
    if data.is_null() {
        return None;
    }
    get(&data, "nk").as_string().filter(|k| !k.is_empty())
}

/// Talka est ouvert : les notifications déjà affichées s'effacent.
async fn clear_shown(reg: &JsValue) {
    if let Ok(list) = await_promise(call(reg, "getNotifications", &[])).await {
        for n in js_sys::Array::from(&list).iter() {
            let _ = call(&n, "close", &[]);
        }
    }
}

/// Désabonne ce navigateur (données effacées, ou autre identité importée) :
/// le relais oublie l'adresse s'il est joignable, et le service de
/// notifications la refuse de toute façon ensuite (le relais l'oublie alors).
pub async fn forget() {
    if !get(&get(&window(), "navigator"), "serviceWorker").is_undefined() {
        let _ = apply(false).await;
    }
}

/// Efface les notifications de réveil (Talka revient à l'écran).
pub async fn clear() {
    let sw = get(&get(&window(), "navigator"), "serviceWorker");
    if sw.is_undefined() {
        return;
    }
    let scope = format!("{}/talka-push/", meta("talka-base"));
    if let Ok(reg) = await_promise(call(&sw, "getRegistration", &[scope.into()])).await
        && !reg.is_undefined()
        && !reg.is_null()
    {
        clear_shown(&reg).await;
    }
}
