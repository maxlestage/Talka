//! Ce que garde l'appareil (IndexedDB) et ce qui voyage, chiffré, entre
//! appareils (`Wire`). Tout est (dé)sérialisé avec serde.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::rc::Rc;

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Profile {
    pub name: String,
    pub about: String,
    pub avatar: String,
    pub v: u64,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(default)]
pub struct Settings {
    /// `auto`, `light` ou `dark`.
    pub theme: String,
    pub read_receipts: bool,
    pub hide_online: bool,
    /// La pastille verte des contacts en ligne (grise hors ligne).
    pub presence_dot: bool,
    pub notify: bool,
    pub sound: bool,
    pub enter_sends: bool,
    pub wallpaper: String,
    pub font_size: u32,
    /// Verrouillage automatique : secondes passées hors de Talka.
    pub lock_after: u32,
    /// Autrefois, l'adresse d'un autre relais ; gardé pour relire les
    /// anciens réglages, jamais utilisé (il n'y a plus qu'un relais).
    pub server: String,
    /// Les messages plus vieux que ça (en jours) s'effacent de l'appareil,
    /// sauf les messages importants (⭐). 0 : gardés pour toujours.
    pub keep_days: u32,
    /// Ce que montrent les notifications : « all » (le nom et le message),
    /// « name » (le nom seulement), « none » (rien : « Nouveau message »).
    pub notify_preview: String,
    /// Une discussion archivée le reste quand un message y arrive (comme
    /// « Garder les discussions archivées » de WhatsApp).
    pub keep_archived: bool,
}

impl Settings {
    /// Le titre et le texte d'une notification, selon `notify_preview`.
    pub fn notification(&self, title: &str, body: &str) -> (String, String) {
        match self.notify_preview.as_str() {
            "none" => ("Talka".into(), "Nouveau message".into()),
            "name" => (title.into(), "Nouveau message".into()),
            _ => (title.into(), body.into()),
        }
    }
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            theme: "auto".into(),
            read_receipts: true,
            hide_online: false,
            presence_dot: true,
            notify: false,
            sound: true,
            enter_sends: true,
            wallpaper: "doodle".into(),
            font_size: 15,
            lock_after: 0,
            server: String::new(),
            keep_days: KEEP_DAYS,
            notify_preview: "all".into(),
            keep_archived: false,
        }
    }
}

impl Chat {
    /// Un message de `from` arrive : la discussion sort-elle des archives ?
    /// Pas pour nos propres messages, ni en sourdine, ni si l'on garde les
    /// discussions archivées.
    pub fn unarchives_on(&self, from: &str, me: &str, keep_archived: bool) -> bool {
        self.archived && from != me && !self.muted && !keep_archived
    }

    /// Un message reçu sans être lu : un non-lu de plus, et « @ » dans la
    /// liste s'il nous mentionne, jusqu'à la lecture.
    pub fn count_unread(&mut self, m: &Message, me: &str) {
        self.unread += 1;
        if m.mentions.iter().any(|x| x == me) {
            self.mentioned = true;
        }
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Contact {
    pub code: String,
    /// Le nom que vous lui donnez.
    pub name: String,
    /// Le nom qu'il s'est choisi (affiché `~Nom`).
    pub profile_name: String,
    pub about: String,
    pub avatar: String,
    pub sign: String,
    pub dh: String,
    pub blocked: bool,
    pub saved: bool,
    /// Ses actus en sourdine, sur cet appareil (comme WhatsApp) : rangées
    /// sous « Masquées », sans compter comme nouvelles.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub status_muted: bool,
    pub added_at: f64,
    pub profile_v: u64,
    pub sent_profile_v: u64,
    /// Son jeton de remise (dans son profil) : pour lui écrire en expéditeur
    /// scellé, sans que le relais sache qui écrit. Vide : pas encore reçu.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub jeton: String,
    /// Lui a-t-on donné le nôtre ?
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub jeton_sent: bool,
    /// A-t-il reçu son jeton personnel (`Identity::jeton_for`) ?
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub jeton_perso: bool,
    /// L'époque de son jeton personnel : elle avance quand on le bloque
    /// (l'ancien est révoqué auprès du relais).
    #[serde(skip_serializing_if = "is_zero_u32")]
    pub jeton_epoch: u32,
    /// Sa version chiffre tout en v3, pièces jointes comprises, et n'envoie
    /// plus l'ancien format (hors signaux en direct) : de lui, l'ancien
    /// format est refusé (un relais pourrait le rejouer).
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub pj: bool,
    /// Sa clé de notification : l'aperçu de ses notifications (« Léa :
    /// Bonjour ») lui est chiffré pour elle. Vide : un simple réveil.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub nk: String,
    /// L'empreinte de ses clés donnée par son lien d'invitation : les clés
    /// trouvées pour son code doivent y correspondre.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub fp: String,
    /// Il nous a déjà écrit en v3 (double cliquet) : l'ancien format, que le
    /// relais pourrait rejouer, ne change plus rien chez nous (minuterie,
    /// modifications, suppressions, groupe, appels).
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub v3: bool,
    /// Ses appareils (v4), tels qu'il les annonce : on écrit à chacun.
    /// Vide : un seul appareil, d'avant la v4.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub devs: Vec<DevInfo>,
    /// La version de cette liste (la plus grande l'emporte).
    #[serde(skip_serializing_if = "is_zero_u64")]
    pub dv: u64,
    /// Il lit ses boîtes aux lettres tournantes (v4) : on lui écrit là.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub bx: bool,
}

/// Un appareil d'un contact (v4) : son identifiant, et sa clé de
/// notification (l'aperçu de ses notifications lui est chiffré).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct DevInfo {
    pub d: String,
    #[serde(skip_serializing_if = "String::is_empty")]
    pub nk: String,
}

/// Un appareil de notre compte (v4, `kv/appareils`).
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Device {
    pub dev: String,
    /// « iPhone », « Chrome sur Mac »…
    pub name: String,
    /// Quand il a été lié.
    pub at: f64,
    /// Sa clé de notification.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub nk: String,
}

/// Les appareils de notre compte (v4) : six au plus ; `v` avance à chaque
/// changement, la plus grande l'emporte.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Devices {
    pub v: u64,
    pub list: Vec<Device>,
    /// Les appareils retirés, les plus récents d'abord. Le relais ne garde
    /// rien sur disque : chacun de nos appareils les lui redit à chaque
    /// connexion, pour qu'un appareil retiré pendant qu'il était hors ligne
    /// reste refusé (et efface tout à son retour), même après un redémarrage.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub gone: Vec<String>,
}

/// Six appareils au plus par compte.
pub const MAX_DEVICES: usize = 6;

/// Oublie les absences de paquet v3 retenues pour `code` : à son code (un
/// ancien client) et à chacun de ses appareils (`<code>/<dev>`). `true` s'il
/// y en avait (ce qui les attendait peut partir).
pub fn forget_missing<T>(cache: &mut HashMap<String, (Option<T>, f64)>, code: &str) -> bool {
    let prefix = format!("{code}/");
    let before = cache.len();
    cache.retain(|k, (bundle, _)| bundle.is_some() || (k != code && !k.starts_with(&prefix)));
    cache.len() != before
}

/// Les seize derniers appareils retirés sont retenus.
pub const MAX_GONE: usize = 16;

impl Devices {
    pub fn has(&self, dev: &str) -> bool {
        self.list.iter().any(|d| d.dev == dev)
    }

    /// Ce que voient les contacts (dans le profil).
    pub fn announced(&self) -> Vec<DevInfo> {
        self.list
            .iter()
            .map(|d| DevInfo {
                d: d.dev.clone(),
                nk: d.nk.clone(),
            })
            .collect()
    }

    /// Retire `dev` du compte : il quitte la liste et rejoint les appareils
    /// retirés.
    pub fn remove(&mut self, dev: &str) {
        self.list.retain(|d| d.dev != dev);
        self.gone.retain(|d| d != dev);
        self.gone.insert(0, dev.to_owned());
        self.gone.truncate(MAX_GONE);
        self.v += 1;
    }

    /// La liste reçue d'un autre de nos appareils : gardée si elle est plus
    /// récente (et bien formée).
    pub fn merge(&mut self, other: Devices) -> bool {
        if other.v <= self.v
            || other.list.len() > MAX_DEVICES
            || other.gone.len() > MAX_GONE
            || other.list.iter().any(|d| !crate::crypto::is_dev(&d.dev))
            || other.gone.iter().any(|d| !crate::crypto::is_dev(d))
        {
            return false;
        }
        *self = other;
        true
    }
}

fn is_zero_u64(v: &u64) -> bool {
    *v == 0
}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Last {
    pub id: String,
    pub from: String,
    pub text: String,
    pub at: f64,
    pub kind: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Chat {
    /// Le code du contact, ou `g:<id>` pour un groupe.
    pub id: String,
    pub group: bool,
    pub name: String,
    pub avatar: String,
    pub members: Vec<String>,
    pub admins: Vec<String>,
    /// La description d'un groupe (ce qu'en disent ses admins).
    #[serde(skip_serializing_if = "String::is_empty")]
    pub desc: String,
    /// Dans ce groupe, seuls les admins envoient des messages.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub admins_only: bool,
    pub created_by: String,
    pub created_at: f64,
    pub last_at: f64,
    pub unread: u32,
    pub marked_unread: bool,
    /// On nous y a mentionné depuis la dernière lecture (« @ » dans la liste).
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub mentioned: bool,
    pub pinned: bool,
    pub archived: bool,
    pub muted: bool,
    /// Sourdine pour un temps : l'heure (ms) où elle se lève d'elle-même
    /// (0 : pour toujours, tant que `muted`).
    #[serde(skip_serializing_if = "is_zero")]
    pub mute_until: f64,
    /// Messages éphémères : leur durée de vie en secondes (0 : désactivés).
    pub timer: u64,
    pub draft: String,
    pub wallpaper: String,
    pub left: bool,
    pub v: u64,
    pub last: Option<Last>,
    pub has_exp: bool,
    /// Le message épinglé en haut de la discussion (pour soi : il n'est pas
    /// envoyé aux autres). Vide : aucun.
    pub pin_msg: String,
    /// Les clés Brocolis 🥦 (leurs `id`) qui chiffrent ce qui part dans cette
    /// discussion, en plus de Talka. Vide : désactivé.
    pub brocolis: Vec<String>,
}

/// Une clé de [Brocolis 🥦](crate::jetons), partagée avec ses proches dans
/// l'app Brocolis.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct BrocolisKey {
    pub id: String,
    pub name: String,
    /// 32 octets en base64.
    pub key: String,
    /// Remplacée par une clé neuve : elle ouvre encore les anciens messages,
    /// mais ne chiffre plus rien.
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub retired: bool,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct Reply {
    pub id: String,
    pub from: String,
    pub text: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Message {
    pub chat: String,
    pub id: String,
    /// Le code de l'auteur, ou `system`.
    pub from: String,
    pub at: f64,
    /// `text`, `image`, `voice`, `file`, `contact`, `system`.
    pub kind: String,
    pub text: String,
    /// Une photo, un vocal ou un fichier : une adresse `data:`.
    pub data: String,
    pub name: String,
    pub mime: String,
    pub size: f64,
    pub dur: f64,
    pub w: u32,
    pub h: u32,
    /// Une carte de contact : son code.
    pub code: String,
    pub reply: Option<Reply>,
    pub fwd: bool,
    /// `pending`, `sent`, `failed` (les accusés sont calculés).
    pub status: String,
    pub to: Vec<String>,
    pub sent_to: Vec<String>,
    pub delivered_to: Vec<String>,
    pub read_by: Vec<String>,
    pub reactions: BTreeMap<String, String>,
    pub edited: bool,
    pub deleted: bool,
    pub starred: bool,
    pub exp: Option<f64>,
    pub read_by_me: bool,
    /// Une capsule temporelle : scellée jusqu'à cette heure (ms ; 0 : non).
    #[serde(skip_serializing_if = "is_zero")]
    pub open_at: f64,
    /// Arrivé ou parti chiffré par Brocolis 🥦 (et ouvert sur cet appareil).
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub bro: bool,
    /// Les membres mentionnés (« @Léa »), par leur code.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub mentions: Vec<String>,
}

pub(crate) fn is_zero_u32(n: &u32) -> bool {
    *n == 0
}

fn is_zero(v: &f64) -> bool {
    *v == 0.0
}

/// Une ligne d'une discussion exportée en texte, comme TalkaKit
/// (`exportLine`) : « 21/10/2026 23:21 - Léa : Bonjour » ; sans auteur pour
/// un message du système. Les retours à la ligne du message sont gardés.
pub fn export_line(stamp: &str, author: Option<&str>, text: &str) -> String {
    match author {
        Some(a) => format!("{stamp} - {a} : {text}"),
        None => format!("{stamp} - {text}"),
    }
}

/// Le nom du fichier d'une discussion exportée : « Talka - Léa.txt », sans
/// les caractères interdits dans un nom de fichier.
pub fn export_filename(title: &str) -> String {
    let clean: String = title
        .chars()
        .map(|c| {
            if "/\\:*?\"<>|".contains(c) || c.is_control() {
                '_'
            } else {
                c
            }
        })
        .collect();
    format!("Talka - {}.txt", clean.trim())
}

/// Par défaut, les messages s'effacent au bout de 3 mois.
pub const KEEP_DAYS: u32 = 90;
const DAY_MS: f64 = 86_400_000.0;

/// Les groupes que l'on partage avec `code` (fiche du contact, comme
/// WhatsApp) : ceux dont nous sommes membres tous les deux, par nom. Le
/// même que l'iPhone (`Chat.groupsInCommon`).
pub fn groups_in_common<'a>(chats: &'a [Chat], me: &str, code: &str) -> Vec<&'a Chat> {
    let mut list: Vec<&Chat> = chats
        .iter()
        .filter(|c| {
            c.group
                && !c.left
                && c.members.iter().any(|m| m == me)
                && c.members.iter().any(|m| m == code)
        })
        .collect();
    list.sort_by_key(|c| c.name.to_lowercase());
    list
}

/// Les photos d'une discussion, pour sa fiche (« Photos », comme WhatsApp) :
/// la plus récente d'abord, sans les supprimées ni une capsule encore scellée
/// pour nous. `msgs` va du plus ancien au plus récent. Le même que l'iPhone
/// (`Message.photos`).
pub fn photos<'a>(msgs: &'a [Message], me: &str, now: f64) -> Vec<&'a Message> {
    attachments(msgs, "image", me, now)
}

/// Les documents (fichiers) d'une discussion, pour sa fiche, avec la même
/// règle que les photos. Le même que l'iPhone (`Message.documents`).
pub fn documents<'a>(msgs: &'a [Message], me: &str, now: f64) -> Vec<&'a Message> {
    attachments(msgs, "file", me, now)
}

/// Les liens d'un texte, avec la règle des liens cliquables des bulles
/// (`dom::rich_text`) : `http://` ou `https://` en début de mot (ou après une
/// parenthèse, un guillemet), jusqu'à l'espace, sans la ponctuation finale.
/// Le même que l'iPhone (`Message.findLinks`).
pub fn find_links(text: &str) -> Vec<String> {
    let chars: Vec<char> = text.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let rest: String = chars[i..].iter().take(8).collect();
        let at_word_start =
            i == 0 || chars[i - 1].is_whitespace() || "([{«\"'“‘".contains(chars[i - 1]);
        if at_word_start && (rest.starts_with("https://") || rest.starts_with("http://")) {
            let mut j = i;
            while j < chars.len() && !chars[j].is_whitespace() && chars[j] != '<' {
                j += 1;
            }
            while j > i && ".,;:!?)]}'\"»”’".contains(chars[j - 1]) {
                j -= 1;
            }
            let url: String = chars[i..j].iter().collect();
            if url.len() > "https://".len() {
                out.push(url);
            }
            i = j.max(i + 1);
        } else {
            i += 1;
        }
    }
    out
}

/// Les liens d'une discussion, pour sa fiche (« Liens », comme WhatsApp) : le
/// plus récent d'abord, chacun une fois, avec son message ; sans les messages
/// supprimés ni une capsule encore scellée pour nous. Le même que l'iPhone
/// (`Message.links`).
pub fn links<'a>(msgs: &'a [Message], me: &str, now: f64) -> Vec<(String, &'a Message)> {
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for m in msgs.iter().rev() {
        if m.from == "system" || m.deleted || m.sealed_for(me, now) {
            continue;
        }
        for url in find_links(&m.text) {
            if seen.insert(url.clone()) {
                out.push((url, m));
            }
        }
    }
    out
}

fn attachments<'a>(msgs: &'a [Message], kind: &str, me: &str, now: f64) -> Vec<&'a Message> {
    msgs.iter()
        .rev()
        .filter(|m| m.kind == kind && !m.deleted && !m.data.is_empty() && !m.sealed_for(me, now))
        .collect()
}

impl Message {
    /// La réaction de `who` (vide : retirée ; 8 caractères au plus). Vrai
    /// si elle change (le message est alors à réécrire).
    pub fn set_reaction(&mut self, who: &str, emoji: &str) -> bool {
        if emoji.is_empty() {
            return self.reactions.remove(who).is_some();
        }
        let emoji: String = emoji.chars().take(8).collect();
        if self.reactions.get(who) == Some(&emoji) {
            return false;
        }
        self.reactions.insert(who.to_owned(), emoji);
        true
    }

    /// Un accusé de `from` : reçu, ou lu (`read`). Vrai s'il apprend
    /// quelque chose (le message est alors à réécrire).
    pub fn note_receipt(&mut self, from: &str, read: bool) -> bool {
        let mut changed = false;
        let mut add = |list: &mut Vec<String>| {
            if !list.iter().any(|x| x == from) {
                list.push(from.to_owned());
                changed = true;
            }
        };
        add(&mut self.delivered_to);
        if read {
            add(&mut self.read_by);
        }
        changed
    }

    /// En favori, et pas supprimé pour tous (un message supprimé n'a plus
    /// rien à garder, comme WhatsApp).
    pub fn is_starred(&self) -> bool {
        self.starred && !self.deleted
    }

    /// Trop vieux pour être gardé (`keep_days` jours, 0 : jamais) ? Les
    /// messages importants (⭐) restent ; une capsule temporelle compte à
    /// partir de son ouverture (sinon elle partirait avant de s'ouvrir).
    pub fn too_old(&self, keep_days: u32, now: f64) -> bool {
        let since = self.at.max(self.open_at);
        keep_days > 0 && !self.is_starred() && since < too_old_before(keep_days, now)
    }

    /// Une capsule temporelle (scellée ou déjà ouverte).
    pub fn is_capsule(&self) -> bool {
        self.open_at > 0.0
    }

    /// Une capsule encore scellée pour son destinataire : l'auteur, lui, voit
    /// toujours ce qu'il a écrit.
    pub fn sealed_for(&self, me: &str, now: f64) -> bool {
        self.open_at > now && self.from != me
    }

    /// La ligne sous le nom d'un document : « 12 Ko · PDF », ou la taille
    /// seule quand le nom n'a pas d'extension (« README », « .profile »).
    /// `size` est déjà mise en forme. Le même que l'iPhone (`Message.fileDetail`).
    pub fn file_detail(&self, size: &str) -> String {
        match self.name.rsplit_once('.') {
            Some((base, ext))
                if !base.is_empty()
                    && !ext.is_empty()
                    && ext.len() <= 8
                    && ext.chars().all(|c| c.is_ascii_alphanumeric()) =>
            {
                format!("{size} · {}", ext.to_uppercase())
            }
            _ => size.to_owned(),
        }
    }

    /// Le nom d'une pièce jointe enregistrée (« Enregistrer ») : le sien, ou
    /// `photo-talka` / `vocal-talka` / `fichier-talka` avec l'extension de son
    /// vrai type (un vocal de l'iPhone est en m4a). Le même que l'iPhone
    /// (`Message.saveName`).
    pub fn save_name(&self) -> String {
        if !self.name.is_empty() {
            return self.name.clone();
        }
        let mime = if self.mime.is_empty() {
            self.data
                .strip_prefix("data:")
                .and_then(|d| d.split([';', ',']).next())
                .unwrap_or("")
        } else {
            self.mime.as_str()
        };
        let base = match self.kind.as_str() {
            "image" => "photo-talka",
            "voice" => "vocal-talka",
            _ => "fichier-talka",
        };
        let mime = mime.split(';').next().unwrap_or("").trim();
        let ext = match mime.to_ascii_lowercase().as_str() {
            "image/jpeg" | "image/jpg" => "jpg",
            "image/png" => "png",
            "image/gif" => "gif",
            "image/webp" => "webp",
            "image/heic" => "heic",
            "audio/webm" => "webm",
            "audio/ogg" => "ogg",
            "audio/mp4" | "audio/m4a" | "audio/x-m4a" | "audio/aac" => "m4a",
            "audio/mpeg" => "mp3",
            _ => match self.kind.as_str() {
                "image" => "jpg",
                "voice" => "webm",
                _ => return base.to_owned(),
            },
        };
        format!("{base}.{ext}")
    }

    /// `chat|id` : la clé dans IndexedDB.
    pub fn key(&self) -> String {
        format!("{}|{}", self.chat, self.id)
    }

    /// L'état d'un message envoyé : `pending`, `sent`, `delivered`, `read`, `failed`.
    pub fn tick(&self) -> &'static str {
        if self.status == "failed" {
            return "failed";
        }
        if self.to.is_empty() {
            return "sent";
        }
        let all = |list: &[String]| self.to.iter().all(|c| list.contains(c));
        if all(&self.read_by) {
            "read"
        } else if all(&self.delivered_to) {
            "delivered"
        } else if all(&self.sent_to) {
            "sent"
        } else {
            "pending"
        }
    }
}

/// Une enveloppe qui attend de partir.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct OutItem {
    pub id: String,
    pub to: String,
    /// Le `Wire`, en JSON : chiffré au moment de partir.
    pub payload: String,
    pub chat: String,
    pub msg_id: String,
    pub seq: f64,
    /// Le démarrage du relais qui a accepté ce message (`ready.boot`) : il
    /// est gardé jusqu'à l'accusé de réception du destinataire, et renvoyé
    /// si le relais a redémarré entre-temps. Vide : pas encore parti.
    pub boot: String,
    /// v4 : les appareils du destinataire qui l'ont déjà (déposé pour eux).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub sent: Vec<String>,
    /// v4 : ceux qui l'ont reçu (accusé de réception de cet appareil).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub got: Vec<String>,
}

impl OutItem {
    /// Sa tête, sans son contenu.
    pub fn head(&self) -> OutHead {
        OutHead {
            id: self.id.clone(),
            to: self.to.clone(),
            chat: self.chat.clone(),
            msg_id: self.msg_id.clone(),
            seq: self.seq,
            boot: self.boot.clone(),
            sent: self.sent.clone(),
            got: self.got.clone(),
        }
    }
}

/// Un [`OutItem`] sans son contenu (`payload`, une photo chiffrée peut
/// peser plusieurs Mo) : la boîte d'envoi parcourt les têtes, et ne lit en
/// entier que ce qui part.
#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(default)]
pub struct OutHead {
    pub id: String,
    pub to: String,
    pub chat: String,
    pub msg_id: String,
    pub seq: f64,
    pub boot: String,
    pub sent: Vec<String>,
    pub got: Vec<String>,
}

/// Les têtes de la boîte d'envoi, gardées en mémoire et tenues à jour par
/// chacune de ses écritures : la parcourir ne relit plus ses éléments
/// (photos chiffrées comprises) dans la base. `None` : pas encore lues.
#[derive(Default)]
pub struct OutHeads {
    heads: Option<BTreeMap<String, OutHead>>,
}

impl OutHeads {
    /// Les têtes, si elles sont connues.
    pub fn get(&self) -> Option<Vec<OutHead>> {
        self.heads.as_ref().map(|h| h.values().cloned().collect())
    }

    /// Lues de la base (`(clé, json)`).
    pub fn fill(&mut self, rows: &[(String, String)]) -> Vec<OutHead> {
        let heads: BTreeMap<String, OutHead> = rows
            .iter()
            .filter_map(|(k, json)| Some((k.clone(), serde_json::from_str(json).ok()?)))
            .collect();
        let out = heads.values().cloned().collect();
        self.heads = Some(heads);
        out
    }

    /// Un élément écrit (son JSON).
    pub fn put(&mut self, key: &str, json: &str) {
        let Some(heads) = &mut self.heads else { return };
        match serde_json::from_str::<OutHead>(json) {
            Ok(head) => {
                heads.insert(key.to_owned(), head);
            }
            // Illisible : tout sera relu.
            Err(_) => self.heads = None,
        }
    }

    /// Un élément retiré.
    pub fn remove(&mut self, key: &str) {
        if let Some(heads) = &mut self.heads {
            heads.remove(key);
        }
    }

    /// Tout relire la prochaine fois (une sauvegarde restaurée…).
    pub fn forget(&mut self) {
        self.heads = None;
    }
}

/// Les marques des messages qu'attend la boîte d'envoi (`MessageMark`),
/// gardées jusqu'à ce que le message soit écrit ou retiré : la base
/// recopiait à chaque passage chaque message entier, photo comprise.
#[derive(Default)]
pub struct Marks {
    marks: HashMap<String, Option<MessageMark>>,
}

impl Marks {
    /// Au-delà, tout est oublié (la boîte d'envoi n'en attend pas autant).
    const MAX: usize = 4096;

    /// La marque gardée de `key` (`chat|id`) : `Some(None)` si le message
    /// n'existe pas, `None` si elle n'est pas connue.
    pub fn get(&self, key: &str) -> Option<Option<MessageMark>> {
        self.marks.get(key).cloned()
    }

    pub fn put(&mut self, key: &str, mark: Option<MessageMark>) {
        if self.marks.len() >= Self::MAX {
            self.marks.clear();
        }
        self.marks.insert(key.to_owned(), mark);
    }

    /// Le message `key` a été écrit ou retiré.
    pub fn changed(&mut self, key: &str) {
        self.marks.remove(key);
    }

    /// Tous les messages de `chat` ont pu changer.
    pub fn changed_chat(&mut self, chat: &str) {
        self.marks
            .retain(|key, _| key.split_once('|').is_none_or(|(c, _)| c != chat));
    }

    pub fn forget(&mut self) {
        self.marks.clear();
    }
}

/// Ce que la recherche dans toutes les discussions garde d'un message : son
/// texte replié (`fold`), et de quoi savoir si c'est une capsule encore
/// scellée au moment de chercher. Gardé tant que la discussion ne change
/// pas : chercher ne décode plus tous les messages de l'appareil à chaque
/// lettre tapée.
#[derive(Clone, Debug, PartialEq)]
pub struct SearchLine {
    pub id: String,
    pub at: f64,
    pub from: String,
    pub open_at: f64,
    pub folded: String,
}

/// Les champs d'un message que lit la recherche (pas sa photo).
#[derive(Default, Deserialize)]
#[serde(default)]
struct SearchFields {
    id: String,
    from: String,
    at: f64,
    kind: String,
    text: String,
    name: String,
    deleted: bool,
    open_at: f64,
}

impl SearchLine {
    /// La ligne d'un message (son JSON) ; `None` pour ce que la recherche ne
    /// montre jamais (messages système, supprimés).
    pub fn of(json: &str) -> Option<SearchLine> {
        let m: SearchFields = serde_json::from_str(json).ok()?;
        if m.kind == "system" || m.deleted {
            return None;
        }
        Some(SearchLine {
            folded: fold(&format!("{} {}", m.text, m.name)),
            id: m.id,
            at: m.at,
            from: m.from,
            open_at: m.open_at,
        })
    }

    /// Trouvée par `q` (déjà replié), sauf capsule encore scellée pour `me`.
    pub fn matches(&self, q: &str, me: &str, now: f64) -> bool {
        !(self.open_at > now && self.from != me) && self.folded.contains(q)
    }
}

/// Un message important d'une discussion : son heure et son identifiant,
/// lus sans décoder le reste du message (sa photo).
pub fn starred_of(json: &str) -> Option<(f64, String)> {
    #[derive(Default, Deserialize)]
    #[serde(default)]
    struct Star {
        id: String,
        at: f64,
        starred: bool,
        deleted: bool,
    }
    let m: Star = serde_json::from_str(json).ok()?;
    (m.starred && !m.deleted).then_some((m.at, m.id))
}

/// Ce que la boîte d'envoi lit d'un message pour savoir s'il attend encore
/// un accusé de réception : pas sa photo.
#[derive(Clone, Debug, Default, PartialEq, Deserialize)]
#[serde(default)]
pub struct MessageMark {
    pub at: f64,
    pub deleted: bool,
    pub delivered_to: Vec<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct StatusItem {
    pub owner: String,
    pub id: String,
    /// `text` ou `image`.
    pub kind: String,
    pub text: String,
    pub bg: String,
    pub data: String,
    pub at: f64,
    pub seen: bool,
    pub viewers: BTreeMap<String, f64>,
}

impl StatusItem {
    pub fn key(&self) -> String {
        format!("{}|{}", self.owner, self.id)
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct CallEntry {
    pub id: String,
    pub peer: String,
    pub video: bool,
    pub incoming: bool,
    pub at: f64,
    pub dur: f64,
    pub missed: bool,
}

/// Ce qui décrit un groupe, joint à chaque envoi dans ce groupe.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct GroupInfo {
    pub id: String,
    pub name: String,
    pub members: Vec<String>,
    pub admins: Vec<String>,
    pub avatar: String,
    pub v: u64,
    /// Sa description ; absente (un client d'avant elle) : inchangée.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub desc: Option<String>,
    /// Seuls les admins écrivent ; absent (un client d'avant) : inchangé.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub admins_only: Option<bool>,
}

/// Combien de messages des autres sont arrivés après `seen` (le dernier
/// message affiché quand on a quitté le bas de la discussion) : le nombre du
/// bouton « Derniers messages », comme WhatsApp. Sans `seen` trouvé dans la
/// liste, rien (la liste a changé de fenêtre). Le même que l'iPhone
/// (`Message.newBelow`).
pub fn new_below(msgs: &[Message], seen: &str, me: &str) -> usize {
    match msgs.iter().position(|m| m.id == seen) {
        Some(i) => msgs[i + 1..]
            .iter()
            .filter(|m| m.from != me && m.from != "system")
            .count(),
        None => 0,
    }
}

/// « 1:05 ». Une durée reçue d'un autre appareil peut être n'importe quoi :
/// bornée (0 à 99 h), comme l'iPhone (`formatDuration`, qui sans cela
/// planterait).
pub fn format_duration(s: f64) -> String {
    let s = if s.is_finite() {
        s.clamp(0.0, 359_999.0).round() as u64
    } else {
        0
    };
    format!("{}:{:02}", s / 60, s % 60)
}

/// Une taille lisible : « 25 o », « 11,7 Ko », « 3,2 Mo » (par 1024, une
/// décimale à la française). Le même que l'iPhone (`formatBytes`).
pub fn format_bytes(n: f64) -> String {
    let units = ["o", "Ko", "Mo", "Go"];
    let mut n = if n.is_finite() { n.max(0.0) } else { 0.0 };
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

/// Ce que pèsent les pièces jointes d'une discussion (« Gérer le stockage »,
/// comme WhatsApp). Le même que l'iPhone (`Storage`).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Storage {
    pub bytes: f64,
    pub photos: usize,
    pub files: usize,
    pub voices: usize,
}

impl Storage {
    /// Les photos, documents et vocaux encore là (pas les supprimés) : leur
    /// taille, ou à défaut celle de leur contenu base64 décodé.
    pub fn of(msgs: &[Message]) -> Storage {
        let mut st = Storage::default();
        for m in msgs.iter().filter(|m| !m.deleted && !m.data.is_empty()) {
            match m.kind.as_str() {
                "image" => st.photos += 1,
                "file" => st.files += 1,
                "voice" => st.voices += 1,
                _ => continue,
            }
            st.bytes += if m.size > 0.0 {
                m.size
            } else {
                let b64 = m.data.split_once(',').map_or(m.data.as_str(), |(_, b)| b);
                (b64.len() * 3 / 4) as f64
            };
        }
        st
    }

    /// « 12 photos · 2 documents · 1 vocal ».
    pub fn detail(&self) -> String {
        let part = |n: usize, one: &str, many: &str| match n {
            0 => None,
            1 => Some(format!("1 {one}")),
            n => Some(format!("{n} {many}")),
        };
        [
            part(self.photos, "photo", "photos"),
            part(self.files, "document", "documents"),
            part(self.voices, "vocal", "vocaux"),
        ]
        .into_iter()
        .flatten()
        .collect::<Vec<_>>()
        .join(" · ")
    }
}

/// Une description de groupe : 500 caractères au plus.
pub const MAX_GROUP_DESC: usize = 500;

/// Un message : 20 000 caractères au plus, arrêtés à la saisie (comme
/// `maxlength`) plutôt que coupés en silence à l'envoi. Le même que l'iPhone
/// (`maxMessageText`).
pub const MAX_TEXT: usize = 20_000;

/// La description d'un groupe telle qu'elle est gardée.
pub fn group_desc(d: &str) -> String {
    d.trim().chars().take(MAX_GROUP_DESC).collect()
}

/// Le contenu d'un message (ce qui est commun à l'envoi et au stockage).
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Content {
    pub kind: String,
    pub text: String,
    pub data: String,
    pub name: String,
    pub mime: String,
    pub size: f64,
    pub dur: f64,
    pub w: u32,
    pub h: u32,
    pub code: String,
    pub reply: Option<Reply>,
    pub fwd: bool,
    /// Une capsule temporelle : à ouvrir à cette heure (ms ; absent : non).
    /// Les versions qui ne la connaissent pas affichent le message tout de
    /// suite.
    #[serde(skip_serializing_if = "is_zero")]
    pub open_at: f64,
    /// Dans un groupe : les membres mentionnés (« @Léa »), par leur code.
    /// Les versions qui ne le connaissent pas l'ignorent.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub mentions: Vec<String>,
}

impl Body {
    /// Ce qui change un état déjà là (et qu'un rejeu suffirait à défaire) :
    /// refusé dans l'ancien format d'un contact passé en v3.
    pub fn changes_state(&self) -> bool {
        matches!(
            self,
            Body::Timer { .. }
                | Body::Edit { .. }
                | Body::Delete { .. }
                | Body::Group { .. }
                | Body::Call { .. }
                | Body::StatusDelete { .. }
        )
    }
}

// ----- Les mentions (« @Léa ») -------------------------------------------------

/// Ce qu'on tape après « @ » (le texte jusqu'au curseur), pour proposer les
/// membres : le « @ » doit commencer un mot ; pas de retour à la ligne, 30
/// caractères au plus.
pub fn mention_query(before: &str) -> Option<String> {
    let at = before.rfind('@')?;
    if at > 0 && !before[..at].ends_with(char::is_whitespace) {
        return None;
    }
    let query = &before[at + 1..];
    (!query.contains('\n') && query.chars().count() <= 30).then(|| query.to_owned())
}

/// Les membres mentionnés dans `text` : ceux dont « @libellé » y figure
/// (`members` : code et libellé). Chacun une fois, dans l'ordre donné.
pub fn mentions_in(text: &str, members: &[(String, String)]) -> Vec<String> {
    let folded = fold(text);
    members
        .iter()
        .filter(|(_, label)| !label.is_empty() && folded.contains(&format!("@{}", fold(label))))
        .map(|(code, _)| code.clone())
        .fold(Vec::new(), |mut out, code| {
            if !out.contains(&code) {
                out.push(code);
            }
            out
        })
}

/// Une donnée reçue (photo, vocal, fichier, avatar) : seulement une adresse
/// `data:` du bon type, jamais une adresse distante (elle trahirait votre
/// adresse IP et l'heure à laquelle vous lisez) ni une page HTML déguisée.
pub fn safe_data(kind: &str, data: &str) -> String {
    let ok = match kind {
        "image" | "avatar" => ["png", "jpeg", "jpg", "webp", "gif"]
            .iter()
            .any(|t| data.starts_with(&format!("data:image/{t};base64,"))),
        "voice" => data.starts_with("data:audio/") || data.starts_with("data:video/"),
        // Un fichier : n'importe quel type, mais seulement `data:` ; il se
        // télécharge comme des octets, jamais ouvert dans l'onglet.
        "file" => data.starts_with("data:"),
        _ => false,
    };
    if ok { data.to_owned() } else { String::new() }
}

/// Un groupe ne change que par ses admins ; un membre peut seulement le
/// quitter (et, s'il était le dernier admin, le premier membre restant le
/// devient). Une version ne saute pas de plus de `GROUP_V_STEP` : personne
/// ne la bloque en l'envoyant au bout des nombres.
pub const GROUP_V_STEP: u64 = 1_000;

impl Chat {
    /// `from` peut-il appliquer cette description du groupe ?
    pub fn accepts(&self, from: &str, grp: &GroupInfo) -> bool {
        if !self.group || grp.v <= self.v || grp.v > self.v.saturating_add(GROUP_V_STEP) {
            return false;
        }
        if !self.members.iter().any(|m| m == from) {
            return false;
        }
        if self.admins.iter().any(|a| a == from) {
            return true;
        }
        // Un simple membre : seulement son départ.
        let members: Vec<&String> = self.members.iter().filter(|m| *m != from).collect();
        let mut admins: Vec<&String> = self.admins.iter().filter(|a| *a != from).collect();
        if admins.is_empty()
            && let Some(first) = members.first()
        {
            admins = vec![*first];
        }
        grp.members.iter().collect::<Vec<_>>() == members
            && grp.admins.iter().collect::<Vec<_>>() == admins
            && grp.name == self.name
            && grp.avatar == self.avatar
            && grp.desc.as_ref().is_none_or(|d| group_desc(d) == self.desc)
            && grp.admins_only.is_none_or(|a| a == self.admins_only)
    }

    /// `me` peut-il écrire ici ? Pas dans un groupe quitté, ni, quand seuls
    /// les admins écrivent, sans en être un.
    pub fn can_write(&self, me: &str) -> bool {
        !self.group || (!self.left && (!self.admins_only || self.admins.iter().any(|a| a == me)))
    }
}

/// Ce qui voyage, chiffré, d'un appareil à l'autre.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "k", rename_all = "kebab-case")]
// Un message est le cas courant : le garder en place plutôt que derrière un
// pointeur (les autres variantes sont plus petites, sans conséquence).
#[allow(clippy::large_enum_variant)]
pub enum Body {
    Msg {
        id: String,
        at: f64,
        /// Le nom choisi par l'auteur (`~Nom` chez qui ne le connaît pas).
        #[serde(default)]
        pn: String,
        #[serde(flatten)]
        content: Content,
    },
    Receipt {
        /// `delivered` ou `read`.
        st: String,
        ids: Vec<String>,
        /// L'appareil qui a reçu (v4).
        #[serde(default, skip_serializing_if = "String::is_empty")]
        dev: String,
    },
    React {
        id: String,
        emoji: String,
    },
    Edit {
        id: String,
        text: String,
    },
    Delete {
        id: String,
    },
    Typing {
        on: bool,
    },
    Profile {
        #[serde(flatten)]
        profile: Profile,
        /// Le jeton de remise de l'expéditeur (expéditeur scellé) ; ignoré
        /// par les versions qui ne le connaissent pas.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        jeton: String,
        /// Sa clé de notification (P-256, base64) : pour lui chiffrer
        /// l'aperçu de ses notifications ; ignorée par les versions qui ne la
        /// connaissent pas.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        nk: String,
        /// Cette version chiffre tout en v3, pièces jointes comprises (voir
        /// `Contact::pj`) ; ignoré par les versions qui ne le connaissent pas.
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        pj: bool,
        /// Ses appareils (v4) et la version de cette liste.
        #[serde(default, skip_serializing_if = "Vec::is_empty")]
        devs: Vec<DevInfo>,
        #[serde(default, skip_serializing_if = "is_zero_u64")]
        dv: u64,
        /// Il lit ses boîtes aux lettres tournantes (v4).
        #[serde(default, skip_serializing_if = "std::ops::Not::not")]
        bx: bool,
    },
    Group {
        op: String,
        #[serde(default)]
        note: String,
    },
    Timer {
        secs: u64,
    },
    Status {
        id: String,
        #[serde(rename = "type")]
        kind: String,
        #[serde(default)]
        text: String,
        #[serde(default)]
        bg: String,
        #[serde(default)]
        data: String,
        at: f64,
    },
    StatusSeen {
        id: String,
    },
    StatusDelete {
        id: String,
    },
    Call {
        op: String,
        call_id: String,
        #[serde(default)]
        video: bool,
        #[serde(default)]
        sdp: String,
        #[serde(default)]
        cand: Option<serde_json::Value>,
    },
    /// Entre les appareils d'un même compte (v4, `docs/SECURITE.md` §7.11) :
    /// ce que l'un a fait, que les autres refont. Accepté seulement de nos
    /// propres appareils.
    Sync {
        op: String,
        /// La discussion concernée.
        #[serde(default, skip_serializing_if = "String::is_empty")]
        c: String,
        /// Ce que l'appareil a envoyé dans la discussion (`op` = `wire`).
        #[serde(default, skip_serializing_if = "Option::is_none")]
        w: Option<Box<Wire>>,
        /// Le reste (selon `op`).
        #[serde(default, skip_serializing_if = "serde_json::Value::is_null")]
        d: serde_json::Value,
    },
}

/// Une enveloppe : le contenu, et la discussion à laquelle il appartient.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Wire {
    #[serde(flatten)]
    pub body: Body,
    /// Le groupe (décrit en entier).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub grp: Option<GroupInfo>,
    /// Le groupe (seulement son identifiant : accusés).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub g: Option<String>,
    /// Messages éphémères : la durée de vie, en secondes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ttl: Option<u64>,
}

impl Wire {
    pub fn new(body: Body) -> Wire {
        Wire {
            body,
            grp: None,
            g: None,
            ttl: None,
        }
    }

    /// L'identifiant du groupe, s'il y en a un.
    pub fn group_id(&self) -> Option<String> {
        self.grp
            .as_ref()
            .map(|g| g.id.clone())
            .or_else(|| self.g.clone())
    }

    /// L'aperçu de sa notification chez le destinataire, en JSON : qui écrit
    /// (`n`, son nom choisi ; le service worker lui préfère le nom que le
    /// destinataire lui a donné), le groupe (`g`), quoi (`t`, `preview` du
    /// message, comme la liste des discussions) et la discussion vue de chez
    /// lui (`c`). Seulement pour un message écrit ; il partira chiffré pour la
    /// clé de notification du destinataire.
    pub fn apercu(
        &self,
        me: &str,
        name: &str,
        preview: impl Fn(&Message) -> String,
    ) -> Option<String> {
        let Body::Msg { content, .. } = &self.body else {
            return None;
        };
        let m = Message {
            kind: content.kind.clone(),
            text: content.text.clone(),
            name: content.name.clone(),
            dur: content.dur,
            open_at: content.open_at,
            ..Default::default()
        };
        let name: String = if name.trim().is_empty() {
            crate::crypto::pretty(me)
        } else {
            name.trim().chars().take(40).collect()
        };
        let c = match self.group_id() {
            Some(id) => format!("g:{id}"),
            None => me.to_owned(),
        };
        let g: String = self
            .grp
            .as_ref()
            .map(|g| g.name.chars().take(40).collect())
            .unwrap_or_default();
        let t: String = preview(&m).chars().take(120).collect();
        let mut out = serde_json::json!({ "n": name, "t": t, "c": c });
        if !g.is_empty() {
            out["g"] = g.into();
        }
        Some(out.to_string())
    }
}

/// La taille maximale d'un document : 3 Mo. Encodé deux fois en base64 (dans
/// le message, puis une fois chiffré), il fait environ 5,4 Mo ; scellé
/// (expéditeur caché), une fois de plus : 7,2 Mo, sous la limite de 8 Mo du
/// relais (`MAX_BODY`).
pub const MAX_FILE: f64 = 3.0 * 1024.0 * 1024.0;

// ----- Les pièces jointes en v3 ------------------------------------------------
//
// Un jeton v3 porte 200 Kio au plus. Une photo, un vocal ou un fichier plus
// gros part ainsi : ses données sont chiffrées par une clé tirée pour lui
// seul (AES-256-GCM, AD `talka-pj:<id du message>`), et le message v3 —
// avec sa confidentialité persistante — ne porte que cette clé
// (`data` = `talka-pj:<clé>`). L'enveloppe devient `<enveloppe v3>|<pièce
// chiffrée en base64>`. Seulement vers un contact dont la version le sait
// (`Contact::pj`) ; les autres reçoivent l'ancien format.

/// Ce que devient `data` dans le message v3.
pub const PJ_PREFIX: &str = "talka-pj:";

fn pj_aad(id: &str) -> Vec<u8> {
    format!("talka-pj:{id}").into_bytes()
}

/// Sépare les données d'un message (un `Wire` JSON) : le message à mettre
/// dans le jeton v3, et la pièce chiffrée (iv ‖ chiffré ‖ tag). `None` si ce
/// n'est pas un message avec des données.
#[allow(deprecated)] // `Nonce::from_slice`, comme crypto.rs
pub fn split_attachment(payload: &str) -> Option<(String, Vec<u8>)> {
    use aes_gcm::aead::{Aead, Payload};
    use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
    let mut wire: Wire = serde_json::from_str(payload).ok()?;
    let Body::Msg { id, content, .. } = &mut wire.body else {
        return None;
    };
    if content.data.is_empty() {
        return None;
    }
    let key = crate::crypto::random_bytes(32);
    let iv = crate::crypto::random_bytes(12);
    let ct = Aes256Gcm::new_from_slice(&key)
        .ok()?
        .encrypt(
            Nonce::from_slice(&iv),
            Payload {
                msg: content.data.as_bytes(),
                aad: &pj_aad(id),
            },
        )
        .ok()?;
    content.data = format!("{PJ_PREFIX}{}", crate::crypto::b64(&key));
    Some((serde_json::to_string(&wire).ok()?, [iv, ct].concat()))
}

/// Remet la pièce chiffrée dans le message ouvert (un `Wire` JSON). Une
/// pièce absente, abîmée ou d'un autre message : les données restent vides.
#[allow(deprecated)] // `Nonce::from_slice`, comme crypto.rs
pub fn join_attachment(plain: &str, blob: Option<&[u8]>) -> String {
    use aes_gcm::aead::{Aead, Payload};
    use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
    let Ok(mut wire) = serde_json::from_str::<Wire>(plain) else {
        return plain.to_owned();
    };
    let Body::Msg { id, content, .. } = &mut wire.body else {
        return plain.to_owned();
    };
    let Some(key) = content.data.strip_prefix(PJ_PREFIX) else {
        return plain.to_owned();
    };
    let opened = (|| {
        let key = crate::crypto::unb64(key)?;
        let blob = blob?;
        if blob.len() < 12 + 16 {
            return None;
        }
        let pt = Aes256Gcm::new_from_slice(&key)
            .ok()?
            .decrypt(
                Nonce::from_slice(&blob[..12]),
                Payload {
                    msg: &blob[12..],
                    aad: &pj_aad(id),
                },
            )
            .ok()?;
        String::from_utf8(pt).ok()
    })();
    content.data = opened.unwrap_or_default();
    serde_json::to_string(&wire).unwrap_or_else(|_| plain.to_owned())
}

/// Pour chercher sans tenir compte des majuscules ni des accents (« ecole »
/// trouve « École »), comme l'application iPhone.
pub fn fold(text: &str) -> String {
    text.to_lowercase()
        .chars()
        .map(|c| match c {
            'à' | 'á' | 'â' | 'ã' | 'ä' | 'å' => 'a',
            'ç' => 'c',
            'è' | 'é' | 'ê' | 'ë' => 'e',
            'ì' | 'í' | 'î' | 'ï' => 'i',
            'ñ' => 'n',
            'ò' | 'ó' | 'ô' | 'õ' | 'ö' => 'o',
            'ù' | 'ú' | 'û' | 'ü' => 'u',
            'ý' | 'ÿ' => 'y',
            'œ' => 'o',
            'æ' => 'a',
            c => c,
        })
        .collect()
}

/// Une copie de `chat` pour l'afficher : tout sauf son fond d'écran (une
/// photo de la galerie, plusieurs centaines de Ko), que les listes
/// n'utilisent pas et qui n'est pas recopié.
pub fn chat_for_list(chat: &mut Chat) -> Chat {
    let wallpaper = std::mem::take(&mut chat.wallpaper);
    let copy = chat.clone();
    chat.wallpaper = wallpaper;
    copy
}

/// Un message écrit avant cette heure peut être trop vieux pour être gardé
/// (`Message::too_old`) ; un message écrit après ne l'est jamais : le ménage
/// ne lit que les premiers.
pub fn too_old_before(keep_days: u32, now: f64) -> f64 {
    now - f64::from(keep_days) * DAY_MS
}

/// Les écritures de messages, comptées par discussion : ce qu'on a lu d'une
/// discussion (la coche de son dernier message) reste vrai tant que son
/// compte n'a pas bougé, sans relire la base.
#[derive(Default)]
pub struct Writes {
    per_chat: HashMap<String, u64>,
    /// Avance quand tout peut avoir changé (une sauvegarde restaurée…).
    epoch: u64,
}

impl Writes {
    /// Un message de `chat` écrit ou effacé.
    pub fn note(&mut self, chat: &str) {
        *self.per_chat.entry(chat.to_owned()).or_default() += 1;
    }

    /// Tout a pu changer.
    pub fn all(&mut self) {
        self.epoch += 1;
        self.per_chat.clear();
    }

    /// L'état de `chat` : différent dès qu'un de ses messages a été écrit.
    pub fn stamp(&self, chat: &str) -> (u64, u64) {
        (self.epoch, self.per_chat.get(chat).copied().unwrap_or(0))
    }
}

/// Les messages effacés de chaque discussion (ses pierres tombales),
/// gardés décodés : chaque message reçu y est cherché, et relire la liste
/// (jusqu'à 20 000) coûtait 0,1 à 2,6 ms. Oubliés dès qu'une liste est
/// écrite ; une liste lue pendant une écriture n'est pas gardée.
#[derive(Default)]
pub struct Tombs {
    per_chat: HashMap<String, Rc<HashSet<String>>>,
    /// Avance à chaque écriture.
    epoch: u64,
}

impl Tombs {
    pub fn get(&self, chat: &str) -> Option<Rc<HashSet<String>>> {
        self.per_chat.get(chat).cloned()
    }

    /// À prendre avant de lire une liste, pour [`Tombs::keep`].
    pub fn epoch(&self) -> u64 {
        self.epoch
    }

    /// La liste de `chat`, lue quand l'époque était `epoch` : gardée si
    /// rien n'a été écrit depuis.
    pub fn keep(&mut self, chat: &str, epoch: u64, ids: HashSet<String>) -> Rc<HashSet<String>> {
        let ids = Rc::new(ids);
        if epoch == self.epoch {
            self.per_chat.insert(chat.to_owned(), ids.clone());
        }
        ids
    }

    /// La liste de `chat` a été écrite.
    pub fn changed(&mut self, chat: &str) {
        self.epoch += 1;
        self.per_chat.remove(chat);
    }

    /// Tout a pu changer.
    pub fn forget(&mut self) {
        self.epoch += 1;
        self.per_chat.clear();
    }
}

/// La prochaine échéance connue des messages éphémères de chaque
/// discussion : le ménage de chaque minute ne relit une discussion que
/// quand l'un d'eux arrive à terme, au lieu de décoder tous ses messages à
/// chaque fois. Une discussion inconnue est relue (et apprise).
#[derive(Default)]
pub struct Expiries {
    next: HashMap<String, f64>,
}

impl Expiries {
    /// Faut-il relire `chat` à l'heure `t` (un message échu, `exp < t`) ?
    pub fn due(&self, chat: &str, t: f64) -> bool {
        self.next.get(chat).is_none_or(|next| *next < t)
    }

    /// `chat` vient d'être relu : `next` est l'échéance la plus proche de ce
    /// qui reste (`None` : aucune).
    pub fn scanned(&mut self, chat: &str, next: Option<f64>) {
        self.next
            .insert(chat.to_owned(), next.unwrap_or(f64::INFINITY));
    }

    /// Un message écrit : son échéance peut être plus proche que celle
    /// connue (une discussion inconnue reste inconnue : elle sera relue).
    pub fn noted(&mut self, chat: &str, exp: Option<f64>) {
        if let (Some(exp), Some(next)) = (exp, self.next.get_mut(chat)) {
            *next = next.min(exp);
        }
    }

    /// Tout oublier (une sauvegarde restaurée, la mémoire effacée).
    pub fn forget(&mut self) {
        self.next.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// La copie d'une discussion pour une liste : tout, sauf son fond
    /// d'écran, qui reste à l'original.
    #[test]
    fn a_chat_for_a_list_is_the_chat_without_its_wallpaper() {
        let mut chat = Chat {
            id: "g:1".into(),
            name: "Famille".into(),
            avatar: "data:image/jpeg;base64,AAAA".into(),
            wallpaper: "data:image/jpeg;base64,WWWW".into(),
            draft: "à demain".into(),
            unread: 3,
            ..Default::default()
        };
        let before = serde_json::to_value(&chat).unwrap();
        let copy = chat_for_list(&mut chat);
        assert_eq!(serde_json::to_value(&chat).unwrap(), before);
        assert!(copy.wallpaper.is_empty());
        let mut expected = before;
        expected["wallpaper"] = "".into();
        assert_eq!(serde_json::to_value(&copy).unwrap(), expected);
    }

    /// Le compte d'une discussion bouge à chaque écriture de l'un de ses
    /// messages, et seulement alors ; tous bougent après une restauration.
    #[test]
    fn writes_tell_when_a_chat_may_have_changed() {
        let mut w = Writes::default();
        let (a, b) = (w.stamp("a"), w.stamp("b"));
        w.note("a");
        assert_ne!(w.stamp("a"), a);
        assert_eq!(w.stamp("b"), b);
        let a = w.stamp("a");
        w.note("a");
        assert_ne!(w.stamp("a"), a);
        let (a, b) = (w.stamp("a"), w.stamp("b"));
        w.all();
        assert_ne!(w.stamp("a"), a);
        assert_ne!(w.stamp("b"), b);
        // Une discussion jamais écrite depuis la restauration garde son état.
        assert_eq!(w.stamp("b"), w.stamp("b"));
    }

    /// Un message est compté important exactement quand `is_starred` le dit.
    /// Un message reçu hors de la discussion ouverte : la discussion,
    /// écrite une seule fois avec son dernier message, garde ses non-lus
    /// et sa mention.
    #[test]
    fn an_unread_message_is_counted_in_the_chat_written_once() {
        let me = "K7Q2MX9PRT";
        let mut c = Chat {
            id: "g:1".into(),
            unread: 2,
            ..Default::default()
        };
        let plain = Message {
            chat: "g:1".into(),
            from: "WGSEAJNSZJ".into(),
            mentions: vec!["AUTREXXXXX".into()],
            ..Default::default()
        };
        c.count_unread(&plain, me);
        assert_eq!((c.unread, c.mentioned), (3, false));
        let named = Message {
            mentions: vec![me.into()],
            ..plain.clone()
        };
        c.count_unread(&named, me);
        assert_eq!((c.unread, c.mentioned), (4, true));
        // Ce qu'écrit `store_message` ensuite : le compte y est.
        let back: Chat = serde_json::from_str(&serde_json::to_string(&c).unwrap()).unwrap();
        assert_eq!((back.unread, back.mentioned), (4, true));
    }

    /// Un accusé déjà su ne réécrit pas le message ; un nouveau, si.
    #[test]
    fn a_receipt_already_known_changes_nothing() {
        let mut m = Message {
            delivered_to: vec!["AAAAAAAAAA".into()],
            ..Default::default()
        };
        assert!(!m.note_receipt("AAAAAAAAAA", false));
        assert!(m.note_receipt("AAAAAAAAAA", true));
        assert!(!m.note_receipt("AAAAAAAAAA", true));
        assert!(!m.note_receipt("AAAAAAAAAA", false));
        assert!(m.note_receipt("BBBBBBBBBB", false));
        assert_eq!(m.delivered_to, ["AAAAAAAAAA", "BBBBBBBBBB"]);
        assert_eq!(m.read_by, ["AAAAAAAAAA"]);
        // Lu sans avoir été dit reçu : reçu aussi, comme avant.
        let mut n = Message::default();
        assert!(n.note_receipt("CCCCCCCCCC", true));
        assert_eq!((n.delivered_to.len(), n.read_by.len()), (1, 1));
    }

    /// Le dernier message d'une discussion relue depuis la base est égal à
    /// celui qu'on recalcule (sinon elle serait réécrite pour rien), et un
    /// dernier message changé ne l'est pas.
    #[test]
    fn a_last_message_read_back_is_the_same_last_message() {
        let last = Last {
            id: "m1".into(),
            from: "K7Q2MX9PRT".into(),
            text: "Bonjour 👋 « à demain »".into(),
            at: 1_764_100_000_123.456,
            kind: "text".into(),
        };
        let chat = Chat {
            id: "g:1".into(),
            last: Some(last.clone()),
            ..Default::default()
        };
        let back: Chat = serde_json::from_str(&serde_json::to_string(&chat).unwrap()).unwrap();
        assert_eq!(back.last, Some(last.clone()));
        let edited = Last {
            text: "Bonjour".into(),
            ..last.clone()
        };
        assert_ne!(back.last, Some(edited));
        assert_ne!(back.last, None);
    }

    /// Une réaction redite, ou retirée alors qu'il n'y en avait pas, ne
    /// réécrit pas le message ; le reste, comme avant.
    #[test]
    fn a_reaction_said_again_changes_nothing() {
        let mut m = Message::default();
        assert!(!m.set_reaction("AAAAAAAAAA", ""));
        assert!(m.set_reaction("AAAAAAAAAA", "👍"));
        assert!(!m.set_reaction("AAAAAAAAAA", "👍"));
        assert!(m.set_reaction("AAAAAAAAAA", "❤️"));
        // Coupée à 8 caractères, comme avant : la même une fois coupée.
        assert!(m.set_reaction("BBBBBBBBBB", "123456789"));
        assert_eq!(m.reactions["BBBBBBBBBB"], "12345678");
        assert!(!m.set_reaction("BBBBBBBBBB", "12345678x"));
        assert!(m.set_reaction("AAAAAAAAAA", ""));
        assert!(!m.set_reaction("AAAAAAAAAA", ""));
        assert_eq!(m.reactions.len(), 1);
    }

    /// Les pierres tombales gardées suivent ce qui est écrit, et une liste
    /// lue pendant une écriture n'est pas gardée.
    #[test]
    fn tombs_are_forgotten_when_written() {
        let ids = |list: &[&str]| list.iter().map(|s| s.to_string()).collect::<HashSet<_>>();
        let mut t = Tombs::default();
        assert!(t.get("g:1").is_none());
        let e = t.epoch();
        assert!(t.keep("g:1", e, ids(&["m1"])).contains("m1"));
        assert!(t.get("g:1").unwrap().contains("m1"));
        t.keep("g:2", t.epoch(), ids(&["m2"]));
        t.changed("g:1");
        assert!(t.get("g:1").is_none());
        assert!(t.get("g:2").is_some());
        // Lue avant une écriture, rendue après : pas gardée.
        let e = t.epoch();
        t.changed("g:1");
        assert!(t.keep("g:1", e, ids(&["vieux"])).contains("vieux"));
        assert!(t.get("g:1").is_none());
        t.forget();
        assert!(t.get("g:2").is_none());
    }

    #[test]
    fn starred_of_agrees_with_is_starred() {
        for starred in [false, true] {
            for deleted in [false, true] {
                let m = Message {
                    id: "m1".into(),
                    at: 7.0,
                    starred,
                    deleted,
                    data: "data:image/jpeg;base64,AAAA".into(),
                    ..Default::default()
                };
                let json = serde_json::to_string(&m).unwrap();
                assert_eq!(
                    starred_of(&json),
                    m.is_starred().then(|| (7.0, "m1".to_owned()))
                );
            }
        }
        assert_eq!(starred_of("pas du JSON"), None);
    }

    /// Une ligne de recherche trouve exactement ce que trouvait le message
    /// entier.
    #[test]
    fn a_search_line_finds_what_the_message_did() {
        let me = "AAAAAAAAAA";
        let now = 1_000.0;
        let q = fold("Été");
        let mut cases = Vec::new();
        for kind in ["text", "system", "image", "file"] {
            for deleted in [false, true] {
                for (text, name) in [
                    ("Un été chaud", ""),
                    ("rien", "photo-ete.jpg"),
                    ("rien", ""),
                ] {
                    for (from, open_at) in [
                        (me, 0.0),
                        (me, 2_000.0),
                        ("BBBBBBBBBB", 2_000.0),
                        ("BBBBBBBBBB", 10.0),
                    ] {
                        cases.push(Message {
                            kind: kind.into(),
                            deleted,
                            text: text.into(),
                            name: name.into(),
                            from: from.into(),
                            open_at,
                            data: "data:image/jpeg;base64,AAAA".into(),
                            ..Default::default()
                        });
                    }
                }
            }
        }
        for m in &cases {
            let old = m.kind != "system"
                && !m.deleted
                && !m.sealed_for(me, now)
                && fold(&format!("{} {}", m.text, m.name)).contains(&q);
            let json = serde_json::to_string(m).unwrap();
            let new = SearchLine::of(&json).is_some_and(|l| l.matches(&q, me, now));
            assert_eq!(new, old, "{m:?}");
        }
    }

    /// Une marque gardée tombe dès que son message est écrit ou retiré, ou
    /// que sa discussion est vidée.
    #[test]
    fn marks_drop_when_their_message_changes() {
        let mark = |at: f64| MessageMark {
            at,
            ..Default::default()
        };
        let mut m = Marks::default();
        assert_eq!(m.get("a|1"), None);
        m.put("a|1", Some(mark(1.0)));
        m.put("a|2", None);
        m.put("b|1", Some(mark(3.0)));
        assert_eq!(m.get("a|1"), Some(Some(mark(1.0))));
        assert_eq!(m.get("a|2"), Some(None));
        m.changed("a|1");
        assert_eq!(m.get("a|1"), None);
        m.changed_chat("a");
        assert_eq!(m.get("a|2"), None);
        assert_eq!(m.get("b|1"), Some(Some(mark(3.0))));
        // « ab » n'est pas « a ».
        m.put("ab|1", None);
        m.changed_chat("a");
        assert_eq!(m.get("ab|1"), Some(None));
        m.forget();
        assert_eq!(m.get("b|1"), None);
    }

    /// Les têtes gardées disent toujours ce que dirait une relecture de la
    /// boîte d'envoi.
    #[test]
    fn kept_heads_follow_every_write() {
        let item = |i: u32, boot: &str| OutItem {
            id: format!("m{i}:B"),
            to: "B".into(),
            payload: "x".repeat(1000),
            msg_id: format!("m{i}"),
            seq: f64::from(i),
            boot: boot.into(),
            ..Default::default()
        };
        let row = |o: &OutItem| (o.id.clone(), serde_json::to_string(o).unwrap());
        let mut base: BTreeMap<String, String> = (0..5).map(|i| row(&item(i, ""))).collect();
        let mut heads = OutHeads::default();
        assert!(heads.get().is_none());
        // Inconnues tant qu'elles n'ont pas été lues : une écriture n'y change rien.
        heads.put("m9:B", &row(&item(9, "")).1);
        assert!(heads.get().is_none());
        let rows: Vec<(String, String)> = base.clone().into_iter().collect();
        assert_eq!(heads.fill(&rows).len(), 5);
        let fresh = |base: &BTreeMap<String, String>| -> Vec<OutHead> {
            base.values()
                .map(|j| serde_json::from_str(j).unwrap())
                .collect()
        };
        for (k, j) in [row(&item(2, "boot")), row(&item(7, ""))] {
            heads.put(&k, &j);
            base.insert(k, j);
        }
        heads.remove("m0:B");
        base.remove("m0:B");
        assert_eq!(heads.get().unwrap(), fresh(&base));
        // Un JSON illisible : tout sera relu.
        heads.put("m3:B", "pas du JSON");
        assert!(heads.get().is_none());
        heads.fill(&base.clone().into_iter().collect::<Vec<_>>());
        heads.forget();
        assert!(heads.get().is_none());
    }

    /// La tête d'un élément de la boîte d'envoi, et la marque d'un message,
    /// disent la même chose que l'élément et le message entiers.
    #[test]
    fn heads_read_like_the_whole_items() {
        let item = OutItem {
            id: "m1:BBBBBBBBBB".into(),
            to: "BBBBBBBBBB".into(),
            payload: "x".repeat(10_000),
            chat: "c".into(),
            msg_id: "m1".into(),
            seq: 12.5,
            boot: "b".into(),
            sent: vec!["d1".into()],
            got: vec!["d2".into()],
        };
        let json = serde_json::to_string(&item).unwrap();
        assert_eq!(serde_json::from_str::<OutHead>(&json).unwrap(), item.head());
        let m = Message {
            at: 3.0,
            deleted: true,
            delivered_to: vec!["BBBBBBBBBB".into()],
            data: "data:image/jpeg;base64,AAAA".into(),
            ..Default::default()
        };
        let mark: MessageMark = serde_json::from_str(&serde_json::to_string(&m).unwrap()).unwrap();
        assert_eq!(
            mark,
            MessageMark {
                at: m.at,
                deleted: m.deleted,
                delivered_to: m.delivered_to.clone(),
            }
        );
    }

    /// Le ménage des vieux messages ne lit que ceux écrits avant la limite :
    /// aucun message écrit après ne peut être trop vieux, capsule comprise.
    #[test]
    fn only_messages_written_before_the_limit_can_be_too_old() {
        let now = 1_000.0 * DAY_MS;
        let limit = too_old_before(90, now);
        for at in [limit - DAY_MS, limit - 1.0, limit, limit + 1.0, now] {
            for open_at in [0.0, at + DAY_MS] {
                for starred in [false, true] {
                    let m = Message {
                        at,
                        open_at,
                        starred,
                        ..Default::default()
                    };
                    if m.too_old(90, now) {
                        assert!(m.at < limit, "{at} {open_at}");
                    }
                }
            }
        }
        let old = Message {
            at: limit - 1.0,
            ..Default::default()
        };
        assert!(old.too_old(90, now));
    }

    /// Le ménage ne relit une discussion qu'à l'échéance de l'un de ses
    /// messages, et n'en manque aucune.
    #[test]
    fn expiries_only_wake_a_chat_when_a_message_is_due() {
        let mut e = Expiries::default();
        // Inconnue : relue.
        assert!(e.due("c", 0.0));
        e.scanned("c", Some(100.0));
        assert!(!e.due("c", 50.0));
        assert!(!e.due("c", 100.0));
        assert!(e.due("c", 100.5));
        // Un message plus pressé, écrit entre-temps.
        e.noted("c", Some(60.0));
        assert!(e.due("c", 61.0));
        // Un plus lointain ne retarde rien ; un sans échéance non plus.
        e.noted("c", Some(500.0));
        e.noted("c", None);
        assert!(e.due("c", 61.0));
        // Plus rien d'éphémère : jamais relue, sauf message nouveau.
        e.scanned("c", None);
        assert!(!e.due("c", 1e15));
        e.noted("c", Some(2e15));
        assert!(e.due("c", 3e15));
        // Une discussion inconnue le reste jusqu'à sa relecture.
        e.noted("d", Some(10.0));
        assert!(e.due("d", 0.0));
        e.forget();
        assert!(e.due("c", 0.0));
    }

    #[test]
    fn groups_in_common_are_shared_and_sorted() {
        let g = |id: &str, name: &str, members: &[&str], left: bool| Chat {
            id: id.into(),
            group: true,
            name: name.into(),
            members: members.iter().map(|m| (*m).to_owned()).collect(),
            left,
            ..Default::default()
        };
        let chats = vec![
            g("g:1", "Voisins", &["MOI", "LEA"], false),
            g("g:2", "amis", &["MOI", "LEA", "BOB"], false),
            g("g:3", "Travail", &["MOI", "BOB"], false),
            g("g:4", "Ancien", &["MOI", "LEA"], true),
            g("g:5", "Sans moi", &["LEA", "BOB"], false),
            Chat {
                id: "LEA".into(),
                ..Default::default()
            },
        ];
        let ids: Vec<_> = groups_in_common(&chats, "MOI", "LEA")
            .iter()
            .map(|c| c.id.as_str())
            .collect();
        assert_eq!(ids, ["g:2", "g:1"]);
        assert!(groups_in_common(&chats, "MOI", "ZOE").is_empty());
    }

    #[test]
    fn photos_newest_first_without_hidden_ones() {
        let m = |id: &str, kind: &str, from: &str, deleted: bool, open_at: f64| Message {
            id: id.into(),
            kind: kind.into(),
            from: from.into(),
            data: "data:image/jpeg;base64,AA==".into(),
            deleted,
            open_at,
            ..Default::default()
        };
        let msgs = vec![
            m("1", "image", "LEA", false, 0.0),
            m("2", "text", "LEA", false, 0.0),
            m("3", "image", "LEA", true, 0.0),
            m("4", "image", "LEA", false, 5_000.0),
            m("5", "image", "MOI", false, 5_000.0),
            m("6", "image", "MOI", false, 0.0),
        ];
        let ids: Vec<_> = photos(&msgs, "MOI", 1_000.0)
            .iter()
            .map(|m| m.id.as_str())
            .collect();
        assert_eq!(ids, ["6", "5", "1"]);
        let ids: Vec<_> = photos(&msgs, "MOI", 9_000.0)
            .iter()
            .map(|m| m.id.as_str())
            .collect();
        assert_eq!(ids, ["6", "5", "4", "1"]);
    }

    #[test]
    fn documents_are_the_files_only() {
        let m = |id: &str, kind: &str, data: &str| Message {
            id: id.into(),
            kind: kind.into(),
            from: "LEA".into(),
            data: data.into(),
            ..Default::default()
        };
        let msgs = vec![
            m("1", "file", "data:application/pdf;base64,AA=="),
            m("2", "image", "data:image/jpeg;base64,AA=="),
            m("3", "file", ""),
            m("4", "voice", "data:audio/webm;base64,AA=="),
            m("5", "file", "data:text/plain;base64,AA=="),
        ];
        let ids: Vec<_> = documents(&msgs, "MOI", 0.0)
            .iter()
            .map(|m| m.id.as_str())
            .collect();
        assert_eq!(ids, ["5", "1"]);
    }

    #[test]
    fn file_detail_shows_a_real_extension_only() {
        let f = |name: &str| Message {
            name: name.into(),
            ..Default::default()
        };
        assert_eq!(f("devis.pdf").file_detail("12 Ko"), "12 Ko · PDF");
        assert_eq!(f("archive.tar.gz").file_detail("3 Mo"), "3 Mo · GZ");
        assert_eq!(f("README").file_detail("1 Ko"), "1 Ko");
        assert_eq!(f("").file_detail("1 Ko"), "1 Ko");
        assert_eq!(f(".profile").file_detail("1 Ko"), "1 Ko");
        assert_eq!(f("fin.").file_detail("1 Ko"), "1 Ko");
        assert_eq!(f("v1.2 compte rendu final").file_detail("1 Ko"), "1 Ko");
        assert_eq!(f("v1.2 fin").file_detail("1 Ko"), "1 Ko");
    }

    #[test]
    fn links_like_the_bubbles() {
        assert_eq!(
            find_links("Regarde https://talka.example/a?b=1, et (http://x.org/y)."),
            ["https://talka.example/a?b=1", "http://x.org/y"]
        );
        assert_eq!(find_links("«https://a.fr»"), ["https://a.fr"]);
        assert!(find_links("pas un lien : xhttps://a.fr ni https://").is_empty());
        let m = |id: &str, from: &str, text: &str, deleted: bool, open_at: f64| Message {
            id: id.into(),
            from: from.into(),
            text: text.into(),
            deleted,
            open_at,
            ..Default::default()
        };
        let msgs = vec![
            m("1", "LEA", "https://a.fr", false, 0.0),
            m("2", "LEA", "https://b.fr https://a.fr", false, 0.0),
            m("3", "LEA", "https://supprime.fr", true, 0.0),
            m("4", "LEA", "https://capsule.fr", false, 5_000.0),
            m("5", "system", "https://systeme.fr", false, 0.0),
        ];
        let got: Vec<_> = links(&msgs, "MOI", 1_000.0)
            .into_iter()
            .map(|(u, m)| (u, m.id.as_str()))
            .collect();
        assert_eq!(
            got,
            [
                ("https://b.fr".to_owned(), "2"),
                ("https://a.fr".to_owned(), "2")
            ]
        );
    }

    #[test]
    fn sizes_like_the_iphone() {
        assert_eq!(format_bytes(0.0), "0 o");
        assert_eq!(format_bytes(25.0), "25 o");
        assert_eq!(format_bytes(1023.0), "1023 o");
        assert_eq!(format_bytes(1024.0), "1,0 Ko");
        assert_eq!(format_bytes(12_000.0), "11,7 Ko");
        assert_eq!(format_bytes(1_572_864.0), "1,5 Mo");
        assert_eq!(format_bytes(-3.0), "0 o");
    }

    #[test]
    fn storage_counts_the_attachments() {
        let m = |kind: &str, data: &str, size: f64, deleted: bool| Message {
            kind: kind.into(),
            data: data.into(),
            size,
            deleted,
            ..Default::default()
        };
        let msgs = vec![
            m("image", "data:image/jpeg;base64,AAAAAAAA", 0.0, false),
            m("image", "data:image/jpeg;base64,AAAA", 0.0, false),
            m("file", "data:application/pdf;base64,AA==", 1000.0, false),
            m("voice", "data:audio/webm;base64,AAAA", 500.0, false),
            m("voice", "data:audio/webm;base64,AAAA", 500.0, true),
            m("text", "", 0.0, false),
        ];
        let st = Storage::of(&msgs);
        assert_eq!(
            st,
            Storage {
                bytes: 6.0 + 3.0 + 1000.0 + 500.0,
                photos: 2,
                files: 1,
                voices: 1
            }
        );
        assert_eq!(st.detail(), "2 photos · 1 document · 1 vocal");
        assert_eq!(Storage::default().detail(), "");
    }

    #[test]
    fn durations_like_the_iphone() {
        assert_eq!(format_duration(65.0), "1:05");
        assert_eq!(format_duration(0.4), "0:00");
        assert_eq!(format_duration(-5.0), "0:00");
        assert_eq!(format_duration(f64::NAN), "0:00");
        assert_eq!(format_duration(f64::INFINITY), "0:00");
        assert_eq!(format_duration(1e300), "5999:59");
    }

    #[test]
    fn new_messages_below_count_the_others_only() {
        let m = |id: &str, from: &str| Message {
            id: id.into(),
            from: from.into(),
            ..Default::default()
        };
        let msgs = vec![
            m("1", "LEA"),
            m("2", "MOI"),
            m("3", "LEA"),
            m("4", "system"),
            m("5", "MOI"),
            m("6", "BOB"),
        ];
        assert_eq!(new_below(&msgs, "2", "MOI"), 2);
        assert_eq!(new_below(&msgs, "6", "MOI"), 0);
        assert_eq!(new_below(&msgs, "inconnu", "MOI"), 0);
    }

    #[test]
    fn save_name_follows_the_real_type() {
        let m = |kind: &str, name: &str, mime: &str, data: &str| Message {
            kind: kind.into(),
            name: name.into(),
            mime: mime.into(),
            data: data.into(),
            ..Default::default()
        };
        assert_eq!(m("file", "devis.pdf", "", "").save_name(), "devis.pdf");
        assert_eq!(
            m("image", "", "", "data:image/jpeg;base64,AA").save_name(),
            "photo-talka.jpg"
        );
        assert_eq!(
            m("image", "", "image/png", "").save_name(),
            "photo-talka.png"
        );
        assert_eq!(
            m("voice", "", "", "data:audio/mp4;base64,AA").save_name(),
            "vocal-talka.m4a"
        );
        assert_eq!(
            m("voice", "", "audio/webm;codecs=opus", "").save_name(),
            "vocal-talka.webm"
        );
        assert_eq!(m("voice", "", "", "").save_name(), "vocal-talka.webm");
        assert_eq!(m("file", "", "", "").save_name(), "fichier-talka");
    }

    /// La limite du relais (`MAX_BODY` côté serveur).
    const MAX_BODY: usize = 8 * 1024 * 1024;

    fn b64_len(n: usize) -> usize {
        n.div_ceil(3) * 4
    }

    #[test]
    fn muted_statuses_are_remembered_per_contact() {
        // Absent des anciens contacts : non ; écrit seulement s'il est mis.
        let old: Contact = serde_json::from_str(r#"{"code":"WGSEAJNSZJ"}"#).unwrap();
        assert!(!old.status_muted);
        assert!(
            !serde_json::to_string(&old)
                .unwrap()
                .contains("status_muted")
        );
        let muted = Contact {
            status_muted: true,
            ..old
        };
        let json = serde_json::to_string(&muted).unwrap();
        assert!(json.contains(r#""status_muted":true"#));
        assert!(serde_json::from_str::<Contact>(&json).unwrap().status_muted);
    }

    #[test]
    fn only_admins_write_when_asked() {
        let mut g = Chat {
            group: true,
            members: vec!["A".into(), "B".into()],
            admins: vec!["A".into()],
            ..Default::default()
        };
        assert!(g.can_write("A") && g.can_write("B"));
        g.admins_only = true;
        assert!(g.can_write("A"));
        assert!(!g.can_write("B"));
        g.left = true;
        assert!(!g.can_write("A"));
        // Une discussion à deux : toujours.
        assert!(Chat::default().can_write("B"));
        // Absent des anciennes discussions : non.
        let old: Chat = serde_json::from_str(r#"{"id":"g:x","group":true}"#).unwrap();
        assert!(!old.admins_only);
    }

    #[test]
    fn archived_chats_come_back_unless_kept() {
        let chat = Chat {
            archived: true,
            ..Default::default()
        };
        assert!(chat.unarchives_on("BOB", "MOI", false));
        // Nos messages, une sourdine, ou le réglage : elle reste archivée.
        assert!(!chat.unarchives_on("MOI", "MOI", false));
        assert!(!chat.unarchives_on("BOB", "MOI", true));
        let muted = Chat {
            muted: true,
            ..chat.clone()
        };
        assert!(!muted.unarchives_on("BOB", "MOI", false));
        // Une discussion pas archivée n'a rien à quitter.
        assert!(!Chat::default().unarchives_on("BOB", "MOI", false));
        // Le réglage : absent des anciens réglages, il vaut « non ».
        let old: Settings = serde_json::from_str(r#"{"theme":"dark"}"#).unwrap();
        assert!(!old.keep_archived);
    }

    #[test]
    fn a_contact_back_online_is_asked_again_for_its_bundles() {
        let mut cache: HashMap<String, (Option<u8>, f64)> = HashMap::new();
        cache.insert("CODE".into(), (None, 1.0));
        cache.insert("CODE/00000000000000a1".into(), (None, 1.0));
        cache.insert("CODE/00000000000000a2".into(), (Some(7), 1.0));
        cache.insert("CODEX/00000000000000a1".into(), (None, 1.0));
        cache.insert("AUTRE".into(), (None, 1.0));
        assert!(forget_missing(&mut cache, "CODE"));
        // Ses absences sont oubliées, à son code comme à ses appareils ; un
        // paquet connu reste, et les autres contacts ne bougent pas.
        let mut left: Vec<&String> = cache.keys().collect();
        left.sort();
        assert_eq!(
            left,
            ["AUTRE", "CODE/00000000000000a2", "CODEX/00000000000000a1"]
        );
        assert!(!forget_missing(&mut cache, "CODE"));
    }

    #[test]
    fn removed_devices_are_remembered() {
        let dev = |d: &str| Device {
            dev: d.into(),
            ..Default::default()
        };
        let mut list = Devices {
            v: 3,
            list: vec![dev("00000000000000a1"), dev("00000000000000a2")],
            gone: vec![],
        };
        // Sans appareil retiré, la liste s'écrit comme avant (et se relit).
        let json = serde_json::to_string(&list).unwrap();
        assert!(!json.contains("gone"));
        assert_eq!(
            serde_json::from_str::<Devices>(r#"{"v":3,"list":[]}"#)
                .unwrap()
                .gone,
            Vec::<String>::new()
        );
        list.remove("00000000000000a2");
        assert_eq!(list.v, 4);
        assert!(!list.has("00000000000000a2"));
        assert_eq!(list.gone, ["00000000000000a2"]);
        // Retirer de nouveau ne le compte qu'une fois ; seize au plus.
        list.remove("00000000000000a2");
        assert_eq!(list.gone.len(), 1);
        for i in 0..20 {
            list.remove(&format!("{i:016x}"));
        }
        assert_eq!(list.gone.len(), MAX_GONE);
        assert_eq!(list.gone[0], format!("{:016x}", 19));
        // Une liste reçue avec des retirés mal formés, ou trop, est refusée.
        let mut mine = Devices::default();
        let mut bad = list.clone();
        bad.gone.push("pas-un-appareil".into());
        assert!(!mine.merge(bad));
        assert!(mine.merge(list.clone()));
        assert_eq!(mine.gone, list.gone);
    }

    #[test]
    fn a_deleted_message_is_no_longer_starred() {
        let m = Message {
            starred: true,
            ..Default::default()
        };
        assert!(m.is_starred());
        let gone = Message { deleted: true, ..m };
        assert!(!gone.is_starred());
    }

    #[test]
    fn old_messages_go_except_starred() {
        let now = 1_800_000_000_000.0;
        let m = |days: f64, starred: bool| Message {
            at: now - days * DAY_MS,
            starred,
            ..Default::default()
        };
        assert!(m(91.0, false).too_old(KEEP_DAYS, now));
        assert!(!m(89.0, false).too_old(KEEP_DAYS, now));
        assert!(
            !m(400.0, true).too_old(KEEP_DAYS, now),
            "les importants restent"
        );
        assert!(!m(4000.0, false).too_old(0, now), "0 : pour toujours");
        assert!(m(31.0, false).too_old(30, now));
        // Une étoile restée sur un message supprimé pour tous ne le garde pas.
        let gone = Message {
            deleted: true,
            ..m(400.0, true)
        };
        assert!(gone.too_old(KEEP_DAYS, now));
        // Une capsule écrite il y a 4 mois, qui s'ouvre dans un mois : gardée,
        // puis comptée à partir de son ouverture.
        let capsule = |opens_in_days: f64| Message {
            open_at: now + opens_in_days * DAY_MS,
            ..m(120.0, false)
        };
        assert!(!capsule(30.0).too_old(KEEP_DAYS, now));
        assert!(!capsule(-10.0).too_old(KEEP_DAYS, now));
        assert!(capsule(-100.0).too_old(KEEP_DAYS, now));
        // Un réglage d'avant cette version : 3 mois.
        let old: Settings = serde_json::from_str(r#"{"theme":"dark"}"#).unwrap();
        assert_eq!(old.keep_days, KEEP_DAYS);
    }

    #[test]
    fn exported_chat_lines() {
        assert_eq!(
            export_line("21/10/2026 23:21", Some("Léa"), "Bonjour"),
            "21/10/2026 23:21 - Léa : Bonjour"
        );
        assert_eq!(
            export_line("21/10/2026 23:22", None, "Léa a rejoint le groupe."),
            "21/10/2026 23:22 - Léa a rejoint le groupe."
        );
        assert_eq!(
            export_filename("Les amis / 2026 : été?"),
            "Talka - Les amis _ 2026 _ été_.txt"
        );
    }

    #[test]
    fn largest_file_fits_in_one_envelope() {
        let file = MAX_FILE as usize;
        // Le message : l'adresse `data:` du fichier, et ce qui l'entoure
        // (nom, type, identifiants, profil…) : 2 Ko suffisent largement.
        let plain = b64_len(file) + "data:application/octet-stream;base64,".len() + 2048;
        // Chiffré : iv (12) ‖ texte ‖ tag (16), en base64.
        let body = b64_len(12 + plain + 16);
        // Scellé (expéditeur caché) : éphémère (65) ‖ iv ‖ {from, body} ‖ tag.
        let sealed = b64_len(65 + 12 + body + 64 + 16);
        assert!(sealed < MAX_BODY, "{sealed} > {MAX_BODY}");
        // En v3 : la pièce chiffrée à côté du message v3 (quelques Ko).
        let pj = b64_len(12 + 16 + b64_len(file) + 64) + 8 * 1024;
        assert!(b64_len(65 + 12 + pj + 64 + 16) < MAX_BODY);
        // L'ancienne limite (4 Mo) ne passait pas.
        let old = b64_len(12 + b64_len(4 * 1024 * 1024) + 16);
        assert!(b64_len(65 + 12 + old + 80) > MAX_BODY);
    }

    #[test]
    fn mute_until_is_optional_in_stored_chats() {
        let old: Chat = serde_json::from_str(r#"{"id":"A","muted":true}"#).unwrap();
        assert!(old.muted);
        assert_eq!(old.mute_until, 0.0);
        assert!(!serde_json::to_string(&old).unwrap().contains("mute_until"));
        let timed = Chat {
            mute_until: 1_800_000_000_000.0,
            ..old
        };
        let back: Chat = serde_json::from_str(&serde_json::to_string(&timed).unwrap()).unwrap();
        assert_eq!(back.mute_until, 1_800_000_000_000.0);
    }

    #[test]
    fn presence_dot_is_on_by_default_even_in_old_settings() {
        let old: Settings = serde_json::from_str(r#"{"theme":"dark","hide_online":true}"#).unwrap();
        assert!(old.presence_dot);
        assert!(old.hide_online);
        let off = Settings {
            presence_dot: false,
            ..Settings::default()
        };
        let back: Settings = serde_json::from_str(&serde_json::to_string(&off).unwrap()).unwrap();
        assert!(!back.presence_dot);
    }

    #[test]
    fn fold_ignores_case_and_accents() {
        assert_eq!(fold("École Où Ça"), "ecole ou ca");
        assert!(fold("Rendez-vous à la Crêperie").contains(&fold("creperie")));
        assert_eq!(fold("👋 OK"), "👋 ok");
    }

    #[test]
    fn wire_roundtrip() {
        let w = Wire {
            body: Body::Msg {
                id: "a".into(),
                at: 1.0,
                pn: "Léa".into(),
                content: Content {
                    kind: "text".into(),
                    text: "Salut".into(),
                    ..Default::default()
                },
            },
            grp: Some(GroupInfo {
                id: "g1".into(),
                name: "Amis".into(),
                ..Default::default()
            }),
            g: None,
            ttl: Some(86400),
        };
        let json = serde_json::to_string(&w).unwrap();
        assert!(json.contains("\"k\":\"msg\""));
        let back: Wire = serde_json::from_str(&json).unwrap();
        assert_eq!(back.group_id().as_deref(), Some("g1"));
        match back.body {
            Body::Msg { content, pn, .. } => {
                assert_eq!(content.text, "Salut");
                assert_eq!(pn, "Léa");
            }
            _ => panic!("msg attendu"),
        }
        let r: Wire = serde_json::from_str(r#"{"k":"status-seen","id":"x"}"#).unwrap();
        assert!(matches!(r.body, Body::StatusSeen { .. }));
    }

    fn msg(kind: &str, text: &str) -> Wire {
        Wire::new(Body::Msg {
            id: "m1".into(),
            at: 1.0,
            pn: "Léa".into(),
            content: Content {
                kind: kind.into(),
                text: text.into(),
                ..Default::default()
            },
        })
    }

    /// Comme la liste des discussions du navigateur, en plus court.
    fn preview(m: &Message) -> String {
        if m.is_capsule() {
            "⏳ Capsule temporelle".into()
        } else if m.kind == "voice" {
            "🎙️ Message vocal".into()
        } else {
            m.text.clone()
        }
    }

    fn apercu(me: &str, name: &str, w: &Wire) -> serde_json::Value {
        serde_json::from_str(&w.apercu(me, name, preview).expect("un aperçu")).unwrap()
    }

    /// L'aperçu des notifications dit qui écrit et quoi ; une capsule ne se
    /// trahit pas, et seuls les messages écrits en ont un.
    #[test]
    fn notification_preview_text() {
        assert_eq!(
            apercu("KD6YMWRFXX", " Léa ", &msg("text", "Bonjour 👋")),
            serde_json::json!({ "n": "Léa", "t": "Bonjour 👋", "c": "KD6YMWRFXX" })
        );
        let v = apercu("KD6YMWRFXX", "", &msg("voice", ""));
        assert_eq!(
            (v["n"].as_str(), v["t"].as_str()),
            (Some("KD6YM-WRFXX"), Some("🎙️ Message vocal"))
        );

        let mut capsule = msg("text", "Le secret");
        if let Body::Msg { content, .. } = &mut capsule.body {
            content.open_at = 9e12;
        }
        assert_eq!(apercu("X", "Léa", &capsule)["t"], "⏳ Capsule temporelle");

        let mut group = msg("text", "À tous");
        group.grp = Some(GroupInfo {
            id: "g1".into(),
            name: "Les amis".into(),
            ..Default::default()
        });
        let g = apercu("X", "Léa", &group);
        assert_eq!(
            (g["n"].as_str(), g["g"].as_str(), g["c"].as_str()),
            (Some("Léa"), Some("Les amis"), Some("g:g1"))
        );
        group.grp = None;
        group.g = Some("g1".into());
        let g = apercu("X", "Léa", &group);
        assert_eq!((g["c"].as_str(), g.get("g")), (Some("g:g1"), None));

        let long = apercu("X", "Léa", &msg("text", &"a".repeat(500)));
        assert_eq!(long["t"].as_str().unwrap().chars().count(), 120);
        assert!(
            Wire::new(Body::Typing { on: true })
                .apercu("X", "Léa", preview)
                .is_none()
        );
    }

    fn group(members: &[&str], admins: &[&str]) -> Chat {
        Chat {
            id: "g:g1".into(),
            group: true,
            name: "Les amis".into(),
            members: members.iter().map(|m| m.to_string()).collect(),
            admins: admins.iter().map(|m| m.to_string()).collect(),
            v: 5,
            ..Default::default()
        }
    }

    fn info(c: &Chat, v: u64) -> GroupInfo {
        GroupInfo {
            id: "g1".into(),
            name: c.name.clone(),
            members: c.members.clone(),
            admins: c.admins.clone(),
            avatar: c.avatar.clone(),
            v,
            desc: Some(c.desc.clone()),
            admins_only: Some(c.admins_only),
        }
    }

    /// Un membre (ou un ancien membre) ne prend pas un groupe en main.
    #[test]
    fn only_admins_change_a_group() {
        let c = group(&["A", "B", "C"], &["A"]);
        // L'admin : oui.
        let mut by_admin = info(&c, 6);
        by_admin.members.push("D".into());
        assert!(c.accepts("A", &by_admin));
        // Un membre qui se fait admin, ou exclut les autres : non.
        let mut coup = info(&c, 6);
        coup.admins = vec!["B".into()];
        assert!(!c.accepts("B", &coup));
        let mut purge = info(&c, 6);
        purge.members = vec!["B".into()];
        assert!(!c.accepts("B", &purge));
        // Un ancien membre, ou un inconnu : non.
        assert!(!c.accepts("Z", &by_admin));
        // Une version au bout des nombres, ou pas plus récente : non.
        assert!(!c.accepts("A", &info(&c, u64::MAX)));
        assert!(!c.accepts("A", &info(&c, 5)));
        // Un membre qui part : oui (et seulement ça).
        let mut leave = info(&c, 6);
        leave.members = vec!["A".into(), "C".into()];
        assert!(c.accepts("B", &leave));
        leave.name = "À moi".into();
        assert!(!c.accepts("B", &leave));
        // … sans toucher à la description (absente : inchangée).
        leave.name = c.name.clone();
        leave.desc = Some("Mon groupe".into());
        assert!(!c.accepts("B", &leave));
        leave.desc = None;
        assert!(c.accepts("B", &leave));
        // … ni à qui peut écrire.
        leave.admins_only = Some(true);
        assert!(!c.accepts("B", &leave));
        leave.admins_only = None;
        assert!(c.accepts("B", &leave));
        let mut only = info(&c, 6);
        only.admins_only = Some(true);
        assert!(c.accepts("A", &only));
        // Le dernier admin part : le premier membre restant le devient.
        let mut last = info(&c, 6);
        last.members = vec!["B".into(), "C".into()];
        last.admins = vec!["B".into()];
        assert!(c.accepts("A", &last));
        let solo = group(&["A", "B"], &["B"]);
        let mut gone = info(&solo, 6);
        gone.members = vec!["A".into()];
        gone.admins = vec!["A".into()];
        assert!(solo.accepts("B", &gone));
    }

    /// Ni adresse distante, ni page déguisée.
    #[test]
    fn received_data_is_only_inline() {
        let png = "data:image/png;base64,iVBORw0KGgo=";
        assert_eq!(safe_data("image", png), png);
        assert_eq!(safe_data("avatar", png), png);
        assert_eq!(safe_data("image", "https://espion.example/x.png?id=1"), "");
        assert_eq!(safe_data("image", "data:image/svg+xml;base64,PHN2Zz4="), "");
        assert_eq!(safe_data("image", "data:text/html;base64,PGgxPg=="), "");
        assert_eq!(
            safe_data("voice", "data:audio/webm;base64,AAAA"),
            "data:audio/webm;base64,AAAA"
        );
        assert_eq!(safe_data("voice", "https://x.example/a.webm"), "");
        assert_eq!(
            safe_data("file", "data:text/html;base64,PGgxPg=="),
            "data:text/html;base64,PGgxPg=="
        );
        assert_eq!(safe_data("file", "https://hameçon.example/facture"), "");
        assert_eq!(safe_data("contact", "data:x"), "");
    }

    /// Une pièce jointe trop grosse pour un jeton v3 : chiffrée à part, sa
    /// clé dans le message ; rendue telle quelle, ou vide si on y touche.
    #[test]
    fn attachments_travel_beside_the_v3_message() {
        let data = format!("data:image/jpeg;base64,{}", "A".repeat(300_000));
        let wire = Wire::new(Body::Msg {
            id: "m1".into(),
            at: 1.0,
            pn: String::new(),
            content: Content {
                kind: "image".into(),
                data: data.clone(),
                ..Default::default()
            },
        });
        let payload = serde_json::to_string(&wire).unwrap();
        let (small, blob) = split_attachment(&payload).unwrap();
        assert!(small.len() < 1_000 && small.contains(PJ_PREFIX));
        assert!(!small.contains("AAAA"));
        let back: Wire = serde_json::from_str(&join_attachment(&small, Some(&blob))).unwrap();
        let Body::Msg { content, .. } = back.body else {
            panic!()
        };
        assert_eq!(content.data, data);
        // Abîmée, absente, ou d'un autre message : vide.
        let mut bad = blob.clone();
        bad[40] ^= 1;
        for b in [Some(bad.as_slice()), None] {
            let w: Wire = serde_json::from_str(&join_attachment(&small, b)).unwrap();
            let Body::Msg { content, .. } = w.body else {
                panic!()
            };
            assert!(content.data.is_empty());
        }
        let other = small.replace("\"m1\"", "\"m2\"");
        let w: Wire = serde_json::from_str(&join_attachment(&other, Some(&blob))).unwrap();
        let Body::Msg { content, .. } = w.body else {
            panic!()
        };
        assert!(content.data.is_empty());
        // Un message sans données, ou autre chose : rien à séparer.
        assert!(
            split_attachment(
                &serde_json::to_string(&Wire::new(Body::Typing { on: true })).unwrap()
            )
            .is_none()
        );
    }

    /// « @ » propose les membres ; l'envoi garde ceux qui sont mentionnés.
    #[test]
    fn mentions() {
        assert_eq!(mention_query("Salut @Lé").as_deref(), Some("Lé"));
        assert_eq!(mention_query("@").as_deref(), Some(""));
        assert_eq!(
            mention_query("écris à moi@exemple.fr"),
            None,
            "pas une adresse"
        );
        assert_eq!(mention_query("Salut @Léa\nça va"), None);
        assert_eq!(mention_query("Salut"), None);
        let members = vec![
            ("AAAAAAAAAA".to_owned(), "Léa Martin".to_owned()),
            ("BBBBBBBBBB".to_owned(), "Bob".to_owned()),
            ("CCCCCCCCCC".to_owned(), String::new()),
        ];
        assert_eq!(
            mentions_in("Merci @lea martin et @Bob ! @Bob", &members),
            vec!["AAAAAAAAAA".to_owned(), "BBBBBBBBBB".to_owned()]
        );
        assert!(mentions_in("Bonjour Bob", &members).is_empty());
        assert!(mentions_in("@ tout le monde", &members).is_empty());
        // Un ancien client ignore le champ ; un message sans mention ne
        // l'écrit pas.
        let c: Content = serde_json::from_str(r#"{"kind":"text","text":"x"}"#).unwrap();
        assert!(c.mentions.is_empty());
        assert!(!serde_json::to_string(&c).unwrap().contains("mentions"));
    }
}
