//! La mémoire de Talka dans le navigateur : Memoire ORM (voir [`crate::rows`])
//! dont les lignes sont gardées dans IndexedDB. Pas de base de données
//! ailleurs : ce qui n'est pas ici n'existe pas.
//!
//! Memoire range ses lignes dans un stockage clé-valeur synchrone
//! ([`KeyValueStorage`]), comme `localStorage` ; mais `localStorage` ne tient
//! qu'environ 5 Mo, trop peu pour des photos et des vocaux. [`IdbKv`] lui
//! donne IndexedDB (des centaines de Mo) : tout est lu une fois à
//! l'ouverture (l'écran de chargement), chaque écriture va aussitôt dans
//! cette copie et part vers IndexedDB juste après, dans l'ordre.
//!
//! Un [`Batch`] retient ces envois le temps d'un traitement : tout ce qui
//! est écrit pendant ce temps part dans **une seule** transaction IndexedDB
//! (tout ou rien). C'est ainsi qu'un message reçu en v3 est rangé avec
//! l'état de session qui l'a déchiffré ; et rien ne part vers le relais
//! (`got`, un envoi chiffré) avant que cet état soit écrit ([`committed`]).
//!
//! Les données d'avant Memoire (la base IndexedDB `talka-rs`, un magasin par
//! sorte d'objet) sont reprises une fois, puis cette ancienne base est
//! supprimée.
//!
//! **Chiffré au repos.** Chaque valeur part vers IndexedDB chiffrée
//! (AES-256-GCM, [`crate::crypto::seal_row`]) avec la clé de l'appareil, et
//! n'est déchiffrée qu'en mémoire : Talka affiche les messages en clair,
//! le disque n'en garde que du chiffré. La clé de l'appareil (32 octets
//! tirés une fois) est gardée dans une autre base (`talka-cle`), chiffrée
//! par une clé WebCrypto **non exportable** : le navigateur ne la donne
//! jamais, ni au code, ni à qui lirait les fichiers d'IndexedDB. Les
//! valeurs d'avant (du texte en clair) sont relues, puis réécrites
//! chiffrées une fois. La sauvegarde (`dump`), elle, ne change pas : elle
//! a son propre chiffrement, par son mot de passe.

use std::cell::{Cell, RefCell};
use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use js_sys::{Array, Object, Promise, Reflect, Uint8Array};
use memoire_orm::web::KeyValueStorage;
use serde::Serialize;
use serde::de::DeserializeOwned;
use serde_json::Value;
use wasm_bindgen::JsCast;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{IdbDatabase, IdbOpenDbRequest, IdbRequest, IdbTransaction, IdbTransactionMode};

use crate::crypto;
use crate::dom::{await_promise, call, get, window};
use crate::rows::{Rows, STORES};

/// La base IndexedDB où Memoire garde ses lignes (une clé, une valeur).
const NAME: &str = "talka-memoire";
const KV: &str = "kv";
/// La base d'avant Memoire.
const LEGACY: &str = "talka-rs";
/// La base de la clé de l'appareil (à part : elle ne se lit pas avec le
/// reste).
const KEY_DB: &str = "talka-cle";
const KEY_STORE: &str = "cle";

thread_local! {
    static ROWS: RefCell<Option<Rows>> = const { RefCell::new(None) };
    static IDB: RefCell<Option<IdbDatabase>> = const { RefCell::new(None) };
    static FLUSHING: Cell<bool> = const { Cell::new(false) };
    /// Un autre onglet a pris la main : plus aucune écriture d'ici.
    static RELEASED: Cell<bool> = const { Cell::new(false) };
    /// Les [`Batch`] ouverts : tant qu'il y en a, rien ne part vers IndexedDB.
    static HOLD: Cell<u32> = const { Cell::new(0) };
    /// La clé de l'appareil, déchiffrée pour la session.
    static DEVICE_KEY: RefCell<Option<Vec<u8>>> = const { RefCell::new(None) };
}

/// Les écritures pas encore parties vers IndexedDB, dans l'ordre.
static QUEUE: Mutex<Vec<(String, Option<String>)>> = Mutex::new(Vec::new());

// ----- IndexedDB ---------------------------------------------------------------

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

/// Une promesse tenue quand la transaction est terminée (ou abandonnée).
fn done(tx: &IdbTransaction) -> Promise {
    let t = tx.clone();
    Promise::new(&mut |resolve, _| {
        let end = Closure::once_into_js(move || {
            let _ = resolve.call0(&JsValue::NULL);
        });
        // L'une ou l'autre, une seule fois (une erreur finit en abandon).
        t.set_oncomplete(Some(end.unchecked_ref()));
        t.set_onabort(Some(end.unchecked_ref()));
    })
}

/// Ouvre une base ; `upgrade` la prépare quand elle est nouvelle.
async fn open_idb(
    name: &str,
    upgrade: impl Fn(&IdbDatabase) + 'static,
) -> Result<IdbDatabase, JsValue> {
    let factory = window()
        .indexed_db()?
        .ok_or_else(|| JsValue::from_str("IndexedDB indisponible"))?;
    let req: IdbOpenDbRequest = factory.open_with_u32(name, 1)?;
    let up = req.clone();
    let on_upgrade = Closure::<dyn FnMut()>::new(move || {
        if let Ok(result) = up.result() {
            upgrade(&result.unchecked_into());
        }
    });
    req.set_onupgradeneeded(Some(on_upgrade.as_ref().unchecked_ref()));
    let result = wait(&req).await;
    drop(on_upgrade);
    Ok(result?.unchecked_into())
}

// ----- La clé de l'appareil -----------------------------------------------------

fn subtle() -> JsValue {
    get(&get(&window(), "crypto"), "subtle")
}

fn aes_gcm(iv: &[u8]) -> Result<JsValue, JsValue> {
    let alg = Object::new();
    Reflect::set(&alg, &"name".into(), &"AES-GCM".into())?;
    Reflect::set(&alg, &"iv".into(), &Uint8Array::from(iv).into())?;
    Ok(alg.into())
}

/// Ce qui protège la clé de l'appareil, en plus du navigateur.
#[derive(Clone, Debug, PartialEq)]
pub enum Lock {
    /// Rien : Talka s'ouvre seul.
    None,
    /// Le code de verrouillage (Argon2id).
    Code,
    /// Une passkey (WebAuthn, extension PRF) : son identifiant et le sel.
    Passkey { id: Vec<u8>, salt: Vec<u8> },
}

/// Une nouvelle protection pour la clé de l'appareil.
pub enum NewLock<'a> {
    None,
    Code(&'a str),
    Passkey {
        id: Vec<u8>,
        salt: Vec<u8>,
        prf: Vec<u8>,
    },
}

async fn key_db() -> Result<IdbDatabase, JsValue> {
    open_idb(KEY_DB, |db| {
        if !db.object_store_names().contains(KEY_STORE) {
            let _ = db.create_object_store(KEY_STORE);
        }
    })
    .await
}

/// L'enregistrement de la clé (`{wrap, iv, data, lock?}`), ou `undefined`.
async fn read_record(db: &IdbDatabase) -> Result<JsValue, JsValue> {
    let req = db
        .transaction_with_str(KEY_STORE)?
        .object_store(KEY_STORE)?
        .get(&"repos".into())?;
    wait(&req).await
}

/// Ce que garde l'enregistrement, ouvert par la clé d'emballage : la clé de
/// l'appareil (32 octets), ou la clé verrouillée par l'utilisateur (60).
async fn unwrap_inner(record: &JsValue) -> Result<Vec<u8>, JsValue> {
    let iv = Uint8Array::new(&get(record, "iv")).to_vec();
    let plain = await_promise(call(
        &subtle(),
        "decrypt",
        &[aes_gcm(&iv)?, get(record, "wrap"), get(record, "data")],
    ))
    .await?;
    Ok(Uint8Array::new(&plain).to_vec())
}

/// Écrit l'enregistrement : `inner` chiffré par la clé d'emballage `wrap`.
/// `add` : seulement s'il n'y en a pas encore (sinon `false`).
async fn write_record(
    db: &IdbDatabase,
    wrap: &JsValue,
    inner: &[u8],
    lock: &JsValue,
    add: bool,
) -> Result<bool, JsValue> {
    let iv = crypto::random_bytes(12);
    let data = await_promise(call(
        &subtle(),
        "encrypt",
        &[aes_gcm(&iv)?, wrap.clone(), Uint8Array::from(inner).into()],
    ))
    .await?;
    let record = Object::new();
    Reflect::set(&record, &"wrap".into(), wrap)?;
    Reflect::set(
        &record,
        &"iv".into(),
        &Uint8Array::from(iv.as_slice()).into(),
    )?;
    Reflect::set(&record, &"data".into(), &data)?;
    if !lock.is_undefined() {
        Reflect::set(&record, &"lock".into(), lock)?;
    }
    let tx = db.transaction_with_str_and_mode(KEY_STORE, IdbTransactionMode::Readwrite)?;
    let store = tx.object_store(KEY_STORE)?;
    let req = if add {
        store.add_with_key(&record, &"repos".into())
    } else {
        store.put_with_key(&record, &"repos".into())
    };
    let ok = match req {
        Ok(req) => wait(&req).await.is_ok(),
        Err(_) => false,
    };
    let _ = JsFuture::from(done(&tx)).await;
    Ok(ok)
}

fn bytes(v: &JsValue) -> Vec<u8> {
    if v.is_undefined() || v.is_null() {
        Vec::new()
    } else {
        Uint8Array::new(v).to_vec()
    }
}

fn lock_of(record: &JsValue) -> Lock {
    let lock = get(record, "lock");
    match get(&lock, "kind").as_string().as_deref() {
        Some("code") => Lock::Code,
        Some("passkey") => Lock::Passkey {
            id: bytes(&get(&lock, "id")),
            salt: bytes(&get(&lock, "salt")),
        },
        _ => Lock::None,
    }
}

/// Ce qui protège la clé de l'appareil (lu avant d'ouvrir la mémoire).
pub async fn lock() -> Lock {
    let Ok(db) = key_db().await else {
        return Lock::None;
    };
    let lock = match read_record(&db).await {
        Ok(r) if !r.is_undefined() && !r.is_null() => lock_of(&r),
        _ => Lock::None,
    };
    db.close();
    lock
}

/// Ouvre la clé de l'appareil avec la clé de l'utilisateur ; `false` si ce
/// n'est pas la bonne. Ensuite, [`open`] ouvre la mémoire.
async fn unlock_with(user_key: impl FnOnce(&JsValue) -> Option<[u8; 32]>) -> bool {
    let Ok(db) = key_db().await else {
        return false;
    };
    let record = read_record(&db).await.unwrap_or(JsValue::UNDEFINED);
    db.close();
    if record.is_undefined() || record.is_null() {
        return false;
    }
    let Ok(inner) = unwrap_inner(&record).await else {
        return false;
    };
    let Some(key) = user_key(&get(&record, "lock")) else {
        return false;
    };
    match crypto::unlock_device_key(&key, &inner) {
        Some(device) if device.len() == 32 => {
            DEVICE_KEY.with(|k| *k.borrow_mut() = Some(device));
            true
        }
        _ => false,
    }
}

/// Le code de verrouillage ouvre-t-il la clé de l'appareil ?
pub async fn unlock_code(code: &str) -> bool {
    unlock_with(|lock| {
        let salt = bytes(&get(lock, "salt"));
        let m = get(lock, "m").as_f64()? as u32;
        let t = get(lock, "t").as_f64()? as u32;
        if !(8..=1 << 20).contains(&m) || !(1..=10).contains(&t) {
            return None;
        }
        crypto::argon2id(code, &salt, m, t)
    })
    .await
}

/// La sortie PRF de la passkey ouvre-t-elle la clé de l'appareil ?
pub async fn unlock_passkey(prf: &[u8]) -> bool {
    unlock_with(|_| crypto::passkey_key(prf)).await
}

/// Change ce qui protège la clé de l'appareil (la mémoire doit être
/// ouverte). `false` si rien n'a pu être écrit.
pub async fn protect(new: NewLock<'_>) -> bool {
    let Some(device) = key() else { return false };
    let Ok(db) = key_db().await else { return false };
    let record = read_record(&db).await.unwrap_or(JsValue::UNDEFINED);
    if record.is_undefined() || record.is_null() {
        db.close();
        return false;
    }
    let wrap = get(&record, "wrap");
    let built: Result<(Vec<u8>, JsValue), JsValue> = (|| {
        let lock = Object::new();
        Ok(match new {
            NewLock::None => (device.clone(), JsValue::UNDEFINED),
            NewLock::Code(code) => {
                let salt = crypto::random_bytes(16);
                let user = crypto::argon2id(code, &salt, crypto::ARGON_M, crypto::ARGON_T)
                    .ok_or_else(|| JsValue::from_str("argon2"))?;
                Reflect::set(&lock, &"kind".into(), &"code".into())?;
                Reflect::set(
                    &lock,
                    &"salt".into(),
                    &Uint8Array::from(salt.as_slice()).into(),
                )?;
                Reflect::set(&lock, &"m".into(), &crypto::ARGON_M.into())?;
                Reflect::set(&lock, &"t".into(), &crypto::ARGON_T.into())?;
                let inner = crypto::lock_device_key(&user, &device)
                    .ok_or_else(|| JsValue::from_str("aes"))?;
                (inner, lock.into())
            }
            NewLock::Passkey { id, salt, prf } => {
                let user = crypto::passkey_key(&prf).ok_or_else(|| JsValue::from_str("prf"))?;
                Reflect::set(&lock, &"kind".into(), &"passkey".into())?;
                Reflect::set(&lock, &"id".into(), &Uint8Array::from(id.as_slice()).into())?;
                Reflect::set(
                    &lock,
                    &"salt".into(),
                    &Uint8Array::from(salt.as_slice()).into(),
                )?;
                let inner = crypto::lock_device_key(&user, &device)
                    .ok_or_else(|| JsValue::from_str("aes"))?;
                (inner, lock.into())
            }
        })
    })();
    let ok = match built {
        Ok((inner, lock)) => write_record(&db, &wrap, &inner, &lock, false)
            .await
            .unwrap_or(false),
        Err(_) => false,
    };
    db.close();
    ok
}

/// La clé de l'appareil : déjà ouverte (code, passkey), lue, ou tirée la
/// première fois. Elle est gardée chiffrée par une clé WebCrypto non
/// exportable ; protégée en plus, elle ne s'ouvre pas sans l'utilisateur.
async fn device_key() -> Result<Vec<u8>, JsValue> {
    if let Some(k) = key() {
        return Ok(k);
    }
    let db = key_db().await?;
    let record = read_record(&db).await?;
    if !record.is_undefined() && !record.is_null() {
        let inner = unwrap_inner(&record).await;
        db.close();
        let inner = inner?;
        if inner.len() != 32 {
            // Protégée par le code ou une passkey : `unlock_*` d'abord.
            return Err(JsValue::from_str("verrouillée"));
        }
        return Ok(inner);
    }
    // La première fois : une clé d'emballage non exportable, et la clé de
    // l'appareil chiffrée par elle.
    let alg = Object::new();
    Reflect::set(&alg, &"name".into(), &"AES-GCM".into())?;
    Reflect::set(&alg, &"length".into(), &256.into())?;
    let usages = Array::of2(&"encrypt".into(), &"decrypt".into());
    let wrap = await_promise(call(
        &subtle(),
        "generateKey",
        &[alg.into(), false.into(), usages.into()],
    ))
    .await?;
    let key = crypto::random_bytes(32);
    // `add` : si un autre onglet l'a écrite entre-temps, c'est la sienne.
    let out = if write_record(&db, &wrap, &key, &JsValue::UNDEFINED, true).await? {
        Ok(key)
    } else {
        let record = read_record(&db).await?;
        unwrap_inner(&record).await.and_then(|k| {
            if k.len() == 32 {
                Ok(k)
            } else {
                Err(JsValue::from_str("verrouillée"))
            }
        })
    };
    db.close();
    out
}

fn key() -> Option<Vec<u8>> {
    DEVICE_KEY.with(|k| k.borrow().clone())
}

/// Tout le contenu de la base de Memoire, déchiffré, pour la copie en
/// mémoire ; et les clés des valeurs encore en clair (d'avant le
/// chiffrement), à réécrire.
async fn load_all(
    db: &IdbDatabase,
    key: &[u8],
) -> Result<(BTreeMap<String, String>, Vec<String>), JsValue> {
    let store = db.transaction_with_str(KV)?.object_store(KV)?;
    let keys = wait(&store.get_all_keys()?).await?;
    let values = wait(&store.get_all()?).await?;
    let (keys, values) = (Array::from(&keys), Array::from(&values));
    let mut out = BTreeMap::new();
    let mut plain = Vec::new();
    for (k, v) in keys.iter().zip(values.iter()) {
        let Some(k) = k.as_string() else { continue };
        if let Some(v) = v.as_string() {
            plain.push(k.clone());
            out.insert(k, v);
        } else if v.is_instance_of::<Uint8Array>() {
            // Illisible (abîmée) : laissée telle quelle sur le disque.
            if let Some(v) = crypto::open_row(key, &k, &Uint8Array::new(&v).to_vec()) {
                out.insert(k, v);
            }
        }
    }
    Ok((out, plain))
}

/// Envoie vers IndexedDB les écritures en attente, dans l'ordre (une
/// transaction par lot ; IndexedDB les applique dans l'ordre de création).
async fn flush() {
    if FLUSHING.with(|f| f.replace(true)) {
        return;
    }
    loop {
        // Un lot est en cours : il partira entier, à sa fin.
        if HOLD.with(Cell::get) > 0 {
            break;
        }
        let batch: Vec<(String, Option<String>)> = {
            let mut q = QUEUE.lock().unwrap_or_else(|e| e.into_inner());
            std::mem::take(&mut *q)
        };
        if batch.is_empty() {
            break;
        }
        let Some(db) = IDB.with(|d| d.borrow().clone()) else {
            break;
        };
        let Ok(tx) = db.transaction_with_str_and_mode(KV, IdbTransactionMode::Readwrite) else {
            break;
        };
        let Ok(store) = tx.object_store(KV) else {
            break;
        };
        let done = done(&tx);
        let device = key();
        for (key, value) in batch {
            let k = JsValue::from_str(&key);
            let _ = match value {
                // Chiffrée ; en clair seulement si l'appareil n'a pas de clé.
                Some(v) => {
                    let sealed = device
                        .as_deref()
                        .and_then(|d| crypto::seal_row(d, &key, &v));
                    let v = match sealed {
                        Some(bytes) => Uint8Array::from(bytes.as_slice()).into(),
                        None => JsValue::from_str(&v),
                    };
                    store.put_with_key(&v, &k).map(drop)
                }
                None => store.delete(&k).map(drop),
            };
        }
        // Écrit pour de bon : la transaction est terminée (pas seulement
        // sa dernière requête).
        let _ = JsFuture::from(done).await;
    }
    FLUSHING.with(|f| f.set(false));
}

/// Attend que toutes les écritures soient dans IndexedDB.
async fn flush_now() {
    loop {
        flush().await;
        let empty = QUEUE.lock().map(|q| q.is_empty()).unwrap_or(true);
        if empty && !FLUSHING.with(Cell::get) {
            return;
        }
        crate::dom::sleep(20).await;
    }
}

fn enqueue(key: &str, value: Option<&str>) {
    if RELEASED.with(Cell::get) {
        return;
    }
    if let Ok(mut q) = QUEUE.lock() {
        q.push((key.to_owned(), value.map(str::to_owned)));
    }
    if !FLUSHING.with(Cell::get) && HOLD.with(Cell::get) == 0 {
        wasm_bindgen_futures::spawn_local(flush());
    }
}

/// Un lot d'écritures : tout ce qui est écrit tant qu'il vit part vers
/// IndexedDB dans la même transaction, à sa fin. Ne jamais attendre
/// [`committed`] pendant qu'un lot est ouvert.
pub struct Batch(());

pub fn batch() -> Batch {
    HOLD.with(|h| h.set(h.get() + 1));
    Batch(())
}

impl Drop for Batch {
    fn drop(&mut self) {
        let left = HOLD.with(|h| {
            h.set(h.get().saturating_sub(1));
            h.get()
        });
        let waiting = QUEUE.lock().map(|q| !q.is_empty()).unwrap_or(false);
        if left == 0 && waiting && !FLUSHING.with(Cell::get) {
            wasm_bindgen_futures::spawn_local(flush());
        }
    }
}

/// Attend que tout ce qui a été écrit jusqu'ici soit dans IndexedDB.
pub async fn committed() {
    flush_now().await;
}

/// Le stockage de Memoire : une copie en mémoire, gardée dans IndexedDB.
#[derive(Clone)]
struct IdbKv {
    cache: Arc<Mutex<BTreeMap<String, String>>>,
}

impl KeyValueStorage for IdbKv {
    fn scope(&self) -> String {
        "talka-indexeddb".into()
    }

    fn get(&self, key: &str) -> memoire_orm::Result<Option<String>> {
        Ok(self.cache.lock().ok().and_then(|c| c.get(key).cloned()))
    }

    fn set(&self, key: &str, value: &str) -> memoire_orm::Result<()> {
        if let Ok(mut c) = self.cache.lock() {
            c.insert(key.to_owned(), value.to_owned());
        }
        enqueue(key, Some(value));
        Ok(())
    }

    fn remove(&self, key: &str) -> memoire_orm::Result<()> {
        if let Ok(mut c) = self.cache.lock() {
            c.remove(key);
        }
        enqueue(key, None);
        Ok(())
    }

    fn keys(&self) -> memoire_orm::Result<Vec<String>> {
        Ok(self
            .cache
            .lock()
            .map(|c| c.keys().cloned().collect())
            .unwrap_or_default())
    }
}

fn rows() -> Option<Rows> {
    ROWS.with(|r| r.borrow().clone())
}

/// Ouvre la mémoire : lit IndexedDB, ouvre Memoire, reprend une fois les
/// données d'avant Memoire.
pub async fn open() -> Result<(), JsValue> {
    if rows().is_some() {
        return Ok(());
    }
    let idb = open_idb(NAME, |db| {
        if !db.object_store_names().contains(KV) {
            let _ = db.create_object_store(KV);
        }
    })
    .await?;
    let device = device_key().await?;
    let (cache, plain) = load_all(&idb, &device).await?;
    DEVICE_KEY.with(|k| *k.borrow_mut() = Some(device));
    IDB.with(|d| *d.borrow_mut() = Some(idb));
    // Les valeurs d'avant le chiffrement : réécrites chiffrées, une fois.
    for k in &plain {
        enqueue(k, cache.get(k).map(String::as_str));
    }
    let storage = IdbKv {
        cache: Arc::new(Mutex::new(cache)),
    };
    let db = Rows::open(storage)
        .await
        .map_err(|e| JsValue::from_str(&e.to_string()))?;
    if db.count().await == 0
        && let Some(old) = legacy_dump().await
    {
        db.restore(&old).await;
        flush_now().await;
        if let Ok(Some(factory)) = window().indexed_db() {
            let _ = factory.delete_database(LEGACY);
        }
    }
    ROWS.with(|r| *r.borrow_mut() = Some(db));
    Ok(())
}

/// Le contenu de la base d'avant Memoire (`None` : il n'y en a pas).
async fn legacy_dump() -> Option<Value> {
    let fresh = Rc::new(Cell::new(false));
    let f = fresh.clone();
    // Ouvrir une base qui n'existe pas la crée, vide : on le voit au
    // passage par `upgrade`, et on l'efface aussitôt.
    let db = open_idb(LEGACY, move |_| f.set(true)).await.ok()?;
    if fresh.get() || db.object_store_names().length() == 0 {
        db.close();
        if let Ok(Some(factory)) = window().indexed_db() {
            let _ = factory.delete_database(LEGACY);
        }
        return None;
    }
    let mut out = serde_json::Map::new();
    for name in STORES {
        let Ok(store) = db
            .transaction_with_str(name)
            .and_then(|t| t.object_store(name))
        else {
            continue;
        };
        let Ok(req) = store.get_all() else { continue };
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
    db.close();
    Some(Value::Object(out))
}

// ----- Ce que le reste de Talka utilise ------------------------------------------

/// Un autre onglet prend la main : envoie les dernières écritures vers
/// IndexedDB, puis n'écrit plus rien (voir `tabs`).
pub async fn release() {
    flush_now().await;
    RELEASED.with(|r| r.set(true));
}

pub async fn get_raw(store: &str, key: &str) -> Option<String> {
    rows()?.get(store, key).await
}

pub async fn read<T: DeserializeOwned>(store: &str, key: &str) -> Option<T> {
    serde_json::from_str(&get_raw(store, key).await?).ok()
}

pub async fn write<T: Serialize>(store: &str, key: &str, value: &T) {
    let (Some(db), Ok(json)) = (rows(), serde_json::to_string(value)) else {
        return;
    };
    db.put(store, key, json, "", 0.0).await;
}

/// Un message : rangé avec sa discussion et son heure, pour la requête
/// indexée qui les lit dans l'ordre.
pub async fn write_message(m: &crate::model::Message) {
    let (Some(db), Ok(json)) = (rows(), serde_json::to_string(m)) else {
        return;
    };
    db.put("messages", &m.key(), json, &m.chat, m.at).await;
}

pub async fn remove(store: &str, key: &str) {
    if let Some(db) = rows() {
        db.remove(store, key).await;
    }
}

pub async fn all<T: DeserializeOwned>(store: &str) -> Vec<T> {
    let Some(db) = rows() else { return Vec::new() };
    db.all(store)
        .await
        .iter()
        .filter_map(|j| serde_json::from_str(j).ok())
        .collect()
}

pub async fn clear(store: &str) {
    if let Some(db) = rows() {
        db.clear(store).await;
    }
}

/// Les messages d'une discussion, du plus ancien au plus récent ; `limit`
/// garde les derniers.
pub async fn messages_of(chat: &str, limit: usize) -> Vec<crate::model::Message> {
    let Some(db) = rows() else { return Vec::new() };
    db.messages_of(chat, limit)
        .await
        .iter()
        .filter_map(|j| serde_json::from_str(j).ok())
        .collect()
}

pub async fn delete_messages_of(chat: &str) {
    if let Some(db) = rows() {
        db.delete_messages_of(chat).await;
    }
}

/// Tout, pour la sauvegarde : `{ magasin: [[clé, json], …] }`.
pub async fn dump() -> Value {
    match rows() {
        Some(db) => db.dump().await,
        None => Value::Object(serde_json::Map::new()),
    }
}

pub async fn restore(data: &Value) {
    if let Some(db) = rows() {
        db.restore(data).await;
        flush_now().await;
    }
}

pub async fn wipe() {
    if let Some(db) = rows() {
        db.wipe().await;
        flush_now().await;
    }
    // La clé de l'appareil (et sa protection) part avec.
    DEVICE_KEY.with(|k| *k.borrow_mut() = None);
    if let Ok(Some(factory)) = window().indexed_db() {
        let _ = factory.delete_database(KEY_DB);
    }
}

/// Tout effacer sans pouvoir ouvrir la mémoire (passkey perdue) : les deux
/// bases, et ce que garde `localStorage`.
pub async fn forget_everything() {
    if let Ok(Some(factory)) = window().indexed_db() {
        for name in [NAME, KEY_DB] {
            if let Ok(req) = factory.delete_database(name) {
                let _ = wait(&req).await;
            }
        }
    }
    local_clear();
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
