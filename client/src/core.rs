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
use crate::pq6;
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
    /// Cet appareil (v4) et les appareils de notre compte.
    static DEV: RefCell<String> = const { RefCell::new(String::new()) };
    static DEVICES: RefCell<Devices> = RefCell::new(Devices::default());
    /// Nos boîtes tournantes suivies : boîte → le contact qui y écrit ; et le
    /// jour pour lequel elles ont été calculées.
    static BOXES: RefCell<HashMap<String, String>> = RefCell::new(HashMap::new());
    /// Le secret des boîtes de couverture (v6) : propre à cet appareil.
    static COVER_SECRET: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
    static BOX_DAY: Cell<u64> = const { Cell::new(0) };
    /// Une liaison en cours sur ce nouvel appareil (v4) : le secret, et les
    /// morceaux reçus.
    static LINKING: RefCell<Option<Linking>> = const { RefCell::new(None) };
}

/// Une liaison attendue (§7.12) : ce que le nouvel appareil a montré, et ce
/// qu'il a reçu.
struct Linking {
    secret: crypto::LinkSecret,
    key: Option<[u8; 32]>,
    ek: Vec<u8>,
    chunks: Vec<Option<Vec<u8>>>,
    done: Option<dom::Sender<Result<String, String>>>,
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

/// Cet appareil (v4).
pub fn my_dev() -> String {
    DEV.with(|d| d.borrow().clone())
}

/// Les appareils de notre compte (v4).
pub fn devices() -> Devices {
    DEVICES.with(|d| d.borrow().clone())
}

/// Nos autres appareils.
fn siblings() -> Vec<String> {
    let me = my_dev();
    DEVICES.with(|d| {
        d.borrow()
            .list
            .iter()
            .filter(|x| x.dev != me)
            .map(|x| x.dev.clone())
            .collect()
    })
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

/// Le libellé d'une mention (« @Léa ») : le nom que chacun s'est choisi, le
/// même sur tous les appareils (à défaut, celui qu'on lui a donné).
pub fn mention_label(code: &str) -> String {
    if code == my_code() {
        return profile().name;
    }
    match contact(code) {
        Some(c) if !c.profile_name.is_empty() => c.profile_name,
        Some(c) if !c.name.is_empty() => c.name,
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
    let relay_key: String = store::read("kv", "relais_cle").await.unwrap_or_default();
    RELAY_KEY.with(|k| *k.borrow_mut() = relay_key);
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
    ensure_device().await;
    net::start(identity, hidden, &my_dev());
    sweep().await;
    dom::interval(60_000, || {
        spawn(sweep());
        // Ce qui attend un paquet v3 (ou le réseau) est réessayé.
        spawn(flush());
        spawn(send_cover());
    });
}

/// Le relais annonce un changement d'état.
pub fn on_net(state: State) {
    emit(Ev::Net);
    if state == State::Online {
        watch_contacts();
        publish_v3();
        unlink_gone();
        spawn(announce_devices());
        spawn(refresh_boxes());
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
    let was = with(|s| s.presence.insert(code.clone(), (online, seen)));
    // Il revient : il a republié son paquet v3. Ce qui l'attendait faute de
    // paquet (un contact qui refuse l'ancien format) part maintenant.
    // À son code comme à chacun de ses appareils (v4).
    if online
        && !was.is_some_and(|(o, _)| o)
        && BUNDLES.with(|b| forget_missing(&mut b.borrow_mut(), &code))
    {
        spawn(flush());
    }
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
    // Un nouveau jour : de nouvelles boîtes tournantes à lire (v4).
    let today = crypto::day(net::server_now());
    if BOX_DAY.with(|d| d.replace(today)) != today {
        refresh_boxes().await;
    }
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
        let mut gone = Vec::new();
        for m in store::messages_of(&chat.id, usize::MAX).await {
            if m.exp.is_some_and(|e| e < t) {
                store::remove("messages", &m.key()).await;
                gone.push((m.id.clone(), m.at));
                changed = true;
            }
        }
        bury(&chat.id, gone).await;
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
            let mut gone = Vec::new();
            for m in store::messages_of(&chat.id, usize::MAX).await {
                if m.too_old(keep, t) {
                    store::remove("messages", &m.key()).await;
                    gone.push((m.id.clone(), m.at));
                    changed = true;
                }
            }
            bury(&chat.id, gone).await;
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
    let mut engine = Engine::start(device.as_deref(), v3::secs(now()));
    // Le cliquet post-quantique (v6, §7.17).
    let pq = store::get_raw("kv", pq6::KEYS).await;
    engine.start_pq(pq.as_deref(), v3::secs(now()));
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
    if let Some(keys) = dirty.pq {
        write_json(pq6::KEYS, &keys).await;
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
    // Celui des anciens clients, puis celui de cet appareil (v4).
    if let Some((bundle, sig)) = with_v3(|e| e.published(&me())).flatten() {
        net::publish_bundle(&bundle, &sig, "");
    }
    let dev = my_dev();
    if let Some((bundle, sig)) = with_v3(|e| e.published_device(&me(), &dev)).flatten() {
        net::publish_bundle(&bundle, &sig, &dev);
    }
}

/// Le paquet v3 d'un contact (de son appareil `dev` en v4), vérifié (lié à
/// son code, et à l'appareil) ; gardé une heure en mémoire (dix minutes
/// s'il n'en a pas).
async fn bundle_for(peer: &Contact, dev: &str) -> Option<KeyBundle> {
    let t = now();
    let addr = address(&peer.code, dev);
    if let Some((bundle, at)) = BUNDLES.with(|b| b.borrow().get(&addr).cloned()) {
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
    // Celui des anciens clients.
    let legacy = found["bundle"]
        .as_str()
        .zip(found["sig"].as_str())
        .and_then(|(b, s)| v3::verify_bundle(&peer.code, &peer.sign, &peer.dh, b, s));
    // Et ceux de ses appareils (v4) : chacun gardé à son adresse.
    let mut found_dev = None;
    for d in found["devices"].as_array().into_iter().flatten() {
        let (Some(id), Some(b), Some(sig)) =
            (d["dev"].as_str(), d["bundle"].as_str(), d["sig"].as_str())
        else {
            continue;
        };
        let bundle = v3::verify_device_bundle(&peer.code, &peer.sign, &peer.dh, id, b, sig);
        if bundle.is_none() {
            dom::warn("v4 : paquet d'appareil refusé (signature ou format)");
            continue;
        }
        if id == dev {
            found_dev = bundle.clone();
        }
        BUNDLES.with(|m| m.borrow_mut().insert(address(&peer.code, id), (bundle, t)));
    }
    let bundle = if dev.is_empty() { legacy } else { found_dev };
    if bundle.is_none() && dev.is_empty() && found["none"] != true {
        dom::warn("v3 : paquet de clés refusé (signature ou format)");
    }
    BUNDLES.with(|b| b.borrow_mut().insert(addr, (bundle.clone(), t)));
    bundle
}

/// Le cliquet post-quantique (v6) est-il en place avec l'un de ses appareils ?
pub fn post_quantum_ratchet(code: &str) -> bool {
    with_v3(|e| e.pq_with_any(code)).unwrap_or(false)
}

/// La protection de la discussion avec un contact (fiche du contact).
pub async fn protection(code: &str) -> Protection {
    load_v3(code).await;
    if let Some(p) = with_v3(|e| e.session_protection(code)).flatten() {
        return p;
    }
    if let Some(peer) = keys_for(code).await
        && let Some(bundle) = bundle_for(&peer, "").await
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
fn repair_v3(code: &str, dev: &str) {
    let addr = address(code, dev);
    let t = now();
    let recent = REPAIRS.with(|r| r.borrow().get(&addr).is_some_and(|at| t - at < 300_000.0));
    if recent {
        return;
    }
    REPAIRS.with(|r| r.borrow_mut().insert(addr.clone(), t));
    with_v3(|e| e.forget(&addr));
    let (code, dev) = (code.to_owned(), dev.to_owned());
    spawn(async move {
        save_v3().await;
        // Notre profil (ou, pour un de nos appareils, la liste des
        // appareils), à cet appareil seulement : il ouvre la session neuve.
        let body = if code == my_code() {
            Body::Sync {
                op: "devices".into(),
                c: String::new(),
                w: None,
                d: serde_json::to_value(devices()).unwrap_or_default(),
            }
        } else {
            profile_body(&code)
        };
        if dev.is_empty() {
            enqueue(&code, Wire::new(body), None, None, false).await;
        } else {
            enqueue_to(&code, Some(&dev), Wire::new(body)).await;
        }
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
    sync(
        "profile",
        "",
        None,
        serde_json::to_value(&p).unwrap_or_default(),
    )
    .await;
    announce_profile().await;
}

/// Notre profil pour `code`, avec son jeton de remise personnel : pour un
/// contact, ou quelqu'un qui nous écrit (un membre d'un de nos groupes), tant
/// qu'il n'est pas bloqué (le bloquer le révoque).
fn profile_body(code: &str) -> Body {
    let jeton = match contact(code) {
        Some(c) if !c.blocked => me().jeton_for(code, c.jeton_epoch),
        _ => String::new(),
    };
    let devices = devices();
    Body::Profile {
        profile: profile(),
        jeton,
        nk: with(|s| s.nk.clone()),
        pj: true,
        devs: devices.announced(),
        dv: devices.v,
        bx: true,
    }
}

// ----- Plusieurs appareils (v4, docs/SECURITE.md §7.11) ------------------------

/// Le nom d'un appareil qui vient d'être lié, jusqu'à ce qu'il donne le sien.
const NEW_DEVICE: &str = "Nouvel appareil";

/// Le nom de cet appareil, tel que le montre la liste des appareils.
fn device_name() -> String {
    let ua = dom::window().navigator().user_agent().unwrap_or_default();
    let browser = if ua.contains("Edg/") {
        "Edge"
    } else if ua.contains("Firefox/") {
        "Firefox"
    } else if ua.contains("Chrome/") {
        "Chrome"
    } else if ua.contains("Safari/") {
        "Safari"
    } else {
        "Navigateur"
    };
    let os = if ua.contains("iPhone") {
        "iPhone"
    } else if ua.contains("iPad") {
        "iPad"
    } else if ua.contains("Android") {
        "Android"
    } else if ua.contains("Mac OS X") {
        "Mac"
    } else if ua.contains("Windows") {
        "Windows"
    } else if ua.contains("Linux") {
        "Linux"
    } else {
        ""
    };
    if os.is_empty() {
        browser.to_owned()
    } else {
        format!("{browser} sur {os}")
    }
}

/// Cet appareil : son identifiant (tiré une fois, jamais sauvegardé), et sa
/// place dans la liste des appareils du compte.
async fn ensure_device() {
    let mut dev: String = store::read("kv", "dev").await.unwrap_or_default();
    if !crypto::is_dev(&dev) {
        dev = crypto::new_dev();
        store::write("kv", "dev", &dev).await;
    }
    DEV.with(|d| *d.borrow_mut() = dev.clone());
    let mut list: Devices = store::read("kv", "appareils").await.unwrap_or_default();
    // Lié depuis un autre appareil : il prend son vrai nom.
    if let Some(d) = list
        .list
        .iter_mut()
        .find(|d| d.dev == dev && d.name == NEW_DEVICE)
    {
        d.name = device_name();
        list.v += 1;
        store::write("kv", "appareils", &list).await;
    }
    if !list.has(&dev) {
        list.list.push(Device {
            dev: dev.clone(),
            name: device_name(),
            at: now(),
            nk: with(|s| s.nk.clone()),
        });
        // Au-delà de six, les plus anciens cèdent leur place.
        while list.list.len() > MAX_DEVICES {
            list.list.remove(0);
        }
        list.v += 1;
        store::write("kv", "appareils", &list).await;
    }
    DEVICES.with(|d| *d.borrow_mut() = list);
}

/// La liste des appareils a changé : gardée, donnée à nos autres appareils
/// et annoncée aux contacts (dans le profil).
async fn devices_changed(list: Devices, tell_siblings: bool) {
    DEVICES.with(|d| *d.borrow_mut() = list.clone());
    store::write("kv", "appareils", &list).await;
    if tell_siblings {
        sync(
            "devices",
            "",
            None,
            serde_json::to_value(&list).unwrap_or_default(),
        )
        .await;
    }
    announce_profile().await;
    emit(Ev::Profile);
}

/// La liste des appareils a changé depuis la dernière annonce (un appareil
/// ajouté au démarrage, une liaison faite hors ligne) : annoncée.
async fn announce_devices() {
    let list = devices();
    let told: u64 = store::read("kv", "appareils_annonces").await.unwrap_or(0);
    if list.v > told {
        sync(
            "devices",
            "",
            None,
            serde_json::to_value(&list).unwrap_or_default(),
        )
        .await;
        announce_profile().await;
        store::write("kv", "appareils_annonces", &list.v).await;
    }
}

/// Notre profil à tous nos contacts (et aux correspondants non bloqués).
async fn announce_profile() {
    for c in contacts() {
        if c.saved && !c.blocked {
            enqueue(&c.code, Wire::new(profile_body(&c.code)), None, None, false).await;
        }
    }
    spawn(flush());
}

/// Renomme cet appareil (ou un autre du compte).
pub async fn rename_device(dev: &str, name: &str) {
    let mut list = devices();
    let Some(d) = list.list.iter_mut().find(|d| d.dev == dev) else {
        return;
    };
    d.name = name.trim().chars().take(40).collect();
    list.v += 1;
    devices_changed(list, true).await;
}

/// Retire un appareil du compte : il reçoit l'ordre de tout effacer, le
/// relais l'oublie, et les contacts ne lui écrivent plus.
pub async fn unlink_device(dev: &str) {
    if dev == my_dev() {
        return;
    }
    // Lui d'abord (tant qu'il est encore dans la liste).
    let w = Wire::new(Body::Sync {
        op: "unlink".into(),
        c: String::new(),
        w: None,
        d: serde_json::json!({ "dev": dev }),
    });
    enqueue_to(&my_code(), Some(dev), w).await;
    flush().await;
    net::unlink(dev);
    let mut list = devices();
    list.remove(dev);
    devices_changed(list, true).await;
}

/// Les appareils retirés, redits au relais à chaque connexion (il ne garde
/// rien sur disque) : un appareil retiré pendant qu'il était hors ligne reste
/// refusé, et efface tout à son retour.
fn unlink_gone() {
    for dev in devices().gone {
        net::unlink(&dev);
    }
}

// ----- Les boîtes tournantes (v4, §7.13) --------------------------------------

/// Les boîtes où nos contacts nous écrivent (à cet appareil) : pour chacun,
/// chaque jour depuis la dernière écoute (un de marge, trente au plus)
/// jusqu'à demain. La connexion anonyme de lecture les suit.
pub async fn refresh_boxes() {
    if with(|s| s.me.is_none()) {
        return;
    }
    let me = me();
    let dev = my_dev();
    let today = crypto::day(net::server_now());
    let last: u64 = store::read("kv", "boites_jour").await.unwrap_or(today);
    let from = last.saturating_sub(1).max(today.saturating_sub(30));
    let mut map = HashMap::new();
    for c in contacts() {
        if c.blocked || c.dh.is_empty() {
            continue;
        }
        let Some(key) = crypto::boites_key(&me, &c.code, &c.dh) else {
            continue;
        };
        for day in from..=today + 1 {
            map.insert(crypto::boite(&key, &me.code, &dev, day), c.code.clone());
        }
    }
    // Des boîtes de couverture (v6, §7.15) : à aujourd'hui et demain, et
    // assez pour que le nombre de boîtes suivies ne dise pas combien nous
    // avons de contacts (un multiple de 64).
    let secret = cover_secret().await;
    for day in [today, today + 1] {
        for i in 0..COVER_MIN {
            map.insert(crypto::cover_box(&secret, day, i), String::new());
        }
    }
    let mut i = COVER_MIN;
    while map.len() % COVER_ROUND != 0 {
        map.insert(crypto::cover_box(&secret, today, i), String::new());
        i += 1;
    }
    COVER_SECRET.with(|c| *c.borrow_mut() = secret);
    let list: Vec<String> = map.keys().cloned().collect();
    BOXES.with(|b| *b.borrow_mut() = map);
    net::listen_boxes(list, crate::push::subscription());
    if net::state() == State::Online {
        store::write("kv", "boites_jour", &today).await;
    }
}

/// Boîtes de couverture par jour, au moins ; le nombre de boîtes suivies est
/// arrondi au multiple de `COVER_ROUND` supérieur.
const COVER_MIN: usize = 4;
const COVER_ROUND: usize = 64;

/// Le secret des boîtes de couverture : tiré une fois, propre à l'appareil.
async fn cover_secret() -> Vec<u8> {
    let saved: String = store::read("kv", "couverture").await.unwrap_or_default();
    if let Some(s) = crypto::unb64(&saved).filter(|s| s.len() == 32) {
        return s;
    }
    let s = crypto::random_bytes(32);
    store::write("kv", "couverture", &crypto::b64(&s)).await;
    s
}

/// Le trafic de couverture (v6, §7.15) : de temps en temps (une fois toutes
/// les trois minutes en moyenne, en ligne), une enveloppe scellée vide, au
/// même palier qu'un court message, part dans une de nos boîtes de
/// couverture ; pour le relais, c'est un dépôt comme un autre. Elle nous
/// revient, et on l'oublie.
async fn send_cover() {
    if net::state() != State::Online || !net::sealed_supported() || js_sys::Math::random() > 0.35 {
        return;
    }
    let secret = COVER_SECRET.with(|c| c.borrow().clone());
    if secret.is_empty() || with(|s| s.me.is_none()) {
        return;
    }
    let me = me();
    let day = crypto::day(net::server_now());
    let i = (js_sys::Math::random() * COVER_MIN as f64) as usize % COVER_MIN;
    let boite = crypto::cover_box(&secret, day, i);
    if let Some(sealed) = crypto::seal_sender_from(&me.code, &my_dev(), &me.code, &me.dh, "") {
        let id = crypto::random_id(9);
        let _ = net::send_boite(&id, &boite, &sealed, false, false, None, None).await;
    }
}

// ----- La liaison d'un appareil (v4, §7.12) -----------------------------------

/// Ce qu'un nouvel appareil ne doit pas recevoir : ce qui est propre à cet
/// appareil-ci.
fn device_only(key: &str) -> bool {
    matches!(
        key,
        "dev" | "nk" | "pin" | "appareils" | "boites_jour" | "brocolis_sealed" | "couverture"
    ) || key == v3::DEVICE_KEY
        || key == pq6::KEYS
        || key.starts_with(v3::SESSION_PREFIX)
}

/// Lie un nouvel appareil (son code de liaison, lu dans son QR code) :
/// il reçoit notre compte, chiffré pour lui seul. Renvoie le code de
/// vérification à comparer avec celui qu'il affiche.
pub async fn link_device(text: &str) -> Result<String, String> {
    let offer = crypto::LinkOffer::parse(text)
        .ok_or("Ce n'est pas un code de liaison Talka.".to_owned())?;
    let mut list = devices();
    if list.has(&offer.dev) {
        return Err("Cet appareil est déjà lié.".into());
    }
    if list.list.len() >= MAX_DEVICES {
        return Err("Six appareils au plus : retirez-en un d'abord.".into());
    }
    if !net::sealed_supported() {
        return Err("Pas de connexion au relais : réessayez.".into());
    }
    let (ek, key) = crypto::link_answer(&offer).ok_or("Code de liaison illisible.".to_owned())?;
    list.list.push(Device {
        dev: offer.dev.clone(),
        name: NEW_DEVICE.into(),
        at: now(),
        nk: String::new(),
    });
    list.v += 1;
    let payload = serde_json::json!({
        "talka": "lien",
        "v": 1,
        "code": my_code(),
        "devices": list,
        "data": link_dump().await,
    })
    .to_string();
    let chunks = crypto::link_seal(&key, &offer.lid, &ek, payload.as_bytes())
        .ok_or("Chiffrement impossible.".to_owned())?;
    let boite = offer.boite();
    for (i, chunk) in chunks.iter().enumerate() {
        let id = format!("lien-{}-{i}", crypto::random_id(8));
        if let Ack::Err(e) =
            net::send_boite(&id, &boite, chunk, false, false, None, Some(3600)).await
        {
            return Err(format!("La liaison n'a pas pu partir ({e}) : réessayez."));
        }
    }
    devices_changed(list, true).await;
    Ok(crypto::link_verification(&ek, &offer.lk))
}

/// Notre compte, pour un nouvel appareil : comme la sauvegarde, sans ce qui
/// est propre à cet appareil, ni les pièces jointes de plus de 512 Kio, ni
/// plus de 32 Mio en tout (les messages les plus anciens d'abord).
async fn link_dump() -> Value {
    let mut dump = store::dump().await;
    if let Some(kv) = dump.get_mut("kv").and_then(Value::as_array_mut) {
        kv.retain(|r| {
            r.get(0)
                .and_then(Value::as_str)
                .is_none_or(|k| !device_only(k))
        });
        // Les clés Brocolis en clair (scellées par le code de cet appareil,
        // elles ne s'ouvriraient pas là-bas).
        if let Some(row) = brocolis_for_backup() {
            kv.retain(|r| r.get(0).and_then(Value::as_str) != Some("brocolis"));
            kv.push(row);
        }
    }
    if let Some(out) = dump.get_mut("outbox") {
        *out = Value::Array(Vec::new());
    }
    if let Some(rows) = dump.get_mut("messages").and_then(Value::as_array_mut) {
        // Les plus récents d'abord.
        rows.sort_by(|a, b| {
            b.get(3)
                .and_then(Value::as_f64)
                .unwrap_or(0.0)
                .total_cmp(&a.get(3).and_then(Value::as_f64).unwrap_or(0.0))
        });
        let mut total = 0usize;
        rows.retain_mut(|r| {
            let Some(json) = r.get(1).and_then(Value::as_str) else {
                return false;
            };
            let json = match serde_json::from_str::<Message>(json) {
                Ok(mut m) if m.data.len() > 512 * 1024 => {
                    m.data.clear();
                    serde_json::to_string(&m).unwrap_or_default()
                }
                _ => json.to_owned(),
            };
            total += json.len();
            r[1] = Value::String(json);
            total <= 32 * 1024 * 1024
        });
    }
    dump
}

/// Ce nouvel appareil attend d'être lié : le code à montrer (QR code), et
/// l'attente de ce que l'autre appareil envoie (le code de vérification).
pub fn start_linking() -> (String, dom::Deferred<Result<String, String>>) {
    let secret = crypto::LinkSecret::new();
    let text = secret.offer.text();
    let boite = secret.offer.boite();
    let d = dom::Deferred::new();
    LINKING.with(|l| {
        *l.borrow_mut() = Some(Linking {
            secret,
            key: None,
            ek: Vec::new(),
            chunks: Vec::new(),
            done: Some(d.sender()),
        })
    });
    net::listen_boxes(vec![boite], None);
    (text, d)
}

/// La liaison abandonnée.
pub fn cancel_linking() {
    LINKING.with(|l| *l.borrow_mut() = None);
    net::listen_boxes(Vec::new(), None);
}

/// Un morceau de la liaison arrive.
async fn link_chunk(env: &Value) {
    let Ok(chunk) = serde_json::from_str::<Value>(env["body"].as_str().unwrap_or("")) else {
        return;
    };
    let Some(ek) = chunk["e"].as_str().and_then(crypto::unb64) else {
        return;
    };
    let complete = LINKING.with(|l| {
        let mut l = l.borrow_mut();
        let link = l.as_mut()?;
        if link.key.is_none() {
            link.key = link.secret.key(&ek);
            link.ek = ek.clone();
        }
        if link.ek != ek {
            return None;
        }
        let key = link.key?;
        let (i, n, plain) = crypto::link_open(&key, &link.secret.offer.lid, &chunk)?;
        if link.chunks.len() != n {
            link.chunks = vec![None; n];
        }
        link.chunks[i] = Some(plain);
        link.chunks.iter().all(Option::is_some).then(|| {
            link.chunks
                .iter()
                .flatten()
                .flatten()
                .copied()
                .collect::<Vec<u8>>()
        })
    });
    let Some(bytes) = complete else { return };
    let result = import_link(&bytes).await;
    let done = LINKING.with(|l| {
        let mut l = l.borrow_mut();
        let link = l.take()?;
        let verif = crypto::link_verification(&link.ek, &link.secret.offer.lk);
        Some((link.done?, verif, link.secret.offer.dev))
    });
    if let Some((done, verif, _)) = done {
        done.send(Some(result.map(|_| verif)));
    }
    net::listen_boxes(Vec::new(), None);
}

/// Range le compte reçu par la liaison : tout, sauf ce qui est propre à
/// l'autre appareil ; cet appareil garde son identifiant.
async fn import_link(bytes: &[u8]) -> Result<(), String> {
    let bad = "La liaison n'a pas abouti : réessayez.".to_owned();
    let payload: Value = serde_json::from_slice(bytes).map_err(|_| bad.clone())?;
    if payload["talka"] != "lien" || payload["v"] != 1 {
        return Err(bad);
    }
    let devices: Devices =
        serde_json::from_value(payload["devices"].clone()).map_err(|_| bad.clone())?;
    let dev = LINKING
        .with(|l| l.borrow().as_ref().map(|l| l.secret.offer.dev.clone()))
        .ok_or(bad.clone())?;
    if !devices.has(&dev) {
        return Err(bad);
    }
    let mut data = payload["data"].clone();
    // L'identité doit y être, et valable.
    let identity = data["kv"]
        .as_array()
        .and_then(|kv| {
            kv.iter()
                .find(|r| r.get(0).and_then(Value::as_str) == Some("identity"))
        })
        .and_then(|r| r.get(1).and_then(Value::as_str))
        .and_then(|j| serde_json::from_str::<Identity>(j).ok())
        .filter(|i| i.is_valid() && Some(i.code.as_str()) == payload["code"].as_str())
        .ok_or(bad.clone())?;
    if let Some(kv) = data.get_mut("kv").and_then(Value::as_array_mut) {
        kv.retain(|r| {
            r.get(0)
                .and_then(Value::as_str)
                .is_none_or(|k| !device_only(k))
        });
        kv.push(serde_json::json!([
            "dev",
            serde_json::to_string(&dev).unwrap_or_default()
        ]));
        kv.push(serde_json::json!([
            "appareils",
            serde_json::to_string(&devices).unwrap_or_default()
        ]));
    }
    store::restore(&data).await;
    let _ = identity;
    Ok(())
}

// ----- La clé du relais (v6, §7.16) --------------------------------------------

/// La clé publique du relais officiel, quand elle est connue d'avance (vide :
/// retenue à la première connexion qui la présente).
pub const OFFICIAL_RELAY_KEY: &str = "";

thread_local! {
    /// La clé du relais retenue (`kv/relais_cle`).
    static RELAY_KEY: RefCell<String> = const { RefCell::new(String::new()) };
    /// L'avertissement n'est montré qu'une fois par ouverture.
    static RELAY_WARNED: Cell<bool> = const { Cell::new(false) };
}

const UNPROVEN_RELAY: &str =
    "Le serveur ne prouve pas son identité habituelle : Talka ignore ses ordres d'effacement.";

/// La clé que l'on attend du relais : celle des sources, ou celle retenue.
fn pinned_relay_key() -> String {
    if OFFICIAL_RELAY_KEY.is_empty() {
        RELAY_KEY.with(|k| k.borrow().clone())
    } else {
        OFFICIAL_RELAY_KEY.to_owned()
    }
}

/// Le relais vient d'accepter la connexion ; `key` et `sig` : sa clé et sa
/// signature du défi (vides s'il n'en a pas). Une clé prouvée est retenue la
/// première fois ; une autre ensuite, ou plus de preuve du tout, et Talka
/// le dit et n'obéit plus à ses ordres d'effacement.
pub fn on_relay_key(key: &str, sig: &str, nonce: &str) {
    let msg = format!("talka-relais-v6:pret:{nonce}:{}/{}", my_code(), my_dev());
    let proven = !key.is_empty() && crypto::verify(key, msg.as_bytes(), sig);
    let pinned = pinned_relay_key();
    if proven && pinned.is_empty() {
        RELAY_KEY.with(|k| *k.borrow_mut() = key.to_owned());
        let key = key.to_owned();
        spawn(async move { store::write("kv", "relais_cle", &key).await });
    } else if !pinned.is_empty() && !(proven && pinned == key) {
        dom::warn("v6 : le relais ne prouve pas sa clé habituelle");
        if !RELAY_WARNED.with(|w| w.replace(true)) {
            dom::toast(UNPROVEN_RELAY);
        }
    }
}

/// Le relais dit que cet appareil est retiré : à croire seulement s'il l'a
/// signé de la clé retenue (sans clé retenue, comme avant).
pub fn relay_says_unlinked(sig: &str) -> bool {
    let pinned = pinned_relay_key();
    if pinned.is_empty() {
        return true;
    }
    let msg = format!("talka-relais-v6:retire:{}/{}", my_code(), my_dev());
    let ok = crypto::verify(&pinned, msg.as_bytes(), sig);
    if !ok {
        dom::warn("v6 : ordre d'effacement non signé par le relais, ignoré");
        if !RELAY_WARNED.with(|w| w.replace(true)) {
            dom::toast(UNPROVEN_RELAY);
        }
    }
    ok
}

/// Le relais dit que cet appareil a été retiré du compte : tout est effacé.
pub fn on_unlinked() {
    spawn(async {
        dom::toast("Cet appareil a été retiré de votre compte Talka.");
        wipe_everything().await;
        dom::sleep(1500).await;
        let _ = dom::window().location().reload();
    });
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
    // Elle est propre à cet appareil : dans la liste des appareils (v4).
    let mut list = devices();
    let dev = my_dev();
    if let Some(d) = list.list.iter_mut().find(|d| d.dev == dev) {
        d.nk = nk;
        list.v += 1;
        devices_changed(list, true).await;
    } else {
        announce_profile().await;
    }
}

/// L'aperçu d'une notification pour le destinataire (voir
/// [`Wire::apercu`]) : le texte comme la liste des discussions.
pub fn apercu_of(me: &str, name: &str, wire: &Wire) -> Option<String> {
    wire.apercu(me, name, preview)
}

/// Notre jeton de remise, pour les contacts qui n'ont pas encore le leur
/// (une fois) : ils pourront nous écrire sans que le relais sache qui
/// écrit. Chacun a le sien ; l'ancien jeton commun est révoqué.
async fn share_jeton() {
    for mut c in contacts() {
        if c.saved && !c.blocked && !c.jeton_perso {
            c.jeton_perso = true;
            c.jeton_sent = true;
            save_contact(c.clone()).await;
            enqueue(&c.code, Wire::new(profile_body(&c.code)), None, None, false).await;
        }
    }
    let common = me().jeton();
    if let Some((nonce, _)) = common.split_once('.') {
        revoke_jeton(nonce).await;
    }
    send_revoked().await;
}

/// Les nonces de nos jetons révoqués (contacts bloqués, ancien jeton
/// commun), redonnés au relais à chaque connexion (il ne les garde qu'en
/// mémoire). Les 1 000 derniers.
async fn revoke_jeton(nonce: &str) {
    let mut list: Vec<String> = store::read("kv", "jetons_revoques")
        .await
        .unwrap_or_default();
    if list.iter().any(|n| n == nonce) {
        return;
    }
    list.push(nonce.to_owned());
    if list.len() > 1_000 {
        list.drain(..list.len() - 1_000);
    }
    store::write("kv", "jetons_revoques", &list).await;
}

async fn send_revoked() {
    let list: Vec<String> = store::read("kv", "jetons_revoques")
        .await
        .unwrap_or_default();
    if !list.is_empty() {
        net::revoke(&list);
    }
}

// ----- Contacts --------------------------------------------------------------

// La mémoire d'abord, IndexedDB ensuite : une lecture faite pendant
// l'écriture voit déjà la nouvelle valeur.
async fn save_contact(c: Contact) {
    let code = c.code.clone();
    let before = with(|s| s.contacts.insert(code.clone(), c.clone()));
    store::write("contacts", &code, &c).await;
    // Un correspondant dont on connaît les clés (ou qu'on débloque) : ses
    // boîtes tournantes sont à lire (v4).
    if before.is_none_or(|b| b.dh != c.dh || b.blocked != c.blocked) && !c.dh.is_empty() {
        spawn(refresh_boxes());
    }
    emit(Ev::Contacts(code));
}

/// Les clés d'un code, vérifiées : le code doit être leur empreinte.
async fn keys_for(code: &str) -> Option<Contact> {
    let c = fetch_keys(code).await?;
    if contact(code).is_none_or(|k| k.sign.is_empty()) {
        save_contact(c.clone()).await;
    }
    Some(c)
}

/// Les clés d'un code (gardées, ou demandées au relais et vérifiées), sans
/// rien enregistrer : un message scellé n'ajoute son expéditeur qu'une fois
/// prouvé (l'enveloppe intérieure ouverte).
async fn fetch_keys(code: &str) -> Option<Contact> {
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
    // Le lien d'invitation donnait l'empreinte de ses clés : ce ne sont pas
    // elles. Quelqu'un d'autre se présente sous ce code ; on n'en veut pas.
    if !c.fp.is_empty() && crypto::fingerprint(&sign, &dh) != c.fp {
        // Une fois par session (pas à chaque reconnexion).
        if MISMATCH.with(|m| m.borrow_mut().insert(code.to_owned())) {
            dom::toast(format!(
                "Attention : les clés de {} ne correspondent pas à son lien d'invitation. Quelqu'un se fait peut-être passer pour lui.",
                crypto::pretty(code)
            ));
        }
        return None;
    }
    c.sign = sign;
    c.dh = dh;
    Some(c)
}

async fn refresh_missing_keys() {
    for c in contacts() {
        if c.sign.is_empty() && keys_for(&c.code).await.is_some() {
            spawn(flush());
        }
    }
}

thread_local! {
    /// Les codes dont les clés trouvées ne sont pas celles du lien.
    static MISMATCH: RefCell<HashSet<String>> = RefCell::new(HashSet::new());
    /// Les empreintes des liens d'invitation ouverts, par code.
    static FINGERPRINTS: RefCell<HashMap<String, String>> = RefCell::new(HashMap::new());
}

/// Un lien d'invitation donne l'empreinte des clés de ce code : elle sera
/// vérifiée quand il sera ajouté.
pub fn expect_fingerprint(code: &str, fp: &str) {
    if crypto::valid_fingerprint(fp) {
        FINGERPRINTS.with(|f| f.borrow_mut().insert(code.to_owned(), fp.to_owned()));
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
    if let Some(fp) = FINGERPRINTS.with(|f| f.borrow_mut().remove(code)) {
        if !c.sign.is_empty() && crypto::fingerprint(&c.sign, &c.dh) != fp {
            return Err("Les clés de ce code ne correspondent pas au lien d'invitation : quelqu'un se fait peut-être passer pour lui.".into());
        }
        c.fp = fp;
    }
    c.saved = true;
    // Notre profil part avec son jeton personnel.
    c.jeton_perso = !c.blocked;
    c.jeton_sent = !c.blocked;
    let name: String = name.trim().chars().take(40).collect();
    if !name.is_empty() {
        c.name = name;
    }
    save_contact(c).await;
    net::watch(&[code.to_owned()]);
    let reachable = keys_for(code).await.is_some();
    // Les clés trouvées ne sont pas celles du lien : on ne l'ajoute pas (et
    // on ne lui envoie rien).
    if !reachable && MISMATCH.with(|m| m.borrow().contains(code)) {
        update_contact(code, |c| c.saved = false).await;
        return Err(format!(
            "Les clés de {} ne correspondent pas à son lien d'invitation : quelqu'un se fait peut-être passer pour lui. Contact non ajouté.",
            crypto::pretty(code)
        ));
    }
    enqueue(code, Wire::new(profile_body(code)), None, None, false).await;
    spawn(flush());
    if let Some(c) = contact(code) {
        sync_contact(&c).await;
    }
    Ok(reachable)
}

pub async fn update_contact(code: &str, f: impl FnOnce(&mut Contact)) {
    if let Some(mut c) = contact(code) {
        let before = (c.name.clone(), c.saved, c.blocked);
        f(&mut c);
        let changed = before != (c.name.clone(), c.saved, c.blocked);
        save_contact(c.clone()).await;
        emit(Ev::Chats);
        if changed {
            sync_contact(&c).await;
        }
    }
}

/// Un contact ajouté, renommé, bloqué : pareil sur nos autres appareils (v4).
async fn sync_contact(c: &Contact) {
    sync(
        "contact",
        "",
        None,
        serde_json::json!({
            "code": c.code, "name": c.name, "saved": c.saved, "blocked": c.blocked,
            "sign": c.sign, "dh": c.dh,
        }),
    )
    .await;
}

pub async fn set_blocked(code: &str, blocked: bool) {
    let mut c = contact(code).unwrap_or_else(|| Contact {
        code: code.to_owned(),
        added_at: now(),
        ..Default::default()
    });
    // Bloqué : son jeton de remise est révoqué (il ne peut plus déposer
    // anonymement) ; débloqué, il en recevra un nouveau.
    if blocked && !c.blocked && c.jeton_perso {
        revoke_jeton(&me().jeton_nonce(code, c.jeton_epoch)).await;
        c.jeton_epoch += 1;
        c.jeton_perso = false;
        send_revoked().await;
    }
    let unblocked = !blocked && c.blocked;
    let changed = c.blocked != blocked;
    c.blocked = blocked;
    save_contact(c.clone()).await;
    emit(Ev::Chats);
    if unblocked && net::sealed_supported() {
        spawn(share_jeton());
    }
    if changed {
        sync_contact(&c).await;
    }
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
        let before = ChatState::of(&c);
        f(&mut c);
        let after = ChatState::of(&c);
        save_chat(c).await;
        // Archivée, épinglée, en sourdine… : pareil sur nos autres appareils.
        if before != after {
            sync(
                "chat",
                id,
                None,
                serde_json::to_value(after).unwrap_or_default(),
            )
            .await;
        }
    }
}

/// Ce qui, d'une discussion, est le même sur tous nos appareils (v4).
#[derive(Clone, Debug, Default, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(default)]
struct ChatState {
    marked_unread: bool,
    pinned: bool,
    archived: bool,
    muted: bool,
    mute_until: f64,
    wallpaper: String,
    pin_msg: String,
}

impl ChatState {
    fn of(c: &Chat) -> ChatState {
        ChatState {
            marked_unread: c.marked_unread,
            pinned: c.pinned,
            archived: c.archived,
            muted: c.muted,
            mute_until: c.mute_until,
            wallpaper: c.wallpaper.clone(),
            pin_msg: c.pin_msg.clone(),
        }
    }

    fn apply(self, c: &mut Chat) {
        c.marked_unread = self.marked_unread;
        c.pinned = self.pinned;
        c.archived = self.archived;
        c.muted = self.muted;
        c.mute_until = self.mute_until;
        c.wallpaper = self.wallpaper.chars().take(200).collect();
        c.pin_msg = self.pin_msg;
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

/// Ouvre les jetons de `text` reçus dans la discussion `chat_id` : avec les
/// clés liées à cette discussion, et celles liées à aucune. Pas avec la clé
/// partagée avec quelqu'un d'autre : un contact qui renverrait un jeton
/// capturé ailleurs ne le ferait pas ouvrir (ni recopier en clair dans une
/// réponse).
pub fn open_brocolis(chat_id: &str, text: &str) -> jetons::Opened {
    let keys = with(|s| {
        let used: HashSet<&String> = s.chats.values().flat_map(|c| c.brocolis.iter()).collect();
        let here: HashSet<&String> = s
            .chats
            .get(chat_id)
            .map(|c| c.brocolis.iter().collect())
            .unwrap_or_default();
        s.brocolis
            .iter()
            .filter(|k| here.contains(&k.id) || !used.contains(&k.id))
            .cloned()
            .collect::<Vec<_>>()
    });
    jetons::open(text, &raw_keys(&keys))
}

/// Ouvre les jetons d'un message et de sa citation ; `true` si l'un l'a été.
fn open_message(m: &mut Message) -> bool {
    let mut changed = false;
    let chat_id = m.chat.clone();
    if jetons::has_token(&m.text) {
        let o = open_brocolis(&chat_id, &m.text);
        if o.opened > 0 {
            m.text = o.text;
            m.bro = true;
            changed = true;
        }
    }
    if let Some(r) = m.reply.as_mut()
        && jetons::has_token(&r.text)
    {
        let o = open_brocolis(&chat_id, &r.text);
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

// ----- Les pierres tombales -----------------------------------------------------
//
// Un message effacé (pour moi, éphémère, trop vieux, discussion vidée) ne
// doit pas revenir si un relais rejouait sa vieille enveloppe dans l'ancien
// format (sans protection contre le rejeu). On garde son identifiant 31 jours
// (le relais ne garde rien plus de 30 jours ; un message plus vieux dans
// l'ancien format est refusé d'office).

/// La fenêtre des pierres tombales (et l'âge au-delà duquel un message dans
/// l'ancien format est refusé).
pub const TOMB_WINDOW: f64 = 31.0 * DAY;

async fn bury(chat: &str, msgs: impl IntoIterator<Item = (String, f64)>) {
    let t = now();
    let key = format!("tombes:{chat}");
    let mut list: Vec<(String, f64)> = store::read("kv", &key).await.unwrap_or_default();
    let before = list.len();
    list.retain(|(_, at)| t - at < TOMB_WINDOW);
    let mut changed = list.len() != before;
    for (id, at) in msgs {
        if t - at < TOMB_WINDOW && !list.iter().any(|(i, _)| *i == id) {
            list.push((id, at));
            changed = true;
        }
    }
    if list.len() > 20_000 {
        list.drain(..list.len() - 20_000);
    }
    if !changed {
        return;
    }
    if list.is_empty() {
        store::remove("kv", &key).await;
    } else {
        store::write("kv", &key, &list).await;
    }
}

/// Ce message a-t-il été effacé ici (dans les 31 derniers jours) ?
async fn buried(chat: &str, id: &str) -> bool {
    store::read::<Vec<(String, f64)>>("kv", &format!("tombes:{chat}"))
        .await
        .is_some_and(|l| l.iter().any(|(i, _)| i == id))
}

/// Une offre d'appel jamais vue (les 200 dernières sont gardées) ?
async fn first_offer(call_id: &str) -> bool {
    let mut seen: Vec<String> = store::read("kv", "appels_vus").await.unwrap_or_default();
    if seen.iter().any(|c| c == call_id) {
        return false;
    }
    seen.push(call_id.to_owned());
    if seen.len() > 200 {
        seen.drain(..seen.len() - 200);
    }
    store::write("kv", "appels_vus", &seen).await;
    true
}

/// Les messages récents d'une discussion qu'on va vider : enterrés.
async fn bury_all(chat: &str) {
    let t = now();
    let recent: Vec<(String, f64)> = store::messages_of(chat, usize::MAX)
        .await
        .into_iter()
        .filter(|m| t - m.at < TOMB_WINDOW)
        .map(|m| (m.id, m.at))
        .collect();
    bury(chat, recent).await;
}

pub async fn delete_chat(id: &str) {
    drop_chat(id).await;
    sync("chat", id, None, serde_json::json!({ "deleted": true })).await;
}

async fn drop_chat(id: &str) {
    bury_all(id).await;
    store::delete_messages_of(id).await;
    store::remove("chats", id).await;
    with(|s| s.chats.remove(id));
    emit(Ev::Chats);
}

/// Vide une discussion ; `keep_starred` : sauf les messages favoris (comme
/// WhatsApp). Nos autres appareils font de même.
pub async fn clear_chat(id: &str, keep_starred: bool) {
    if keep_starred {
        empty_chat_but_starred(id).await;
    } else {
        empty_chat(id).await;
    }
    sync(
        "chat",
        id,
        None,
        serde_json::json!({ "cleared": true, "keep_starred": keep_starred }),
    )
    .await;
}

/// La discussion a-t-elle des messages favoris ?
pub async fn has_starred(id: &str) -> bool {
    store::messages_of(id, usize::MAX)
        .await
        .iter()
        .any(Message::is_starred)
}

/// Efface tout sauf les messages favoris.
async fn empty_chat_but_starred(id: &str) {
    let t = now();
    let mut gone = Vec::new();
    for m in store::messages_of(id, usize::MAX).await {
        if !m.is_starred() {
            store::remove("messages", &m.key()).await;
            if t - m.at < TOMB_WINDOW {
                gone.push((m.id.clone(), m.at));
            }
        }
    }
    bury(id, gone).await;
    refresh_last(id).await;
    if let Some(mut c) = chat(id) {
        c.unread = 0;
        c.mentioned = false;
        save_chat(c).await;
    }
    emit(Ev::Messages(id.to_owned()));
}

async fn empty_chat(id: &str) {
    bury_all(id).await;
    store::delete_messages_of(id).await;
    if let Some(mut c) = chat(id) {
        c.last = None;
        c.unread = 0;
        c.mentioned = false;
        save_chat(c).await;
    }
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
        if c.unarchives_on(&m.from, &my_code(), settings().keep_archived) {
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
        ..Default::default()
    };
    if live {
        let mut item = item;
        let _ = send_now(&mut item, true).await;
        return;
    }
    store::write("outbox", &item.id, &item).await;
    spawn(flush());
}

/// Comme `enqueue`, pour un seul appareil du destinataire (v4) : les autres
/// sont marqués comme déjà servis.
async fn enqueue_to(to: &str, dev: Option<&str>, wire: Wire) {
    let Ok(payload) = serde_json::to_string(&wire) else {
        return;
    };
    let sent = match dev {
        Some(dev) => targets(to).into_iter().filter(|d| d != dev).collect(),
        None => Vec::new(),
    };
    let item = OutItem {
        id: format!("{}:{to}", crypto::random_id(10)),
        to: to.to_owned(),
        payload,
        seq: now() * 1000.0,
        sent,
        ..Default::default()
    };
    store::write("outbox", &item.id, &item).await;
    spawn(flush());
}

/// Les appareils où écrire à `code` : chacun des siens (v4, sa dernière
/// liste annoncée), ou le code seul (vide, avant la v4). Pour nous-mêmes :
/// nos autres appareils.
fn targets(code: &str) -> Vec<String> {
    if code == my_code() {
        return siblings();
    }
    match contact(code) {
        Some(c) if !c.devs.is_empty() => c.devs.iter().map(|d| d.d.clone()).collect(),
        _ => vec![String::new()],
    }
}

/// L'adresse d'une session v3 : `<code>/<dev>` (v4), ou le code.
fn address(code: &str, dev: &str) -> String {
    if dev.is_empty() {
        code.to_owned()
    } else {
        format!("{code}/{dev}")
    }
}

/// Envoie un élément de la boîte d'envoi : une copie par appareil du
/// destinataire (v4), chacune chiffrée par sa propre session. Les appareils
/// déjà servis (`item.sent`) ne la reçoivent pas deux fois.
async fn send_now(item: &mut OutItem, live: bool) -> Result<(), String> {
    let mine = item.to == my_code();
    let peer = if mine {
        let me = me();
        Contact {
            code: me.code.clone(),
            sign: me.sign.clone(),
            dh: me.dh.clone(),
            pj: true,
            ..Default::default()
        }
    } else {
        keys_for(&item.to).await.ok_or("unknown")?
    };
    let mut failure: Option<String> = None;
    for dev in targets(&item.to) {
        if item.sent.contains(&dev) {
            continue;
        }
        match send_one(item, &peer, &dev, mine, live).await {
            Ok(()) => item.sent.push(dev),
            // Le relais ne connaît plus cet appareil (retiré) : on n'insiste
            // pas.
            Err(e) if e == "dev" => item.sent.push(dev),
            Err(e) => {
                failure.get_or_insert(e);
            }
        }
    }
    match failure {
        Some(e) => Err(e),
        None => Ok(()),
    }
}

/// Une copie, pour un appareil (`dev` vide : le code, avant la v4).
async fn send_one(
    item: &OutItem,
    peer: &Contact,
    dev: &str,
    mine: bool,
    live: bool,
) -> Result<(), String> {
    let addr = address(&peer.code, dev);
    load_v3(&addr).await;
    // « écrit… » et les signaux d'appel ne sont remis qu'à un destinataire
    // en ligne : hors ligne, le relais les jette, et chacun laisserait chez
    // lui une clé sautée dans la session. Ils gardent alors l'ancien format.
    let ratchet = !live || mine || presence(&peer.code).is_some_and(|(online, _)| online);
    // Sans session, le paquet de l'appareil en ouvre une (s'il en a publié un).
    let bundle = if !ratchet || with_v3(|e| e.has_session(&addr)).unwrap_or(true) {
        None
    } else {
        bundle_for(peer, dev).await
    };
    // Une pièce jointe trop grosse pour un jeton v3, vers un contact dont la
    // version le sait : chiffrée à part, sa clé dans le message v3.
    let (payload, blob) = match (ratchet && peer.pj && item.payload.len() > v3::MAX_PLAINTEXT)
        .then(|| split_attachment(&item.payload))
        .flatten()
    {
        Some((small, blob)) => (small, Some(blob)),
        None => (item.payload.clone(), None),
    };
    // Chiffré au moment de partir : un nouvel essai chiffre à nouveau (un
    // autre numéro de message) ; le destinataire range par identifiant.
    let v3 = ratchet
        .then(|| {
            with_v3(|e| {
                e.maintain_pq(v3::secs(now()));
                e.seal_to(
                    &me(),
                    &addr,
                    &peer.code,
                    &peer.dh,
                    bundle.as_ref(),
                    &payload,
                )
            })
        })
        .flatten();
    let sealed = match v3 {
        Some(sealed) => sealed,
        None => crypto::seal(&me(), &peer.code, &peer.dh, &payload)
            .map(|body| v3::Sealed { body, v3: false }),
    }
    .ok_or("crypto")?;
    // Un contact dont la version refuse l'ancien format (un relais pourrait
    // le rejouer) : sans session ni paquet, on attend qu'il en publie un.
    if ratchet && peer.pj && !sealed.v3 && payload.len() <= v3::MAX_PLAINTEXT {
        return Err("v3".into());
    }
    if sealed.v3 {
        // La session a avancé : rien ne part avant qu'elle soit écrite (une
        // clé de message ne doit jamais servir deux fois).
        save_v3().await;
        store::committed().await;
    }
    let body = match blob {
        Some(blob) => format!("{}|{}", sealed.body, crypto::b64(&blob)),
        None => sealed.body,
    };
    // Un identifiant par copie (le relais répond copie par copie).
    let id = if dev.is_empty() {
        item.id.clone()
    } else {
        format!("{}#{dev}", item.id)
    };
    // Nos propres appareils : le relais sait déjà qui nous sommes.
    if mine {
        return match net::send(&id, &peer.code, dev, &body, live, false, None).await {
            Ack::Ok => Ok(()),
            Ack::Err(e) => Err(e),
        };
    }
    // Les messages écrits (pas les accusés, réactions, profils) réveillent
    // un destinataire hors ligne.
    let wake = !live && !item.msg_id.is_empty();
    // Et, s'il a donné sa clé de notification (celle de cet appareil en
    // v4), avec un aperçu chiffré pour elle : le relais le transmet sans
    // pouvoir le lire.
    let nk = if dev.is_empty() {
        peer.nk.clone()
    } else {
        peer.devs
            .iter()
            .find(|d| d.d == dev)
            .map(|d| d.nk.clone())
            .unwrap_or_default()
    };
    let apercu = (wake && !nk.is_empty())
        .then(|| serde_json::from_str::<Wire>(&item.payload).ok())
        .flatten()
        .and_then(|w| apercu_of(&my_code(), &profile().name, &w))
        .and_then(|a| crypto::seal_apercu(&me(), &nk, &a));
    // Une boîte tournante (v4) : le relais ne sait ni qui écrit, ni à qui.
    if peer.bx && !dev.is_empty() && net::sealed_supported() {
        let key = crypto::boites_key(&me(), &peer.code, &peer.dh).ok_or("crypto")?;
        let boite = crypto::boite(&key, &peer.code, dev, crypto::day(net::server_now()));
        let outer = crypto::seal_sender_from(&me().code, &my_dev(), &peer.code, &peer.dh, &body)
            .ok_or("crypto")?;
        return match net::send_boite(&id, &boite, &outer, live, wake, apercu.as_deref(), None).await
        {
            Ack::Ok => Ok(()),
            Ack::Err(e) => Err(e),
        };
    }
    // L'expéditeur scellé : le relais ne sait pas qui écrit (une connexion
    // anonyme, et le jeton de remise du destinataire).
    if !peer.jeton.is_empty() && net::sealed_supported() {
        let outer = crypto::seal_sender_from(&me().code, &my_dev(), &peer.code, &peer.dh, &body)
            .ok_or("crypto")?;
        match net::send_sealed(&id, peer, dev, &outer, live, wake, apercu.as_deref()).await {
            Ack::Ok => return Ok(()),
            // Son jeton ne vaut plus : on l'oublie, et cet envoi part comme avant.
            Ack::Err(e) if e == "jeton" => {
                if let Some(mut c) = contact(&peer.code) {
                    c.jeton.clear();
                    save_contact(c).await;
                }
            }
            // Pas de repli silencieux : l'envoi réessaiera, toujours scellé.
            Ack::Err(e) => return Err(e),
        }
    }
    match net::send(&id, &item.to, dev, &body, live, wake, apercu.as_deref()).await {
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
                // Le relais a redémarré : ce qui n'a pas été reçu repart
                // (seulement vers les appareils qui ne l'ont pas).
                let got = item.got.clone();
                item.sent.retain(|d| got.contains(d));
            }
            let before = item.sent.len();
            let result = send_now(&mut item, false).await;
            // Les appareils déjà servis sont retenus, même si un autre attend.
            if result.is_err() && item.sent.len() != before {
                store::write("outbox", &item.id, &item).await;
            }
            match result {
                Ok(()) => {
                    if !item.msg_id.is_empty() && !boot.is_empty() {
                        item.boot = boot.clone();
                        store::write("outbox", &item.id, &item).await;
                    } else {
                        store::remove("outbox", &item.id).await;
                    }
                    if !item.msg_id.is_empty() && item.to != my_code() {
                        mark_sent(&item.chat, &item.msg_id, &item.to).await;
                    }
                }
                Err(e) if e == "size" || e == "code" => {
                    store::remove("outbox", &item.id).await;
                    if !item.msg_id.is_empty() && item.to != my_code() {
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
    // Pour nos autres appareils : chacun dit l'avoir reçu (`sync`/`got`).
    if item.to == my_code() {
        let pending = siblings().iter().any(|d| !item.got.contains(d));
        return pending
            && get_message(&item.chat, &item.msg_id)
                .await
                .is_some_and(|m| !m.deleted && now() - m.at < 30.0 * 24.0 * 3600.0 * 1000.0);
    }
    // v4 : chaque appareil du destinataire doit l'avoir reçu.
    let devs: Vec<String> = targets(&item.to)
        .into_iter()
        .filter(|d| !d.is_empty())
        .collect();
    get_message(&item.chat, &item.msg_id)
        .await
        .is_some_and(|m| {
            let missing = if devs.is_empty() {
                !m.delivered_to.iter().any(|c| c == &item.to)
            } else {
                devs.iter().any(|d| !item.got.contains(d))
            };
            !m.deleted && missing && now() - m.at < 30.0 * 24.0 * 3600.0 * 1000.0
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
        desc: Some(chat.desc.clone()),
        admins_only: Some(chat.admins_only),
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
        mentions: plain.mentions,
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
    // Nos autres appareils l'affichent aussi (v4) : gardé, comme pour un
    // destinataire, jusqu'à ce que chacun l'ait (et renvoyé si le relais
    // redémarre entre-temps).
    if !siblings().is_empty() {
        let w = Wire::new(Body::Sync {
            op: "wire".into(),
            c: chat_id.to_owned(),
            w: Some(Box::new(wire)),
            d: Value::Null,
        });
        enqueue(&my_code(), w, Some(chat_id), Some(&id), false).await;
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
    // Ce qui change la discussion (réaction, modification, suppression,
    // minuterie, groupe) : refait sur nos autres appareils (v4).
    if !live && !matches!(wire.body, Body::Typing { .. } | Body::Group { .. }) {
        sync("wire", &c.id, Some(wire), Value::Null).await;
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
        m.starred = false;
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
        bury(chat_id, [(m.id.clone(), m.at)]).await;
        sync(
            "forget",
            chat_id,
            None,
            serde_json::json!({ "ids": [m.id] }),
        )
        .await;
    }
    refresh_last(chat_id).await;
    emit(Ev::Messages(chat_id.to_owned()));
}

pub async fn toggle_star(chat_id: &str, msg_id: &str) {
    if let Some(mut m) = get_message(chat_id, msg_id).await {
        m.starred = !m.starred;
        store::write_message(&m).await;
        emit(Ev::Messages(chat_id.to_owned()));
        sync(
            "star",
            chat_id,
            None,
            serde_json::json!({ "id": msg_id, "on": m.starred }),
        )
        .await;
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

/// Les messages favoris d'une discussion, les plus récents d'abord.
pub async fn starred_in(chat_id: &str) -> Vec<Message> {
    let mut list: Vec<Message> = store::messages_of(chat_id, usize::MAX)
        .await
        .into_iter()
        .filter(Message::is_starred)
        .collect();
    list.sort_by(|a, b| b.at.total_cmp(&a.at));
    list
}

pub async fn starred() -> Vec<Message> {
    let mut list: Vec<Message> = store::all::<Message>("messages")
        .await
        .into_iter()
        .filter(Message::is_starred)
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
        // Une mention ne suit pas un message transféré.
        mentions: Vec::new(),
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
    sync_group(&c, &format!("Vous avez créé le groupe « {} ».", c.name)).await;
    c
}

/// Un groupe changé ici : nos autres appareils le changent aussi (v4).
async fn sync_group(c: &Chat, note: &str) {
    sync(
        "group",
        &c.id,
        None,
        serde_json::json!({ "grp": group_info(c), "left": c.left, "note": note }),
    )
    .await;
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
    sync_group(&next, note).await;
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
    save_chat(c.clone()).await;
    system_message(chat_id, "Vous avez quitté le groupe.", now()).await;
    sync_group(&c, "Vous avez quitté le groupe.").await;
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
                    dev: my_dev(),
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
    let fresh = !by_sender.is_empty();
    if settings().read_receipts {
        for (code, ids) in by_sender {
            queue_receipt(&code, chat_id, &ids, "read");
        }
    }
    if c.unread > 0 || c.marked_unread || c.mentioned {
        update_chat(chat_id, |c| {
            c.unread = 0;
            c.marked_unread = false;
            c.mentioned = false;
        })
        .await;
    }
    // Lu ici : lu sur nos autres appareils (sans nouvel accusé).
    if fresh {
        sync(
            "read",
            chat_id,
            None,
            serde_json::json!({ "at": net::server_now() }),
        )
        .await;
    }
}

/// Lu sur un autre de nos appareils : jusqu'à `at`, sans accusé.
async fn read_here(chat_id: &str, at: f64) {
    let Some(c) = chat(chat_id) else { return };
    let me = my_code();
    for mut m in store::messages_of(chat_id, 300).await {
        if m.from != me && m.from != "system" && !m.read_by_me && m.at <= at + 60_000.0 {
            m.read_by_me = true;
            store::write_message(&m).await;
        }
    }
    if c.unread > 0 || c.mentioned {
        let mut c = c;
        c.unread = 0;
        c.mentioned = false;
        save_chat(c).await;
    }
    emit(Ev::Messages(chat_id.to_owned()));
}

/// Une enveloppe arrive du relais.
pub fn on_envelope(env: Value) {
    spawn(async move {
        let n = env["n"].as_u64();
        receive(&env, None).await;
        if let Some(n) = n {
            // Le relais oublie le message une fois rangé pour de bon (avec
            // l'état de session qui l'a ouvert).
            store::committed().await;
            net::got(&[n]);
        }
    });
}

/// Une enveloppe arrive d'une boîte tournante (v4) : ou d'une liaison en
/// cours.
pub fn on_box_envelope(env: Value) {
    spawn(async move {
        let n = env["n"].as_u64();
        let boite = env["boite"].as_str().unwrap_or("").to_owned();
        if LINKING.with(|l| l.borrow().is_some()) {
            link_chunk(&env).await;
        } else if let Some(contact) = BOXES.with(|b| b.borrow().get(&boite).cloned())
            // Une boîte de couverture : rien à lire, seulement à oublier.
            && !contact.is_empty()
        {
            receive(&env, Some(&contact)).await;
        }
        if let Some(n) = n {
            store::committed().await;
            net::box_got(&[n]);
        }
    });
}

/// `boite` : le contact dont c'est la boîte tournante (l'enveloppe doit
/// venir de lui).
async fn receive(env: &Value, boite: Option<&str>) {
    // Scellé : l'expéditeur est dans l'enveloppe, que nous seuls ouvrons ;
    // l'enveloppe intérieure le prouve ensuite (sa clé de paire).
    let unsealed = if env["sealed"].as_bool() == Some(true) {
        let Some(opened) = crypto::open_sealed_from(&me(), env["body"].as_str().unwrap_or(""))
        else {
            return;
        };
        Some(opened)
    } else {
        None
    };
    let (from, fd) = match &unsealed {
        Some((from, fd, _)) => (from.clone(), fd.clone()),
        None => (
            env["from"].as_str().unwrap_or("").to_owned(),
            env["fd"]
                .as_str()
                .filter(|d| crypto::is_dev(d))
                .unwrap_or("")
                .to_owned(),
        ),
    };
    if from.is_empty() || blocked(&from) || boite.is_some_and(|b| b != from) {
        return;
    }
    // De nous-mêmes : seulement d'un autre de nos appareils.
    let mine = from == my_code();
    if mine && (fd.is_empty() || fd == my_dev() || !devices().has(&fd)) {
        return;
    }
    let peer = if mine {
        let me = me();
        Contact {
            code: me.code.clone(),
            sign: me.sign.clone(),
            dh: me.dh.clone(),
            pj: true,
            v3: true,
            ..Default::default()
        }
    } else {
        let Some(peer) = fetch_keys(&from).await else {
            return;
        };
        peer
    };
    let addr = address(&from, &fd);
    load_v3(&addr).await;
    // Une session ouverte avant de connaître ses appareils (à son code).
    load_v3(&from).await;
    let full = match &unsealed {
        Some((_, _, body)) => body.as_str(),
        None => env["body"].as_str().unwrap_or(""),
    };
    // Une pièce jointe chiffrée à part : `<enveloppe>|<pièce>`.
    let (body, blob) = match full.split_once('|') {
        Some((body, blob)) => (body, crypto::unb64(blob)),
        None => (full, None),
    };
    // Le message, et l'état de session qui l'a ouvert : une seule
    // transaction IndexedDB.
    let _batch = store::batch();
    let opened =
        with_v3(|e| e.open_from(&me(), &addr, &peer.code, &peer.dh, body, v3::secs(now())))
            .unwrap_or_else(|| match crypto::open(&me(), &peer.code, &peer.dh, body) {
                Some(plain) => Opened::Legacy(plain),
                None => Opened::Failed(Failure::Envelope),
            });
    save_v3().await;
    let legacy = matches!(opened, Opened::Legacy(_));
    let plain = match opened {
        Opened::Legacy(plain) | Opened::V3(plain) => plain,
        // Déjà lu : le relais l'a renvoyé.
        Opened::Replay => return,
        Opened::Failed(Failure::Envelope) => return, // pas pour nous, ou abîmé
        Opened::Failed(Failure::V3(e)) => {
            dom::warn(&format!("v3 : message de {addr} illisible ({e})"));
            if v3::needs_repair(&e) {
                repair_v3(&from, &fd);
            }
            return;
        }
    };
    // Un appareil dont le relais n'avait pas de paquet (un appareil qu'on
    // vient de lier) : ouvert en v3, il en a un maintenant. Ce qui
    // l'attendait part, sans attendre la fin des dix minutes.
    if !legacy && BUNDLES.with(|b| b.borrow().get(&addr).is_some_and(|(b, _)| b.is_none())) {
        BUNDLES.with(|b| b.borrow_mut().remove(&addr));
        spawn(flush());
    }
    // De nos appareils : seulement de la synchronisation, en v3.
    if mine {
        if legacy {
            return;
        }
        if let Ok(wire) = serde_json::from_str::<Wire>(&join_attachment(&plain, blob.as_deref()))
            && let Body::Sync { op, c, w, d } = wire.body
        {
            handle_sync(&fd, &op, &c, w.map(|w| *w), d).await;
        }
        return;
    }
    // Prouvé : ses clés sont gardées (s'il est nouveau), et son passage en v3.
    let known = contact(&from);
    if known
        .as_ref()
        .is_none_or(|c| c.sign.is_empty() || (!legacy && !c.v3))
    {
        let mut c = known.clone().unwrap_or_else(|| peer.clone());
        c.sign = peer.sign.clone();
        c.dh = peer.dh.clone();
        c.v3 |= !legacy;
        save_contact(c).await;
    }
    // La pièce jointe chiffrée à part (seulement en v3).
    let plain = if legacy {
        plain
    } else {
        join_attachment(&plain, blob.as_deref())
    };
    let Ok(wire) = serde_json::from_str::<Wire>(&plain) else {
        return;
    };
    let live = env["live"].as_bool().unwrap_or(false);
    // L'ancien format n'a pas de protection contre le rejeu :
    // - d'un contact dont la version chiffre tout en v3, il est refusé (hors
    //   signaux en direct) ;
    // - d'un contact passé en v3, il ne change plus d'état (minuterie,
    //   modification, suppression, groupe, appel) ;
    // - un message de plus de 31 jours est refusé (le relais ne garde rien
    //   plus de 30 jours : c'est un rejeu).
    if legacy && !live {
        let refuse = match &known {
            Some(c) if c.pj => true,
            Some(c) if c.v3 => wire.body.changes_state(),
            _ => false,
        } || matches!(&wire.body, Body::Msg { at, .. } if now() - at > TOMB_WINDOW);
        if refuse {
            dom::warn(&format!("ancien format refusé de {from}"));
            return;
        }
    }
    let ts = env["ts"].as_f64().unwrap_or_else(now);
    handle(&from, wire, ts, live).await;
}

/// La discussion d'un groupe, créée ou mise à jour par `grp` ; `true` si
/// cette description a été appliquée (un groupe ne change que par ses
/// admins, voir `Chat::accepts`).
async fn group_chat(from: &str, grp: &GroupInfo, at: f64) -> (Option<Chat>, bool) {
    let id = format!("g:{}", grp.id);
    let me = my_code();
    match chat(&id) {
        None => {
            // Un nouveau groupe : créé par un de ses admins, qui nous y met.
            if !grp.members.contains(&me)
                || !grp.admins.iter().any(|a| a == from)
                || !grp.members.iter().any(|m| m == from)
            {
                return (None, false);
            }
            let c = Chat {
                id: id.clone(),
                group: true,
                name: grp.name.clone(),
                avatar: safe_data("avatar", &grp.avatar),
                members: grp.members.clone(),
                admins: grp.admins.clone(),
                desc: grp.desc.as_deref().map(group_desc).unwrap_or_default(),
                admins_only: grp.admins_only.unwrap_or(false),
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
            (Some(c), true)
        }
        Some(mut c) if c.accepts(from, grp) => {
            let was_in = c.members.contains(&me);
            c.name = grp.name.clone();
            c.avatar = safe_data("avatar", &grp.avatar);
            c.members = grp.members.clone();
            c.admins = grp.admins.clone();
            if let Some(d) = &grp.desc {
                c.desc = group_desc(d);
            }
            if let Some(a) = grp.admins_only {
                c.admins_only = a;
            }
            c.v = grp.v;
            if grp.members.contains(&me) {
                c.left = false;
            } else if was_in {
                c.left = true;
            }
            save_chat(c.clone()).await;
            (Some(c), true)
        }
        other => (other, false),
    }
}

async fn handle(from: &str, wire: Wire, ts: f64, live: bool) {
    let me = my_code();
    match &wire.body {
        Body::Profile {
            profile,
            jeton,
            nk,
            pj,
            devs,
            dv,
            bx,
        } => {
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
            // Sa version chiffre tout en v3 (une fois su, ça ne se perd pas).
            if *pj && !c.pj {
                c.pj = true;
                save_contact(c.clone()).await;
            }
            // Ses appareils (v4) : la liste la plus récente, bien formée.
            if *dv > c.dv
                && devs.len() <= MAX_DEVICES
                && devs
                    .iter()
                    .all(|d| crypto::is_dev(&d.d) && (d.nk.is_empty() || crypto::valid_nk(&d.nk)))
            {
                c.devs = devs.clone();
                c.dv = *dv;
                save_contact(c.clone()).await;
                // Un nouvel appareil : on vérifie son paquet au prochain envoi.
                BUNDLES.with(|b| {
                    b.borrow_mut()
                        .retain(|k, _| !k.starts_with(&format!("{from}/")))
                });
                spawn(refresh_boxes());
            }
            // Il lit ses boîtes tournantes (v4).
            if *bx != c.bx {
                c.bx = *bx;
                save_contact(c.clone()).await;
            }
            if profile.v >= c.profile_v {
                c.profile_name = profile.name.chars().take(40).collect();
                c.about = profile.about.chars().take(140).collect();
                c.avatar = if profile.avatar.len() <= 60_000 {
                    safe_data("avatar", &profile.avatar)
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
        Body::Call { op, call_id, .. } => {
            // Une offre d'appel déjà vue (un rejeu) ne fait pas sonner.
            if op == "offer" && !first_offer(call_id).await {
                return;
            }
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
                data: safe_data("image", data),
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
    let (c, applied) = match (&wire.grp, &wire.g) {
        (Some(grp), _) => {
            // L'annonce « vous a ajouté » se range juste avant le premier message.
            let at = match &wire.body {
                Body::Msg { at, .. } => at.min(ts) - 1.0,
                _ => ts,
            };
            group_chat(from, grp, at).await
        }
        (None, Some(g)) => (chat(&format!("g:{g}")), false),
        // Un message de groupe hors d'un groupe : ignoré.
        (None, None) if matches!(wire.body, Body::Group { .. }) => return,
        (None, None) => (Some(ensure_direct_chat(from).await), false),
    };
    let Some(mut c) = c else { return };
    // Dans un groupe, seuls ses membres parlent ; qui vient de le quitter
    // (sa description appliquée à l'instant) l'annonce encore.
    let leaving = applied && matches!(&wire.body, Body::Group { op, .. } if op == "leave");
    if c.group && !c.members.iter().any(|x| x == from) && !leaving {
        return;
    }

    match wire.body {
        Body::Msg {
            id,
            at,
            pn,
            content,
        } => {
            // Déjà reçu, ou effacé ici depuis (un rejeu ne le fait pas revenir).
            if get_message(&c.id, &id).await.is_some() || buried(&c.id, &id).await {
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
            // Une clé Brocolis partagée garde sa clé ; le reste, seulement des
            // données `data:` du bon type.
            let data = if shared_key {
                content.data
            } else {
                safe_data(&kind, &content.data)
            };
            let mut m = Message {
                chat: c.id.clone(),
                id: id.clone(),
                from: from.to_owned(),
                at,
                kind,
                text: content.text.chars().take(65_536).collect(),
                // Une clé Brocolis partagée garde sa clé ; le reste, seulement
                // des données `data:` du bon type.
                data,
                name: content.name,
                mime: content.mime,
                size: content.size,
                dur: content.dur,
                w: content.w,
                h: content.h,
                code: content.code,
                reply: content.reply,
                fwd: content.fwd,
                // Seulement des membres du groupe (50 au plus).
                mentions: if c.group {
                    content
                        .mentions
                        .iter()
                        .filter(|m| c.members.contains(m))
                        .take(50)
                        .cloned()
                        .collect()
                } else {
                    Vec::new()
                },
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
            // La citation : celle de notre copie du message cité, jamais ce
            // que l'expéditeur prétend (« Vous : je te dois 500 € »).
            if let Some(r) = m.reply.as_mut() {
                match get_message(&c.id, &r.id).await {
                    Some(orig) => {
                        r.from = orig.from.clone();
                        r.text = readable(&orig).chars().take(140).collect();
                    }
                    None => {
                        r.from = String::new();
                        r.text = r.text.chars().take(140).collect();
                    }
                }
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
                // Mentionné : « @ » dans la liste, jusqu'à la lecture.
                if m.mentions.contains(&me) {
                    c.mentioned = true;
                }
                save_chat(c.clone()).await;
            }
            store_message(&m, true).await;
            queue_receipt(from, &c.id, std::slice::from_ref(&id), "delivered");
            if active && settings().read_receipts {
                queue_receipt(from, &c.id, &[id], "read");
            }
            // Lu ici, sous nos yeux : lu sur nos autres appareils aussi.
            if active {
                sync("read", &c.id, None, serde_json::json!({ "at": m.at })).await;
            }
            // Votre profil, pour qui ne l'a pas encore.
            if let Some(mut sender) = contact(from)
                && !sender.blocked
                && (sender.sent_profile_v < profile().v
                    || (!sender.jeton_perso && net::sealed_supported()))
            {
                sender.sent_profile_v = profile().v;
                if net::sealed_supported() {
                    sender.jeton_sent = true;
                    sender.jeton_perso = true;
                }
                save_contact(sender).await;
                enqueue(from, Wire::new(profile_body(from)), None, None, false).await;
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
        Body::Receipt { st, ids, dev } => {
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
                // Bien arrivé : plus besoin de le garder pour un renvoi (v4 :
                // quand chacun de ses appareils l'a).
                let key = format!("{id}:{from}");
                match store::read::<OutItem>("outbox", &key).await {
                    Some(mut item) if !dev.is_empty() => {
                        if !item.got.contains(&dev) {
                            item.got.push(dev.clone());
                        }
                        if item.boot.is_empty() || still_unconfirmed(&item).await {
                            store::write("outbox", &key, &item).await;
                        } else {
                            store::remove("outbox", &key).await;
                        }
                    }
                    _ => store::remove("outbox", &key).await,
                }
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
            let o = open_brocolis(&c.id, &text);
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
            m.starred = false;
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
            } else if op != "create" && applied && c.group {
                // Seulement les annonces que Talka écrit (« Vous avez ajouté
                // Bob »), d'un admin dont la mise à jour vient d'être
                // appliquée : pas de faux message système.
                if let Some(rest) = note.strip_prefix("Vous avez ") {
                    let rest: String = rest.chars().take(200).collect();
                    system_message(&c.id, &format!("{} a {rest}", display_name(from)), ts).await;
                }
            }
            emit(Ev::Chats);
        }
        _ => {}
    }
}

// ----- La synchronisation entre nos appareils (v4, §7.11) ----------------------

/// Ce que cet appareil a fait, pour nos autres appareils.
async fn sync(op: &str, c: &str, w: Option<Wire>, d: Value) {
    if siblings().is_empty() {
        return;
    }
    let wire = Wire::new(Body::Sync {
        op: op.to_owned(),
        c: c.to_owned(),
        w: w.map(Box::new),
        d,
    });
    enqueue(&my_code(), wire, None, None, false).await;
}

/// Ce qu'un autre de nos appareils (`fd`) a fait : refait ici, sans le
/// renvoyer à personne.
async fn handle_sync(fd: &str, op: &str, c: &str, w: Option<Wire>, d: Value) {
    match op {
        "wire" => {
            let Some(w) = w else { return };
            let id = match &w.body {
                Body::Msg { id, .. } => Some(id.clone()),
                _ => None,
            };
            apply_own_wire(c, w).await;
            // Un message : l'appareil qui l'a envoyé sait que nous l'avons.
            if let Some(id) = id {
                let got = Wire::new(Body::Sync {
                    op: "got".into(),
                    c: c.to_owned(),
                    w: None,
                    d: serde_json::json!({ "ids": [id] }),
                });
                enqueue_to(&my_code(), Some(fd), got).await;
            }
        }
        "got" => {
            let me = my_code();
            for id in d["ids"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
            {
                let key = format!("{id}:{me}");
                if let Some(mut item) = store::read::<OutItem>("outbox", &key).await {
                    if !item.got.iter().any(|x| x == fd) {
                        item.got.push(fd.to_owned());
                    }
                    if item.boot.is_empty() || still_unconfirmed(&item).await {
                        store::write("outbox", &key, &item).await;
                    } else {
                        store::remove("outbox", &key).await;
                    }
                }
            }
        }
        "read" => read_here(c, d["at"].as_f64().unwrap_or(0.0)).await,
        "contact" => {
            let Some(code) = d["code"].as_str().and_then(crypto::normalize) else {
                return;
            };
            if code == my_code() {
                return;
            }
            let mut k = contact(&code).unwrap_or_else(|| Contact {
                code: code.clone(),
                added_at: now(),
                ..Default::default()
            });
            k.name = d["name"].as_str().unwrap_or("").chars().take(40).collect();
            k.saved = d["saved"].as_bool().unwrap_or(k.saved);
            k.blocked = d["blocked"].as_bool().unwrap_or(k.blocked);
            // Ses clés, seulement si elles vont avec son code.
            let (sign, dh) = (
                d["sign"].as_str().unwrap_or(""),
                d["dh"].as_str().unwrap_or(""),
            );
            if k.sign.is_empty() && crypto::verify_keys(&code, sign, dh) {
                k.sign = sign.to_owned();
                k.dh = dh.to_owned();
            }
            save_contact(k).await;
            net::watch(&[code]);
            emit(Ev::Chats);
        }
        "chat" => {
            if d["deleted"] == true {
                drop_chat(c).await;
            } else if d["cleared"] == true {
                if d["keep_starred"] == true {
                    empty_chat_but_starred(c).await;
                } else {
                    empty_chat(c).await;
                }
            } else if let Ok(state) = serde_json::from_value::<ChatState>(d)
                && let Some(mut chat) = chat(c)
            {
                state.apply(&mut chat);
                save_chat(chat).await;
            }
        }
        "forget" => {
            for id in d["ids"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(Value::as_str)
            {
                if let Some(m) = get_message(c, id).await {
                    store::remove("messages", &m.key()).await;
                    bury(c, [(m.id.clone(), m.at)]).await;
                }
            }
            refresh_last(c).await;
            emit(Ev::Messages(c.to_owned()));
        }
        "star" => {
            if let Some(mut m) = get_message(c, d["id"].as_str().unwrap_or("")).await {
                m.starred = d["on"].as_bool().unwrap_or(false);
                store::write_message(&m).await;
                emit(Ev::Messages(c.to_owned()));
            }
        }
        "group" => {
            let Ok(grp) = serde_json::from_value::<GroupInfo>(d["grp"].clone()) else {
                return;
            };
            apply_own_group(
                &grp,
                d["left"].as_bool().unwrap_or(false),
                d["note"].as_str().unwrap_or(""),
            )
            .await;
        }
        "profile" => {
            if let Ok(p) = serde_json::from_value::<Profile>(d)
                && p.v > profile().v
            {
                let p = Profile {
                    name: p.name.chars().take(40).collect(),
                    about: p.about.chars().take(140).collect(),
                    avatar: safe_data("avatar", &p.avatar),
                    v: p.v,
                };
                with(|s| s.profile = p.clone());
                store::write("kv", "profile", &p).await;
                emit(Ev::Profile);
            }
        }
        "devices" => {
            let Ok(list) = serde_json::from_value::<Devices>(d) else {
                return;
            };
            let mut current = devices();
            // Retirés par un autre appareil, nous ne le sommes que par
            // `unlink` (une liste en retard ne nous efface pas).
            if current.merge(list) {
                if !current.has(&my_dev()) {
                    return;
                }
                devices_changed(current, false).await;
            }
        }
        "status" => {
            if let Some(id) = d["deleted"].as_str() {
                store::remove("statuses", &format!("{}|{id}", my_code())).await;
            } else if let Ok(mut st) = serde_json::from_value::<StatusItem>(d) {
                st.owner = my_code();
                st.data = safe_data("image", &st.data);
                store::write("statuses", &st.key(), &st).await;
            }
            emit(Ev::Statuses);
        }
        "call" => emit(Ev::CallSignal {
            from: my_code(),
            body: Body::Call {
                op: "elsewhere".into(),
                call_id: d["id"].as_str().unwrap_or("").to_owned(),
                video: false,
                sdp: String::new(),
                cand: None,
            },
            live: true,
        }),
        "unlink" if d["dev"].as_str() == Some(&my_dev()) && devices().has(fd) => on_unlinked(),
        _ => {}
    }
}

/// Un groupe créé, changé ou quitté sur un autre de nos appareils.
async fn apply_own_group(grp: &GroupInfo, left: bool, note: &str) {
    let id = format!("g:{}", grp.id);
    let me = my_code();
    let mut c = chat(&id).unwrap_or_else(|| Chat {
        id: id.clone(),
        group: true,
        created_by: me.clone(),
        created_at: now(),
        last_at: now(),
        ..Default::default()
    });
    if grp.v < c.v {
        return;
    }
    c.name = grp.name.chars().take(60).collect();
    c.avatar = safe_data("avatar", &grp.avatar);
    c.members = grp.members.clone();
    c.admins = grp.admins.clone();
    if let Some(d) = &grp.desc {
        c.desc = group_desc(d);
    }
    if let Some(a) = grp.admins_only {
        c.admins_only = a;
    }
    c.v = grp.v;
    c.left = left;
    save_chat(c).await;
    if !note.is_empty() {
        system_message(&id, note, now()).await;
    }
    net::watch(
        &grp.members
            .iter()
            .filter(|m| **m != me)
            .cloned()
            .collect::<Vec<_>>(),
    );
}

/// Ce qu'un autre de nos appareils a envoyé dans la discussion `chat_id`.
async fn apply_own_wire(chat_id: &str, wire: Wire) {
    let me = my_code();
    // La discussion : un groupe dont nous sommes, ou un contact.
    let c = if chat_id.starts_with("g:") {
        match chat(chat_id) {
            Some(c) => c,
            None => return,
        }
    } else {
        match crypto::normalize(chat_id) {
            Some(code) if code != me => ensure_direct_chat(&code).await,
            _ => return,
        }
    };
    match wire.body {
        Body::Msg {
            id, at, content, ..
        } => {
            if get_message(&c.id, &id).await.is_some() || buried(&c.id, &id).await {
                return;
            }
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
            let data = if shared_key {
                content.data
            } else {
                safe_data(&kind, &content.data)
            };
            let to = recipients_of(&c);
            let mut m = Message {
                chat: c.id.clone(),
                id,
                from: me.clone(),
                at,
                status: "sent".into(),
                sent_to: to.clone(),
                to,
                kind,
                text: content.text.chars().take(65_536).collect(),
                data,
                name: content.name,
                mime: content.mime,
                size: content.size,
                dur: content.dur,
                w: content.w,
                h: content.h,
                code: content.code,
                reply: content.reply,
                fwd: content.fwd,
                mentions: content.mentions,
                open_at: content.open_at,
                read_by_me: true,
                ..Default::default()
            };
            if let Some(ttl) = wire.ttl {
                m.exp = Some(at + ttl as f64 * 1000.0);
            }
            open_message(&mut m);
            store_message(&m, true).await;
            emit(Ev::Messages(c.id.clone()));
        }
        Body::React { id, emoji } => {
            if let Some(mut m) = get_message(&c.id, &id).await {
                if emoji.is_empty() {
                    m.reactions.remove(&me);
                } else {
                    m.reactions.insert(me, emoji.chars().take(8).collect());
                }
                store::write_message(&m).await;
                emit(Ev::Messages(c.id.clone()));
            }
        }
        Body::Edit { id, text } => {
            if let Some(mut m) = get_message(&c.id, &id).await
                && m.from == me
                && !m.deleted
            {
                let o = open_brocolis(&c.id, &text);
                m.bro = o.opened > 0;
                m.text = o.text;
                m.edited = true;
                store::write_message(&m).await;
                refresh_last(&c.id).await;
                emit(Ev::Messages(c.id.clone()));
            }
        }
        Body::Delete { id } => {
            if let Some(mut m) = get_message(&c.id, &id).await
                && m.from == me
            {
                m.deleted = true;
                m.text.clear();
                m.data.clear();
                m.reactions.clear();
                m.starred = false;
                store::write_message(&m).await;
                refresh_last(&c.id).await;
                emit(Ev::Messages(c.id.clone()));
            }
        }
        Body::Timer { secs } => {
            if let Some(mut chat) = chat(&c.id) {
                chat.timer = secs;
                save_chat(chat).await;
            }
            system_message(&c.id, &timer_text(&me, secs), now()).await;
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
    sync(
        "status",
        "",
        None,
        serde_json::to_value(&st).unwrap_or_default(),
    )
    .await;
    for c in contacts() {
        if c.saved && !c.blocked {
            enqueue(&c.code, Wire::new(body.clone()), None, None, false).await;
        }
    }
}

pub async fn delete_status(id: &str) {
    store::remove("statuses", &format!("{}|{id}", my_code())).await;
    emit(Ev::Statuses);
    sync("status", "", None, serde_json::json!({ "deleted": id })).await;
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

/// Un appel entrant pris ou refusé ici : nos autres appareils cessent de
/// sonner (v4).
pub fn call_taken(id: &str) {
    let d = serde_json::json!({ "id": id });
    spawn(async move { sync("call", "", None, d).await });
}

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
                .is_none_or(|k| !k.starts_with(v3::SESSION_PREFIX) && k != pq6::KEYS)
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
    replace_backed_up_device().await;
    // Le code de la sauvegarde n'est peut-être pas celui de cet appareil : la
    // clé de l'appareil n'est plus protégée par l'ancien ; le code restauré
    // la protégera au prochain déverrouillage (une passkey, elle, reste).
    if store::lock().await == store::Lock::Code {
        store::protect(store::NewLock::None).await;
    }
    Ok(())
}

/// Une sauvegarde restaurée remplace l'appareil d'où elle vient : cet
/// appareil prend un identifiant neuf (deux appareils ne partagent jamais le
/// leur), et l'ancien rejoint les appareils retirés ; s'il sert encore, il
/// efface tout à sa prochaine connexion. Pour ajouter un appareil sans en
/// remplacer un, il y a la liaison par QR code.
async fn replace_backed_up_device() {
    let old: String = store::read("kv", "dev").await.unwrap_or_default();
    store::write("kv", "dev", &crypto::new_dev()).await;
    let mut list: Devices = store::read("kv", "appareils").await.unwrap_or_default();
    if crypto::is_dev(&old) && list.has(&old) {
        list.remove(&old);
        store::write("kv", "appareils", &list).await;
    }
}

/// Cet appareil quitte le compte (avant de tout effacer) : nos autres
/// appareils et les contacts ne lui écrivent plus. Au mieux : sans réseau,
/// il reste dans la liste jusqu'à ce qu'un autre appareil le retire.
pub async fn leave_account() {
    let me = my_dev();
    let mut list = devices();
    if siblings().is_empty() || !list.has(&me) || net::state() != State::Online {
        return;
    }
    list.list.retain(|d| d.dev != me);
    list.v += 1;
    devices_changed(list, true).await;
    flush().await;
}

pub async fn wipe_everything() {
    crate::push::forget().await;
    net::stop();
    store::wipe().await;
    store::local_clear();
}
