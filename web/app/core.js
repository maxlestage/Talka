// Le cœur de Talka : contacts, discussions, messages, groupes, statuts.
// Tout est rangé dans IndexedDB (db.js) ; le relais ne voit que des
// enveloppes chiffrées (crypto.js, net.js).
//
// Un message part en autant d'enveloppes que de destinataires (une seule
// dans une discussion à deux) : chacune est chiffrée pour son destinataire,
// au moment de partir. En attendant, elle est gardée dans `outbox` : un
// message écrit hors ligne part tout seul au retour du réseau.

import * as db from "./db.js";
import * as cr from "./crypto.js";
import { Net } from "./net.js";

export const DAY = 24 * 3600 * 1000;
export const STATUS_TTL = DAY;

const bus = new EventTarget();
export function on(type, fn) {
  const h = (e) => fn(e.detail);
  bus.addEventListener(type, h);
  return () => bus.removeEventListener(type, h);
}
export function emit(type, detail) {
  bus.dispatchEvent(new CustomEvent(type, { detail }));
}

export const state = {
  me: null, // identité + clés
  profile: { name: "", about: "Disponible", avatar: "", v: 0 },
  settings: {
    theme: "auto",
    readReceipts: true,
    hideOnline: false,
    notify: false,
    sound: true,
    enterSends: true,
    wallpaper: "doodle",
    fontSize: 15,
    lockAfter: 0,
  },
  contacts: new Map(),
  chats: new Map(),
  presence: new Map(), // code → { online, seen }
  typing: new Map(), // chat → Map(code → timeout)
  activeChat: null,
  net: null,
};

export const isGroup = (chatId) => chatId?.startsWith("g:");

// ----- Démarrage -------------------------------------------------------------

export async function hasAccount() {
  return !!(await db.kv.get("identity"));
}

export async function createAccount(name, avatar) {
  const identity = await cr.createIdentity();
  await db.kv.set("identity", identity);
  state.profile = { name: name.trim().slice(0, 40), about: "Disponible sur Talka", avatar: avatar || "", v: 1 };
  await db.kv.set("profile", state.profile);
  await db.persist();
}

export async function start() {
  const identity = await db.kv.get("identity");
  state.me = await cr.loadIdentity(identity);
  state.profile = { ...state.profile, ...((await db.kv.get("profile")) || {}) };
  state.settings = { ...state.settings, ...((await db.kv.get("settings")) || {}) };
  for (const c of await db.all("contacts")) state.contacts.set(c.code, c);
  for (const c of await db.all("chats")) state.chats.set(c.id, c);

  const net = new Net(state.me);
  net.hidden = state.settings.hideOnline;
  state.net = net;
  net.addEventListener("state", (e) => {
    emit("net", e.detail);
    if (e.detail === "online") {
      watchContacts();
      flush();
      refreshMissingKeys();
    }
  });
  net.addEventListener("msg", (e) => receive(e.detail));
  net.addEventListener("presence", (e) => {
    const { code, online, seen } = e.detail;
    state.presence.set(code, { online, seen });
    emit("presence", code);
  });
  net.connect();
  await sweep();
  setInterval(sweep, 60 * 1000);
}

function watchContacts() {
  const codes = new Set([...state.contacts.keys()]);
  for (const chat of state.chats.values()) if (!isGroup(chat.id)) codes.add(chat.id);
  state.net.watch([...codes]);
}

// Les messages éphémères échus et les statuts de plus de 24 heures.
async function sweep() {
  const now = Date.now();
  for (const chat of state.chats.values()) {
    if (!chat.timer && !chat.hasExp) continue;
    const list = await db.messagesOf(chat.id);
    let changed = false;
    for (const m of list) {
      if (m.exp && m.exp < now) {
        await db.del("messages", m.key);
        changed = true;
      }
    }
    if (changed) {
      await refreshLast(chat.id);
      emit("messages", chat.id);
    }
  }
  for (const s of await db.all("statuses")) {
    if (s.at + STATUS_TTL < now) await db.del("statuses", s.key);
  }
  emit("statuses");
}

export async function saveSettings(patch) {
  Object.assign(state.settings, patch);
  await db.kv.set("settings", state.settings);
  if ("hideOnline" in patch) state.net?.setHidden(patch.hideOnline);
  emit("settings");
}

export async function saveProfile(patch) {
  Object.assign(state.profile, patch, { v: (state.profile.v || 0) + 1 });
  await db.kv.set("profile", state.profile);
  emit("profile");
  // Vos contacts voient le nouveau nom, la nouvelle photo.
  for (const c of state.contacts.values()) {
    if (c.saved && !c.blocked) enqueue(c.code, profilePayload(), {});
  }
  flush();
}

function profilePayload() {
  const { name, about, avatar, v } = state.profile;
  return { k: "profile", name, about, avatar, v };
}

// ----- Contacts --------------------------------------------------------------

export function displayName(code) {
  if (code === state.me?.code) return "Vous";
  const c = state.contacts.get(code);
  if (c?.name) return c.name;
  if (c?.profileName) return `~${c.profileName}`;
  return cr.pretty(code);
}

export function avatarOf(code) {
  if (code === state.me?.code) return state.profile.avatar;
  return state.contacts.get(code)?.avatar || "";
}

async function saveContact(c) {
  state.contacts.set(c.code, c);
  await db.put("contacts", c);
  emit("contacts", c.code);
}

// Les clés d'un code, vérifiées : le code doit être leur empreinte.
async function keysFor(code) {
  const c = state.contacts.get(code);
  if (c?.sign && c?.dh) return c;
  const found = await state.net.lookup(code);
  if (!found || !(await cr.verifyKeys(code, found.sign, found.dh))) return null;
  // Relu après l'attente : un nom donné entre-temps n'est pas écrasé.
  const now = state.contacts.get(code);
  const updated = { ...(now || { code, saved: false, addedAt: Date.now() }), sign: found.sign, dh: found.dh };
  await saveContact(updated);
  return updated;
}

async function refreshMissingKeys() {
  for (const c of state.contacts.values()) {
    if (!c.sign) {
      try {
        if (await keysFor(c.code)) flush();
      } catch {
        /* hors ligne : plus tard */
      }
    }
  }
}

// Ajoute un contact par son code. Renvoie le contact (ses clés peuvent
// manquer si le relais ne connaît pas encore ce code).
export async function addContact(code, name) {
  if (code === state.me.code) throw new Error("C'est votre propre code 🙂");
  const existing = state.contacts.get(code);
  const c = { ...(existing || { code, addedAt: Date.now() }), saved: true, name: (name || "").trim().slice(0, 40) || existing?.name || "" };
  await saveContact(c);
  state.net.watch([code]);
  let keyed = null;
  try {
    keyed = await keysFor(code);
  } catch {
    /* hors ligne */
  }
  enqueue(code, profilePayload(), {});
  flush();
  return { contact: state.contacts.get(code), reachable: !!keyed };
}

export async function updateContact(code, patch) {
  const c = state.contacts.get(code);
  if (!c) return;
  await saveContact({ ...c, ...patch });
  emit("chats");
}

export async function removeContact(code) {
  const c = state.contacts.get(code);
  if (!c) return;
  await saveContact({ ...c, saved: false, name: "" });
}

export async function setBlocked(code, blocked) {
  const c = state.contacts.get(code) || { code, addedAt: Date.now(), saved: false };
  await saveContact({ ...c, blocked });
  emit("chats");
}

// ----- Discussions -----------------------------------------------------------

async function saveChat(chat) {
  state.chats.set(chat.id, chat);
  await db.put("chats", chat);
  emit("chats");
}

export async function ensureDirectChat(code) {
  let chat = state.chats.get(code);
  if (!chat) {
    chat = { id: code, type: "direct", createdAt: Date.now(), lastAt: Date.now(), unread: 0 };
    await saveChat(chat);
    state.net.watch([code]);
  }
  return chat;
}

export async function updateChat(id, patch) {
  const chat = state.chats.get(id);
  if (!chat) return;
  await saveChat({ ...chat, ...patch });
}

export async function deleteChat(id) {
  await db.deleteMessagesOf(id);
  state.chats.delete(id);
  await db.del("chats", id);
  emit("chats");
}

export async function clearChat(id) {
  await db.deleteMessagesOf(id);
  await updateChat(id, { last: null, unread: 0 });
  emit("messages", id);
}

export function chatTitle(chat) {
  if (!chat) return "";
  return chat.type === "group" ? chat.name || "Groupe" : displayName(chat.id);
}

export function recipientsOf(chat) {
  if (chat.type === "group") return (chat.members || []).filter((c) => c !== state.me.code);
  return [chat.id];
}

function preview(m) {
  if (m.deleted) return "🚫 Message supprimé";
  switch (m.kind) {
    case "image":
      return `📷 ${m.text || "Photo"}`;
    case "voice":
      return `🎙️ Message vocal (${fmtDur(m.dur)})`;
    case "file":
      return `📄 ${m.name || "Fichier"}`;
    case "contact":
      return `👤 ${m.name || "Contact"}`;
    case "system":
      return m.text;
    default:
      return m.text || "";
  }
}

export function fmtDur(s) {
  s = Math.round(s || 0);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

async function refreshLast(chatId) {
  const chat = state.chats.get(chatId);
  if (!chat) return;
  const [m] = await db.messagesOf(chatId, 1);
  chat.last = m ? { id: m.id, from: m.from, text: preview(m), at: m.at, kind: m.kind } : null;
  await saveChat(chat);
}

async function storeMessage(m, { bump = true } = {}) {
  m.key = `${m.chat}|${m.id}`;
  await db.put("messages", m);
  const chat = state.chats.get(m.chat);
  if (chat && bump) {
    chat.last = { id: m.id, from: m.from, text: preview(m), at: m.at, kind: m.kind };
    chat.lastAt = Math.max(chat.lastAt || 0, m.at);
    if (chat.archived && m.from !== state.me.code && !chat.muted) chat.archived = false;
    if (m.exp) chat.hasExp = true;
    await saveChat(chat);
  }
  emit("messages", m.chat);
}

async function getMessage(chatId, id) {
  return db.get("messages", `${chatId}|${id}`);
}

export async function messages(chatId, limit) {
  return db.messagesOf(chatId, limit);
}

// ----- Envoi ----------------------------------------------------------------

let outSeq = 0;

// Une enveloppe à envoyer à `to` : gardée jusqu'à ce que le relais confirme.
function enqueue(to, payload, { chat = null, msgId = null, live = false } = {}) {
  const item = {
    id: `${msgId || cr.randomId(10)}:${to}`,
    to,
    payload,
    chat,
    msgId,
    live,
    seq: Date.now() * 1000 + (outSeq++ % 1000),
  };
  if (live) {
    sendNow(item).catch(() => {});
    return;
  }
  db.put("outbox", item).then(() => flush());
}

async function sendNow(item) {
  const peer = await keysFor(item.to);
  if (!peer) throw Object.assign(new Error("unknown"), { code: "unknown" });
  const body = await cr.seal(state.me, peer, item.payload);
  return state.net.send(item.id, item.to, body, item.live);
}

let flushing = false;
let flushAgain = false;

export async function flush() {
  if (!state.net || state.net.state !== "online") return;
  if (flushing) {
    flushAgain = true;
    return;
  }
  flushing = true;
  try {
    do {
      flushAgain = false;
      const items = (await db.all("outbox")).sort((a, b) => a.seq - b.seq);
      for (const item of items) {
        if (state.net.state !== "online") return;
        try {
          await sendNow(item);
          await db.del("outbox", item.id);
          if (item.msgId) await markSent(item.chat, item.msgId, item.to);
        } catch (e) {
          if (e.code === "size" || e.code === "code") {
            await db.del("outbox", item.id);
            if (item.msgId) await markFailed(item.chat, item.msgId);
          }
          // "unknown" : le destinataire n'a jamais ouvert Talka depuis le
          // redémarrage du relais ; l'enveloppe attend ses clés.
          if (e.message === "offline" || e.message === "timeout") return;
        }
      }
    } while (flushAgain);
  } finally {
    flushing = false;
  }
}

async function markSent(chatId, msgId, to) {
  const m = await getMessage(chatId, msgId);
  if (!m) return;
  m.sentTo = [...new Set([...(m.sentTo || []), to])];
  if ((m.to || []).every((c) => m.sentTo.includes(c)) && m.status === "pending") m.status = "sent";
  await db.put("messages", m);
  emit("messages", chatId);
}

async function markFailed(chatId, msgId) {
  const m = await getMessage(chatId, msgId);
  if (!m) return;
  m.status = "failed";
  await db.put("messages", m);
  emit("messages", chatId);
}

function groupInfo(chat) {
  return { id: chat.id.slice(2), name: chat.name, members: chat.members, admins: chat.admins, avatar: chat.avatar || "", v: chat.v || 1 };
}

// Ce qui accompagne chaque envoi dans une discussion : le groupe, la durée
// de vie des messages éphémères.
function envelopeFor(chat, payload) {
  const out = { ...payload };
  if (chat.type === "group") out.grp = groupInfo(chat);
  if (chat.timer) out.ttl = chat.timer;
  return out;
}

// Envoie un message dans une discussion.
// `content` : { kind, text, data, name, mime, size, dur, w, h, reply, fwd, code }
export async function sendMessage(chatId, content) {
  const chat = state.chats.get(chatId);
  if (!chat) return;
  const id = cr.randomId(12);
  const at = state.net.now();
  const to = recipientsOf(chat).filter((c) => !state.contacts.get(c)?.blocked);
  const m = {
    chat: chatId,
    id,
    from: state.me.code,
    at,
    status: to.length ? "pending" : "sent",
    to,
    sentTo: [],
    deliveredTo: [],
    readBy: [],
    reactions: {},
    ...content,
  };
  if (chat.timer) m.exp = at + chat.timer * 1000;
  await storeMessage(m);
  // Le nom choisi voyage avec le message : un membre de groupe qui ne vous
  // connaît pas voit « ~Alice » plutôt qu'un code.
  const payload = envelopeFor(chat, { k: "msg", id, at, pn: state.profile.name, ...content });
  for (const code of to) enqueue(code, payload, { chat: chatId, msgId: id });
  if (chat.draft) await updateChat(chatId, { draft: "" });
  return m;
}

// Une action sur un message déjà envoyé (réaction, modification, suppression…),
// pour tous les membres de la discussion.
function broadcast(chat, payload, { live = false } = {}) {
  const env = envelopeFor(chat, payload);
  for (const code of recipientsOf(chat)) {
    if (!state.contacts.get(code)?.blocked) enqueue(code, env, { live });
  }
}

export async function react(chatId, msgId, emoji) {
  const chat = state.chats.get(chatId);
  const m = await getMessage(chatId, msgId);
  if (!chat || !m) return;
  m.reactions = m.reactions || {};
  if (m.reactions[state.me.code] === emoji) emoji = "";
  if (emoji) m.reactions[state.me.code] = emoji;
  else delete m.reactions[state.me.code];
  await db.put("messages", m);
  emit("messages", chatId);
  broadcast(chat, { k: "react", id: msgId, emoji });
}

export async function editMessage(chatId, msgId, text) {
  const chat = state.chats.get(chatId);
  const m = await getMessage(chatId, msgId);
  if (!chat || !m || m.from !== state.me.code) return;
  m.text = text;
  m.edited = true;
  await db.put("messages", m);
  await refreshLast(chatId);
  emit("messages", chatId);
  broadcast(chat, { k: "edit", id: msgId, text });
}

export async function deleteMessage(chatId, msgId, forEveryone) {
  const chat = state.chats.get(chatId);
  const m = await getMessage(chatId, msgId);
  if (!chat || !m) return;
  if (forEveryone && m.from === state.me.code) {
    Object.assign(m, { deleted: true, text: "", data: "", reactions: {} });
    await db.put("messages", m);
    broadcast(chat, { k: "delete", id: msgId });
  } else {
    await db.del("messages", m.key);
  }
  await refreshLast(chatId);
  emit("messages", chatId);
}

export async function toggleStar(chatId, msgId) {
  const m = await getMessage(chatId, msgId);
  if (!m) return;
  m.starred = !m.starred;
  await db.put("messages", m);
  emit("messages", chatId);
}

export async function starredMessages() {
  return (await db.all("messages")).filter((m) => m.starred).sort((a, b) => b.at - a.at);
}

export async function forward(m, chatIds) {
  const content = { kind: m.kind, text: m.text, data: m.data, name: m.name, mime: m.mime, size: m.size, dur: m.dur, w: m.w, h: m.h, code: m.code, fwd: true };
  for (const id of chatIds) await sendMessage(id, content);
}

let typingSent = 0;
export function typing(chatId, on) {
  const chat = state.chats.get(chatId);
  if (!chat || state.net?.state !== "online") return;
  const now = Date.now();
  if (on && now - typingSent < 2500) return;
  typingSent = on ? now : 0;
  broadcast(chat, { k: "typing", on }, { live: true });
}

export async function setTimer(chatId, secs) {
  const chat = state.chats.get(chatId);
  if (!chat) return;
  await updateChat(chatId, { timer: secs || 0 });
  await systemMessage(chatId, timerText(state.me.code, secs));
  broadcast(state.chats.get(chatId), { k: "timer", secs: secs || 0 });
}

function timerText(code, secs) {
  const who = code === state.me.code ? "Vous avez" : `${displayName(code)} a`;
  if (!secs) return `⏳ ${who} désactivé les messages éphémères.`;
  const label = secs >= 90 * 86400 ? "90 jours" : secs >= 7 * 86400 ? "7 jours" : "24 heures";
  return `⏳ ${who} activé les messages éphémères : ${label}.`;
}

async function systemMessage(chatId, text, at = Date.now()) {
  await storeMessage({ chat: chatId, id: cr.randomId(12), from: "system", at, kind: "system", text });
}

// ----- Groupes ---------------------------------------------------------------

export async function createGroup(name, members, avatar = "") {
  const id = `g:${cr.randomId(12)}`;
  const chat = {
    id,
    type: "group",
    name: name.trim().slice(0, 60),
    avatar,
    members: [state.me.code, ...members],
    admins: [state.me.code],
    createdBy: state.me.code,
    createdAt: Date.now(),
    lastAt: Date.now(),
    unread: 0,
    v: 1,
  };
  await saveChat(chat);
  await systemMessage(id, `Vous avez créé le groupe « ${chat.name} ».`);
  broadcast(chat, { k: "group", op: "create" });
  return chat;
}

export async function updateGroup(chatId, patch, note) {
  const chat = state.chats.get(chatId);
  if (!chat) return;
  const before = chat.members || [];
  const next = { ...chat, ...patch, v: (chat.v || 1) + 1 };
  await saveChat(next);
  if (note) await systemMessage(chatId, note);
  // Les anciens membres retirés l'apprennent aussi.
  const removed = before.filter((c) => !next.members.includes(c) && c !== state.me.code);
  broadcast(next, { k: "group", op: "update", note });
  for (const code of removed) enqueue(code, { k: "group", op: "update", note, grp: groupInfo(next) }, {});
}

export async function leaveGroup(chatId) {
  const chat = state.chats.get(chatId);
  if (!chat) return;
  const members = (chat.members || []).filter((c) => c !== state.me.code);
  let admins = (chat.admins || []).filter((c) => c !== state.me.code);
  if (!admins.length && members.length) admins = [members[0]];
  const next = { ...chat, members, admins, v: (chat.v || 1) + 1, left: true };
  for (const code of members) enqueue(code, { k: "group", op: "leave", grp: groupInfo(next) }, {});
  await saveChat(next);
  await systemMessage(chatId, "Vous avez quitté le groupe.");
}

// ----- Réception -------------------------------------------------------------

const receiptQueue = new Map(); // `${to}|${st}|${chat}` → Set(ids)
let receiptTimer = null;

function queueReceipt(to, chatId, ids, st) {
  const k = `${to}|${st}|${chatId}`;
  const set = receiptQueue.get(k) || new Set();
  for (const id of ids) set.add(id);
  receiptQueue.set(k, set);
  clearTimeout(receiptTimer);
  receiptTimer = setTimeout(() => {
    for (const [key, ids] of receiptQueue) {
      const [code, status, chat] = key.split("|");
      const payload = { k: "receipt", st: status, ids: [...ids] };
      if (isGroup(chat)) payload.g = chat.slice(2);
      enqueue(code, payload, {});
    }
    receiptQueue.clear();
  }, 400);
}

// Marque une discussion comme lue (accusés de lecture bleus).
export async function markRead(chatId) {
  const chat = state.chats.get(chatId);
  if (!chat) return;
  const list = await db.messagesOf(chatId, 300);
  const bySender = new Map();
  for (const m of list) {
    if (m.from !== state.me.code && m.from !== "system" && !m.readByMe) {
      m.readByMe = true;
      await db.put("messages", m);
      if (!bySender.has(m.from)) bySender.set(m.from, []);
      bySender.get(m.from).push(m.id);
    }
  }
  if (state.settings.readReceipts) {
    for (const [code, ids] of bySender) queueReceipt(code, chatId, ids, "read");
  }
  if (chat.unread || chat.markedUnread) await updateChat(chatId, { unread: 0, markedUnread: false });
}

async function receive(env) {
  const numbers = env.n ? [env.n] : [];
  try {
    const from = env.from;
    const sender = state.contacts.get(from);
    if (sender?.blocked) return;
    const peer = await keysFor(from);
    if (!peer) return;
    let p;
    try {
      p = await cr.open(state.me, peer, env.body);
    } catch {
      return; // pas pour nous, ou abîmé
    }
    await handle(from, p, env);
  } catch (e) {
    console.warn("Talka : message ignoré", e);
    if (e?.message === "offline") return; // pas de clés : on le reprendra
  } finally {
    if (numbers.length) state.net.got(numbers);
  }
}

async function groupChat(from, grp) {
  if (!grp?.id) return null;
  const id = `g:${grp.id}`;
  let chat = state.chats.get(id);
  if (!chat) {
    if (!(grp.members || []).includes(state.me.code)) return null;
    chat = { id, type: "group", name: grp.name, avatar: grp.avatar, members: grp.members, admins: grp.admins, createdBy: from, createdAt: Date.now(), lastAt: Date.now(), unread: 0, v: grp.v };
    await saveChat(chat);
    await systemMessage(id, `${displayName(from)} vous a ajouté au groupe « ${grp.name} ».`);
    state.net.watch(grp.members.filter((c) => c !== state.me.code));
  } else if ((grp.v || 0) > (chat.v || 0)) {
    const wasIn = (chat.members || []).includes(state.me.code);
    chat = { ...chat, name: grp.name, avatar: grp.avatar, members: grp.members, admins: grp.admins, v: grp.v };
    if (!grp.members.includes(state.me.code) && wasIn) {
      chat.left = true;
    } else if (grp.members.includes(state.me.code)) {
      chat.left = false;
    }
    await saveChat(chat);
  }
  return chat;
}

async function chatFor(from, p) {
  if (p.grp || p.g) {
    const chat = p.grp ? await groupChat(from, p.grp) : state.chats.get(`g:${p.g}`);
    if (!chat || !(chat.members || []).includes(from) && p.k !== "group") return null;
    return chat;
  }
  return ensureDirectChat(from);
}

async function handle(from, p, env) {
  const ts = env.ts || Date.now();
  switch (p.k) {
    case "profile": {
      const c = state.contacts.get(from) || { code: from, saved: false, addedAt: Date.now() };
      if ((p.v || 0) >= (c.profileV || 0)) {
        await saveContact({ ...c, profileName: String(p.name || "").slice(0, 40), about: String(p.about || "").slice(0, 140), avatar: String(p.avatar || "").slice(0, 60000), profileV: p.v || 0 });
        emit("chats");
      }
      return;
    }
    case "typing": {
      const chat = p.g || p.grp ? state.chats.get(`g:${p.g || p.grp.id}`) : state.chats.get(from);
      if (!chat) return;
      const map = state.typing.get(chat.id) || new Map();
      clearTimeout(map.get(from));
      if (p.on) map.set(from, setTimeout(() => {
        map.delete(from);
        emit("typing", chat.id);
      }, 6000));
      else map.delete(from);
      state.typing.set(chat.id, map);
      emit("typing", chat.id);
      return;
    }
    case "call":
      emit("call-signal", { from, p, live: !!env.live, ts });
      return;
    case "status": {
      const c = state.contacts.get(from);
      if (!c?.saved) return; // seulement les contacts enregistrés
      if (ts - (p.at || ts) > STATUS_TTL) return;
      const st = { key: `${from}|${p.id}`, owner: from, id: p.id, type: p.type, text: String(p.text || "").slice(0, 700), bg: p.bg, data: p.data || "", at: p.at || ts, seen: false };
      await db.put("statuses", st);
      emit("statuses");
      return;
    }
    case "status-seen": {
      const st = await db.get("statuses", `${state.me.code}|${p.id}`);
      if (!st) return;
      st.viewers = { ...(st.viewers || {}), [from]: ts };
      await db.put("statuses", st);
      emit("statuses");
      return;
    }
    case "status-delete":
      await db.del("statuses", `${from}|${p.id}`);
      emit("statuses");
      return;
  }

  // Le reste appartient à une discussion.
  const chat = await chatFor(from, p);
  if (!chat) return;
  const sendProfile = () => {
    const c = state.contacts.get(from);
    if (c && (c.sentProfileV || 0) < state.profile.v) {
      enqueue(from, profilePayload(), {});
      saveContact({ ...c, sentProfileV: state.profile.v });
    }
  };

  switch (p.k) {
    case "msg": {
      if (await getMessage(chat.id, p.id)) return; // déjà reçu
      const m = {
        chat: chat.id,
        id: p.id,
        from,
        at: Math.min(p.at || ts, ts + 60000),
        kind: ["text", "image", "voice", "file", "contact"].includes(p.kind) ? p.kind : "text",
        text: typeof p.text === "string" ? p.text.slice(0, 65536) : "",
        data: typeof p.data === "string" ? p.data : "",
        name: p.name,
        mime: p.mime,
        size: p.size,
        dur: p.dur,
        w: p.w,
        h: p.h,
        code: p.code,
        reply: p.reply,
        fwd: !!p.fwd,
        reactions: {},
      };
      if (p.ttl) m.exp = m.at + p.ttl * 1000;
      const sender = state.contacts.get(from);
      if (sender && !sender.profileName && typeof p.pn === "string") await saveContact({ ...sender, profileName: p.pn.slice(0, 40) });
      const isActive = state.activeChat === chat.id && document.visibilityState === "visible";
      if (!isActive) chat.unread = (chat.unread || 0) + 1;
      else m.readByMe = true;
      await storeMessage(m);
      queueReceipt(from, chat.id, [p.id], "delivered");
      if (isActive && state.settings.readReceipts) queueReceipt(from, chat.id, [p.id], "read");
      sendProfile();
      const map = state.typing.get(chat.id);
      if (map?.has(from)) {
        clearTimeout(map.get(from));
        map.delete(from);
        emit("typing", chat.id);
      }
      emit("incoming", { chat, message: m, active: isActive });
      return;
    }
    case "receipt": {
      for (const id of p.ids || []) {
        const m = await getMessage(chat.id, id);
        if (!m || m.from !== state.me.code) continue;
        const field = p.st === "read" ? "readBy" : "deliveredTo";
        m[field] = [...new Set([...(m[field] || []), from])];
        if (p.st === "read") m.deliveredTo = [...new Set([...(m.deliveredTo || []), from])];
        m.status = computeStatus(m);
        await db.put("messages", m);
      }
      emit("messages", chat.id);
      return;
    }
    case "react": {
      const m = await getMessage(chat.id, p.id);
      if (!m) return;
      m.reactions = m.reactions || {};
      if (p.emoji) m.reactions[from] = String(p.emoji).slice(0, 16);
      else delete m.reactions[from];
      await db.put("messages", m);
      emit("messages", chat.id);
      if (m.from === state.me.code && p.emoji) emit("reaction", { chat, from, emoji: p.emoji, message: m });
      return;
    }
    case "edit": {
      const m = await getMessage(chat.id, p.id);
      if (!m || m.from !== from || m.deleted) return;
      m.text = String(p.text || "").slice(0, 65536);
      m.edited = true;
      await db.put("messages", m);
      await refreshLast(chat.id);
      emit("messages", chat.id);
      return;
    }
    case "delete": {
      const m = await getMessage(chat.id, p.id);
      if (!m || m.from !== from) return;
      Object.assign(m, { deleted: true, text: "", data: "", reactions: {} });
      await db.put("messages", m);
      await refreshLast(chat.id);
      emit("messages", chat.id);
      return;
    }
    case "timer": {
      await updateChat(chat.id, { timer: p.secs || 0 });
      await systemMessage(chat.id, timerText(from, p.secs), ts);
      return;
    }
    case "group": {
      if (p.op === "create") {
        // Le message système est écrit par `groupChat` à la création.
      } else if (p.op === "leave") {
        await systemMessage(chat.id, `${displayName(from)} a quitté le groupe.`, ts);
      } else if (p.note) {
        await systemMessage(chat.id, p.note.replace(/^Vous avez/, `${displayName(from)} a`), ts);
      }
      emit("chats");
      return;
    }
  }
}

export function computeStatus(m) {
  if (m.from !== state.me.code) return m.status;
  if (m.status === "failed") return "failed";
  const to = m.to || [];
  if (!to.length) return "sent";
  if (to.every((c) => (m.readBy || []).includes(c))) return "read";
  if (to.every((c) => (m.deliveredTo || []).includes(c))) return "delivered";
  if (to.every((c) => (m.sentTo || []).includes(c))) return "sent";
  return m.status || "pending";
}

// ----- Statuts ---------------------------------------------------------------

export async function postStatus({ type, text, bg, data }) {
  const id = cr.randomId(10);
  const at = state.net.now();
  const st = { key: `${state.me.code}|${id}`, owner: state.me.code, id, type, text, bg, data: data || "", at, seen: true, viewers: {} };
  await db.put("statuses", st);
  emit("statuses");
  for (const c of state.contacts.values()) {
    if (c.saved && !c.blocked) enqueue(c.code, { k: "status", id, type, text, bg, data, at }, {});
  }
}

export async function deleteStatus(id) {
  await db.del("statuses", `${state.me.code}|${id}`);
  emit("statuses");
  for (const c of state.contacts.values()) {
    if (c.saved && !c.blocked) enqueue(c.code, { k: "status-delete", id }, {});
  }
}

export async function statuses() {
  const now = Date.now();
  return (await db.all("statuses")).filter((s) => s.at + STATUS_TTL > now).sort((a, b) => a.at - b.at);
}

export async function markStatusSeen(st) {
  if (st.seen || st.owner === state.me.code) return;
  st.seen = true;
  await db.put("statuses", st);
  emit("statuses");
  if (state.settings.readReceipts) enqueue(st.owner, { k: "status-seen", id: st.id }, {});
}

// ----- Appels ----------------------------------------------------------------

export function sendCallSignal(code, payload) {
  enqueue(code, { k: "call", ...payload }, { live: true });
}

export async function logCall(entry) {
  await db.put("calls", entry);
  emit("calls");
}

export async function callLog() {
  return (await db.all("calls")).sort((a, b) => b.at - a.at);
}

export async function clearCalls() {
  await db.clear("calls");
  emit("calls");
}

// ----- Sauvegarde ------------------------------------------------------------

export async function exportBackup(password) {
  const data = await db.dump();
  return cr.encryptBackup(password, { app: "talka", at: Date.now(), data });
}

export async function importBackup(password, file) {
  const backup = await cr.decryptBackup(password, file);
  if (backup.app !== "talka" || !backup.data) throw new Error("Sauvegarde illisible.");
  await db.restore(backup.data);
}

export async function wipeEverything() {
  state.net?.stop();
  await db.wipe();
  try {
    localStorage.clear();
  } catch {
    /* rien */
  }
}

export async function pendingCount() {
  return (await db.all("outbox")).length;
}
