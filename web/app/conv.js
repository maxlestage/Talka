// La conversation ouverte : l'en-tête, les bulles, la zone de saisie.

import * as core from "./core.js";
import * as calls from "./calls.js";
import * as sounds from "./sounds.js";
import { state } from "./core.js";
import { pretty } from "./crypto.js";
import {
  h, clear, icon, iconButton, time, dayLabel, sameDay, lastSeen, bytes, toast, menu, modal,
  confirmBox, richText, onlyEmoji, download,
} from "./dom.js";
import { picker, QUICK, remember } from "./emoji.js";
import { compressImage, pickFile, readAsDataURL, Recorder, waveform, MAX_FILE, dataUrlBlob } from "./media.js";
import { avatar, openInfo, pickChats, openAddContact, openImage, colorOf } from "./ui.js";

const PAGE = 120;

const conv = {
  chatId: null,
  limit: PAGE,
  el: null,
  list: null,
  reply: null,
  editing: null,
  recorder: null,
  search: "",
  renderQueued: false,
  firstUnread: null,
};

export function current() {
  return conv.chatId;
}

// ----- En-tête -----------------------------------------------------------------

function subtitle(chat) {
  const typingMap = state.typing.get(chat.id);
  if (typingMap?.size) {
    if (chat.type === "group") return `${[...typingMap.keys()].map((c) => core.displayName(c)).join(", ")} écrit…`;
    return "écrit…";
  }
  if (chat.type === "group") {
    if (chat.left) return "Vous ne faites plus partie de ce groupe";
    return (chat.members || []).map((c) => (c === state.me.code ? "Vous" : core.displayName(c))).join(", ");
  }
  const contact = state.contacts.get(chat.id);
  if (contact?.blocked) return "Bloqué";
  const p = state.presence.get(chat.id);
  if (p?.online) return "en ligne";
  if (p?.seen) return lastSeen(p.seen);
  return pretty(chat.id);
}

function header(chat) {
  const sub = h("div", { class: "conv-sub" }, subtitle(chat));
  const title = h(
    "button",
    { type: "button", class: "conv-title", onclick: () => openInfo(chat.id) },
    avatar(chat.id, 40),
    h("div", { class: "conv-names" }, h("div", { class: "conv-name" }, core.chatTitle(chat)), sub),
  );
  const direct = chat.type === "direct";
  return h(
    "header",
    { class: "conv-head" },
    iconButton("back", "Retour", () => history.back(), "only-mobile"),
    title,
    h(
      "div",
      { class: "conv-actions" },
      direct ? iconButton("video", "Appel vidéo", () => calls.startCall(chat.id, true)) : null,
      direct ? iconButton("phone", "Appel audio", () => calls.startCall(chat.id, false)) : null,
      iconButton("search", "Rechercher", () => toggleSearch()),
      iconButton("more", "Menu", (e) => chatMenu(chat, e.currentTarget)),
    ),
  );
}

function chatMenu(chat, at) {
  const direct = chat.type === "direct";
  const contact = state.contacts.get(chat.id);
  menu(
    [
      { icon: "info", label: direct ? "Infos du contact" : "Infos du groupe", onclick: () => openInfo(chat.id) },
      { icon: "search", label: "Rechercher", onclick: () => toggleSearch() },
      { icon: "timer", label: "Messages éphémères", onclick: () => timerDialog(chat) },
      { icon: chat.muted ? "bell" : "belloff", label: chat.muted ? "Réactiver les notifications" : "Mettre en sourdine", onclick: () => core.updateChat(chat.id, { muted: !chat.muted }) },
      { icon: "palette", label: "Fond d'écran", onclick: () => wallpaperDialog(chat) },
      direct && !contact?.saved ? { icon: "person", label: "Ajouter aux contacts", onclick: () => openAddContact(chat.id) } : null,
      direct ? { icon: "block", label: contact?.blocked ? "Débloquer" : "Bloquer", danger: !contact?.blocked, onclick: () => toggleBlock(chat.id) } : null,
      { icon: "trash", label: "Vider la discussion", danger: true, onclick: async () => (await confirmBox("Vider la discussion ?", "Tous les messages seront effacés de cet appareil.", "Vider", true)) && core.clearChat(chat.id) },
      { icon: "trash", label: direct ? "Supprimer la discussion" : "Quitter et supprimer", danger: true, onclick: () => deleteChat(chat) },
    ],
    at,
  );
}

async function toggleBlock(code) {
  const c = state.contacts.get(code);
  if (!c?.blocked && !(await confirmBox(`Bloquer ${core.displayName(code)} ?`, "Vous ne recevrez plus ses messages, appels ni statuts.", "Bloquer", true))) return;
  await core.setBlocked(code, !c?.blocked);
  toast(c?.blocked ? "Contact débloqué" : "Contact bloqué");
}

async function deleteChat(chat) {
  if (!(await confirmBox("Supprimer la discussion ?", "Elle disparaîtra de cet appareil avec tous ses messages.", "Supprimer", true))) return;
  if (chat.type === "group" && !chat.left) await core.leaveGroup(chat.id);
  await core.deleteChat(chat.id);
  history.back();
}

export function timerDialog(chat) {
  const options = [
    [0, "Désactivés"],
    [86400, "24 heures"],
    [7 * 86400, "7 jours"],
    [90 * 86400, "90 jours"],
  ];
  const m = modal(
    "Messages éphémères",
    h(
      "div",
      { class: "stack" },
      h("p", { class: "muted" }, "Les nouveaux messages disparaîtront de cette discussion après la durée choisie, pour tout le monde."),
      options.map(([secs, label]) =>
        h(
          "label",
          { class: "radio" },
          h("input", { type: "radio", name: "timer", checked: (chat.timer || 0) === secs, onchange: async () => { await core.setTimer(chat.id, secs); m.close(); } }),
          label,
        ),
      ),
    ),
  );
}

const WALLPAPERS = ["doodle", "plain", "mint", "sky", "rose", "night", "sand"];

export function wallpaperDialog(chat) {
  const m = modal(
    chat ? "Fond d'écran de la discussion" : "Fond d'écran",
    h(
      "div",
      { class: "wall-grid" },
      WALLPAPERS.map((w) =>
        h("button", {
          type: "button",
          class: `wall-swatch wall-${w}`,
          "aria-label": w,
          onclick: async () => {
            if (chat) await core.updateChat(chat.id, { wallpaper: w });
            else await core.saveSettings({ wallpaper: w });
            m.close();
            if (conv.chatId) applyWallpaper();
          },
        }),
      ),
    ),
  );
}

function applyWallpaper() {
  const chat = state.chats.get(conv.chatId);
  if (!conv.list || !chat) return;
  conv.list.className = `messages wall-${chat.wallpaper || state.settings.wallpaper || "doodle"}`;
}

// ----- Ouvrir, fermer ----------------------------------------------------------

export async function open(chatId, host) {
  const chat = state.chats.get(chatId);
  if (!chat) return;
  stopRecording(true);
  conv.chatId = chatId;
  conv.limit = PAGE;
  conv.reply = null;
  conv.editing = null;
  conv.search = "";
  conv.firstUnread = chat.unread ? chat.unread : null;
  state.activeChat = chatId;
  conv.list = h("div", { class: "messages", role: "log", "aria-live": "polite" });
  conv.list.addEventListener("scroll", onScroll);
  conv.down = h("button", { type: "button", class: "to-bottom hidden", "aria-label": "Derniers messages", onclick: () => scrollBottom(true) }, icon("arrowdown"));
  conv.searchBar = h("div", { class: "conv-search hidden" });
  conv.banner = h("div", { class: "conv-banner" });
  conv.composer = h("div", { class: "composer-wrap" });
  conv.head = header(chat);
  conv.el = h("div", { class: "conv" }, conv.head, conv.searchBar, conv.banner, h("div", { class: "messages-wrap" }, conv.list, conv.down), conv.composer);
  clear(host, conv.el);
  applyWallpaper();
  renderBanner();
  renderComposer();
  await render(true);
  await core.markRead(chatId);
}

export function close() {
  stopRecording(true);
  if (conv.chatId) saveDraft();
  conv.chatId = null;
  state.activeChat = null;
}

function saveDraft() {
  const ta = conv.composer?.querySelector("textarea");
  if (!ta || conv.editing) return;
  const chat = state.chats.get(conv.chatId);
  if (chat && (chat.draft || "") !== ta.value) core.updateChat(chat.id, { draft: ta.value });
}

export function refreshHeader() {
  const chat = state.chats.get(conv.chatId);
  if (!chat || !conv.head) return;
  const fresh = header(chat);
  conv.head.replaceWith(fresh);
  conv.head = fresh;
  renderBanner();
  if (chat.left || state.contacts.get(chat.id)?.blocked) renderComposer();
}

function renderBanner() {
  const chat = state.chats.get(conv.chatId);
  if (!chat || !conv.banner) return;
  const contact = state.contacts.get(chat.id);
  if (chat.type === "direct" && !contact?.saved && !contact?.blocked) {
    clear(
      conv.banner,
      h(
        "div",
        { class: "unknown" },
        h("p", {}, `${pretty(chat.id)} n'est pas dans vos contacts.`),
        h(
          "div",
          { class: "row" },
          h("button", { class: "btn ghost danger", type: "button", onclick: () => toggleBlock(chat.id) }, "Bloquer"),
          h("button", { class: "btn primary", type: "button", onclick: () => openAddContact(chat.id) }, "Ajouter"),
        ),
      ),
    );
  } else if (chat.type === "direct" && contact && !contact.sign) {
    clear(conv.banner, h("div", { class: "notice" }, "⏳ Ce code n'est pas encore joignable : vos messages partiront dès que votre contact ouvrira Talka."));
  } else conv.banner.replaceChildren();
}

// ----- Les messages --------------------------------------------------------------

export function queueRender() {
  if (conv.renderQueued) return;
  conv.renderQueued = true;
  requestAnimationFrame(() => {
    conv.renderQueued = false;
    render(false);
  });
}

function nearBottom() {
  const l = conv.list;
  return l.scrollHeight - l.scrollTop - l.clientHeight < 120;
}

// Fait défiler la liste seule (scrollIntoView ferait aussi glisser la page).
function centerOn(el, smooth) {
  if (!el) return;
  const top = el.getBoundingClientRect().top - conv.list.getBoundingClientRect().top + conv.list.scrollTop;
  conv.list.scrollTo({ top: top - conv.list.clientHeight / 2 + el.offsetHeight / 2, behavior: smooth ? "smooth" : "auto" });
}

function scrollBottom(smooth) {
  conv.list.scrollTo({ top: conv.list.scrollHeight, behavior: smooth ? "smooth" : "auto" });
}

function onScroll() {
  conv.down.classList.toggle("hidden", nearBottom());
  if (conv.list.scrollTop < 60 && !conv.loadingMore && conv.hasMore) {
    conv.loadingMore = true;
    const before = conv.list.scrollHeight;
    conv.limit += PAGE;
    render(false).then(() => {
      conv.list.scrollTop += conv.list.scrollHeight - before;
      conv.loadingMore = false;
    });
  }
}

async function render(initial) {
  const chatId = conv.chatId;
  if (!chatId) return;
  const chat = state.chats.get(chatId);
  const all = await core.messages(chatId, conv.limit + 1);
  if (conv.chatId !== chatId) return;
  conv.hasMore = all.length > conv.limit;
  let list = conv.hasMore ? all.slice(1) : all;
  const now = Date.now();
  list = list.filter((m) => !m.exp || m.exp > now);
  const q = conv.search.trim().toLowerCase();
  const stick = initial || nearBottom();
  const frag = document.createDocumentFragment();
  frag.append(
    h("div", { class: "e2e-note" }, icon("lock"), " Les messages sont chiffrés de bout en bout. Personne d'autre, pas même Talka, ne peut les lire."),
  );
  if (chat?.timer) frag.append(h("div", { class: "system-msg" }, "⏳ Messages éphémères activés"));
  let prev = null;
  if (initial && conv.firstUnread && list.length >= conv.firstUnread) conv.unreadId = list[list.length - conv.firstUnread].id;
  if (initial && !conv.firstUnread) conv.unreadId = null;
  list.forEach((m) => {
    if (q && !(m.text || m.name || "").toLowerCase().includes(q)) return;
    if (!prev || !sameDay(prev.at, m.at)) frag.append(h("div", { class: "day-sep" }, h("span", {}, dayLabel(m.at))));
    if (m.id === conv.unreadId && !q) frag.append(h("div", { class: "unread-sep" }, h("span", {}, `${conv.firstUnread} message${conv.firstUnread > 1 ? "s" : ""} non lu${conv.firstUnread > 1 ? "s" : ""}`)));
    frag.append(bubble(m, prev, chat));
    prev = m;
  });
  if (q && prev === null) frag.append(h("div", { class: "system-msg" }, "Aucun message trouvé"));
  conv.list.replaceChildren(frag);
  if (stick) scrollBottom(false);
  if (initial && conv.unreadId) centerOn(conv.list.querySelector(".unread-sep"), false);
  conv.down.classList.toggle("hidden", nearBottom());
}

function ticks(m) {
  const st = core.computeStatus(m);
  if (st === "pending") return h("span", { class: "tick pending", title: "En attente" }, "🕓");
  if (st === "failed") return h("span", { class: "tick failed", title: "Échec de l'envoi" }, "⚠️");
  const double = st === "delivered" || st === "read";
  return h("span", { class: `tick ${st}`, title: { sent: "Envoyé", delivered: "Distribué", read: "Lu" }[st] }, double ? "✓✓" : "✓");
}

function quote(m, chatId) {
  if (!m.reply) return null;
  const r = m.reply;
  return h(
    "button",
    {
      type: "button",
      class: "quote",
      style: { borderColor: colorOf(r.from) },
      onclick: (e) => {
        e.stopPropagation();
        const el = conv.list.querySelector(`[data-id="${CSS.escape(r.id)}"]`);
        if (el) {
          centerOn(el, true);
          el.classList.add("flash");
          setTimeout(() => el.classList.remove("flash"), 1200);
        } else toast("Message introuvable");
      },
    },
    h("strong", { style: { color: colorOf(r.from) } }, r.from === state.me.code ? "Vous" : core.displayName(r.from)),
    h("span", {}, r.text || "Message"),
    chatId ? null : null,
  );
}

function voicePlayer(m) {
  const audio = new Audio();
  audio.preload = "none";
  const playBtn = h("button", { type: "button", class: "voice-play", "aria-label": "Lire" }, icon("play"));
  const bars = h("div", { class: "voice-bars" });
  const dur = h("span", { class: "voice-dur" }, core.fmtDur(m.dur));
  let speed = 1;
  const speedBtn = h("button", { type: "button", class: "voice-speed", onclick: () => { speed = speed === 1 ? 1.5 : speed === 1.5 ? 2 : 1; audio.playbackRate = speed; speedBtn.textContent = `${speed}×`; } }, "1×");
  waveform(m.data, 36).then((w) => bars.replaceChildren(...w.map((v) => h("i", { style: { height: `${Math.round(v * 100)}%` } }))));
  audio.addEventListener("timeupdate", () => {
    const p = audio.currentTime / (audio.duration && isFinite(audio.duration) ? audio.duration : m.dur || 1);
    const n = Math.round(p * bars.children.length);
    [...bars.children].forEach((b, i) => b.classList.toggle("on", i < n));
    dur.textContent = core.fmtDur(audio.currentTime);
  });
  audio.addEventListener("ended", () => {
    playBtn.replaceChildren(icon("play"));
    [...bars.children].forEach((b) => b.classList.remove("on"));
    dur.textContent = core.fmtDur(m.dur);
  });
  playBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    if (!audio.src) audio.src = m.data;
    if (audio.paused) {
      document.querySelectorAll("audio.talka-playing").forEach((a) => a !== audio && a.pause());
      audio.classList.add("talka-playing");
      audio.playbackRate = speed;
      audio.play().catch(() => toast("Lecture impossible sur ce navigateur."));
      playBtn.replaceChildren(icon("pause"));
    } else {
      audio.pause();
      playBtn.replaceChildren(icon("play"));
    }
  });
  bars.addEventListener("click", (e) => {
    e.stopPropagation();
    if (!audio.duration || !isFinite(audio.duration)) return;
    const r = bars.getBoundingClientRect();
    audio.currentTime = ((e.clientX - r.left) / r.width) * audio.duration;
  });
  return h("div", { class: "voice" }, h("div", { class: "voice-avatar" }, avatar(m.from, 40), h("span", { class: "voice-mic" }, icon("mic"))), playBtn, h("div", { class: "voice-main" }, bars, h("div", { class: "voice-meta" }, dur, speedBtn)));
}

function content(m) {
  if (m.deleted) return h("div", { class: "text deleted" }, "🚫 ", m.from === state.me.code ? "Vous avez supprimé ce message" : "Ce message a été supprimé");
  switch (m.kind) {
    case "image": {
      const ratio = m.w && m.h ? Math.min(1.4, Math.max(0.6, m.h / m.w)) : 0.75;
      return h(
        "div",
        { class: "media" },
        h("button", { type: "button", class: "img-btn", onclick: (e) => { e.stopPropagation(); openImage(m.data, m.text); } }, h("img", { src: m.data, alt: m.text || "Photo", loading: "lazy", style: { aspectRatio: m.w && m.h ? `${m.w} / ${m.h}` : "4 / 3", maxHeight: `${Math.round(330 * ratio)}px` } })),
        m.text ? h("div", { class: "text" }, richText(m.text)) : null,
      );
    }
    case "voice":
      return voicePlayer(m);
    case "file":
      return h(
        "button",
        { type: "button", class: "file", onclick: (e) => { e.stopPropagation(); download(m.name || "fichier", dataUrlBlob(m.data)); } },
        h("span", { class: "file-icon" }, icon("file")),
        h("span", { class: "file-meta" }, h("strong", {}, m.name || "Fichier"), h("small", {}, `${bytes(m.size)} · ${(m.name || "").split(".").pop().toUpperCase()}`)),
        icon("download"),
      );
    case "contact":
      return h(
        "div",
        { class: "contact-card" },
        h("div", { class: "row" }, avatar(m.code, 40), h("div", {}, h("strong", {}, m.name || pretty(m.code)), h("small", { class: "muted" }, pretty(m.code)))),
        m.code !== state.me.code
          ? h("button", { type: "button", class: "btn ghost", onclick: (e) => { e.stopPropagation(); state.contacts.get(m.code)?.saved ? openChatFn(m.code) : openAddContact(m.code, m.name); } }, state.contacts.get(m.code)?.saved ? "Envoyer un message" : "Ajouter le contact")
          : null,
      );
    default: {
      const big = onlyEmoji(m.text);
      return h("div", { class: `text ${big ? "big-emoji" : ""}` }, richText(m.text || ""));
    }
  }
}

let openChatFn = () => {};
export function setOpenChat(fn) {
  openChatFn = fn;
}

function bubble(m, prev, chat) {
  if (m.kind === "system") return h("div", { class: "system-msg" }, m.text);
  const mine = m.from === state.me.code;
  const first = !prev || prev.from !== m.from || prev.kind === "system" || !sameDay(prev.at, m.at) || m.at - prev.at > 5 * 60000;
  const reactions = Object.entries(m.reactions || {});
  const counts = new Map();
  for (const [, e] of reactions) counts.set(e, (counts.get(e) || 0) + 1);
  const b = h(
    "div",
    { class: `bubble ${mine ? "out" : "in"} ${first ? "first" : ""} ${m.kind}`, "data-id": m.id },
    chat?.type === "group" && !mine && first ? h("div", { class: "sender", style: { color: colorOf(m.from) } }, core.displayName(m.from)) : null,
    m.fwd && !m.deleted ? h("div", { class: "fwd" }, icon("forward"), " Transféré") : null,
    m.deleted ? null : quote(m),
    content(m),
    h(
      "div",
      { class: "meta" },
      m.starred ? h("span", { class: "star" }, "★") : null,
      m.exp ? h("span", { title: "Message éphémère" }, "⏳") : null,
      m.edited && !m.deleted ? h("span", { class: "edited" }, "Modifié") : null,
      time(m.at),
      mine && !m.deleted ? ticks(m) : null,
    ),
    h("button", { type: "button", class: "bubble-menu", "aria-label": "Options du message", onclick: (e) => { e.stopPropagation(); messageMenu(m, e.currentTarget); } }, icon("arrowdown")),
    counts.size
      ? h(
          "button",
          { type: "button", class: "reactions", onclick: (e) => { e.stopPropagation(); showReactions(m); } },
          [...counts.keys()].slice(0, 4).join(""),
          reactions.length > 1 ? h("span", {}, String(reactions.length)) : null,
        )
      : null,
  );
  const row = h("div", { class: `msg-row ${mine ? "out" : "in"} ${counts.size ? "has-react" : ""}` }, b);
  // Double-clic : ❤️ ; appui long : le menu ; glisser vers la droite : répondre.
  b.addEventListener("dblclick", () => !m.deleted && core.react(conv.chatId, m.id, "❤️"));
  b.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    messageMenu(m, { x: e.clientX, y: e.clientY });
  });
  let sx = 0;
  let sy = 0;
  let dx = 0;
  let pressTimer = null;
  b.addEventListener("pointerdown", (e) => {
    if (e.pointerType === "mouse") return;
    sx = e.clientX;
    sy = e.clientY;
    dx = 0;
    pressTimer = setTimeout(() => {
      pressTimer = null;
      navigator.vibrate?.(15);
      messageMenu(m, b);
    }, 480);
  });
  b.addEventListener("pointermove", (e) => {
    if (!sx) return;
    dx = e.clientX - sx;
    if (Math.abs(dx) > 8 || Math.abs(e.clientY - sy) > 8) clearTimeout(pressTimer);
    if (dx > 0 && Math.abs(e.clientY - sy) < 30) b.style.transform = `translateX(${Math.min(dx, 70)}px)`;
  });
  const endSwipe = () => {
    clearTimeout(pressTimer);
    if (dx > 60 && !m.deleted) startReply(m);
    b.style.transform = "";
    sx = 0;
    dx = 0;
  };
  b.addEventListener("pointerup", endSwipe);
  b.addEventListener("pointercancel", endSwipe);
  return row;
}

function showReactions(m) {
  modal(
    "Réactions",
    h(
      "div",
      { class: "list" },
      Object.entries(m.reactions || {}).map(([code, e]) =>
        h(
          "div",
          { class: "list-item" },
          avatar(code, 40),
          h("div", { class: "grow" }, core.displayName(code), code === state.me.code ? h("small", { class: "muted block" }, "Touchez pour retirer") : null),
          h("span", { class: "big-emoji-sm", onclick: () => code === state.me.code && core.react(conv.chatId, m.id, e) }, e),
        ),
      ),
    ),
  );
}

function messageMenu(m, at) {
  if (m.deleted) {
    menu([{ icon: "trash", label: "Supprimer pour moi", danger: true, onclick: () => core.deleteMessage(conv.chatId, m.id, false) }], at);
    return;
  }
  const mine = m.from === state.me.code;
  const el = menu(
    [
      { icon: "reply", label: "Répondre", onclick: () => startReply(m) },
      m.text ? { icon: "copy", label: "Copier", onclick: () => navigator.clipboard?.writeText(m.text).then(() => toast("Copié")) } : null,
      { icon: "forward", label: "Transférer", onclick: () => forwardDialog(m) },
      { icon: "star", label: m.starred ? "Retirer des favoris" : "Mettre en favori", onclick: () => core.toggleStar(conv.chatId, m.id) },
      mine && m.kind === "text" && Date.now() - m.at < 15 * 60000 ? { icon: "edit", label: "Modifier", onclick: () => startEdit(m) } : null,
      mine ? { icon: "info", label: "Infos", onclick: () => messageInfo(m) } : null,
      m.kind === "image" || m.kind === "file" || m.kind === "voice" ? { icon: "download", label: "Enregistrer", onclick: () => download(m.name || (m.kind === "image" ? "photo-talka.jpg" : "vocal-talka.webm"), dataUrlBlob(m.data)) } : null,
      { icon: "trash", label: "Supprimer", danger: true, onclick: () => deleteDialog(m) },
    ],
    at,
  );
  // Les réactions rapides, au-dessus du menu.
  const quick = h(
    "div",
    { class: "quick-react" },
    QUICK.map((e) => h("button", { type: "button", class: m.reactions?.[state.me.code] === e ? "on" : "", onclick: () => { el.remove(); remember(e); core.react(conv.chatId, m.id, e); } }, e)),
    h("button", { type: "button", class: "more", "aria-label": "Plus d'emojis", onclick: () => { el.remove(); reactPicker(m); } }, "+"),
  );
  el.prepend(quick);
}

function reactPicker(m) {
  const md = modal("Réagir", picker((e) => { core.react(conv.chatId, m.id, e); md.close(); }));
}

async function deleteDialog(m) {
  const mine = m.from === state.me.code;
  const md = modal("Supprimer le message ?", h("p", { class: "muted" }, mine ? "Vous pouvez le supprimer pour tout le monde, ou seulement sur cet appareil." : "Il sera supprimé de cet appareil."), {
    actions: [
      { label: "Annuler", cls: "ghost", onclick: (c) => c() },
      { label: "Pour moi", cls: "ghost danger", onclick: (c) => { core.deleteMessage(conv.chatId, m.id, false); c(); } },
      mine ? { label: "Pour tout le monde", cls: "danger", onclick: (c) => { core.deleteMessage(conv.chatId, m.id, true); c(); } } : null,
    ].filter(Boolean),
  });
  return md;
}

function messageInfo(m) {
  const chat = state.chats.get(conv.chatId);
  const rows = (m.to || []).map((code) => {
    const st = (m.readBy || []).includes(code) ? "Lu ✓✓" : (m.deliveredTo || []).includes(code) ? "Distribué ✓✓" : (m.sentTo || []).includes(code) ? "Envoyé ✓" : "En attente 🕓";
    return h("div", { class: "list-item" }, avatar(code, 36), h("div", { class: "grow" }, core.displayName(code)), h("small", { class: "muted" }, st));
  });
  modal("Infos du message", h("div", { class: "stack" }, h("p", { class: "muted" }, `Envoyé le ${new Date(m.at).toLocaleString("fr-FR")}${chat?.type === "group" ? "" : ""}`), h("div", { class: "list" }, rows)));
}

async function forwardDialog(m) {
  const ids = await pickChats("Transférer à…");
  if (!ids?.length) return;
  await core.forward(m, ids);
  toast(ids.length > 1 ? `Transféré à ${ids.length} discussions` : "Transféré");
}

function snippet(m) {
  if (m.kind === "image") return `📷 ${m.text || "Photo"}`;
  if (m.kind === "voice") return `🎙️ Message vocal (${core.fmtDur(m.dur)})`;
  if (m.kind === "file") return `📄 ${m.name}`;
  if (m.kind === "contact") return `👤 ${m.name}`;
  return (m.text || "").slice(0, 140);
}

function startReply(m) {
  conv.editing = null;
  conv.reply = { id: m.id, from: m.from, text: snippet(m) };
  renderComposer(true);
}

function startEdit(m) {
  conv.reply = null;
  conv.editing = m;
  renderComposer(true);
  const ta = conv.composer.querySelector("textarea");
  ta.value = m.text;
  autosize(ta);
}

function toggleSearch() {
  const bar = conv.searchBar;
  if (!bar.classList.contains("hidden")) {
    bar.classList.add("hidden");
    conv.search = "";
    render(false);
    return;
  }
  const input = h("input", { class: "field", type: "search", placeholder: "Rechercher dans la discussion…", "aria-label": "Rechercher" });
  input.addEventListener("input", () => {
    conv.search = input.value;
    conv.limit = 2000;
    render(false);
  });
  clear(bar, input, iconButton("close", "Fermer la recherche", () => toggleSearch()));
  bar.classList.remove("hidden");
  input.focus();
}

// ----- La saisie -------------------------------------------------------------------

function autosize(ta) {
  ta.style.height = "auto";
  ta.style.height = `${Math.min(ta.scrollHeight, 140)}px`;
}

function renderComposer(focus = false) {
  const chat = state.chats.get(conv.chatId);
  if (!chat) return;
  if (chat.left) {
    clear(conv.composer, h("div", { class: "composer-off" }, "Vous ne pouvez plus envoyer de messages dans ce groupe."));
    return;
  }
  if (state.contacts.get(chat.id)?.blocked) {
    clear(conv.composer, h("div", { class: "composer-off" }, "Vous avez bloqué ce contact. ", h("button", { class: "link", type: "button", onclick: () => toggleBlock(chat.id) }, "Débloquer")));
    return;
  }
  const old = conv.composer.querySelector("textarea");
  const ta = h("textarea", { rows: "1", placeholder: "Message", "aria-label": "Message", maxlength: "20000" });
  ta.value = old ? old.value : chat.draft || "";
  const sendBtn = h("button", { type: "button", class: "send-btn", "aria-label": "Envoyer" });
  const updateBtn = () => {
    const hasText = ta.value.trim().length > 0;
    sendBtn.replaceChildren(icon(hasText || conv.editing ? "send" : "mic"));
    sendBtn.setAttribute("aria-label", hasText ? "Envoyer" : "Message vocal");
  };
  ta.addEventListener("input", () => {
    autosize(ta);
    updateBtn();
    if (ta.value) core.typing(conv.chatId, true);
  });
  ta.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && state.settings.enterSends && !e.isComposing && matchMedia("(pointer: fine)").matches) {
      e.preventDefault();
      submit();
    } else if (e.key === "Escape" && (conv.reply || conv.editing)) {
      conv.reply = null;
      conv.editing = null;
      renderComposer(true);
    }
  });
  ta.addEventListener("paste", async (e) => {
    const file = [...(e.clipboardData?.files || [])][0];
    if (file?.type.startsWith("image/")) {
      e.preventDefault();
      sendImage(file);
    }
  });
  ta.addEventListener("blur", saveDraft);
  const submit = async () => {
    const text = ta.value.trim();
    if (conv.editing) {
      if (text && text !== conv.editing.text) await core.editMessage(conv.chatId, conv.editing.id, text);
      conv.editing = null;
      ta.value = "";
      renderComposer(true);
      return;
    }
    if (!text) return startRecording();
    const reply = conv.reply;
    conv.reply = null;
    ta.value = "";
    autosize(ta);
    updateBtn();
    core.typing(conv.chatId, false);
    await core.sendMessage(conv.chatId, { kind: "text", text, reply });
    if (state.settings.sound) sounds.sent();
    if (reply) renderComposer(true);
    scrollBottom(true);
  };
  sendBtn.addEventListener("click", submit);

  const emojiBtn = iconButton("smile", "Emojis", () => {
    const open = conv.composer.querySelector(".emoji-picker");
    if (open) return open.remove();
    const p = picker((e) => {
      const s = ta.selectionStart ?? ta.value.length;
      ta.value = ta.value.slice(0, s) + e + ta.value.slice(ta.selectionEnd ?? s);
      ta.selectionStart = ta.selectionEnd = s + e.length;
      updateBtn();
      autosize(ta);
      ta.focus({ preventScroll: true });
    });
    conv.composer.prepend(p);
  });
  const attachBtn = iconButton("attach", "Joindre", (e) =>
    menu(
      [
        { icon: "image", label: "Photos", onclick: async () => { const f = await pickFile("image/*"); if (f) sendImage(f); } },
        { icon: "camera", label: "Appareil photo", onclick: async () => { const f = await pickFile("image/*", { capture: "environment" }); if (f) sendImage(f); } },
        { icon: "file", label: "Document", onclick: async () => { const f = await pickFile("*/*"); if (f) sendFile(f); } },
        { icon: "person", label: "Contact", onclick: () => shareContact() },
      ],
      e.currentTarget,
    ),
  );

  const bar = conv.reply
    ? h("div", { class: "reply-bar" }, h("div", { class: "quote", style: { borderColor: colorOf(conv.reply.from) } }, h("strong", { style: { color: colorOf(conv.reply.from) } }, conv.reply.from === state.me.code ? "Vous" : core.displayName(conv.reply.from)), h("span", {}, conv.reply.text)), iconButton("close", "Annuler la réponse", () => { conv.reply = null; renderComposer(true); }))
    : conv.editing
      ? h("div", { class: "reply-bar" }, h("div", { class: "quote" }, h("strong", {}, "Modifier le message"), h("span", {}, conv.editing.text)), iconButton("close", "Annuler", () => { conv.editing = null; ta.value = ""; renderComposer(true); }))
      : null;

  clear(conv.composer, bar, h("div", { class: "composer" }, h("div", { class: "composer-box" }, emojiBtn, ta, attachBtn), sendBtn));
  updateBtn();
  requestAnimationFrame(() => autosize(ta));
  if (focus) ta.focus();
}

async function sendImage(file) {
  if (!file.type.startsWith("image/")) return sendFile(file);
  let img;
  try {
    img = await compressImage(file);
  } catch {
    return toast("Cette image ne peut pas être lue.");
  }
  const caption = h("input", { class: "field", placeholder: "Ajouter une légende…", maxlength: "1000" });
  const chatId = conv.chatId;
  const m = modal(
    "Envoyer une photo",
    h("div", { class: "stack" }, h("img", { src: img.data, class: "preview-img", alt: "" }), caption),
    {
      wide: true,
      actions: [
        { label: "Annuler", cls: "ghost", onclick: (c) => c() },
        {
          label: "Envoyer",
          cls: "primary",
          onclick: async (c) => {
            c();
            const reply = conv.reply;
            conv.reply = null;
            await core.sendMessage(chatId, { kind: "image", data: img.data, w: img.w, h: img.h, text: caption.value.trim(), reply });
            if (reply) renderComposer();
          },
        },
      ],
    },
  );
  caption.addEventListener("keydown", (e) => e.key === "Enter" && m.el.querySelector(".btn.primary").click());
}

async function sendFile(file) {
  if (file.size > MAX_FILE) return toast(`Fichier trop lourd (4 Mo au plus).`);
  const data = await readAsDataURL(file);
  await core.sendMessage(conv.chatId, { kind: "file", data, name: file.name.slice(0, 120), mime: file.type, size: file.size });
}

async function shareContact() {
  const people = [...state.contacts.values()].filter((c) => c.saved);
  const items = [{ code: state.me.code, name: `${state.profile.name} (vous)` }, ...people.map((c) => ({ code: c.code, name: core.displayName(c.code) }))];
  const m = modal(
    "Partager un contact",
    h(
      "div",
      { class: "list" },
      items.map((c) =>
        h("button", { type: "button", class: "list-item", onclick: async () => { m.close(); await core.sendMessage(conv.chatId, { kind: "contact", code: c.code, name: c.code === state.me.code ? state.profile.name : c.name }); } }, avatar(c.code, 40), h("div", { class: "grow" }, h("div", {}, c.name), h("small", { class: "muted" }, pretty(c.code)))),
      ),
    ),
  );
}

// ----- Les vocaux ------------------------------------------------------------------

async function startRecording() {
  if (!Recorder.supported()) return toast("Votre navigateur ne permet pas d'enregistrer.");
  const rec = new Recorder();
  try {
    await rec.start();
  } catch {
    return toast("Micro refusé : autorisez-le dans les réglages du navigateur.");
  }
  conv.recorder = rec;
  const timer = h("span", { class: "rec-time" }, "0:00");
  const iv = setInterval(() => (timer.textContent = core.fmtDur(rec.elapsed())), 250);
  rec.iv = iv;
  const chatId = conv.chatId;
  const finish = async (cancel) => {
    clearInterval(iv);
    conv.recorder = null;
    const out = await rec.stop(cancel);
    renderComposer();
    if (out) {
      await core.sendMessage(chatId, { kind: "voice", data: out.data, dur: out.dur, mime: out.mime });
      if (state.settings.sound) sounds.sent();
    }
  };
  clear(
    conv.composer,
    h(
      "div",
      { class: "composer recording" },
      iconButton("trash", "Annuler", () => finish(true), "danger"),
      h("div", { class: "rec-box" }, h("span", { class: "rec-dot" }), timer, h("span", { class: "muted" }, "Enregistrement…")),
      h("button", { type: "button", class: "send-btn", "aria-label": "Envoyer le vocal", onclick: () => finish(false) }, icon("send")),
    ),
  );
  setTimeout(() => conv.recorder === rec && finish(false), 5 * 60 * 1000);
}

function stopRecording(cancel) {
  if (!conv.recorder) return;
  clearInterval(conv.recorder.iv);
  conv.recorder.stop(cancel);
  conv.recorder = null;
}

export function onIncomingActive() {
  if (state.settings.sound) sounds.sent();
}
