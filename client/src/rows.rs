//! Les données de Talka dans Memoire ORM.
//!
//! Une seule table, `rows` : chaque ligne range un objet de `model` (en
//! JSON) avec le magasin auquel il appartient (`store`) et sa clé ; les
//! messages ont en plus `chat` et `at`, pour les lire dans l'ordre par une
//! requête indexée. Le moteur est celui, en mémoire, de Memoire ; ses lignes
//! sont gardées par un [`KeyValueStorage`] : IndexedDB dans le navigateur
//! (voir `store`), `MemoryStorage` dans les tests.
//!
//! | Magasin | Contenu |
//! |---|---|
//! | `kv` | identité, profil, réglages, code de verrouillage |
//! | `contacts` | `Contact` (clé : le code) |
//! | `chats` | `Chat` |
//! | `messages` | `Message` (clé : `chat|id`) |
//! | `outbox` | `OutItem` : les enveloppes pas encore confirmées |
//! | `statuses` | `StatusItem` |
//! | `calls` | `CallEntry` |
//!
//! La sauvegarde (`dump`, `restore`) garde le format d'avant Memoire,
//! `{ magasin: [[clé, json], …] }` (et `[clé, json, chat, at]` pour les
//! messages) : elle passe d'un appareil à l'autre, iPhone compris.

use memoire_orm::prelude::*;
use memoire_orm::web::{KeyValueStorage, LocalStorageDb};
use serde_json::Value;

pub const STORES: [&str; 7] = [
    "kv", "contacts", "chats", "messages", "outbox", "statuses", "calls",
];

/// Une ligne : un objet d'un magasin.
#[derive(Model, Clone, Debug, PartialEq)]
#[memoire(table = "rows")]
pub struct Row {
    /// `magasin/clé` : unique.
    #[memoire(primary_key)]
    pub id: String,
    #[memoire(index)]
    pub store: String,
    pub key: String,
    /// La discussion d'un message (vide ailleurs).
    #[memoire(index)]
    pub chat: String,
    /// L'heure d'un message (0 ailleurs).
    pub at: f64,
    pub json: String,
}

fn id(store: &str, key: &str) -> String {
    format!("{store}/{key}")
}

/// La base de Talka : une base Memoire sur un stockage clé-valeur.
#[derive(Clone)]
pub struct Rows {
    db: LocalStorageDb,
}

impl Rows {
    /// Ouvre la base (et crée sa table la première fois).
    pub async fn open(storage: impl KeyValueStorage) -> memoire_orm::Result<Rows> {
        let db = LocalStorageDb::open_with("talka", storage)?;
        db.create_table_if_not_exists::<Row>().await?;
        Ok(Rows { db })
    }

    pub async fn get(&self, store: &str, key: &str) -> Option<String> {
        Row::find(&self.db, id(store, key))
            .await
            .ok()
            .flatten()
            .map(|r| r.json)
    }

    pub async fn put(&self, store: &str, key: &str, json: String, chat: &str, at: f64) {
        let row = Row {
            id: id(store, key),
            store: store.to_owned(),
            key: key.to_owned(),
            chat: chat.to_owned(),
            at,
            json,
        };
        let _ = row.upsert(&self.db).await;
    }

    pub async fn remove(&self, store: &str, key: &str) {
        let _ = Row::delete_by_key(&self.db, id(store, key)).await;
    }

    /// Les lignes d'un magasin, par clé.
    async fn rows(&self, store: &str) -> Vec<Row> {
        Row::query()
            .filter(Row::STORE.eq(store))
            .order_by(Row::KEY.asc())
            .all(&self.db)
            .await
            .unwrap_or_default()
    }

    /// Chaque objet d'un magasin : `(clé, json)`.
    pub async fn all_keyed(&self, store: &str) -> Vec<(String, String)> {
        self.rows(store)
            .await
            .into_iter()
            .map(|r| (r.key, r.json))
            .collect()
    }

    /// Le JSON de chaque objet d'un magasin.
    pub async fn all(&self, store: &str) -> Vec<String> {
        self.rows(store).await.into_iter().map(|r| r.json).collect()
    }

    pub async fn clear(&self, store: &str) {
        let _ = Row::delete_many()
            .filter(Row::STORE.eq(store))
            .execute(&self.db)
            .await;
    }

    /// Les messages d'une discussion, du plus ancien au plus récent ;
    /// `limit` garde les derniers. La discussion d'abord : Memoire cherche
    /// par l'index du premier terme, et celui du magasin donnerait tous les
    /// messages de l'appareil à trier (3 ms → 0,07 ms par affichage d'une
    /// discussion de 500 messages, quand l'appareil en garde 30 000).
    pub async fn messages_of(&self, chat: &str, limit: usize) -> Vec<String> {
        let mut found = Row::query()
            .filter(Row::CHAT.eq(chat) & Row::STORE.eq("messages"))
            .order_by(Row::AT.desc())
            .limit(u64::try_from(limit).unwrap_or(u64::MAX))
            .all(&self.db)
            .await
            .unwrap_or_default();
        found.reverse();
        found.into_iter().map(|r| r.json).collect()
    }

    /// Les messages d'une discussion écrits avant `at` (la colonne `at` : leur
    /// heure), dans n'importe quel ordre : le ménage des vieux messages ne
    /// décode qu'eux, pas toute la discussion.
    pub async fn messages_before(&self, chat: &str, at: f64) -> Vec<String> {
        Row::query()
            .filter(Row::CHAT.eq(chat) & Row::STORE.eq("messages") & Row::AT.lt(at))
            .all(&self.db)
            .await
            .unwrap_or_default()
            .into_iter()
            .map(|r| r.json)
            .collect()
    }

    pub async fn delete_messages_of(&self, chat: &str) {
        let _ = Row::delete_many()
            .filter(Row::CHAT.eq(chat) & Row::STORE.eq("messages"))
            .execute(&self.db)
            .await;
    }

    /// Le nombre de lignes, tous magasins confondus.
    pub async fn count(&self) -> u64 {
        Row::query().count(&self.db).await.unwrap_or(0)
    }

    /// Tout, pour la sauvegarde : `{ magasin: [[clé, json], …] }`.
    pub async fn dump(&self) -> Value {
        let mut out = serde_json::Map::new();
        for name in STORES {
            let rows = self
                .rows(name)
                .await
                .into_iter()
                .map(|r| {
                    let mut row = vec![Value::String(r.key), Value::String(r.json)];
                    if name == "messages" {
                        row.push(Value::String(r.chat));
                        row.push(Value::from(r.at));
                    }
                    Value::Array(row)
                })
                .collect();
            out.insert(name.to_owned(), Value::Array(rows));
        }
        Value::Object(out)
    }

    /// Remplace tout par une sauvegarde (le format de `dump`).
    pub async fn restore(&self, data: &Value) {
        self.wipe().await;
        for name in STORES {
            for row in data[name].as_array().into_iter().flatten() {
                let (Some(key), Some(json)) = (row[0].as_str(), row[1].as_str()) else {
                    continue;
                };
                let (chat, at) = if name == "messages" {
                    (
                        row[2].as_str().unwrap_or_default(),
                        row[3].as_f64().unwrap_or(0.0),
                    )
                } else {
                    ("", 0.0)
                };
                self.put(name, key, json.to_owned(), chat, at).await;
            }
        }
    }

    pub async fn wipe(&self) {
        let _ = Row::delete_many().execute(&self.db).await;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use memoire_orm::web::MemoryStorage;

    /// Une base par test : chaque `MemoryStorage` est un stockage à part.
    fn rows(storage: &MemoryStorage) -> Rows {
        memoire_orm::block_on(Rows::open(storage.clone())).expect("base")
    }

    #[test]
    fn read_write_and_messages_in_order() {
        let storage = MemoryStorage::new();
        let db = rows(&storage);
        memoire_orm::block_on(async {
            db.put("kv", "profile", r#"{"name":"Léa"}"#.into(), "", 0.0)
                .await;
            assert_eq!(
                db.get("kv", "profile").await.as_deref(),
                Some(r#"{"name":"Léa"}"#)
            );
            db.put("kv", "profile", r#"{"name":"Lea"}"#.into(), "", 0.0)
                .await;
            assert_eq!(
                db.get("kv", "profile").await.as_deref(),
                Some(r#"{"name":"Lea"}"#)
            );
            for (i, at) in [(1, 30.0), (2, 10.0), (3, 20.0)] {
                db.put("messages", &format!("g:x|m{i}"), format!("m{i}"), "g:x", at)
                    .await;
            }
            db.put("messages", "AUTRE|m9", "m9".into(), "AUTRE", 5.0)
                .await;
            // Rangé dans un autre magasin sous la même discussion : pas un message.
            db.put("outbox", "o1", "o1".into(), "g:x", 40.0).await;
            assert_eq!(db.messages_of("g:x", 10).await, ["m2", "m3", "m1"]);
            assert_eq!(db.messages_of("g:x", 2).await, ["m3", "m1"]);
            let mut before = db.messages_before("g:x", 30.0).await;
            before.sort();
            assert_eq!(before, ["m2", "m3"]);
            assert!(db.messages_before("g:x", 10.0).await.is_empty());
            assert_eq!(db.messages_before("AUTRE", 100.0).await, ["m9"]);
            db.delete_messages_of("g:x").await;
            assert!(db.messages_of("g:x", 10).await.is_empty());
            assert_eq!(db.get("outbox", "o1").await.as_deref(), Some("o1"));
            assert_eq!(db.messages_of("AUTRE", 10).await, ["m9"]);
            db.remove("kv", "profile").await;
            assert_eq!(db.get("kv", "profile").await, None);
        });
    }

    /// L'écran des messages importants les lit discussion par discussion
    /// (`core::starred`) : ce sont bien ceux qu'on trouvait en lisant tous
    /// les messages de l'appareil.
    #[test]
    fn the_starred_of_each_chat_are_the_starred_of_all() {
        use crate::model::{Message, starred_of};
        let db = rows(&MemoryStorage::new());
        memoire_orm::block_on(async {
            for i in 0..40 {
                let chat = format!("g:{}", i % 3);
                let m = Message {
                    chat: chat.clone(),
                    id: format!("m{i}"),
                    at: f64::from(i),
                    starred: i % 4 == 1,
                    deleted: i % 8 == 5,
                    data: if i % 5 == 0 {
                        "data:image/png;base64,AAAA".into()
                    } else {
                        String::new()
                    },
                    ..Default::default()
                };
                let json = serde_json::to_string(&m).unwrap();
                db.put("messages", &format!("{chat}|m{i}"), json, &chat, m.at)
                    .await;
            }
            // Un favori rangé ailleurs que dans les messages ne compte pas.
            db.put(
                "outbox",
                "o1",
                r#"{"id":"o1","starred":true}"#.into(),
                "g:0",
                1.0,
            )
            .await;
            let mut all: Vec<(f64, String)> = db
                .all("messages")
                .await
                .iter()
                .filter_map(|j| serde_json::from_str::<Message>(j).ok())
                .filter(Message::is_starred)
                .map(|m| (m.at, m.id))
                .collect();
            let mut each = Vec::new();
            for chat in ["g:0", "g:1", "g:2"] {
                for json in db.messages_of(chat, usize::MAX).await {
                    each.extend(starred_of(&json));
                }
            }
            all.sort_by(|a, b| b.0.total_cmp(&a.0));
            each.sort_by(|a, b| b.0.total_cmp(&a.0));
            assert_eq!(all.len(), 5);
            assert_eq!(each, all);
        });
    }

    #[test]
    fn rows_survive_a_reopen_from_the_storage() {
        let storage = MemoryStorage::new();
        memoire_orm::block_on(async {
            let db = Rows::open(storage.clone()).await.expect("base");
            db.put(
                "contacts",
                "WGSEAJNSZJ",
                r#"{"code":"WGSEAJNSZJ"}"#.into(),
                "",
                0.0,
            )
            .await;
            // Ce que l'appareil garde : une clé par ligne, lisible.
            assert!(
                storage
                    .snapshot()
                    .keys()
                    .any(|k| k.starts_with("talka:rows:"))
            );
            db.db.close();
            let again = Rows::open(storage.clone()).await.expect("base");
            assert_eq!(again.all("contacts").await, [r#"{"code":"WGSEAJNSZJ"}"#]);
        });
    }

    #[test]
    fn backup_keeps_the_old_format() {
        let storage = MemoryStorage::new();
        let db = rows(&storage);
        let backup = serde_json::json!({
            "kv": [["identity", "{\"code\":\"X\"}"]],
            "contacts": [],
            "chats": [["g:1", "{\"id\":\"g:1\"}"]],
            "messages": [["g:1|m1", "{\"id\":\"m1\"}", "g:1", 12.5]],
            "outbox": [], "statuses": [], "calls": []
        });
        memoire_orm::block_on(async {
            db.put("calls", "old", "{}".into(), "", 0.0).await;
            db.restore(&backup).await;
            assert_eq!(db.dump().await, backup);
            assert_eq!(db.count().await, 3);
            db.wipe().await;
            assert_eq!(db.count().await, 0);
        });
    }
}
