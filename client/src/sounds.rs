//! Les sons de Talka, synthétisés avec WebAudio : aucun fichier à charger.

use std::cell::{Cell, RefCell};

use web_sys::{AudioContext, OscillatorType};

use crate::dom::{clear_interval, interval, listen_forever, window};

thread_local! {
    static CTX: RefCell<Option<AudioContext>> = const { RefCell::new(None) };
    static RING: Cell<i32> = const { Cell::new(0) };
}

fn audio() -> Option<AudioContext> {
    CTX.with(|c| {
        let mut c = c.borrow_mut();
        if c.is_none() {
            *c = AudioContext::new().ok();
        }
        let ctx = c.clone()?;
        if ctx.state() == web_sys::AudioContextState::Suspended {
            let _ = ctx.resume();
        }
        Some(ctx)
    })
}

fn tone(freq: f32, start: f64, dur: f64, gain: f32, kind: OscillatorType) {
    let Some(a) = audio() else { return };
    let (Ok(o), Ok(g)) = (a.create_oscillator(), a.create_gain()) else {
        return;
    };
    o.set_type(kind);
    o.frequency().set_value(freq);
    let t = a.current_time() + start;
    let p = g.gain();
    let _ = p.set_value_at_time(0.0, t);
    let _ = p.linear_ramp_to_value_at_time(gain, t + 0.01);
    let _ = p.exponential_ramp_to_value_at_time(0.0001, t + dur);
    let _ = o
        .connect_with_audio_node(&g)
        .and_then(|g| g.connect_with_audio_node(&a.destination()));
    let _ = o.start_with_when(t);
    let _ = o.stop_with_when(t + dur + 0.02);
}

pub fn incoming() {
    tone(880.0, 0.0, 0.12, 0.12, OscillatorType::Sine);
    tone(1320.0, 0.1, 0.18, 0.12, OscillatorType::Sine);
}

pub fn sent() {
    tone(660.0, 0.0, 0.08, 0.05, OscillatorType::Sine);
}

/// La sonnerie (appel entrant) ou la tonalité (appel sortant), en boucle.
pub fn ring(outgoing: bool) {
    stop_ring();
    let once = move || {
        if outgoing {
            tone(425.0, 0.0, 1.2, 0.06, OscillatorType::Sine);
        } else {
            for i in 0..6 {
                tone(
                    if i % 2 == 1 { 784.0 } else { 988.0 },
                    i as f64 * 0.13,
                    0.12,
                    0.1,
                    OscillatorType::Triangle,
                );
            }
        }
    };
    once();
    RING.with(|r| r.set(interval(if outgoing { 3000 } else { 2200 }, once)));
}

pub fn stop_ring() {
    RING.with(|r| {
        if r.get() != 0 {
            clear_interval(r.replace(0));
        }
    });
}

pub fn hangup() {
    tone(480.0, 0.0, 0.2, 0.08, OscillatorType::Sine);
    tone(380.0, 0.22, 0.3, 0.08, OscillatorType::Sine);
}

/// Les navigateurs n'autorisent le son qu'après un geste : on le prépare au premier.
pub fn unlock() {
    for e in ["pointerdown", "keydown"] {
        listen_forever(&window(), e, |_| {
            CTX.with(|c| {
                if c.borrow().is_none() {
                    *c.borrow_mut() = AudioContext::new().ok();
                }
            });
        });
    }
}
