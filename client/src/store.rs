//! La mémoire de Talka : IndexedDB, dans le navigateur. Pas de base de
//! données ailleurs : ce qui n'est pas ici n'existe pas.
//!
//! Chaque magasin range des objets `{ key, json }` (le JSON d'une structure
//! de `model`) ; les messages ont en plus `chat` et `at`, pour l'index qui
//! les lit dans l'ordre.
//!
//! | Magasin | Contenu |
//! |---|---|
//! | `kv` | identité, profil, réglages, code de verrouillage |
//! | `contacts` | `Contact` (clé : le code) |
//! | `chats` | `Chat` |
//! | `messages` | `Message` (clé : `chat|id`) |
//! | `outbox` | `OutItem` : les enveloppes pas encore confirmées par le relais |
//! | `statuses` | `StatusItem` |
//! | `calls` | `CallEntry` |

use std::cell::RefCell;

use js_sys::{Array, Object, Promise};
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;
use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{
    IdbCursorWithValue, IdbDatabase, IdbKeyRange, IdbObjectStoreParameters, IdbOpenDbRequest,
    IdbRequest, IdbTransactionMode,
};

use crate::dom::{get, set, window};

const NAME: &str = "talka-rs";
const VERSION: u32 = 1;
pub const STORES: [&str; 7] = [
    "kv", "contacts", "chats", "messages", "outbox", "statuses", "calls",
];

thread_local! {
    static DB: RefCell<Option<IdbDatabase>> = const { RefCell::new(None) };
}

fn db() -> Result<IdbDatabase, JsValue> {
    DB.with(|d| d.borrow().clone())
        .ok_or_else(|| JsValue::from_str("base fermée"))
}

/// Attend une requête IndexedDB.
async fn wait(req: &IdbRequest) -> Result<JsValue, JsValue> {
    let r = req.clone();
    let promise = Promise::new(&mut |resolve, reject| {
        let rr = r.clone();
        let ok = Closure::once_into_js(move || {
            let _ = resolve.call1(&JsValue::NULL, &rr.result().unwrap_or(JsValue::UNDEFINED));
        });
        let err = Closure::once_into_js(move || {
            let _ = reject.call1(&JsValue::NULL, &JsValue::from_str("indexeddb"));
        });
        r.set_onsuccess(Some(ok.unchecked_ref()));
        r.set_onerror(Some(err.unchecked_ref()));
    });
    JsFuture::from(promise).await
}

/// Ouvre la base (et la crée la première fois).
pub async fn open() -> Result<(), JsValue> {
    if DB.with(|d| d.borrow().is_some()) {
        return Ok(());
    }
    let factory = window()
        .indexed_db()?
        .ok_or_else(|| JsValue::from_str("IndexedDB indisponible"))?;
    let req: IdbOpenDbRequest = factory.open_with_u32(NAME, VERSION)?;
    let upgrade_req = req.clone();
    let upgrade = Closure::<dyn FnMut()>::new(move || {
        let Ok(result) = upgrade_req.result() else {
            return;
        };
        let db: IdbDatabase = result.unchecked_into();
        for name in STORES {
            if db.object_store_names().contains(name) {
                continue;
            }
            let params = IdbObjectStoreParameters::new();
            params.set_key_path(&JsValue::from_str("key"));
            if let Ok(store) = db.create_object_store_with_optional_parameters(name, &params)
                && name == "messages"
            {
                let path = Array::of2(&JsValue::from_str("chat"), &JsValue::from_str("at"));
                let _ = store.create_index_with_str_sequence("chat_at", &path);
            }
        }
    });
    req.set_onupgradeneeded(Some(upgrade.as_ref().unchecked_ref()));
    let result = wait(&req).await;
    drop(upgrade);
    let db: IdbDatabase = result?.unchecked_into();
    DB.with(|d| *d.borrow_mut() = Some(db));
    Ok(())
}

fn object_store(name: &str, write: bool) -> Result<web_sys::IdbObjectStore, JsValue> {
    let mode = if write {
        IdbTransactionMode::Readwrite
    } else {
        IdbTransactionMode::Readonly
    };
    db()?
        .transaction_with_str_and_mode(name, mode)?
        .object_store(name)
}

fn record(key: &str, json: &str) -> Object {
    let o = Object::new();
    set(&o, "key", key);
    set(&o, "json", json);
    o
}

fn parse<T: DeserializeOwned>(v: &JsValue) -> Option<T> {
    serde_json::from_str(&get(v, "json").as_string()?).ok()
}

pub async fn get_raw(store: &str, key: &str) -> Option<String> {
    let req = object_store(store, false)
        .ok()?
        .get(&JsValue::from_str(key))
        .ok()?;
    let v = wait(&req).await.ok()?;
    if v.is_undefined() || v.is_null() {
        return None;
    }
    get(&v, "json").as_string()
}

pub async fn read<T: DeserializeOwned>(store: &str, key: &str) -> Option<T> {
    serde_json::from_str(&get_raw(store, key).await?).ok()
}

pub async fn write<T: Serialize>(store: &str, key: &str, value: &T) {
    let Ok(json) = serde_json::to_string(value) else {
        return;
    };
    put_record(store, record(key, &json)).await;
}

async fn put_record(store: &str, rec: Object) {
    if let Ok(s) = object_store(store, true)
        && let Ok(req) = s.put(&rec)
    {
        let _ = wait(&req).await;
    }
}

/// Un message : rangé avec `chat` et `at`, pour l'index.
pub async fn write_message(m: &crate::model::Message) {
    let Ok(json) = serde_json::to_string(m) else {
        return;
    };
    let rec = record(&m.key(), &json);
    set(&rec, "chat", m.chat.as_str());
    set(&rec, "at", m.at);
    put_record("messages", rec).await;
}

pub async fn remove(store: &str, key: &str) {
    if let Ok(s) = object_store(store, true)
        && let Ok(req) = s.delete(&JsValue::from_str(key))
    {
        let _ = wait(&req).await;
    }
}

pub async fn all<T: DeserializeOwned>(store: &str) -> Vec<T> {
    let Ok(s) = object_store(store, false) else {
        return Vec::new();
    };
    let Ok(req) = s.get_all() else {
        return Vec::new();
    };
    let Ok(v) = wait(&req).await else {
        return Vec::new();
    };
    Array::from(&v).iter().filter_map(|r| parse(&r)).collect()
}

pub async fn clear(store: &str) {
    if let Ok(s) = object_store(store, true)
        && let Ok(req) = s.clear()
    {
        let _ = wait(&req).await;
    }
}

/// Parcourt les messages d'une discussion avec un curseur, du plus récent au
/// plus ancien : au plus `limit`, ou tous en les effaçant (`delete`).
async fn cursor(chat: &str, limit: usize, delete: bool) -> Vec<JsValue> {
    let Ok(s) = object_store("messages", delete) else {
        return Vec::new();
    };
    let Ok(index) = s.index("chat_at") else {
        return Vec::new();
    };
    let lower = Array::of2(&JsValue::from_str(chat), &JsValue::from_f64(f64::MIN));
    let upper = Array::of2(&JsValue::from_str(chat), &JsValue::from_f64(f64::MAX));
    let Ok(range) = IdbKeyRange::bound(&lower, &upper) else {
        return Vec::new();
    };
    let Ok(req) =
        index.open_cursor_with_range_and_direction(&range, web_sys::IdbCursorDirection::Prev)
    else {
        return Vec::new();
    };
    let found = std::rc::Rc::new(RefCell::new(Vec::new()));
    let f = found.clone();
    let r = req.clone();
    let mut resolve_slot: Option<js_sys::Function> = None;
    let promise = Promise::new(&mut |resolve, _| resolve_slot = Some(resolve));
    let resolve = resolve_slot.expect("promesse");
    let step = Closure::<dyn FnMut()>::new(move || {
        let c = r
            .result()
            .ok()
            .filter(|v| !v.is_null() && !v.is_undefined());
        match c {
            Some(c) if f.borrow().len() < limit => {
                let c: IdbCursorWithValue = c.unchecked_into();
                if let Ok(v) = c.value() {
                    f.borrow_mut().push(v);
                }
                if delete {
                    let _ = c.delete();
                }
                let _ = c.continue_();
            }
            _ => {
                let _ = resolve.call0(&JsValue::NULL);
            }
        }
    });
    req.set_onsuccess(Some(step.as_ref().unchecked_ref()));
    let _ = JsFuture::from(promise).await;
    drop(step);
    found.take()
}

/// Les messages d'une discussion, du plus ancien au plus récent ; `limit`
/// garde les derniers.
pub async fn messages_of(chat: &str, limit: usize) -> Vec<crate::model::Message> {
    let mut out: Vec<crate::model::Message> = cursor(chat, limit, false)
        .await
        .iter()
        .filter_map(parse)
        .collect();
    out.reverse();
    out
}

pub async fn delete_messages_of(chat: &str) {
    cursor(chat, usize::MAX, true).await;
}

/// Tout, pour la sauvegarde : `{ magasin: [[clé, json], …] }`.
pub async fn dump() -> Value {
    let mut out = serde_json::Map::new();
    for name in STORES {
        let Ok(s) = object_store(name, false) else {
            continue;
        };
        let Ok(req) = s.get_all() else { continue };
        let rows: Vec<Value> = match wait(&req).await {
            Ok(v) => Array::from(&v)
                .iter()
                .map(|r| {
                    let mut row = vec![
                        Value::String(get(&r, "key").as_string().unwrap_or_default()),
                        Value::String(get(&r, "json").as_string().unwrap_or_default()),
                    ];
                    if name == "messages" {
                        row.push(Value::String(
                            get(&r, "chat").as_string().unwrap_or_default(),
                        ));
                        row.push(
                            get(&r, "at")
                                .as_f64()
                                .map(Value::from)
                                .unwrap_or(Value::Null),
                        );
                    }
                    Value::Array(row)
                })
                .collect(),
            Err(_) => Vec::new(),
        };
        out.insert(name.to_owned(), Value::Array(rows));
    }
    Value::Object(out)
}

pub async fn restore(data: &Value) {
    for name in STORES {
        clear(name).await;
        for row in data[name].as_array().into_iter().flatten() {
            let (Some(key), Some(json)) = (row[0].as_str(), row[1].as_str()) else {
                continue;
            };
            let rec = record(key, json);
            if name == "messages" {
                set(&rec, "chat", row[2].as_str().unwrap_or_default());
                set(&rec, "at", row[3].as_f64().unwrap_or(0.0));
            }
            put_record(name, rec).await;
        }
    }
}

pub async fn wipe() {
    for name in STORES {
        clear(name).await;
    }
}

/// L'espace occupé (octets), quand le navigateur sait le dire.
pub async fn usage() -> Option<f64> {
    let storage = get(&window().navigator(), "storage");
    if storage.is_undefined() {
        return None;
    }
    let est = crate::dom::await_promise(crate::dom::call(&storage, "estimate", &[]))
        .await
        .ok()?;
    get(&est, "usage").as_f64()
}

/// Demande au navigateur de ne pas effacer Talka quand la place manque.
pub async fn persist() {
    let storage = get(&window().navigator(), "storage");
    if !storage.is_undefined() {
        let _ = crate::dom::await_promise(crate::dom::call(&storage, "persist", &[])).await;
    }
}

/// Une petite valeur de l'appareil (`localStorage`) : emojis récents…
pub fn local_get(key: &str) -> Option<String> {
    window()
        .local_storage()
        .ok()
        .flatten()?
        .get_item(key)
        .ok()
        .flatten()
}

pub fn local_set(key: &str, value: &str) {
    if let Some(s) = window().local_storage().ok().flatten() {
        let _ = s.set_item(key, value);
    }
}

pub fn local_clear() {
    if let Some(s) = window().local_storage().ok().flatten() {
        let _ = s.clear();
    }
}
