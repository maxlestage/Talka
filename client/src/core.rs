//! Le cœur de Talka : contacts, discussions, messages, groupes, statuts.
//! Tout est rangé dans IndexedDB (`store`) ; le relais ne voit que des
//! enveloppes chiffrées (`crypto`, `net`).
//!
//! Un message part en autant d'enveloppes que de destinataires (une seule
//! dans une discussion à deux) : chacune est chiffrée pour son destinataire,
//! au moment de partir. En attendant, elle est gardée dans `outbox` : un
//! message écrit hors ligne part tout seul au retour du réseau.
//!
//! Avec un contact qui a publié son paquet de clés v3 (voir [`crate::v3`]),
//! l'enveloppe porte un jeton de sa session à double cliquet ; l'état de la
//! session est écrit avec le message qui l'a fait avancer, et rien ne part
//! vers le relais avant qu'il soit dans IndexedDB.

use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};

use serde_json::Value;

use crate::crypto::{self, Identity};
use crate::dom::{self, now, spawn};
use crate::jetons;
use crate::model::*;
use crate::net::{self, Ack, State};
use crate::store;
use crate::v3::{self, Engine, Failure, KeyBundle, Opened, Protection};

pub const DAY: f64 = 86_400_000.0;

/// Ce qui change, pour l'interface (des valeurs de passage : la taille des
/// variantes importe peu).
#[allow(clippy::large_enum_variant)]
pub enum Ev {
    Chats,
    Contacts(String),
    Messages(String),
    Presence(String),
    Typing(String),
    Net,
    Statuses,
    Calls,
    Profile,
    Settings,
    Incoming {
        chat: Chat,
        message: Message,
        active: bool,
    },
    Reaction {
        chat: Chat,
        from: String,
        emoji: String,
    },
    CallSignal {
        from: String,
        body: Body,
        live: bool,
    },
}

fn emit(e: Ev) {
    crate::ui::event(e);
}

#[derive(Default)]
struct St {
    me: Option<Identity>,
    profile: Profile,
    settings: Settings,
    contacts: HashMap<String, Contact>,
    chats: HashMap<String, Chat>,
    presence: HashMap<String, (bool, Option<f64>)>,
    typing: HashMap<String, HashMap<String, i32>>,
    active: Option<String>,
    brocolis: Vec<BrocolisKey>,
    /// Notre clé de notification (publique), donnée aux contacts.
    nk: String,
}

thread_local! {
    static ST: RefCell<St> = RefCell::new(St::default());
    static FLUSHING: Cell<bool> = const { Cell::new(false) };
    static FLUSH_AGAIN: Cell<bool> = const { Cell::new(false) };
    static SEQ: Cell<u32> = const { Cell::new(0) };
    /// Le dernier effacement des messages trop vieux (0 : au prochain passage).
    static LAST_PRUNE: Cell<f64> = const { Cell::new(0.0) };
    static TYPING_SENT: Cell<f64> = const { Cell::new(0.0) };
    static RECEIPTS: RefCell<HashMap<(String, String, String), HashSet<String>>> = RefCell::new(HashMap::new());
    static RECEIPT_TIMER: Cell<i32> = const { Cell::new(0) };
    /// L'appareil v3 et les sessions (chargées contact par contact).
    static V3: RefCell<Option<Engine>> = const { RefCell::new(None) };
    /// Les paquets v3 demandés au relais : (paquet vérifié ou aucun, heure).
    static BUNDLES: RefCell<HashMap<String, (Option<KeyBundle>, f64)>> = RefCell::new(HashMap::new());
    /// La dernière session réparée, contact par contact.
    static REPAIRS: RefCell<HashMap<String, f64>> = RefCell::new(HashMap::new());
}

fn with<T>(f: impl FnOnce(&mut St) -> T) -> T {
    ST.with(|s| f(&mut s.borrow_mut()))
}

// ----- Lecture de l'état -------------------------------------------------------

pub fn me() -> Identity {
    with(|s| s.me.clone()).expect("identité")
}

pub fn my_code() -> String {
    with(|s| s.me.as_ref().map(|m| m.code.clone()).unwrap_or_default())
}

pub fn profile() -> Profile {
    with(|s| s.profile.clone())
}

pub fn settings() -> Settings {
    with(|s| s.settings.clone())
}

pub fn contact(code: &str) -> Option<Contact> {
    with(|s| s.contacts.get(code).cloned())
}

pub fn contacts() -> Vec<Contact> {
    with(|s| s.contacts.values().cloned().collect())
}

pub fn chat(id: &str) -> Option<Chat> {
    with(|s| s.chats.get(id).cloned())
}

pub fn chats() -> Vec<Chat> {
    with(|s| s.chats.values().cloned().collect())
}

pub fn presence(code: &str) -> Option<(bool, Option<f64>)> {
    with(|s| s.presence.get(code).copied())
}

pub fn typing_in(chat: &str) -> Vec<String> {
    with(|s| {
        s.typing
            .get(chat)
            .map(|m| m.keys().cloned().collect())
            .unwrap_or_default()
    })
}

pub fn active_chat() -> Option<String> {
    with(|s| s.active.clone())
}

pub fn set_active(chat: Option<String>) {
    with(|s| s.active = chat);
}

pub fn is_group(id: &str) -> bool {
    id.starts_with("g:")
}

pub fn blocked(code: &str) -> bool {
    contact(code).is_some_and(|c| c.blocked)
}

pub fn display_name(code: &str) -> String {
    if code == my_code() {
        return "Vous".into();
    }
    match contact(code) {
        Some(c) if !c.name.is_empty() => c.name,
        Some(c) if !c.profile_name.is_empty() => format!("~{}", c.profile_name),
        _ => crypto::pretty(code),
    }
}

pub fn avatar_of(code: &str) -> String {
    if code == my_code() {
        return profile().avatar;
    }
    contact(code).map(|c| c.avatar).unwrap_or_default()
}

pub fn chat_title(chat: &Chat) -> String {
    if chat.group {
        if chat.name.is_empty() {
            "Groupe".into()
        } else {
            chat.name.clone()
        }
    } else {
        display_name(&chat.id)
    }
}

pub fn recipients_of(chat: &Chat) -> Vec<String> {
    if chat.group {
        let me = my_code();
        chat.members.iter().filter(|c| **c != me).cloned().collect()
    } else {
        vec![chat.id.clone()]
    }
}

// ----- Démarrage ---------------------------------------------------------------

pub async fn has_account() -> bool {
    store::read::<Identity>("kv", "identity")
        .await
        .is_some_and(|i| i.is_valid())
}

pub async fn create_account(name: &str, avatar: String) {
    let identity = Identity::create(now());
    store::write("kv", "identity", &identity).await;
    let profile = Profile {
        name: name.trim().chars().take(40).collect(),
        about: "Disponible sur Talka".into(),
        avatar,
        v: 1,
    };
    store::write("kv", "profile", &profile).await;
    store::persist().await;
}

pub async fn start() {
    let identity: Identity = store::read("kv", "identity").await.expect("identité");
    let profile: Profile = store::read("kv", "profile").await.unwrap_or_default();
    let settings: Settings = store::read("kv", "settings").await.unwrap_or_default();
    let contacts: Vec<Contact> = store::all("contacts").await;
    let chats: Vec<Chat> = store::all("chats").await;
    let brocolis = load_brocolis().await;
    let nk: String = store::read("kv", "nk").await.unwrap_or_default();
    let hidden = settings.hide_online;
    with(|s| {
        s.brocolis = brocolis;
        s.nk = nk;
        s.me = Some(identity.clone());
        s.profile = profile;
        s.settings = settings;
        s.contacts = contacts.into_iter().map(|c| (c.code.clone(), c)).collect();
        s.chats = chats.into_iter().map(|c| (c.id.clone(), c)).collect();
    });
    start_v3().await;
    net::start(identity, hidden);
    sweep().await;
    dom::interval(60_000, || spawn(sweep()));
}

/// Le relais annonce un changement d'état.
pub fn on_net(state: State) {
    emit(Ev::Net);
    if state == State::Online {
        watch_contacts();
        publish_v3();
        spawn(flush());
        spawn(refresh_missing_keys());
        if net::sealed_supported() {
            spawn(share_jeton());
        }
    }
}

fn watch_contacts() {
    let mut codes: HashSet<String> = with(|s| s.contacts.keys().cloned().collect());
    for c in chats() {
        if c.group {
            codes.extend(c.members.iter().cloned());
        } else {
            codes.insert(c.id.clone());
        }
    }
    codes.remove(&my_code());
    net::watch(&codes.into_iter().collect::<Vec<_>>());
}

pub fn on_presence(msg: &Value) {
    let code = msg["code"].as_str().unwrap_or("").to_owned();
    let online = msg["online"].as_bool().unwrap_or(false);
    let seen = msg["seen"].as_f64();
    with(|s| s.presence.insert(code.clone(), (online, seen)));
    emit(Ev::Presence(code));
}

/// Les messages éphémères échus et les statuts de plus de 24 heures.
/// Mettre en sourdine pour `secs` secondes (`None` : pour toujours), ou
/// réactiver le son (`Some(0)`).
pub async fn mute(chat_id: &str, secs: Option<u64>) {
    let until = secs.map_or(0.0, |s| now() + s as f64 * 1000.0);
    let on = secs != Some(0);
    update_chat(chat_id, |c| {
        c.muted = on;
        c.mute_until = if on { until } else { 0.0 };
    })
    .await;
}

/// « jusqu'à 18:30 », « jusqu'à demain 09:00 », « toujours ».
pub fn mute_label(c: &Chat) -> String {
    if !c.muted {
        return String::new();
    }
    if c.mute_until == 0.0 {
        return "Sourdine".into();
    }
    format!(
        "Sourdine jusqu'à {}{}",
        match dom::when_day(c.mute_until).as_str() {
            "aujourd'hui" => String::new(),
            d => format!("{d} "),
        },
        dom::time(c.mute_until)
    )
}

async fn sweep() {
    let t = now();
    // Les sourdines pour un temps qui arrivent à leur terme.
    for chat in chats() {
        if chat.muted && chat.mute_until > 0.0 && chat.mute_until <= t {
            update_chat(&chat.id, |c| {
                c.muted = false;
                c.mute_until = 0.0;
            })
            .await;
        }
    }
    for chat in chats() {
        if chat.timer == 0 && !chat.has_exp {
            continue;
        }
        let mut changed = false;
        for m in store::messages_of(&chat.id, usize::MAX).await {
            if m.exp.is_some_and(|e| e < t) {
                store::remove("messages", &m.key()).await;
                changed = true;
            }
        }
        if changed {
            refresh_last(&chat.id).await;
            emit(Ev::Messages(chat.id.clone()));
        }
    }
    // Les messages trop vieux (3 mois par défaut), une fois par heure.
    let keep = settings().keep_days;
    let due = LAST_PRUNE.with(|l| t - l.get() >= 3_600_000.0);
    if keep > 0 && due {
        LAST_PRUNE.with(|l| l.set(t));
        for chat in chats() {
            let mut changed = false;
            for m in store::messages_of(&chat.id, usize::MAX).await {
                if m.too_old(keep, t) {
                    store::remove("messages", &m.key()).await;
                    changed = true;
                }
            }
            if changed {
                refresh_last(&chat.id).await;
                emit(Ev::Messages(chat.id.clone()));
            }
        }
    }
    for s in store::all::<StatusItem>("statuses").await {
        if s.at + DAY < t {
            store::remove("statuses", &s.key()).await;
        }
    }
    emit(Ev::Statuses);
    // Les clés pré-signées v3 : renouvelées chaque semaine, puis republiées.
    if with_v3(|e| e.maintain(v3::secs(t))).unwrap_or(false) {
        save_v3().await;
        publish_v3();
    }
}

// ----- Le protocole v3 --------------------------------------------------------

fn with_v3<T>(f: impl FnOnce(&mut Engine) -> T) -> Option<T> {
    V3.with(|v| v.borrow_mut().as_mut().map(f))
}

/// L'appareil v3 : relu, ou créé au premier démarrage d'une version qui sait
/// faire v3.
async fn start_v3() {
    let device = store::get_raw("kv", v3::DEVICE_KEY).await;
    let engine = Engine::start(device.as_deref(), v3::secs(now()));
    V3.with(|v| *v.borrow_mut() = Some(engine));
    save_v3().await;
}

async fn write_json(key: &str, json: &str) {
    if let Ok(value) = serde_json::from_str::<Value>(json) {
        store::write("kv", key, &value).await;
    }
}

/// Écrit ce qui a changé dans l'état v3 (l'appareil, des sessions).
async fn save_v3() {
    let Some(dirty) = with_v3(Engine::take_dirty) else {
        return;
    };
    if let Some(device) = dirty.device {
        write_json(v3::DEVICE_KEY, &device).await;
    }
    for (code, json) in dirty.peers {
        let key = format!("{}{code}", v3::SESSION_PREFIX);
        if json.is_empty() {
            store::remove("kv", &key).await;
        } else {
            write_json(&key, &json).await;
        }
    }
}

/// Les sessions d'un contact, lues d'IndexedDB la première fois.
async fn load_v3(code: &str) {
    if with_v3(|e| e.is_loaded(code)).unwrap_or(true) {
        return;
    }
    let json = store::get_raw("kv", &format!("{}{code}", v3::SESSION_PREFIX)).await;
    with_v3(|e| e.load(code, json.as_deref()));
}

/// Publie le paquet de clés v3, signé par la clé du code (à chaque
/// connexion : le relais ne garde rien sur disque).
fn publish_v3() {
    if let Some((bundle, sig)) = with_v3(|e| e.published(&me())).flatten() {
        net::publish_bundle(&bundle, &sig);
    }
}

/// Le paquet v3 d'un contact, vérifié (lié à son code) ; gardé une heure en
/// mémoire (dix minutes s'il n'en a pas).
async fn bundle_for(peer: &Contact) -> Option<KeyBundle> {
    let t = now();
    if let Some((bundle, at)) = BUNDLES.with(|b| b.borrow().get(&peer.code).cloned()) {
        let ttl = if bundle.is_some() {
            3_600_000.0
        } else {
            600_000.0
        };
        if t - at < ttl {
            return bundle;
        }
    }
    // Pas de réponse (réseau, ancien relais) : rien n'est retenu.
    let found = net::fetch_bundle(&peer.code).await?;
    let bundle = (!found["none"].as_bool().unwrap_or(false))
        .then(|| {
            v3::verify_bundle(
                &peer.code,
                &peer.sign,
                &peer.dh,
                found["bundle"].as_str()?,
                found["sig"].as_str()?,
            )
        })
        .flatten();
    if bundle.is_none() && found["none"] != true {
        dom::warn("v3 : paquet de clés refusé (signature ou format)");
    }
    BUNDLES.with(|b| {
        b.borrow_mut()
            .insert(peer.code.clone(), (bundle.clone(), t))
    });
    bundle
}

/// La protection de la discussion avec un contact (fiche du contact).
pub async fn protection(code: &str) -> Protection {
    load_v3(code).await;
    if let Some(p) = with_v3(|e| e.session_protection(code)).flatten() {
        return p;
    }
    if let Some(peer) = keys_for(code).await
        && let Some(bundle) = bundle_for(&peer).await
    {
        return Protection::Ratchet {
            pq: bundle.pq.is_some(),
        };
    }
    Protection::Legacy
}

/// Une enveloppe v3 illisible (session perdue d'un côté ou de l'autre) :
/// on oublie nos sessions avec ce contact et on lui envoie notre profil, qui
/// ouvre une session neuve ; il la prend, et ses messages suivants se
/// lisent. Au plus une fois toutes les cinq minutes par contact.
fn repair_v3(code: &str) {
    let t = now();
    let recent = REPAIRS.with(|r| r.borrow().get(code).is_some_and(|at| t - at < 300_000.0));
    if recent {
        return;
    }
    REPAIRS.with(|r| r.borrow_mut().insert(code.to_owned(), t));
    with_v3(|e| e.forget(code));
    let code = code.to_owned();
    spawn(async move {
        save_v3().await;
        enqueue(&code, Wire::new(profile_body()), None, None, false).await;
    });
}

pub async fn save_settings(f: impl FnOnce(&mut Settings)) {
    let before = settings();
    let next = with(|s| {
        f(&mut s.settings);
        s.settings.clone()
    });
    store::write("kv", "settings", &next).await;
    if before.hide_online != next.hide_online {
        net::set_hidden(next.hide_online);
    }
    if before.keep_days != next.keep_days {
        LAST_PRUNE.with(|l| l.set(0.0));
        spawn(sweep());
    }
    emit(Ev::Settings);
}

pub async fn save_profile(f: impl FnOnce(&mut Profile)) {
    let p = with(|s| {
        f(&mut s.profile);
        s.profile.v += 1;
        s.profile.clone()
    });
    store::write("kv", "profile", &p).await;
    emit(Ev::Profile);
    for c in contacts() {
        if c.saved && !c.blocked {
            enqueue(&c.code, Wire::new(profile_body()), None, None, false).await;
        }
    }
    spawn(flush());
}

fn profile_body() -> Body {
    Body::Profile {
        profile: profile(),
        jeton: me().jeton(),
        nk: with(|s| s.nk.clone()),
    }
}

/// La clé de notification de ce navigateur (son service worker la tire) :
/// une nouvelle part chez les contacts, avec le profil, pour qu'ils
/// chiffrent l'aperçu des notifications. Vide : plus d'aperçu.
pub async fn set_nk(nk: String) {
    if with(|s| s.nk == nk) {
        return;
    }
    with(|s| s.nk = nk.clone());
    store::write("kv", "nk", &nk).await;
    for c in contacts() {
        if c.saved && !c.blocked {
            enqueue(&c.code, Wire::new(profile_body()), None, None, false).await;
        }
    }
    spawn(flush());
}

/// L'aperçu d'une notification pour le destinataire (voir
/// [`Wire::apercu`]) : le texte comme la liste des discussions.
pub fn apercu_of(me: &str, name: &str, wire: &Wire) -> Option<String> {
    wire.apercu(me, name, preview)
}

/// Notre jeton de remise, pour les contacts qui ne l'ont pas encore (une
/// fois) : ils pourront nous écrire sans que le relais sache qui écrit.
async fn share_jeton() {
    for mut c in contacts() {
        if c.saved && !c.blocked && !c.jeton_sent {
            c.jeton_sent = true;
            save_contact(c.clone()).await;
            enqueue(&c.code, Wire::new(profile_body()), None, None, false).await;
        }
    }
}

// ----- Contacts --------------------------------------------------------------

// La mémoire d'abord, IndexedDB ensuite : une lecture faite pendant
// l'écriture voit déjà la nouvelle valeur.
async fn save_contact(c: Contact) {
    let code = c.code.clone();
    with(|s| s.contacts.insert(code.clone(), c.clone()));
    store::write("contacts", &code, &c).await;
    emit(Ev::Contacts(code));
}

/// Les clés d'un code, vérifiées : le code doit être leur empreinte.
async fn keys_for(code: &str) -> Option<Contact> {
    if let Some(c) = contact(code).filter(|c| !c.sign.is_empty() && !c.dh.is_empty()) {
        return Some(c);
    }
    let found = net::lookup(code).await?;
    let (sign, dh) = (
        found["sign"].as_str()?.to_owned(),
        found["dh"].as_str()?.to_owned(),
    );
    if !crypto::verify_keys(code, &sign, &dh) {
        return None;
    }
    // Relu après l'attente : un nom donné entre-temps n'est pas écrasé.
    let mut c = contact(code).unwrap_or_else(|| Contact {
        code: code.to_owned(),
        added_at: now(),
        ..Default::default()
    });
    c.sign = sign;
    c.dh = dh;
    save_contact(c.clone()).await;
    Some(c)
}

async fn refresh_missing_keys() {
    for c in contacts() {
        if c.sign.is_empty() && keys_for(&c.code).await.is_some() {
            spawn(flush());
        }
    }
}

/// Ajoute un contact par son code ; `true` s'il est joignable tout de suite.
pub async fn add_contact(code: &str, name: &str) -> Result<bool, String> {
    if code == my_code() {
        return Err("C'est votre propre code 🙂".into());
    }
    let mut c = contact(code).unwrap_or_else(|| Contact {
        code: code.to_owned(),
        added_at: now(),
        ..Default::default()
    });
    c.saved = true;
    let name: String = name.trim().chars().take(40).collect();
    if !name.is_empty() {
        c.name = name;
    }
    save_contact(c).await;
    net::watch(&[code.to_owned()]);
    let reachable = keys_for(code).await.is_some();
    enqueue(code, Wire::new(profile_body()), None, None, false).await;
    spawn(flush());
    Ok(reachable)
}

pub async fn update_contact(code: &str, f: impl FnOnce(&mut Contact)) {
    if let Some(mut c) = contact(code) {
        f(&mut c);
        save_contact(c).await;
        emit(Ev::Chats);
    }
}

pub async fn set_blocked(code: &str, blocked: bool) {
    let mut c = contact(code).unwrap_or_else(|| Contact {
        code: code.to_owned(),
        added_at: now(),
        ..Default::default()
    });
    c.blocked = blocked;
    save_contact(c).await;
    emit(Ev::Chats);
}

// ----- Discussions -----------------------------------------------------------

async fn save_chat(chat: Chat) {
    with(|s| s.chats.insert(chat.id.clone(), chat.clone()));
    store::write("chats", &chat.id, &chat).await;
    emit(Ev::Chats);
}

pub async fn ensure_direct_chat(code: &str) -> Chat {
    if let Some(c) = chat(code) {
        return c;
    }
    let c = Chat {
        id: code.to_owned(),
        created_at: now(),
        last_at: now(),
        ..Default::default()
    };
    save_chat(c.clone()).await;
    net::watch(&[code.to_owned()]);
    c
}

pub async fn update_chat(id: &str, f: impl FnOnce(&mut Chat)) {
    if let Some(mut c) = chat(id) {
        f(&mut c);
        save_chat(c).await;
    }
}

// ----- Brocolis 🥦 ----------------------------------------------------------
//
// Les clés de l'app Brocolis, gardées dans `kv/brocolis` ; avec un code de
// verrouillage, chiffrées par lui dans `kv/brocolis_sealed` (PBKDF2 →
// AES-256-GCM) et ouvertes au déverrouillage. En attendant, les jetons
// reçus restent scellés, et s'ouvrent au déverrouillage.

/// La clé qui scelle `kv/brocolis_sealed` (tirée du code), et son sel.
#[derive(Clone)]
struct KeyLock {
    key: [u8; 32],
    salt: String,
}

thread_local! {
    static KEY_LOCK: RefCell<Option<KeyLock>> = const { RefCell::new(None) };
    /// Les clés sont scellées et pas encore ouvertes.
    static KEYS_LOCKED: Cell<bool> = const { Cell::new(false) };
}

const KEYS_LOCKED_MSG: &str = "Déverrouillez Talka pour utiliser vos clés Brocolis.";
/// Le texte qu'affiche une version de Talka qui ne connaît pas les clés partagées.
const SHARED_KEY_FALLBACK: &str = "🥦 Clé Brocolis partagée : mettez Talka à jour pour l'ajouter.";

async fn load_brocolis() -> Vec<BrocolisKey> {
    if store::read::<crypto::SealedKeys>("kv", "brocolis_sealed")
        .await
        .is_some()
    {
        KEYS_LOCKED.with(|l| l.set(true));
        return Vec::new();
    }
    store::read("kv", "brocolis").await.unwrap_or_default()
}

pub fn brocolis_keys() -> Vec<BrocolisKey> {
    with(|s| s.brocolis.clone())
}

/// Les clés qui chiffrent ce qui part dans `c` (celles qui existent encore,
/// et pas celles qu'on a renouvelées).
pub fn chat_brocolis(c: &Chat) -> Vec<BrocolisKey> {
    with(|s| {
        s.brocolis
            .iter()
            .filter(|k| !k.retired && c.brocolis.contains(&k.id))
            .cloned()
            .collect()
    })
}

fn raw_keys(keys: &[BrocolisKey]) -> Vec<jetons::Key> {
    keys.iter()
        .filter_map(|k| jetons::key_from_b64(&k.key))
        .collect()
}

fn seal_text(c: &Chat, text: &str) -> String {
    jetons::seal(text, &raw_keys(&chat_brocolis(c)))
}

/// Scelle le texte et la citation de `content` ; `true` si le texte l'a été.
/// Une clé partagée ne se scelle pas : elle voyage dans le chiffrement de Talka.
fn seal_content(c: &Chat, content: &mut Content) -> bool {
    if content.kind == "brokey" {
        return false;
    }
    let sealed = seal_text(c, &content.text);
    let bro = sealed != content.text;
    content.text = sealed;
    if let Some(r) = content.reply.as_mut() {
        r.text = seal_text(c, &r.text);
    }
    bro
}

/// Ouvre les jetons de `text` avec toutes vos clés.
pub fn open_brocolis(text: &str) -> jetons::Opened {
    jetons::open(text, &raw_keys(&brocolis_keys()))
}

/// Ouvre les jetons d'un message et de sa citation ; `true` si l'un l'a été.
fn open_message(m: &mut Message) -> bool {
    let mut changed = false;
    if jetons::has_token(&m.text) {
        let o = open_brocolis(&m.text);
        if o.opened > 0 {
            m.text = o.text;
            m.bro = true;
            changed = true;
        }
    }
    if let Some(r) = m.reply.as_mut()
        && jetons::has_token(&r.text)
    {
        let o = open_brocolis(&r.text);
        if o.opened > 0 {
            r.text = o.text;
            changed = true;
        }
    }
    changed
}

async fn save_brocolis() {
    let keys = brocolis_keys();
    let json = serde_json::to_string(&keys).unwrap_or_default();
    match KEY_LOCK.with(|l| l.borrow().clone()) {
        Some(lock) => {
            if let Some(sealed) = crypto::seal_keys(&lock.key, &lock.salt, &json) {
                store::write("kv", "brocolis_sealed", &sealed).await;
                store::remove("kv", "brocolis").await;
            }
        }
        None => {
            store::write("kv", "brocolis", &keys).await;
            store::remove("kv", "brocolis_sealed").await;
        }
    }
    emit(Ev::Settings);
}

/// Au déverrouillage : ouvre les clés scellées par le code (et scelle celles
/// qui ne l'étaient pas encore), puis les messages qu'elles déverrouillent.
pub async fn unlock_brocolis(pin: &str) {
    if let Some(sealed) = store::read::<crypto::SealedKeys>("kv", "brocolis_sealed").await {
        let Some(salt) = crypto::unb64(&sealed.salt) else {
            return;
        };
        let key = crypto::pin_key(pin, &salt);
        let Some(keys) = crypto::open_keys(&key, &sealed)
            .and_then(|json| serde_json::from_str::<Vec<BrocolisKey>>(&json).ok())
        else {
            return;
        };
        KEY_LOCK.with(|l| {
            *l.borrow_mut() = Some(KeyLock {
                key,
                salt: sealed.salt,
            })
        });
        KEYS_LOCKED.with(|l| l.set(false));
        with(|s| s.brocolis = keys);
        emit(Ev::Settings);
        reopen_messages().await;
    } else if KEY_LOCK.with(|l| l.borrow().is_none()) {
        protect_brocolis(Some(pin)).await;
    }
}

/// Le code de verrouillage change : les clés sont scellées par le nouveau
/// (ou gardées sans code, s'il est retiré).
pub async fn protect_brocolis(pin: Option<&str>) {
    if KEYS_LOCKED.with(Cell::get) {
        return;
    }
    let lock = pin.map(|pin| {
        let salt = crypto::random_bytes(16);
        KeyLock {
            key: crypto::pin_key(pin, &salt),
            salt: crypto::b64(&salt),
        }
    });
    KEY_LOCK.with(|l| *l.borrow_mut() = lock);
    save_brocolis().await;
}

/// Les clés en clair, pour la sauvegarde (elle-même chiffrée par son mot de
/// passe) : elle s'ouvre ainsi sur un autre appareil, iPhone compris.
pub fn brocolis_for_backup() -> Option<Value> {
    if KEYS_LOCKED.with(Cell::get) {
        return None;
    }
    let json = serde_json::to_string(&brocolis_keys()).ok()?;
    Some(Value::Array(vec![
        Value::String("brocolis".into()),
        Value::String(json),
    ]))
}

fn check_unlocked() -> Result<(), &'static str> {
    if KEYS_LOCKED.with(Cell::get) {
        Err(KEYS_LOCKED_MSG)
    } else {
        Ok(())
    }
}

/// Ajoute une clé (collée seule, ou dans l'invitation de l'app Brocolis),
/// puis ouvre les messages déjà reçus qu'elle déverrouille. Une clé déjà là
/// est renvoyée telle quelle.
pub async fn add_brocolis_key(name: &str, pasted: &str) -> Result<BrocolisKey, &'static str> {
    check_unlocked()?;
    let key =
        jetons::find_key(pasted).ok_or("Ce n'est pas une clé Brocolis (32 octets en base64).")?;
    if let Some(k) = brocolis_keys().into_iter().find(|k| k.key == key) {
        return Ok(k);
    }
    let n = brocolis_keys().len() + 1;
    let name = name.trim();
    let k = BrocolisKey {
        id: crypto::random_id(6),
        name: if name.is_empty() {
            format!("Clé {n}")
        } else {
            name.chars().take(40).collect()
        },
        key,
        retired: false,
    };
    with(|s| s.brocolis.push(k.clone()));
    save_brocolis().await;
    reopen_messages().await;
    Ok(k)
}

pub async fn rename_brocolis_key(id: &str, name: &str) {
    let name: String = name.trim().chars().take(40).collect();
    if name.is_empty() || check_unlocked().is_err() {
        return;
    }
    with(|s| {
        if let Some(k) = s.brocolis.iter_mut().find(|k| k.id == id) {
            k.name = name;
        }
    });
    save_brocolis().await;
}

/// Oublie une clé : les discussions qu'elle chiffrait n'en ont plus besoin.
/// Les messages déjà ouverts le restent.
pub async fn remove_brocolis_key(id: &str) {
    if check_unlocked().is_err() {
        return;
    }
    with(|s| s.brocolis.retain(|k| k.id != id));
    save_brocolis().await;
    for c in chats()
        .into_iter()
        .filter(|c| c.brocolis.iter().any(|x| x == id))
    {
        update_chat(&c.id, |c| c.brocolis.retain(|x| x != id)).await;
    }
}

/// Renouvelle une clé : une neuve, du même nom, la remplace dans toutes ses
/// discussions et y est envoyée par Talka ; l'ancienne garde l'historique
/// lisible mais ne chiffre plus rien. Renvoie le nombre de discussions.
pub async fn renew_brocolis_key(id: &str) -> Result<usize, &'static str> {
    check_unlocked()?;
    let old = brocolis_keys()
        .into_iter()
        .find(|k| k.id == id)
        .ok_or("Clé introuvable.")?;
    let fresh = BrocolisKey {
        id: crypto::random_id(6),
        name: old.name.clone(),
        key: jetons::generate_key(),
        retired: false,
    };
    with(|s| {
        if let Some(k) = s.brocolis.iter_mut().find(|k| k.id == id) {
            k.retired = true;
            k.name = format!("{} (ancienne)", old.name);
        }
        s.brocolis.push(fresh.clone());
    });
    save_brocolis().await;
    let using: Vec<Chat> = chats()
        .into_iter()
        .filter(|c| c.brocolis.iter().any(|x| x == id))
        .collect();
    for c in &using {
        let fresh_id = fresh.id.clone();
        update_chat(&c.id, |c| {
            for x in c.brocolis.iter_mut().filter(|x| *x == id) {
                *x = fresh_id.clone();
            }
        })
        .await;
        share_brocolis_key(&c.id, &fresh.id, Some(&old)).await;
    }
    Ok(using.len())
}

/// Envoie une clé dans une discussion, par le chiffrement de bout en bout de
/// Talka : plus besoin de la copier-coller ailleurs.
///
/// Sur le fil, `data` porte la clé et `code` l'empreinte de celle qu'elle
/// remplace (un renouvellement). L'appareil, lui, ne garde jamais la clé
/// dans ses messages une fois qu'elle est dans ses clés (scellées par le
/// code) : `data` est vidé et `code` désigne la clé (son `id`).
pub async fn share_brocolis_key(
    chat_id: &str,
    key_id: &str,
    replaces: Option<&BrocolisKey>,
) -> Option<Message> {
    let k = brocolis_keys().into_iter().find(|k| k.id == key_id)?;
    let mut m = send_message(
        chat_id,
        Content {
            kind: "brokey".into(),
            text: SHARED_KEY_FALLBACK.into(),
            data: k.key,
            name: k.name,
            code: replaces
                .and_then(|r| jetons::fingerprint(&r.key))
                .unwrap_or_default(),
            ..Default::default()
        },
    )
    .await?;
    forget_shared_key(&mut m, &k.id).await;
    Some(m)
}

async fn forget_shared_key(m: &mut Message, key_id: &str) {
    m.data.clear();
    m.code = key_id.to_owned();
    store::write_message(m).await;
    emit(Ev::Messages(m.chat.clone()));
}

/// La clé d'une bulle « clé partagée », si elle est dans vos clés.
pub fn shared_key_of(m: &Message) -> Option<BrocolisKey> {
    brocolis_keys().into_iter().find(|k| {
        if m.data.is_empty() {
            k.id == m.code
        } else {
            k.key == m.data
        }
    })
}

/// Ajoute une clé reçue dans une discussion et l'y active ; si elle en
/// renouvelle une, l'ancienne n'y chiffre plus (elle garde l'historique).
pub async fn accept_brocolis_key(chat_id: &str, m: &Message) -> Result<BrocolisKey, &'static str> {
    if m.data.is_empty() {
        return shared_key_of(m).ok_or("Cette clé a été oubliée.");
    }
    let k = add_brocolis_key(&m.name, &m.data).await?;
    let old = (!m.code.is_empty())
        .then(|| {
            brocolis_keys()
                .into_iter()
                .find(|o| o.id != k.id && jetons::fingerprint(&o.key).as_deref() == Some(&m.code))
        })
        .flatten();
    if let Some(old) = &old {
        with(|s| {
            if let Some(o) = s.brocolis.iter_mut().find(|o| o.id == old.id)
                && !o.retired
            {
                o.retired = true;
                o.name = format!("{} (ancienne)", o.name);
            }
        });
        save_brocolis().await;
    }
    if let Some(c) = chat(chat_id) {
        let mut ids: Vec<String> = c
            .brocolis
            .iter()
            .filter(|x| old.as_ref().is_none_or(|o| &o.id != *x))
            .cloned()
            .collect();
        if !ids.contains(&k.id) {
            ids.push(k.id.clone());
        }
        set_chat_brocolis(chat_id, ids).await;
    }
    if let Some(mut stored) = get_message(chat_id, &m.id).await {
        forget_shared_key(&mut stored, &k.id).await;
    }
    Ok(k)
}

/// Les clés qui chiffrent ce qui part dans une discussion (vide : aucune).
pub async fn set_chat_brocolis(chat_id: &str, ids: Vec<String>) {
    update_chat(chat_id, |c| c.brocolis = ids).await;
    emit(Ev::Messages(chat_id.to_owned()));
}

/// Les messages gardés avec des jetons qu'une nouvelle clé peut ouvrir.
async fn reopen_messages() {
    for c in chats() {
        let mut any = false;
        for mut m in store::messages_of(&c.id, usize::MAX).await {
            if open_message(&mut m) {
                store::write_message(&m).await;
                any = true;
            }
        }
        if any {
            refresh_last(&c.id).await;
            emit(Ev::Messages(c.id.clone()));
        }
    }
}

pub async fn delete_chat(id: &str) {
    store::delete_messages_of(id).await;
    store::remove("chats", id).await;
    with(|s| s.chats.remove(id));
    emit(Ev::Chats);
}

pub async fn clear_chat(id: &str) {
    store::delete_messages_of(id).await;
    update_chat(id, |c| {
        c.last = None;
        c.unread = 0;
    })
    .await;
    emit(Ev::Messages(id.to_owned()));
}

/// La recherche dans toutes les discussions : les messages qui contiennent
/// `q` (déjà replié par `fold`), les plus récents d'abord, `limit` au plus.
/// Ni messages système ou supprimés, ni capsule scellée, ni discussion bloquée.
pub async fn search_messages(q: &str, limit: usize) -> Vec<Message> {
    if q.chars().count() < 2 {
        return Vec::new();
    }
    let (me, t) = (my_code(), now());
    let mut found = Vec::new();
    for chat in chats() {
        if !chat.group && blocked(&chat.id) {
            continue;
        }
        for m in store::messages_of(&chat.id, usize::MAX).await {
            if m.kind != "system"
                && !m.deleted
                && !m.sealed_for(&me, t)
                && crate::model::fold(&format!("{} {}", m.text, m.name)).contains(q)
            {
                found.push(m);
            }
        }
    }
    found.sort_by(|a, b| b.at.total_cmp(&a.at));
    found.truncate(limit);
    found
}

/// Ce qu'on lit d'un message sur une ligne (épingle, favoris) : son texte,
/// sauf s'il est supprimé ou si c'est une capsule encore scellée pour soi ;
/// sinon son aperçu (« ⏳ Capsule temporelle »…). Comme TalkaKit.
pub fn readable(m: &Message) -> String {
    if m.kind == "text" && !m.deleted && !m.sealed_for(&my_code(), now()) {
        m.text.clone()
    } else {
        preview(m)
    }
}

/// Une discussion en texte, comme WhatsApp (« Exporter la discussion ») :
/// (nom du fichier, texte). Ce qu'on lit dans la discussion, rien d'autre :
/// une capsule scellée reste scellée, une photo devient « 📷 Photo ».
pub async fn export_chat(chat_id: &str) -> Option<(String, String)> {
    let c = chat(chat_id)?;
    let title = chat_title(&c);
    let t = now();
    let mine = profile().name;
    let mut out = format!(
        "Discussion Talka « {title} », exportée le {}.\nChiffrée de bout en bout dans Talka ; ce fichier, lui, ne l'est pas.\n\n",
        dom::stamp(t)
    );
    for m in store::messages_of(chat_id, usize::MAX).await {
        if m.exp.is_some_and(|e| e <= t) {
            continue;
        }
        let author = if m.kind == "system" || m.from == "system" {
            None
        } else if m.from == my_code() {
            Some(if mine.is_empty() {
                "Vous".to_owned()
            } else {
                mine.clone()
            })
        } else {
            Some(display_name(&m.from))
        };
        out.push_str(&export_line(
            &dom::stamp(m.at),
            author.as_deref(),
            &readable(&m),
        ));
        out.push('\n');
    }
    Some((export_filename(&title), out))
}

/// Une capsule temporelle s'ouvre au plus tard dans dix ans.
pub const CAPSULE_MAX_MS: f64 = 10.0 * 366.0 * DAY;

pub fn preview(m: &Message) -> String {
    if m.deleted {
        return "🚫 Message supprimé".into();
    }
    // Ni l'aperçu de la liste, ni la notification ne trahissent une capsule.
    if m.is_capsule() {
        return "⏳ Capsule temporelle".into();
    }
    match m.kind.as_str() {
        "image" if m.text.is_empty() => "📷 Photo".into(),
        "image" => format!("📷 {}", jetons::masked(&m.text)),
        "voice" => format!("🎙️ Message vocal ({})", dom::fmt_dur(m.dur)),
        "file" => format!(
            "📄 {}",
            if m.name.is_empty() {
                "Fichier"
            } else {
                &m.name
            }
        ),
        "contact" => format!(
            "👤 {}",
            if m.name.is_empty() {
                "Contact"
            } else {
                &m.name
            }
        ),
        "brokey" => format!("🥦 Clé Brocolis « {} »", m.name),
        _ => jetons::masked(&m.text).into_owned(),
    }
}

fn last_of(m: &Message) -> Last {
    Last {
        id: m.id.clone(),
        from: m.from.clone(),
        text: preview(m),
        at: m.at,
        kind: m.kind.clone(),
    }
}

async fn refresh_last(chat_id: &str) {
    let last = store::messages_of(chat_id, 1).await.pop();
    update_chat(chat_id, |c| c.last = last.as_ref().map(last_of)).await;
}

async fn store_message(m: &Message, bump: bool) {
    store::write_message(m).await;
    if bump && let Some(mut c) = chat(&m.chat) {
        c.last = Some(last_of(m));
        c.last_at = c.last_at.max(m.at);
        if c.archived && m.from != my_code() && !c.muted {
            c.archived = false;
        }
        if m.exp.is_some() {
            c.has_exp = true;
        }
        save_chat(c).await;
    }
    emit(Ev::Messages(m.chat.clone()));
}

async fn get_message(chat_id: &str, id: &str) -> Option<Message> {
    store::read("messages", &format!("{chat_id}|{id}")).await
}

pub async fn messages(chat_id: &str, limit: usize) -> Vec<Message> {
    store::messages_of(chat_id, limit).await
}

// ----- Envoi ----------------------------------------------------------------

/// Une enveloppe à envoyer à `to` : gardée jusqu'à ce que le relais confirme.
/// `live` : seulement si le destinataire est en ligne, sans garde.
async fn enqueue(to: &str, wire: Wire, chat: Option<&str>, msg_id: Option<&str>, live: bool) {
    let Ok(payload) = serde_json::to_string(&wire) else {
        return;
    };
    let seq = SEQ.with(|s| {
        s.set(s.get().wrapping_add(1));
        s.get()
    });
    let item = OutItem {
        id: format!(
            "{}:{to}",
            msg_id
                .map(str::to_owned)
                .unwrap_or_else(|| crypto::random_id(10))
        ),
        to: to.to_owned(),
        payload,
        chat: chat.unwrap_or_default().to_owned(),
        msg_id: msg_id.unwrap_or_default().to_owned(),
        seq: now() * 1000.0 + (seq % 1000) as f64,
        boot: String::new(),
    };
    if live {
        let _ = send_now(&item, true).await;
        return;
    }
    store::write("outbox", &item.id, &item).await;
    spawn(flush());
}

async fn send_now(item: &OutItem, live: bool) -> Result<(), String> {
    let peer = keys_for(&item.to).await.ok_or("unknown")?;
    load_v3(&peer.code).await;
    // « écrit… » et les signaux d'appel ne sont remis qu'à un destinataire
    // en ligne : hors ligne, le relais les jette, et chacun laisserait chez
    // lui une clé sautée dans la session. Ils gardent alors l'ancien format.
    let ratchet = !live || presence(&peer.code).is_some_and(|(online, _)| online);
    // Sans session, le paquet du contact en ouvre une (s'il en a publié un).
    let bundle = if !ratchet || with_v3(|e| e.has_session(&peer.code)).unwrap_or(true) {
        None
    } else {
        bundle_for(&peer).await
    };
    // Chiffré au moment de partir : un nouvel essai chiffre à nouveau (un
    // autre numéro de message) ; le destinataire range par identifiant.
    let v3 = ratchet
        .then(|| with_v3(|e| e.seal(&me(), &peer.code, &peer.dh, bundle.as_ref(), &item.payload)))
        .flatten();
    let sealed = match v3 {
        Some(sealed) => sealed,
        None => crypto::seal(&me(), &peer.code, &peer.dh, &item.payload)
            .map(|body| v3::Sealed { body, v3: false }),
    }
    .ok_or("crypto")?;
    if sealed.v3 {
        // La session a avancé : rien ne part avant qu'elle soit écrite (une
        // clé de message ne doit jamais servir deux fois).
        save_v3().await;
        store::committed().await;
    }
    let body = sealed.body;
    // Les messages écrits (pas les accusés, réactions, profils) réveillent
    // un destinataire hors ligne.
    let wake = !live && !item.msg_id.is_empty();
    // Et, s'il a donné sa clé de notification, avec un aperçu chiffré pour
    // elle : le relais le transmet sans pouvoir le lire.
    let apercu = (wake && !peer.nk.is_empty())
        .then(|| serde_json::from_str::<Wire>(&item.payload).ok())
        .flatten()
        .and_then(|w| apercu_of(&my_code(), &profile().name, &w))
        .and_then(|a| crypto::seal_apercu(&me(), &peer.nk, &a));
    // L'expéditeur scellé : le relais ne sait pas qui écrit (une connexion
    // anonyme, et le jeton de remise du destinataire).
    if !peer.jeton.is_empty() && net::sealed_supported() {
        let outer = crypto::seal_sender(&me().code, &peer.code, &peer.dh, &body).ok_or("crypto")?;
        match net::send_sealed(&item.id, &peer, &outer, live, wake, apercu.as_deref()).await {
            Ack::Ok => return Ok(()),
            // Son jeton ne vaut plus : on l'oublie, et cet envoi part comme avant.
            Ack::Err(e) if e == "jeton" => {
                update_contact(&peer.code, |c| c.jeton.clear()).await;
            }
            // Pas de repli silencieux : l'envoi réessaiera, toujours scellé.
            Ack::Err(e) => return Err(e),
        }
    }
    match net::send(&item.id, &item.to, &body, live, wake, apercu.as_deref()).await {
        Ack::Ok => Ok(()),
        Ack::Err(e) => Err(e),
    }
}

/// Envoie ce qui attend dans la boîte d'envoi.
pub async fn flush() {
    if net::state() != State::Online {
        return;
    }
    if FLUSHING.with(|f| f.replace(true)) {
        FLUSH_AGAIN.with(|f| f.set(true));
        return;
    }
    loop {
        FLUSH_AGAIN.with(|f| f.set(false));
        let mut items: Vec<OutItem> = store::all("outbox").await;
        items.sort_by(|a, b| a.seq.total_cmp(&b.seq));
        let mut stop = false;
        let boot = net::boot();
        for mut item in items {
            if net::state() != State::Online {
                stop = true;
                break;
            }
            // Déjà accepté par le relais : on attend l'accusé de réception,
            // sauf si le relais a redémarré depuis (sa boîte aux lettres,
            // en mémoire, est perdue) : alors on renvoie.
            if !item.boot.is_empty() {
                if !still_unconfirmed(&item).await {
                    store::remove("outbox", &item.id).await;
                    continue;
                }
                if item.boot == boot || boot.is_empty() {
                    continue;
                }
            }
            match send_now(&item, false).await {
                Ok(()) => {
                    if !item.msg_id.is_empty() && !boot.is_empty() {
                        item.boot = boot.clone();
                        store::write("outbox", &item.id, &item).await;
                    } else {
                        store::remove("outbox", &item.id).await;
                    }
                    if !item.msg_id.is_empty() {
                        mark_sent(&item.chat, &item.msg_id, &item.to).await;
                    }
                }
                Err(e) if e == "size" || e == "code" => {
                    store::remove("outbox", &item.id).await;
                    if !item.msg_id.is_empty() {
                        mark_failed(&item.chat, &item.msg_id).await;
                        if e == "size" {
                            dom::toast("Message trop lourd : il n'a pas pu partir.");
                        }
                    }
                }
                // Sans réseau : on reprendra. « unknown » : le destinataire
                // n'a pas rouvert Talka depuis le redémarrage du relais.
                Err(e) if e == "offline" || e == "timeout" => {
                    stop = true;
                    break;
                }
                Err(_) => {}
            }
        }
        if stop || !FLUSH_AGAIN.with(Cell::get) {
            break;
        }
    }
    FLUSHING.with(|f| f.set(false));
}

/// Un message accepté par le relais attend-il encore l'accusé de réception
/// de ce destinataire ? Au-delà de 30 jours (la durée des boîtes aux
/// lettres), on n'insiste plus.
async fn still_unconfirmed(item: &OutItem) -> bool {
    get_message(&item.chat, &item.msg_id)
        .await
        .is_some_and(|m| {
            !m.deleted
                && !m.delivered_to.iter().any(|c| c == &item.to)
                && now() - m.at < 30.0 * 24.0 * 3600.0 * 1000.0
        })
}

async fn mark_sent(chat_id: &str, msg_id: &str, to: &str) {
    if let Some(mut m) = get_message(chat_id, msg_id).await {
        if !m.sent_to.iter().any(|c| c == to) {
            m.sent_to.push(to.to_owned());
        }
        if m.tick() != "pending" && m.status == "pending" {
            m.status = "sent".into();
        }
        store::write_message(&m).await;
        emit(Ev::Messages(chat_id.to_owned()));
    }
}

async fn mark_failed(chat_id: &str, msg_id: &str) {
    if let Some(mut m) = get_message(chat_id, msg_id).await {
        m.status = "failed".into();
        store::write_message(&m).await;
        emit(Ev::Messages(chat_id.to_owned()));
    }
}

fn group_info(chat: &Chat) -> GroupInfo {
    GroupInfo {
        id: chat.id.trim_start_matches("g:").to_owned(),
        name: chat.name.clone(),
        members: chat.members.clone(),
        admins: chat.admins.clone(),
        avatar: chat.avatar.clone(),
        v: chat.v.max(1),
    }
}

/// Ce qui accompagne chaque envoi dans une discussion : le groupe, la durée
/// de vie des messages éphémères.
fn envelope(chat: &Chat, body: Body) -> Wire {
    let mut w = Wire::new(body);
    if chat.group {
        w.grp = Some(group_info(chat));
    }
    if chat.timer > 0 {
        w.ttl = Some(chat.timer);
    }
    w
}

/// Envoie un message dans une discussion.
pub async fn send_message(chat_id: &str, mut content: Content) -> Option<Message> {
    let c = chat(chat_id)?;
    // Brocolis 🥦 : le texte part scellé ; l'appareil garde le clair.
    let plain = content.clone();
    let bro = seal_content(&c, &mut content);
    let id = crypto::random_id(12);
    let at = net::server_now();
    let to: Vec<String> = recipients_of(&c)
        .into_iter()
        .filter(|x| !blocked(x))
        .collect();
    let mut m = Message {
        chat: chat_id.to_owned(),
        id: id.clone(),
        from: my_code(),
        at,
        status: if to.is_empty() {
            "sent".into()
        } else {
            "pending".into()
        },
        to: to.clone(),
        kind: plain.kind,
        text: plain.text,
        data: plain.data,
        name: plain.name,
        mime: plain.mime,
        size: plain.size,
        dur: plain.dur,
        w: plain.w,
        h: plain.h,
        code: plain.code,
        reply: plain.reply,
        fwd: plain.fwd,
        open_at: plain.open_at,
        bro,
        ..Default::default()
    };
    if c.timer > 0 {
        m.exp = Some(at + c.timer as f64 * 1000.0);
    }
    // Un jeton collé (clavier Brocolis) s'affiche ouvert, s'il est pour vous.
    open_message(&mut m);
    store_message(&m, true).await;
    // Le nom choisi voyage avec le message : un membre de groupe qui ne vous
    // connaît pas voit « ~Alice » plutôt qu'un code.
    let wire = envelope(
        &c,
        Body::Msg {
            id: id.clone(),
            at,
            pn: profile().name,
            content,
        },
    );
    for code in &to {
        enqueue(code, wire.clone(), Some(chat_id), Some(&id), false).await;
    }
    if chat(chat_id).is_some_and(|c| !c.draft.is_empty()) {
        update_chat(chat_id, |c| c.draft.clear()).await;
    }
    Some(m)
}

/// Une action sur la discussion (réaction, modification…), pour tous ses membres.
async fn broadcast(c: &Chat, body: Body, live: bool) {
    let wire = envelope(c, body);
    for code in recipients_of(c) {
        if !blocked(&code) {
            enqueue(&code, wire.clone(), None, None, live).await;
        }
    }
}

pub async fn react(chat_id: &str, msg_id: &str, emoji: &str) {
    let (Some(c), Some(mut m)) = (chat(chat_id), get_message(chat_id, msg_id).await) else {
        return;
    };
    let me = my_code();
    let emoji = if m.reactions.get(&me).map(String::as_str) == Some(emoji) {
        ""
    } else {
        emoji
    };
    if emoji.is_empty() {
        m.reactions.remove(&me);
    } else {
        m.reactions.insert(me, emoji.to_owned());
    }
    store::write_message(&m).await;
    emit(Ev::Messages(chat_id.to_owned()));
    broadcast(
        &c,
        Body::React {
            id: msg_id.to_owned(),
            emoji: emoji.to_owned(),
        },
        false,
    )
    .await;
}

pub async fn edit_message(chat_id: &str, msg_id: &str, text: &str) {
    let (Some(c), Some(mut m)) = (chat(chat_id), get_message(chat_id, msg_id).await) else {
        return;
    };
    if m.from != my_code() {
        return;
    }
    m.text = text.to_owned();
    m.edited = true;
    let sealed = seal_text(&c, text);
    m.bro = sealed != text;
    store::write_message(&m).await;
    refresh_last(chat_id).await;
    emit(Ev::Messages(chat_id.to_owned()));
    broadcast(
        &c,
        Body::Edit {
            id: msg_id.to_owned(),
            text: sealed,
        },
        false,
    )
    .await;
}

pub async fn delete_message(chat_id: &str, msg_id: &str, for_everyone: bool) {
    let (Some(c), Some(mut m)) = (chat(chat_id), get_message(chat_id, msg_id).await) else {
        return;
    };
    if for_everyone && m.from == my_code() {
        m.deleted = true;
        m.text.clear();
        m.data.clear();
        m.reactions.clear();
        store::write_message(&m).await;
        broadcast(
            &c,
            Body::Delete {
                id: msg_id.to_owned(),
            },
            false,
        )
        .await;
    } else {
        store::remove("messages", &m.key()).await;
    }
    refresh_last(chat_id).await;
    emit(Ev::Messages(chat_id.to_owned()));
}

pub async fn toggle_star(chat_id: &str, msg_id: &str) {
    if let Some(mut m) = get_message(chat_id, msg_id).await {
        m.starred = !m.starred;
        store::write_message(&m).await;
        emit(Ev::Messages(chat_id.to_owned()));
    }
}

/// Épingle un message en haut de sa discussion (ou retire l'épingle).
pub async fn set_pin(chat_id: &str, msg_id: &str) {
    let id = msg_id.to_owned();
    update_chat(chat_id, move |c| c.pin_msg = id).await;
    emit(Ev::Messages(chat_id.to_owned()));
}

/// Le message épinglé d'une discussion, s'il existe encore.
pub async fn pinned_message(chat_id: &str) -> Option<Message> {
    let id = chat(chat_id)?.pin_msg;
    if id.is_empty() {
        return None;
    }
    let t = now();
    get_message(chat_id, &id)
        .await
        .filter(|m| !m.deleted && m.exp.is_none_or(|e| e > t))
}

pub async fn starred() -> Vec<Message> {
    let mut list: Vec<Message> = store::all::<Message>("messages")
        .await
        .into_iter()
        .filter(|m| m.starred)
        .collect();
    list.sort_by(|a, b| b.at.total_cmp(&a.at));
    list
}

pub async fn forward(m: &Message, chat_ids: &[String]) {
    let content = Content {
        kind: m.kind.clone(),
        text: m.text.clone(),
        data: m.data.clone(),
        name: m.name.clone(),
        mime: m.mime.clone(),
        size: m.size,
        dur: m.dur,
        w: m.w,
        h: m.h,
        code: m.code.clone(),
        reply: None,
        fwd: true,
        open_at: 0.0,
    };
    for id in chat_ids {
        send_message(id, content.clone()).await;
    }
}

pub fn typing(chat_id: &str, on: bool) {
    let Some(c) = chat(chat_id) else { return };
    if net::state() != State::Online {
        return;
    }
    let t = now();
    if on && t - TYPING_SENT.with(Cell::get) < 2500.0 {
        return;
    }
    TYPING_SENT.with(|s| s.set(if on { t } else { 0.0 }));
    spawn(async move { broadcast(&c, Body::Typing { on }, true).await });
}

pub async fn set_timer(chat_id: &str, secs: u64) {
    update_chat(chat_id, |c| c.timer = secs).await;
    system_message(chat_id, &timer_text(&my_code(), secs), now()).await;
    if let Some(c) = chat(chat_id) {
        broadcast(&c, Body::Timer { secs }, false).await;
    }
}

fn timer_text(code: &str, secs: u64) -> String {
    let who = if code == my_code() {
        "Vous avez".to_owned()
    } else {
        format!("{} a", display_name(code))
    };
    if secs == 0 {
        return format!("⏳ {who} désactivé les messages éphémères.");
    }
    let label = if secs >= 90 * 86400 {
        "90 jours"
    } else if secs >= 7 * 86400 {
        "7 jours"
    } else {
        "24 heures"
    };
    format!("⏳ {who} activé les messages éphémères : {label}.")
}

async fn system_message(chat_id: &str, text: &str, at: f64) {
    let m = Message {
        chat: chat_id.to_owned(),
        id: crypto::random_id(12),
        from: "system".into(),
        at,
        kind: "system".into(),
        text: text.to_owned(),
        ..Default::default()
    };
    store_message(&m, true).await;
}

// ----- Groupes ---------------------------------------------------------------

pub async fn create_group(name: &str, members: Vec<String>, avatar: String) -> Chat {
    let me = my_code();
    let mut all = vec![me.clone()];
    all.extend(members);
    let c = Chat {
        id: format!("g:{}", crypto::random_id(12)),
        group: true,
        name: name.trim().chars().take(60).collect(),
        avatar,
        members: all,
        admins: vec![me.clone()],
        created_by: me,
        created_at: now(),
        last_at: now(),
        v: 1,
        ..Default::default()
    };
    save_chat(c.clone()).await;
    system_message(
        &c.id,
        &format!("Vous avez créé le groupe « {} ».", c.name),
        now(),
    )
    .await;
    broadcast(
        &c,
        Body::Group {
            op: "create".into(),
            note: String::new(),
        },
        false,
    )
    .await;
    c
}

pub async fn update_group(chat_id: &str, f: impl FnOnce(&mut Chat), note: &str) {
    let Some(before) = chat(chat_id) else { return };
    let mut next = before.clone();
    f(&mut next);
    next.v = before.v.max(1) + 1;
    save_chat(next.clone()).await;
    if !note.is_empty() {
        system_message(chat_id, note, now()).await;
    }
    broadcast(
        &next,
        Body::Group {
            op: "update".into(),
            note: note.to_owned(),
        },
        false,
    )
    .await;
    // Les membres retirés l'apprennent aussi.
    let me = my_code();
    for code in before
        .members
        .iter()
        .filter(|c| !next.members.contains(c) && **c != me)
    {
        let mut w = Wire::new(Body::Group {
            op: "update".into(),
            note: note.to_owned(),
        });
        w.grp = Some(group_info(&next));
        enqueue(code, w, None, None, false).await;
    }
}

pub async fn leave_group(chat_id: &str) {
    let Some(mut c) = chat(chat_id) else { return };
    let me = my_code();
    c.members.retain(|x| *x != me);
    c.admins.retain(|x| *x != me);
    if c.admins.is_empty()
        && let Some(first) = c.members.first()
    {
        c.admins = vec![first.clone()];
    }
    c.v = c.v.max(1) + 1;
    c.left = true;
    for code in c.members.clone() {
        let mut w = Wire::new(Body::Group {
            op: "leave".into(),
            note: String::new(),
        });
        w.grp = Some(group_info(&c));
        enqueue(&code, w, None, None, false).await;
    }
    save_chat(c).await;
    system_message(chat_id, "Vous avez quitté le groupe.", now()).await;
}

// ----- Réception -------------------------------------------------------------

fn queue_receipt(to: &str, chat_id: &str, ids: &[String], st: &str) {
    RECEIPTS.with(|r| {
        r.borrow_mut()
            .entry((to.to_owned(), st.to_owned(), chat_id.to_owned()))
            .or_default()
            .extend(ids.iter().cloned())
    });
    dom::clear_timer(RECEIPT_TIMER.with(Cell::get));
    let t = dom::timer(400, || {
        let batch: Vec<_> = RECEIPTS.with(|r| r.borrow_mut().drain().collect());
        spawn(async move {
            for ((code, st, chat), ids) in batch {
                let mut w = Wire::new(Body::Receipt {
                    st,
                    ids: ids.into_iter().collect(),
                });
                if is_group(&chat) {
                    w.g = Some(chat.trim_start_matches("g:").to_owned());
                }
                enqueue(&code, w, None, None, false).await;
            }
        });
    });
    RECEIPT_TIMER.with(|c| c.set(t));
}

/// Marque une discussion comme lue (accusés de lecture bleus).
/// Toutes les discussions non lues (archivées comprises) marquées comme
/// lues ; les confirmations de lecture partent comme pour une seule.
/// Renvoie le nombre de discussions concernées.
pub async fn mark_all_read() -> usize {
    let ids: Vec<String> = chats()
        .into_iter()
        .filter(|c| c.unread > 0 || c.marked_unread)
        .map(|c| c.id)
        .collect();
    for id in &ids {
        mark_read(id).await;
    }
    ids.len()
}

pub async fn mark_read(chat_id: &str) {
    let Some(c) = chat(chat_id) else { return };
    let me = my_code();
    let mut by_sender: HashMap<String, Vec<String>> = HashMap::new();
    for mut m in store::messages_of(chat_id, 300).await {
        if m.from != me && m.from != "system" && !m.read_by_me {
            m.read_by_me = true;
            store::write_message(&m).await;
            by_sender
                .entry(m.from.clone())
                .or_default()
                .push(m.id.clone());
        }
    }
    if settings().read_receipts {
        for (code, ids) in by_sender {
            queue_receipt(&code, chat_id, &ids, "read");
        }
    }
    if c.unread > 0 || c.marked_unread {
        update_chat(chat_id, |c| {
            c.unread = 0;
            c.marked_unread = false;
        })
        .await;
    }
}

/// Une enveloppe arrive du relais.
pub fn on_envelope(env: Value) {
    spawn(async move {
        let n = env["n"].as_u64();
        receive(&env).await;
        if let Some(n) = n {
            // Le relais oublie le message une fois rangé pour de bon (avec
            // l'état de session qui l'a ouvert).
            store::committed().await;
            net::got(&[n]);
        }
    });
}

async fn receive(env: &Value) {
    // Scellé : l'expéditeur est dans l'enveloppe, que nous seuls ouvrons ;
    // l'enveloppe intérieure le prouve ensuite (sa clé de paire).
    let unsealed = if env["sealed"].as_bool() == Some(true) {
        let Some(opened) = crypto::open_sealed(&me(), env["body"].as_str().unwrap_or("")) else {
            return;
        };
        Some(opened)
    } else {
        None
    };
    let from = match &unsealed {
        Some((from, _)) => from.clone(),
        None => env["from"].as_str().unwrap_or("").to_owned(),
    };
    if from.is_empty() || blocked(&from) {
        return;
    }
    let Some(peer) = keys_for(&from).await else {
        return;
    };
    load_v3(&from).await;
    let body = match &unsealed {
        Some((_, body)) => body.as_str(),
        None => env["body"].as_str().unwrap_or(""),
    };
    // Le message, et l'état de session qui l'a ouvert : une seule
    // transaction IndexedDB.
    let _batch = store::batch();
    let opened = with_v3(|e| e.open(&me(), &peer.code, &peer.dh, body, v3::secs(now())))
        .unwrap_or_else(|| match crypto::open(&me(), &peer.code, &peer.dh, body) {
            Some(plain) => Opened::Legacy(plain),
            None => Opened::Failed(Failure::Envelope),
        });
    save_v3().await;
    let plain = match opened {
        Opened::Legacy(plain) | Opened::V3(plain) => plain,
        // Déjà lu : le relais l'a renvoyé.
        Opened::Replay => return,
        Opened::Failed(Failure::Envelope) => return, // pas pour nous, ou abîmé
        Opened::Failed(Failure::V3(e)) => {
            dom::warn(&format!("v3 : message de {from} illisible ({e})"));
            if v3::needs_repair(&e) {
                repair_v3(&from);
            }
            return;
        }
    };
    let Ok(wire) = serde_json::from_str::<Wire>(&plain) else {
        return;
    };
    let ts = env["ts"].as_f64().unwrap_or_else(now);
    handle(&from, wire, ts, env["live"].as_bool().unwrap_or(false)).await;
}

async fn group_chat(from: &str, grp: &GroupInfo, at: f64) -> Option<Chat> {
    let id = format!("g:{}", grp.id);
    let me = my_code();
    match chat(&id) {
        None => {
            if !grp.members.contains(&me) {
                return None;
            }
            let c = Chat {
                id: id.clone(),
                group: true,
                name: grp.name.clone(),
                avatar: grp.avatar.clone(),
                members: grp.members.clone(),
                admins: grp.admins.clone(),
                created_by: from.to_owned(),
                created_at: now(),
                last_at: now(),
                v: grp.v,
                ..Default::default()
            };
            save_chat(c.clone()).await;
            system_message(
                &id,
                &format!(
                    "{} vous a ajouté au groupe « {} ».",
                    display_name(from),
                    grp.name
                ),
                at,
            )
            .await;
            net::watch(
                &grp.members
                    .iter()
                    .filter(|c| **c != me)
                    .cloned()
                    .collect::<Vec<_>>(),
            );
            Some(c)
        }
        Some(mut c) if grp.v > c.v => {
            let was_in = c.members.contains(&me);
            c.name = grp.name.clone();
            c.avatar = grp.avatar.clone();
            c.members = grp.members.clone();
            c.admins = grp.admins.clone();
            c.v = grp.v;
            if grp.members.contains(&me) {
                c.left = false;
            } else if was_in {
                c.left = true;
            }
            save_chat(c.clone()).await;
            Some(c)
        }
        other => other,
    }
}

async fn handle(from: &str, wire: Wire, ts: f64, live: bool) {
    let me = my_code();
    match &wire.body {
        Body::Profile { profile, jeton, nk } => {
            let mut c = contact(from).unwrap_or_else(|| Contact {
                code: from.to_owned(),
                added_at: now(),
                ..Default::default()
            });
            // Son jeton de remise, s'il est bien signé par sa clé.
            if *jeton != c.jeton
                && !jeton.is_empty()
                && crypto::verify_jeton(from, &c.sign, &c.dh, jeton)
            {
                c.jeton = jeton.clone();
                save_contact(c.clone()).await;
            }
            // Sa clé de notification (une clé P-256 publique, ou rien).
            if *nk != c.nk && (nk.is_empty() || crypto::valid_nk(nk)) {
                c.nk = nk.clone();
                save_contact(c.clone()).await;
            }
            if profile.v >= c.profile_v {
                c.profile_name = profile.name.chars().take(40).collect();
                c.about = profile.about.chars().take(140).collect();
                c.avatar = if profile.avatar.len() <= 60_000 {
                    profile.avatar.clone()
                } else {
                    String::new()
                };
                c.profile_v = profile.v;
                save_contact(c).await;
                emit(Ev::Chats);
            }
            return;
        }
        Body::Typing { on } => {
            let chat_id = match wire.group_id() {
                Some(g) => format!("g:{g}"),
                None => from.to_owned(),
            };
            if chat(&chat_id).is_none() {
                return;
            }
            let old = with(|s| s.typing.entry(chat_id.clone()).or_default().remove(from));
            if let Some(t) = old {
                dom::clear_timer(t);
            }
            if *on {
                let (c, f) = (chat_id.clone(), from.to_owned());
                let t = dom::timer(6000, move || {
                    with(|s| s.typing.get_mut(&c).map(|m| m.remove(&f)));
                    emit(Ev::Typing(c));
                });
                with(|s| {
                    s.typing
                        .entry(chat_id.clone())
                        .or_default()
                        .insert(from.to_owned(), t)
                });
            }
            emit(Ev::Typing(chat_id));
            return;
        }
        Body::Call { .. } => {
            emit(Ev::CallSignal {
                from: from.to_owned(),
                body: wire.body,
                live,
            });
            return;
        }
        Body::Status {
            id,
            kind,
            text,
            bg,
            data,
            at,
        } => {
            if !contact(from).is_some_and(|c| c.saved) || ts - at > DAY {
                return;
            }
            let st = StatusItem {
                owner: from.to_owned(),
                id: id.clone(),
                kind: kind.clone(),
                text: text.chars().take(700).collect(),
                bg: bg.clone(),
                data: data.clone(),
                at: *at,
                ..Default::default()
            };
            store::write("statuses", &st.key(), &st).await;
            emit(Ev::Statuses);
            return;
        }
        Body::StatusSeen { id } => {
            let key = format!("{me}|{id}");
            if let Some(mut st) = store::read::<StatusItem>("statuses", &key).await {
                st.viewers.insert(from.to_owned(), ts);
                store::write("statuses", &key, &st).await;
                emit(Ev::Statuses);
            }
            return;
        }
        Body::StatusDelete { id } => {
            store::remove("statuses", &format!("{from}|{id}")).await;
            emit(Ev::Statuses);
            return;
        }
        _ => {}
    }

    // Le reste appartient à une discussion.
    let c = match (&wire.grp, &wire.g) {
        (Some(grp), _) => {
            // L'annonce « vous a ajouté » se range juste avant le premier message.
            let at = match &wire.body {
                Body::Msg { at, .. } => at.min(ts) - 1.0,
                _ => ts,
            };
            group_chat(from, grp, at).await
        }
        (None, Some(g)) => chat(&format!("g:{g}")),
        (None, None) => Some(ensure_direct_chat(from).await),
    };
    let Some(mut c) = c else { return };
    if c.group && !c.members.iter().any(|x| x == from) && !matches!(wire.body, Body::Group { .. }) {
        return;
    }

    match wire.body {
        Body::Msg {
            id,
            at,
            pn,
            content,
        } => {
            if get_message(&c.id, &id).await.is_some() {
                return; // déjà reçu
            }
            // Une clé Brocolis partagée n'en est une que si elle est valide.
            let shared_key =
                content.kind == "brokey" && jetons::key_from_b64(&content.data).is_some();
            let kind = if ["text", "image", "voice", "file", "contact"]
                .contains(&content.kind.as_str())
                || shared_key
            {
                content.kind
            } else {
                "text".into()
            };
            let at = at.min(ts + 60_000.0);
            let mut m = Message {
                chat: c.id.clone(),
                id: id.clone(),
                from: from.to_owned(),
                at,
                kind,
                text: content.text.chars().take(65_536).collect(),
                data: content.data,
                name: content.name,
                mime: content.mime,
                size: content.size,
                dur: content.dur,
                w: content.w,
                h: content.h,
                code: content.code,
                reply: content.reply,
                fwd: content.fwd,
                // Une capsule s'ouvre au plus tard dans dix ans.
                open_at: if content.open_at > at {
                    content.open_at.min(at + CAPSULE_MAX_MS)
                } else {
                    0.0
                },
                ..Default::default()
            };
            if let Some(ttl) = wire.ttl {
                m.exp = Some(at + ttl as f64 * 1000.0);
            }
            open_message(&mut m);
            if let Some(mut sender) = contact(from)
                && sender.profile_name.is_empty()
                && !pn.is_empty()
            {
                sender.profile_name = pn.chars().take(40).collect();
                save_contact(sender).await;
            }
            let active = active_chat().as_deref() == Some(c.id.as_str()) && dom::visible();
            if active {
                m.read_by_me = true;
            } else {
                c.unread += 1;
                save_chat(c.clone()).await;
            }
            store_message(&m, true).await;
            queue_receipt(from, &c.id, std::slice::from_ref(&id), "delivered");
            if active && settings().read_receipts {
                queue_receipt(from, &c.id, &[id], "read");
            }
            // Votre profil, pour qui ne l'a pas encore.
            if let Some(mut sender) = contact(from)
                && (sender.sent_profile_v < profile().v
                    || (!sender.jeton_sent && net::sealed_supported()))
            {
                sender.sent_profile_v = profile().v;
                sender.jeton_sent = net::sealed_supported();
                save_contact(sender).await;
                enqueue(from, Wire::new(profile_body()), None, None, false).await;
            }
            let removed = with(|s| s.typing.get_mut(&c.id).and_then(|t| t.remove(from)));
            if let Some(t) = removed {
                dom::clear_timer(t);
                emit(Ev::Typing(c.id.clone()));
            }
            let c = chat(&c.id).unwrap_or(c);
            emit(Ev::Incoming {
                chat: c,
                message: m,
                active,
            });
        }
        Body::Receipt { st, ids } => {
            for id in ids {
                let Some(mut m) = get_message(&c.id, &id).await else {
                    continue;
                };
                if m.from != me {
                    continue;
                }
                let add = |list: &mut Vec<String>| {
                    if !list.iter().any(|x| x == from) {
                        list.push(from.to_owned());
                    }
                };
                add(&mut m.delivered_to);
                if st == "read" {
                    add(&mut m.read_by);
                }
                store::write_message(&m).await;
                // Bien arrivé : plus besoin de le garder pour un renvoi.
                store::remove("outbox", &format!("{id}:{from}")).await;
            }
            emit(Ev::Messages(c.id.clone()));
        }
        Body::React { id, emoji } => {
            let Some(mut m) = get_message(&c.id, &id).await else {
                return;
            };
            if emoji.is_empty() {
                m.reactions.remove(from);
            } else {
                m.reactions
                    .insert(from.to_owned(), emoji.chars().take(8).collect());
            }
            store::write_message(&m).await;
            emit(Ev::Messages(c.id.clone()));
            if m.from == me && !emoji.is_empty() {
                emit(Ev::Reaction {
                    chat: c,
                    from: from.to_owned(),
                    emoji,
                });
            }
        }
        Body::Edit { id, text } => {
            let Some(mut m) = get_message(&c.id, &id).await else {
                return;
            };
            if m.from != from || m.deleted {
                return;
            }
            let text: String = text.chars().take(65_536).collect();
            let o = open_brocolis(&text);
            m.bro = o.opened > 0;
            m.text = o.text;
            m.edited = true;
            store::write_message(&m).await;
            refresh_last(&c.id).await;
            emit(Ev::Messages(c.id.clone()));
        }
        Body::Delete { id } => {
            let Some(mut m) = get_message(&c.id, &id).await else {
                return;
            };
            if m.from != from {
                return;
            }
            m.deleted = true;
            m.text.clear();
            m.data.clear();
            m.reactions.clear();
            store::write_message(&m).await;
            refresh_last(&c.id).await;
            emit(Ev::Messages(c.id.clone()));
        }
        Body::Timer { secs } => {
            update_chat(&c.id, |c| c.timer = secs).await;
            system_message(&c.id, &timer_text(from, secs), ts).await;
        }
        Body::Group { op, note } => {
            if op == "leave" {
                system_message(
                    &c.id,
                    &format!("{} a quitté le groupe.", display_name(from)),
                    ts,
                )
                .await;
            } else if op != "create" && !note.is_empty() {
                let note = match note.strip_prefix("Vous avez") {
                    Some(rest) => format!("{} a{rest}", display_name(from)),
                    None => note,
                };
                system_message(&c.id, &note, ts).await;
            }
            emit(Ev::Chats);
        }
        _ => {}
    }
}

// ----- Statuts ---------------------------------------------------------------

pub async fn post_status(kind: &str, text: &str, bg: &str, data: String) {
    let id = crypto::random_id(10);
    let at = net::server_now();
    let st = StatusItem {
        owner: my_code(),
        id: id.clone(),
        kind: kind.to_owned(),
        text: text.to_owned(),
        bg: bg.to_owned(),
        data: data.clone(),
        at,
        seen: true,
        ..Default::default()
    };
    store::write("statuses", &st.key(), &st).await;
    emit(Ev::Statuses);
    let body = Body::Status {
        id,
        kind: kind.to_owned(),
        text: text.to_owned(),
        bg: bg.to_owned(),
        data,
        at,
    };
    for c in contacts() {
        if c.saved && !c.blocked {
            enqueue(&c.code, Wire::new(body.clone()), None, None, false).await;
        }
    }
}

pub async fn delete_status(id: &str) {
    store::remove("statuses", &format!("{}|{id}", my_code())).await;
    emit(Ev::Statuses);
    for c in contacts() {
        if c.saved && !c.blocked {
            enqueue(
                &c.code,
                Wire::new(Body::StatusDelete { id: id.to_owned() }),
                None,
                None,
                false,
            )
            .await;
        }
    }
}

pub async fn statuses() -> Vec<StatusItem> {
    let t = now();
    let mut list: Vec<StatusItem> = store::all::<StatusItem>("statuses")
        .await
        .into_iter()
        .filter(|s| s.at + DAY > t)
        .collect();
    list.sort_by(|a, b| a.at.total_cmp(&b.at));
    list
}

pub async fn mark_status_seen(st: &StatusItem) {
    if st.seen || st.owner == my_code() {
        return;
    }
    let mut s = st.clone();
    s.seen = true;
    store::write("statuses", &s.key(), &s).await;
    emit(Ev::Statuses);
    if settings().read_receipts {
        enqueue(
            &st.owner,
            Wire::new(Body::StatusSeen { id: st.id.clone() }),
            None,
            None,
            false,
        )
        .await;
    }
}

// ----- Appels ----------------------------------------------------------------

pub fn send_call_signal(code: &str, body: Body) {
    let code = code.to_owned();
    spawn(async move { enqueue(&code, Wire::new(body), None, None, true).await });
}

pub async fn log_call(entry: CallEntry) {
    store::write("calls", &entry.id, &entry).await;
    emit(Ev::Calls);
}

pub async fn call_log() -> Vec<CallEntry> {
    let mut list: Vec<CallEntry> = store::all("calls").await;
    list.sort_by(|a, b| b.at.total_cmp(&a.at));
    list
}

pub async fn clear_calls() {
    store::clear("calls").await;
    emit(Ev::Calls);
}

// ----- Sauvegarde ------------------------------------------------------------

pub async fn export_backup(password: &str) -> Option<String> {
    let mut dump = store::dump().await;
    // Les clés Brocolis y vont en clair (la sauvegarde a son mot de passe) :
    // scellées par le code de cet appareil, elles ne s'ouvriraient pas ailleurs.
    if let Some(row) = brocolis_for_backup()
        && let Some(kv) = dump.get_mut("kv").and_then(Value::as_array_mut)
    {
        kv.retain(|r| {
            r.get(0)
                .and_then(Value::as_str)
                .is_none_or(|k| k != "brocolis" && k != "brocolis_sealed")
        });
        kv.push(row);
    }
    // Les sessions v3 n'y vont pas : restaurées plus tard, en retard sur
    // celles du contact, elles reprendraient des clés de message déjà
    // servies. L'appareil v3 (`v3_device`) y va, comme l'identité ; après
    // une restauration, chaque contact ouvre (ou reçoit) une session neuve.
    if let Some(kv) = dump.get_mut("kv").and_then(Value::as_array_mut) {
        kv.retain(|r| {
            r.get(0)
                .and_then(Value::as_str)
                .is_none_or(|k| !k.starts_with(v3::SESSION_PREFIX))
        });
    }
    let data = serde_json::json!({ "app": "talka", "at": now(), "data": dump });
    let backup = crypto::encrypt_backup(password, &data.to_string())?;
    serde_json::to_string(&backup).ok()
}

pub async fn import_backup(password: &str, file: &str) -> Result<(), String> {
    let bad = "Ce fichier n'est pas une sauvegarde Talka.";
    let backup: crypto::Backup = serde_json::from_str(file).map_err(|_| bad.to_owned())?;
    let plain = crypto::decrypt_backup(password, &backup)?;
    let data: Value = serde_json::from_str(&plain).map_err(|_| bad.to_owned())?;
    if data["app"] != "talka" {
        return Err(bad.into());
    }
    // Une autre identité : l'abonnement aux notifications de l'ancienne ne
    // doit plus réveiller ce navigateur (la nouvelle se réabonne).
    crate::push::forget().await;
    store::restore(&data["data"]).await;
    Ok(())
}

pub async fn wipe_everything() {
    crate::push::forget().await;
    net::stop();
    store::wipe().await;
    store::local_clear();
}
