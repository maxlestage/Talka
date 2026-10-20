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
use crate::jetons;
use crate::model::{BrocolisKey, Chat, Message};

const ABOUT: &str = "Brocolis chiffre vos messages une seconde fois, avec des clés que vous partagez avec vos proches dans l'app Brocolis 🥦. Talka les chiffre déjà de bout en bout ; seuls ceux qui ont la clé ouvrent cette seconde couche, ici ou dans l'app Brocolis (clavier, Partager).";
const STRENGTH: &str = "Chaque clé : 256 bits tirés au hasard (AES-256-GCM). Le base64 n'est que sa façon de s'écrire. Le point faible, c'est le partage : envoyez-la par Talka plutôt que par un canal en clair, et comparez son empreinte de vive voix.";

fn fingerprint(k: &BrocolisKey) -> String {
    jetons::fingerprint(&k.key).unwrap_or_default()
}

/// Les clés Brocolis de l'appareil : ajouter, créer, copier, renommer,
/// renouveler, oublier ; chacune avec son empreinte.
pub fn keys_dialog() {
    let keys = core::brocolis_keys();
    modal(
        "Brocolis 🥦",
        move |m| {
            let list: Vec<Child> = keys
                .iter()
                .map(|k| {
                    let (copy_key, rename, renew, forget) = (k.clone(), k.clone(), k.clone(), k.clone());
                    let (m1, m2, m3) = (m.clone(), m.clone(), m.clone());
                    h("div.list-item.bro-row")
                        .class_if("retired", k.retired)
                        .child(h("span.bro-key").text("🥦"))
                        .child(
                            h("div.grow")
                                .child(h("div").text(&k.name))
                                .child(h("small.muted.mono").attr("title", "Empreinte : comparez-la de vive voix").text(fingerprint(k))),
                        )
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
                        .child((!k.retired).then(|| {
                            icon_button("key", "Renouveler", move || {
                                let (k, m) = (renew.clone(), m2.clone());
                                spawn(async move {
                                    if confirm_box(
                                        "Renouveler cette clé ?",
                                        &format!("Une clé neuve remplace « {} » dans ses discussions et y est envoyée par Talka. L'ancienne garde l'historique lisible, mais ne chiffre plus rien.", k.name),
                                        "Renouveler",
                                        false,
                                    )
                                    .await
                                    {
                                        match core::renew_brocolis_key(&k.id).await {
                                            Ok(0) => toast("🥦 Clé renouvelée : partagez la neuve avec vos proches"),
                                            Ok(n) => toast(format!("🥦 Clé renouvelée et envoyée dans {n} discussion{}", if n > 1 { "s" } else { "" })),
                                            Err(e) => toast(e),
                                        }
                                        m.close();
                                        keys_dialog();
                                    }
                                })
                            })
                        }))
                        .child(icon_button("trash", "Oublier la clé", move || {
                            let (k, m) = (forget.clone(), m3.clone());
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
                .child(h("p.muted.small").text(STRENGTH))
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Créer une clé", "ghost", |m| {
                    m.close();
                    spawn(create_key(None));
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
        Ok(k) => toast(format!(
            "🥦 Clé « {} » ajoutée · empreinte {}",
            k.name,
            fingerprint(&k)
        )),
        Err(e) => toast(e),
    }
    keys_dialog();
}

/// Crée une clé ; pour une discussion, l'y active et l'y envoie par Talka.
async fn create_key(chat: Option<Chat>) {
    let default = chat.as_ref().map(core::chat_title).unwrap_or_default();
    let Some(name) = prompt_box(
        "Nouvelle clé Brocolis",
        "Son nom (pour qui ?)",
        &default,
        "text",
        "Créer",
        40,
    )
    .await
    else {
        return;
    };
    let k = match core::add_brocolis_key(&name, &jetons::generate_key()).await {
        Ok(k) => k,
        Err(e) => return toast(e),
    };
    match chat {
        Some(c) => {
            let mut ids = c.brocolis.clone();
            ids.push(k.id.clone());
            core::set_chat_brocolis(&c.id, ids).await;
            core::share_brocolis_key(&c.id, &k.id, None).await;
            crate::conv::render_composer(false);
            toast("🥦 Clé créée, envoyée par Talka et activée dans cette discussion");
        }
        None => {
            copy(&k.key, "🥦 Clé créée et copiée : partagez-la par un canal sûr (ou envoyez-la par Talka depuis une discussion)").await;
            keys_dialog();
        }
    }
}

/// Les clés qui chiffrent ce qui part dans une discussion.
pub fn chat_dialog(chat: &Chat) {
    let keys: Vec<BrocolisKey> = core::brocolis_keys()
        .into_iter()
        .filter(|k| !k.retired)
        .collect();
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
                .child(h("small.muted.mono").text(format!(" {}", fingerprint(k))))
                .into()
        })
        .collect();
    let (c1, c2) = (chat.clone(), chat.clone());
    let id = chat.id.clone();
    let empty = keys.is_empty();
    modal(
        "Chiffrer avec Brocolis 🥦",
        move |_| {
            h("div.stack")
                .child(h("p.muted").text(if empty {
                    ABOUT
                } else {
                    "Ce qui part dans cette discussion sera chiffré avec les clés cochées, en plus de Talka. Écrivez ((comme ceci)) pour ne chiffrer qu'un passage."
                }))
                .children(rows)
                .child(h("p.muted.small").text("« Créer et envoyer » : une clé neuve part dans cette discussion par le chiffrement de Talka ; vos correspondants l'ajoutent d'un geste. « Envoyer les clés cochées » fait de même pour des clés existantes."))
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Créer et envoyer", "ghost", move |m| {
                    m.close();
                    spawn(create_key(Some(c1.clone())));
                }),
                action("Envoyer les clés cochées", "ghost", {
                    let boxes = boxes.clone();
                    move |m| {
                        let ids: Vec<String> = boxes
                            .iter()
                            .filter(|(_, b)| checked(b))
                            .map(|(id, _)| id.clone())
                            .collect();
                        if ids.is_empty() {
                            return toast("Cochez d'abord une clé");
                        }
                        m.close();
                        let c = c2.clone();
                        spawn(async move {
                            for k in &ids {
                                core::share_brocolis_key(&c.id, k, None).await;
                            }
                            toast("🥦 Clé envoyée par Talka");
                        });
                    }
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

/// La bulle d'une clé partagée par Talka : son nom, son empreinte, et
/// « Ajouter » (qui l'active aussi dans la discussion).
pub fn key_card(m: &Message) -> El {
    let mine = m.from == core::my_code();
    let have = core::shared_key_of(m);
    let fp = if m.data.is_empty() {
        have.as_ref().map(fingerprint).unwrap_or_default()
    } else {
        jetons::fingerprint(&m.data).unwrap_or_default()
    };
    let card = h("div.bro-card").child(
        h("div.bro-card-head")
            .child(h("span.bro-key").text("🥦"))
            .child(
                h("div.grow")
                    .child(h("strong").text(format!("Clé Brocolis « {} »", m.name)))
                    .child(h("small.muted.mono").text(&fp)),
            ),
    );
    let action: Child = if mine {
        h("small.muted")
            .text("Vous avez partagé cette clé. Comparez l'empreinte de vive voix.")
            .into()
    } else if let Some(k) = have {
        h("small.muted")
            .text(format!("✓ Dans vos clés (« {} »)", k.name))
            .into()
    } else if m.data.is_empty() {
        h("small.muted")
            .text("Clé oubliée sur cet appareil.")
            .into()
    } else {
        let msg = m.clone();
        h("button.btn.primary")
            .attr("type", "button")
            .text("Ajouter la clé")
            .click(move || {
                let msg = msg.clone();
                spawn(async move {
                    match core::accept_brocolis_key(&msg.chat, &msg).await {
                        Ok(k) => {
                            crate::conv::render_composer(false);
                            crate::conv::queue_render();
                            toast(format!(
                                "🥦 « {} » ajoutée et activée ici · empreinte {}",
                                k.name,
                                fingerprint(&k)
                            ));
                        }
                        Err(e) => toast(e),
                    }
                });
            })
            .into()
    };
    card.child(action)
}
