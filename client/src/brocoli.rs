//! L'œuf de Pâques 🥦 : un message qui parle de brocoli fait pleuvoir des
//! brocolis sur la conversation. Rien ne voyage en plus : chaque appareil
//! regarde le texte qu'il a déjà déchiffré.

use crate::dom::*;
use crate::model::fold;

const COUNT: usize = 36;

/// Le message appelle-t-il les brocolis ?
pub fn summons(text: &str) -> bool {
    text.contains('🥦') || fold(text).contains("brocoli")
}

/// La pluie, si personne ne l'a demandée moins vive.
pub fn rain() {
    if matches_media("(prefers-reduced-motion: reduce)") || by_id("brocolis").is_some() {
        return;
    }
    let Some(body) = document().body() else {
        return;
    };
    let host = h("div#brocolis").attr("aria-hidden", "true").el();
    for _ in 0..COUNT {
        let (left, delay, dur, size, turn) = (
            js_sys::Math::random() * 100.0,
            js_sys::Math::random() * 1.2,
            2.2 + js_sys::Math::random() * 1.6,
            22.0 + js_sys::Math::random() * 22.0,
            (js_sys::Math::random() - 0.5) * 720.0,
        );
        append(
            &host,
            h("span.brocoli")
                .text("🥦")
                .style("left", format!("{left:.1}%"))
                .style("font-size", format!("{size:.0}px"))
                .style("animation-delay", format!("{delay:.2}s"))
                .style("animation-duration", format!("{dur:.2}s"))
                .style("--turn", format!("{turn:.0}deg"))
                .into(),
        );
    }
    let _ = body.append_child(&host);
    timer(5000, move || host.remove());
    toast("🥦 Chiffré au brocoli (et en AES-256-GCM, quand même)");
}
