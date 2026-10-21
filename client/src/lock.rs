//! Le code de verrouillage : 6 à 12 chiffres (4 ou 5 pour les codes d'avant)
//! demandés à l'ouverture de Talka (et au retour, après un délai choisi).
//! Gardé haché (Argon2id), jamais en clair. Après 5 essais manqués, il faut
//! attendre de plus en plus longtemps.
//!
//! Il ne fait pas que cacher l'écran : la clé de l'appareil, qui chiffre
//! toute la mémoire de Talka, est chiffrée par lui (Argon2id, voir
//! `store::protect`) : sans le code, les fichiers ne s'ouvrent pas. Une
//! passkey peut le remplacer pour cette clé (`passkey`).

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use wasm_bindgen::JsCast;
use web_sys::{Element, KeyboardEvent};

use std::future::Future;
use std::pin::Pin;

use crate::crypto::{PIN_MAX, PinHash, check_pin, hash_pin, pin_is_old};
use crate::dom::{
    Deferred, document, drop_scope, h, icon, listen, now, replace, scope_into, sleep, toggle_class,
    unique,
};
use crate::store;

pub async fn has_pin() -> bool {
    store::read::<PinHash>("kv", "pin").await.is_some()
}

pub async fn set_pin(pin: Option<&str>) {
    match pin {
        Some(p) => store::write("kv", "pin", &hash_pin(p)).await,
        None => store::remove("kv", "pin").await,
    }
    // Les clés Brocolis suivent le code : scellées par lui, ou en clair sans.
    crate::core::protect_brocolis(pin).await;
    // La clé de l'appareil aussi (sauf si une passkey la protège).
    match (store::lock().await, pin) {
        (store::Lock::Passkey { .. }, _) => {}
        (_, Some(p)) => {
            store::protect(store::NewLock::Code(p)).await;
        }
        (store::Lock::Code, None) => {
            store::protect(store::NewLock::None).await;
        }
        (store::Lock::None, None) => {}
    }
    store::committed().await;
}

/// Le bon code vient d'être tapé : un code d'avant est rehaché (Argon2id),
/// et la clé de l'appareil, si rien ne la protégeait encore, l'est par lui.
async fn upgrade(pin: &str, stored: &PinHash) {
    if pin_is_old(stored) {
        store::write("kv", "pin", &hash_pin(pin)).await;
    }
    if store::lock().await == store::Lock::None {
        store::protect(store::NewLock::Code(pin)).await;
    }
}

/// Le code de verrouillage est-il le bon ? (Vrai s'il n'y en a pas.)
pub async fn check(pin: &str) -> bool {
    verify(pin).await
}

async fn verify(pin: &str) -> bool {
    match store::read::<PinHash>("kv", "pin").await {
        Some(stored) => {
            let ok = check_pin(pin, &stored);
            if ok {
                upgrade(pin, &stored).await;
            }
            ok
        }
        None => true,
    }
}

/// Un pavé numérique. `on_done(code)` est appelé à « OK » (4 à 6 chiffres) ;
/// il renvoie le message à afficher, ou rien pour fermer.
#[derive(Clone)]
pub struct Pad {
    pub el: Element,
    msg: Element,
    zone: String,
}

impl Pad {
    pub fn error(&self, text: &str) {
        self.msg.set_text_content(Some(text));
        toggle_class(&self.msg, "err", true);
        toggle_class(&self.el, "shake", false);
        let _ = self.el.client_width();
        toggle_class(&self.el, "shake", true);
    }

    pub fn info(&self, text: &str) {
        self.msg.set_text_content(Some(text));
        toggle_class(&self.msg, "err", false);
    }

    pub fn destroy(&self) {
        self.el.remove();
        let z = self.zone.clone();
        crate::dom::timer(0, move || drop_scope(&z));
    }
}

pub fn pin_pad(title: &str, subtitle: &str, on_done: impl Fn(String, Pad) + 'static) -> Pad {
    let zone = unique("pin");
    let value = Rc::new(RefCell::new(String::new()));
    let dots = h("div.pin-dots").attr("aria-live", "polite").el();
    let msg = h("p.pin-msg").text(subtitle).el();
    let el = h("div.pin").el();
    let pad = Pad {
        el: el.clone(),
        msg: msg.clone(),
        zone: zone.clone(),
    };
    let draw = {
        let (dots, value) = (dots.clone(), value.clone());
        Rc::new(move || {
            let n = value.borrow().len();
            replace(
                &dots,
                (0..n.max(4))
                    .map(|i| h("span").class_if("on", i < n).el())
                    .collect::<Vec<_>>(),
            );
        })
    };
    let on_done = Rc::new(on_done);
    let press: Rc<dyn Fn(&str)> = {
        let (value, draw, pad, on_done) =
            (value.clone(), draw.clone(), pad.clone(), on_done.clone());
        Rc::new(move |k: &str| {
            match k {
                "del" => {
                    value.borrow_mut().pop();
                }
                "ok" => {
                    if value.borrow().len() >= 4 {
                        let v = value.replace(String::new());
                        draw();
                        on_done(v, pad.clone());
                    }
                    return;
                }
                d => {
                    if value.borrow().len() < PIN_MAX {
                        value.borrow_mut().push_str(d);
                    }
                }
            }
            draw();
        })
    };
    scope_into(&zone, || {
        const KEYS: [&str; 12] = [
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "ok", "0", "del",
        ];
        let grid = h("div.pin-pad").children(KEYS.iter().map(|k| {
            let p = press.clone();
            let label = match *k {
                "del" => "⌫",
                "ok" => "OK",
                d => d,
            };
            h("button.pin-key")
                .class_if("fn", *k == "ok" || *k == "del")
                .attr("type", "button")
                .attr("aria-label", if *k == "del" { "Effacer" } else { label })
                .text(label)
                .click(move || p(k))
        }));
        replace(
            &el,
            vec![
                h("div.pin-lock").child(icon("lock")).el(),
                h("h2").text(title).el(),
                msg.clone(),
                dots.clone(),
                grid.el(),
            ],
        );
        let p = press.clone();
        let host = el.clone();
        listen(&document(), "keydown", move |e| {
            if !host.is_connected() {
                return;
            }
            let Some(k) = e.dyn_ref::<KeyboardEvent>() else {
                return;
            };
            match k.key().as_str() {
                d if d.len() == 1 && d.chars().all(|c| c.is_ascii_digit()) => p(d),
                "Backspace" => p("del"),
                "Enter" => p("ok"),
                _ => {}
            }
        });
    });
    draw();
    pad
}

type Check = Rc<dyn Fn(String) -> Pin<Box<dyn Future<Output = bool>>>>;

/// Au-delà de ce nombre de codes faux, tout est effacé (si choisi).
pub const WIPE_AFTER: u32 = 10;

/// « Effacer après 10 codes faux » : gardé hors de la mémoire chiffrée,
/// pour être lu avant qu'elle s'ouvre.
pub fn wipe_after_fails() -> bool {
    store::local_get("talka.effacer10").as_deref() == Some("1")
}

pub fn set_wipe_after_fails(on: bool) {
    store::local_set("talka.effacer10", if on { "1" } else { "0" });
}

/// L'écran verrouillé : se termine quand le bon code est tapé, et le rend
/// (il ouvre les clés Brocolis).
pub async fn lock_screen() -> String {
    screen(Rc::new(|pin: String| {
        Box::pin(async move { verify(&pin).await })
    }))
    .await
}

/// Talka s'ouvre et sa mémoire est protégée par le code : il ouvre la clé
/// de l'appareil (Argon2id, une demi-seconde environ) avant tout le reste.
pub async fn unlock_screen() -> String {
    screen(Rc::new(|pin: String| {
        Box::pin(async move { store::unlock_code(&pin).await })
    }))
    .await
}

async fn screen(check: Check) -> String {
    let host = h("div.lock-screen").el();
    let _ = document().body().expect("body").append_child(&host);
    let d = Deferred::<String>::new();
    let s = d.sender();
    // Les essais manqués survivent à un rechargement de la page.
    let saved: (u32, f64) = store::local_get("talka.essais")
        .and_then(|v| serde_json::from_str(&v).ok())
        .unwrap_or((0, 0.0));
    let fails = Rc::new(Cell::new(saved.0));
    let until = Rc::new(Cell::new(saved.1));
    let h2 = host.clone();
    let pad = pin_pad(
        "Talka est verrouillé",
        "Entrez votre code",
        move |pin, pad| {
            let (s, fails, until, host, check) = (
                s.clone(),
                fails.clone(),
                until.clone(),
                h2.clone(),
                check.clone(),
            );
            crate::dom::spawn(async move {
                if now() < until.get() {
                    pad.error(&format!(
                        "Patientez {} s",
                        ((until.get() - now()) / 1000.0).ceil()
                    ));
                    return;
                }
                pad.info("Vérification…");
                sleep(30).await;
                if check(pin.clone()).await {
                    store::local_set("talka.essais", "[0,0]");
                    pad.destroy();
                    host.remove();
                    s.send(Some(pin));
                } else {
                    fails.set(fails.get() + 1);
                    // Choisi dans les réglages : au dixième code faux, tout
                    // est effacé (une sauvegarde le rendra).
                    if wipe_after_fails() && fails.get() >= WIPE_AFTER {
                        pad.error("10 codes faux : Talka efface tout.");
                        store::forget_everything().await;
                        let _ = crate::dom::window().location().reload();
                        return;
                    }
                    if fails.get() >= 5 {
                        let wait = (15.0 * 2f64.powi(fails.get() as i32 - 5)).min(300.0);
                        until.set(now() + wait * 1000.0);
                        pad.error("Code incorrect. Trop d'essais : patientez.");
                    } else {
                        pad.error("Code incorrect");
                    }
                    store::local_set(
                        "talka.essais",
                        &format!("[{},{}]", fails.get(), until.get()),
                    );
                }
            });
        },
    );
    let _ = host.append_child(&pad.el);
    let forgot = h("button.link.lock-forgot")
        .attr("type", "button")
        .text("Code oublié ?")
        .click(|| {
            crate::dom::spawn(async {
                if crate::dom::confirm_box(
                    "Code oublié",
                    "Sans lui, la mémoire de Talka sur cet appareil ne s'ouvre plus. Vous pouvez tout effacer, puis restaurer une sauvegarde.",
                    "Tout effacer",
                    true,
                )
                .await
                {
                    store::forget_everything().await;
                    let _ = crate::dom::window().location().reload();
                }
            });
        })
        .el();
    let _ = host.append_child(&forgot);
    d.wait().await.unwrap_or_default()
}
