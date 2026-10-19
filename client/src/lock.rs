//! Le code de verrouillage : 4 à 6 chiffres demandés à l'ouverture de Talka
//! (et au retour, après un délai choisi). Gardé haché (PBKDF2), jamais en
//! clair. Après 5 essais manqués, il faut attendre de plus en plus longtemps.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use wasm_bindgen::JsCast;
use web_sys::{Element, KeyboardEvent};

use crate::crypto::{PinHash, check_pin, hash_pin};
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
}

async fn verify(pin: &str) -> bool {
    match store::read::<PinHash>("kv", "pin").await {
        Some(stored) => check_pin(pin, &stored),
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
                    if value.borrow().len() < 6 {
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

/// L'écran verrouillé : se termine quand le bon code est tapé.
pub async fn lock_screen() {
    let host = h("div.lock-screen").el();
    let _ = document().body().expect("body").append_child(&host);
    let d = Deferred::<bool>::new();
    let s = d.sender();
    let fails = Rc::new(Cell::new(0u32));
    let until = Rc::new(Cell::new(0.0f64));
    let h2 = host.clone();
    let pad = pin_pad(
        "Talka est verrouillé",
        "Entrez votre code",
        move |pin, pad| {
            let (s, fails, until, host) = (s.clone(), fails.clone(), until.clone(), h2.clone());
            crate::dom::spawn(async move {
                if now() < until.get() {
                    pad.error(&format!(
                        "Patientez {} s",
                        ((until.get() - now()) / 1000.0).ceil()
                    ));
                    return;
                }
                sleep(10).await;
                if verify(&pin).await {
                    pad.destroy();
                    host.remove();
                    s.send(Some(true));
                } else {
                    fails.set(fails.get() + 1);
                    if fails.get() >= 5 {
                        let wait = (15.0 * 2f64.powi(fails.get() as i32 - 5)).min(300.0);
                        until.set(now() + wait * 1000.0);
                        pad.error("Code incorrect. Trop d'essais : patientez.");
                    } else {
                        pad.error("Code incorrect");
                    }
                }
            });
        },
    );
    let _ = host.append_child(&pad.el);
    d.wait().await;
}
