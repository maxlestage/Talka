//! Ce que garde l'appareil (IndexedDB) et ce qui voyage, chiffré, entre
//! appareils (`Wire`). Tout est (dé)sérialisé avec serde.

use std::collections::BTreeMap;

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
    /// Sur GitHub Pages sans relais configuré : l'adresse choisie.
    pub server: String,
    /// Les messages plus vieux que ça (en jours) s'effacent de l'appareil,
    /// sauf les messages importants (⭐). 0 : gardés pour toujours.
    pub keep_days: u32,
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
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
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
    pub created_by: String,
    pub created_at: f64,
    pub last_at: f64,
    pub unread: u32,
    pub marked_unread: bool,
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
}

pub(crate) fn is_zero(v: &f64) -> bool {
    *v == 0.0
}

/// Par défaut, les messages s'effacent au bout de 3 mois.
pub const KEEP_DAYS: u32 = 90;
const DAY_MS: f64 = 86_400_000.0;

impl Message {
    /// Trop vieux pour être gardé (`keep_days` jours, 0 : jamais) ? Les
    /// messages importants (⭐) restent.
    pub fn too_old(&self, keep_days: u32, now: f64) -> bool {
        keep_days > 0 && !self.starred && self.at < now - f64::from(keep_days) * DAY_MS
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
}

/// Ce qui voyage, chiffré, d'un appareil à l'autre.
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(tag = "k", rename_all = "kebab-case")]
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
}

/// La taille maximale d'un document : 3 Mo. Encodé deux fois en base64 (dans
/// le message, puis une fois chiffré), il fait environ 5,4 Mo, sous la
/// limite de 6 Mo du relais (`MAX_BODY`). À 4 Mo, l'envoi échouait.
pub const MAX_FILE: f64 = 3.0 * 1024.0 * 1024.0;

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

#[cfg(test)]
mod tests {
    use super::*;

    /// La limite du relais (`MAX_BODY` côté serveur).
    const MAX_BODY: usize = 6 * 1024 * 1024;

    fn b64_len(n: usize) -> usize {
        n.div_ceil(3) * 4
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
        // Un réglage d'avant cette version : 3 mois.
        let old: Settings = serde_json::from_str(r#"{"theme":"dark"}"#).unwrap();
        assert_eq!(old.keep_days, KEEP_DAYS);
    }

    #[test]
    fn largest_file_fits_in_one_envelope() {
        let file = MAX_FILE as usize;
        // Le message : l'adresse `data:` du fichier, et ce qui l'entoure
        // (nom, type, identifiants, profil…) : 2 Ko suffisent largement.
        let plain = b64_len(file) + "data:application/octet-stream;base64,".len() + 2048;
        // Chiffré : iv (12) ‖ texte ‖ tag (16), en base64.
        let body = b64_len(12 + plain + 16);
        assert!(body < MAX_BODY, "{body} > {MAX_BODY}");
        // L'ancienne limite (4 Mo) ne passait pas.
        let old = b64_len(12 + b64_len(4 * 1024 * 1024) + 16);
        assert!(old > MAX_BODY);
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
}
