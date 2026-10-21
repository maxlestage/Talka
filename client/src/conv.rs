//! La conversation ouverte : l'en-tête, les bulles, la zone de saisie.

use std::cell::{Cell, RefCell};
use std::collections::BTreeMap;
use std::rc::Rc;

use wasm_bindgen::JsCast;
use web_sys::{
    Element, HtmlElement, HtmlMediaElement, HtmlTextAreaElement, KeyboardEvent, MouseEvent,
    PointerEvent, ScrollBehavior, ScrollToOptions,
};

use crate::core;
use crate::crypto::pretty;
use crate::dom::*;
use crate::emoji::{QUICK, picker, remember};
use crate::media::{self, MAX_FILE, Recorder};
use crate::model::{Chat, Content, Message, Reply};
use crate::sounds;
use crate::ui::{
    avatar, avatar_presence, color_of, open_add_contact, open_chat, open_image, open_info,
    pick_chats,
};

const PAGE: usize = 120;

#[derive(Default)]
struct Conv {
    chat: Option<String>,
    limit: usize,
    list: Option<Element>,
    down: Option<Element>,
    search_bar: Option<Element>,
    pin_bar: Option<Element>,
    banner: Option<Element>,
    composer: Option<Element>,
    head: Option<Element>,
    reply: Option<Reply>,
    editing: Option<Message>,
    search: String,
    first_unread: u32,
    unread_id: Option<String>,
    has_more: bool,
    loading_more: bool,
}

thread_local! {
    static C: RefCell<Conv> = RefCell::new(Conv::default());
    static QUEUED: Cell<bool> = const { Cell::new(false) };
    static RECORDER: RefCell<Option<(Recorder, i32)>> = const { RefCell::new(None) };
}

fn with<T>(f: impl FnOnce(&mut Conv) -> T) -> T {
    C.with(|c| f(&mut c.borrow_mut()))
}

pub fn current() -> Option<String> {
    with(|c| c.chat.clone())
}

fn el(f: impl FnOnce(&Conv) -> Option<Element>) -> Option<Element> {
    C.with(|c| f(&c.borrow()))
}

// ----- En-tête -----------------------------------------------------------------

fn subtitle(chat: &Chat) -> String {
    let typing = core::typing_in(&chat.id);
    if !typing.is_empty() {
        if chat.group {
            return format!(
                "{} écrit…",
                typing
                    .iter()
                    .map(|c| core::display_name(c))
                    .collect::<Vec<_>>()
                    .join(", ")
            );
        }
        return "écrit…".into();
    }
    if chat.group {
        if chat.left {
            return "Vous ne faites plus partie de ce groupe".into();
        }
        let me = core::my_code();
        return chat
            .members
            .iter()
            .map(|c| {
                if *c == me {
                    "Vous".into()
                } else {
                    core::display_name(c)
                }
            })
            .collect::<Vec<_>>()
            .join(", ");
    }
    if core::blocked(&chat.id) {
        return "Bloqué".into();
    }
    match core::presence(&chat.id) {
        Some((true, _)) => "en ligne".into(),
        Some((false, Some(seen))) => last_seen(seen),
        _ => pretty(&chat.id),
    }
}

fn header(chat: &Chat) -> Element {
    let id = chat.id.clone();
    let direct = !chat.group;
    let (v, a) = (chat.id.clone(), chat.id.clone());
    let menu_chat = chat.id.clone();
    h("header.conv-head")
        .child(icon_button("back", "Retour", back).class("only-mobile"))
        .child(
            h("button.conv-title")
                .attr("type", "button")
                .click(move || {
                    let id = id.clone();
                    spawn(async move { open_info(&id).await })
                })
                .child(avatar_presence(&chat.id, 40))
                .child(
                    h("div.conv-names")
                        .child(h("div.conv-name").text(core::chat_title(chat)))
                        .child(h("div.conv-sub").text(subtitle(chat))),
                ),
        )
        .child(
            h("div.conv-actions")
                .child(direct.then(|| {
                    icon_button("video", "Appel vidéo", move || {
                        let v = v.clone();
                        spawn(async move { crate::calls::start_call(&v, true).await })
                    })
                }))
                .child(direct.then(|| {
                    icon_button("phone", "Appel audio", move || {
                        let a = a.clone();
                        spawn(async move { crate::calls::start_call(&a, false).await })
                    })
                }))
                .child(icon_button("search", "Rechercher", toggle_search))
                .child({
                    let b = h("button.icon-btn")
                        .attr("type", "button")
                        .attr("aria-label", "Menu")
                        .attr("title", "Menu")
                        .child(icon("more"))
                        .el();
                    let bb = b.clone();
                    listen(&b, "click", move |e| {
                        e.stop_propagation();
                        if let Some(c) = core::chat(&menu_chat) {
                            chat_menu(&c, At::El(bb.clone()));
                        }
                    });
                    b
                }),
        )
        .el()
}

fn chat_menu(chat: &Chat, at: At) {
    let direct = !chat.group;
    let saved = core::contact(&chat.id).is_some_and(|c| c.saved);
    let blocked = core::blocked(&chat.id);
    let i9 = chat.clone();
    let (i1, i2, i3, i4, i5, i6, i7, i8) = (
        chat.id.clone(),
        chat.clone(),
        chat.clone(),
        chat.clone(),
        chat.id.clone(),
        chat.id.clone(),
        chat.id.clone(),
        chat.clone(),
    );
    menu(
        vec![
            item(
                "info",
                if direct {
                    "Infos du contact"
                } else {
                    "Infos du groupe"
                },
                move || {
                    let id = i1.clone();
                    spawn(async move { open_info(&id).await })
                },
            ),
            item("search", "Rechercher", toggle_search),
            item("timer", "Messages éphémères", move || timer_dialog(&i2)),
            item(
                "lock",
                if chat.brocolis.is_empty() {
                    "Chiffrer avec Brocolis 🥦".to_owned()
                } else {
                    format!("Brocolis 🥦 : {}", brocolis_names(chat))
                },
                move || crate::brocoli::chat_dialog(&i9),
            ),
            item(
                if chat.muted { "bell" } else { "belloff" },
                if chat.muted {
                    "Réactiver les notifications"
                } else {
                    "Mettre en sourdine"
                },
                move || mute_or_unmute(&i3),
            ),
            item("palette", "Fond d'écran", move || {
                wallpaper_dialog(Some(i4.clone()))
            }),
            if direct && !saved {
                item("person", "Ajouter aux contacts", move || {
                    open_add_contact(&i5, "")
                })
            } else {
                None
            },
            if direct {
                if blocked {
                    item("block", "Débloquer", move || {
                        spawn(toggle_block(i6.clone()))
                    })
                } else {
                    danger("block", "Bloquer", move || spawn(toggle_block(i6.clone())))
                }
            } else {
                None
            },
            danger("trash", "Vider la discussion", move || {
                let id = i7.clone();
                spawn(async move {
                    if confirm_box(
                        "Vider la discussion ?",
                        "Tous les messages seront effacés de cet appareil.",
                        "Vider",
                        true,
                    )
                    .await
                    {
                        core::clear_chat(&id).await;
                    }
                })
            }),
            danger(
                "trash",
                if direct {
                    "Supprimer la discussion"
                } else {
                    "Quitter et supprimer"
                },
                move || spawn(delete_chat(i8.clone())),
            ),
        ],
        at,
    );
}

/// Les noms des clés Brocolis qui chiffrent cette discussion.
fn brocolis_names(chat: &Chat) -> String {
    core::chat_brocolis(chat)
        .iter()
        .map(|k| k.name.as_str())
        .collect::<Vec<_>>()
        .join(", ")
}

async fn toggle_block(code: String) {
    let blocked = core::blocked(&code);
    if !blocked
        && !confirm_box(
            &format!("Bloquer {} ?", core::display_name(&code)),
            "Vous ne recevrez plus ses messages, appels ni statuts.",
            "Bloquer",
            true,
        )
        .await
    {
        return;
    }
    core::set_blocked(&code, !blocked).await;
    toast(if blocked {
        "Contact débloqué"
    } else {
        "Contact bloqué"
    });
    refresh_header();
    render_composer(false);
}

async fn delete_chat(chat: Chat) {
    if !confirm_box(
        "Supprimer la discussion ?",
        "Elle disparaîtra de cet appareil avec tous ses messages.",
        "Supprimer",
        true,
    )
    .await
    {
        return;
    }
    if chat.group && !chat.left {
        core::leave_group(&chat.id).await;
    }
    core::delete_chat(&chat.id).await;
    back();
}

/// La sourdine : réactiver le son d'un geste, ou choisir pour combien de
/// temps la mettre (8 heures, une semaine, toujours), comme WhatsApp.
pub fn mute_or_unmute(chat: &Chat) {
    if chat.muted {
        let id = chat.id.clone();
        spawn(async move {
            core::mute(&id, Some(0)).await;
            toast("Notifications réactivées");
        });
        return;
    }
    let options: [(Option<u64>, &str); 3] = [
        (Some(8 * 3600), "8 heures"),
        (Some(7 * 86400), "1 semaine"),
        (None, "Toujours"),
    ];
    let id = chat.id.clone();
    modal(
        "Mettre en sourdine",
        move |m| {
            h("div.stack")
                .child(h("p.muted").text(
                    "Plus de notification ni de son pour cette discussion ; les messages arrivent quand même.",
                ))
                .children(options.iter().map(|(secs, label)| {
                    let (id, m, secs, label) = (id.clone(), m.clone(), *secs, *label);
                    h("button.list-item")
                        .attr("type", "button")
                        .child(icon("belloff"))
                        .child(h("div.grow").text(label))
                        .click(move || {
                            let id = id.clone();
                            m.close();
                            spawn(async move {
                                core::mute(&id, secs).await;
                                toast(match secs {
                                    None => "Sourdine".to_owned(),
                                    Some(_) => format!("Sourdine pendant {label}"),
                                });
                            });
                        })
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

pub fn timer_dialog(chat: &Chat) {
    let options: [(u64, &str); 4] = [
        (0, "Désactivés"),
        (86400, "24 heures"),
        (7 * 86400, "7 jours"),
        (90 * 86400, "90 jours"),
    ];
    let id = chat.id.clone();
    let current = chat.timer;
    modal(
        "Messages éphémères",
        move |m| {
            h("div.stack")
                .child(h("p.muted").text("Les nouveaux messages disparaîtront de cette discussion après la durée choisie, pour tout le monde."))
                .children(options.iter().map(|(secs, label)| {
                    let (id, m, secs) = (id.clone(), m.clone(), *secs);
                    h("label.radio")
                        .child(
                            h("input")
                                .attr("type", "radio")
                                .attr("name", "timer")
                                .prop("checked", current == secs)
                                .on("change", move |_| {
                                    let id = id.clone();
                                    m.close();
                                    spawn(async move { core::set_timer(&id, secs).await });
                                }),
                        )
                        .text(*label)
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

const WALLPAPERS: [&str; 7] = ["doodle", "plain", "mint", "sky", "rose", "night", "sand"];

pub fn wallpaper_dialog(chat: Option<Chat>) {
    let title = if chat.is_some() {
        "Fond d'écran de la discussion"
    } else {
        "Fond d'écran"
    };
    modal(
        title,
        move |m| {
            let (m2, chat2) = (m.clone(), chat.clone());
            let photo = h("button.wall-swatch.wall-add")
                .attr("type", "button")
                .attr("aria-label", "Ma photo (galerie)")
                .child(icon("image"))
                .child(h("span").text("Ma photo"))
                .click(move || {
                    let (m, chat) = (m2.clone(), chat2.clone());
                    spawn(async move {
                        let Some(f) = media::pick_file("image/*", None).await else {
                            return;
                        };
                        match media::compress_image(&f, 1280.0, 0.78).await {
                            Some((data, _, _)) => {
                                save_wallpaper(chat, data).await;
                                m.close();
                            }
                            None => toast("Cette image ne peut pas être lue."),
                        }
                    })
                });
            let (m3, chat3) = (m.clone(), chat.clone());
            let default = chat.as_ref().map(|_| {
                h("button.wall-swatch.wall-add")
                    .attr("type", "button")
                    .attr("aria-label", "Par défaut")
                    .child(h("span").text("Par défaut"))
                    .click(move || {
                        let (m, chat) = (m3.clone(), chat3.clone());
                        spawn(async move {
                            save_wallpaper(chat, String::new()).await;
                            m.close();
                        })
                    })
            });
            h("div.wall-grid")
                .child(photo)
                .child(default)
                .children(WALLPAPERS.iter().map(|w| {
                    let (m, chat, w) = (m.clone(), chat.clone(), *w);
                    h("button.wall-swatch")
                        .class(&format!("wall-{w}"))
                        .attr("type", "button")
                        .attr("aria-label", w)
                        .click(move || {
                            let (m, chat) = (m.clone(), chat.clone());
                            spawn(async move {
                                save_wallpaper(chat, w.into()).await;
                                m.close();
                            })
                        })
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

fn apply_wallpaper() {
    let (Some(list), Some(id)) = (el(|c| c.list.clone()), current()) else {
        return;
    };
    let Some(chat) = core::chat(&id) else { return };
    let w = if chat.wallpaper.is_empty() {
        core::settings().wallpaper
    } else {
        chat.wallpaper
    };
    // Une photo choisie dans la galerie : une adresse `data:` (gardée sur
    // l'appareil, jamais envoyée).
    if w.starts_with("data:image/") {
        list.set_class_name("messages wall-photo");
        set_style(&list, "background-image", &format!("url(\"{w}\")"));
        return;
    }
    set_style(&list, "background-image", "");
    list.set_class_name(&format!(
        "messages wall-{}",
        if w.is_empty() { "doodle" } else { &w }
    ));
}

/// Enregistre un fond d'écran : celui d'une discussion, ou de toutes.
async fn save_wallpaper(chat: Option<Chat>, w: String) {
    match chat {
        Some(c) => core::update_chat(&c.id, |x| x.wallpaper = w).await,
        None => core::save_settings(|s| s.wallpaper = w).await,
    }
    apply_wallpaper();
}

// ----- Ouvrir, fermer ----------------------------------------------------------

pub async fn open(chat_id: &str, host: &Element) {
    let Some(chat) = core::chat(chat_id) else {
        return;
    };
    stop_recording(true).await;
    core::set_active(Some(chat_id.to_owned()));
    let list = h("div.messages")
        .attr("role", "log")
        .attr("aria-live", "polite")
        .el();
    let down = h("button.to-bottom.hidden")
        .attr("type", "button")
        .attr("aria-label", "Derniers messages")
        .child(icon("arrowdown"))
        .el();
    let search_bar = h("div.conv-search.hidden").el();
    let pin_bar = h("div.pin-bar").el();
    let banner = h("div.conv-banner").el();
    let composer = h("div.composer-wrap").el();
    let head = scope("conv-head", || header(&chat));
    scope("conv", || {
        listen(&list, "scroll", |_| on_scroll());
        listen(&down, "click", |_| scroll_bottom(true));
    });
    replace(
        host,
        h("div.conv")
            .child(head.clone())
            .child(search_bar.clone())
            .child(pin_bar.clone())
            .child(banner.clone())
            .child(
                h("div.messages-wrap")
                    .child(list.clone())
                    .child(down.clone()),
            )
            .child(composer.clone()),
    );
    with(|c| {
        *c = Conv {
            chat: Some(chat_id.to_owned()),
            limit: PAGE,
            list: Some(list),
            down: Some(down),
            search_bar: Some(search_bar),
            pin_bar: Some(pin_bar),
            banner: Some(banner),
            composer: Some(composer),
            head: Some(head),
            first_unread: chat.unread,
            ..Default::default()
        }
    });
    apply_wallpaper();
    render_banner();
    render_composer(false);
    render(true).await;
    core::mark_read(chat_id).await;
}

pub fn close() {
    spawn(stop_recording(true));
    save_draft();
    with(|c| *c = Conv::default());
    core::set_active(None);
}

fn textarea() -> Option<HtmlTextAreaElement> {
    el(|c| c.composer.clone())
        .and_then(|c| query_in(&c, "textarea"))
        .and_then(|t| t.dyn_into().ok())
}

fn save_draft() {
    let (Some(ta), Some(id)) = (textarea(), current()) else {
        return;
    };
    if with(|c| c.editing.is_some()) {
        return;
    }
    let text = ta.value();
    if core::chat(&id).is_some_and(|c| c.draft != text) {
        spawn(async move { core::update_chat(&id, |c| c.draft = text).await });
    }
}

pub fn refresh_header() {
    let (Some(id), Some(old)) = (current(), el(|c| c.head.clone())) else {
        return;
    };
    let Some(chat) = core::chat(&id) else { return };
    let fresh = scope("conv-head", || header(&chat));
    let _ = old.replace_with_with_node_1(&fresh);
    with(|c| c.head = Some(fresh));
    render_banner();
}

fn render_banner() {
    let (Some(id), Some(banner)) = (current(), el(|c| c.banner.clone())) else {
        return;
    };
    let Some(chat) = core::chat(&id) else { return };
    let contact = core::contact(&id);
    scope("conv-banner", || {
        if !chat.group && !contact.as_ref().is_some_and(|c| c.saved || c.blocked) {
            let (a, b) = (id.clone(), id.clone());
            replace(
                &banner,
                h("div.unknown")
                    .child(h("p").text(format!("{} n'est pas dans vos contacts.", pretty(&id))))
                    .child(
                        h("div.row")
                            .child(
                                h("button.btn.ghost.danger")
                                    .attr("type", "button")
                                    .text("Bloquer")
                                    .click(move || spawn(toggle_block(a.clone()))),
                            )
                            .child(
                                h("button.btn.primary")
                                    .attr("type", "button")
                                    .text("Ajouter")
                                    .click(move || open_add_contact(&b, "")),
                            ),
                    ),
            );
        } else if !chat.group && contact.as_ref().is_some_and(|c| c.sign.is_empty()) {
            replace(&banner, h("div.notice").text("⏳ Ce code n'est pas encore joignable : vos messages partiront dès que votre contact ouvrira Talka."));
        } else {
            replace(&banner, Child::None);
        }
    });
}

// ----- Les messages --------------------------------------------------------------

pub fn queue_render() {
    if QUEUED.with(|q| q.replace(true)) {
        return;
    }
    timer(16, || {
        QUEUED.with(|q| q.set(false));
        spawn(render(false));
    });
}

fn near_bottom() -> bool {
    let Some(l) = el(|c| c.list.clone()) else {
        return true;
    };
    (l.scroll_height() - l.scroll_top() - l.client_height()) < 120
}

fn scroll_bottom(smooth: bool) {
    let Some(l) = el(|c| c.list.clone()) else {
        return;
    };
    let o = ScrollToOptions::new();
    o.set_top(l.scroll_height() as f64);
    o.set_behavior(if smooth {
        ScrollBehavior::Smooth
    } else {
        ScrollBehavior::Auto
    });
    l.scroll_to_with_scroll_to_options(&o);
}

/// Fait défiler la liste seule (scrollIntoView ferait aussi glisser la page).
fn center_on(target: &Element, smooth: bool) {
    let Some(l) = el(|c| c.list.clone()) else {
        return;
    };
    let top = target.get_bounding_client_rect().top() - l.get_bounding_client_rect().top()
        + l.scroll_top() as f64;
    let hgt = target
        .dyn_ref::<HtmlElement>()
        .map_or(0, |e| e.offset_height()) as f64;
    let o = ScrollToOptions::new();
    o.set_top(top - l.client_height() as f64 / 2.0 + hgt / 2.0);
    o.set_behavior(if smooth {
        ScrollBehavior::Smooth
    } else {
        ScrollBehavior::Auto
    });
    l.scroll_to_with_scroll_to_options(&o);
}

fn on_scroll() {
    if let Some(d) = el(|c| c.down.clone()) {
        toggle_class(&d, "hidden", near_bottom());
    }
    let Some(l) = el(|c| c.list.clone()) else {
        return;
    };
    if l.scroll_top() < 60 && with(|c| c.has_more && !c.loading_more) {
        with(|c| {
            c.loading_more = true;
            c.limit += PAGE;
        });
        let before = l.scroll_height();
        spawn(async move {
            render(false).await;
            l.set_scroll_top(l.scroll_top() + l.scroll_height() - before);
            with(|c| c.loading_more = false);
        });
    }
}

async fn render(initial: bool) {
    let Some(chat_id) = current() else { return };
    let limit = with(|c| c.limit);
    let all = core::messages(&chat_id, limit + 1).await;
    if current().as_deref() != Some(chat_id.as_str()) {
        return;
    }
    let Some(list_el) = el(|c| c.list.clone()) else {
        return;
    };
    render_pin(&chat_id).await;
    let chat = core::chat(&chat_id);
    let has_more = all.len() > limit;
    let t = now();
    let list: Vec<Message> = all
        .into_iter()
        .skip(if has_more { 1 } else { 0 })
        .filter(|m| m.exp.is_none_or(|e| e > t))
        .collect();
    let q = with(|c| crate::model::fold(c.search.trim()));
    let stick = initial || near_bottom();
    if initial {
        let n = with(|c| c.first_unread) as usize;
        let id = (n > 0 && list.len() >= n).then(|| list[list.len() - n].id.clone());
        with(|c| c.unread_id = id);
    }
    let (unread_id, first_unread) = with(|c| {
        c.has_more = has_more;
        (c.unread_id.clone(), c.first_unread)
    });
    scope("messages", || {
        let mut nodes: Vec<Child> = vec![
            h("div.e2e-note").child(icon("lock")).text(" Les messages sont chiffrés de bout en bout. Personne d'autre, pas même Talka, ne peut les lire.").into(),
        ];
        if chat.as_ref().is_some_and(|c| c.timer > 0) {
            nodes.push(
                h("div.system-msg")
                    .text("⏳ Messages éphémères activés")
                    .into(),
            );
        }
        let mut prev: Option<&Message> = None;
        let mut shown = 0;
        let (me, t) = (core::my_code(), now());
        for m in &list {
            // La recherche ne trouve pas ce qu'une capsule scellée cache.
            if !q.is_empty()
                && (m.sealed_for(&me, t)
                    || !crate::model::fold(&format!("{} {}", m.text, m.name)).contains(&q))
            {
                continue;
            }
            shown += 1;
            if prev.is_none_or(|p| !same_day(p.at, m.at)) {
                nodes.push(
                    h("div.day-sep")
                        .child(h("span").text(day_label(m.at)))
                        .into(),
                );
            }
            if q.is_empty() && unread_id.as_deref() == Some(m.id.as_str()) {
                let s = if first_unread > 1 { "s" } else { "" };
                nodes.push(
                    h("div.unread-sep")
                        .child(h("span").text(format!("{first_unread} message{s} non lu{s}")))
                        .into(),
                );
            }
            nodes.push(bubble(m, prev, chat.as_ref()).into());
            prev = Some(m);
        }
        if !q.is_empty() && shown == 0 {
            nodes.push(h("div.system-msg").text("Aucun message trouvé").into());
        }
        replace(&list_el, nodes);
    });
    if stick {
        scroll_bottom(false);
    }
    if initial && let Some(sep) = query_in(&list_el, ".unread-sep") {
        center_on(&sep, false);
    }
    if let Some(d) = el(|c| c.down.clone()) {
        toggle_class(&d, "hidden", near_bottom());
    }
}

fn ticks(m: &Message) -> El {
    match m.tick() {
        "pending" => h("span.tick.pending")
            .attr("title", "En attente")
            .text("🕓"),
        "failed" => h("span.tick.failed")
            .attr("title", "Échec de l'envoi")
            .text("⚠️"),
        "sent" => h("span.tick.sent").attr("title", "Envoyé").text("✓"),
        "delivered" => h("span.tick.delivered")
            .attr("title", "Distribué")
            .text("✓✓"),
        _ => h("span.tick.read").attr("title", "Lu").text("✓✓"),
    }
}

fn who(code: &str) -> String {
    if code == core::my_code() {
        "Vous".into()
    } else {
        core::display_name(code)
    }
}

fn quote(r: &Reply) -> El {
    let id = r.id.clone();
    h("button.quote")
        .attr("type", "button")
        .style("border-color", color_of(&r.from))
        .child(
            h("strong")
                .style("color", color_of(&r.from))
                .text(who(&r.from)),
        )
        .child(h("span").text(if r.text.is_empty() {
            "Message".into()
        } else {
            crate::jetons::masked(&r.text)
        }))
        .click(move || jump_to(&id))
}

/// Ouvre une discussion sur un message (résultat de recherche) : le temps
/// qu'elle s'affiche, puis le message clignote au milieu de l'écran.
pub fn reveal(id: &str) {
    let id = id.to_owned();
    timer(450, move || jump_to(&id));
}

/// Amène un message au milieu de l'écran et le fait clignoter.
fn jump_to(id: &str) {
    let Some(list) = el(|c| c.list.clone()) else {
        return;
    };
    match query_in(&list, &format!("[data-id=\"{id}\"]")) {
        Some(target) => {
            center_on(&target, true);
            toggle_class(&target, "flash", true);
            timer(1200, move || toggle_class(&target, "flash", false));
        }
        None => toast("Message introuvable (plus ancien que ceux affichés ?)"),
    }
}

/// Le bandeau du message épinglé, sous l'en-tête.
async fn render_pin(chat_id: &str) {
    let Some(bar) = el(|c| c.pin_bar.clone()) else {
        return;
    };
    let pinned = core::pinned_message(chat_id).await;
    scope("conv-pin", || match pinned {
        Some(m) => {
            let (id, chat) = (m.id.clone(), m.chat.clone());
            let text = core::readable(&m);
            replace(
                &bar,
                h("div.pin-row")
                    .child(
                        h("button.pin-open")
                            .attr("type", "button")
                            .attr("aria-label", "Aller au message épinglé")
                            .child(icon("pin"))
                            .child(
                                h("span.pin-text")
                                    .child(h("strong").text(who(&m.from)))
                                    .child(h("span").text(text)),
                            )
                            .click(move || jump_to(&id)),
                    )
                    .child(icon_button("close", "Désépingler", move || {
                        let c = chat.clone();
                        spawn(async move { core::set_pin(&c, "").await })
                    })),
            );
        }
        None => replace(&bar, Child::None),
    });
}

fn voice_player(m: &Message) -> El {
    let audio = h("audio").attr("preload", "none").el();
    let audio_m: HtmlMediaElement = audio.clone().unchecked_into();
    let play_btn = h("button.voice-play")
        .attr("type", "button")
        .attr("aria-label", "Lire")
        .child(icon("play"))
        .el();
    let bars = h("div.voice-bars").el();
    let dur_el = h("span.voice-dur").text(fmt_dur(m.dur)).el();
    let speed = Rc::new(Cell::new(1.0f64));
    let speed_btn = h("button.voice-speed")
        .attr("type", "button")
        .attr("aria-label", "Vitesse de lecture : 1×")
        .text("1×")
        .el();
    let (data, dur) = (m.data.clone(), m.dur);
    let b = bars.clone();
    spawn(async move {
        let w = media::waveform(&data, 36).await;
        replace(
            &b,
            w.iter()
                .map(|v| {
                    h("i")
                        .style("height", format!("{}%", (v * 100.0).round()))
                        .el()
                })
                .collect::<Vec<_>>(),
        );
    });
    {
        let (a, s, sb) = (audio_m.clone(), speed.clone(), speed_btn.clone());
        listen(&speed_btn, "click", move |e| {
            e.stop_propagation();
            let next = match s.get() {
                x if x < 1.4 => 1.5,
                x if x < 1.9 => 2.0,
                _ => 1.0,
            };
            s.set(next);
            a.set_playback_rate(next);
            let label = format!(
                "{}×",
                if next == 1.5 {
                    "1,5".to_owned()
                } else {
                    format!("{next}")
                }
            );
            sb.set_text_content(Some(&label));
            let _ = sb.set_attribute("aria-label", &format!("Vitesse de lecture : {label}"));
        });
    }
    {
        let (a, bars, d) = (audio_m.clone(), bars.clone(), dur_el.clone());
        listen(&audio, "timeupdate", move |_| {
            let total = if a.duration().is_finite() && a.duration() > 0.0 {
                a.duration()
            } else {
                dur.max(1.0)
            };
            let n = ((a.current_time() / total) * 36.0).round() as usize;
            for (i, b) in query_all_in(&bars, "i").iter().enumerate() {
                toggle_class(b, "on", i < n);
            }
            d.set_text_content(Some(&fmt_dur(a.current_time())));
        });
    }
    // Mis en pause d'ailleurs (un autre vocal qui démarre) : le bouton le dit.
    {
        let p = play_btn.clone();
        listen(&audio, "pause", move |_| {
            replace(&p, icon("play"));
            let _ = p.set_attribute("aria-label", "Lire");
        });
    }
    {
        let (p, bars, d, el) = (
            play_btn.clone(),
            bars.clone(),
            dur_el.clone(),
            audio.clone(),
        );
        listen(&audio, "ended", move |_| {
            replace(&p, icon("play"));
            let _ = p.set_attribute("aria-label", "Lire");
            for b in query_all_in(&bars, "i") {
                toggle_class(&b, "on", false);
            }
            d.set_text_content(Some(&fmt_dur(dur)));
            toggle_class(&el, "playing", false);
            play_next_voice(&el);
        });
    }
    {
        let (a, p, s, data) = (
            audio_m.clone(),
            play_btn.clone(),
            speed.clone(),
            m.data.clone(),
        );
        listen(&play_btn, "click", move |e| {
            e.stop_propagation();
            if a.src().is_empty() {
                a.set_src(&data);
            }
            if a.paused() {
                for other in query_all_in(
                    &document().document_element().expect("html"),
                    "audio.playing",
                ) {
                    if let Some(o) = other.dyn_ref::<HtmlMediaElement>() {
                        let _ = o.pause();
                    }
                    toggle_class(&other, "playing", false);
                }
                toggle_class(&a, "playing", true);
                a.set_playback_rate(s.get());
                play(&a);
                replace(&p, icon("pause"));
                let _ = p.set_attribute("aria-label", "Pause");
            } else {
                let _ = a.pause();
                replace(&p, icon("play"));
                let _ = p.set_attribute("aria-label", "Lire");
            }
        });
    }
    {
        let (a, b2) = (audio_m, bars.clone());
        listen(&bars, "click", move |e| {
            e.stop_propagation();
            let Some(me) = e.dyn_ref::<MouseEvent>() else {
                return;
            };
            if !a.duration().is_finite() {
                return;
            }
            let r = b2.get_bounding_client_rect();
            a.set_current_time(((me.client_x() as f64 - r.left()) / r.width()) * a.duration());
        });
    }
    h("div.voice")
        .child(audio)
        .child(
            h("div.voice-avatar")
                .child(avatar(&m.from, 40))
                .child(h("span.voice-mic").child(icon("mic"))),
        )
        .child(play_btn)
        .child(
            h("div.voice-main")
                .child(bars)
                .child(h("div.voice-meta").child(dur_el).child(speed_btn)),
        )
}

/// Comme WhatsApp : un vocal fini, le suivant enchaîne s'il vient juste
/// après (les séparateurs de jour et « non lus » ne comptent pas ; tout autre
/// message arrête la lecture).
fn play_next_voice(audio: &Element) {
    let Some(row) = audio.closest(".msg-row").ok().flatten() else {
        return;
    };
    let mut next = row.next_element_sibling();
    while let Some(el) = next {
        if el.class_list().contains("msg-row") {
            if let Some(btn) = query_in(&el, ".bubble.voice .voice-play") {
                let opts = js_sys::Object::new();
                let _ = js_sys::Reflect::set(&opts, &"block".into(), &"nearest".into());
                let _ = js_sys::Reflect::set(&opts, &"behavior".into(), &"smooth".into());
                let _ = call(&el, "scrollIntoView", &[opts.into()]);
                if let Some(b) = btn.dyn_ref::<HtmlElement>() {
                    b.click();
                }
            }
            return;
        }
        next = el.next_element_sibling();
    }
}

// ----- Les capsules temporelles -----

/// « dans 2 j 4 h », « dans 12 min », « dans 40 s ».
fn countdown(open_at: f64) -> String {
    let s = ((open_at - now()) / 1000.0).ceil().max(0.0) as u64;
    let (d, h, m) = (s / 86_400, s % 86_400 / 3600, s % 3600 / 60);
    if d > 0 {
        format!("dans {d} j {h} h")
    } else if h > 0 {
        format!("dans {h} h {m:02}")
    } else if m > 0 {
        format!("dans {m} min")
    } else {
        format!("dans {s} s")
    }
}

/// Le bandeau d'une capsule : pour l'auteur, quand elle s'ouvrira ; une fois
/// ouverte, quand elle a été écrite.
fn capsule_band(m: &Message) -> Option<El> {
    if !m.is_capsule() || m.deleted || m.sealed_for(&core::my_code(), now()) {
        return None;
    }
    let text = if m.open_at > now() {
        format!(
            "⏳ Scellée · s'ouvre {} à {}",
            when_day(m.open_at),
            time(m.open_at)
        )
    } else {
        format!(
            "⏳ Capsule écrite {} · ouverte {}",
            when_day(m.at),
            when_day(m.open_at)
        )
    };
    Some(h("div.capsule-band").text(text))
}

/// Une capsule encore scellée : l'enveloppe, et le temps qu'il reste.
fn sealed(m: &Message) -> El {
    start_capsule_tick();
    h("div.capsule")
        .attr("data-open-at", m.open_at.to_string())
        .child(h("div.capsule-seal").attr("aria-hidden", "true").text("✉️"))
        .child(h("div.capsule-title").text("Capsule temporelle"))
        .child(h("div.capsule-count").text(format!("S'ouvre {}", countdown(m.open_at))))
        .child(h("div.capsule-date").text(format!(
            "{} à {}",
            cap(&when_day(m.open_at)),
            time(m.open_at)
        )))
}

thread_local! {
    static CAPSULE_TICK: Cell<bool> = const { Cell::new(false) };
}

/// Une fois par seconde : les comptes à rebours des capsules affichées, et
/// l'ouverture de celles dont l'heure est venue.
fn start_capsule_tick() {
    if CAPSULE_TICK.with(|t| t.replace(true)) {
        return;
    }
    let tick = wasm_bindgen::closure::Closure::<dyn FnMut()>::new(|| {
        let Ok(list) = document().query_selector_all("[data-open-at]") else {
            return;
        };
        let mut opened = false;
        for i in 0..list.length() {
            let Some(node) = list
                .item(i)
                .and_then(|n| n.dyn_into::<web_sys::Element>().ok())
            else {
                continue;
            };
            let at: f64 = node
                .get_attribute("data-open-at")
                .and_then(|v| v.parse().ok())
                .unwrap_or(0.0);
            if at <= now() {
                opened = true;
            } else if let Ok(Some(c)) = node.query_selector(".capsule-count") {
                c.set_text_content(Some(&format!("S'ouvre {}", countdown(at))));
            }
        }
        if opened {
            queue_render();
        }
    });
    let _ = web_sys::window()
        .expect("fenêtre")
        .set_interval_with_callback_and_timeout_and_arguments_0(
            tick.as_ref().unchecked_ref(),
            1000,
        );
    tick.forget();
}

fn content(m: &Message) -> El {
    if m.deleted {
        return h("div.text.deleted").text(if m.from == core::my_code() {
            "🚫 Vous avez supprimé ce message"
        } else {
            "🚫 Ce message a été supprimé"
        });
    }
    match m.kind.as_str() {
        "image" => {
            let ratio = if m.w > 0 && m.h > 0 {
                (m.h as f64 / m.w as f64).clamp(0.6, 1.4)
            } else {
                0.75
            };
            let (src, caption) = (m.data.clone(), m.text.clone());
            h("div.media")
                .child(
                    h("button.img-btn")
                        .attr("type", "button")
                        .click(move || open_image(&src, &caption))
                        .child(
                            h("img")
                                .attr("src", &m.data)
                                .attr("alt", if m.text.is_empty() { "Photo" } else { &m.text })
                                .attr("loading", "lazy")
                                .style(
                                    "aspect-ratio",
                                    if m.w > 0 && m.h > 0 {
                                        format!("{} / {}", m.w, m.h)
                                    } else {
                                        "4 / 3".into()
                                    },
                                )
                                .style("max-height", format!("{}px", (330.0 * ratio).round())),
                        ),
                )
                .child((!m.text.is_empty()).then(|| h("div.text").children(rich_text(&m.text))))
        }
        "voice" => voice_player(m),
        "file" => {
            let (name, data) = (
                if m.name.is_empty() {
                    "fichier".to_owned()
                } else {
                    m.name.clone()
                },
                m.data.clone(),
            );
            let ext = m.name.rsplit('.').next().unwrap_or("").to_uppercase();
            h("button.file")
                .attr("type", "button")
                .click(move || download(&name, &data))
                .child(h("span.file-icon").child(icon("file")))
                .child(
                    h("span.file-meta")
                        .child(h("strong").text(if m.name.is_empty() {
                            "Fichier"
                        } else {
                            &m.name
                        }))
                        .child(h("small").text(format!("{} · {ext}", bytes(m.size)))),
                )
                .child(icon("download"))
        }
        "brokey" => crate::brocoli::key_card(m),
        "contact" => {
            let code = m.code.clone();
            let name = m.name.clone();
            let saved = core::contact(&code).is_some_and(|c| c.saved);
            let is_me = code == core::my_code();
            h("div.contact-card")
                .child(
                    h("div.row").child(avatar(&code, 40)).child(
                        h("div")
                            .child(h("strong").text(if m.name.is_empty() {
                                pretty(&code)
                            } else {
                                m.name.clone()
                            }))
                            .child(h("small.muted").text(pretty(&code))),
                    ),
                )
                .child((!is_me).then(|| {
                    h("button.btn.ghost")
                        .attr("type", "button")
                        .text(if saved {
                            "Envoyer un message"
                        } else {
                            "Ajouter le contact"
                        })
                        .click(move || {
                            if saved {
                                let c = code.clone();
                                spawn(async move { open_chat(&c).await });
                            } else {
                                open_add_contact(&code, &name);
                            }
                        })
                }))
        }
        _ => h("div.text")
            .class_if("big-emoji", only_emoji(&m.text))
            .children(rich_text(&m.text)),
    }
}

fn bubble(m: &Message, prev: Option<&Message>, chat: Option<&Chat>) -> Element {
    if m.kind == "system" {
        return h("div.system-msg").text(&m.text).el();
    }
    let me = core::my_code();
    let mine = m.from == me;
    let first = prev.is_none_or(|p| {
        p.from != m.from || p.kind == "system" || !same_day(p.at, m.at) || m.at - p.at > 300_000.0
    });
    let mut counts: BTreeMap<String, usize> = BTreeMap::new();
    for e in m.reactions.values() {
        *counts.entry(e.clone()).or_default() += 1;
    }
    let group = chat.is_some_and(|c| c.group);
    let mm = m.clone();
    let menu_btn = h("button.bubble-menu")
        .attr("type", "button")
        .attr("aria-label", "Options du message")
        .child(icon("arrowdown"))
        .el();
    {
        let (m2, b) = (m.clone(), menu_btn.clone());
        listen(&menu_btn, "click", move |e| {
            e.stop_propagation();
            message_menu(&m2, At::El(b.clone()));
        });
    }
    let reactions = (!counts.is_empty()).then(|| {
        let m2 = m.clone();
        h("button.reactions")
            .attr("type", "button")
            .text(counts.keys().take(4).cloned().collect::<String>())
            .child((m.reactions.len() > 1).then(|| h("span").text(m.reactions.len().to_string())))
            .click(move || show_reactions(&m2))
    });
    let b = h("div.bubble")
        .class(if mine { "out" } else { "in" })
        .class_if("first", first)
        .class(&m.kind)
        .attr("data-id", &m.id)
        .child((group && !mine && first).then(|| {
            h("div.sender")
                .style("color", color_of(&m.from))
                .text(core::display_name(&m.from))
        }))
        .child(
            (m.fwd && !m.deleted).then(|| h("div.fwd").child(icon("forward")).text(" Transféré")),
        )
        .child(if m.deleted {
            None
        } else {
            m.reply.as_ref().map(quote)
        })
        .child(capsule_band(m))
        .child(if m.sealed_for(&core::my_code(), now()) {
            sealed(m)
        } else {
            content(m)
        })
        .child(
            h("div.meta")
                .child(m.starred.then(|| h("span.star").text("★")))
                .child((m.bro && !m.deleted).then(|| {
                    h("span.bro")
                        .attr("title", "Chiffré aussi par Brocolis 🥦")
                        .text("🥦")
                }))
                .child(
                    m.exp
                        .map(|_| h("span").attr("title", "Message éphémère").text("⏳")),
                )
                .child((m.edited && !m.deleted).then(|| h("span.edited").text("Modifié")))
                .text(time(m.at))
                .child((mine && !m.deleted).then(|| ticks(m))),
        )
        .child(menu_btn)
        .child(reactions)
        .el();
    // Double-clic : ❤️ ; clic droit ou appui long : le menu ; glisser vers la
    // droite : répondre.
    {
        let m2 = mm.clone();
        listen(&b, "dblclick", move |_| {
            if !m2.deleted {
                let (c, id) = (m2.chat.clone(), m2.id.clone());
                spawn(async move { core::react(&c, &id, "❤️").await });
            }
        });
        let m2 = mm.clone();
        listen(&b, "contextmenu", move |e| {
            e.prevent_default();
            if let Some(me) = e.dyn_ref::<MouseEvent>() {
                message_menu(&m2, At::Point(me.client_x() as f64, me.client_y() as f64));
            }
        });
        let start = Rc::new(Cell::new((0.0f64, 0.0f64, 0.0f64)));
        let press = Rc::new(Cell::new(0i32));
        let (s, p, m2, bb) = (start.clone(), press.clone(), mm.clone(), b.clone());
        listen(&b, "pointerdown", move |e| {
            let Some(pe) = e.dyn_ref::<PointerEvent>() else {
                return;
            };
            if pe.pointer_type() == "mouse" {
                return;
            }
            s.set((pe.client_x() as f64, pe.client_y() as f64, 0.0));
            let (m3, b3, p3) = (m2.clone(), bb.clone(), p.clone());
            p.set(timer(480, move || {
                p3.set(0);
                let _ = call(&window().navigator(), "vibrate", &[15.into()]);
                message_menu(&m3, At::El(b3.clone()));
            }));
        });
        let (s, p, bb) = (start.clone(), press.clone(), b.clone());
        listen(&b, "pointermove", move |e| {
            let (x, y, _) = s.get();
            if x == 0.0 {
                return;
            }
            let Some(pe) = e.dyn_ref::<PointerEvent>() else {
                return;
            };
            let dx = pe.client_x() as f64 - x;
            let dy = pe.client_y() as f64 - y;
            if dx.abs() > 8.0 || dy.abs() > 8.0 {
                clear_timer(p.get());
            }
            s.set((x, y, dx));
            if dx > 0.0 && dy.abs() < 30.0 {
                set_style(&bb, "transform", &format!("translateX({}px)", dx.min(70.0)));
            }
        });
        for ev in ["pointerup", "pointercancel"] {
            let (s, p, bb, m2) = (start.clone(), press.clone(), b.clone(), mm.clone());
            listen(&b, ev, move |_| {
                clear_timer(p.get());
                let (_, _, dx) = s.get();
                if dx > 60.0 && !m2.deleted {
                    start_reply(&m2);
                }
                set_style(&bb, "transform", "");
                s.set((0.0, 0.0, 0.0));
            });
        }
    }
    h("div.msg-row")
        .class(if mine { "out" } else { "in" })
        .class_if("has-react", !counts.is_empty())
        .child(b)
        .el()
}

fn show_reactions(m: &Message) {
    let m = m.clone();
    modal(
        "Réactions",
        move |md| {
            let me = core::my_code();
            h("div.list")
                .children(m.reactions.iter().map(|(code, e)| {
                    let (chat, id, e2, md) = (m.chat.clone(), m.id.clone(), e.clone(), md.clone());
                    let mine = *code == me;
                    h("div.list-item")
                        .child(avatar(code, 40))
                        .child(h("div.grow").text(core::display_name(code)).child(
                            mine.then(|| h("small.muted.block").text("Touchez pour retirer")),
                        ))
                        .child(h("span.big-emoji-sm").text(e).click(move || {
                            if mine {
                                let (c, i, e) = (chat.clone(), id.clone(), e2.clone());
                                md.close();
                                spawn(async move { core::react(&c, &i, &e).await });
                            }
                        }))
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

fn message_menu(m: &Message, at: At) {
    let (chat, id) = (m.chat.clone(), m.id.clone());
    if m.deleted {
        menu(
            vec![danger("trash", "Supprimer pour moi", move || {
                let (c, i) = (chat.clone(), id.clone());
                spawn(async move { core::delete_message(&c, &i, false).await })
            })],
            at,
        );
        return;
    }
    let mine = m.from == core::my_code();
    let m1 = m.clone();
    let m2 = m.clone();
    let m3 = m.clone();
    let m4 = m.clone();
    let m5 = m.clone();
    let m6 = m.clone();
    let m7 = m.clone();
    let text = m.text.clone();
    let can_edit = mine && m.kind == "text" && !m.is_capsule() && now() - m.at < 15.0 * 60_000.0;
    // Une capsule scellée ne se copie, ne se transfère ni ne s'enregistre.
    let sealed = m.sealed_for(&core::my_code(), now());
    let media = !sealed && matches!(m.kind.as_str(), "image" | "file" | "voice");
    let menu_el = menu(
        vec![
            item("reply", "Répondre", move || start_reply(&m1)),
            // Dans un groupe, sur le message d'un autre membre : lui répondre
            // dans votre discussion à deux, sa phrase citée.
            if core::is_group(&m.chat) && !mine && m.from != "system" && !core::blocked(&m.from) {
                let m = m.clone();
                item("person", "Répondre en privé", move || {
                    let m = m.clone();
                    spawn(async move {
                        crate::ui::open_chat(&m.from).await;
                        start_reply(&m);
                    })
                })
            } else {
                None
            },
            if m.text.is_empty() || sealed {
                None
            } else {
                item("copy", "Copier", move || {
                    let t = text.clone();
                    spawn(async move { copy(&t, "Copié").await })
                })
            },
            if sealed {
                None
            } else {
                item("forward", "Transférer", move || {
                    spawn(forward_dialog(m2.clone()))
                })
            },
            item(
                "star",
                if m.starred {
                    "Retirer des favoris"
                } else {
                    "Mettre en favori"
                },
                move || {
                    let (c, i) = (m3.chat.clone(), m3.id.clone());
                    spawn(async move { core::toggle_star(&c, &i).await })
                },
            ),
            {
                let pinned = core::chat(&m.chat).is_some_and(|c| c.pin_msg == m.id);
                let (c, i) = (m.chat.clone(), m.id.clone());
                item(
                    "pin",
                    if pinned { "Désépingler" } else { "Épingler" },
                    move || {
                        let (c, i) = (c.clone(), if pinned { String::new() } else { i.clone() });
                        spawn(async move { core::set_pin(&c, &i).await })
                    },
                )
            },
            if can_edit {
                item("edit", "Modifier", move || start_edit(&m4))
            } else {
                None
            },
            if mine {
                item("info", "Infos", move || message_info(&m5))
            } else {
                None
            },
            if media {
                item("download", "Enregistrer", move || {
                    let name = if !m6.name.is_empty() {
                        m6.name.clone()
                    } else if m6.kind == "image" {
                        "photo-talka.jpg".into()
                    } else {
                        "vocal-talka.webm".into()
                    };
                    download(&name, &m6.data);
                })
            } else {
                None
            },
            danger("trash", "Supprimer", move || delete_dialog(&m7)),
        ],
        at,
    );
    // Les réactions rapides, au-dessus du menu.
    let mine_emoji = m
        .reactions
        .get(&core::my_code())
        .cloned()
        .unwrap_or_default();
    let quick = scope_into("global", || {
        let mut row = h("div.quick-react");
        for e in QUICK {
            let (me, c, i) = (menu_el.clone(), m.chat.clone(), m.id.clone());
            row = row.child(
                h("button")
                    .attr("type", "button")
                    .class_if("on", mine_emoji == e)
                    .text(e)
                    .click(move || {
                        me.remove();
                        remember(e);
                        let (c, i) = (c.clone(), i.clone());
                        spawn(async move { core::react(&c, &i, e).await });
                    }),
            );
        }
        let (me, mm) = (menu_el.clone(), m.clone());
        row.child(
            h("button.more")
                .attr("type", "button")
                .attr("aria-label", "Plus d'emojis")
                .text("+")
                .click(move || {
                    me.remove();
                    react_picker(&mm);
                }),
        )
        .el()
    });
    let _ = menu_el.prepend_with_node_1(&quick);
    place_menu(&menu_el);
}

fn react_picker(m: &Message) {
    let (c, i) = (m.chat.clone(), m.id.clone());
    modal(
        "Réagir",
        move |md| {
            let md = md.clone();
            picker(move |e| {
                let (c, i, e) = (c.clone(), i.clone(), e.to_owned());
                md.close();
                spawn(async move { core::react(&c, &i, &e).await });
            })
            .into()
        },
        ModalOpts::default(),
    );
}

fn delete_dialog(m: &Message) {
    let mine = m.from == core::my_code();
    let (c1, i1, c2, i2) = (m.chat.clone(), m.id.clone(), m.chat.clone(), m.id.clone());
    let mut actions = vec![
        action("Annuler", "ghost", |md| md.close()),
        action("Pour moi", "ghost danger", move |md| {
            let (c, i) = (c1.clone(), i1.clone());
            md.close();
            spawn(async move { core::delete_message(&c, &i, false).await });
        }),
    ];
    if mine {
        actions.push(action("Pour tout le monde", "danger", move |md| {
            let (c, i) = (c2.clone(), i2.clone());
            md.close();
            spawn(async move { core::delete_message(&c, &i, true).await });
        }));
    }
    modal(
        "Supprimer le message ?",
        move |_| {
            h("p.muted")
                .text(if mine {
                    "Vous pouvez le supprimer pour tout le monde, ou seulement sur cet appareil."
                } else {
                    "Il sera supprimé de cet appareil."
                })
                .into()
        },
        ModalOpts {
            actions,
            ..Default::default()
        },
    );
}

fn message_info(m: &Message) {
    let m = m.clone();
    modal(
        "Infos du message",
        move |_| {
            h("div.stack")
                .child(h("p.muted").text(format!("Envoyé {}", full_date(m.at).to_lowercase())))
                .child(h("div.list").children(m.to.iter().map(|code| {
                    let st = if m.read_by.contains(code) {
                        "Lu ✓✓"
                    } else if m.delivered_to.contains(code) {
                        "Distribué ✓✓"
                    } else if m.sent_to.contains(code) {
                        "Envoyé ✓"
                    } else {
                        "En attente 🕓"
                    };
                    h("div.list-item")
                        .child(avatar(code, 36))
                        .child(h("div.grow").text(core::display_name(code)))
                        .child(h("small.muted").text(st))
                })))
                .into()
        },
        ModalOpts::default(),
    );
}

async fn forward_dialog(m: Message) {
    let Some(ids) = pick_chats("Transférer à…").await else {
        return;
    };
    if ids.is_empty() {
        return;
    }
    core::forward(&m, &ids).await;
    toast(if ids.len() > 1 {
        format!("Transféré à {} discussions", ids.len())
    } else {
        "Transféré".into()
    });
}

fn snippet(m: &Message) -> String {
    if m.sealed_for(&core::my_code(), now()) {
        return core::preview(m);
    }
    match m.kind.as_str() {
        "image" | "voice" | "file" | "contact" | "brokey" => core::preview(m),
        _ => crate::jetons::masked(&m.text).chars().take(140).collect(),
    }
}

fn start_reply(m: &Message) {
    with(|c| {
        c.editing = None;
        c.reply = Some(Reply {
            id: m.id.clone(),
            from: m.from.clone(),
            text: snippet(m),
        });
    });
    render_composer(true);
}

fn start_edit(m: &Message) {
    with(|c| {
        c.reply = None;
        c.editing = Some(m.clone());
    });
    render_composer(true);
    if let Some(ta) = textarea() {
        ta.set_value(&m.text);
        autosize(&ta);
    }
}

fn toggle_search() {
    let Some(bar) = el(|c| c.search_bar.clone()) else {
        return;
    };
    if !bar.class_list().contains("hidden") {
        toggle_class(&bar, "hidden", true);
        with(|c| c.search.clear());
        spawn(render(false));
        return;
    }
    scope("conv-search", || {
        let input = h("input.field")
            .attr("type", "search")
            .attr("placeholder", "Rechercher dans la discussion…")
            .attr("aria-label", "Rechercher")
            .el();
        let i = input.clone();
        listen(&input, "input", move |_| {
            let v = value(&i);
            with(|c| {
                c.search = v;
                c.limit = 2000;
            });
            spawn(render(false));
        });
        replace(
            &bar,
            vec![
                input.clone(),
                icon_button("close", "Fermer la recherche", toggle_search).el(),
            ],
        );
        toggle_class(&bar, "hidden", false);
        focus(&input);
    });
}

// ----- La saisie -------------------------------------------------------------------

fn autosize(ta: &HtmlTextAreaElement) {
    let _ = ta.style().set_property("height", "auto");
    let hgt = ta.scroll_height().min(140);
    let _ = ta.style().set_property("height", &format!("{hgt}px"));
}

pub fn render_composer(focus_it: bool) {
    let (Some(id), Some(host)) = (current(), el(|c| c.composer.clone())) else {
        return;
    };
    let Some(chat) = core::chat(&id) else { return };
    scope("composer", || {
        if chat.left {
            replace(
                &host,
                h("div.composer-off")
                    .text("Vous ne pouvez plus envoyer de messages dans ce groupe."),
            );
            return;
        }
        if !chat.group && core::blocked(&chat.id) {
            let c = chat.id.clone();
            replace(
                &host,
                h("div.composer-off")
                    .text("Vous avez bloqué ce contact. ")
                    .child(
                        h("button.link")
                            .attr("type", "button")
                            .text("Débloquer")
                            .click(move || spawn(toggle_block(c.clone()))),
                    ),
            );
            return;
        }
        let old = textarea().map(|t| t.value());
        let ta: HtmlTextAreaElement = h("textarea")
            .attr("rows", "1")
            .attr(
                "placeholder",
                if chat.brocolis.is_empty() {
                    "Message"
                } else {
                    "Message chiffré par Brocolis 🥦"
                },
            )
            .attr("aria-label", "Message")
            .attr("maxlength", "20000")
            .el()
            .unchecked_into();
        ta.set_value(&old.unwrap_or(chat.draft.clone()));
        let send_btn = h("button.send-btn")
            .attr("type", "button")
            .attr("aria-label", "Envoyer")
            .el();
        let update_btn = {
            let (ta, b) = (ta.clone(), send_btn.clone());
            Rc::new(move || {
                let has = !ta.value().trim().is_empty() || with(|c| c.editing.is_some());
                replace(&b, icon(if has { "send" } else { "mic" }));
                let _ =
                    b.set_attribute("aria-label", if has { "Envoyer" } else { "Message vocal" });
            })
        };
        let submit: Rc<dyn Fn()> = {
            let (ta, update_btn) = (ta.clone(), update_btn.clone());
            Rc::new(move || {
                let text = ta.value().trim().to_owned();
                let Some(chat_id) = current() else { return };
                if let Some(editing) = with(|c| c.editing.take()) {
                    ta.set_value("");
                    spawn(async move {
                        if !text.is_empty() && text != editing.text {
                            core::edit_message(&chat_id, &editing.id, &text).await;
                        }
                        render_composer(true);
                    });
                    return;
                }
                if text.is_empty() {
                    spawn(start_recording());
                    return;
                }
                let reply = with(|c| c.reply.take());
                ta.set_value("");
                autosize(&ta);
                update_btn();
                core::typing(&chat_id, false);
                let had_reply = reply.is_some();
                if crate::brocoli::summons(&text) {
                    crate::brocoli::rain();
                }
                spawn(async move {
                    core::send_message(
                        &chat_id,
                        Content {
                            kind: "text".into(),
                            text,
                            reply,
                            ..Default::default()
                        },
                    )
                    .await;
                    if core::settings().sound {
                        sounds::sent();
                    }
                    if had_reply {
                        render_composer(true);
                    }
                    scroll_bottom(true);
                });
            })
        };
        {
            let (t, u) = (ta.clone(), update_btn.clone());
            listen(&ta, "input", move |_| {
                autosize(&t);
                u();
                if !t.value().is_empty()
                    && let Some(id) = current()
                {
                    core::typing(&id, true);
                }
            });
            let s = submit.clone();
            listen(&ta, "keydown", move |e| {
                let Some(k) = e.dyn_ref::<KeyboardEvent>() else {
                    return;
                };
                if k.key() == "Enter"
                    && !k.shift_key()
                    && !k.is_composing()
                    && core::settings().enter_sends
                    && matches_media("(pointer: fine)")
                {
                    e.prevent_default();
                    s();
                } else if k.key() == "Escape" && with(|c| c.reply.is_some() || c.editing.is_some())
                {
                    with(|c| {
                        c.reply = None;
                        c.editing = None;
                    });
                    render_composer(true);
                }
            });
            listen(&ta, "paste", move |e| {
                let files = get(&get(&e, "clipboardData"), "files");
                let file = js_sys::Array::from(&files).get(0);
                if let Ok(f) = file.dyn_into::<web_sys::File>()
                    && f.type_().starts_with("image/")
                {
                    e.prevent_default();
                    spawn(send_image(f));
                }
            });
            listen(&ta, "blur", |_| save_draft());
            let s = submit.clone();
            listen(&send_btn, "click", move |_| s());
        }
        let emoji_btn = {
            let (ta, host, u) = (ta.clone(), host.clone(), update_btn.clone());
            icon_button("smile", "Emojis", move || {
                if let Some(open) = query_in(&host, ".emoji-picker") {
                    open.remove();
                    return;
                }
                let (ta, u) = (ta.clone(), u.clone());
                let p = scope_into("composer", || {
                    picker(move |e| {
                        let start = ta
                            .selection_start()
                            .ok()
                            .flatten()
                            .unwrap_or(ta.value().len() as u32)
                            as usize;
                        let end =
                            ta.selection_end().ok().flatten().unwrap_or(start as u32) as usize;
                        let v: Vec<u16> = ta.value().encode_utf16().collect();
                        let (s, en) = (start.min(v.len()), end.min(v.len()));
                        let next = String::from_utf16_lossy(&v[..s])
                            + e
                            + &String::from_utf16_lossy(&v[en..]);
                        ta.set_value(&next);
                        let pos = (s + e.encode_utf16().count()) as u32;
                        let _ = ta.set_selection_range(pos, pos);
                        u();
                        autosize(&ta);
                    })
                    .el()
                });
                let _ = host.prepend_with_node_1(&p);
            })
        };
        let attach_btn = {
            let b = h("button.icon-btn")
                .attr("type", "button")
                .attr("aria-label", "Joindre")
                .attr("title", "Joindre")
                .child(icon("attach"))
                .el();
            let bb = b.clone();
            listen(&b, "click", move |e| {
                e.stop_propagation();
                menu(
                    vec![
                        item("image", "Galerie photos", || {
                            spawn(async {
                                if let Some(f) = media::pick_file("image/*", None).await {
                                    send_image(f).await;
                                }
                            })
                        }),
                        item("camera", "Appareil photo", || {
                            spawn(async {
                                if let Some(f) =
                                    media::pick_file("image/*", Some("environment")).await
                                {
                                    send_image(f).await;
                                }
                            })
                        }),
                        item("file", "Document", || {
                            spawn(async {
                                if let Some(f) = media::pick_file("*/*", None).await {
                                    send_file(f).await;
                                }
                            })
                        }),
                        item("person", "Contact", share_contact),
                        item("hourglass", "Capsule temporelle", compose_capsule),
                    ],
                    At::El(bb.clone()),
                );
            });
            b
        };
        let (reply, editing) = with(|c| (c.reply.clone(), c.editing.clone()));
        let bar = if let Some(r) = reply {
            Some(
                h("div.reply-bar")
                    .child(
                        h("div.quote")
                            .style("border-color", color_of(&r.from))
                            .child(
                                h("strong")
                                    .style("color", color_of(&r.from))
                                    .text(who(&r.from)),
                            )
                            .child(h("span").text(&r.text)),
                    )
                    .child(icon_button("close", "Annuler la réponse", || {
                        with(|c| c.reply = None);
                        render_composer(true);
                    })),
            )
        } else {
            editing.map(|m| {
                let t = ta.clone();
                h("div.reply-bar")
                    .child(
                        h("div.quote")
                            .child(h("strong").text("Modifier le message"))
                            .child(h("span").text(&m.text)),
                    )
                    .child(icon_button("close", "Annuler", move || {
                        with(|c| c.editing = None);
                        t.set_value("");
                        render_composer(true);
                    }))
            })
        };
        replace(
            &host,
            vec![
                Child::from(bar),
                h("div.composer")
                    .child(
                        h("div.composer-box")
                            .child(emoji_btn)
                            .child(ta.clone().unchecked_into::<Element>())
                            .child(attach_btn),
                    )
                    .child(send_btn)
                    .into(),
            ],
        );
        update_btn();
        let t = ta.clone();
        timer(0, move || autosize(&t));
        if focus_it {
            let _ = ta.focus();
        }
    });
}

async fn send_image(file: web_sys::File) {
    if !file.type_().starts_with("image/") {
        return send_file(file).await;
    }
    let Some((data, w, hgt)) = media::compress_image(&file, 1600.0, 0.82).await else {
        return toast("Cette image ne peut pas être lue.");
    };
    let Some(chat_id) = current() else { return };
    let caption = h("input.field")
        .attr("placeholder", "Ajouter une légende…")
        .attr("maxlength", "1000")
        .el();
    let (c1, d1) = (caption.clone(), data.clone());
    let send: Rc<dyn Fn(&Modal)> = Rc::new(move |md: &Modal| {
        md.close();
        let (chat_id, data, text) = (chat_id.clone(), d1.clone(), value(&c1).trim().to_owned());
        let reply = with(|c| c.reply.take());
        spawn(async move {
            let had = reply.is_some();
            core::send_message(
                &chat_id,
                Content {
                    kind: "image".into(),
                    data,
                    w,
                    h: hgt,
                    text,
                    reply,
                    ..Default::default()
                },
            )
            .await;
            if had {
                render_composer(false);
            }
        });
    });
    let s2 = send.clone();
    let c2 = caption.clone();
    let m = modal(
        "Envoyer une photo",
        move |_| {
            h("div.stack")
                .child(h("img.preview-img").attr("src", &data).attr("alt", ""))
                .child(c2)
                .into()
        },
        ModalOpts {
            wide: true,
            actions: vec![
                action("Annuler", "ghost", |m| m.close()),
                action("Envoyer", "primary", move |m| s2(m)),
            ],
            ..Default::default()
        },
    );
    let mm = m.clone();
    m.scoped(move || {
        listen(&caption, "keydown", move |e| {
            if e.dyn_ref::<KeyboardEvent>()
                .is_some_and(|k| k.key() == "Enter")
            {
                send(&mm);
            }
        })
    });
}

async fn send_file(file: web_sys::File) {
    if file.size() > MAX_FILE {
        return toast("Fichier trop lourd (3 Mo au plus).");
    }
    let Some(data) = media::data_url(&file).await else {
        return;
    };
    let Some(chat_id) = current() else { return };
    let name: String = file.name().chars().take(120).collect();
    core::send_message(
        &chat_id,
        Content {
            kind: "file".into(),
            data,
            name,
            mime: file.type_(),
            size: file.size(),
            ..Default::default()
        },
    )
    .await;
}

/// `AAAA-MM-JJTHH:MM`, l'heure locale d'un champ `datetime-local`.
fn local_input(ts: f64) -> String {
    let d = js_sys::Date::new(&wasm_bindgen::JsValue::from_f64(ts));
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}",
        d.get_full_year(),
        d.get_month() + 1,
        d.get_date(),
        d.get_hours(),
        d.get_minutes()
    )
}

/// Une heure locale (`h`:00), `days` jours après aujourd'hui.
fn at_hour(days: u32, h: u32) -> f64 {
    let d = js_sys::Date::new(&wasm_bindgen::JsValue::from_f64(now()));
    d.set_date(d.get_date() + days);
    d.set_hours(h);
    d.set_minutes(0);
    d.set_seconds(0);
    d.set_milliseconds(0);
    d.get_time()
}

/// Écrire une capsule temporelle : un message scellé jusqu'à l'heure choisie.
fn compose_capsule() {
    let Some(chat_id) = current() else { return };
    let text = h("textarea.field")
        .attr("rows", "4")
        .attr("maxlength", "4000")
        .attr("placeholder", "Ce que vous voulez qu'on lise plus tard…")
        .attr("aria-label", "Message de la capsule")
        .el();
    let when = h("input.field")
        .attr("type", "datetime-local")
        .attr("aria-label", "Heure d'ouverture")
        .attr("min", local_input(now() + 60_000.0))
        .prop("value", local_input(at_hour(1, 9)))
        .el();
    let err = h("p.error").el();
    let (t, w, e) = (text.clone(), when.clone(), err.clone());
    let next_year = {
        let d = js_sys::Date::new(&wasm_bindgen::JsValue::from_f64(now()));
        js_sys::Date::new_with_year_month_day(d.get_full_year() + 1, 0, 1).get_time()
    };
    let presets: Vec<(&str, f64)> = vec![
        ("Dans 1 heure", now() + 3_600_000.0),
        ("Demain 9 h", at_hour(1, 9)),
        ("Dans une semaine", at_hour(7, 9)),
        ("Au Nouvel An", next_year),
        ("Dans un an", at_hour(365, 9)),
    ];
    modal(
        "Capsule temporelle",
        move |_| {
            let chips = h("div.capsule-presets").children(presets.iter().map(|(label, ts)| {
                let (w, ts) = (w.clone(), *ts);
                h("button.chip")
                    .attr("type", "button")
                    .text(*label)
                    .click(move || set_value(&w, &local_input(ts)))
            }));
            h("div.stack")
                .child(h("p.muted").text(
                    "Un message scellé : il part maintenant, mais ne s'ouvre qu'à l'heure choisie. \
                     Jusque-là, on ne voit qu'une enveloppe et un compte à rebours.",
                ))
                .child(t.clone())
                .child(h("label.muted.small").text("S'ouvre le"))
                .child(w.clone())
                .child(chips)
                .child(e.clone())
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Annuler", "ghost", |m| m.close()),
                action("Sceller et envoyer", "primary", move |m| {
                    let body = value(&text).trim().to_owned();
                    let open_at =
                        js_sys::Date::new(&wasm_bindgen::JsValue::from_str(&value(&when)))
                            .get_time();
                    if body.is_empty() {
                        err.set_text_content(Some("Écrivez le message de la capsule."));
                        return;
                    }
                    if !open_at.is_finite() || open_at < now() + 60_000.0 {
                        err.set_text_content(Some("Choisissez une heure d'ouverture à venir."));
                        return;
                    }
                    if open_at > now() + core::CAPSULE_MAX_MS {
                        err.set_text_content(Some(
                            "Une capsule s'ouvre au plus tard dans dix ans.",
                        ));
                        return;
                    }
                    m.close();
                    let chat_id = chat_id.clone();
                    spawn(async move {
                        core::send_message(
                            &chat_id,
                            Content {
                                kind: "text".into(),
                                text: body,
                                open_at,
                                ..Default::default()
                            },
                        )
                        .await;
                        toast("Capsule scellée ⏳");
                    });
                }),
            ],
            ..Default::default()
        },
    );
}

fn share_contact() {
    let me = core::my_code();
    let mut items = vec![(
        me.clone(),
        format!("{} (vous)", core::profile().name),
        core::profile().name,
    )];
    for c in core::contacts().into_iter().filter(|c| c.saved) {
        let n = core::display_name(&c.code);
        items.push((c.code.clone(), n.clone(), n));
    }
    modal(
        "Partager un contact",
        move |md| {
            h("div.list")
                .children(items.iter().map(|(code, label, name)| {
                    let (code2, name, md) = (code.clone(), name.clone(), md.clone());
                    h("button.list-item")
                        .attr("type", "button")
                        .child(avatar(code, 40))
                        .child(
                            h("div.grow")
                                .child(h("div").text(label))
                                .child(h("small.muted").text(pretty(code))),
                        )
                        .click(move || {
                            md.close();
                            let (code, name) = (code2.clone(), name.clone());
                            if let Some(chat_id) = current() {
                                spawn(async move {
                                    core::send_message(
                                        &chat_id,
                                        Content {
                                            kind: "contact".into(),
                                            code,
                                            name,
                                            ..Default::default()
                                        },
                                    )
                                    .await;
                                });
                            }
                        })
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

// ----- Les vocaux ------------------------------------------------------------------

async fn start_recording() {
    if !media::can_record() {
        return toast("Votre navigateur ne permet pas d'enregistrer.");
    }
    let Some(rec) = Recorder::start().await else {
        return toast("Micro refusé : autorisez-le dans les réglages du navigateur.");
    };
    let (Some(host), Some(chat_id)) = (el(|c| c.composer.clone()), current()) else {
        let _ = rec.stop(true).await;
        return;
    };
    let time_el = h("span.rec-time").text("0:00").el();
    let started = now();
    let t = time_el.clone();
    let iv = interval(250, move || {
        t.set_text_content(Some(&fmt_dur((now() - started) / 1000.0)))
    });
    RECORDER.with(|r| *r.borrow_mut() = Some((rec, iv)));
    let finish = move |cancel: bool| {
        let chat_id = chat_id.clone();
        spawn(async move {
            let Some((rec, iv)) = RECORDER.with(|r| r.borrow_mut().take()) else {
                return;
            };
            clear_interval(iv);
            let out = rec.stop(cancel).await;
            render_composer(false);
            if let Some((data, dur, mime)) = out {
                core::send_message(
                    &chat_id,
                    Content {
                        kind: "voice".into(),
                        data,
                        dur,
                        mime,
                        ..Default::default()
                    },
                )
                .await;
                if core::settings().sound {
                    sounds::sent();
                }
            }
        });
    };
    let f1 = finish.clone();
    let f2 = finish.clone();
    scope("composer", || {
        replace(
            &host,
            h("div.composer.recording")
                .child(icon_button("trash", "Annuler", move || f1(true)).class("danger"))
                .child(
                    h("div.rec-box")
                        .child(h("span.rec-dot"))
                        .child(time_el)
                        .child(h("span.muted").text("Enregistrement…")),
                )
                .child(
                    h("button.send-btn")
                        .attr("type", "button")
                        .attr("aria-label", "Envoyer le vocal")
                        .child(icon("send"))
                        .click(move || f2(false)),
                ),
        );
    });
    timer(5 * 60 * 1000, move || {
        if RECORDER.with(|r| r.borrow().is_some()) {
            finish(false);
        }
    });
}

async fn stop_recording(cancel: bool) {
    if let Some((rec, iv)) = RECORDER.with(|r| r.borrow_mut().take()) {
        clear_interval(iv);
        let _ = rec.stop(cancel).await;
    }
}
