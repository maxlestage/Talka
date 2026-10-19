//! Le site de présentation : les sections apparaissent au défilement, et le
//! nombre de personnes connectées s'affiche quand le relais le donne.

use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use web_sys::{Element, IntersectionObserver, IntersectionObserverEntry, IntersectionObserverInit};

use crate::dom::{by_id, document, has, query_all_in, spawn, toggle_class, window};

pub fn start() {
    if let Some(html) = document().document_element() {
        toggle_class(&html, "js", true);
        reveal(&html);
    }
    spawn(live_count());
}

fn reveal(html: &Element) {
    let items = query_all_in(html, ".reveal");
    if !has(&window(), "IntersectionObserver") {
        for el in items {
            toggle_class(&el, "visible", true);
        }
        return;
    }
    let callback = Closure::<dyn FnMut(js_sys::Array, IntersectionObserver)>::new(
        |entries: js_sys::Array, observer: IntersectionObserver| {
            for entry in entries.iter() {
                let entry: IntersectionObserverEntry = entry.unchecked_into();
                if entry.is_intersecting() {
                    let target = entry.target();
                    toggle_class(&target, "visible", true);
                    observer.unobserve(&target);
                }
            }
        },
    );
    let opts = IntersectionObserverInit::new();
    opts.set_root_margin("0px 0px -8% 0px");
    opts.set_threshold(&JsValue::from_f64(0.12));
    if let Ok(observer) =
        IntersectionObserver::new_with_options(callback.as_ref().unchecked_ref(), &opts)
    {
        for el in items {
            observer.observe(&el);
        }
    }
    callback.forget();
}

async fn live_count() {
    let Some(target) = by_id("live-count") else {
        return;
    };
    let Some(stats) = crate::net::get_json("/api/stats").await else {
        return;
    };
    let online = stats["online"].as_u64().unwrap_or(0);
    if online == 0 {
        return;
    }
    target.set_text_content(Some(&if online == 1 {
        "1 personne connectée en ce moment".to_owned()
    } else {
        format!("{online} personnes connectées en ce moment")
    }));
}
