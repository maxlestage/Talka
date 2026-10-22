//! L'interface : le démarrage, la barre latérale (discussions, actus,
//! appels), les fiches de contact et de groupe, les réglages, le code Talka.

use std::cell::{Cell, RefCell};
use std::collections::HashSet;
use std::rc::Rc;

use wasm_bindgen::JsCast;
use web_sys::{Element, MouseEvent, PointerEvent};

use crate::calls;
use crate::conv;
use crate::core::{self, Ev};
use crate::crypto::{normalize, pretty, safety_number};
use crate::dom::*;
use crate::lock;
use crate::media;
use crate::model::{Chat, MAX_GROUP_DESC, Message, StatusItem, fold, group_desc};
use crate::net::{self, State};
use crate::sounds;
use crate::store;

#[derive(Default)]
struct Ui {
    side: Option<Element>,
    main: Option<Element>,
    body: Option<Element>,
    net: Option<Element>,
    tabs: Option<Element>,
    title: Option<Element>,
    fab: Option<Element>,
    tab: String,
    filter: String,
    query: String,
    /// Les messages trouvés pour une recherche (la requête, les messages).
    found: Option<(String, Vec<Message>)>,
    archived: bool,
    lock: bool,
}

thread_local! {
    static U: RefCell<Ui> = RefCell::new(Ui { tab: "chats".into(), filter: "all".into(), ..Default::default() });
    static SIDE_QUEUED: Cell<bool> = const { Cell::new(false) };
    static MOUNTED: Cell<bool> = const { Cell::new(false) };
}

fn with<T>(f: impl FnOnce(&mut Ui) -> T) -> T {
    U.with(|u| f(&mut u.borrow_mut()))
}

fn root() -> Element {
    by_id("root").expect("#root")
}

// ----- Petits éléments ----------------------------------------------------------

const COLORS: [&str; 10] = [
    "#e542a3", "#1f7aec", "#d36b00", "#02a698", "#7f66ff", "#c4532d", "#008069", "#a62c71",
    "#5e47de", "#b7820b",
];

pub fn color_of(code: &str) -> &'static str {
    let n = code
        .chars()
        .fold(0u32, |n, c| n.wrapping_mul(31).wrapping_add(c as u32));
    COLORS[(n % COLORS.len() as u32) as usize]
}

/// L'avatar d'un code, d'un groupe ou de soi : photo, sinon initiale colorée.
/// L'avatar d'un contact avec sa pastille : verte s'il est en ligne, grise
/// sinon. Ni pour un groupe, ni pour soi, ni pour un contact bloqué, ni si
/// la pastille est désactivée dans les réglages.
pub fn avatar_presence(id: &str, size: u32) -> Element {
    let shown = core::settings().presence_dot
        && id != core::my_code()
        && !core::is_group(id)
        && !core::blocked(id);
    if !shown {
        return avatar(id, size);
    }
    let online = matches!(core::presence(id), Some((true, _)));
    h("span.avatar-wrap")
        .child(avatar(id, size))
        .child(
            h("span.presence-dot")
                .class_if("on", online)
                .attr("role", "img")
                .attr("aria-label", if online { "En ligne" } else { "Hors ligne" }),
        )
        .el()
}

pub fn avatar(id: &str, size: u32) -> Element {
    let px = format!("{size}px");
    let font = format!("{}px", (size as f64 * 0.42).round());
    let sized = |e: El| {
        e.style("width", &px)
            .style("height", &px)
            .style("font-size", &font)
    };
    if let Some(chat) = core::chat(id).filter(|c| c.group) {
        return if chat.avatar.is_empty() {
            sized(h("span.avatar.group").child(icon("group"))).el()
        } else {
            sized(h("img.avatar").attr("src", &chat.avatar).attr("alt", "")).el()
        };
    }
    let src = core::avatar_of(id);
    if !src.is_empty() {
        return sized(h("img.avatar").attr("src", &src).attr("alt", "")).el();
    }
    let name = if id == core::my_code() {
        core::profile().name
    } else {
        core::display_name(id)
    };
    let letter = name
        .trim_start_matches('~')
        .trim()
        .chars()
        .next()
        .map(|c| c.to_uppercase().to_string())
        .unwrap_or_default();
    let e = sized(h("span.avatar").style("background", color_of(id)));
    if letter.chars().next().is_some_and(char::is_alphanumeric) {
        e.text(letter).el()
    } else {
        e.child(icon("person")).el()
    }
}

fn ring(el: Element, seen: bool) -> Element {
    h("span.ring").class_if("seen", seen).child(el).el()
}

// ----- Le démarrage --------------------------------------------------------------

/// Un autre onglet a pris la main (voir `tabs`).
pub fn inactive() {
    crate::conv::close();
    replace(
        &root(),
        h("div.welcome").child(
            h("div.welcome-card")
                .child(
                    h("img")
                        .attr("src", asset("logo.svg"))
                        .attr("alt", "")
                        .attr("width", "72")
                        .attr("height", "72"),
                )
                .child(h("h1").text("Talka est ouvert dans un autre onglet"))
                .child(h("p.muted").text(
                    "Un seul onglet à la fois garde vos messages à jour. Continuez dans l'autre onglet, ou reprenez ici.",
                ))
                .child(
                    h("button.btn.primary")
                        .attr("type", "button")
                        .text("Utiliser ici")
                        .click(|| {
                            let _ = window().location().reload();
                        }),
                ),
        ),
    );
    toggle_class(&root(), "boot", false);
}

fn fatal(text: &str) {
    replace(
        &root(),
        h("div.welcome").child(
            h("div.welcome-card")
                .child(
                    h("img")
                        .attr("src", asset("logo.svg"))
                        .attr("alt", "")
                        .attr("width", "72")
                        .attr("height", "72"),
                )
                .child(h("h1").text("Talka ne peut pas démarrer"))
                .child(h("p.muted").text(text)),
        ),
    );
    toggle_class(&root(), "boot", false);
}

/// L'adresse d'un fichier du site (sous le chemin de GitHub Pages).
pub fn asset(path: &str) -> String {
    format!("{}/{path}", meta("talka-base"))
}

/// Le lien d'invitation : `/c/<empreinte>/<code>` sur le serveur,
/// `/app/#k=<empreinte>&add=<code>` sur GitHub Pages. L'empreinte de nos
/// clés (`crypto::fingerprint`) est vérifiée par qui l'ouvre ; le code reste
/// à la fin, pour les versions qui ne la connaissent pas.
pub fn invite_link(code: &str) -> String {
    let origin = window().location().origin().unwrap_or_default();
    let me = core::me();
    let fp = if me.code == code {
        crate::crypto::fingerprint(&me.sign, &me.dh)
    } else {
        String::new()
    };
    match (meta("talka-static") == "1", fp.is_empty()) {
        (true, false) => format!("{origin}{}/app/#k={fp}&add={code}", meta("talka-base")),
        (true, true) => format!("{origin}{}/app/#add={code}", meta("talka-base")),
        (false, false) => format!("{origin}/c/{fp}/{code}"),
        (false, true) => format!("{origin}/c/{code}"),
    }
}

fn pending_invite() -> Option<String> {
    let hash = window().location().hash().unwrap_or_default();
    let code = hash.split("add=").nth(1)?.split('&').next()?.to_owned();
    let fp = hash
        .split("k=")
        .nth(1)
        .and_then(|f| f.split('&').next())
        .map(str::to_owned);
    if let Ok(h) = window().history() {
        let path = window().location().pathname().unwrap_or_default();
        let _ = h.replace_state_with_url(&wasm_bindgen::JsValue::NULL, "", Some(&path));
    }
    let code = normalize(&code)?;
    if let Some(fp) = fp {
        core::expect_fingerprint(&code, &fp);
    }
    Some(code)
}

/// L'étape affichée par l'écran de chargement (tant qu'il est là).
fn boot_step(text: &str) {
    if let Some(el) = by_id("boot-step") {
        el.set_text_content(Some(text));
    }
}

/// Démarre l'application : verrouillage, accueil, ou discussions.
pub async fn boot() {
    track_height();
    crate::tabs::claim().await;
    // La mémoire protégée par le code ou une passkey : l'ouvrir d'abord.
    let mut pin = None;
    match store::lock().await {
        store::Lock::None => {}
        store::Lock::Code => {
            replace(&root(), Child::None);
            pin = Some(lock::unlock_screen().await);
        }
        store::Lock::Passkey { id, salt } => {
            replace(&root(), Child::None);
            crate::passkey::unlock_screen(&id, &salt).await;
        }
    }
    boot_step("Lecture de vos messages…");
    if store::open().await.is_err() {
        fatal(
            "Ce navigateur ne permet pas à Talka de garder vos messages (IndexedDB). Essayez une version récente de Chrome, Firefox, Safari ou Edge, hors navigation privée.",
        );
        return;
    }
    let invite = pending_invite();
    sounds::unlock();
    boot_step("Préparation de vos discussions…");
    if core::has_account().await {
        if pin.is_none() && lock::has_pin().await {
            replace(&root(), Child::None);
            pin = Some(lock::lock_screen().await);
            with(|u| u.lock = true);
        }
        start_core().await;
        if let Some(pin) = pin {
            core::unlock_brocolis(&pin).await;
        }
        mount();
    } else {
        let created = welcome().await;
        start_core().await;
        mount();
        if created {
            reveal_code().await;
        }
    }
    auto_lock();
    if let Some(code) = invite.filter(|c| *c != core::my_code()) {
        if core::contact(&code).is_some_and(|c| c.saved) {
            open_chat(&code).await;
        } else {
            open_add_contact(&code, "");
        }
    }
}

async fn start_core() {
    core::start().await;
}

/// Le verrouillage automatique, quand on quitte Talka.
fn auto_lock() {
    let hidden_at = Rc::new(Cell::new(0.0));
    listen_forever(&document(), "visibilitychange", move |_| {
        if !visible() {
            hidden_at.set(now());
            return;
        }
        let since = hidden_at.get();
        if since == 0.0 {
            return;
        }
        spawn(async move {
            if !lock::has_pin().await || query(".lock-screen").is_some() {
                return;
            }
            if now() - since >= core::settings().lock_after as f64 * 1000.0 {
                lock_now().await;
            }
        });
    });
}

async fn lock_now() {
    let body = document().body().expect("body");
    toggle_class(&body, "locked", true);
    let pin = lock::lock_screen().await;
    toggle_class(&body, "locked", false);
    core::unlock_brocolis(&pin).await;
}

// ----- Le squelette ----------------------------------------------------------------

fn mount() {
    let side = h("aside.sidebar").el();
    let main = h("section.main").el();
    let r = root();
    toggle_class(&r, "boot", false);
    replace(&r, h("div.layout").child(side.clone()).child(main.clone()));
    with(|u| {
        u.side = Some(side);
        u.main = Some(main);
    });
    MOUNTED.with(|m| m.set(true));
    apply_theme();
    render_side();
    render_empty_main();
    listen_forever(&window(), "popstate", |_| {
        if conv::current().is_some() && history_tag().is_empty() {
            close_chat();
        }
    });
    listen_forever(&document(), "visibilitychange", |_| {
        if visible() {
            spawn(crate::push::clear());
            if let Some(id) = conv::current() {
                spawn(async move { core::mark_read(&id).await });
            }
        }
        update_badge();
    });
}

/// Ce que le cœur signale.
pub fn event(e: Ev) {
    if !MOUNTED.with(Cell::get) {
        return;
    }
    match e {
        Ev::Chats => queue_side(),
        Ev::Profile => {
            queue_side();
            refresh_devices_row();
        }
        Ev::Contacts(code) => {
            queue_side();
            spawn(crate::push::contacts_changed());
            let open = conv::current();
            if open.as_deref() == Some(code.as_str()) || open.as_deref().is_some_and(core::is_group)
            {
                conv::refresh_header();
            }
        }
        Ev::Messages(id) => {
            if conv::current().as_deref() == Some(id.as_str()) {
                conv::queue_render();
            }
            // Une recherche affichée retrouve aussi ce qui vient d'arriver
            // (ou ne montre plus ce qui vient d'être supprimé).
            with(|u| u.found = None);
            queue_side();
        }
        Ev::Presence(code) => {
            if conv::current().as_deref() == Some(code.as_str()) {
                conv::refresh_header();
            }
            // La pastille de la liste des discussions.
            if core::settings().presence_dot {
                queue_side();
            }
        }
        Ev::Typing(id) => {
            if conv::current().as_deref() == Some(id.as_str()) {
                conv::refresh_header();
            }
            queue_side();
        }
        Ev::Net => render_net(),
        Ev::Statuses => {
            if with(|u| u.tab == "status") {
                queue_side();
            }
            spawn(update_status_dot());
        }
        Ev::Calls => {
            if with(|u| u.tab == "calls") {
                queue_side();
            }
        }
        Ev::Settings => apply_theme(),
        Ev::Incoming {
            chat,
            message,
            active,
        } => on_incoming(chat, message, active),
        Ev::Reaction { chat, from, emoji } => {
            if conv::current().as_deref() != Some(chat.id.as_str()) {
                notify(
                    &chat,
                    &format!(
                        "{} a réagi {emoji} à votre message",
                        core::display_name(&from)
                    ),
                );
            }
        }
        Ev::CallSignal { from, body, live } => spawn(calls::on_signal(from, body, live)),
    }
}

pub fn apply_theme() {
    let s = core::settings();
    let dark =
        s.theme == "dark" || (s.theme != "light" && matches_media("(prefers-color-scheme: dark)"));
    let html = document().document_element().expect("html");
    let _ = html.set_attribute("data-theme", if dark { "dark" } else { "light" });
    set_style(&html, "--msg-size", &format!("{}px", s.font_size.max(12)));
    if let Some(m) = query("meta[name=\"theme-color\"]") {
        let _ = m.set_attribute("content", if dark { "#202c33" } else { "#008069" });
    }
}

fn queue_side() {
    if SIDE_QUEUED.with(|q| q.replace(true)) {
        return;
    }
    timer(16, || {
        SIDE_QUEUED.with(|q| q.set(false));
        spawn(render_side_body());
        update_badge();
    });
}

fn render_empty_main() {
    let Some(main) = with(|u| u.main.clone()) else {
        return;
    };
    scope("main", || {
        replace(
            &main,
            h("div.empty-main")
                .child(h("img").attr("src", asset("logo.svg")).attr("alt", "").attr("width", "96").attr("height", "96"))
                .child(h("h1").text("Talka pour le Web"))
                .child(h("p").text("Envoyez et recevez des messages chiffrés de bout en bout. Votre code remplace votre numéro, et tout reste sur cet appareil."))
                .child(h("button.btn.primary").attr("type", "button").child(icon("qr")).text(format!(" Mon code : {}", pretty(&core::my_code()))).click(show_my_code))
                .child(h("p.muted.small").child(icon("lock")).text(" Chiffré de bout en bout")),
        );
    });
}

fn update_badge() {
    let n: u32 = core::chats()
        .iter()
        .filter(|c| !c.muted && !c.archived)
        .map(|c| c.unread)
        .sum();
    document().set_title(&if n > 0 {
        format!("({n}) Talka")
    } else {
        "Talka".into()
    });
    let nav = window().navigator();
    let _ = if n > 0 {
        call(&nav, "setAppBadge", &[n.into()])
    } else {
        call(&nav, "clearAppBadge", &[])
    };
}

// ----- La barre latérale --------------------------------------------------------

const TABS: [(&str, &str, &str); 3] = [
    ("chats", "chat", "Discussions"),
    ("status", "status", "Actus"),
    ("calls", "phone", "Appels"),
];

fn render_side() {
    let Some(side) = with(|u| u.side.clone()) else {
        return;
    };
    let body = h("div.side-body").el();
    let net_el = h("div.net-banner.hidden").el();
    let title = h("h1").text("Talka").el();
    let tab = with(|u| u.tab.clone());
    scope("side", || {
        let tabs = h("nav.tabs")
            .attr("aria-label", "Sections")
            .children(TABS.iter().map(|(key, ic, label)| {
                h("button.tab")
                    .class_if("on", tab == *key)
                    .attr("type", "button")
                    .attr("data-tab", key)
                    .child(h("span.tab-icon").child(icon(ic)).child(h("i.tab-dot")))
                    .child(h("span").text(label))
                    .click(move || switch_tab(key))
            }));
        let more = h("button.icon-btn")
            .attr("type", "button")
            .attr("aria-label", "Menu")
            .attr("title", "Menu")
            .child(icon("more"))
            .el();
        let m2 = more.clone();
        listen(&more, "click", move |e| {
            e.stop_propagation();
            let locked = with(|u| u.lock);
            menu(
                vec![
                    item("group", "Nouveau groupe", || spawn(open_new_group())),
                    item("person", "Ajouter un contact", || open_add_contact("", "")),
                    item("star", "Messages favoris", || spawn(open_starred())),
                    if core::chats()
                        .iter()
                        .any(|c| c.unread > 0 || c.marked_unread)
                    {
                        item("check", "Tout marquer comme lu", || {
                            spawn(async {
                                let n = core::mark_all_read().await;
                                toast(if n > 1 {
                                    format!("{n} discussions marquées comme lues")
                                } else {
                                    "Discussion marquée comme lue".into()
                                });
                            })
                        })
                    } else {
                        None
                    },
                    item("archive", "Discussions archivées", || {
                        with(|u| u.archived = true);
                        switch_tab("chats");
                    }),
                    item("settings", "Réglages", || spawn(open_settings())),
                    if locked {
                        item("lock", "Verrouiller", || spawn(lock_now()))
                    } else {
                        None
                    },
                ],
                At::El(m2.clone()),
            );
        });
        let tabs = tabs.el();
        // Sur téléphone, l'action principale de l'onglet, sous le pouce.
        let fab = h("button.fab").attr("type", "button").el();
        listen(&fab, "click", |e| {
            e.stop_propagation();
            match with(|u| u.tab.clone()).as_str() {
                "status" => new_text_status(),
                "calls" => spawn(async {
                    if let Some(code) = pick_contacts("Appeler…", &[], "Appeler")
                        .await
                        .and_then(|l| l.into_iter().next())
                    {
                        calls::start_call(&code, false).await;
                    }
                }),
                _ => open_new_chat(),
            }
        });
        replace(
            &side,
            vec![
                h("header.side-head")
                    .child(
                        h("button.me-btn")
                            .attr("type", "button")
                            .attr("aria-label", "Mon profil")
                            .child(avatar(&core::my_code(), 40))
                            .click(|| spawn(open_settings())),
                    )
                    .child(title.clone())
                    .child(
                        h("div.side-actions")
                            .child(icon_button("qr", "Mon code", show_my_code))
                            .child(icon_button("plus", "Nouvelle discussion", open_new_chat))
                            .child(more),
                    )
                    .el(),
                net_el.clone(),
                body.clone(),
                fab.clone(),
                tabs.clone(),
            ],
        );
        with(|u| {
            u.body = Some(body);
            u.net = Some(net_el);
            u.tabs = Some(tabs);
            u.title = Some(title);
            u.fab = Some(fab);
        });
    });
    update_fab();
    render_net();
    spawn(render_side_body());
}

fn switch_tab(key: &str) {
    with(|u| {
        u.tab = key.to_owned();
        u.query.clear();
    });
    if let Some(tabs) = with(|u| u.tabs.clone()) {
        for b in query_all_in(&tabs, "button") {
            toggle_class(
                &b,
                "on",
                b.get_attribute("data-tab").as_deref() == Some(key),
            );
        }
    }
    if let Some(t) = with(|u| u.title.clone()) {
        t.set_text_content(Some(match key {
            "status" => "Actus",
            "calls" => "Appels",
            _ => "Talka",
        }));
    }
    update_fab();
    spawn(render_side_body());
}

/// Le bouton flottant suit l'onglet : nouvelle discussion, actu, appel.
fn update_fab() {
    let Some(fab) = with(|u| u.fab.clone()) else {
        return;
    };
    let (ic, label) = match with(|u| u.tab.clone()).as_str() {
        "status" => ("edit", "Nouvelle actu"),
        "calls" => ("phone", "Nouvel appel"),
        _ => ("plus", "Nouvelle discussion"),
    };
    replace(&fab, icon(ic));
    let _ = fab.set_attribute("aria-label", label);
    let _ = fab.set_attribute("title", label);
}

fn render_net() {
    let Some(n) = with(|u| u.net.clone()) else {
        return;
    };
    match net::state() {
        State::Online => toggle_class(&n, "hidden", true),
        st => {
            toggle_class(&n, "hidden", false);
            n.set_text_content(Some(if st == State::Connecting {
                "⏳ Connexion…"
            } else {
                "📴 Hors ligne : vos messages partiront au retour du réseau."
            }));
        }
    }
}

async fn render_side_body() {
    let tab = with(|u| u.tab.clone());
    let focused = document()
        .active_element()
        .is_some_and(|e| e.class_list().contains("side-search"));
    match tab.as_str() {
        "status" => render_statuses().await,
        "calls" => render_calls().await,
        _ => render_chat_list(),
    }
    if focused
        && let Some(b) = with(|u| u.body.clone())
        && let Some(s) = query_in(&b, ".side-search")
    {
        focus(&s);
        if let Some(i) = s.dyn_ref::<web_sys::HtmlInputElement>() {
            let n = i.value().len() as u32;
            let _ = i.set_selection_range(n, n);
        }
    }
    update_status_dot().await;
}

async fn update_status_dot() {
    let me = core::my_code();
    let fresh = core::statuses()
        .await
        .iter()
        .any(|s| s.owner != me && !s.seen && !core::blocked(&s.owner));
    if let Some(tabs) = with(|u| u.tabs.clone())
        && let Some(dot) = query_in(&tabs, "[data-tab=\"status\"] .tab-dot")
    {
        toggle_class(&dot, "on", fresh);
    }
}

fn search_box(placeholder: &str) -> El {
    let q = with(|u| u.query.clone());
    let input = h("input.side-search")
        .attr("type", "search")
        .attr("placeholder", placeholder)
        .attr("aria-label", placeholder)
        .prop("value", q)
        .el();
    let i = input.clone();
    listen(&input, "input", move |_| {
        with(|u| {
            u.query = value(&i);
            u.found = None;
        });
        spawn(render_side_body());
    });
    h("div.search-wrap").child(icon("search")).child(input)
}

/// Un message trouvé : sa discussion, sa date, et le passage ; un clic
/// ouvre la discussion sur lui.
fn found_item(m: &Message) -> Element {
    let (chat, id) = (m.chat.clone(), m.id.clone());
    let title = core::chat(&m.chat)
        .map(|c| core::chat_title(&c))
        .unwrap_or_default();
    let who = if m.from == core::my_code() {
        "Vous : ".to_owned()
    } else if core::is_group(&m.chat) {
        format!("{} : ", core::display_name(&m.from))
    } else {
        String::new()
    };
    h("button.chat-item.found-item")
        .attr("type", "button")
        .attr("role", "listitem")
        .child(avatar(&m.chat, 40))
        .child(
            h("div.chat-item-main")
                .child(
                    h("div.chat-item-top")
                        .child(h("span.chat-item-title").text(&title))
                        .child(h("small").text(list_time(m.at))),
                )
                .child(h("div.chat-item-bottom").child(
                    h("span.chat-item-preview").text(format!("{who}{}", core::readable(m))),
                )),
        )
        .click(move || {
            let (chat, id) = (chat.clone(), id.clone());
            spawn(async move {
                open_chat(&chat).await;
                conv::reveal(&id);
            })
        })
        .el()
}

fn render_chat_list() {
    let Some(body) = with(|u| u.body.clone()) else {
        return;
    };
    let (q, filter, archived) = with(|u| (fold(u.query.trim()), u.filter.clone(), u.archived));
    let q_code = q.replace('-', "");
    let all: Vec<Chat> = core::chats()
        .into_iter()
        .filter(|c| !core::blocked(&c.id) || c.last.is_some())
        .collect();
    let archived_count = all.iter().filter(|c| c.archived).count();
    let mut chats: Vec<Chat> = all
        .into_iter()
        .filter(|c| c.archived == archived)
        .filter(|c| filter != "unread" || c.unread > 0 || c.marked_unread)
        .filter(|c| filter != "groups" || c.group)
        .filter(|c| {
            q.is_empty()
                || fold(&core::chat_title(c)).contains(&q)
                || c.id.to_lowercase().contains(&q_code)
                || c.last.as_ref().is_some_and(|l| fold(&l.text).contains(&q))
        })
        .collect();
    chats.sort_by(|a, b| {
        b.pinned
            .cmp(&a.pinned)
            .then(b.last_at.total_cmp(&a.last_at))
    });
    scope("side-body", || {
        let chips = h("div.chips").children(
            [
                ("all", "Toutes"),
                ("unread", "Non lues"),
                ("groups", "Groupes"),
            ]
            .iter()
            .map(|(k, label)| {
                h("button.chip")
                    .class_if("on", filter == *k)
                    .attr("type", "button")
                    .text(label)
                    .click(move || {
                        with(|u| u.filter = k.to_string());
                        spawn(render_side_body());
                    })
            }),
        );
        let items: Vec<Element> = chats.iter().map(chat_item).collect();
        let extra: Vec<Element> = if q.is_empty() {
            Vec::new()
        } else {
            core::contacts()
                .into_iter()
                .filter(|c| c.saved && core::chat(&c.code).is_none())
                .filter(|c| {
                    fold(&core::display_name(&c.code)).contains(&q)
                        || c.code.to_lowercase().contains(&q_code)
                })
                .map(|c| {
                    let code = c.code.clone();
                    contact_item(&c.code, move || {
                        let code = code.clone();
                        spawn(async move { open_chat(&code).await })
                    })
                    .el()
                })
                .collect()
        };
        // Les messages de toutes les discussions : cherchés une fois par
        // requête, en arrière-plan, puis affichés sous les discussions.
        let messages: Vec<Message> = match with(|u| u.found.clone()) {
            Some((key, found)) if key == q => found,
            _ => {
                if q.chars().count() >= 2 {
                    with(|u| u.found = Some((q.clone(), Vec::new())));
                    let key = q.clone();
                    spawn(async move {
                        let found = core::search_messages(&key, 40).await;
                        let current = with(|u| fold(u.query.trim()));
                        if current == key {
                            with(|u| u.found = Some((key, found)));
                            render_side_body().await;
                        }
                    });
                }
                Vec::new()
            }
        };
        let found_items: Vec<Element> = messages.iter().map(found_item).collect();
        let empty = items.is_empty() && extra.is_empty() && found_items.is_empty();
        let typed_code = normalize(&q).filter(|c| core::contact(c).is_none());
        let mut nodes: Vec<Child> =
            vec![search_box("Rechercher ou démarrer une discussion").into()];
        if archived {
            nodes.push(
                h("button.archived-row")
                    .attr("type", "button")
                    .child(icon("back"))
                    .child(h("strong").text("Archivées"))
                    .click(|| {
                        with(|u| u.archived = false);
                        spawn(render_side_body());
                    })
                    .into(),
            );
        } else {
            nodes.push(chips.into());
            if archived_count > 0 && filter == "all" && q.is_empty() {
                nodes.push(
                    h("button.archived-row")
                        .attr("type", "button")
                        .child(icon("archive"))
                        .child(h("span").text("Archivées"))
                        .child(h("small").text(archived_count.to_string()))
                        .click(|| {
                            with(|u| u.archived = true);
                            spawn(render_side_body());
                        })
                        .into(),
                );
            }
        }
        let has_extra = !extra.is_empty();
        nodes.push(
            h("div.chat-list")
                .attr("role", "list")
                .children(items)
                .into(),
        );
        if has_extra {
            nodes.push(h("div.list-title").text("Contacts").into());
            nodes.push(extra.into());
        }
        if !found_items.is_empty() {
            nodes.push(h("div.list-title").text("Messages").into());
            nodes.push(
                h("div.chat-list")
                    .attr("role", "list")
                    .children(found_items)
                    .into(),
            );
        }
        if empty {
            let mut e = h("div.side-empty");
            if !q.is_empty() {
                e = e.child(h("p").text("Aucun résultat."));
            } else if !archived {
                e = e
                    .child(h("p").text("Aucune discussion pour l'instant."))
                    .child(
                        h("p.muted")
                            .text("Partagez votre code ou ajoutez celui d'un ami pour commencer."),
                    )
                    .child(
                        h("button.btn.primary")
                            .attr("type", "button")
                            .text("Ajouter un contact")
                            .click(|| open_add_contact("", "")),
                    )
                    .child(
                        h("button.btn.ghost")
                            .attr("type", "button")
                            .text("Partager mon code")
                            .click(show_my_code),
                    );
            }
            if let Some(code) = typed_code {
                let c2 = code.clone();
                e = e.child(
                    h("button.btn.primary")
                        .attr("type", "button")
                        .text(format!("Ajouter {}", pretty(&code)))
                        .click(move || open_add_contact(&c2, "")),
                );
            }
            nodes.push(e.into());
        }
        replace(&body, nodes);
    });
}

fn chat_item(chat: &Chat) -> Element {
    let typing = core::typing_in(&chat.id);
    let me = core::my_code();
    let preview: El = if !typing.is_empty() {
        h("span.typing").text(if chat.group {
            format!("{} écrit…", core::display_name(&typing[0]))
        } else {
            "écrit…".into()
        })
    } else if !chat.draft.is_empty() {
        h("span")
            .child(h("span.draft").text("Brouillon : "))
            .text(&chat.draft)
    } else if let Some(last) = &chat.last {
        let mine = last.from == me;
        let who = if chat.group && last.from != "system" {
            format!(
                "{} : ",
                if mine {
                    "Vous".into()
                } else {
                    core::display_name(&last.from)
                }
            )
        } else {
            String::new()
        };
        h("span")
            .child((mine && last.kind != "system").then(|| h("span.tick-mini").text("✓ ")))
            .text(who)
            .text(&last.text)
    } else {
        h("span.muted").text(if chat.group {
            "Groupe créé"
        } else {
            "Nouvelle discussion"
        })
    };
    let unread = chat.unread > 0 || chat.marked_unread;
    let id = chat.id.clone();
    let el = h("button.chat-item")
        .attr("type", "button")
        .attr("role", "listitem")
        .class_if(
            "active",
            conv::current().as_deref() == Some(chat.id.as_str()),
        )
        .child(avatar_presence(&chat.id, 49))
        .child(
            h("div.chat-item-main")
                .child(
                    h("div.chat-item-top")
                        .child(h("span.chat-item-title").text(core::chat_title(chat)))
                        .child(
                            h("small").class_if("unread-time", unread).text(
                                chat.last
                                    .as_ref()
                                    .map(|l| list_time(l.at))
                                    .unwrap_or_default(),
                            ),
                        ),
                )
                .child(
                    h("div.chat-item-bottom")
                        .child(h("span.chat-item-preview").child(preview))
                        .child(
                            h("span.chat-item-icons")
                                .child((chat.timer > 0).then(|| {
                                    h("span").attr("title", "Messages éphémères").text("⏳")
                                }))
                                .child(chat.muted.then(|| {
                                    h("span")
                                        .attr("title", core::mute_label(chat))
                                        .child(icon_class("belloff", "small"))
                                }))
                                .child(chat.pinned.then(|| icon_class("pin", "small")))
                                // Mentionné depuis la dernière lecture.
                                .child(chat.mentioned.then(|| {
                                    h("span.badge.mention")
                                        .attr("title", "Vous êtes mentionné")
                                        .attr("aria-label", "Vous êtes mentionné")
                                        .text("@")
                                }))
                                .child(unread.then(|| {
                                    h("span.badge").class_if("muted", chat.muted).text(
                                        if chat.unread > 0 {
                                            chat.unread.to_string()
                                        } else {
                                            String::new()
                                        },
                                    )
                                })),
                        ),
                ),
        )
        .el();
    let pressed = Rc::new(Cell::new(false));
    {
        let (id, p) = (id.clone(), pressed.clone());
        listen(&el, "click", move |_| {
            if p.replace(false) {
                return;
            }
            let id = id.clone();
            spawn(async move { open_chat(&id).await });
        });
    }
    let chat_menu = {
        let c = chat.clone();
        Rc::new(move |at: At| {
            let (c1, c2, c3, c4, c5) = (c.clone(), c.clone(), c.clone(), c.clone(), c.clone());
            let unread = c.unread > 0 || c.marked_unread;
            menu(
                vec![
                    item(
                        "pin",
                        if c.pinned {
                            "Désépingler"
                        } else {
                            "Épingler"
                        },
                        move || {
                            let c = c1.clone();
                            spawn(async move {
                                core::update_chat(&c.id, |x| x.pinned = !c.pinned).await
                            })
                        },
                    ),
                    item(
                        "archive",
                        if c.archived {
                            "Désarchiver"
                        } else {
                            "Archiver"
                        },
                        move || {
                            let c = c2.clone();
                            spawn(async move {
                                core::update_chat(&c.id, |x| x.archived = !c.archived).await
                            })
                        },
                    ),
                    item(
                        if c.muted { "bell" } else { "belloff" },
                        if c.muted {
                            "Réactiver le son"
                        } else {
                            "Sourdine"
                        },
                        move || crate::conv::mute_or_unmute(&c3),
                    ),
                    item(
                        "check",
                        if unread {
                            "Marquer comme lu"
                        } else {
                            "Marquer comme non lu"
                        },
                        move || {
                            let c = c4.clone();
                            spawn(async move {
                                if unread {
                                    core::mark_read(&c.id).await;
                                } else {
                                    core::update_chat(&c.id, |x| x.marked_unread = true).await;
                                }
                            })
                        },
                    ),
                    danger("trash", "Supprimer", move || {
                        let c = c5.clone();
                        spawn(async move {
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
                            if c.group && !c.left {
                                core::leave_group(&c.id).await;
                            }
                            if conv::current().as_deref() == Some(c.id.as_str()) {
                                close_chat();
                            }
                            core::delete_chat(&c.id).await;
                        })
                    }),
                ],
                at,
            );
        })
    };
    {
        let m = chat_menu.clone();
        listen(&el, "contextmenu", move |e| {
            e.prevent_default();
            if let Some(me) = e.dyn_ref::<MouseEvent>() {
                m(At::Point(me.client_x() as f64, me.client_y() as f64));
            }
        });
    }
    let t = Rc::new(Cell::new(0));
    {
        let (t, m, e2, p) = (t.clone(), chat_menu.clone(), el.clone(), pressed.clone());
        listen(&el, "pointerdown", move |e| {
            if e.dyn_ref::<PointerEvent>()
                .is_some_and(|p| p.pointer_type() == "mouse")
            {
                return;
            }
            let (m, e3, p) = (m.clone(), e2.clone(), p.clone());
            t.set(timer(500, move || {
                p.set(true);
                let _ = call(&window().navigator(), "vibrate", &[15.into()]);
                m(At::El(e3.clone()));
            }));
        });
    }
    for ev in ["pointerup", "pointermove", "pointercancel"] {
        let t = t.clone();
        listen(&el, ev, move |_| clear_timer(t.get()));
    }
    el
}

fn contact_item(code: &str, f: impl FnMut() + 'static) -> El {
    let c = core::contact(code);
    let about = c
        .as_ref()
        .map(|c| c.about.clone())
        .filter(|a| !a.is_empty())
        .unwrap_or_else(|| pretty(code));
    h("button.list-item")
        .attr("type", "button")
        .child(avatar(code, 44))
        .child(
            h("div.grow")
                .child(h("div.strong").text(core::display_name(code)))
                .child(h("small.muted.ellipsis").text(about)),
        )
        .click(f)
}

// ----- Ouvrir une discussion ------------------------------------------------------

pub async fn open_chat(chat_id: &str) {
    if core::chat(chat_id).is_none() && !core::is_group(chat_id) {
        core::ensure_direct_chat(chat_id).await;
    }
    for m in query_all_in(
        &document().document_element().expect("html"),
        ".modal-backdrop",
    ) {
        m.remove();
    }
    if history_tag().is_empty() {
        push_state("chat");
    }
    let (Some(main), Some(layout)) = (with(|u| u.main.clone()), query(".layout")) else {
        return;
    };
    toggle_class(&layout, "in-chat", true);
    conv::close();
    conv::open(chat_id, &main).await;
    spawn(render_side_body());
}

fn close_chat() {
    conv::close();
    if let Some(l) = query(".layout") {
        toggle_class(&l, "in-chat", false);
    }
    render_empty_main();
    spawn(render_side_body());
}

// ----- Notifications ------------------------------------------------------------

fn on_incoming(chat: Chat, message: crate::model::Message, active: bool) {
    if active {
        if core::settings().sound {
            sounds::sent();
        }
        if message.kind == "text"
            && !message.sealed_for(&core::my_code(), now())
            && crate::brocoli::summons(&message.text)
        {
            crate::brocoli::rain();
        }
        return;
    }
    // En sourdine, seulement si l'on nous mentionne (« @Léa »).
    if chat.muted && !message.mentions.contains(&core::my_code()) {
        return;
    }
    let text = if message.kind == "text" && !message.is_capsule() {
        crate::jetons::masked(&message.text).into_owned()
    } else {
        core::preview(&message)
    };
    let body = if chat.group {
        format!("{} : {text}", core::display_name(&message.from))
    } else {
        text
    };
    notify(&chat, &body);
}

fn notify(chat: &Chat, body: &str) {
    let s = core::settings();
    if s.sound && !chat.muted {
        sounds::incoming();
    }
    let short: String = body.chars().take(80).collect();
    if visible() {
        if conv::current().as_deref() != Some(chat.id.as_str()) {
            toast(format!("{} : {short}", core::chat_title(chat)));
        }
        return;
    }
    if !s.notify || web_sys::Notification::permission() != web_sys::NotificationPermission::Granted
    {
        return;
    }
    // L'écran verrouillé ne montre que ce qu'on a choisi.
    let (title, body) = s.notification(&core::chat_title(chat), body);
    let opts = web_sys::NotificationOptions::new();
    opts.set_body(&body.chars().take(200).collect::<String>());
    opts.set_icon(&asset("icons/icon-192.png"));
    opts.set_tag(&chat.id);
    if let Ok(n) = web_sys::Notification::new_with_options(&title, &opts) {
        let id = chat.id.clone();
        let nn = n.clone();
        listen_forever(&n, "click", move |_| {
            let _ = window().focus();
            nn.close();
            let id = id.clone();
            spawn(async move { open_chat(&id).await });
        });
    }
}

// ----- Mon code, contacts, groupes --------------------------------------------------

/// Le QR code d'un lien, dessiné en Rust (SVG), en adresse `data:`.
fn qr_data_url(link: &str) -> String {
    use qrcode::render::svg;
    let Ok(code) = qrcode::QrCode::with_error_correction_level(link, qrcode::EcLevel::M) else {
        return String::new();
    };
    let svg_text = code
        .render::<svg::Color<'_>>()
        .min_dimensions(240, 240)
        .quiet_zone(true)
        .dark_color(svg::Color("#0b141a"))
        .light_color(svg::Color("#ffffff"))
        .build();
    media::text_data_url(&svg_text, "image/svg+xml")
}

// ----- Les appareils liés (v4) ---------------------------------------------------

fn devices_summary() -> String {
    match core::devices().list.len() {
        0 | 1 => "Seulement cet appareil".into(),
        n => format!("{n} appareils"),
    }
}

/// La ligne « Appareils liés » des réglages, s'ils sont ouverts : la liste a
/// changé (une liaison, un retrait, ou un autre de nos appareils).
fn refresh_devices_row() {
    let Some(root) = document().document_element() else {
        return;
    };
    for el in query_all_in(&root, ".devices-row small") {
        el.set_text_content(Some(&devices_summary()));
    }
}

/// La liste des appareils du compte : les retirer, en lier un nouveau.
fn open_devices() {
    let list = core::devices();
    let me = core::my_dev();
    modal(
        "Appareils liés",
        move |md| {
            let rows = list.list.iter().map(|d| {
                let this = d.dev == me;
                let since = crate::dom::day_label(d.at);
                let (dev, md2) = (d.dev.clone(), md.clone());
                let name = if d.name.is_empty() { "Appareil".to_owned() } else { d.name.clone() };
                let (n2, md4) = (name.clone(), md.clone());
                h("div.list-item")
                    .child(h("span.avatar.action").child(icon("phone")))
                    .child(
                        h("div.grow")
                            .child(h("div.strong").text(&name))
                            .child(h("small.muted").text(if this {
                                "Cet appareil".to_owned()
                            } else {
                                format!("Lié le {since}")
                            })),
                    )
                    .child(this.then(|| {
                        h("button.btn.ghost")
                            .attr("type", "button")
                            .text("Renommer")
                            .click(move || {
                                let (n2, md4) = (n2.clone(), md4.clone());
                                spawn(async move {
                                    if let Some(name) = prompt_box("Nom de cet appareil", "Il s'affiche dans la liste de vos appareils.", &n2, "text", "Enregistrer", 40).await {
                                        core::rename_device(&core::my_dev(), &name).await;
                                        md4.close();
                                    }
                                })
                            })
                    }))
                    .child((!this).then(|| {
                        h("button.btn.ghost.danger")
                            .attr("type", "button")
                            .text("Retirer")
                            .click(move || {
                                let (dev, md2) = (dev.clone(), md2.clone());
                                let name = name.clone();
                                spawn(async move {
                                    if confirm_box(
                                        "Retirer cet appareil ?",
                                        &format!("« {name} » ne recevra plus vos messages et effacera tout ce qu'il garde de votre compte."),
                                        "Retirer",
                                        true,
                                    )
                                    .await
                                    {
                                        core::unlink_device(&dev).await;
                                        md2.close();
                                        toast("Appareil retiré");
                                    }
                                })
                            })
                    }))
            });
            let (md3, md5) = (md.clone(), md.clone());
            // La caméra et le décodeur de Talka : partout où il y a une caméra.
            let can_scan = window().navigator().media_devices().is_ok();
            h("div.stack")
                .child(h("p.muted.small").text("Votre compte sur plusieurs appareils à la fois (iPhone, iPad, navigateur) : chacun a ses propres clés, et chaque message part chiffré pour chacun. Six appareils au plus."))
                .child(h("div.list").children(rows))
                .child(h("p.muted.small").text("Pour en lier un : ouvrez Talka sur le nouvel appareil et choisissez « J'ai déjà Talka sur un autre appareil ». Un QR code s'affiche."))
                .child(can_scan.then(|| {
                    h("button.btn.primary")
                        .attr("type", "button")
                        .child(icon("qr"))
                        .text(" Scanner son QR code")
                        .click(move || {
                            md3.close();
                            spawn(link_other_device(true));
                        })
                }))
                .child(
                    h("button.btn.ghost")
                        .attr("type", "button")
                        .text("Coller son code de liaison")
                        .click(move || {
                            md5.close();
                            spawn(link_other_device(false));
                        }),
                )
                .into()
        },
        ModalOpts::default(),
    );
}

/// Lier un nouvel appareil : lire son QR code (ou coller son code de
/// liaison), puis comparer le code de vérification.
async fn link_other_device(camera: bool) {
    let text = if camera {
        scan(
            "Visez le QR code affiché par le nouvel appareil.",
            Box::new(|raw: &str| {
                raw.starts_with(crate::crypto::LINK_PREFIX)
                    .then(|| raw.to_owned())
            }),
        )
        .await
    } else {
        prompt_box(
            "Code de liaison",
            "Sur le nouvel appareil : « J'ai déjà Talka sur un autre appareil », puis « Copier le code de liaison ».",
            "",
            "text",
            "Lier",
            400,
        )
        .await
    };
    let Some(text) = text else { return };
    toast("Liaison en cours…");
    match core::link_device(text.trim()).await {
        Ok(verif) => {
            let _ = confirm_box(
                "Appareil lié ✓",
                &format!("Le nouvel appareil doit afficher le même code de vérification : {verif}. S'il est différent, retirez-le (Réglages → Appareils liés)."),
                "OK",
                false,
            )
            .await;
            // La liste, avec le nouveau venu (comme WhatsApp).
            open_devices();
        }
        Err(e) => toast(e),
    }
}

/// Lier ce navigateur à un compte existant : un QR code que l'autre
/// appareil lit ; `true` quand le compte est arrivé.
async fn link_this_device() -> bool {
    let (text, d) = core::start_linking();
    let cancel = d.sender();
    let t2 = text.clone();
    let m = modal(
        "Lier à un compte existant",
        move |_| {
            let t3 = t2.clone();
            h("div.my-code")
                .child(h("img.qr").attr("src", qr_data_url(&t2)).attr("alt", "QR code de liaison").attr("width", "240").attr("height", "240"))
                .child(h("p.muted.small").text("Sur l'appareil où Talka est déjà ouvert (iPhone, iPad, navigateur) : Réglages → Appareils liés → Scanner son QR code, puis visez celui-ci."))
                .child(
                    h("button.btn.ghost")
                        .attr("type", "button")
                        .child(icon("copy"))
                        .text(" Copier le code de liaison")
                        .click(move || {
                            let t = t3.clone();
                            spawn(async move { copy(&t, "Code de liaison copié").await })
                        }),
                )
                .child(h("p.muted.small").text("En attente de l'autre appareil…"))
                .into()
        },
        ModalOpts {
            on_close: Some(Box::new(move || {
                core::cancel_linking();
                cancel.send(None);
            })),
            ..Default::default()
        },
    );
    match d.wait().await {
        Some(Ok(verif)) => {
            m.close();
            let _ = confirm_box(
                "Compte lié ✓",
                &format!("Vérifiez que l'autre appareil affiche le même code : {verif}."),
                "Continuer",
                false,
            )
            .await;
            true
        }
        Some(Err(e)) => {
            m.close();
            toast(e);
            false
        }
        None => false,
    }
}

pub fn show_my_code() {
    let me = core::my_code();
    let link = invite_link(&me);
    let p = core::profile();
    modal(
        "Mon code Talka",
        move |_| {
            let (l, code) = (link.clone(), me.clone());
            h("div.my-code")
                .child(avatar(&me, 72))
                .child(h("strong").text(&p.name))
                .child(h("div.code-big").attr("aria-label", "Votre code").text(pretty(&me)))
                .child(h("img.qr").attr("src", qr_data_url(&link)).attr("alt", "QR code de votre code Talka").attr("width", "220").attr("height", "220"))
                .child(h("p.muted.small").text("Montrez ce QR code ou partagez votre lien : on vous écrit sans connaître votre numéro."))
                .child(
                    h("div.row.center")
                        .child(h("button.btn.ghost").attr("type", "button").child(icon("copy")).text(" Copier le code").click(move || {
                            let c = pretty(&code);
                            spawn(async move { copy(&c, "Code copié").await })
                        }))
                        .child(h("button.btn.primary").attr("type", "button").child(icon("share")).text(" Partager").click(move || {
                            let l = l.clone();
                            spawn(share_link(l))
                        })),
                )
                .into()
        },
        ModalOpts::default(),
    );
}

async fn share_link(link: String) {
    let text = format!(
        "Écris-moi sur Talka ! Mon code : {}\n{link}",
        pretty(&core::my_code())
    );
    let nav = window().navigator();
    if has(&nav, "share") {
        let data = js_sys::Object::new();
        set(&data, "title", "Mon code Talka");
        set(&data, "text", text.as_str());
        set(&data, "url", link.as_str());
        let _ = await_promise(call(&nav, "share", &[data.into()])).await;
    } else {
        copy(&text, "Lien copié").await;
    }
}

pub fn open_add_contact(prefill: &str, name: &str) {
    let known = if prefill.is_empty() {
        None
    } else {
        core::contact(prefill)
    };
    let name = if !name.is_empty() {
        name.to_owned()
    } else {
        known
            .map(|c| {
                if c.name.is_empty() {
                    c.profile_name
                } else {
                    c.name
                }
            })
            .unwrap_or_default()
    };
    let code_input = h("input.field.code-input")
        .attr("placeholder", "K7Q2M-X9PRT")
        .attr("autocapitalize", "characters")
        .attr("autocomplete", "off")
        .attr("spellcheck", "false")
        .attr("maxlength", "13")
        .attr("aria-label", "Code Talka")
        .prop(
            "value",
            if prefill.is_empty() {
                String::new()
            } else {
                pretty(prefill)
            },
        )
        .el();
    let name_input = h("input.field")
        .attr("placeholder", "Nom (facultatif)")
        .attr("maxlength", "40")
        .attr("aria-label", "Nom du contact")
        .prop("value", name)
        .el();
    let err = h("p.error").el();
    let (ci, ni, er) = (code_input.clone(), name_input.clone(), err.clone());
    let m =
        modal(
            "Ajouter un contact",
            move |_| {
                let ci2 = ci.clone();
                listen(&ci, "input", move |_| {
                    let raw: String = value(&ci2)
                        .to_uppercase()
                        .chars()
                        .filter(char::is_ascii_alphanumeric)
                        .take(10)
                        .collect();
                    set_value(
                        &ci2,
                        &if raw.len() > 5 {
                            format!("{}-{}", &raw[..5], &raw[5..])
                        } else {
                            raw
                        },
                    );
                });
                let ci3 = ci.clone();
                h("div.stack")
                    .child(h("p.muted").text(
                        "Entrez le code Talka de votre ami : 10 caractères, comme K7Q2M-X9PRT.",
                    ))
                    .child(ci.clone())
                    .child(ni.clone())
                    .child(er.clone())
                    .child(media::can_scan().then(|| {
                        h("button.btn.ghost")
                            .attr("type", "button")
                            .child(icon("camera"))
                            .text(" Scanner un QR code")
                            .click(move || {
                                let c = ci3.clone();
                                spawn(async move {
                                    if let Some(code) = scan_qr().await {
                                        set_value(&c, &pretty(&code));
                                    }
                                })
                            })
                    }))
                    .into()
            },
            ModalOpts {
                actions: vec![
                    action("Annuler", "ghost", |m| m.close()),
                    action("Ajouter", "primary", move |m| {
                        let (m, code, name, err) = (
                            m.clone(),
                            value(&code_input),
                            value(&name_input),
                            err.clone(),
                        );
                        spawn(async move {
                            let Some(code) = normalize(&code) else {
                                err.set_text_content(Some(
                                    "Ce code n'est pas valide : 10 caractères, sans 0, 1, I ni O.",
                                ));
                                return;
                            };
                            match core::add_contact(&code, &name).await {
                                Ok(reachable) => {
                                    m.close();
                                    toast(if reachable {
                                        "Contact ajouté ✓"
                                    } else {
                                        "Contact ajouté. Il sera joignable dès qu'il ouvrira Talka."
                                    });
                                    open_chat(&code).await;
                                }
                                Err(e) => err.set_text_content(Some(&e)),
                            }
                        });
                    }),
                ],
                ..Default::default()
            },
        );
    if !prefill.is_empty()
        && let Some(n) = query_all_in(&m.el(), "input").get(1).cloned()
    {
        timer(80, move || focus(&n));
    }
}

/// Scanner un QR code Talka avec la caméra (`BarcodeDetector`).
async fn scan_qr() -> Option<String> {
    scan(
        "Visez le QR code de votre ami.",
        Box::new(|raw: &str| {
            let tail = raw.rsplit(['/', '=']).next().unwrap_or(raw).to_owned();
            normalize(&tail).or_else(|| normalize(raw))
        }),
    )
    .await
}

/// Ce qu'on garde d'un QR code lu (rien : on continue de chercher).
type Pick = Box<dyn Fn(&str) -> Option<String>>;

/// Scanner un QR code : `pick` dit ce qu'on en garde.
async fn scan(hint: &'static str, pick: Pick) -> Option<String> {
    let devices = window().navigator().media_devices().ok()?;
    let cons = web_sys::MediaStreamConstraints::new();
    let v = js_sys::Object::new();
    set(&v, "facingMode", "environment");
    cons.set_video(&v);
    let stream: web_sys::MediaStream = match wasm_bindgen_futures::JsFuture::from(
        devices.get_user_media_with_constraints(&cons).ok()?,
    )
    .await
    {
        Ok(s) => s.unchecked_into(),
        Err(_) => {
            toast("Caméra refusée.");
            return None;
        }
    };
    let video = h("video.scan-video")
        .attr("autoplay", "")
        .attr("playsinline", "")
        .prop("muted", true)
        .el();
    if let Some(m) = video.dyn_ref::<web_sys::HtmlMediaElement>() {
        m.set_src_object(Some(&stream));
    }
    let done = Rc::new(Cell::new(false));
    let (d2, s2) = (done.clone(), stream.clone());
    let v2 = video.clone();
    let m = modal(
        "Scanner un QR code",
        move |_| {
            h("div.stack")
                .child(v2)
                .child(h("p.muted.small").text(hint))
                .into()
        },
        ModalOpts {
            on_close: Some(Box::new(move || {
                d2.set(true);
                for t in s2.get_tracks().iter() {
                    t.unchecked_into::<web_sys::MediaStreamTrack>().stop();
                }
            })),
            ..Default::default()
        },
    );
    // `BarcodeDetector` quand le navigateur l'a ; sinon (Safari, Firefox),
    // le décodeur de Talka, image par image.
    let detector = get(&window(), "BarcodeDetector")
        .dyn_into::<js_sys::Function>()
        .ok()
        .and_then(|ctor| {
            let opts = js_sys::Object::new();
            set(&opts, "formats", js_sys::Array::of1(&"qr_code".into()));
            js_sys::Reflect::construct(&ctor, &js_sys::Array::of1(&opts)).ok()
        });
    let canvas: web_sys::HtmlCanvasElement =
        document().create_element("canvas").ok()?.dyn_into().ok()?;
    let found = loop {
        if done.get() {
            break None;
        }
        sleep(300).await;
        let texts: Vec<String> = match &detector {
            Some(detector) => {
                let Ok(codes) =
                    await_promise(call(detector, "detect", &[video.clone().into()])).await
                else {
                    continue;
                };
                js_sys::Array::from(&codes)
                    .iter()
                    .filter_map(|c| get(&c, "rawValue").as_string())
                    .collect()
            }
            None => frame_codes(&video, &canvas),
        };
        let hit = texts.iter().find_map(|raw| pick(raw));
        if hit.is_some() {
            break hit;
        }
    };
    m.close();
    found
}

/// Les QR codes d'une image de la caméra, lus par Talka (`crate::qr`).
fn frame_codes(video: &Element, canvas: &web_sys::HtmlCanvasElement) -> Vec<String> {
    let Some(v) = video.dyn_ref::<web_sys::HtmlVideoElement>() else {
        return Vec::new();
    };
    let (vw, vh) = (v.video_width(), v.video_height());
    if vw == 0 || vh == 0 {
        return Vec::new();
    }
    // 640 pixels de large au plus : assez pour un QR code, vite lu.
    let scale = (640.0 / vw as f64).min(1.0);
    let (w, h) = ((vw as f64 * scale) as u32, (vh as f64 * scale) as u32);
    canvas.set_width(w);
    canvas.set_height(h);
    let Some(ctx) = canvas
        .get_context("2d")
        .ok()
        .flatten()
        .and_then(|c| c.dyn_into::<web_sys::CanvasRenderingContext2d>().ok())
    else {
        return Vec::new();
    };
    if ctx
        .draw_image_with_html_video_element_and_dw_and_dh(v, 0.0, 0.0, w as f64, h as f64)
        .is_err()
    {
        return Vec::new();
    }
    let Ok(data) = ctx.get_image_data(0.0, 0.0, w as f64, h as f64) else {
        return Vec::new();
    };
    crate::qr::decode_rgba(w as usize, h as usize, &data.data())
}

fn open_new_chat() {
    let mut people: Vec<_> = core::contacts()
        .into_iter()
        .filter(|c| c.saved && !c.blocked)
        .collect();
    people.sort_by_key(|c| core::display_name(&c.code).to_lowercase());
    modal(
        "Nouvelle discussion",
        move |md| {
            let (m1, m2, m3) = (md.clone(), md.clone(), md.clone());
            let action_row = |ic: &str, label: &str, f: Box<dyn Fn()>| {
                h("button.list-item.action")
                    .attr("type", "button")
                    .child(h("span.avatar.action").child(icon(ic)))
                    .child(h("div.grow.strong").text(label))
                    .click(f)
            };
            h("div.list")
                .child(action_row(
                    "group",
                    "Nouveau groupe",
                    Box::new(move || {
                        m1.close();
                        spawn(open_new_group());
                    }),
                ))
                .child(action_row(
                    "person",
                    "Ajouter un contact par son code",
                    Box::new(move || {
                        m2.close();
                        open_add_contact("", "");
                    }),
                ))
                .child(action_row(
                    "qr",
                    "Mon code et QR code",
                    Box::new(move || {
                        m3.close();
                        show_my_code();
                    }),
                ))
                .child(if people.is_empty() {
                    h("p.muted.pad").text("Vous n'avez pas encore de contacts.")
                } else {
                    h("div.list-title").text("Contacts sur Talka")
                })
                .children(people.iter().map(|c| {
                    let (code, md) = (c.code.clone(), md.clone());
                    contact_item(&c.code, move || {
                        md.close();
                        let code = code.clone();
                        spawn(async move { open_chat(&code).await });
                    })
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

/// Choisir des contacts : renvoie leurs codes (`None` : annulé).
async fn pick_contacts(title: &str, exclude: &[String], ok: &str) -> Option<Vec<String>> {
    let people: Vec<_> = core::contacts()
        .into_iter()
        .filter(|c| c.saved && !c.blocked && !exclude.contains(&c.code))
        .collect();
    let chosen = Rc::new(RefCell::new(HashSet::<String>::new()));
    let d = Deferred::<Vec<String>>::new();
    let (s1, s2) = (d.sender(), d.sender());
    let ch = chosen.clone();
    modal(
        title,
        move |_| {
            if people.is_empty() {
                return h("p.muted")
                    .text("Ajoutez d'abord des contacts avec leur code.")
                    .into();
            }
            h("div.list")
                .children(people.iter().map(|c| {
                    let (ch, code) = (ch.clone(), c.code.clone());
                    let name = core::display_name(&c.code);
                    let b = h("input")
                        .attr("type", "checkbox")
                        .attr("aria-label", &name)
                        .el();
                    let b2 = b.clone();
                    listen(&b, "change", move |_| {
                        if checked(&b2) {
                            ch.borrow_mut().insert(code.clone());
                        } else {
                            ch.borrow_mut().remove(&code);
                        }
                    });
                    h("label.list-item")
                        .child(avatar(&c.code, 40))
                        .child(h("div.grow").text(name))
                        .child(b)
                }))
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Annuler", "ghost", |m| m.close()),
                action(ok, "primary", move |m| {
                    s1.send(Some(chosen.borrow().iter().cloned().collect()));
                    m.close();
                }),
            ],
            on_close: Some(Box::new(move || s2.send(None))),
            ..Default::default()
        },
    );
    d.wait().await
}

/// Choisir des discussions (transférer) : renvoie leurs identifiants.
pub async fn pick_chats(title: &str) -> Option<Vec<String>> {
    let mut chats: Vec<Chat> = core::chats()
        .into_iter()
        .filter(|c| !c.left && !core::blocked(&c.id))
        .collect();
    chats.sort_by(|a, b| b.last_at.total_cmp(&a.last_at));
    let chosen = Rc::new(RefCell::new(Vec::<String>::new()));
    let d = Deferred::<Vec<String>>::new();
    let (s1, s2) = (d.sender(), d.sender());
    let ch = chosen.clone();
    modal(
        title,
        move |_| {
            h("div.list")
                .children(chats.iter().map(|c| {
                    let (ch, id) = (ch.clone(), c.id.clone());
                    let name = core::chat_title(c);
                    let b = h("input")
                        .attr("type", "checkbox")
                        .attr("aria-label", &name)
                        .el();
                    let b2 = b.clone();
                    listen(&b, "change", move |_| {
                        ch.borrow_mut().retain(|x| *x != id);
                        if checked(&b2) {
                            ch.borrow_mut().push(id.clone());
                        }
                    });
                    h("label.list-item")
                        .child(avatar(&c.id, 40))
                        .child(h("div.grow").text(name))
                        .child(b)
                }))
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Annuler", "ghost", |m| m.close()),
                action("Envoyer", "primary", move |m| {
                    s1.send(Some(chosen.borrow().clone()));
                    m.close();
                }),
            ],
            on_close: Some(Box::new(move || s2.send(None))),
            ..Default::default()
        },
    );
    d.wait().await
}

async fn open_new_group() {
    let Some(members) = pick_contacts("Nouveau groupe : les membres", &[], "Suivant").await else {
        return;
    };
    if members.is_empty() {
        return toast("Choisissez au moins un contact.");
    }
    let pic = Rc::new(RefCell::new(String::new()));
    let pic_btn = h("button.avatar-pick")
        .attr("type", "button")
        .attr("aria-label", "Photo du groupe")
        .child(icon("camera"))
        .el();
    let name = h("input.field")
        .attr("placeholder", "Nom du groupe")
        .attr("maxlength", "60")
        .attr("aria-label", "Nom du groupe")
        .el();
    let summary = format!(
        "{} membre{} : {}",
        members.len(),
        if members.len() > 1 { "s" } else { "" },
        members
            .iter()
            .map(|c| core::display_name(c))
            .collect::<Vec<_>>()
            .join(", ")
    );
    let (pb, p2, n2) = (pic_btn.clone(), pic.clone(), name.clone());
    let (p3, n3) = (pic.clone(), name.clone());
    modal(
        "Nouveau groupe",
        move |_| {
            let (pb2, p2) = (pb.clone(), p2.clone());
            listen(&pb, "click", move |_| {
                let (pb, p) = (pb2.clone(), p2.clone());
                spawn(async move {
                    let Some(f) = media::pick_file("image/*", None).await else {
                        return;
                    };
                    if let Some(a) = media::avatar_from(&f).await {
                        replace(&pb, h("img").attr("src", &a).attr("alt", ""));
                        *p.borrow_mut() = a;
                    }
                });
            });
            h("div.stack.center")
                .child(pb.clone())
                .child(n2.clone())
                .child(h("p.muted.small").text(&summary))
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Annuler", "ghost", |m| m.close()),
                action("Créer", "primary", move |m| {
                    let n = value(&n3);
                    if n.trim().is_empty() {
                        focus(&n3);
                        return;
                    }
                    m.close();
                    let (members, avatar) = (members.clone(), p3.borrow().clone());
                    spawn(async move {
                        let c = core::create_group(&n, members, avatar).await;
                        open_chat(&c.id).await;
                    });
                }),
            ],
            ..Default::default()
        },
    );
}

// ----- Les fiches -----------------------------------------------------------------

pub async fn open_info(id: &str) {
    match core::chat(id).filter(|c| c.group) {
        Some(c) => group_info(c).await,
        None => contact_info(id).await,
    }
}

async fn media_grid(chat_id: &str) -> Option<Element> {
    let list: Vec<_> = core::messages(chat_id, 500)
        .await
        .into_iter()
        .rev()
        .filter(|m| m.kind == "image" && !m.deleted)
        .take(24)
        .collect();
    if list.is_empty() {
        return None;
    }
    Some(
        h("div.info-section")
            .child(h("h3").text("Photos"))
            .child(h("div.media-grid").children(list.iter().map(|m| {
                let (d, t) = (m.data.clone(), m.text.clone());
                h("button")
                    .attr("type", "button")
                    .child(
                        h("img")
                            .attr("src", &m.data)
                            .attr("alt", "")
                            .attr("loading", "lazy"),
                    )
                    .click(move || open_image(&d, &t))
            })))
            .el(),
    )
}

async fn contact_info(code: &str) {
    let c = core::contact(code).unwrap_or_default();
    let has_chat = core::chat(code).is_some();
    let grid = if has_chat {
        media_grid(code).await
    } else {
        None
    };
    let me = core::me();
    let safety = safety_number((&me.code, &me.sign, &me.dh), (code, &c.sign, &c.dh));
    let protection = match core::protection(code).await {
        crate::v3::Protection::Ratchet { pq: true } => {
            "🔐 Session à double cliquet (post-quantique)"
        }
        crate::v3::Protection::Ratchet { pq: false } => "🔐 Session à double cliquet (classique)",
        crate::v3::Protection::Legacy => {
            "Ancien chiffrement (le contact n'a pas encore la mise à jour)"
        }
    };
    let code = code.to_owned();
    modal(
        "Infos du contact",
        move |md| {
            let p = core::presence(&code);
            let (m1, m2, m3, k1, k2, k3) = (
                md.clone(),
                md.clone(),
                md.clone(),
                code.clone(),
                code.clone(),
                code.clone(),
            );
            let actions = {
                let mut a = h("div.info-section.actions");
                if c.saved {
                    let (k, md, current) = (
                        code.clone(),
                        md.clone(),
                        if c.name.is_empty() {
                            c.profile_name.clone()
                        } else {
                            c.name.clone()
                        },
                    );
                    a = a.child(
                        h("button.btn.ghost")
                            .attr("type", "button")
                            .child(icon("edit"))
                            .text(" Renommer")
                            .click(move || {
                                let (k, md, current) = (k.clone(), md.clone(), current.clone());
                                spawn(async move {
                                    if let Some(n) = prompt_box(
                                        "Renommer le contact",
                                        "",
                                        &current,
                                        "text",
                                        "OK",
                                        40,
                                    )
                                    .await
                                    {
                                        core::update_contact(&k, |c| c.name = n.trim().to_owned())
                                            .await;
                                        md.close();
                                        contact_info(&k).await;
                                    }
                                })
                            }),
                    );
                } else {
                    let (k, md, pn) = (code.clone(), md.clone(), c.profile_name.clone());
                    a = a.child(
                        h("button.btn.primary")
                            .attr("type", "button")
                            .child(icon("person"))
                            .text(" Ajouter aux contacts")
                            .click(move || {
                                md.close();
                                open_add_contact(&k, &pn);
                            }),
                    );
                }
                if let Some(chat) = core::chat(&code) {
                    a = a.child(
                        h("button.btn.ghost")
                            .attr("type", "button")
                            .child(icon("timer"))
                            .text(" Messages éphémères")
                            .click(move || conv::timer_dialog(&chat)),
                    );
                }
                let (k, mdb, blocked) = (code.clone(), md.clone(), c.blocked);
                a = a.child(
                    h("button.btn.ghost.danger")
                        .attr("type", "button")
                        .child(icon("block"))
                        .text(if blocked { " Débloquer" } else { " Bloquer" })
                        .click(move || {
                            let k = k.clone();
                            mdb.close();
                            spawn(async move {
                                core::set_blocked(&k, !blocked).await;
                                toast(if blocked { "Débloqué" } else { "Bloqué" });
                            })
                        }),
                );
                if c.saved {
                    let (k, md) = (code.clone(), md.clone());
                    a = a.child(h("button.btn.ghost.danger").attr("type", "button").child(icon("trash")).text(" Retirer des contacts").click(move || {
                        let (k, md) = (k.clone(), md.clone());
                        spawn(async move {
                            if confirm_box("Retirer ce contact ?", "La discussion reste, mais le contact n'est plus enregistré.", "Retirer", true).await {
                                core::update_contact(&k, |c| {
                                    c.saved = false;
                                    c.name.clear();
                                })
                                .await;
                                md.close();
                            }
                        })
                    }));
                }
                a
            };
            h("div.info")
                .child(
                    h("div.info-head")
                        .child(avatar(&code, 120))
                        .child(h("h2").text(core::display_name(&code)))
                        .child((!c.profile_name.is_empty() && !c.name.is_empty()).then(|| h("p.muted").text(format!("~{}", c.profile_name))))
                        .child(h("p.code-small").text(pretty(&code)))
                        .child(match p {
                            Some((true, _)) => Some(h("p.online").text("en ligne")),
                            Some((false, Some(seen))) => Some(h("p.muted.small").text(last_seen(seen))),
                            _ => None,
                        })
                        .child(
                            h("div.row.center")
                                .child(h("button.round-btn").attr("type", "button").child(icon("chat")).child(h("span").text("Message")).click(move || {
                                    m1.close();
                                    let k = k1.clone();
                                    spawn(async move { open_chat(&k).await })
                                }))
                                .child(h("button.round-btn").attr("type", "button").child(icon("phone")).child(h("span").text("Audio")).click(move || {
                                    m2.close();
                                    let k = k2.clone();
                                    spawn(async move { calls::start_call(&k, false).await })
                                }))
                                .child(h("button.round-btn").attr("type", "button").child(icon("video")).child(h("span").text("Vidéo")).click(move || {
                                    m3.close();
                                    let k = k3.clone();
                                    spawn(async move { calls::start_call(&k, true).await })
                                })),
                        ),
                )
                .child((!c.about.is_empty()).then(|| h("div.info-section").child(h("h3").text("Actu")).child(h("p").text(&c.about))))
                .child(grid.clone())
                .child(
                    h("div.info-section")
                        .child(h("h3").child(icon("shield")).text(" Chiffrement"))
                        .child(h("p.muted.small").text("Les messages et appels sont chiffrés de bout en bout. Comparez ce numéro de sécurité avec celui affiché chez votre contact : s'il est identique, personne ne s'est glissé entre vous."))
                        .child(h("p.small").text(protection))
                        .child(h("p.safety").text(&safety)),
                )
                .child(actions)
                .into()
        },
        ModalOpts {
            wide: true,
            ..Default::default()
        },
    );
}

fn group_body(id: &str, md: &Modal, grid: Option<Element>) -> Child {
    let Some(chat) = core::chat(id) else {
        return Child::None;
    };
    let me = core::my_code();
    let is_admin = chat.admins.contains(&me) && !chat.left;
    let mut members = chat.members.clone();
    members.sort_by_key(|c| {
        if *c == me {
            String::new()
        } else {
            core::display_name(c).to_lowercase()
        }
    });
    let n = members.len();
    let refresh = {
        let (id, md) = (id.to_owned(), md.clone());
        Rc::new(move || {
            let (id, md) = (id.clone(), md.clone());
            spawn(async move {
                let grid = media_grid(&id).await;
                md.scoped(|| replace(&md.body(), group_body(&id, &md, grid)));
            })
        })
    };
    let admin_row = is_admin.then(|| {
        let (c1, c2, r1, r2) = (chat.clone(), chat.clone(), refresh.clone(), refresh.clone());
        let (c3, r3) = (chat.clone(), refresh.clone());
        h("div.row.center")
            .child(
                h("button.btn.ghost")
                    .attr("type", "button")
                    .child(icon("edit"))
                    .text(" Renommer")
                    .click(move || {
                        let (c, r) = (c1.clone(), r1.clone());
                        spawn(async move {
                            if let Some(n) =
                                prompt_box("Nom du groupe", "", &c.name, "text", "OK", 60)
                                    .await
                                    .filter(|n| !n.trim().is_empty())
                            {
                                let n = n.trim().to_owned();
                                core::update_group(
                                    &c.id,
                                    |x| x.name = n.clone(),
                                    &format!("Vous avez renommé le groupe en « {n} »."),
                                )
                                .await;
                                r();
                            }
                        })
                    }),
            )
            .child(
                h("button.btn.ghost")
                    .attr("type", "button")
                    .child(icon("camera"))
                    .text(" Photo")
                    .click(move || {
                        let (c, r) = (c2.clone(), r2.clone());
                        spawn(async move {
                            let Some(f) = media::pick_file("image/*", None).await else {
                                return;
                            };
                            if let Some(a) = media::avatar_from(&f).await {
                                core::update_group(
                                    &c.id,
                                    |x| x.avatar = a,
                                    "Vous avez changé la photo du groupe.",
                                )
                                .await;
                                r();
                            }
                        })
                    }),
            )
            .child(
                h("button.btn.ghost")
                    .attr("type", "button")
                    .child(icon("text"))
                    .text(" Description")
                    .click(move || {
                        let (c, r) = (c3.clone(), r3.clone());
                        spawn(async move {
                            let Some(d) = prompt_box(
                                "Description du groupe",
                                "Visible par tous les membres.",
                                &c.desc,
                                "text",
                                "OK",
                                MAX_GROUP_DESC as u32,
                            )
                            .await
                            .map(|d| group_desc(&d))
                            .filter(|d| *d != c.desc) else {
                                return;
                            };
                            let note = if d.is_empty() {
                                "Vous avez supprimé la description du groupe."
                            } else {
                                "Vous avez changé la description du groupe."
                            };
                            core::update_group(&c.id, |x| x.desc = d, note).await;
                            r();
                        })
                    }),
            )
    });
    let add_row = is_admin.then(|| {
        let (c, r) = (chat.clone(), refresh.clone());
        h("button.list-item.action")
            .attr("type", "button")
            .child(h("span.avatar.action").child(icon("plus")))
            .child(h("div.grow.strong").text("Ajouter des membres"))
            .click(move || {
                let (c, r) = (c.clone(), r.clone());
                spawn(async move {
                    let Some(add) =
                        pick_contacts("Ajouter des membres", &c.members, "Ajouter").await
                    else {
                        return;
                    };
                    if add.is_empty() {
                        return;
                    }
                    let names = add
                        .iter()
                        .map(|x| core::display_name(x))
                        .collect::<Vec<_>>()
                        .join(", ");
                    core::update_group(
                        &c.id,
                        |x| x.members.extend(add.clone()),
                        &format!("Vous avez ajouté {names}."),
                    )
                    .await;
                    r();
                })
            })
    });
    let rows: Vec<El> = members
        .iter()
        .map(|code| {
            let mut row = h("div.list-item")
                .child(avatar(code, 40))
                .child(
                    h("div.grow")
                        .child(h("div").text(if *code == me {
                            "Vous".into()
                        } else {
                            core::display_name(code)
                        }))
                        .child(h("small.muted").text(pretty(code))),
                )
                .child(
                    chat.admins
                        .contains(code)
                        .then(|| h("span.tag").text("Admin")),
                );
            if is_admin && *code != me {
                let (code, c, r) = (code.clone(), chat.clone(), refresh.clone());
                let b = h("button.icon-btn")
                    .attr("type", "button")
                    .attr("aria-label", "Options")
                    .child(icon("more"))
                    .el();
                let bb = b.clone();
                listen(&b, "click", move |e| {
                    e.stop_propagation();
                    let saved = core::contact(&code).is_some_and(|x| x.saved);
                    let (k1, k2, k3, c2, c3, r2, r3) = (
                        code.clone(),
                        code.clone(),
                        code.clone(),
                        c.clone(),
                        c.clone(),
                        r.clone(),
                        r.clone(),
                    );
                    menu(
                        vec![
                            if saved {
                                item("chat", "Envoyer un message", move || {
                                    let k = k1.clone();
                                    spawn(async move { open_chat(&k).await })
                                })
                            } else {
                                item("person", "Ajouter aux contacts", move || {
                                    open_add_contact(&k1, "")
                                })
                            },
                            if c.admins.contains(&code) {
                                None
                            } else {
                                item("key", "Nommer admin", move || {
                                    let (k, c, r) = (k2.clone(), c2.clone(), r2.clone());
                                    spawn(async move {
                                        core::update_group(
                                            &c.id,
                                            |x| x.admins.push(k.clone()),
                                            &format!(
                                                "Vous avez nommé {} admin.",
                                                core::display_name(&k)
                                            ),
                                        )
                                        .await;
                                        r();
                                    })
                                })
                            },
                            danger("trash", "Retirer du groupe", move || {
                                let (k, c, r) = (k3.clone(), c3.clone(), r3.clone());
                                spawn(async move {
                                    core::update_group(
                                        &c.id,
                                        |x| {
                                            x.members.retain(|m| *m != k);
                                            x.admins.retain(|m| *m != k);
                                        },
                                        &format!("Vous avez retiré {}.", core::display_name(&k)),
                                    )
                                    .await;
                                    r();
                                })
                            }),
                        ],
                        At::El(bb.clone()),
                    );
                });
                row = row.child(b);
            }
            row
        })
        .collect();
    let (c1, c2, md2) = (chat.clone(), chat.clone(), md.clone());
    h("div.info")
        .child(
            h("div.info-head")
                .child(avatar(&chat.id, 120))
                .child(h("h2").text(&chat.name))
                .child(h("p.muted").text(format!(
                    "Groupe · {n} membre{}",
                    if n > 1 { "s" } else { "" }
                )))
                .child((!chat.desc.is_empty()).then(|| h("p.group-desc").text(&chat.desc)))
                .child(admin_row),
        )
        .child(grid)
        .child(
            h("div.info-section")
                .child(h("h3").text(format!("{n} membres")))
                .child(add_row)
                .children(rows),
        )
        .child(
            h("div.info-section.actions")
                .child(
                    h("button.btn.ghost")
                        .attr("type", "button")
                        .child(icon("timer"))
                        .text(" Messages éphémères")
                        .click(move || conv::timer_dialog(&c1)),
                )
                .child((!chat.left).then(|| {
                    h("button.btn.ghost.danger")
                        .attr("type", "button")
                        .child(icon("logout"))
                        .text(" Quitter le groupe")
                        .click(move || {
                            let (c, md) = (c2.clone(), md2.clone());
                            spawn(async move {
                                if confirm_box(
                                    "Quitter le groupe ?",
                                    "Vous ne recevrez plus ses messages.",
                                    "Quitter",
                                    true,
                                )
                                .await
                                {
                                    core::leave_group(&c.id).await;
                                    md.close();
                                }
                            })
                        })
                })),
        )
        .into()
}

async fn group_info(chat: Chat) {
    let grid = media_grid(&chat.id).await;
    let id = chat.id.clone();
    modal(
        "Infos du groupe",
        move |md| group_body(&id, md, grid),
        ModalOpts {
            wide: true,
            ..Default::default()
        },
    );
}

pub fn open_image(src: &str, caption: &str) {
    let zone = unique("viewer");
    let viewer = h("div.viewer")
        .attr("role", "dialog")
        .attr("aria-label", "Photo")
        .el();
    scope_into(&zone, || {
        let (v1, v2, z1, z2) = (viewer.clone(), viewer.clone(), zone.clone(), zone.clone());
        let close = Rc::new(move || {
            v1.remove();
            let z = z1.clone();
            timer(0, move || drop_scope(&z));
        });
        let src2 = src.to_owned();
        let c1 = close.clone();
        replace(
            &viewer,
            vec![
                h("div.viewer-bar")
                    .child(icon_button("download", "Enregistrer", move || {
                        download("photo-talka.jpg", &src2)
                    }))
                    .child(icon_button("close", "Fermer", move || c1()))
                    .el(),
                h("img")
                    .attr("src", src)
                    .attr("alt", if caption.is_empty() { "Photo" } else { caption })
                    .el(),
                h("p.viewer-caption").text(caption).el(),
            ],
        );
        let c2 = close.clone();
        listen(&viewer, "click", move |e| {
            if e.target()
                .and_then(|t| t.dyn_into::<Element>().ok())
                .as_ref()
                == Some(&v2)
            {
                c2();
            }
        });
        let c3 = close.clone();
        listen(&document(), "keydown", move |e| {
            if e.dyn_ref::<web_sys::KeyboardEvent>()
                .is_some_and(|k| k.key() == "Escape")
            {
                c3();
            }
        });
        let _ = z2;
    });
    let _ = document().body().expect("body").append_child(&viewer);
}

async fn open_starred() {
    let list = core::starred().await;
    modal(
        "Messages favoris",
        move |_| {
            if list.is_empty() {
                return h("p.muted").text("Ouvrez le menu d'un message (ou appuyez longuement) pour le mettre en favori.").into();
            }
            h("div.list")
                .children(list.iter().map(|m| {
                    let chat = m.chat.clone();
                    let title = core::chat(&m.chat)
                        .map(|c| core::chat_title(&c))
                        .unwrap_or_default();
                    h("button.list-item")
                        .attr("type", "button")
                        .child(avatar(&m.from, 36))
                        .child(
                            h("div.grow")
                                .child(h("small.muted").text(format!(
                                    "{} · {title} · {}",
                                    core::display_name(&m.from),
                                    list_time(m.at)
                                )))
                                .child(h("div.ellipsis").text(core::readable(m))),
                        )
                        .click(move || {
                            let c = chat.clone();
                            spawn(async move { open_chat(&c).await })
                        })
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

// ----- Actus (statuts) ----------------------------------------------------------------

const BGS: [&str; 7] = [
    "#008069", "#5e47de", "#c4532d", "#1f7aec", "#a62c71", "#b7820b", "#243b4a",
];

async fn render_statuses() {
    let list = core::statuses().await;
    let Some(body) = with(|u| u.body.clone()).filter(|_| with(|u| u.tab == "status")) else {
        return;
    };
    let me = core::my_code();
    let mine: Vec<StatusItem> = list.iter().filter(|s| s.owner == me).cloned().collect();
    let mut owners: Vec<(String, Vec<StatusItem>)> = Vec::new();
    for s in list
        .into_iter()
        .filter(|s| s.owner != me && !core::blocked(&s.owner))
    {
        match owners.iter_mut().find(|(o, _)| *o == s.owner) {
            Some((_, v)) => v.push(s),
            None => owners.push((s.owner.clone(), vec![s])),
        }
    }
    owners.sort_by(|a, b| {
        b.1.last()
            .map_or(0.0, |s| s.at)
            .total_cmp(&a.1.last().map_or(0.0, |s| s.at))
    });
    let (fresh, seen): (Vec<_>, Vec<_>) = owners
        .into_iter()
        .partition(|(_, items)| items.iter().any(|s| !s.seen));
    scope("side-body", || {
        let row = |(owner, items): &(String, Vec<StatusItem>)| {
            let unseen = items.iter().any(|s| !s.seen);
            let last = items.last().map_or(0.0, |s| s.at);
            let items2 = items.clone();
            h("button.list-item")
                .attr("type", "button")
                .child(ring(avatar(owner, 46), !unseen))
                .child(
                    h("div.grow")
                        .child(h("div.strong").text(core::display_name(owner)))
                        .child(h("small.muted").text(list_time(last))),
                )
                .click(move || {
                    let start = items2.iter().position(|s| !s.seen).unwrap_or(0);
                    view_statuses(items2.clone(), start)
                })
        };
        let mine2 = mine.clone();
        let my_avatar = if mine.is_empty() {
            h("span")
                .child(avatar(&me, 46))
                .child(h("span.plus-badge").text("+"))
                .el()
        } else {
            ring(avatar(&me, 46), true)
        };
        let has_fresh = !fresh.is_empty();
        let has_seen = !seen.is_empty();
        let nobody = !has_fresh && !has_seen;
        replace(
            &body,
            h("div.list")
                .child(
                    h("div.list-item.my-status")
                        .child(h("button.my-status-avatar").attr("type", "button").attr("aria-label", "Voir mon statut").child(my_avatar).click(move || {
                            if mine2.is_empty() {
                                new_text_status();
                            } else {
                                view_statuses(mine2.clone(), 0);
                            }
                        }))
                        .child(
                            h("div.grow")
                                .child(h("div.strong").text("Mon statut"))
                                .child(h("small.muted").text(match mine.last() {
                                    Some(l) => format!("{} actu{} · {}", mine.len(), if mine.len() > 1 { "s" } else { "" }, list_time(l.at)),
                                    None => "Appuyez pour ajouter une actu".into(),
                                })),
                        )
                        .child(icon_button("text", "Statut texte", new_text_status))
                        .child(icon_button("camera", "Statut photo", || spawn(new_photo_status()))),
                )
                .child(has_fresh.then(|| h("div.list-title").text("Récentes")))
                .children(fresh.iter().map(row))
                .child(has_seen.then(|| h("div.list-title").text("Vues")))
                .children(seen.iter().map(row))
                .child(nobody.then(|| h("p.muted.pad").text("Les actus de vos contacts apparaîtront ici. Elles disparaissent au bout de 24 heures."))),
        );
    });
}

fn new_text_status() {
    let bg = Rc::new(Cell::new(0usize));
    let ta = h("textarea.status-input")
        .attr("placeholder", "Écrivez une actu")
        .attr("maxlength", "700")
        .attr("aria-label", "Texte de l'actu")
        .el();
    let card = h("div.status-compose")
        .style("background", BGS[0])
        .child(ta.clone())
        .el();
    let (b1, c1, b2, t2) = (bg.clone(), card.clone(), bg.clone(), ta.clone());
    modal(
        "Nouvelle actu",
        move |_| h("div.stack").child(card.clone()).into(),
        ModalOpts {
            actions: vec![
                action("Couleur", "ghost", move |_| {
                    b1.set((b1.get() + 1) % BGS.len());
                    set_style(&c1, "background", BGS[b1.get()]);
                }),
                action("Publier", "primary", move |m| {
                    let text = value(&t2).trim().to_owned();
                    if text.is_empty() {
                        focus(&t2);
                        return;
                    }
                    m.close();
                    let bgc = BGS[b2.get()];
                    spawn(async move {
                        core::post_status("text", &text, bgc, String::new()).await;
                        toast("Actu publiée pour 24 heures");
                    });
                }),
            ],
            ..Default::default()
        },
    );
}

async fn new_photo_status() {
    let Some(f) = media::pick_file("image/*", None).await else {
        return;
    };
    let Some((data, _, _)) = media::compress_image(&f, 1280.0, 0.8).await else {
        return;
    };
    let caption = h("input.field")
        .attr("placeholder", "Légende")
        .attr("maxlength", "300")
        .el();
    let (c1, c2, d1) = (caption.clone(), caption.clone(), data.clone());
    modal(
        "Nouvelle actu",
        move |_| {
            h("div.stack")
                .child(h("img.preview-img").attr("src", &d1).attr("alt", ""))
                .child(c1.clone())
                .into()
        },
        ModalOpts {
            wide: true,
            actions: vec![
                action("Annuler", "ghost", |m| m.close()),
                action("Publier", "primary", move |m| {
                    m.close();
                    let (data, text) = (data.clone(), value(&c2).trim().to_owned());
                    spawn(async move {
                        core::post_status("image", &text, "", data).await;
                        toast("Actu publiée pour 24 heures");
                    });
                }),
            ],
            ..Default::default()
        },
    );
}

/// Passer à une actu (par son rang).
type Step = Rc<dyn Fn(isize)>;

fn view_statuses(items: Vec<StatusItem>, start: usize) {
    let zone = unique("story");
    let root_el = h("div.story")
        .attr("role", "dialog")
        .attr("aria-label", "Actus")
        .el();
    let bars = h("div.story-bars")
        .children(items.iter().map(|_| h("span").child(h("i"))))
        .el();
    let stage = h("div.story-stage").el();
    let head = h("div.story-head-info").el();
    let foot = h("div.story-foot").el();
    let items = Rc::new(items);
    let idx = Rc::new(Cell::new(start));
    let tmr = Rc::new(Cell::new(0));
    let begin = Rc::new(Cell::new(0.0f64));
    let left = Rc::new(Cell::new(5000.0f64));
    let paused = Rc::new(Cell::new(false));
    let go: Rc<RefCell<Option<Step>>> = Rc::new(RefCell::new(None));
    let close: Rc<dyn Fn()> = {
        let (r, z, t) = (root_el.clone(), zone.clone(), tmr.clone());
        Rc::new(move || {
            clear_timer(t.get());
            r.remove();
            let z = z.clone();
            timer(0, move || drop_scope(&z));
            spawn(render_side_body());
        })
    };
    let go_fn: Rc<dyn Fn(isize)> = {
        let (items, idx, tmr, begin, left, bars, stage, head, foot, close, go, zone) = (
            items.clone(),
            idx.clone(),
            tmr.clone(),
            begin.clone(),
            left.clone(),
            bars.clone(),
            stage.clone(),
            head.clone(),
            foot.clone(),
            close.clone(),
            go.clone(),
            zone.clone(),
        );
        let paused = paused.clone();
        Rc::new(move |n: isize| {
            let n = n.max(0) as usize;
            if n >= items.len() {
                close();
                return;
            }
            idx.set(n);
            let s = items[n].clone();
            for (k, b) in query_all_in(&bars, "span").iter().enumerate() {
                b.set_class_name(if k < n {
                    "done"
                } else if k == n {
                    "run"
                } else {
                    ""
                });
            }
            let me = core::my_code();
            replace(
                &head,
                vec![
                    avatar(&s.owner, 36),
                    h("div")
                        .child(h("strong").text(if s.owner == me {
                            "Mon statut".into()
                        } else {
                            core::display_name(&s.owner)
                        }))
                        .child(h("small").text(list_time(s.at)))
                        .el(),
                ],
            );
            replace(
                &stage,
                if s.kind == "image" {
                    h("div.story-img")
                        .child(h("img").attr("src", &s.data).attr("alt", ""))
                        .child((!s.text.is_empty()).then(|| h("p").text(&s.text)))
                        .el()
                } else {
                    h("div.story-text")
                        .style("background", if s.bg.is_empty() { BGS[0] } else { &s.bg })
                        .text(&s.text)
                        .el()
                },
            );
            scope(&format!("{zone}-foot"), || {
                if s.owner == me {
                    let viewers: Vec<(String, f64)> =
                        s.viewers.iter().map(|(k, v)| (k.clone(), *v)).collect();
                    let n_viewers = viewers.len();
                    let (t, p, g) = (tmr.clone(), paused.clone(), go.clone());
                    let (id, cl) = (s.id.clone(), close.clone());
                    let idx2 = idx.clone();
                    replace(
                        &foot,
                        vec![
                            h("button.btn.ghost.light")
                                .attr("type", "button")
                                .text(format!("👁 {n_viewers}"))
                                .click(move || {
                                    clear_timer(t.get());
                                    p.set(true);
                                    let (g, p, i) = (g.clone(), p.clone(), idx2.clone());
                                    let viewers = viewers.clone();
                                    modal(
                                        "Vue par",
                                        move |_| {
                                            if viewers.is_empty() {
                                                return h("p.muted")
                                                    .text("Personne pour l'instant.")
                                                    .into();
                                            }
                                            h("div.list")
                                                .children(viewers.iter().map(|(c, t)| {
                                                    h("div.list-item")
                                                        .child(avatar(c, 36))
                                                        .child(
                                                            h("div.grow")
                                                                .text(core::display_name(c)),
                                                        )
                                                        .child(h("small.muted").text(time(*t)))
                                                }))
                                                .into()
                                        },
                                        ModalOpts {
                                            on_close: Some(Box::new(move || {
                                                p.set(false);
                                                if let Some(go) = g.borrow().clone() {
                                                    go(i.get() as isize);
                                                }
                                            })),
                                            ..Default::default()
                                        },
                                    );
                                })
                                .el(),
                            h("button.btn.ghost.light")
                                .attr("type", "button")
                                .text("Supprimer")
                                .click(move || {
                                    let (id, cl) = (id.clone(), cl.clone());
                                    spawn(async move {
                                        if confirm_box(
                                            "Supprimer cette actu ?",
                                            "Elle disparaîtra aussi chez vos contacts.",
                                            "Supprimer",
                                            true,
                                        )
                                        .await
                                        {
                                            core::delete_status(&id).await;
                                            cl();
                                        }
                                    })
                                })
                                .el(),
                        ],
                    );
                } else {
                    let st = s.clone();
                    spawn(async move { core::mark_status_seen(&st).await });
                    let (owner, cl) = (s.owner.clone(), close.clone());
                    replace(
                        &foot,
                        h("button.btn.ghost.light")
                            .attr("type", "button")
                            .text("Répondre")
                            .click(move || {
                                cl();
                                let o = owner.clone();
                                spawn(async move { open_chat(&o).await })
                            }),
                    );
                }
            });
            clear_timer(tmr.get());
            begin.set(now());
            left.set(5000.0);
            let g = go.clone();
            tmr.set(timer(5000, move || {
                if let Some(go) = g.borrow().clone() {
                    go(n as isize + 1);
                }
            }));
        })
    };
    *go.borrow_mut() = Some(go_fn.clone());
    scope_into(&zone, || {
        let (c1, g1, g2, i1, i2) = (
            close.clone(),
            go_fn.clone(),
            go_fn.clone(),
            idx.clone(),
            idx.clone(),
        );
        replace(
            &root_el,
            vec![
                bars.clone(),
                h("div.story-head")
                    .child(head.clone())
                    .child(icon_button("close", "Fermer", move || c1()))
                    .el(),
                stage.clone(),
                foot.clone(),
                h("button.story-prev")
                    .attr("type", "button")
                    .attr("aria-label", "Précédente")
                    .click(move || g1(i1.get() as isize - 1))
                    .el(),
                h("button.story-next")
                    .attr("type", "button")
                    .attr("aria-label", "Suivante")
                    .click(move || g2(i2.get() as isize + 1))
                    .el(),
            ],
        );
        let (c2, g3, i3) = (close.clone(), go_fn.clone(), idx.clone());
        let r2 = root_el.clone();
        listen(&document(), "keydown", move |e| {
            if !r2.is_connected() {
                return;
            }
            match e
                .dyn_ref::<web_sys::KeyboardEvent>()
                .map(|k| k.key())
                .as_deref()
            {
                Some("Escape") => c2(),
                Some("ArrowRight") => g3(i3.get() as isize + 1),
                Some("ArrowLeft") => g3(i3.get() as isize - 1),
                _ => {}
            }
        });
        // Appui long : pause.
        let (p, t, l, b, r) = (
            paused.clone(),
            tmr.clone(),
            left.clone(),
            begin.clone(),
            root_el.clone(),
        );
        listen(&stage, "pointerdown", move |_| {
            p.set(true);
            clear_timer(t.get());
            l.set(l.get() - (now() - b.get()));
            toggle_class(&r, "paused", true);
        });
        for ev in ["pointerup", "pointerleave"] {
            let (p, t, l, b, r, g, i) = (
                paused.clone(),
                tmr.clone(),
                left.clone(),
                begin.clone(),
                root_el.clone(),
                go_fn.clone(),
                idx.clone(),
            );
            listen(&stage, ev, move |_| {
                if !p.replace(false) {
                    return;
                }
                toggle_class(&r, "paused", false);
                b.set(now());
                let (g, i) = (g.clone(), i.clone());
                t.set(timer(l.get().max(300.0) as i32, move || {
                    g(i.get() as isize + 1)
                }));
            });
        }
    });
    let _ = document().body().expect("body").append_child(&root_el);
    go_fn(start as isize);
}

// ----- Appels ---------------------------------------------------------------------

async fn render_calls() {
    let list = core::call_log().await;
    let Some(body) = with(|u| u.body.clone()).filter(|_| with(|u| u.tab == "calls")) else {
        return;
    };
    scope("side-body", || {
        let header = if list.is_empty() {
            h("p.muted.pad").text("Aucun appel pour l'instant. Les appels audio et vidéo passent directement d'un appareil à l'autre.")
        } else {
            h("div.list-title.row")
                .child(h("span.grow").text("Récents"))
                .child(
                    h("button.link")
                        .attr("type", "button")
                        .text("Effacer")
                        .click(|| {
                            spawn(async {
                                if confirm_box(
                                    "Effacer l'historique des appels ?",
                                    "",
                                    "Effacer",
                                    true,
                                )
                                .await
                                {
                                    core::clear_calls().await;
                                }
                            })
                        }),
                )
        };
        replace(
            &body,
            h("div.list")
                .child(
                    h("button.list-item.action")
                        .attr("type", "button")
                        .child(h("span.avatar.action").child(icon("phone")))
                        .child(h("div.grow.strong").text("Nouvel appel"))
                        .click(|| {
                            spawn(async {
                                if let Some(code) = pick_contacts("Appeler…", &[], "Appeler")
                                    .await
                                    .and_then(|l| l.into_iter().next())
                                {
                                    calls::start_call(&code, false).await;
                                }
                            })
                        }),
                )
                .child(header)
                .children(list.iter().map(|c| {
                    let (peer, video) = (c.peer.clone(), c.video);
                    h("div.list-item")
                        .child(avatar(&c.peer, 44))
                        .child(
                            h("div.grow")
                                .child(
                                    h("div.strong")
                                        .class_if("missed", c.missed)
                                        .text(core::display_name(&c.peer)),
                                )
                                .child(
                                    h("small.muted.call-line")
                                        .class_if("missed", c.missed)
                                        .attr("title", calls::describe(c))
                                        .child(icon_class(
                                            if c.incoming { "incoming" } else { "outgoing" },
                                            "small",
                                        ))
                                        .text(format!(
                                            " {}{}",
                                            list_time(c.at),
                                            if c.dur > 0.0 {
                                                format!(" · {}", fmt_dur(c.dur))
                                            } else {
                                                String::new()
                                            }
                                        )),
                                ),
                        )
                        .child(icon_button(
                            if c.video { "video" } else { "phone" },
                            "Rappeler",
                            move || {
                                let p = peer.clone();
                                spawn(async move { calls::start_call(&p, video).await })
                            },
                        ))
                })),
        );
    });
}

// ----- Réglages ---------------------------------------------------------------------

fn toggle(label: &str, desc: &str, on: bool, f: impl Fn(bool) + 'static) -> El {
    let input = h("input.switch")
        .attr("type", "checkbox")
        .prop("checked", on)
        .el();
    let i = input.clone();
    listen(&input, "change", move |_| f(checked(&i)));
    h("label.setting")
        .child(
            h("div.grow")
                .child(h("div").text(label))
                .child((!desc.is_empty()).then(|| h("small.muted").text(desc))),
        )
        .child(input)
}

fn setting(ic: &str, label: &str, desc: &str, danger_: bool, f: impl FnMut() + 'static) -> El {
    h("button.setting")
        .class_if("danger", danger_)
        .attr("type", "button")
        .child(icon(ic))
        .child(
            h("div.grow")
                .child(h("div").text(label))
                .child((!desc.is_empty()).then(|| h("small.muted").text(desc))),
        )
        .click(f)
}

fn select(
    label: &str,
    options: &[(&str, &str)],
    current: &str,
    f: impl Fn(String) + 'static,
) -> El {
    let sel = h("select.field")
        .attr("aria-label", label)
        .children(options.iter().map(|(v, l)| {
            h("option")
                .attr("value", v)
                .prop("selected", *v == current)
                .text(l)
        }))
        .el();
    let s = sel.clone();
    listen(&sel, "change", move |_| f(value(&s)));
    h("div.setting").child(h("div.grow").text(label)).child(sel)
}

pub async fn open_settings() {
    let s = core::settings();
    let used = store::usage().await;
    let has_pin = lock::has_pin().await;
    let key_lock = store::lock().await;
    let p = core::profile();
    let name = h("input.field")
        .attr("maxlength", "40")
        .attr("aria-label", "Votre nom")
        .prop("value", p.name.clone())
        .el();
    let about = h("input.field")
        .attr("maxlength", "140")
        .attr("aria-label", "Votre actu")
        .prop("value", p.about.clone())
        .el();
    let save: Rc<dyn Fn()> = {
        let (n, a) = (name.clone(), about.clone());
        Rc::new(move || {
            let (nv, av) = (value(&n).trim().to_owned(), value(&a).trim().to_owned());
            let p = core::profile();
            if !nv.is_empty() && (nv != p.name || av != p.about) {
                spawn(async move {
                    core::save_profile(|p| {
                        p.name = nv;
                        p.about = av;
                    })
                    .await;
                    toast("Profil enregistré");
                    render_side();
                });
            }
        })
    };
    let blocked_n = core::contacts().iter().filter(|c| c.blocked).count();
    let s2 = save.clone();
    modal(
        "Réglages",
        move |md| {
            let s3 = save.clone();
            listen(&name, "change", move |_| s3());
            let s4 = save.clone();
            listen(&about, "change", move |_| s4());
            let avatar_btn = h("button.avatar-pick.big")
                .attr("type", "button")
                .attr("aria-label", "Changer de photo")
                .child(avatar(&core::my_code(), 110))
                .child(h("span.avatar-edit").child(icon("camera")))
                .el();
            let ab = avatar_btn.clone();
            listen(&avatar_btn, "click", move |_| {
                let ab = ab.clone();
                let has = !core::profile().avatar.is_empty();
                let (a1, a2) = (ab.clone(), ab.clone());
                menu(
                    vec![
                        item("image", "Choisir une photo", move || {
                            let ab = a1.clone();
                            spawn(async move {
                                let Some(f) = media::pick_file("image/*", None).await else {
                                    return;
                                };
                                let Some(a) = media::avatar_from(&f).await else {
                                    return;
                                };
                                core::save_profile(|p| p.avatar = a).await;
                                replace(
                                    &ab,
                                    vec![
                                        avatar(&core::my_code(), 110),
                                        h("span.avatar-edit").child(icon("camera")).el(),
                                    ],
                                );
                                render_side();
                            })
                        }),
                        if has {
                            danger("trash", "Retirer la photo", move || {
                                let ab = a2.clone();
                                spawn(async move {
                                    core::save_profile(|p| p.avatar.clear()).await;
                                    replace(
                                        &ab,
                                        vec![
                                            avatar(&core::my_code(), 110),
                                            h("span.avatar-edit").child(icon("camera")).el(),
                                        ],
                                    );
                                    render_side();
                                })
                            })
                        } else {
                            None
                        },
                    ],
                    At::El(ab.clone()),
                );
            });
            let (m1, m2, m3) = (md.clone(), md.clone(), md.clone());
            let mut privacy = h("div.settings-group")
                .child(h("h3").text("Confidentialité"))
                .child(toggle(
                    "Confirmations de lecture",
                    "Désactivées, vous ne voyez pas non plus celles des autres",
                    s.read_receipts,
                    |v| spawn(core::save_settings(move |s| s.read_receipts = v)),
                ))
                .child(toggle(
                    "Masquer « en ligne »",
                    "Vos contacts ne voient plus quand vous êtes connecté",
                    s.hide_online,
                    |v| spawn(core::save_settings(move |s| s.hide_online = v)),
                ))
                .child(toggle(
                    "Pastille « en ligne »",
                    "Un point vert sur l'avatar des contacts connectés, gris sinon",
                    s.presence_dot,
                    |v| {
                        spawn(async move {
                            core::save_settings(move |s| s.presence_dot = v).await;
                            queue_side();
                            conv::refresh_header();
                        })
                    },
                ))
                .child(setting(
                    "lock",
                    if has_pin {
                        "Changer le code de verrouillage"
                    } else {
                        "Code de verrouillage"
                    },
                    if has_pin {
                        "Talka demande votre code à l'ouverture"
                    } else {
                        "Un code de 6 chiffres ou plus, qui chiffre aussi vos messages sur cet appareil"
                    },
                    false,
                    move || {
                        m1.close();
                        spawn(async {
                            setup_pin().await;
                        });
                    },
                ));
            let passkey_on = matches!(key_lock, store::Lock::Passkey { .. });
            if passkey_on || crate::passkey::supported() {
                let m = m3;
                privacy = privacy.child(setting(
                    "key",
                    if passkey_on {
                        "Retirer la passkey"
                    } else {
                        "Protéger avec une passkey"
                    },
                    if passkey_on {
                        "Vos messages ne s'ouvrent qu'avec votre passkey"
                    } else {
                        "Empreinte, visage ou clé de sécurité pour ouvrir vos messages"
                    },
                    passkey_on,
                    move || {
                        let m = m.clone();
                        spawn(async move {
                            if passkey_on {
                                if confirm_box(
                                    "Retirer la passkey ?",
                                    "Vos messages resteront chiffrés sur cet appareil, mais s'ouvriront sans elle (avec votre code de verrouillage, s'il y en a un).",
                                    "Retirer",
                                    true,
                                )
                                .await
                                    && store::protect(store::NewLock::None).await
                                {
                                    m.close();
                                    toast("Passkey retirée");
                                }
                                return;
                            }
                            if !confirm_box(
                                "Protéger avec une passkey",
                                "Vos messages, chiffrés sur cet appareil, ne s'ouvriront qu'avec votre passkey (empreinte, visage, Windows Hello ou clé de sécurité). Même quelqu'un qui copierait les fichiers de votre navigateur ne pourrait pas les lire. Si vous perdez la passkey, seule une sauvegarde vous rendra vos messages : faites-en une.",
                                "Créer la passkey",
                                false,
                            )
                            .await
                            {
                                return;
                            }
                            let p = core::profile();
                            match crate::passkey::enable(&core::my_code(), &p.name).await {
                                Ok(()) => {
                                    store::committed().await;
                                    m.close();
                                    toast("Vos messages sont protégés par votre passkey 🔐");
                                }
                                Err(e) => toast(e),
                            }
                        })
                    },
                ));
            }
            if has_pin {
                privacy = privacy
                    .child(select(
                        "Verrouillage automatique",
                        &[
                            ("0", "Dès que je quitte Talka"),
                            ("60", "Après 1 minute"),
                            ("300", "Après 5 minutes"),
                            ("900", "Après 15 minutes"),
                            ("3600", "Après 1 heure"),
                        ],
                        &s.lock_after.to_string(),
                        |v| {
                            let n = v.parse().unwrap_or(0);
                            spawn(core::save_settings(move |s| s.lock_after = n))
                        },
                    ))
                    .child(toggle(
                        "Effacer après 10 codes faux",
                        "Au dixième code faux, Talka efface tout sur cet appareil",
                        crate::lock::wipe_after_fails(),
                        crate::lock::set_wipe_after_fails,
                    ))
                    .child(setting(
                        "lock",
                        "Retirer le code de verrouillage",
                        "",
                        true,
                        move || {
                            let m = m2.clone();
                            spawn(async move {
                                if confirm_box(
                                    "Retirer le code ?",
                                    "Talka s'ouvrira sans code.",
                                    "Retirer",
                                    true,
                                )
                                .await
                                {
                                    lock::set_pin(None).await;
                                    with(|u| u.lock = false);
                                    m.close();
                                    toast("Code retiré");
                                }
                            })
                        },
                    ));
            }
            privacy = privacy.child(setting(
                "block",
                "Contacts bloqués",
                &format!("{blocked_n} bloqué(s)"),
                false,
                blocked_list,
            ));
            let notif_ok =
                web_sys::Notification::permission() == web_sys::NotificationPermission::Granted;
            h("div.settings")
                .child(h("div.settings-profile").child(avatar_btn).child(h("div.stack.grow").child(h("label.muted.small").text("Votre nom")).child(name.clone()).child(h("label.muted.small").text("Actu")).child(about.clone())))
                .child(h("div.settings-group").child(h("h3").text("Mon code")).child(setting("qr", &pretty(&core::my_code()), "Votre identifiant Talka : il remplace le numéro de téléphone", false, show_my_code)))
                .child(privacy)
                .child(h("div.settings-group").child(h("h3").text("Appareils")).child(setting("phone", "Appareils liés", &devices_summary(), false, open_devices).class("devices-row")))
                .child(h("div.settings-group").child(h("h3").text("Brocolis 🥦")).child(setting("lock", "Clés Brocolis", &brocolis_summary(), false, crate::brocoli::keys_dialog)))
                .child(
                    h("div.settings-group")
                        .child(h("h3").text("Discussions"))
                        .child(select("Thème", &[("auto", "Comme l'appareil"), ("light", "Clair"), ("dark", "Sombre")], &s.theme, |v| spawn(core::save_settings(move |s| s.theme = v))))
                        .child(select("Taille du texte", &[("13", "Petite"), ("15", "Moyenne"), ("17", "Grande"), ("19", "Très grande")], &s.font_size.to_string(), |v| {
                            let n = v.parse().unwrap_or(15);
                            spawn(core::save_settings(move |s| s.font_size = n))
                        }))
                        .child(setting("palette", "Fond d'écran", "Pour toutes les discussions", false, || conv::wallpaper_dialog(None)))
                        .child(toggle("Entrée pour envoyer", "Sur ordinateur ; Maj + Entrée va à la ligne", s.enter_sends, |v| spawn(core::save_settings(move |s| s.enter_sends = v))))
                        .child(select("Conserver les messages", &[("1", "24 heures"), ("7", "7 jours"), ("30", "1 mois"), ("90", "3 mois"), ("180", "6 mois"), ("365", "1 an"), ("0", "Toujours")], &s.keep_days.to_string(), |v| {
                            let days = v.parse().unwrap_or(crate::model::KEEP_DAYS);
                            spawn(core::save_settings(move |s| s.keep_days = days))
                        }))
                        .child(h("p.muted.small.pad").text("Plus vieux, ils s'effacent de cet appareil, sauf les messages importants ⭐.")),
                )
                .child(
                    h("div.settings-group")
                        .child(h("h3").text("Notifications"))
                        .child(toggle("Notifications", if crate::push::available() { "Même quand Talka est fermé, sans rien dévoiler" } else { "Quand Talka est en arrière-plan" }, s.notify && notif_ok, |v| {
                            spawn(async move {
                                if v {
                                    let p = await_promise(call(&get(&window(), "Notification"), "requestPermission", &[])).await.ok().and_then(|p| p.as_string()).unwrap_or_default();
                                    if p != "granted" {
                                        toast("Notifications refusées par le navigateur");
                                        core::save_settings(|s| s.notify = false).await;
                                        return;
                                    }
                                }
                                core::save_settings(move |s| s.notify = v).await;
                                crate::push::sync().await;
                            })
                        }))
                        .child(select("Aperçu", &[("all", "Nom et message"), ("name", "Nom seulement"), ("none", "Rien (« Nouveau message »)")], &s.notify_preview, |v| {
                            spawn(async move {
                                core::save_settings(move |s| s.notify_preview = v).await;
                                crate::push::contacts_changed().await;
                            })
                        }))
                        .child(toggle("Sons", "Messages, envois, appels", s.sound, |v| spawn(core::save_settings(move |s| s.sound = v)))),
                )
                .child(
                    h("div.settings-group")
                        .child(h("h3").text("Stockage et sauvegarde"))
                        .child(h("p.muted.small.pad").text(format!("Talka n'a pas de base de données : tout est gardé dans ce navigateur{}.", used.map(|u| format!(" ({} utilisés)", bytes(u))).unwrap_or_default())))
                        .child(setting("download", "Exporter une sauvegarde chiffrée", "Un fichier protégé par mot de passe : vos clés, contacts et discussions", false, || spawn(export_backup())))
                        .child(setting("upload", "Importer une sauvegarde", "Remplace tout ce qui est sur cet appareil", false, || spawn(import_backup()))),
                )
                .child(
                    h("div.settings-group")
                        .child(h("h3").text("Aide"))
                        .child(setting("info", "À propos de Talka", "Le site, la confidentialité, les questions", false, || {
                            let _ = window().open_with_url_and_target(&format!("{}/", meta("talka-base")), "_blank");
                        }))
                        .child(setting("trash", "Supprimer toutes mes données", "Efface votre code, vos contacts et vos discussions de cet appareil", true, || spawn(wipe())))
                        .child(h("p.muted.tiny.center").text("Talka · fait avec Paquets 📦 · écrit en Rust 🦀")),
                )
                .into()
        },
        ModalOpts {
            wide: true,
            class: "settings-modal",
            on_close: Some(Box::new(move || s2())),
            ..Default::default()
        },
    );
}

fn brocolis_summary() -> String {
    match core::brocolis_keys().len() {
        0 => "Une seconde couche de chiffrement, avec l'app Brocolis".into(),
        1 => "1 clé".into(),
        n => format!("{n} clés"),
    }
}

fn blocked_list() {
    let blocked: Vec<_> = core::contacts().into_iter().filter(|c| c.blocked).collect();
    modal(
        "Contacts bloqués",
        move |_| {
            if blocked.is_empty() {
                return h("p.muted").text("Aucun contact bloqué.").into();
            }
            h("div.list")
                .children(blocked.iter().map(|c| {
                    let code = c.code.clone();
                    let row = h("div.list-item").el();
                    let r = row.clone();
                    replace(
                        &row,
                        vec![
                            avatar(&c.code, 40),
                            h("div.grow").text(core::display_name(&c.code)).el(),
                            h("button.btn.ghost")
                                .attr("type", "button")
                                .text("Débloquer")
                                .click(move || {
                                    let (code, r) = (code.clone(), r.clone());
                                    spawn(async move {
                                        core::set_blocked(&code, false).await;
                                        r.remove();
                                    })
                                })
                                .el(),
                        ],
                    );
                    row
                }))
                .into()
        },
        ModalOpts::default(),
    );
}

/// Choisir un code de verrouillage (deux fois) ; `true` s'il est enregistré.
pub async fn setup_pin() -> bool {
    let host = h("div.lock-screen.setup").el();
    let _ = document().body().expect("body").append_child(&host);
    let d = Deferred::<bool>::new();
    let first = Rc::new(RefCell::new(None::<String>));
    let (s1, h1) = (d.sender(), host.clone());
    let pad = lock::pin_pad(
        "Code de verrouillage",
        "Choisissez un code de 6 à 12 chiffres, puis OK",
        move |pin, pad| {
            let prev = first.borrow_mut().take();
            match prev {
                None if pin.len() < crate::crypto::PIN_MIN => {
                    pad.error("6 chiffres au moins");
                }
                None if pin.chars().all(|c| pin.starts_with(c))
                    || "01234567890123".contains(&pin)
                    || "98765432109876".contains(&pin) =>
                {
                    pad.error("Trop facile à deviner : choisissez-en un autre");
                }
                None => {
                    *first.borrow_mut() = Some(pin);
                    pad.info("Confirmez votre code");
                }
                Some(p) if p != pin => pad.error("Les codes ne correspondent pas. Recommencez."),
                Some(_) => {
                    let (s, host, pad) = (s1.clone(), h1.clone(), pad.clone());
                    spawn(async move {
                        lock::set_pin(Some(&pin)).await;
                        with(|u| u.lock = true);
                        pad.destroy();
                        host.remove();
                        toast("Code de verrouillage activé 🔒");
                        s.send(Some(true));
                    });
                }
            }
        },
    );
    let (s2, h2, p2) = (d.sender(), host.clone(), pad.clone());
    let cancel = scope_into("global", || {
        h("button.btn.ghost.light")
            .attr("type", "button")
            .text("Annuler")
            .click(move || {
                p2.destroy();
                h2.remove();
                s2.send(Some(false));
            })
            .el()
    });
    let _ = host.append_child(&pad.el);
    let _ = host.append_child(&cancel);
    d.wait().await.unwrap_or(false)
}

async fn export_backup() {
    let Some(pw) = prompt_box("Mot de passe de la sauvegarde", "Il sera demandé pour la restaurer. Sans lui, la sauvegarde est illisible (8 caractères au moins).", "", "password", "Exporter", 200).await else {
        return;
    };
    if pw.chars().count() < 8 {
        return toast("8 caractères au moins.");
    }
    toast("Préparation de la sauvegarde…");
    sleep(50).await;
    let Some(file) = core::export_backup(&pw).await else {
        return toast("La sauvegarde a échoué.");
    };
    let day = js_sys::Date::new_0()
        .to_iso_string()
        .as_string()
        .unwrap_or_default();
    download(
        &format!("talka-sauvegarde-{}.talka", &day[..10.min(day.len())]),
        &media::text_data_url(&file, "application/json"),
    );
}

async fn import_backup() {
    // Aucun filtre : sur iPhone, l'extension « .talka », inconnue du
    // système, rendait le fichier grisé. Le contenu est vérifié ensuite.
    let Some(f) = media::pick_file("", None).await else {
        return;
    };
    let Some(text) = media::text_of(&f).await else {
        return toast("Ce fichier n'est pas une sauvegarde Talka.");
    };
    let Some(pw) = prompt_box(
        "Mot de passe de la sauvegarde",
        "",
        "",
        "password",
        "Restaurer",
        200,
    )
    .await
    else {
        return;
    };
    let started = MOUNTED.with(Cell::get);
    if started
        && !confirm_box(
            "Remplacer les données ?",
            "Tout ce qui est sur cet appareil sera remplacé par la sauvegarde.",
            "Remplacer",
            true,
        )
        .await
    {
        return;
    }
    toast("Restauration…");
    sleep(50).await;
    if started {
        net::stop();
    }
    match core::import_backup(&pw, &text).await {
        Ok(()) => {
            toast("Sauvegarde restaurée ✓");
            sleep(600).await;
            let _ = window().location().reload();
        }
        Err(e) => {
            toast(e);
            if started {
                sleep(1200).await;
                let _ = window().location().reload();
            }
        }
    }
}

async fn wipe() {
    if !confirm_box(
        "Supprimer toutes vos données ?",
        "Votre code, vos clés, vos contacts et vos discussions seront effacés de cet appareil. Sans sauvegarde, ils seront perdus pour toujours.",
        "Tout supprimer",
        true,
    )
    .await
    {
        return;
    }
    // Lié à d'autres appareils : il quitte d'abord le compte.
    core::leave_account().await;
    core::wipe_everything().await;
    let _ = window().location().reload();
}

// ----- L'accueil -----------------------------------------------------------------------

/// L'accueil : `true` si un compte a été créé, `false` si cet appareil a
/// été lié à un compte existant (v4).
async fn welcome() -> bool {
    let r = root();
    toggle_class(&r, "boot", false);
    let d = Deferred::<bool>::new();
    let linked = d.sender();
    let pic = Rc::new(RefCell::new(String::new()));
    let pic_btn = h("button.avatar-pick.big")
        .attr("type", "button")
        .attr("aria-label", "Ajouter une photo")
        .child(icon("camera"))
        .el();
    let name = h("input.field")
        .attr("placeholder", "Votre nom")
        .attr("maxlength", "40")
        .attr("autocomplete", "name")
        .attr("aria-label", "Votre nom")
        .el();
    let err = h("p.error").el();
    let go = h("button.btn.primary.big")
        .attr("type", "button")
        .text("Créer mon code")
        .el();
    scope("welcome", || {
        let (pb, p) = (pic_btn.clone(), pic.clone());
        listen(&pic_btn, "click", move |_| {
            let (pb, p) = (pb.clone(), p.clone());
            spawn(async move {
                let Some(f) = media::pick_file("image/*", None).await else {
                    return;
                };
                if let Some(a) = media::avatar_from(&f).await {
                    replace(&pb, h("img").attr("src", &a).attr("alt", ""));
                    *p.borrow_mut() = a;
                }
            });
        });
        let (n, e, g, s, p) = (
            name.clone(),
            err.clone(),
            go.clone(),
            d.sender(),
            pic.clone(),
        );
        let submit = Rc::new(move || {
            let v = value(&n);
            if v.trim().is_empty() {
                e.set_text_content(Some(
                    "Choisissez un nom : c'est lui que verront vos contacts.",
                ));
                focus(&n);
                return;
            }
            let _ = g.set_attribute("disabled", "");
            g.set_text_content(Some("Création de vos clés…"));
            let (s, avatar) = (s.clone(), p.borrow().clone());
            spawn(async move {
                core::create_account(&v, avatar).await;
                s.send(Some(true));
            });
        });
        let s1 = submit.clone();
        listen(&go, "click", move |_| s1());
        listen(&name, "keydown", move |ev| {
            if ev
                .dyn_ref::<web_sys::KeyboardEvent>()
                .is_some_and(|k| k.key() == "Enter")
            {
                submit();
            }
        });
        replace(
            &r,
            h("div.welcome").child(
                h("div.welcome-card")
                    .child(h("img").attr("src", asset("logo.svg")).attr("alt", "").attr("width", "80").attr("height", "80"))
                    .child(h("h1").text("Bienvenue sur Talka"))
                    .child(h("p.muted").text("Pas de numéro de téléphone ici : votre appareil va créer vos clés de chiffrement et votre code Talka, que vous partagerez avec vos proches."))
                    .child(pic_btn.clone())
                    .child(name.clone())
                    .child(err.clone())
                    .child(go.clone())
                    .child(h("button.link").attr("type", "button").text("J'ai déjà Talka sur un autre appareil").click(move || {
                        let linked = linked.clone();
                        spawn(async move {
                            if link_this_device().await {
                                linked.send(Some(false));
                            }
                        })
                    }))
                    .child(h("button.link").attr("type", "button").text("Restaurer une sauvegarde").click(|| spawn(import_backup())))
                    .child(h("p.muted.tiny").child(icon("lock")).text(" Tout reste sur cet appareil. ").child(h("a").attr("href", asset("confidentialite/")).attr("target", "_blank").text("Confidentialité"))),
            ),
        );
    });
    timer(50, move || focus(&name));
    d.wait().await.unwrap_or(true)
}

/// Juste après la création : le code, à partager.
async fn reveal_code() {
    let d = Deferred::<bool>::new();
    let (s1, s2) = (d.sender(), d.sender());
    modal(
        "Voici votre code Talka 🎉",
        |_| {
            h("div.my-code")
                .child(h("div.code-big").text(pretty(&core::my_code())))
                .child(h("p.muted").text("C'est votre identifiant : il remplace le numéro de téléphone. Donnez-le à vos amis, ou partagez votre lien d'invitation."))
                .child(h("p.muted.small").text("Conseil : protégez Talka avec un code de verrouillage, et exportez de temps en temps une sauvegarde chiffrée (Réglages)."))
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Code de verrouillage", "ghost", move |m| {
                    m.close();
                    let s = s1.clone();
                    spawn(async move {
                        setup_pin().await;
                        s.send(Some(true));
                    });
                }),
                action("C'est parti", "primary", |m| m.close()),
            ],
            on_close: Some(Box::new(move || {
                let s = s2.clone();
                timer(0, move || {
                    if query(".lock-screen.setup").is_none() {
                        s.send(Some(true));
                    }
                });
            })),
            ..Default::default()
        },
    );
    d.wait().await;
}
