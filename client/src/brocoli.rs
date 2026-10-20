//! Brocolis 🥦 dans l'interface : l'œuf de Pâques (un message qui parle de
//! brocoli fait pleuvoir des brocolis ; rien ne voyage en plus, chaque
//! appareil regarde le texte qu'il a déjà déchiffré), puis les clés de
//! l'app Brocolis et les discussions qu'elles chiffrent.

use crate::dom::*;
use crate::model::fold;

const COUNT: usize = 36;

/// Le message appelle-t-il les brocolis ? Pas un jeton Brocolis resté
/// scellé : il ne dit rien de ce qu'il cache.
pub fn summons(text: &str) -> bool {
    let text = crate::jetons::masked(text).replace("🔒🥦", "");
    text.contains('🥦') || fold(&text).contains("brocoli")
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

// ----- Brocolis 🥦 : les clés et les discussions chiffrées -------------------

use web_sys::Element;

use crate::core;
use crate::model::Chat;

const ABOUT: &str = "Brocolis chiffre vos messages une seconde fois, avec des clés que vous partagez avec vos proches dans l'app Brocolis 🥦. Talka les chiffre déjà de bout en bout ; seuls ceux qui ont la clé ouvrent cette seconde couche, ici ou dans l'app Brocolis (clavier, Partager).";

/// Les clés Brocolis de l'appareil : ajouter, créer, copier, renommer, oublier.
pub fn keys_dialog() {
    let keys = core::brocolis_keys();
    modal(
        "Brocolis 🥦",
        move |m| {
            let list: Vec<Child> = keys
                .iter()
                .map(|k| {
                    let (copy_key, rename, forget) = (k.clone(), k.clone(), k.clone());
                    let (m1, m2) = (m.clone(), m.clone());
                    h("div.list-item")
                        .child(h("span.bro-key").text("🥦"))
                        .child(h("div.grow").text(&k.name))
                        .child(icon_button("copy", "Copier la clé", move || {
                            let key = copy_key.key.clone();
                            spawn(async move { copy(&key, "Clé copiée : à coller dans l'app Brocolis").await })
                        }))
                        .child(icon_button("edit", "Renommer", move || {
                            let (k, m) = (rename.clone(), m1.clone());
                            spawn(async move {
                                if let Some(name) = prompt_box("Renommer la clé", "", &k.name, "text", "Enregistrer", 40).await {
                                    core::rename_brocolis_key(&k.id, &name).await;
                                    m.close();
                                    keys_dialog();
                                }
                            })
                        }))
                        .child(icon_button("trash", "Oublier la clé", move || {
                            let (k, m) = (forget.clone(), m2.clone());
                            spawn(async move {
                                if confirm_box(
                                    "Oublier cette clé ?",
                                    &format!("« {} » ne chiffrera plus rien. Les messages déjà ouverts le restent ; les nouveaux, à cette clé, resteront scellés.", k.name),
                                    "Oublier",
                                    true,
                                )
                                .await
                                {
                                    core::remove_brocolis_key(&k.id).await;
                                    m.close();
                                    keys_dialog();
                                }
                            })
                        }))
                        .into()
                })
                .collect();
            h("div.stack")
                .child(h("p.muted").text(ABOUT))
                .child(if list.is_empty() {
                    h("p.muted.small").text("Aucune clé pour l'instant.")
                } else {
                    h("div.list").children(list)
                })
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Créer une clé", "ghost", |m| {
                    m.close();
                    spawn(create_key());
                }),
                action("Ajouter une clé", "primary", |m| {
                    m.close();
                    spawn(add_key());
                }),
            ],
            ..Default::default()
        },
    );
}

async fn add_key() {
    let Some(pasted) = prompt_box(
        "Ajouter une clé Brocolis",
        "Collez la clé (ou toute l'invitation reçue de l'app Brocolis)",
        "",
        "text",
        "Suivant",
        4000,
    )
    .await
    else {
        return keys_dialog();
    };
    let Some(name) = prompt_box(
        "Le nom de la clé",
        "Par exemple : Maman, Travail",
        "",
        "text",
        "Ajouter",
        40,
    )
    .await
    else {
        return keys_dialog();
    };
    match core::add_brocolis_key(&name, &pasted).await {
        Ok(k) => toast(format!("🥦 Clé « {} » ajoutée", k.name)),
        Err(e) => toast(e),
    }
    keys_dialog();
}

async fn create_key() {
    let Some(name) = prompt_box(
        "Nouvelle clé Brocolis",
        "Son nom (pour qui ?)",
        "",
        "text",
        "Créer",
        40,
    )
    .await
    else {
        return keys_dialog();
    };
    if let Ok(k) = core::add_brocolis_key(&name, &crate::jetons::generate_key()).await {
        copy(
            &k.key,
            "🥦 Clé créée et copiée : partagez-la avec vos proches, par un canal sûr",
        )
        .await;
    }
    keys_dialog();
}

/// Les clés qui chiffrent ce qui part dans une discussion.
pub fn chat_dialog(chat: &Chat) {
    let keys = core::brocolis_keys();
    if keys.is_empty() {
        modal(
            "Chiffrer avec Brocolis 🥦",
            |_| {
                h("div.stack")
                    .child(h("p.muted").text(ABOUT))
                    .child(
                        h("p").text("Ajoutez d'abord une clé, partagée avec vos correspondants."),
                    )
                    .into()
            },
            ModalOpts {
                actions: vec![action("Ajouter une clé", "primary", |m| {
                    m.close();
                    spawn(add_key());
                })],
                ..Default::default()
            },
        );
        return;
    }
    let boxes: Vec<(String, Element)> = keys
        .iter()
        .map(|k| {
            let input = h("input")
                .attr("type", "checkbox")
                .prop("checked", chat.brocolis.contains(&k.id))
                .el();
            (k.id.clone(), input)
        })
        .collect();
    let rows: Vec<Child> = keys
        .iter()
        .zip(&boxes)
        .map(|(k, (_, input))| {
            h("label.radio")
                .child(input.clone())
                .text(format!("🥦 {}", k.name))
                .into()
        })
        .collect();
    let id = chat.id.clone();
    modal(
        "Chiffrer avec Brocolis 🥦",
        move |_| {
            h("div.stack")
                .child(h("p.muted").text("Ce qui part dans cette discussion sera chiffré avec les clés cochées, en plus de Talka. Écrivez ((comme ceci)) pour ne chiffrer qu'un passage."))
                .children(rows)
                .child(h("p.muted.small").text("Plusieurs clés : un seul message, lisible par chacune (jeton v2, lu par l'app Brocolis à jour)."))
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Gérer les clés", "ghost", |m| {
                    m.close();
                    keys_dialog();
                }),
                action("Enregistrer", "primary", move |m| {
                    let ids: Vec<String> = boxes
                        .iter()
                        .filter(|(_, b)| checked(b))
                        .map(|(id, _)| id.clone())
                        .collect();
                    let (id, on) = (id.clone(), !ids.is_empty());
                    m.close();
                    spawn(async move {
                        core::set_chat_brocolis(&id, ids).await;
                        crate::conv::render_composer(false);
                        toast(if on {
                            "🥦 Brocolis activé pour cette discussion"
                        } else {
                            "Brocolis désactivé pour cette discussion"
                        });
                    });
                }),
            ],
            ..Default::default()
        },
    );
}
