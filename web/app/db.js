// La mémoire de Talka : IndexedDB, dans le navigateur. Pas de base de données
// ailleurs : ce qui n'est pas ici n'existe pas.
//
// Magasins :
// - kv        : identité, profil, réglages, code de verrouillage
// - contacts  : { code, name, profileName, about, avatar, sign, dh, blocked, addedAt }
// - chats     : { id, type: "direct" | "group", … }
// - messages  : { key: chat|id, chat, id, from, at, kind, … } (index chat+at)
// - outbox    : enveloppes chiffrées pas encore confirmées par le serveur
// - statuses  : { key: owner|id, owner, id, at, … }
// - calls     : { id, peer, video, dir, at, dur, missed }

const NAME = "talka";
const VERSION = 1;
export const STORES = ["kv", "contacts", "chats", "messages", "outbox", "statuses", "calls"];

let dbPromise = null;

function req(r) {
  return new Promise((resolve, reject) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
}

export function open() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const r = indexedDB.open(NAME, VERSION);
    r.onupgradeneeded = () => {
      const db = r.result;
      db.createObjectStore("kv");
      db.createObjectStore("contacts", { keyPath: "code" });
      db.createObjectStore("chats", { keyPath: "id" });
      const messages = db.createObjectStore("messages", { keyPath: "key" });
      messages.createIndex("chat_at", ["chat", "at"]);
      db.createObjectStore("outbox", { keyPath: "id" });
      const statuses = db.createObjectStore("statuses", { keyPath: "key" });
      statuses.createIndex("at", "at");
      const calls = db.createObjectStore("calls", { keyPath: "id" });
      calls.createIndex("at", "at");
    };
    r.onsuccess = () => {
      const db = r.result;
      db.onversionchange = () => db.close();
      resolve(db);
    };
    r.onerror = () => reject(r.error);
    r.onblocked = () => reject(new Error("La base est ouverte dans un autre onglet."));
  });
  return dbPromise;
}

async function store(name, mode = "readonly") {
  const db = await open();
  return db.transaction(name, mode).objectStore(name);
}

export async function get(name, key) {
  return req((await store(name)).get(key));
}

export async function put(name, value, key) {
  const s = await store(name, "readwrite");
  return req(key === undefined ? s.put(value) : s.put(value, key));
}

export async function del(name, key) {
  return req((await store(name, "readwrite")).delete(key));
}

export async function all(name) {
  return req((await store(name)).getAll());
}

export async function clear(name) {
  return req((await store(name, "readwrite")).clear());
}

export const kv = {
  get: (key) => get("kv", key),
  set: (key, value) => put("kv", value, key),
  del: (key) => del("kv", key),
};

// Les messages d'une discussion, du plus ancien au plus récent ; `limit` garde
// les derniers.
export async function messagesOf(chat, limit = Infinity) {
  const s = await store("messages");
  const range = IDBKeyRange.bound([chat, -Infinity], [chat, Infinity]);
  const out = [];
  return new Promise((resolve, reject) => {
    const cursor = s.index("chat_at").openCursor(range, "prev");
    cursor.onsuccess = () => {
      const c = cursor.result;
      if (c && out.length < limit) {
        out.push(c.value);
        c.continue();
      } else {
        resolve(out.reverse());
      }
    };
    cursor.onerror = () => reject(cursor.error);
  });
}

export async function deleteMessagesOf(chat) {
  const s = await store("messages", "readwrite");
  const range = IDBKeyRange.bound([chat, -Infinity], [chat, Infinity]);
  return new Promise((resolve, reject) => {
    const cursor = s.index("chat_at").openCursor(range);
    cursor.onsuccess = () => {
      const c = cursor.result;
      if (c) {
        c.delete();
        c.continue();
      } else resolve();
    };
    cursor.onerror = () => reject(cursor.error);
  });
}

// Tout, pour la sauvegarde.
export async function dump() {
  const out = {};
  for (const name of STORES) {
    if (name === "kv") {
      const s = await store("kv");
      const keys = await req(s.getAllKeys());
      const values = await req((await store("kv")).getAll());
      out.kv = keys.map((k, i) => [k, values[i]]);
    } else {
      out[name] = await all(name);
    }
  }
  return out;
}

export async function restore(data) {
  for (const name of STORES) {
    await clear(name);
    const rows = data[name] || [];
    const db = await open();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(name, "readwrite");
      const s = tx.objectStore(name);
      for (const row of rows) {
        if (name === "kv") s.put(row[1], row[0]);
        else s.put(row);
      }
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
    });
  }
}

export async function wipe() {
  for (const name of STORES) await clear(name);
}

// L'espace occupé, quand le navigateur sait le dire.
export async function usage() {
  try {
    const e = await navigator.storage?.estimate?.();
    return e ? { used: e.usage || 0, quota: e.quota || 0 } : null;
  } catch {
    return null;
  }
}

// Demande au navigateur de ne pas effacer Talka quand la place manque.
export async function persist() {
  try {
    if (navigator.storage?.persisted && (await navigator.storage.persisted())) return true;
    return (await navigator.storage?.persist?.()) || false;
  } catch {
    return false;
  }
}
