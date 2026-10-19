//! Photos, vocaux et fichiers : préparés dans le navigateur avant d'être
//! chiffrés. Les photos sont réduites (1600 px, JPEG) avec un `<canvas>`, les
//! vocaux enregistrés avec `MediaRecorder`.

use std::cell::RefCell;
use std::rc::Rc;

use js_sys::{Array, Uint8Array};
use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{
    Blob, BlobEvent, CanvasRenderingContext2d, File, FileReader, HtmlCanvasElement,
    HtmlImageElement, HtmlInputElement, MediaRecorder, MediaRecorderOptions, MediaStream,
    MediaStreamConstraints, Url,
};

use crate::dom::{Deferred, document, get, h, listen, now, scope_into, unique, window};

pub const MAX_FILE: f64 = 4.0 * 1024.0 * 1024.0;

/// Choisir un fichier (`None` : annulé).
pub async fn pick_file(accept: &str, capture: Option<&str>) -> Option<File> {
    let input: HtmlInputElement = h("input")
        .attr("type", "file")
        .attr("accept", accept)
        .el()
        .unchecked_into();
    if let Some(c) = capture {
        let _ = input.set_attribute("capture", c);
    }
    let d = Deferred::<File>::new();
    let zone = unique("pick");
    scope_into(&zone, || {
        let (s1, s2) = (d.sender(), d.sender());
        let i = input.clone();
        listen(&input, "change", move |_| {
            s1.send(i.files().and_then(|f| f.get(0)))
        });
        listen(&input, "cancel", move |_| s2.send(None));
    });
    input.click();
    let out = d.wait().await;
    crate::dom::drop_scope(&zone);
    out
}

/// Le contenu d'un fichier, en adresse `data:`.
pub async fn data_url(blob: &Blob) -> Option<String> {
    let reader = FileReader::new().ok()?;
    let d = Deferred::<String>::new();
    let s = d.sender();
    let r = reader.clone();
    let done = Closure::once_into_js(move || s.send(r.result().ok().and_then(|v| v.as_string())));
    reader.set_onloadend(Some(done.unchecked_ref()));
    reader.read_as_data_url(blob).ok()?;
    d.wait().await
}

pub async fn text_of(file: &File) -> Option<String> {
    JsFuture::from(file.text()).await.ok()?.as_string()
}

async fn load_image(src: &str) -> Option<HtmlImageElement> {
    let img = HtmlImageElement::new().ok()?;
    let d = Deferred::<bool>::new();
    let (s1, s2) = (d.sender(), d.sender());
    let ok = Closure::once_into_js(move || s1.send(Some(true)));
    let err = Closure::once_into_js(move || s2.send(Some(false)));
    img.set_onload(Some(ok.unchecked_ref()));
    img.set_onerror(Some(err.unchecked_ref()));
    img.set_src(src);
    d.wait().await.filter(|ok| *ok).map(|_| img)
}

fn canvas(w: u32, hgt: u32) -> Option<(HtmlCanvasElement, CanvasRenderingContext2d)> {
    let c: HtmlCanvasElement = document().create_element("canvas").ok()?.unchecked_into();
    c.set_width(w);
    c.set_height(hgt);
    let ctx = c.get_context("2d").ok()??.unchecked_into();
    Some((c, ctx))
}

/// Une photo réduite : (adresse `data:`, largeur, hauteur).
pub async fn compress_image(file: &File, max: f64, quality: f64) -> Option<(String, u32, u32)> {
    let url = Url::create_object_url_with_blob(file).ok()?;
    let img = load_image(&url).await;
    let _ = Url::revoke_object_url(&url);
    let img = img?;
    let (nw, nh) = (img.natural_width() as f64, img.natural_height() as f64);
    let scale = (max / nw.max(nh)).min(1.0);
    let (w, hgt) = (
        (nw * scale).round().max(1.0) as u32,
        (nh * scale).round().max(1.0) as u32,
    );
    let (c, ctx) = canvas(w, hgt)?;
    ctx.set_fill_style_str("#fff");
    ctx.fill_rect(0.0, 0.0, w as f64, hgt as f64);
    ctx.draw_image_with_html_image_element_and_dw_and_dh(&img, 0.0, 0.0, w as f64, hgt as f64)
        .ok()?;
    let data = c
        .to_data_url_with_type_and_encoder_options("image/jpeg", &JsValue::from_f64(quality))
        .ok()?;
    Some((data, w, hgt))
}

/// Une petite photo carrée (profil, groupe).
pub async fn avatar_from(file: &File) -> Option<String> {
    let size = 160.0;
    let url = Url::create_object_url_with_blob(file).ok()?;
    let img = load_image(&url).await;
    let _ = Url::revoke_object_url(&url);
    let img = img?;
    let (nw, nh) = (img.natural_width() as f64, img.natural_height() as f64);
    let s = nw.min(nh);
    let (c, ctx) = canvas(size as u32, size as u32)?;
    ctx.draw_image_with_html_image_element_and_sw_and_sh_and_dx_and_dy_and_dw_and_dh(
        &img,
        (nw - s) / 2.0,
        (nh - s) / 2.0,
        s,
        s,
        0.0,
        0.0,
        size,
        size,
    )
    .ok()?;
    c.to_data_url_with_type_and_encoder_options("image/jpeg", &JsValue::from_f64(0.8))
        .ok()
}

/// Les octets d'une adresse `data:`.
pub fn data_bytes(data: &str) -> Vec<u8> {
    let b64 = data.split_once(',').map_or("", |(_, b)| b);
    crate::crypto::unb64(b64).unwrap_or_default()
}

/// Un texte à télécharger, en adresse `data:`.
pub fn text_data_url(text: &str, mime: &str) -> String {
    format!("data:{mime};base64,{}", crate::crypto::b64(text.as_bytes()))
}

// ----- Les vocaux --------------------------------------------------------------

pub struct Recorder {
    rec: MediaRecorder,
    stream: MediaStream,
    chunks: Rc<RefCell<Vec<Blob>>>,
    started: f64,
    zone: String,
}

pub fn can_record() -> bool {
    crate::dom::has(&window(), "MediaRecorder")
        && crate::dom::has(&window().navigator(), "mediaDevices")
}

impl Recorder {
    pub async fn start() -> Option<Recorder> {
        let devices = window().navigator().media_devices().ok()?;
        let c = MediaStreamConstraints::new();
        c.set_audio(&JsValue::TRUE);
        let stream: MediaStream = JsFuture::from(devices.get_user_media_with_constraints(&c).ok()?)
            .await
            .ok()?
            .unchecked_into();
        let mime = [
            "audio/webm;codecs=opus",
            "audio/mp4",
            "audio/ogg;codecs=opus",
            "audio/webm",
        ]
        .into_iter()
        .find(|t| MediaRecorder::is_type_supported(t));
        let opts = MediaRecorderOptions::new();
        if let Some(m) = mime {
            opts.set_mime_type(m);
            opts.set_audio_bits_per_second(32_000);
        }
        let rec =
            MediaRecorder::new_with_media_stream_and_media_recorder_options(&stream, &opts).ok()?;
        let chunks = Rc::new(RefCell::new(Vec::new()));
        let zone = unique("rec");
        let ch = chunks.clone();
        scope_into(&zone, || {
            listen(&rec, "dataavailable", move |e| {
                if let Some(b) = e.dyn_ref::<BlobEvent>().and_then(|b| b.data())
                    && b.size() > 0.0
                {
                    ch.borrow_mut().push(b);
                }
            });
        });
        rec.start_with_time_slice(250).ok()?;
        Some(Recorder {
            rec,
            stream,
            chunks,
            started: now(),
            zone,
        })
    }

    pub fn elapsed(&self) -> f64 {
        (now() - self.started) / 1000.0
    }

    /// Arrête : (adresse `data:`, durée, type), ou rien si annulé ou trop court.
    pub async fn stop(self, cancel: bool) -> Option<(String, f64, String)> {
        let dur = self.elapsed();
        let d = Deferred::<bool>::new();
        let s = d.sender();
        scope_into(&self.zone, || {
            listen(&self.rec, "stop", move |_| s.send(Some(true)))
        });
        let _ = self.rec.stop();
        d.wait().await;
        for t in self.stream.get_tracks().iter() {
            t.unchecked_into::<web_sys::MediaStreamTrack>().stop();
        }
        crate::dom::drop_scope(&self.zone);
        if cancel || dur < 0.6 {
            return None;
        }
        let parts = Array::new();
        for b in self.chunks.borrow().iter() {
            parts.push(b);
        }
        let mime = self.rec.mime_type();
        let opts = web_sys::BlobPropertyBag::new();
        opts.set_type(if mime.is_empty() { "audio/webm" } else { &mime });
        let blob = Blob::new_with_blob_sequence_and_options(&parts, &opts).ok()?;
        Some((data_url(&blob).await?, dur, blob.type_()))
    }
}

thread_local! {
    static WAVES: RefCell<std::collections::HashMap<u64, Vec<f32>>> = RefCell::new(std::collections::HashMap::new());
}

fn hash(s: &str) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut h = std::collections::hash_map::DefaultHasher::new();
    s.hash(&mut h);
    h.finish()
}

/// La forme d'onde (barres de 0 à 1) d'un vocal, calculée une fois.
pub async fn waveform(data: &str, bars: usize) -> Vec<f32> {
    let key = hash(data);
    if let Some(w) = WAVES.with(|w| w.borrow().get(&key).cloned()) {
        return w;
    }
    let fallback = || {
        (0..bars)
            .map(|i| 0.3 + 0.5 * ((i as f32) * 1.7).sin().abs())
            .collect::<Vec<f32>>()
    };
    let Ok(ctx) = web_sys::AudioContext::new() else {
        return fallback();
    };
    let bytes = data_bytes(data);
    let array = Uint8Array::from(bytes.as_slice());
    let decoded = match ctx.decode_audio_data(&array.buffer()) {
        Ok(p) => JsFuture::from(p).await.ok(),
        Err(_) => None,
    };
    let _ = ctx.close();
    let Some(buffer) = decoded else {
        return fallback();
    };
    let buffer: web_sys::AudioBuffer = buffer.unchecked_into();
    let Ok(ch) = buffer.get_channel_data(0) else {
        return fallback();
    };
    let step = (ch.len() / bars).max(1);
    let mut out: Vec<f32> = (0..bars)
        .map(|i| {
            let slice = ch.iter().skip(i * step).take(step).step_by(16);
            let (sum, n) = slice.fold((0.0f32, 0u32), |(s, n), v| (s + v.abs(), n + 1));
            if n == 0 { 0.0 } else { sum / n as f32 }
        })
        .collect();
    let max = out.iter().cloned().fold(0.001f32, f32::max);
    for v in &mut out {
        *v = (*v / max).max(0.12);
    }
    WAVES.with(|w| w.borrow_mut().insert(key, out.clone()));
    out
}

/// `BarcodeDetector` existe-t-il (scanner les QR codes) ?
pub fn can_scan() -> bool {
    !get(&window(), "BarcodeDetector").is_undefined()
}
