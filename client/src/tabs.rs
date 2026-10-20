//! Un seul onglet actif, comme WhatsApp Web.
//!
//! Chaque onglet garde en mémoire sa copie de la base (Memoire) : deux
//! onglets ouverts en même temps finiraient par écraser l'un les écritures
//! de l'autre dans IndexedDB. Le dernier onglet ouvert prend la main : il
//! l'annonce (`claim`) ; l'ancien envoie ses dernières écritures, se coupe
//! du relais, le confirme (`released`) puis propose « Utiliser ici ».

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use web_sys::{BroadcastChannel, MessageEvent};

use crate::dom::{sleep, spawn};
use crate::{net, store, ui};

/// Le canal entre onglets, et son écouteur (gardés le temps de la page).
type Channel = (BroadcastChannel, Closure<dyn FnMut(MessageEvent)>);

thread_local! {
    static CHANNEL: RefCell<Option<Channel>> = const { RefCell::new(None) };
}

/// Prend la main : demande aux autres onglets de Talka de se retirer et
/// attend qu'ils aient fini d'écrire (une seconde au plus).
pub async fn claim() {
    let Ok(channel) = BroadcastChannel::new("talka-onglets") else {
        return; // navigateur ancien : on fait comme avant
    };
    let me = crate::crypto::random_id(8);
    let released = Rc::new(Cell::new(false));
    let (r, ch, mine) = (released.clone(), channel.clone(), me.clone());
    let on_message = Closure::<dyn FnMut(MessageEvent)>::new(move |e: MessageEvent| {
        let Some(text) = e.data().as_string() else {
            return;
        };
        let (kind, from) = text.split_once(':').unwrap_or((text.as_str(), ""));
        if from == mine {
            return;
        }
        match kind {
            "claim" => {
                let ch = ch.clone();
                spawn(async move {
                    store::release().await;
                    net::stop();
                    let _ = ch.post_message(&JsValue::from_str("released:"));
                    ui::inactive();
                });
            }
            "released" => r.set(true),
            _ => {}
        }
    });
    channel.set_onmessage(Some(on_message.as_ref().unchecked_ref()));
    let _ = channel.post_message(&JsValue::from_str(&format!("claim:{me}")));
    CHANNEL.with(|c| *c.borrow_mut() = Some((channel, on_message)));
    // Personne ne répond s'il n'y a pas d'autre onglet : on n'attend pas plus.
    for _ in 0..10 {
        if released.get() {
            break;
        }
        sleep(100).await;
    }
}
