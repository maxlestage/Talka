//! Les appels audio et vidéo : WebRTC, d'appareil à appareil. Le relais ne
//! transporte que la mise en relation (offre, réponse, candidats ICE),
//! chiffrée comme un message, et seulement si l'autre est en ligne.

use std::cell::{Cell, RefCell};
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
use crate::model::{Body, CallEntry};
use crate::net::{self, State};
use crate::sounds;

struct Call {
    id: String,
    peer: String,
    video: bool,
    incoming: bool,
    at: f64,
    connected_at: Cell<f64>,
    offer: RefCell<String>,
    pc: RefCell<Option<RtcPeerConnection>>,
    local: RefCell<Option<MediaStream>>,
    pending: RefCell<Vec<JsValue>>,
    muted: Cell<bool>,
    cam_off: Cell<bool>,
    facing: RefCell<String>,
    ended: Cell<bool>,
    no_answer: Cell<i32>,
    ticker: Cell<i32>,
    zone: String,
    root: Element,
    remote: Element,
    remote_audio: Element,
    local_video: Element,
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

/// Appeler un contact.
pub async fn start_call(peer: &str, video: bool) {
    if in_call() {
        return toast("Un appel est déjà en cours.");
    }
    if net::state() != State::Online {
        return toast("Pas de connexion au réseau.");
    }
    let c = new_call(random_id(10), peer.to_owned(), video, false);
    if !get_media(&c).await {
        end(&c, "Micro ou caméra refusés.", false).await;
        return;
    }
    create_peer(&c).await;
    let Some(pc) = c.pc.borrow().clone() else {
        return;
    };
    let Ok(offer) = JsFuture::from(pc.create_offer()).await else {
        end(&c, "Appel impossible", false).await;
        return;
    };
    let sdp = get(&offer, "sdp").as_string().unwrap_or_default();
    let desc = RtcSessionDescriptionInit::new(RtcSdpType::Offer);
    desc.set_sdp(&sdp);
    let _ = JsFuture::from(pc.set_local_description(&desc)).await;
    core::send_call_signal(
        peer,
        Body::Call {
            op: "offer".into(),
            call_id: c.id.clone(),
            video,
            sdp,
            cand: None,
        },
    );
    set_status(&c, "Appel…");
    sounds::ring(true);
    let cc = c.clone();
    c.no_answer.set(timer(45_000, move || {
        core::send_call_signal(
            &cc.peer,
            Body::Call {
                op: "end".into(),
                call_id: cc.id.clone(),
                video: false,
                sdp: String::new(),
                cand: None,
            },
        );
        spawn(async move { end(&cc, "Pas de réponse", false).await });
    }));
}

fn new_call(id: String, peer: String, video: bool, incoming: bool) -> Rc<Call> {
    let zone = unique("call");
    let remote = h("video.call-remote")
        .attr("autoplay", "")
        .attr("playsinline", "")
        .el();
    let remote_audio = h("audio").attr("autoplay", "").el();
    let local_video = h("video.call-local")
        .attr("autoplay", "")
        .attr("playsinline", "")
        .prop("muted", true)
        .el();
    let status = h("div.call-status").el();
    let root = h("div.call")
        .class(if video { "video" } else { "audio" })
        .attr("role", "dialog")
        .el();
    let c = Rc::new(Call {
        id,
        peer,
        video,
        incoming,
        at: now(),
        connected_at: Cell::new(0.0),
        offer: RefCell::new(String::new()),
        pc: RefCell::new(None),
        local: RefCell::new(None),
        pending: RefCell::new(Vec::new()),
        muted: Cell::new(false),
        cam_off: Cell::new(false),
        facing: RefCell::new("user".into()),
        ended: Cell::new(false),
        no_answer: Cell::new(0),
        ticker: Cell::new(0),
        zone,
        root,
        remote,
        remote_audio,
        local_video,
        status,
    });
    build_ui(&c);
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

async fn create_peer(c: &Rc<Call>) {
    let config = RtcConfiguration::new();
    config.set_ice_servers(&ice_servers().await);
    let Ok(pc) = RtcPeerConnection::new_with_configuration(&config) else {
        return;
    };
    if let Some(stream) = c.local.borrow().as_ref() {
        for t in tracks(stream) {
            let _ = call(&pc, "addTrack", &[t.into(), stream.into()]);
        }
    }
    scope_into(&c.zone, || {
        let cc = c.clone();
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
                core::send_call_signal(
                    &cc.peer,
                    Body::Call {
                        op: "ice".into(),
                        call_id: cc.id.clone(),
                        video: false,
                        sdp: String::new(),
                        cand: Some(json),
                    },
                );
            }
        });
        let cc = c.clone();
        listen(&pc, "track", move |e| {
            let streams = get(&e, "streams");
            let stream = Array::from(&streams).get(0);
            if let Ok(stream) = stream.dyn_into::<MediaStream>() {
                for el in [&cc.remote, &cc.remote_audio] {
                    if let Some(m) = el.dyn_ref::<HtmlMediaElement>() {
                        m.set_src_object(Some(&stream));
                        dom::play(m);
                    }
                }
            }
        });
        let cc = c.clone();
        let p = pc.clone();
        listen(&pc, "connectionstatechange", move |_| {
            let state = get(&p, "connectionState").as_string().unwrap_or_default();
            match state.as_str() {
                "connected" if cc.connected_at.get() == 0.0 => {
                    cc.connected_at.set(now());
                    sounds::stop_ring();
                    toggle_class(&cc.root, "live", true);
                    let c2 = cc.clone();
                    cc.ticker.set(interval(1000, move || {
                        set_status(&c2, &fmt_dur((now() - c2.connected_at.get()) / 1000.0))
                    }));
                    set_status(&cc, "0:00");
                }
                "failed" => {
                    let c2 = cc.clone();
                    spawn(async move { end(&c2, "La connexion a échoué", false).await });
                }
                "disconnected" => set_status(&cc, "Reconnexion…"),
                _ => {}
            }
        });
    });
    *c.pc.borrow_mut() = Some(pc);
}

async fn add_candidate(pc: &RtcPeerConnection, cand: &JsValue) {
    let _ = dom::await_promise(call(pc, "addIceCandidate", std::slice::from_ref(cand))).await;
}

async fn flush_candidates(c: &Call) {
    let Some(pc) = c.pc.borrow().clone() else {
        return;
    };
    let pending: Vec<JsValue> = c.pending.borrow_mut().drain(..).collect();
    for cand in pending {
        add_candidate(&pc, &cand).await;
    }
}

/// Un signal reçu (offre, réponse, candidat, fin).
pub async fn on_signal(from: String, body: Body, live: bool) {
    let Body::Call {
        op,
        call_id,
        video,
        sdp,
        cand,
    } = body
    else {
        return;
    };
    if op == "offer" {
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
            })
            .await;
            return;
        }
        let c = new_call(call_id, from.clone(), video, true);
        *c.offer.borrow_mut() = sdp;
        toggle_class(&c.root, "ringing", true);
        set_status(
            &c,
            if video {
                "Appel vidéo entrant…"
            } else {
                "Appel audio entrant…"
            },
        );
        sounds::ring(false);
        notify(&from, video);
        let cc = c.clone();
        c.no_answer.set(timer(45_000, move || {
            spawn(async move { end(&cc, "Appel manqué", true).await })
        }));
        return;
    }
    // Pris ou refusé sur un autre de nos appareils (v4) : on cesse de sonner.
    if op == "elsewhere" {
        if let Some(c) =
            current().filter(|c| c.id == call_id && c.incoming && c.pc.borrow().is_none())
        {
            end(&c, "Pris sur un autre appareil", false).await;
        }
        return;
    }
    let Some(c) = current().filter(|c| c.id == call_id && c.peer == from) else {
        return;
    };
    match op.as_str() {
        "answer" => {
            clear_timer(c.no_answer.get());
            set_status(&c, "Connexion…");
            let Some(pc) = c.pc.borrow().clone() else {
                return;
            };
            let desc = RtcSessionDescriptionInit::new(RtcSdpType::Answer);
            desc.set_sdp(&sdp);
            let _ = JsFuture::from(pc.set_remote_description(&desc)).await;
            flush_candidates(&c).await;
        }
        "ice" => {
            let Some(cand) = cand.and_then(|v| JSON::parse(&v.to_string()).ok()) else {
                return;
            };
            let pc = c.pc.borrow().clone();
            match pc {
                Some(pc) if pc.remote_description().is_some() => add_candidate(&pc, &cand).await,
                _ => c.pending.borrow_mut().push(cand),
            }
        }
        "reject" => end(&c, "Appel refusé", false).await,
        "busy" => end(&c, "Occupé", false).await,
        "end" => {
            let missed = c.incoming && c.connected_at.get() == 0.0;
            end(
                &c,
                if missed {
                    "Appel manqué"
                } else {
                    "Appel terminé"
                },
                missed,
            )
            .await;
        }
        _ => {}
    }
}

async fn accept(c: Rc<Call>) {
    clear_timer(c.no_answer.get());
    sounds::stop_ring();
    // Nos autres appareils cessent de sonner.
    core::call_taken(&c.id);
    toggle_class(&c.root, "ringing", false);
    set_status(&c, "Connexion…");
    if !get_media(&c).await {
        core::send_call_signal(
            &c.peer,
            Body::Call {
                op: "reject".into(),
                call_id: c.id.clone(),
                video: false,
                sdp: String::new(),
                cand: None,
            },
        );
        end(&c, "Micro ou caméra refusés.", false).await;
        return;
    }
    create_peer(&c).await;
    let Some(pc) = c.pc.borrow().clone() else {
        return;
    };
    let offer = RtcSessionDescriptionInit::new(RtcSdpType::Offer);
    offer.set_sdp(&c.offer.borrow());
    let _ = JsFuture::from(pc.set_remote_description(&offer)).await;
    flush_candidates(&c).await;
    let Ok(answer) = JsFuture::from(pc.create_answer()).await else {
        return;
    };
    let sdp = get(&answer, "sdp").as_string().unwrap_or_default();
    let desc = RtcSessionDescriptionInit::new(RtcSdpType::Answer);
    desc.set_sdp(&sdp);
    let _ = JsFuture::from(pc.set_local_description(&desc)).await;
    core::send_call_signal(
        &c.peer,
        Body::Call {
            op: "answer".into(),
            call_id: c.id.clone(),
            video: false,
            sdp,
            cand: None,
        },
    );
}

fn hangup(c: &Rc<Call>) {
    let op = if c.incoming && c.pc.borrow().is_none() {
        "reject"
    } else {
        "end"
    };
    if op == "reject" {
        core::call_taken(&c.id);
    }
    core::send_call_signal(
        &c.peer,
        Body::Call {
            op: op.into(),
            call_id: c.id.clone(),
            video: false,
            sdp: String::new(),
            cand: None,
        },
    );
    let c = c.clone();
    spawn(async move { end(&c, "Appel terminé", false).await });
}

async fn end(c: &Rc<Call>, reason: &str, missed: bool) {
    if c.ended.replace(true) {
        return;
    }
    clear_timer(c.no_answer.get());
    clear_interval(c.ticker.get());
    sounds::stop_ring();
    if c.pc.borrow().is_some() {
        sounds::hangup();
    }
    if let Some(s) = c.local.borrow().as_ref() {
        for t in tracks(s) {
            t.stop();
        }
    }
    if let Some(pc) = c.pc.borrow().as_ref() {
        pc.close();
    }
    set_status(c, reason);
    toggle_class(&c.root, "ended", true);
    let connected = c.connected_at.get();
    let dur = if connected > 0.0 {
        ((now() - connected) / 1000.0).round()
    } else {
        0.0
    };
    core::log_call(CallEntry {
        id: c.id.clone(),
        peer: c.peer.clone(),
        video: c.video,
        incoming: c.incoming,
        at: c.at,
        dur,
        missed: missed || (c.incoming && dur == 0.0),
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

fn notify(from: &str, video: bool) {
    let s = core::settings();
    if dom::visible()
        || !s.notify
        || web_sys::Notification::permission() != web_sys::NotificationPermission::Granted
    {
        return;
    }
    let opts = web_sys::NotificationOptions::new();
    opts.set_body(if video {
        "📹 Appel vidéo entrant"
    } else {
        "📞 Appel entrant"
    });
    opts.set_icon(&crate::ui::asset("icons/icon-192.png"));
    opts.set_tag("talka-call");
    let _ = web_sys::Notification::new_with_options(&core::display_name(from), &opts);
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
    let name = core::display_name(&c.peer);
    let avatar = core::avatar_of(&c.peer);
    scope_into(&c.zone, || {
        let face = if avatar.is_empty() {
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
        let (c1, c3, c4) = (c.clone(), c.clone(), c.clone());
        replace(
            &c.root,
            vec![
                c.remote.clone(),
                c.remote_audio.clone(),
                if c.video {
                    c.local_video.clone()
                } else {
                    h("span").el()
                },
                h("div.call-info")
                    .child(face)
                    .child(h("div.call-name").text(&name))
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
                    .child(call_btn("hangup", "Raccrocher", "red", move || hangup(&c4)))
                    .el(),
            ],
        );
        let _ = c
            .root
            .set_attribute("aria-label", &format!("Appel avec {name}"));
    });
}

async fn switch_camera(c: &Rc<Call>) {
    let (Some(pc), Some(local)) = (c.pc.borrow().clone(), c.local.borrow().clone()) else {
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
    for sender in pc.get_senders().iter() {
        let sender: web_sys::RtcRtpSender = sender.unchecked_into();
        if sender.track().is_some_and(|t| t.kind() == "video") {
            let _ = JsFuture::from(sender.replace_track(Some(&track))).await;
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

/// Une ligne de l'historique : « Appel audio reçu · 2:31 ».
pub fn describe(e: &CallEntry) -> String {
    let kind = if e.video { "vidéo" } else { "audio" };
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
