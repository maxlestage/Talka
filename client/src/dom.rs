//! Construire l'interface depuis Rust, sans HTML : `h("div.card").text("…")`.
//!
//! Les écouteurs d'événements sont rangés par **zone** (`scope("messages",
//! || …)`) : quand une zone est redessinée, les écouteurs de son ancien rendu
//! sont libérés avec lui. Rien ne s'accumule, même après des milliers de
//! messages.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::future::Future;
use std::rc::Rc;

use js_sys::{Array, Function, Promise, Reflect};
use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{Document, Element, Event, HtmlElement, HtmlInputElement, KeyboardEvent, Window};

/// Un écouteur : libéré, il se débranche de sa cible (sinon le navigateur
/// appellerait une fonction qui n'existe plus).
struct Listener {
    target: web_sys::EventTarget,
    event: String,
    closure: Closure<dyn FnMut(Event)>,
}

impl Drop for Listener {
    fn drop(&mut self) {
        let _ = self.target.remove_event_listener_with_callback(
            &self.event,
            self.closure.as_ref().unchecked_ref(),
        );
    }
}

thread_local! {
    static SCOPES: RefCell<HashMap<String, Vec<Listener>>> = RefCell::new(HashMap::new());
    static STACK: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
    static NEXT: Cell<u64> = const { Cell::new(0) };
}

pub fn window() -> Window {
    web_sys::window().expect("fenêtre")
}

pub fn document() -> Document {
    window().document().expect("document")
}

/// Un nom de zone unique (fenêtres, menus).
pub fn unique(prefix: &str) -> String {
    NEXT.with(|n| {
        n.set(n.get() + 1);
        format!("{prefix}-{}", n.get())
    })
}

/// Exécute `f` dans la zone `name` : les écouteurs créés pendant `f`
/// remplacent ceux du rendu précédent de cette zone.
pub fn scope<T>(name: &str, f: impl FnOnce() -> T) -> T {
    let old = SCOPES.with(|s| s.borrow_mut().remove(name));
    STACK.with(|s| s.borrow_mut().push(name.to_owned()));
    let out = f();
    STACK.with(|s| s.borrow_mut().pop());
    drop(old);
    out
}

/// Libère les écouteurs d'une zone (une fenêtre fermée).
pub fn drop_scope(name: &str) {
    let old = SCOPES.with(|s| s.borrow_mut().remove(name));
    drop(old);
}

fn keep(listener: Listener) {
    let name = STACK
        .with(|s| s.borrow().last().cloned())
        .unwrap_or_else(|| "global".to_owned());
    SCOPES.with(|s| s.borrow_mut().entry(name).or_default().push(listener));
}

/// Écoute un événement sur n'importe quelle cible, dans la zone courante.
pub fn listen(target: &web_sys::EventTarget, event: &str, f: impl FnMut(Event) + 'static) {
    let closure = Closure::<dyn FnMut(Event)>::new(f);
    let _ = target.add_event_listener_with_callback(event, closure.as_ref().unchecked_ref());
    keep(Listener {
        target: target.clone(),
        event: event.to_owned(),
        closure,
    });
}

/// Un écouteur permanent (fenêtre, document) : jamais libéré.
pub fn listen_forever(target: &web_sys::EventTarget, event: &str, f: impl FnMut(Event) + 'static) {
    let closure = Closure::<dyn FnMut(Event)>::new(f);
    let _ = target.add_event_listener_with_callback(event, closure.as_ref().unchecked_ref());
    closure.forget();
}

/// Un élément en construction.
pub struct El(pub Element);

/// Ce qu'on peut mettre dans un élément.
pub enum Child {
    El(Element),
    Text(String),
    Many(Vec<Child>),
    None,
}

impl From<El> for Child {
    fn from(e: El) -> Self {
        Child::El(e.0)
    }
}
impl From<Element> for Child {
    fn from(e: Element) -> Self {
        Child::El(e)
    }
}
impl From<&Element> for Child {
    fn from(e: &Element) -> Self {
        Child::El(e.clone())
    }
}
impl From<&str> for Child {
    fn from(t: &str) -> Self {
        Child::Text(t.to_owned())
    }
}
impl From<String> for Child {
    fn from(t: String) -> Self {
        Child::Text(t)
    }
}
impl From<&String> for Child {
    fn from(t: &String) -> Self {
        Child::Text(t.clone())
    }
}
impl<T: Into<Child>> From<Option<T>> for Child {
    fn from(o: Option<T>) -> Self {
        o.map_or(Child::None, Into::into)
    }
}
impl<T: Into<Child>> From<Vec<T>> for Child {
    fn from(v: Vec<T>) -> Self {
        Child::Many(v.into_iter().map(Into::into).collect())
    }
}

/// `h("button.primary#ok")` : la balise, puis ses classes et son identifiant.
pub fn h(spec: &str) -> El {
    let end = spec.find(['.', '#']).unwrap_or(spec.len());
    let tag = if end == 0 { "div" } else { &spec[..end] };
    let el = document().create_element(tag).expect("élément");
    let mut rest = &spec[end..];
    while !rest.is_empty() {
        let kind = &rest[..1];
        let next = rest[1..].find(['.', '#']).map_or(rest.len(), |i| i + 1);
        let name = &rest[1..next];
        if kind == "." {
            let _ = el.class_list().add_1(name);
        } else {
            el.set_id(name);
        }
        rest = &rest[next..];
    }
    El(el)
}

impl El {
    pub fn class(self, c: &str) -> Self {
        for name in c.split_whitespace() {
            let _ = self.0.class_list().add_1(name);
        }
        self
    }

    pub fn class_if(self, c: &str, on: bool) -> Self {
        if on { self.class(c) } else { self }
    }

    pub fn attr(self, k: &str, v: impl AsRef<str>) -> Self {
        let _ = self.0.set_attribute(k, v.as_ref());
        self
    }

    /// Une propriété JavaScript (`value`, `checked`, `muted`…).
    pub fn prop(self, k: &str, v: impl Into<JsValue>) -> Self {
        let _ = Reflect::set(&self.0, &JsValue::from_str(k), &v.into());
        self
    }

    pub fn style(self, prop: &str, value: impl AsRef<str>) -> Self {
        if let Some(e) = self.0.dyn_ref::<HtmlElement>() {
            let _ = e.style().set_property(prop, value.as_ref());
        }
        self
    }

    pub fn text(self, t: impl AsRef<str>) -> Self {
        let _ = self.0.append_with_str_1(t.as_ref());
        self
    }

    pub fn child(self, c: impl Into<Child>) -> Self {
        append(&self.0, c.into());
        self
    }

    pub fn children<C: Into<Child>>(self, items: impl IntoIterator<Item = C>) -> Self {
        for c in items {
            append(&self.0, c.into());
        }
        self
    }

    pub fn on(self, event: &str, f: impl FnMut(Event) + 'static) -> Self {
        listen(&self.0, event, f);
        self
    }

    /// Un clic, qui ne remonte pas.
    pub fn click(self, mut f: impl FnMut() + 'static) -> Self {
        self.on("click", move |e| {
            e.stop_propagation();
            f();
        })
    }

    pub fn el(self) -> Element {
        self.0
    }
}

pub fn append(parent: &Element, c: Child) {
    match c {
        Child::El(e) => {
            let _ = parent.append_child(&e);
        }
        Child::Text(t) => {
            let _ = parent.append_with_str_1(&t);
        }
        Child::Many(list) => {
            for c in list {
                append(parent, c);
            }
        }
        Child::None => {}
    }
}

/// Remplace les enfants d'un élément.
pub fn replace(parent: &Element, children: impl Into<Child>) {
    parent.set_text_content(None);
    append(parent, children.into());
}

pub fn by_id(id: &str) -> Option<Element> {
    document().get_element_by_id(id)
}

pub fn query(selector: &str) -> Option<Element> {
    document().query_selector(selector).ok().flatten()
}

pub fn query_in(el: &Element, selector: &str) -> Option<Element> {
    el.query_selector(selector).ok().flatten()
}

pub fn query_all_in(el: &Element, selector: &str) -> Vec<Element> {
    let Ok(list) = el.query_selector_all(selector) else {
        return Vec::new();
    };
    (0..list.length())
        .filter_map(|i| list.item(i)?.dyn_into::<Element>().ok())
        .collect()
}

pub fn value(el: &Element) -> String {
    if let Some(i) = el.dyn_ref::<HtmlInputElement>() {
        i.value()
    } else if let Some(t) = el.dyn_ref::<web_sys::HtmlTextAreaElement>() {
        t.value()
    } else {
        get(el, "value").as_string().unwrap_or_default()
    }
}

pub fn set_value(el: &Element, v: &str) {
    if let Some(i) = el.dyn_ref::<HtmlInputElement>() {
        i.set_value(v);
    } else if let Some(t) = el.dyn_ref::<web_sys::HtmlTextAreaElement>() {
        t.set_value(v);
    }
}

pub fn checked(el: &Element) -> bool {
    el.dyn_ref::<HtmlInputElement>()
        .is_some_and(|i| i.checked())
}

pub fn focus(el: &Element) {
    if let Some(e) = el.dyn_ref::<HtmlElement>() {
        let _ = e.focus();
    }
}

pub fn toggle_class(el: &Element, class: &str, on: bool) {
    let _ = el.class_list().toggle_with_force(class, on);
}

pub fn set_style(el: &Element, prop: &str, value: &str) {
    if let Some(e) = el.dyn_ref::<HtmlElement>() {
        let _ = e.style().set_property(prop, value);
    }
}

/// Le contenu d'une balise `<meta name="…">`.
pub fn meta(name: &str) -> String {
    query(&format!("meta[name=\"{name}\"]"))
        .and_then(|m| m.get_attribute("content"))
        .unwrap_or_default()
}

pub fn spawn(f: impl Future<Output = ()> + 'static) {
    wasm_bindgen_futures::spawn_local(f);
}

pub async fn sleep(ms: i32) {
    let promise = Promise::new(&mut |resolve, _| {
        let _ = window().set_timeout_with_callback_and_timeout_and_arguments_0(&resolve, ms);
    });
    let _ = JsFuture::from(promise).await;
}

/// Un minuteur qu'on peut annuler (`clear_timer`).
pub fn timer(ms: i32, f: impl FnOnce() + 'static) -> i32 {
    let closure = Closure::once_into_js(f);
    window()
        .set_timeout_with_callback_and_timeout_and_arguments_0(closure.unchecked_ref(), ms)
        .unwrap_or(0)
}

pub fn clear_timer(id: i32) {
    window().clear_timeout_with_handle(id);
}

/// Un minuteur répété, pour toujours (ou jusqu'à `clear_interval`).
pub fn interval(ms: i32, f: impl FnMut() + 'static) -> i32 {
    let closure = Closure::<dyn FnMut()>::new(f);
    let id = window()
        .set_interval_with_callback_and_timeout_and_arguments_0(
            closure.as_ref().unchecked_ref(),
            ms,
        )
        .unwrap_or(0);
    closure.forget();
    id
}

pub fn clear_interval(id: i32) {
    window().clear_interval_with_handle(id);
}

pub fn now() -> f64 {
    js_sys::Date::now()
}

pub fn get(obj: &JsValue, key: &str) -> JsValue {
    Reflect::get(obj, &JsValue::from_str(key)).unwrap_or(JsValue::UNDEFINED)
}

pub fn set(obj: &JsValue, key: &str, v: impl Into<JsValue>) {
    let _ = Reflect::set(obj, &JsValue::from_str(key), &v.into());
}

/// Appelle une méthode par son nom (`navigator.share`, `setAppBadge`…).
pub fn call(obj: &JsValue, method: &str, args: &[JsValue]) -> Result<JsValue, JsValue> {
    let f: Function = get(obj, method).dyn_into()?;
    f.apply(obj, &args.iter().cloned().collect::<Array>())
}

pub async fn await_promise(v: Result<JsValue, JsValue>) -> Result<JsValue, JsValue> {
    let p: Promise = v?.dyn_into()?;
    JsFuture::from(p).await
}

pub fn has(obj: &JsValue, key: &str) -> bool {
    !get(obj, key).is_undefined()
}

pub fn matches_media(query: &str) -> bool {
    window()
        .match_media(query)
        .ok()
        .flatten()
        .is_some_and(|m| m.matches())
}

pub fn visible() -> bool {
    document().visibility_state() == web_sys::VisibilityState::Visible
}

/// Une valeur à attendre, fournie plus tard (fenêtres, choix).
pub struct Deferred<T> {
    slot: Rc<RefCell<Option<T>>>,
    resolve: Rc<RefCell<Option<Function>>>,
    promise: Promise,
}

impl<T: 'static> Deferred<T> {
    pub fn new() -> Deferred<T> {
        let resolve = Rc::new(RefCell::new(None));
        let r = resolve.clone();
        let promise = Promise::new(&mut move |res, _| {
            *r.borrow_mut() = Some(res);
        });
        Deferred {
            slot: Rc::new(RefCell::new(None)),
            resolve,
            promise,
        }
    }

    /// De quoi tenir la promesse, à garder dans les écouteurs.
    pub fn sender(&self) -> Sender<T> {
        Sender {
            slot: self.slot.clone(),
            resolve: self.resolve.clone(),
        }
    }

    pub async fn wait(self) -> Option<T> {
        let _ = JsFuture::from(self.promise).await;
        self.slot.borrow_mut().take()
    }
}

pub struct Sender<T> {
    slot: Rc<RefCell<Option<T>>>,
    resolve: Rc<RefCell<Option<Function>>>,
}

impl<T> Clone for Sender<T> {
    fn clone(&self) -> Self {
        Sender {
            slot: self.slot.clone(),
            resolve: self.resolve.clone(),
        }
    }
}

impl<T> Sender<T> {
    /// Tient la promesse (une seule fois ; `None` : sans valeur).
    pub fn send(&self, v: Option<T>) {
        let Some(resolve) = self.resolve.borrow_mut().take() else {
            return;
        };
        *self.slot.borrow_mut() = v;
        let _ = resolve.call0(&JsValue::NULL);
    }
}

// ----- Icônes -----------------------------------------------------------------

fn path(name: &str) -> &'static str {
    match name {
        "chat" => "M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2z",
        "status" => "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 4a6 6 0 1 1 0 12 6 6 0 0 1 0-12z",
        "phone" => {
            "M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z"
        }
        "video" => {
            "M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11z"
        }
        "plus" => "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z",
        "more" => {
            "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z"
        }
        "back" => "M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z",
        "send" => "M2 21l21-9L2 3v7l15 2-15 2z",
        "mic" => {
            "M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5.3-3a5.3 5.3 0 0 1-10.6 0H5a7 7 0 0 0 6 6.9V21h2v-3.1A7 7 0 0 0 19 11z"
        }
        "attach" => {
            "M16.5 6v11.5a4 4 0 0 1-8 0V5a2.5 2.5 0 0 1 5 0v10.5a1 1 0 0 1-2 0V6H10v9.5a2.5 2.5 0 0 0 5 0V5a4 4 0 0 0-8 0v12.5a5.5 5.5 0 0 0 11 0V6z"
        }
        "smile" => {
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-3.5 6a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM12 17.5a5.5 5.5 0 0 1-5-3.5h10a5.5 5.5 0 0 1-5 3.5z"
        }
        "search" => {
            "M15.5 14h-.8l-.3-.3A6.5 6.5 0 1 0 14 15.5l.3.3v.8l5 5 1.5-1.5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z"
        }
        "close" => {
            "M19 6.4L17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"
        }
        "check" => "M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z",
        "lock" => {
            "M18 8h-1V6a5 5 0 0 0-10 0v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zM9 6a3 3 0 0 1 6 0v2H9z"
        }
        "settings" => {
            "M19.4 13a7.5 7.5 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7 7 0 0 0-1.7-1L15 3h-4l-.4 2.9a7 7 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.5 7.5 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1c.5.4 1.1.7 1.7 1L11 21h4l.4-2.9c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.5zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z"
        }
        "group" => {
            "M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm-8 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm0 2c-2.3 0-7 1.2-7 3.5V19h14v-2.5C15 14.2 10.3 13 8 13zm8 0c-.3 0-.6 0-1 .1a4.2 4.2 0 0 1 2 3.4V19h6v-2.5c0-2.3-4.7-3.5-7-3.5z"
        }
        "person" => {
            "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.7 0-8 1.3-8 4v2h16v-2c0-2.7-5.3-4-8-4z"
        }
        "camera" => {
            "M12 9a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm8-3h-3.2L15 4H9L7.2 6H4a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2z"
        }
        "image" => {
            "M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3 3.5-4.5 4.5 6H5z"
        }
        "file" => "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zm-1 7V3.5L18.5 9z",
        "reply" => "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z",
        "forward" => "M14 9V5l7 7-7 7v-4.1c-5 0-8.5 1.6-11 5.1 1-5 4-10 11-11z",
        "copy" => {
            "M16 1H4a2 2 0 0 0-2 2v14h2V3h12zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11z"
        }
        "star" => "M12 17.3l6.2 3.7-1.6-7L22 9.2l-7.2-.6L12 2 9.2 8.6 2 9.2 7.4 14l-1.6 7z",
        "hourglass" => {
            "M6 2v6h.01L6 8.01 10 12l-4 4 .01.01H6V22h12v-5.99h-.01L18 16l-4-4 4-3.99-.01-.01H18V2H6zm10 14.5V20H8v-3.5l4-4 4 4zm-4-5l-4-4V4h8v3.5l-4 4z"
        }
        "trash" => "M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6zM19 4h-3.5l-1-1h-5l-1 1H5v2h14z",
        "edit" => {
            "M3 17.2V21h3.8L17.8 10l-3.8-3.8zM20.7 7a1 1 0 0 0 0-1.4l-2.3-2.3a1 1 0 0 0-1.4 0l-1.8 1.8 3.8 3.8z"
        }
        "play" => "M8 5v14l11-7z",
        "pause" => "M6 19h4V5H6zm8-14v14h4V5z",
        "hangup" => {
            "M12 9c-1.6 0-3.1.3-4.6.7v3.1a1 1 0 0 1-.6.9 11.5 11.5 0 0 0-2.7 1.9 1 1 0 0 1-1.4 0L.3 13.2a1 1 0 0 1 0-1.4A17 17 0 0 1 12 7a17 17 0 0 1 11.7 4.8 1 1 0 0 1 0 1.4l-2.5 2.5a1 1 0 0 1-1.4 0 11.3 11.3 0 0 0-2.7-1.9 1 1 0 0 1-.6-.9V9.7A15 15 0 0 0 12 9z"
        }
        "micoff" => {
            "M19 11h-1.7a5 5 0 0 1-.5 2.2l1.2 1.2A7 7 0 0 0 19 11zm-4 .2V5a3 3 0 0 0-6 0v.2zM4.3 3L3 4.3l6 6V11a3 3 0 0 0 4.2 2.8l1.6 1.6A5.3 5.3 0 0 1 6.7 11H5a7 7 0 0 0 6 6.9V21h2v-3.1a7 7 0 0 0 3.3-1.3l3.4 3.4 1.3-1.3z"
        }
        "videooff" => {
            "M21 6.5l-4 4V7a1 1 0 0 0-1-1H9.8L21 17.2zM3.3 2L2 3.3 4.7 6H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 .6-.2l3.1 3.2 1.3-1.3z"
        }
        "switchcam" => {
            "M20 5h-3.2L15 3H9L7.2 5H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm-5 11.5V14H9v2.5L5.5 13 9 9.5V12h6V9.5l3.5 3.5z"
        }
        "qr" => {
            "M3 11h8V3H3zm2-6h4v4H5zm8-2v8h8V3zm6 6h-4V5h4zM3 21h8v-8H3zm2-6h4v4H5zm13 0h-2v-2h-3v3h2v2h-2v3h2v-2h2v2h3v-3h-2zm2-2h1v2h-1zm-7 8h2v-2h-2z"
        }
        "archive" => {
            "M20.5 5.2l-1.4-1.7A1.5 1.5 0 0 0 18 3H6a1.5 1.5 0 0 0-1.2.5L3.5 5.2A2 2 0 0 0 3 6.5V19a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6.5a2 2 0 0 0-.5-1.3zM12 17.5L6.5 12H10v-2h4v2h3.5zM5.1 5l.8-1h12l1 1z"
        }
        "pin" => "M16 9V4h1V2H7v2h1v5l-2 2v2h5.2v7h1.6v-7H18v-2z",
        "bell" => {
            "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2zm6-6v-5c0-3.1-1.6-5.6-4.5-6.3V4a1.5 1.5 0 0 0-3 0v.7C7.6 5.4 6 7.9 6 11v5l-2 2v1h16v-1z"
        }
        "belloff" => {
            "M20 18.7L5.3 4 4 5.3l2.9 2.9A6.7 6.7 0 0 0 6 11v5l-2 2v1h13.7l1 1zM12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2zm6-7.7V11c0-3.1-1.6-5.6-4.5-6.3V4a1.5 1.5 0 0 0-3 0v.7c-.5.1-1 .3-1.4.5z"
        }
        "timer" => {
            "M15 1H9v2h6zm-4 13h2V8h-2zm8-6.6l1.4-1.4c-.4-.5-.9-1-1.4-1.4L17.6 6A9 9 0 1 0 21 13a9 9 0 0 0-2-5.6zM12 20a7 7 0 1 1 0-14 7 7 0 0 1 0 14z"
        }
        "key" => "M12.6 10a6 6 0 1 0 0 4H17v4h4v-4h2v-4zM7 14a2 2 0 1 1 0-4 2 2 0 0 1 0 4z",
        "download" => "M19 9h-4V3H9v6H5l7 7zM5 18v2h14v-2z",
        "upload" => "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z",
        "share" => {
            "M18 16a3 3 0 0 0-2 .8L8.9 12.7a3 3 0 0 0 0-1.4L16 7.2A3 3 0 1 0 15 5v.3L8 9.4a3 3 0 1 0 0 5.2l7 4.1v.3a3 3 0 1 0 3-3z"
        }
        "block" => {
            "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM4 12a8 8 0 0 1 12.9-6.3L5.7 16.9A8 8 0 0 1 4 12zm8 8a8 8 0 0 1-4.9-1.7L18.3 7.1A8 8 0 0 1 12 20z"
        }
        "logout" => {
            "M10 17l1.4-1.4L8.8 13H20v-2H8.8l2.6-2.6L10 7l-5 5zM4 5h8V3H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h8v-2H4z"
        }
        "palette" => {
            "M12 3a9 9 0 0 0 0 18 1.5 1.5 0 0 0 1.1-2.5 1.5 1.5 0 0 1 1.1-2.5H16a5 5 0 0 0 5-5c0-4.4-4-8-9-8zM6.5 12a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z"
        }
        "text" => "M5 4v3h5.5v12h3V7H19V4z",
        "arrowdown" => "M7 10l5 5 5-5z",
        "incoming" => "M20 5.4L18.6 4 7 15.6V9H5v10h10v-2H8.4z",
        "outgoing" => "M9 5v2h6.6L4 18.6 5.4 20 17 8.4V15h2V5z",
        "shield" => "M12 1L3 5v6c0 5.6 3.8 10.7 9 12 5.2-1.3 9-6.4 9-12V5z",
        "server" => {
            "M4 3h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zm0 10h16a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1zM7 6a1 1 0 1 0 0 2 1 1 0 0 0 0-2zm0 10a1 1 0 1 0 0 2 1 1 0 0 0 0-2z"
        }
        _ => "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2zm0-8h-2V7h2z",
    }
}

/// Une icône, dessinée en SVG (24×24).
pub fn icon(name: &str) -> Element {
    icon_class(name, "")
}

pub fn icon_class(name: &str, class: &str) -> Element {
    let ns = Some("http://www.w3.org/2000/svg");
    let doc = document();
    let svg = doc.create_element_ns(ns, "svg").expect("svg");
    let _ = svg.set_attribute("viewBox", "0 0 24 24");
    let _ = svg.set_attribute("aria-hidden", "true");
    let _ = svg.set_attribute("class", format!("icon {class}").trim());
    let p = doc.create_element_ns(ns, "path").expect("path");
    let _ = p.set_attribute("d", path(name));
    let _ = p.set_attribute("fill", "currentColor");
    let _ = svg.append_child(&p);
    svg
}

pub fn icon_button(name: &str, label: &str, f: impl FnMut() + 'static) -> El {
    h("button.icon-btn")
        .attr("type", "button")
        .attr("title", label)
        .attr("aria-label", label)
        .child(icon(name))
        .click(f)
}

// ----- Dates ------------------------------------------------------------------

fn date(ts: f64) -> js_sys::Date {
    js_sys::Date::new(&JsValue::from_f64(ts))
}

const DAYS: [&str; 7] = [
    "dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi",
];
const MONTHS: [&str; 12] = [
    "janvier",
    "février",
    "mars",
    "avril",
    "mai",
    "juin",
    "juillet",
    "août",
    "septembre",
    "octobre",
    "novembre",
    "décembre",
];

/// `14:05`.
pub fn time(ts: f64) -> String {
    let d = date(ts);
    format!("{:02}:{:02}", d.get_hours(), d.get_minutes())
}

fn start_of_day(ts: f64) -> f64 {
    let d = date(ts);
    d.set_hours(0);
    d.set_minutes(0);
    d.set_seconds(0);
    d.set_milliseconds(0);
    d.get_time()
}

fn days_ago(ts: f64) -> i64 {
    ((start_of_day(now()) - start_of_day(ts)) / 86_400_000.0).round() as i64
}

pub fn cap(s: &str) -> String {
    let mut c = s.chars();
    c.next().map_or_else(String::new, |f| {
        f.to_uppercase().collect::<String>() + c.as_str()
    })
}

fn short_date(ts: f64) -> String {
    let d = date(ts);
    format!(
        "{:02}/{:02}/{:02}",
        d.get_date(),
        d.get_month() + 1,
        d.get_full_year() % 100
    )
}

/// « Aujourd'hui », « Hier », « Lundi », « 3 mars 2026 ».
pub fn day_label(ts: f64) -> String {
    let d = date(ts);
    match days_ago(ts) {
        0 => "Aujourd'hui".into(),
        1 => "Hier".into(),
        n if n < 7 => cap(DAYS[d.get_day() as usize]),
        _ => format!(
            "{} {} {}",
            d.get_date(),
            MONTHS[d.get_month() as usize],
            d.get_full_year()
        ),
    }
}

/// Un jour, passé ou à venir : « aujourd'hui », « demain », « hier »,
/// « lundi » (dans la semaine), « le 3 mars 2026 ».
pub fn when_day(ts: f64) -> String {
    let d = date(ts);
    match days_ago(ts) {
        0 => "aujourd'hui".into(),
        1 => "hier".into(),
        -1 => "demain".into(),
        n if (-6..7).contains(&n) => DAYS[d.get_day() as usize].into(),
        _ => format!(
            "le {} {} {}",
            d.get_date(),
            MONTHS[d.get_month() as usize],
            d.get_full_year()
        ),
    }
}

/// L'heure d'un élément de liste : `14:05`, « Hier », « Lundi », `03/03/26`.
pub fn list_time(ts: f64) -> String {
    match days_ago(ts) {
        0 => time(ts),
        1 => "Hier".into(),
        n if n < 7 => cap(DAYS[date(ts).get_day() as usize]),
        _ => short_date(ts),
    }
}

pub fn last_seen(ts: f64) -> String {
    match days_ago(ts) {
        0 => format!("vu(e) aujourd'hui à {}", time(ts)),
        1 => format!("vu(e) hier à {}", time(ts)),
        _ => format!("vu(e) le {}", short_date(ts)),
    }
}

pub fn same_day(a: f64, b: f64) -> bool {
    start_of_day(a) == start_of_day(b)
}

pub fn full_date(ts: f64) -> String {
    format!("{} à {}", day_label(ts), time(ts))
}

pub fn fmt_dur(s: f64) -> String {
    let s = s.round().max(0.0) as u64;
    format!("{}:{:02}", s / 60, s % 60)
}

pub fn bytes(n: f64) -> String {
    let units = ["o", "Ko", "Mo", "Go"];
    let mut n = n.max(0.0);
    let mut i = 0;
    while n >= 1024.0 && i < units.len() - 1 {
        n /= 1024.0;
        i += 1;
    }
    let s = if i == 0 {
        format!("{n:.0}")
    } else {
        format!("{n:.1}")
    };
    format!("{} {}", s.replace('.', ","), units[i])
}

// ----- Toasts, fenêtres, menus ----------------------------------------------

pub fn toast(text: impl AsRef<str>) {
    // Une ligne ou deux, pas un message entier.
    let full = text.as_ref();
    let text = if full.chars().count() > 110 {
        format!("{}…", full.chars().take(108).collect::<String>().trim_end())
    } else {
        full.to_owned()
    };
    let host = by_id("toasts").unwrap_or_else(|| {
        let el = h("div#toasts").el();
        let _ = document().body().expect("body").append_child(&el);
        el
    });
    let el = h("div.toast").attr("role", "status").text(text).el();
    let _ = host.append_child(&el);
    let shown = el.clone();
    timer(20, move || toggle_class(&shown, "show", true));
    spawn(async move {
        sleep(2800).await;
        toggle_class(&el, "show", false);
        sleep(300).await;
        el.remove();
    });
}

/// Une fenêtre ouverte.
#[derive(Clone)]
pub struct Modal(Rc<ModalInner>);

struct ModalInner {
    backdrop: Element,
    box_: Element,
    scope: String,
    closed: Cell<bool>,
    on_close: RefCell<Option<Box<dyn FnOnce()>>>,
}

pub struct Action {
    pub label: String,
    pub class: &'static str,
    pub run: Box<dyn Fn(&Modal)>,
}

pub fn action(
    label: impl Into<String>,
    class: &'static str,
    run: impl Fn(&Modal) + 'static,
) -> Action {
    Action {
        label: label.into(),
        class,
        run: Box::new(run),
    }
}

#[derive(Default)]
pub struct ModalOpts {
    pub actions: Vec<Action>,
    pub wide: bool,
    pub class: &'static str,
    pub on_close: Option<Box<dyn FnOnce()>>,
}

impl Modal {
    pub fn el(&self) -> Element {
        self.0.box_.clone()
    }

    pub fn body(&self) -> Element {
        query_in(&self.0.box_, ".modal-body").unwrap_or_else(|| self.0.box_.clone())
    }

    /// Les écouteurs créés dans `f` vivent autant que la fenêtre.
    pub fn scoped<T>(&self, f: impl FnOnce() -> T) -> T {
        STACK.with(|s| s.borrow_mut().push(self.0.scope.clone()));
        let out = f();
        STACK.with(|s| s.borrow_mut().pop());
        out
    }

    pub fn close(&self) {
        if self.0.closed.replace(true) {
            return;
        }
        toggle_class(&self.0.backdrop, "show", false);
        let backdrop = self.0.backdrop.clone();
        let scope = self.0.scope.clone();
        timer(200, move || {
            backdrop.remove();
            drop_scope(&scope);
        });
        if let Some(f) = self.0.on_close.borrow_mut().take() {
            f();
        }
    }
}

/// Une fenêtre modale. Le corps est construit dans la zone de la fenêtre :
/// ses écouteurs sont libérés à la fermeture.
pub fn modal(title: &str, body: impl FnOnce(&Modal) -> Child, opts: ModalOpts) -> Modal {
    let scope = unique("modal");
    let backdrop = h("div.modal-backdrop").el();
    let box_ = h("div.modal")
        .class_if("wide", opts.wide)
        .class(opts.class)
        .attr("role", "dialog")
        .attr("aria-modal", "true")
        .attr(
            "aria-label",
            if title.is_empty() { "Fenêtre" } else { title },
        )
        .el();
    let m = Modal(Rc::new(ModalInner {
        backdrop: backdrop.clone(),
        box_: box_.clone(),
        scope: scope.clone(),
        closed: Cell::new(false),
        on_close: RefCell::new(opts.on_close),
    }));
    m.scoped(|| {
        if !title.is_empty() {
            let mm = m.clone();
            append(
                &box_,
                h("header.modal-head")
                    .child(h("h2").text(title))
                    .child(icon_button("close", "Fermer", move || mm.close()))
                    .into(),
            );
        }
        let content = body(&m);
        append(&box_, h("div.modal-body").child(content).into());
        if !opts.actions.is_empty() {
            let mut foot = h("footer.modal-actions");
            for a in opts.actions {
                let mm = m.clone();
                let run = a.run;
                foot = foot.child(
                    h("button.btn")
                        .class(a.class)
                        .attr("type", "button")
                        .text(&a.label)
                        .click(move || run(&mm)),
                );
            }
            append(&box_, foot.into());
        }
        let mm = m.clone();
        let bd = backdrop.clone();
        listen(&backdrop, "mousedown", move |e| {
            if e.target()
                .and_then(|t| t.dyn_into::<Element>().ok())
                .as_ref()
                == Some(&bd)
            {
                mm.close();
            }
        });
        let mm = m.clone();
        listen(&document(), "keydown", move |e| {
            if e.dyn_ref::<KeyboardEvent>()
                .is_some_and(|k| k.key() == "Escape")
            {
                // Seulement la fenêtre du dessus.
                let top = query_all_in(
                    &document().document_element().expect("html"),
                    ".modal-backdrop",
                )
                .pop();
                if top.as_ref() == Some(&mm.0.backdrop) {
                    mm.close();
                }
            }
        });
    });
    let _ = backdrop.append_child(&box_);
    let _ = document().body().expect("body").append_child(&backdrop);
    let bd = backdrop.clone();
    timer(10, move || toggle_class(&bd, "show", true));
    let b = box_.clone();
    timer(60, move || {
        if let Some(f) = query_in(&b, "input,textarea,button.primary") {
            focus(&f);
        }
    });
    m
}

/// Oui ou non.
pub async fn confirm_box(title: &str, text: &str, ok: &str, danger: bool) -> bool {
    let d = Deferred::<bool>::new();
    let s1 = d.sender();
    let s2 = d.sender();
    let s3 = d.sender();
    let text = text.to_owned();
    modal(
        title,
        move |_| h("p").text(&text).into(),
        ModalOpts {
            actions: vec![
                action("Annuler", "ghost", move |m| {
                    s1.send(Some(false));
                    m.close();
                }),
                action(ok, if danger { "danger" } else { "primary" }, move |m| {
                    s2.send(Some(true));
                    m.close();
                }),
            ],
            on_close: Some(Box::new(move || s3.send(Some(false)))),
            ..Default::default()
        },
    );
    d.wait().await.unwrap_or(false)
}

/// Une ligne de texte à saisir (`None` : annulé).
pub async fn prompt_box(
    title: &str,
    label: &str,
    value: &str,
    kind: &str,
    ok: &str,
    max: u32,
) -> Option<String> {
    let d = Deferred::<String>::new();
    let input = h("input.field")
        .attr("type", kind)
        .attr("maxlength", max.to_string())
        .attr("aria-label", if label.is_empty() { title } else { label })
        .prop("value", value)
        .el();
    let (s1, s2) = (d.sender(), d.sender());
    let i1 = input.clone();
    let label = label.to_owned();
    let i2 = input.clone();
    let m = modal(
        title,
        move |_| {
            h("div.stack")
                .child((!label.is_empty()).then(|| h("label.muted").text(&label)))
                .child(i2)
                .into()
        },
        ModalOpts {
            actions: vec![
                action("Annuler", "ghost", |m| m.close()),
                action(ok, "primary", move |m| {
                    s1.send(Some(value_of(&i1)));
                    m.close();
                }),
            ],
            on_close: Some(Box::new(move || s2.send(None))),
            ..Default::default()
        },
    );
    let s = d.sender();
    let i3 = input.clone();
    let mm = m.clone();
    m.scoped(move || {
        listen(&input, "keydown", move |e| {
            if e.dyn_ref::<KeyboardEvent>()
                .is_some_and(|k| k.key() == "Enter")
            {
                s.send(Some(value_of(&i3)));
                mm.close();
            }
        });
    });
    d.wait().await
}

fn value_of(el: &Element) -> String {
    value(el)
}

/// Une entrée de menu.
pub struct Item {
    pub icon: &'static str,
    pub label: String,
    pub danger: bool,
    pub run: Box<dyn Fn()>,
}

pub fn item(
    icon: &'static str,
    label: impl Into<String>,
    run: impl Fn() + 'static,
) -> Option<Item> {
    Some(Item {
        icon,
        label: label.into(),
        danger: false,
        run: Box::new(run),
    })
}

pub fn danger(
    icon: &'static str,
    label: impl Into<String>,
    run: impl Fn() + 'static,
) -> Option<Item> {
    Some(Item {
        icon,
        label: label.into(),
        danger: true,
        run: Box::new(run),
    })
}

/// Où ouvrir un menu : près d'un élément, ou d'un point.
pub enum At {
    El(Element),
    Point(f64, f64),
}

/// Un menu contextuel ; renvoie son élément (pour y ajouter des réactions).
pub fn menu(items: Vec<Option<Item>>, at: At) -> Element {
    if let Some(old) = query(".ctx-menu") {
        old.remove();
    }
    let scope = unique("menu");
    let el = h("div.ctx-menu").attr("role", "menu").el();
    scope_into(&scope, || {
        for it in items.into_iter().flatten() {
            let menu_el = el.clone();
            let sc = scope.clone();
            let run = it.run;
            let b = h("button")
                .attr("type", "button")
                .attr("role", "menuitem")
                .class_if("danger", it.danger)
                .child(icon(it.icon))
                .child(h("span").text(&it.label))
                .click(move || {
                    menu_el.remove();
                    let sc = sc.clone();
                    timer(0, move || drop_scope(&sc));
                    run();
                });
            append(&el, b.into());
        }
    });
    let _ = document().body().expect("body").append_child(&el);
    let (left, right, top, bottom) = match &at {
        At::El(e) => {
            let r = e.get_bounding_client_rect();
            (r.left(), r.right(), r.top(), r.bottom())
        }
        At::Point(x, y) => (*x, *x, *y, *y),
    };
    set(
        &el,
        "__talka_at",
        js_sys::Array::of4(&left.into(), &right.into(), &top.into(), &bottom.into()),
    );
    place_menu(&el);
    let menu_el = el.clone();
    let sc = scope.clone();
    timer(0, move || {
        scope_into(&sc.clone(), || {
            let m = menu_el.clone();
            let sc2 = sc.clone();
            listen(&document(), "pointerdown", move |e| {
                let inside = e
                    .target()
                    .and_then(|t| t.dyn_into::<web_sys::Node>().ok())
                    .is_some_and(|n| m.contains(Some(&n)));
                if !inside {
                    m.remove();
                    let sc = sc2.clone();
                    timer(0, move || drop_scope(&sc));
                }
            });
        });
    });
    el
}

/// Place un menu près de son point d'ancrage, entièrement dans l'écran
/// (à rappeler si son contenu change).
pub fn place_menu(el: &Element) {
    let at = js_sys::Array::from(&get(el, "__talka_at"));
    let n = |i: u32| at.get(i).as_f64().unwrap_or(8.0);
    let (left, right, top, bottom) = (n(0), n(1), n(2), n(3));
    let html = el.dyn_ref::<HtmlElement>().expect("menu");
    let (w, hh) = (html.offset_width() as f64, html.offset_height() as f64);
    let (vw, vh) = (
        window()
            .inner_width()
            .ok()
            .and_then(|v| v.as_f64())
            .unwrap_or(400.0),
        window()
            .inner_height()
            .ok()
            .and_then(|v| v.as_f64())
            .unwrap_or(700.0),
    );
    let mut x = (right - w).min(vw - w - 8.0);
    if x < 8.0 {
        x = left.min(vw - w - 8.0);
    }
    let mut y = bottom + 4.0;
    if y + hh > vh - 8.0 {
        y = (top - hh - 4.0).max(8.0);
    }
    set_style(el, "left", &format!("{}px", x.max(8.0)));
    set_style(el, "top", &format!("{}px", y.min(vh - hh - 8.0).max(8.0)));
}

/// Ajoute des écouteurs à une zone existante, sans la vider.
pub fn scope_into<T>(name: &str, f: impl FnOnce() -> T) -> T {
    STACK.with(|s| s.borrow_mut().push(name.to_owned()));
    let out = f();
    STACK.with(|s| s.borrow_mut().pop());
    out
}

// ----- Texte -----------------------------------------------------------------

/// Le texte d'un message : liens cliquables, *gras*, _italique_, ~barré~, `code`.
pub fn rich_text(text: &str) -> Vec<Child> {
    // Un jeton Brocolis qu'aucune clé n'ouvre : « 🔒🥦 », pas un pavé de base64.
    let masked = crate::jetons::masked(text);
    let text: &str = &masked;
    let mut out = Vec::new();
    let mut plain = String::new();
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    let flush = |plain: &mut String, out: &mut Vec<Child>| {
        if !plain.is_empty() {
            out.push(Child::Text(std::mem::take(plain)));
        }
    };
    while i < chars.len() {
        let rest: String = chars[i..].iter().take(8).collect();
        let at_word_start = i == 0 || chars[i - 1].is_whitespace();
        if at_word_start && (rest.starts_with("https://") || rest.starts_with("http://")) {
            let mut j = i;
            while j < chars.len() && !chars[j].is_whitespace() && chars[j] != '<' {
                j += 1;
            }
            while j > i && ".,;:!?)]'\"".contains(chars[j - 1]) {
                j -= 1;
            }
            let url: String = chars[i..j].iter().collect();
            flush(&mut plain, &mut out);
            out.push(
                h("a")
                    .attr("href", &url)
                    .attr("target", "_blank")
                    .attr("rel", "noopener noreferrer")
                    .text(&url)
                    .into(),
            );
            i = j;
            continue;
        }
        let c = chars[i];
        if "*_~`".contains(c)
            && let Some(len) = chars[i + 1..].iter().position(|&x| x == c || x == '\n')
            && len > 0
            && chars[i + 1 + len] == c
        {
            let inner: String = chars[i + 1..i + 1 + len].iter().collect();
            if !inner.starts_with(' ') && !inner.ends_with(' ') {
                flush(&mut plain, &mut out);
                let tag = match c {
                    '*' => "strong",
                    '_' => "em",
                    '~' => "s",
                    _ => "code",
                };
                out.push(h(tag).text(&inner).into());
                i += len + 2;
                continue;
            }
        }
        plain.push(c);
        i += 1;
    }
    flush(&mut plain, &mut out);
    out
}

/// Un message fait seulement de 1 à 3 emojis s'affiche en grand.
pub fn only_emoji(text: &str) -> bool {
    let t = text.trim();
    if t.is_empty() || t.chars().count() > 16 {
        return false;
    }
    let mut count = 0;
    for c in t.chars() {
        let u = c as u32;
        let joiner = u == 0x200d
            || u == 0xfe0f
            || (0x1f3fb..=0x1f3ff).contains(&u)
            || (0xe0020..=0xe007f).contains(&u);
        let pictographic = (0x1f000..=0x1faff).contains(&u)
            || (0x2600..=0x27bf).contains(&u)
            || (0x2190..=0x21ff).contains(&u)
            || (0x2b00..=0x2bff).contains(&u)
            || u == 0x2764
            || (0x1f1e6..=0x1f1ff).contains(&u);
        if c.is_whitespace() || joiner {
            continue;
        }
        if !pictographic {
            return false;
        }
        count += 1;
    }
    count > 0 && count <= 4
}

/// Télécharge une adresse (`data:`, `blob:`) sous un nom.
pub fn download(name: &str, url: &str) {
    let a = h("a").attr("href", url).attr("download", name).el();
    let _ = document().body().expect("body").append_child(&a);
    if let Some(a) = a.dyn_ref::<HtmlElement>() {
        a.click();
    }
    a.remove();
}

/// Copie dans le presse-papiers.
pub async fn copy(text: &str, done: &str) {
    let clipboard = get(&window().navigator(), "clipboard");
    let ok = !clipboard.is_undefined()
        && await_promise(call(&clipboard, "writeText", &[JsValue::from_str(text)]))
            .await
            .is_ok();
    if ok {
        toast(done);
    } else {
        let _ = window().prompt_with_message_and_default("Copiez :", text);
    }
}

/// Lance la lecture d'un média ; une lecture interrompue n'est pas une erreur.
pub fn play(media: &web_sys::HtmlMediaElement) {
    thread_local! {
        static IGNORE: Closure<dyn FnMut(JsValue)> = Closure::new(|_| {});
    }
    if let Ok(p) = media.play() {
        IGNORE.with(|f| {
            let _ = p.catch(f);
        });
    }
}

/// L'historique : une entrée pour la discussion ouverte (bouton Retour).
pub fn push_state(tag: &str) {
    if let Ok(h) = window().history() {
        let state = js_sys::Object::new();
        set(&state, "talka", tag);
        let _ = h.push_state_with_url(&state, "", None);
    }
}

pub fn history_tag() -> String {
    window()
        .history()
        .ok()
        .and_then(|h| h.state().ok())
        .map(|s| get(&s, "talka"))
        .and_then(|v| v.as_string())
        .unwrap_or_default()
}

pub fn back() {
    if let Ok(h) = window().history() {
        let _ = h.back();
    }
}
