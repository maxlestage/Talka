//! Les notifications quand Talka est fermé (Web Push).
//!
//! Le relais annonce sa clé VAPID (`ready.push`) s'il sait réveiller les
//! navigateurs. Avec « Notifications » activé (et permises), ce navigateur
//! s'abonne auprès de son service de notifications et donne l'adresse au
//! relais, à chaque connexion. Son service worker a sa propre portée
//! (`talka-push/`, sans aucune page) : il ne remplace pas celui qui garde
//! l'application hors ligne. Le réveil est vide : le service worker
//! (`talka-sw.js`) affiche seulement « Nouveau message » ; le message reste
//! chiffré dans la boîte aux lettres jusqu'à l'ouverture de Talka.

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
                net::push_endpoint(&endpoint, false);
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
        net::push_endpoint(&endpoint, true);
    }
    clear_shown(&reg).await;
    Ok(())
}

/// Talka est ouvert : les « Nouveau message » déjà affichés s'effacent.
async fn clear_shown(reg: &JsValue) {
    let opts = Object::new();
    let _ = Reflect::set(&opts, &"tag".into(), &"talka".into());
    if let Ok(list) = await_promise(call(reg, "getNotifications", &[opts.into()])).await {
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
