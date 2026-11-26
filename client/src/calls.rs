//! Les appels audio et vidéo : WebRTC, d'appareil à appareil, à deux ou à
//! plusieurs (huit au plus, chacun relié à chacun : voir `mesh`, qui décide
//! qui appelle qui). Le relais ne transporte que la mise en relation
//! (offres, réponses, candidats ICE), chiffrée comme un message, et
//! seulement si l'autre est en ligne.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;

use js_sys::{Array, JSON};
use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{
    Element, HtmlMediaElement, MediaStream, MediaStreamConstraints, MediaStreamTrack,
    RtcConfiguration, RtcPeerConnection, RtcSdpType, RtcSessionDescriptionInit,
};

use crate::core;
use crate::crypto::random_id;
use crate::dom::{
    self, El, call, clear_interval, clear_timer, document, fmt_dur, get, h, icon, interval, listen,
    now, replace, scope_into, set, spawn, time, timer, toast, toggle_class, unique, window,
};
use crate::mesh::{Do, MAX_PEOPLE, Mesh};
use crate::model::{Body, CallEntry};
use crate::net::{self, State};
use crate::sounds;

/// La liaison avec un participant : sa connexion WebRTC et sa vignette.
struct Peer {
    pc: RtcPeerConnection,
    /// Ses candidats ICE arrivés avant sa description.
    pending: RefCell<Vec<JsValue>>,
    tile: Element,
}

struct Call {
    id: String,
    video: bool,
    incoming: bool,
    /// Qui nous a appelés, ou le premier appelé.
    first: String,
    /// Le groupe d'où l'appel est lancé, s'il y en a un.
    chat: String,
    at: f64,
    connected_at: Cell<f64>,
    mesh: RefCell<Mesh>,
    peers: RefCell<BTreeMap<String, Rc<Peer>>>,
    /// Des candidats ICE arrivés avant la liaison (un appel qui sonne).
    early: RefCell<BTreeMap<String, Vec<JsValue>>>,
    /// Tous ceux qui ont été de l'appel (pour le journal).
    ever: RefCell<BTreeSet<String>>,
    local: RefCell<Option<MediaStream>>,
    muted: Cell<bool>,
    cam_off: Cell<bool>,
    facing: RefCell<String>,
    ended: Cell<bool>,
    no_answer: Cell<i32>,
    ticker: Cell<i32>,
    zone: String,
    root: Element,
    grid: Element,
    local_video: Element,
    title: Element,
    people: Element,
    status: Element,
}

thread_local! {
    static CURRENT: RefCell<Option<Rc<Call>>> = const { RefCell::new(None) };
    static ICE: RefCell<Option<JsValue>> = const { RefCell::new(None) };
}

fn current() -> Option<Rc<Call>> {
    CURRENT.with(|c| c.borrow().clone())
}

pub fn in_call() -> bool {
    current().is_some()
}

async fn ice_servers() -> JsValue {
    if let Some(v) = ICE.with(|i| i.borrow().clone()) {
        return v;
    }
    let json = net::get_json("/api/ice")
        .await
        .map(|v| v["iceServers"].to_string())
        .unwrap_or_else(|| r#"[{"urls":"stun:stun.l.google.com:19302"}]"#.into());
    let v = JSON::parse(&json).unwrap_or_else(|_| Array::new().into());
    ICE.with(|i| *i.borrow_mut() = Some(v.clone()));
    v
}

fn set_status(c: &Call, text: &str) {
    c.status.set_text_content(Some(text));
}

/// Un signal de cet appel à `peer`. Les invitations, liaisons et `join`
/// d'un appel à plusieurs portent la liste de ses participants ; un appel
/// à deux part exactement comme avant.
fn send(c: &Call, peer: &str, op: &str, sdp: String, cand: Option<serde_json::Value>) {
    let (members, chat) = {
        let mesh = c.mesh.borrow();
        if mesh.is_group() && matches!(op, "offer" | "link" | "join") {
            (mesh.members(), c.chat.clone())
        } else {
            (Vec::new(), String::new())
        }
    };
    core::send_call_signal(
        peer,
        Body::Call {
            op: op.into(),
            call_id: c.id.clone(),
            video: c.video && matches!(op, "offer" | "link"),
            sdp,
            cand,
            members,
            chat,
        },
    );
}

/// Appeler un contact.
pub async fn start_call(peer: &str, video: bool) {
    start(vec![peer.to_owned()], video, String::new()).await;
}

/// Appeler depuis une discussion : le contact, ou tous les membres du
/// groupe (huit personnes au plus).
pub async fn call_chat(chat_id: &str, video: bool) {
    let Some(chat) = core::chat(chat_id).filter(|c| c.group) else {
        return start_call(chat_id, video).await;
    };
    let me = core::my_code();
    let others: Vec<String> = chat.members.iter().filter(|m| **m != me).cloned().collect();
    if others.len() >= MAX_PEOPLE {
        return toast(
            "Ce groupe compte plus de huit personnes : un appel en réunit huit au plus. Appelez depuis l'onglet Appels, en choisissant qui.",
        );
    }
    start_group_call(others, video, chat_id).await;
}

/// Appeler plusieurs personnes à la fois (les membres d'un groupe, ou des
/// contacts choisis) : huit personnes au plus, nous compris.
pub async fn start_group_call(peers: Vec<String>, video: bool, chat: &str) {
    start(peers, video, chat.to_owned()).await;
}

async fn start(peers: Vec<String>, video: bool, chat: String) {
    if in_call() {
        return toast("Un appel est déjà en cours.");
    }
    if net::state() != State::Online {
        return toast("Pas de connexion au réseau.");
    }
    let me = core::my_code();
    let mut seen = BTreeSet::new();
    let peers: Vec<String> = peers
        .into_iter()
        .filter(|p| *p != me && !core::blocked(p) && seen.insert(p.clone()))
        .collect();
    let Some(first) = peers.first().cloned() else {
        return toast("Personne à appeler.");
    };
    if peers.len() >= MAX_PEOPLE {
        return toast("Un appel réunit huit personnes au plus.");
    }
    let (mesh, todo) = Mesh::outgoing(&me, &peers);
    let c = new_call(random_id(10), first, chat, mesh, video, false);
    if !get_media(&c).await {
        // Personne n'a encore été appelé.
        end(&c, "Micro ou caméra refusés.", false).await;
        return;
    }
    run(&c, todo, "end", "Appel impossible").await;
    if c.ended.get() {
        return;
    }
    set_status(&c, "Appel…");
    sounds::ring(true);
    wait_for_answers(&c);
}

/// Sans réponse en 45 secondes, les invitations en attente s'arrêtent (et
/// l'appel avec elles, s'il n'y a personne d'autre).
fn wait_for_answers(c: &Rc<Call>) {
    clear_timer(c.no_answer.get());
    let cc = c.clone();
    c.no_answer.set(timer(45_000, move || {
        let todo = cc.mesh.borrow_mut().no_answer();
        let cc = cc.clone();
        spawn(async move { run(&cc, todo, "end", "Pas de réponse").await });
    }));
}

fn new_call(
    id: String,
    first: String,
    chat: String,
    mesh: Mesh,
    video: bool,
    incoming: bool,
) -> Rc<Call> {
    let zone = unique("call");
    let grid = h("div.call-grid").el();
    let local_video = h("video.call-local")
        .attr("autoplay", "")
        .attr("playsinline", "")
        .prop("muted", true)
        .el();
    let status = h("div.call-status").el();
    let title = h("div.call-name").el();
    let people = h("div.call-people").el();
    let root = h("div.call")
        .class(if video { "video" } else { "audio" })
        .attr("role", "dialog")
        .el();
    let c = Rc::new(Call {
        id,
        video,
        incoming,
        first,
        chat,
        at: now(),
        connected_at: Cell::new(0.0),
        mesh: RefCell::new(mesh),
        peers: RefCell::new(BTreeMap::new()),
        early: RefCell::new(BTreeMap::new()),
        ever: RefCell::new(BTreeSet::new()),
        local: RefCell::new(None),
        muted: Cell::new(false),
        cam_off: Cell::new(false),
        facing: RefCell::new("user".into()),
        ended: Cell::new(false),
        no_answer: Cell::new(0),
        ticker: Cell::new(0),
        zone,
        root,
        grid,
        local_video,
        title,
        people,
        status,
    });
    build_ui(&c);
    refresh(&c);
    CURRENT.with(|cur| *cur.borrow_mut() = Some(c.clone()));
    let _ = document().body().expect("body").append_child(&c.root);
    c
}

async fn get_media(c: &Call) -> bool {
    let Ok(devices) = window().navigator().media_devices() else {
        return false;
    };
    let cons = MediaStreamConstraints::new();
    cons.set_audio(&JsValue::TRUE);
    if c.video {
        let v = js_sys::Object::new();
        set(&v, "facingMode", c.facing.borrow().as_str());
        cons.set_video(&v);
    }
    let Ok(p) = devices.get_user_media_with_constraints(&cons) else {
        return false;
    };
    let Ok(stream) = JsFuture::from(p).await else {
        return false;
    };
    let stream: MediaStream = stream.unchecked_into();
    if let Some(v) = c.local_video.dyn_ref::<HtmlMediaElement>() {
        v.set_src_object(Some(&stream));
    }
    *c.local.borrow_mut() = Some(stream);
    true
}

fn tracks(stream: &MediaStream) -> Vec<MediaStreamTrack> {
    stream
        .get_tracks()
        .iter()
        .map(|t| t.unchecked_into())
        .collect()
}

/// Fait ce que `mesh` a décidé. `bye` : ce que dit un au revoir (`end`, ou
/// `reject` pour un appel qui sonnait) ; `reason` : ce qu'affiche la fin.
async fn run(c: &Rc<Call>, todo: Vec<Do>, bye: &str, reason: &str) {
    for d in todo {
        match d {
            Do::Offer { peer, ring } => offer(c, &peer, ring).await,
            Do::Answer { peer, sdp } => answer(c, &peer, &sdp).await,
            Do::Join { peer } => send(c, &peer, "join", String::new(), None),
            Do::Hangup { peer } => {
                send(c, &peer, bye, String::new(), None);
                close(c, &peer);
            }
            Do::Close { peer } => close(c, &peer),
            Do::End => end(c, reason, false).await,
        }
    }
    refresh(c);
}

/// Une liaison qui n'a pas pu se faire, ou qui a échoué : `mesh` l'oublie
/// (sans rien attendre : appelé aussi depuis les écouteurs de WebRTC).
fn lost(c: &Rc<Call>, peer: &str, reason: &'static str) {
    let todo = c.mesh.borrow_mut().on_gone(peer);
    for d in todo {
        match d {
            Do::Close { peer } => close(c, &peer),
            Do::End => {
                let cc = c.clone();
                spawn(async move { end(&cc, reason, false).await });
            }
            _ => {}
        }
    }
    refresh(c);
}

async fn offer(c: &Rc<Call>, peer: &str, ring: bool) {
    let Some(p) = create_peer(c, peer).await else {
        return lost(c, peer, "Appel impossible");
    };
    let Ok(offer) = JsFuture::from(p.pc.create_offer()).await else {
        return lost(c, peer, "Appel impossible");
    };
    let sdp = get(&offer, "sdp").as_string().unwrap_or_default();
    let desc = RtcSessionDescriptionInit::new(RtcSdpType::Offer);
    desc.set_sdp(&sdp);
    let _ = JsFuture::from(p.pc.set_local_description(&desc)).await;
    send(c, peer, if ring { "offer" } else { "link" }, sdp, None);
}

async fn answer(c: &Rc<Call>, peer: &str, remote: &str) {
    let Some(p) = create_peer(c, peer).await else {
        return lost(c, peer, "Appel impossible");
    };
    let offer = RtcSessionDescriptionInit::new(RtcSdpType::Offer);
    offer.set_sdp(remote);
    let _ = JsFuture::from(p.pc.set_remote_description(&offer)).await;
    flush_candidates(&p).await;
    let Ok(answer) = JsFuture::from(p.pc.create_answer()).await else {
        return lost(c, peer, "Appel impossible");
    };
    let sdp = get(&answer, "sdp").as_string().unwrap_or_default();
    let desc = RtcSessionDescriptionInit::new(RtcSdpType::Answer);
    desc.set_sdp(&sdp);
    let _ = JsFuture::from(p.pc.set_local_description(&desc)).await;
    send(c, peer, "answer", sdp, None);
}

/// La connexion WebRTC avec `peer`, et sa vignette (son image, sa voix).
async fn create_peer(c: &Rc<Call>, peer: &str) -> Option<Rc<Peer>> {
    let config = RtcConfiguration::new();
    config.set_ice_servers(&ice_servers().await);
    if c.ended.get() {
        return None;
    }
    let pc = RtcPeerConnection::new_with_configuration(&config).ok()?;
    if let Some(stream) = c.local.borrow().as_ref() {
        for t in tracks(stream) {
            let _ = call(&pc, "addTrack", &[t.into(), stream.into()]);
        }
    }
    let video = h("video").attr("autoplay", "").attr("playsinline", "").el();
    let audio = h("audio").attr("autoplay", "").el();
    let tile = h("div.call-tile")
        .child(video.clone())
        .child(audio.clone())
        .child(h("span.call-tile-name").text(core::display_name(peer)))
        .el();
    scope_into(&c.zone, || {
        let (cc, who) = (c.clone(), peer.to_owned());
        listen(&pc, "icecandidate", move |e| {
            let cand = get(&e, "candidate");
            if cand.is_null() || cand.is_undefined() {
                return;
            }
            let json = call(&cand, "toJSON", &[])
                .ok()
                .and_then(|v| JSON::stringify(&v).ok())
                .and_then(|s| s.as_string());
            if let Some(json) = json.and_then(|s| serde_json::from_str(&s).ok()) {
                send(&cc, &who, "ice", String::new(), Some(json));
            }
        });
        let (v, a) = (video.clone(), audio.clone());
        listen(&pc, "track", move |e| {
            let streams = get(&e, "streams");
            let stream = Array::from(&streams).get(0);
            if let Ok(stream) = stream.dyn_into::<MediaStream>() {
                for el in [&v, &a] {
                    if let Some(m) = el.dyn_ref::<HtmlMediaElement>() {
                        m.set_src_object(Some(&stream));
                        dom::play(m);
                    }
                }
            }
        });
        let (cc, who, p, t) = (c.clone(), peer.to_owned(), pc.clone(), tile.clone());
        listen(&pc, "connectionstatechange", move |_| {
            let state = get(&p, "connectionState").as_string().unwrap_or_default();
            toggle_class(&t, "waiting", state != "connected");
            match state.as_str() {
                "connected" => connected(&cc),
                "failed" => lost(&cc, &who, "La connexion a échoué"),
                "disconnected" if !cc.mesh.borrow().is_group() => set_status(&cc, "Reconnexion…"),
                _ => {}
            }
        });
    });
    toggle_class(&tile, "waiting", true);
    let p = Rc::new(Peer {
        pc,
        pending: RefCell::new(c.early.borrow_mut().remove(peer).unwrap_or_default()),
        tile,
    });
    if let Some(old) = c.peers.borrow_mut().insert(peer.to_owned(), p.clone()) {
        old.pc.close();
        old.tile.remove();
    }
    let _ = c.grid.append_child(&p.tile);
    c.ever.borrow_mut().insert(peer.to_owned());
    refresh(c);
    Some(p)
}

/// Le premier relié : l'appel commence (son chronomètre).
fn connected(c: &Rc<Call>) {
    if c.connected_at.get() > 0.0 {
        return;
    }
    c.connected_at.set(now());
    sounds::stop_ring();
    toggle_class(&c.root, "live", true);
    let c2 = c.clone();
    c.ticker.set(interval(1000, move || {
        set_status(&c2, &fmt_dur((now() - c2.connected_at.get()) / 1000.0))
    }));
    set_status(c, "0:00");
}

fn close(c: &Call, peer: &str) {
    if let Some(p) = c.peers.borrow_mut().remove(peer) {
        p.pc.close();
        p.tile.remove();
    }
    c.early.borrow_mut().remove(peer);
}

async fn add_candidate(pc: &RtcPeerConnection, cand: &JsValue) {
    let _ = dom::await_promise(call(pc, "addIceCandidate", std::slice::from_ref(cand))).await;
}

async fn flush_candidates(p: &Peer) {
    let pending: Vec<JsValue> = p.pending.borrow_mut().drain(..).collect();
    for cand in pending {
        add_candidate(&p.pc, &cand).await;
    }
}

fn peer_of(c: &Call, code: &str) -> Option<Rc<Peer>> {
    c.peers.borrow().get(code).cloned()
}

/// Un signal reçu (invitation, liaison, réponse, candidat, fin).
pub async fn on_signal(from: String, body: Body, live: bool) {
    let Body::Call {
        op,
        call_id,
        video,
        sdp,
        cand,
        members,
        chat,
    } = body
    else {
        return;
    };
    // Pris ou refusé sur un autre de nos appareils (v4) : on cesse de sonner.
    if op == "elsewhere" {
        if let Some(c) =
            current().filter(|c| c.id == call_id && c.incoming && !c.mesh.borrow().joined())
        {
            end(&c, "Pris sur un autre appareil", false).await;
        }
        return;
    }
    let this = current().filter(|c| c.id == call_id && !c.ended.get());
    let Some(c) = this else {
        if op == "offer" {
            ring(from, call_id, video, sdp, members, chat, live).await;
        }
        return;
    };
    match op.as_str() {
        "offer" | "link" | "join" if !live => {}
        "offer" | "link" => {
            let todo = c
                .mesh
                .borrow_mut()
                .on_offer(&from, &sdp, &members, op == "offer");
            run(&c, todo, "end", "Appel terminé").await;
        }
        "join" => {
            let todo = c.mesh.borrow_mut().on_join(&from, &members);
            run(&c, todo, "end", "Appel terminé").await;
        }
        "answer" => {
            if !c.mesh.borrow_mut().on_answer(&from) {
                return;
            }
            if c.connected_at.get() == 0.0 {
                set_status(&c, "Connexion…");
            }
            let Some(p) = peer_of(&c, &from) else {
                return;
            };
            let desc = RtcSessionDescriptionInit::new(RtcSdpType::Answer);
            desc.set_sdp(&sdp);
            let _ = JsFuture::from(p.pc.set_remote_description(&desc)).await;
            flush_candidates(&p).await;
            refresh(&c);
        }
        "ice" => {
            let Some(cand) = cand.and_then(|v| JSON::parse(&v.to_string()).ok()) else {
                return;
            };
            match peer_of(&c, &from) {
                Some(p) if p.pc.remote_description().is_some() => add_candidate(&p.pc, &cand).await,
                Some(p) => p.pending.borrow_mut().push(cand),
                None => c.early.borrow_mut().entry(from).or_default().push(cand),
            }
        }
        "reject" | "busy" | "end" => {
            let group = c.mesh.borrow().is_group();
            let missed = c.incoming && c.connected_at.get() == 0.0;
            let reason = match op.as_str() {
                _ if group => "Appel terminé",
                "reject" => "Appel refusé",
                "busy" => "Occupé",
                _ if missed => "Appel manqué",
                _ => "Appel terminé",
            };
            let todo = c.mesh.borrow_mut().on_gone(&from);
            let last = todo.contains(&Do::End);
            run(&c, todo, "end", reason).await;
            if group && !last {
                let name = core::display_name(&from);
                toast(&match op.as_str() {
                    "end" => format!("{name} a quitté l'appel."),
                    "busy" => format!("{name} est déjà en ligne."),
                    _ => format!("{name} ne vient pas."),
                });
            }
        }
        _ => {}
    }
}

/// Une invitation, sans appel en cours : ça sonne (ou « occupé »).
async fn ring(
    from: String,
    call_id: String,
    video: bool,
    sdp: String,
    members: Vec<String>,
    chat: String,
    live: bool,
) {
    if !live {
        return; // une vieille offre : l'appel est passé
    }
    if in_call() || core::blocked(&from) {
        core::send_call_signal(
            &from,
            Body::Call {
                op: "busy".into(),
                call_id: call_id.clone(),
                video: false,
                sdp: String::new(),
                cand: None,
                members: Vec::new(),
                chat: String::new(),
            },
        );
        core::log_call(CallEntry {
            id: call_id,
            peer: from,
            video,
            incoming: true,
            at: now(),
            dur: 0.0,
            missed: true,
            members: members
                .into_iter()
                .filter(|m| *m != core::my_code())
                .collect(),
            chat,
        })
        .await;
        return;
    }
    let mesh = Mesh::incoming(&core::my_code(), &from, &sdp, &members);
    let group = mesh.is_group();
    let c = new_call(call_id, from.clone(), chat, mesh, video, true);
    toggle_class(&c.root, "ringing", true);
    set_status(
        &c,
        match (group, video) {
            (true, true) => "Appel vidéo de groupe entrant…",
            (true, false) => "Appel de groupe entrant…",
            (false, true) => "Appel vidéo entrant…",
            (false, false) => "Appel audio entrant…",
        },
    );
    sounds::ring(false);
    notify(&from, video, group);
    let cc = c.clone();
    c.no_answer.set(timer(45_000, move || {
        spawn(async move { end(&cc, "Appel manqué", true).await })
    }));
}

async fn accept(c: Rc<Call>) {
    clear_timer(c.no_answer.get());
    sounds::stop_ring();
    // Nos autres appareils cessent de sonner.
    core::call_taken(&c.id);
    toggle_class(&c.root, "ringing", false);
    set_status(&c, "Connexion…");
    if !get_media(&c).await {
        let todo = c.mesh.borrow_mut().hangup();
        run(&c, todo, "reject", "Micro ou caméra refusés.").await;
        return;
    }
    let todo = c.mesh.borrow_mut().accept();
    run(&c, todo, "end", "Appel terminé").await;
}

fn hangup(c: &Rc<Call>) {
    let ringing = !c.mesh.borrow().joined();
    if ringing {
        core::call_taken(&c.id);
    }
    let todo = c.mesh.borrow_mut().hangup();
    let c = c.clone();
    spawn(async move {
        run(
            &c,
            todo,
            if ringing { "reject" } else { "end" },
            "Appel terminé",
        )
        .await
    });
}

/// Faire sonner d'autres contacts pendant l'appel.
async fn add_people(c: Rc<Call>) {
    let mut exclude = c.mesh.borrow().others();
    exclude.push(core::my_code());
    let room = MAX_PEOPLE.saturating_sub(exclude.len());
    if room == 0 {
        return toast("Un appel réunit huit personnes au plus.");
    }
    let Some(list) = crate::ui::pick_contacts("Ajouter à l'appel…", &exclude, "Appeler").await
    else {
        return;
    };
    let mut any = false;
    for code in list {
        if c.ended.get() {
            return;
        }
        let added = c.mesh.borrow_mut().add(&code);
        match added {
            Ok(todo) => {
                any = true;
                run(&c, todo, "end", "Appel terminé").await;
            }
            Err(_) => {
                toast("Un appel réunit huit personnes au plus.");
                break;
            }
        }
    }
    if any {
        wait_for_answers(&c);
    }
}

async fn end(c: &Rc<Call>, reason: &str, missed: bool) {
    if c.ended.replace(true) {
        return;
    }
    clear_timer(c.no_answer.get());
    clear_interval(c.ticker.get());
    sounds::stop_ring();
    if c.connected_at.get() > 0.0 {
        sounds::hangup();
    }
    if let Some(s) = c.local.borrow().as_ref() {
        for t in tracks(s) {
            t.stop();
        }
    }
    let peers: Vec<Rc<Peer>> = std::mem::take(&mut *c.peers.borrow_mut())
        .into_values()
        .collect();
    for p in peers {
        p.pc.close();
    }
    set_status(c, reason);
    toggle_class(&c.root, "ended", true);
    let connected = c.connected_at.get();
    let dur = if connected > 0.0 {
        ((now() - connected) / 1000.0).round()
    } else {
        0.0
    };
    let mut ever: BTreeSet<String> = c.ever.borrow().clone();
    ever.extend(c.mesh.borrow().others());
    ever.insert(c.first.clone());
    core::log_call(CallEntry {
        id: c.id.clone(),
        peer: c.first.clone(),
        video: c.video,
        incoming: c.incoming,
        at: c.at,
        dur,
        missed: missed || (c.incoming && dur == 0.0),
        members: if ever.len() > 1 {
            ever.into_iter().collect()
        } else {
            Vec::new()
        },
        chat: c.chat.clone(),
    })
    .await;
    let cc = c.clone();
    timer(1400, move || {
        cc.root.remove();
        dom::drop_scope(&cc.zone);
        CURRENT.with(|cur| {
            let mut cur = cur.borrow_mut();
            if cur.as_ref().is_some_and(|x| Rc::ptr_eq(x, &cc)) {
                *cur = None;
            }
        });
    });
}

fn notify(from: &str, video: bool, group: bool) {
    if dom::visible() || !core::setting(|s| s.notify) || !crate::push::granted() {
        return;
    }
    let opts = web_sys::NotificationOptions::new();
    opts.set_body(match (group, video) {
        (true, true) => "📹 Appel vidéo de groupe entrant",
        (true, false) => "📞 Appel de groupe entrant",
        (false, true) => "📹 Appel vidéo entrant",
        (false, false) => "📞 Appel entrant",
    });
    opts.set_icon(&crate::ui::asset("icons/icon-192.png"));
    opts.set_tag("talka-call");
    let _ = web_sys::Notification::new_with_options(&core::display_name(from), &opts);
}

/// Le titre d'un appel : le nom de l'autre, du groupe, ou des participants.
fn title_of(first: &str, chat: &str, others: &[String]) -> String {
    if others.len() <= 1 {
        return core::display_name(others.first().map_or(first, String::as_str));
    }
    if let Some(c) = core::chat(chat) {
        return core::chat_title(&c);
    }
    names(others)
}

/// « Léa, Max et 3 autres ».
fn names(codes: &[String]) -> String {
    let list: Vec<String> = codes.iter().map(|c| core::display_name(c)).collect();
    match list.len() {
        0 => String::new(),
        1 => list[0].clone(),
        2 | 3 => {
            let (last, rest) = list.split_last().expect("non vide");
            format!("{} et {last}", rest.join(", "))
        }
        n => format!("{}, {} et {} autres", list[0], list[1], n - 2),
    }
}

/// Ce que l'écran montre de l'appel : son titre, qui en est, la grille.
fn refresh(c: &Call) {
    let (others, group) = {
        let m = c.mesh.borrow();
        (m.others(), m.is_group())
    };
    let title = title_of(&c.first, &c.chat, &others);
    let people = if group { names(&others) } else { String::new() };
    c.title.set_text_content(Some(&title));
    // Sous le nom du groupe, qui en est ; pas deux fois les mêmes noms.
    c.people
        .set_text_content(Some(if people == title { "" } else { &people }));
    toggle_class(&c.root, "group", group);
    let n = c.peers.borrow().len();
    let cols = match n {
        0 | 1 => 1,
        2..=4 => 2,
        _ => 3,
    };
    let _ = c
        .grid
        .unchecked_ref::<web_sys::HtmlElement>()
        .style()
        .set_property("--cols", &cols.to_string());
}

fn call_btn(name: &str, label: &str, class: &str, f: impl FnMut() + 'static) -> El {
    h("button.call-btn")
        .class(class)
        .attr("type", "button")
        .attr("aria-label", label)
        .attr("title", label)
        .child(icon(name))
        .click(f)
}

fn build_ui(c: &Rc<Call>) {
    let group = c.mesh.borrow().is_group();
    let avatar = if group {
        String::new()
    } else {
        core::avatar_of(&c.first)
    };
    let name = core::display_name(&c.first);
    scope_into(&c.zone, || {
        let face = if group {
            h("div.call-avatar").child(icon("group"))
        } else if avatar.is_empty() {
            h("div.call-avatar").text(
                name.trim_start_matches('~')
                    .chars()
                    .next()
                    .map(|ch| ch.to_uppercase().to_string())
                    .unwrap_or_default(),
            )
        } else {
            h("div.call-avatar").child(h("img").attr("src", &avatar).attr("alt", ""))
        };
        let mute = h("button.call-btn")
            .attr("type", "button")
            .attr("aria-label", "Couper le micro")
            .attr("title", "Couper le micro")
            .child(icon("mic"))
            .el();
        let cc = c.clone();
        let m2 = mute.clone();
        listen(&mute, "click", move |_| {
            cc.muted.set(!cc.muted.get());
            if let Some(s) = cc.local.borrow().as_ref() {
                for t in s.get_audio_tracks().iter() {
                    t.unchecked_into::<MediaStreamTrack>()
                        .set_enabled(!cc.muted.get());
                }
            }
            replace(&m2, icon(if cc.muted.get() { "micoff" } else { "mic" }));
            toggle_class(&m2, "off", cc.muted.get());
        });
        let cam = h("button.call-btn")
            .attr("type", "button")
            .attr("aria-label", "Caméra")
            .attr("title", "Caméra")
            .child(icon("video"))
            .el();
        let cc = c.clone();
        let c2 = cam.clone();
        listen(&cam, "click", move |_| {
            cc.cam_off.set(!cc.cam_off.get());
            if let Some(s) = cc.local.borrow().as_ref() {
                for t in s.get_video_tracks().iter() {
                    t.unchecked_into::<MediaStreamTrack>()
                        .set_enabled(!cc.cam_off.get());
                }
            }
            replace(
                &c2,
                icon(if cc.cam_off.get() {
                    "videooff"
                } else {
                    "video"
                }),
            );
            toggle_class(&c2, "off", cc.cam_off.get());
        });
        let cc = c.clone();
        let flip = call_btn("switchcam", "Changer de caméra", "", move || {
            let cc = cc.clone();
            spawn(async move { switch_camera(&cc).await });
        });
        let cc = c.clone();
        let add = call_btn("plus", "Ajouter quelqu'un", "", move || {
            spawn(add_people(cc.clone()))
        });
        let (c1, c3, c4) = (c.clone(), c.clone(), c.clone());
        replace(
            &c.root,
            vec![
                c.grid.clone(),
                if c.video {
                    c.local_video.clone()
                } else {
                    h("span").el()
                },
                h("div.call-info")
                    .child(face)
                    .child(c.title.clone())
                    .child(c.people.clone())
                    .child(
                        h("div.call-e2e")
                            .child(icon("lock"))
                            .text(" Chiffré de bout en bout"),
                    )
                    .child(c.status.clone())
                    .el(),
                h("div.call-actions.incoming-actions")
                    .child(call_btn("hangup", "Refuser", "red", move || hangup(&c1)))
                    .child(call_btn(
                        if c.video { "video" } else { "phone" },
                        "Répondre",
                        "green",
                        move || spawn(accept(c3.clone())),
                    ))
                    .el(),
                h("div.call-actions.live-actions")
                    .child(c.video.then_some(flip))
                    .child(c.video.then_some(cam))
                    .child(mute)
                    .child(add)
                    .child(call_btn("hangup", "Raccrocher", "red", move || hangup(&c4)))
                    .el(),
            ],
        );
        let _ = c.root.set_attribute(
            "aria-label",
            &if group {
                "Appel de groupe".to_owned()
            } else {
                format!("Appel avec {name}")
            },
        );
    });
}

async fn switch_camera(c: &Rc<Call>) {
    let Some(local) = c.local.borrow().clone() else {
        return;
    };
    let next = if c.facing.borrow().as_str() == "user" {
        "environment"
    } else {
        "user"
    };
    *c.facing.borrow_mut() = next.into();
    let Ok(devices) = window().navigator().media_devices() else {
        return;
    };
    let cons = MediaStreamConstraints::new();
    let v = js_sys::Object::new();
    set(&v, "facingMode", next);
    cons.set_video(&v);
    let Ok(p) = devices.get_user_media_with_constraints(&cons) else {
        return;
    };
    let Ok(stream) = JsFuture::from(p).await else {
        toast("Impossible de changer de caméra.");
        return;
    };
    let stream: MediaStream = stream.unchecked_into();
    let Some(track) = stream
        .get_video_tracks()
        .iter()
        .next()
        .map(|t| t.unchecked_into::<MediaStreamTrack>())
    else {
        return;
    };
    // La nouvelle caméra pour chacun des participants.
    let peers: Vec<Rc<Peer>> = c.peers.borrow().values().cloned().collect();
    for p in peers {
        for sender in p.pc.get_senders().iter() {
            let sender: web_sys::RtcRtpSender = sender.unchecked_into();
            if sender.track().is_some_and(|t| t.kind() == "video") {
                let _ = JsFuture::from(sender.replace_track(Some(&track))).await;
            }
        }
    }
    for t in local.get_video_tracks().iter() {
        let t: MediaStreamTrack = t.unchecked_into();
        t.stop();
        local.remove_track(&t);
    }
    local.add_track(&track);
    if let Some(v) = c.local_video.dyn_ref::<HtmlMediaElement>() {
        v.set_src_object(Some(&local));
    }
}

/// Le nom d'une ligne du journal : l'autre, le groupe, ou les participants.
pub fn entry_title(e: &CallEntry) -> String {
    if e.members.len() <= 1 {
        return core::display_name(&e.peer);
    }
    let me = core::my_code();
    let others: Vec<String> = e.members.iter().filter(|m| **m != me).cloned().collect();
    title_of(&e.peer, &e.chat, &others)
}

/// Une ligne de l'historique : « Appel audio reçu · 2:31 ».
pub fn describe(e: &CallEntry) -> String {
    let kind = match (e.members.len() > 1, e.video) {
        (true, true) => "vidéo de groupe",
        (true, false) => "de groupe",
        (false, true) => "vidéo",
        (false, false) => "audio",
    };
    if e.missed {
        return format!("Appel {kind} manqué · {}", time(e.at));
    }
    let dir = if e.incoming { "reçu" } else { "émis" };
    if e.dur > 0.0 {
        format!("Appel {kind} {dir} · {}", fmt_dur(e.dur))
    } else {
        format!("Appel {kind} {dir}")
    }
}

/// Rappeler depuis le journal : la même personne, ou les mêmes personnes.
pub async fn call_again(e: &CallEntry) {
    if e.members.len() > 1 {
        let me = core::my_code();
        let others: Vec<String> = e.members.iter().filter(|m| **m != me).cloned().collect();
        start_group_call(others, e.video, &e.chat).await;
    } else {
        start_call(&e.peer, e.video).await;
    }
}
