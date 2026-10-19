// L'interface : la barre latérale (discussions, actus, appels), l'accueil,
// les fiches de contact et de groupe, les réglages, le code Talka.

import * as core from "./core.js";
import * as conv from "./conv.js";
import * as calls from "./calls.js";
import * as lock from "./lock.js";
import * as db from "./db.js";
import { state } from "./core.js";
import { pretty, normalize, safetyNumber } from "./crypto.js";
import {
  h, clear, icon, iconButton, listTime, toast, modal, confirmBox, promptBox, menu, bytes, download, lastSeen, time,
} from "./dom.js";
import { avatarFrom, compressImage, pickFile } from "./media.js";

const ui = {
  root: null,
  side: null,
  main: null,
  tab: "chats",
  filter: "all",
  query: "",
  showArchived: false,
};

// ----- Petits éléments ----------------------------------------------------------

const COLORS = ["#e542a3", "#1f7aec", "#d36b00", "#02a698", "#7f66ff", "#c4532d", "#008069", "#a62c71", "#5e47de", "#b7820b"];

export function colorOf(code) {
  let n = 0;
  for (const c of String(code || "")) n = (n * 31 + c.charCodeAt(0)) >>> 0;
  return COLORS[n % COLORS.length];
}

// L'avatar d'un code, d'un groupe ou de soi : photo, sinon initiale colorée.
export function avatar(id, size = 48) {
  const style = { width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.42)}px` };
  const chat = state.chats.get(id);
  if (chat?.type === "group") {
    return chat.avatar
      ? h("img", { class: "avatar", src: chat.avatar, alt: "", style })
      : h("span", { class: "avatar group", style }, icon("group"));
  }
  const src = core.avatarOf(id);
  if (src) return h("img", { class: "avatar", src, alt: "", style });
  const name = id === state.me?.code ? state.profile.name : core.displayName(id);
  const letter = (name || "?").replace(/^~/, "").trim().charAt(0).toUpperCase() || "?";
  return h("span", { class: "avatar", style: { ...style, background: colorOf(id) } }, /[A-Z0-9À-Ý]/i.test(letter) ? letter : icon("person"));
}

function statusRing(el, seen) {
  return h("span", { class: `ring ${seen ? "seen" : ""}` }, el);
}

// ----- Le squelette ----------------------------------------------------------------

export function mount() {
  ui.root = document.getElementById("root");
  ui.root.className = "";
  ui.side = h("aside", { class: "sidebar" });
  ui.main = h("section", { class: "main" });
  clear(ui.root, h("div", { class: "layout" }, ui.side, ui.main));
  applyTheme();
  renderSide();
  renderEmptyMain();
  conv.setOpenChat(openChat);

  core.on("chats", () => queueSide());
  core.on("contacts", () => {
    queueSide();
    if (conv.current()) conv.refreshHeader();
  });
  core.on("messages", (chatId) => {
    if (chatId === conv.current()) conv.queueRender();
    queueSide();
  });
  core.on("presence", (code) => {
    if (conv.current() === code) conv.refreshHeader();
  });
  core.on("typing", (chatId) => {
    if (conv.current() === chatId) conv.refreshHeader();
    queueSide();
  });
  core.on("net", () => renderNetBanner());
  core.on("statuses", () => ui.tab === "status" && queueSide());
  core.on("calls", () => ui.tab === "calls" && queueSide());
  core.on("profile", () => queueSide());
  core.on("settings", () => applyTheme());
  core.on("incoming", onIncoming);
  core.on("reaction", ({ chat, from, emoji }) => {
    if (conv.current() !== chat.id) notify(chat, `${core.displayName(from)} a réagi ${emoji} à votre message`);
  });
  core.on("call-signal", (s) => calls.onSignal(s));

  addEventListener("popstate", () => {
    if (conv.current() && !history.state?.chat) closeChat();
  });
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible" && conv.current()) core.markRead(conv.current());
    updateBadge();
  });
  matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", applyTheme);
}

export function applyTheme() {
  const t = state.settings.theme;
  const dark = t === "dark" || (t === "auto" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.dataset.theme = dark ? "dark" : "light";
  document.documentElement.style.setProperty("--msg-size", `${state.settings.fontSize || 15}px`);
  document.querySelector('meta[name="theme-color"]')?.setAttribute("content", dark ? "#202c33" : "#008069");
}

let sideQueued = false;
function queueSide() {
  if (sideQueued) return;
  sideQueued = true;
  requestAnimationFrame(() => {
    sideQueued = false;
    renderSideBody();
    updateBadge();
  });
}

function renderEmptyMain() {
  clear(
    ui.main,
    h(
      "div",
      { class: "empty-main" },
      h("img", { src: "/logo.svg", alt: "", width: "96", height: "96" }),
      h("h1", {}, "Talka pour le Web"),
      h("p", {}, "Envoyez et recevez des messages chiffrés de bout en bout. Votre code remplace votre numéro, et tout reste sur cet appareil."),
      h("button", { class: "btn primary", type: "button", onclick: showMyCode }, icon("qr"), " Mon code : ", pretty(state.me.code)),
      h("p", { class: "muted small" }, icon("lock"), " Chiffré de bout en bout"),
    ),
  );
}

function updateBadge() {
  let n = 0;
  for (const c of state.chats.values()) if (!c.muted && !c.archived) n += c.unread || 0;
  document.title = n ? `(${n}) Talka` : "Talka";
  try {
    if (n) navigator.setAppBadge?.(n);
    else navigator.clearAppBadge?.();
  } catch {
    /* non pris en charge */
  }
}

// ----- La barre latérale --------------------------------------------------------

const TABS = [
  ["chats", "chat", "Discussions"],
  ["status", "status", "Actus"],
  ["calls", "phone", "Appels"],
];

function renderSide() {
  ui.body = h("div", { class: "side-body" });
  ui.net = h("div", { class: "net-banner hidden" });
  ui.headTitle = h("h1", {}, "Talka");
  ui.tabs = h(
    "nav",
    { class: "tabs", "aria-label": "Sections" },
    TABS.map(([key, ic, label]) =>
      h("button", { type: "button", class: `tab ${ui.tab === key ? "on" : ""}`, "data-tab": key, onclick: () => switchTab(key) }, h("span", { class: "tab-icon" }, icon(ic), h("i", { class: "tab-dot" })), h("span", {}, label)),
    ),
  );
  clear(
    ui.side,
    h(
      "header",
      { class: "side-head" },
      h("button", { type: "button", class: "me-btn", "aria-label": "Mon profil", onclick: openSettings }, avatar(state.me.code, 40)),
      ui.headTitle,
      h(
        "div",
        { class: "side-actions" },
        iconButton("qr", "Mon code", showMyCode),
        iconButton("plus", "Nouvelle discussion", openNewChat),
        iconButton("more", "Menu", (e) =>
          menu(
            [
              { icon: "group", label: "Nouveau groupe", onclick: openNewGroup },
              { icon: "person", label: "Ajouter un contact", onclick: () => openAddContact() },
              { icon: "star", label: "Messages favoris", onclick: openStarred },
              { icon: "archive", label: "Discussions archivées", onclick: () => { ui.showArchived = true; switchTab("chats"); } },
              { icon: "settings", label: "Réglages", onclick: openSettings },
              lockAvailable ? { icon: "lock", label: "Verrouiller", onclick: lockNow } : null,
            ],
            e.currentTarget,
          ),
        ),
      ),
    ),
    ui.net,
    ui.body,
    ui.tabs,
  );
  renderNetBanner();
  renderSideBody();
}

let lockAvailable = false;
export function setLockAvailable(v) {
  lockAvailable = v;
}

async function lockNow() {
  document.body.classList.add("locked");
  await lock.lockScreen();
  document.body.classList.remove("locked");
}

function switchTab(key) {
  ui.tab = key;
  ui.query = "";
  for (const b of ui.tabs.children) b.classList.toggle("on", b.dataset.tab === key);
  ui.headTitle.textContent = key === "chats" ? "Talka" : key === "status" ? "Actus" : "Appels";
  renderSideBody();
}

function renderNetBanner() {
  const st = state.net?.state;
  if (!ui.net) return;
  if (st === "online") {
    ui.net.classList.add("hidden");
    return;
  }
  ui.net.classList.remove("hidden");
  clear(ui.net, st === "connecting" ? "⏳ Connexion…" : "📴 Hors ligne : vos messages partiront au retour du réseau.");
}

function renderSideBody() {
  if (!ui.body) return;
  const focused = document.activeElement?.classList.contains("side-search");
  if (ui.tab === "chats") renderChatList();
  else if (ui.tab === "status") renderStatuses();
  else renderCalls();
  // Le point vert sur l'onglet Actus quand il y a du nouveau.
  core.statuses().then((list) => {
    const fresh = list.some((s) => s.owner !== state.me.code && !s.seen);
    ui.tabs.querySelector('[data-tab="status"] .tab-dot')?.classList.toggle("on", fresh);
  });
  if (focused) ui.body.querySelector(".side-search")?.focus();
}

function searchBox(placeholder) {
  const input = h("input", { class: "side-search", type: "search", placeholder, value: ui.query, "aria-label": placeholder });
  input.addEventListener("input", () => {
    ui.query = input.value;
    renderSideBody();
    const again = ui.body.querySelector(".side-search");
    again.focus();
    again.setSelectionRange(again.value.length, again.value.length);
  });
  return h("div", { class: "search-wrap" }, icon("search"), input);
}

function renderChatList() {
  const q = ui.query.trim().toLowerCase();
  let chats = [...state.chats.values()].filter((c) => !state.contacts.get(c.id)?.blocked || c.last);
  const archivedCount = chats.filter((c) => c.archived).length;
  chats = chats.filter((c) => !!c.archived === ui.showArchived);
  if (ui.filter === "unread") chats = chats.filter((c) => c.unread || c.markedUnread);
  if (ui.filter === "groups") chats = chats.filter((c) => c.type === "group");
  if (q) chats = chats.filter((c) => core.chatTitle(c).toLowerCase().includes(q) || c.id.toLowerCase().includes(q.replace(/-/g, "")) || (c.last?.text || "").toLowerCase().includes(q));
  chats.sort((a, b) => (b.pinned ? 1 : 0) - (a.pinned ? 1 : 0) || (b.lastAt || 0) - (a.lastAt || 0));

  const chips = h(
    "div",
    { class: "chips" },
    [["all", "Toutes"], ["unread", "Non lues"], ["groups", "Groupes"]].map(([k, label]) =>
      h("button", { type: "button", class: `chip ${ui.filter === k ? "on" : ""}`, onclick: () => { ui.filter = k; renderSideBody(); } }, label),
    ),
  );
  const items = chats.map(chatItem);
  // Les contacts sans discussion, quand on cherche.
  const extra = q
    ? [...state.contacts.values()]
        .filter((c) => c.saved && !state.chats.has(c.code) && (core.displayName(c.code).toLowerCase().includes(q) || c.code.toLowerCase().includes(q.replace(/-/g, ""))))
        .map((c) => contactItem(c.code, () => openChat(c.code)))
    : [];
  clear(
    ui.body,
    searchBox("Rechercher ou démarrer une discussion"),
    ui.showArchived
      ? h("button", { type: "button", class: "archived-row", onclick: () => { ui.showArchived = false; renderSideBody(); } }, icon("back"), h("strong", {}, "Archivées"))
      : chips,
    !ui.showArchived && archivedCount && ui.filter === "all" && !q
      ? h("button", { type: "button", class: "archived-row", onclick: () => { ui.showArchived = true; renderSideBody(); } }, icon("archive"), h("span", {}, "Archivées"), h("small", {}, String(archivedCount)))
      : null,
    h("div", { class: "chat-list", role: "list" }, items),
    extra.length ? h("div", { class: "list-title" }, "Contacts") : null,
    extra,
    !items.length && !extra.length
      ? h(
          "div",
          { class: "side-empty" },
          q ? h("p", {}, "Aucun résultat.") : null,
          !q && !ui.showArchived
            ? [
                h("p", {}, "Aucune discussion pour l'instant."),
                h("p", { class: "muted" }, "Partagez votre code ou ajoutez celui d'un ami pour commencer."),
                h("button", { class: "btn primary", type: "button", onclick: () => openAddContact() }, "Ajouter un contact"),
                h("button", { class: "btn ghost", type: "button", onclick: showMyCode }, "Partager mon code"),
              ]
            : null,
          normalize(q) && !state.contacts.has(normalize(q))
            ? h("button", { class: "btn primary", type: "button", onclick: () => openAddContact(normalize(q)) }, `Ajouter ${pretty(normalize(q))}`)
            : null,
        )
      : null,
  );
}

function chatItem(chat) {
  const typingMap = state.typing.get(chat.id);
  const last = chat.last;
  let previewEl;
  if (typingMap?.size) previewEl = h("span", { class: "typing" }, chat.type === "group" ? `${core.displayName([...typingMap.keys()][0])} écrit…` : "écrit…");
  else if (chat.draft) previewEl = h("span", {}, h("span", { class: "draft" }, "Brouillon : "), chat.draft);
  else if (last) {
    const mine = last.from === state.me.code;
    const who = chat.type === "group" && last.from !== "system" ? `${mine ? "Vous" : core.displayName(last.from)} : ` : "";
    previewEl = h("span", {}, mine && last.kind !== "system" ? h("span", { class: "tick-mini" }, "✓ ") : null, who, last.text);
  } else previewEl = h("span", { class: "muted" }, chat.type === "group" ? "Groupe créé" : "Nouvelle discussion");
  const unread = chat.unread || (chat.markedUnread ? "" : 0);
  const el = h(
    "button",
    { type: "button", role: "listitem", class: `chat-item ${conv.current() === chat.id ? "active" : ""}`, onclick: () => openChat(chat.id) },
    avatar(chat.id, 49),
    h(
      "div",
      { class: "chat-item-main" },
      h("div", { class: "chat-item-top" }, h("span", { class: "chat-item-title" }, core.chatTitle(chat)), h("small", { class: unread || chat.markedUnread ? "unread-time" : "" }, last ? listTime(last.at) : "")),
      h(
        "div",
        { class: "chat-item-bottom" },
        h("span", { class: "chat-item-preview" }, previewEl),
        h(
          "span",
          { class: "chat-item-icons" },
          chat.timer ? h("span", { title: "Messages éphémères" }, "⏳") : null,
          chat.muted ? icon("belloff", "small") : null,
          chat.pinned ? icon("pin", "small") : null,
          unread || chat.markedUnread ? h("span", { class: `badge ${chat.muted ? "muted" : ""}` }, unread ? String(unread) : "") : null,
        ),
      ),
    ),
  );
  const chatMenu = (at) =>
    menu(
      [
        { icon: "pin", label: chat.pinned ? "Désépingler" : "Épingler", onclick: () => core.updateChat(chat.id, { pinned: !chat.pinned }) },
        { icon: "archive", label: chat.archived ? "Désarchiver" : "Archiver", onclick: () => core.updateChat(chat.id, { archived: !chat.archived }) },
        { icon: chat.muted ? "bell" : "belloff", label: chat.muted ? "Réactiver le son" : "Sourdine", onclick: () => core.updateChat(chat.id, { muted: !chat.muted }) },
        { icon: "check", label: chat.unread || chat.markedUnread ? "Marquer comme lu" : "Marquer comme non lu", onclick: () => (chat.unread || chat.markedUnread ? core.markRead(chat.id) : core.updateChat(chat.id, { markedUnread: true })) },
        {
          icon: "trash",
          label: "Supprimer",
          danger: true,
          onclick: async () => {
            if (!(await confirmBox("Supprimer la discussion ?", "Elle disparaîtra de cet appareil avec tous ses messages.", "Supprimer", true))) return;
            if (chat.type === "group" && !chat.left) await core.leaveGroup(chat.id);
            if (conv.current() === chat.id) closeChat();
            await core.deleteChat(chat.id);
          },
        },
      ],
      at,
    );
  el.addEventListener("contextmenu", (e) => {
    e.preventDefault();
    chatMenu({ x: e.clientX, y: e.clientY });
  });
  let t = null;
  el.addEventListener("pointerdown", (e) => {
    if (e.pointerType === "mouse") return;
    t = setTimeout(() => {
      t = null;
      navigator.vibrate?.(15);
      el.dataset.pressed = "1";
      chatMenu(el);
    }, 500);
  });
  el.addEventListener("pointerup", () => clearTimeout(t));
  el.addEventListener("pointermove", () => clearTimeout(t));
  el.addEventListener("click", (e) => {
    if (el.dataset.pressed) {
      e.stopImmediatePropagation();
      delete el.dataset.pressed;
    }
  }, true);
  return el;
}

function contactItem(code, onclick, extra = null) {
  const c = state.contacts.get(code);
  return h(
    "button",
    { type: "button", class: "list-item", onclick },
    avatar(code, 44),
    h("div", { class: "grow" }, h("div", { class: "strong" }, core.displayName(code)), h("small", { class: "muted ellipsis" }, c?.about || pretty(code))),
    extra,
  );
}

// ----- Ouvrir une discussion ------------------------------------------------------

export async function openChat(chatId) {
  if (!state.chats.has(chatId) && !core.isGroup(chatId)) await core.ensureDirectChat(chatId);
  document.querySelector(".modal-backdrop")?.remove();
  if (!history.state?.chat) history.pushState({ chat: chatId }, "");
  else history.replaceState({ chat: chatId }, "");
  ui.root.querySelector(".layout").classList.add("in-chat");
  await conv.open(chatId, ui.main);
  renderSideBody();
}

function closeChat() {
  conv.close();
  ui.root.querySelector(".layout").classList.remove("in-chat");
  renderEmptyMain();
  renderSideBody();
}

// ----- Notifications ------------------------------------------------------------

function onIncoming({ chat, message, active }) {
  if (active) {
    conv.onIncomingActive();
    return;
  }
  if (chat.muted) return;
  const who = core.displayName(message.from);
  const text = message.kind === "text" ? message.text : chat.last?.text || "Nouveau message";
  notify(chat, chat.type === "group" ? `${who} : ${text}` : text);
}

function notify(chat, body) {
  if (state.settings.sound && !chat.muted) import("./sounds.js").then((s) => s.incoming());
  if (document.visibilityState === "visible" || !state.settings.notify || typeof Notification === "undefined" || Notification.permission !== "granted") {
    if (document.visibilityState === "visible" && conv.current() !== chat.id) toast(`${core.chatTitle(chat)} : ${body.slice(0, 80)}`);
    return;
  }
  try {
    const n = new Notification(core.chatTitle(chat), { body: body.slice(0, 200), icon: "/icons/icon-192.png", tag: chat.id });
    n.onclick = () => {
      focus();
      openChat(chat.id);
      n.close();
    };
  } catch {
    /* Android sans service worker : pas de notification */
  }
}

// ----- Mon code, contacts, groupes --------------------------------------------------

export function inviteLink(code = state.me.code) {
  return `${location.origin}/c/${code}`;
}

export function showMyCode() {
  const link = inviteLink();
  const share = async () => {
    const text = `Écris-moi sur Talka ! Mon code : ${pretty(state.me.code)}\n${link}`;
    if (navigator.share) {
      try {
        await navigator.share({ title: "Mon code Talka", text, url: link });
      } catch {
        /* annulé */
      }
    } else {
      await navigator.clipboard?.writeText(text);
      toast("Lien copié");
    }
  };
  modal(
    "Mon code Talka",
    h(
      "div",
      { class: "my-code" },
      avatar(state.me.code, 72),
      h("strong", {}, state.profile.name),
      h("div", { class: "code-big", "aria-label": "Votre code" }, pretty(state.me.code)),
      h("img", { class: "qr", src: `/api/qr/${state.me.code}`, alt: "QR code de votre code Talka", width: "220", height: "220" }),
      h("p", { class: "muted small" }, "Montrez ce QR code ou partagez votre lien : on vous écrit sans connaître votre numéro."),
      h(
        "div",
        { class: "row center" },
        h("button", { class: "btn ghost", type: "button", onclick: () => navigator.clipboard?.writeText(pretty(state.me.code)).then(() => toast("Code copié")) }, icon("copy"), " Copier le code"),
        h("button", { class: "btn primary", type: "button", onclick: share }, icon("share"), " Partager"),
      ),
    ),
  );
}

export function openAddContact(prefill = "", name = "") {
  const codeInput = h("input", { class: "field code-input", placeholder: "K7Q2M-X9PRT", value: prefill ? pretty(prefill) : "", autocapitalize: "characters", autocomplete: "off", spellcheck: "false", maxlength: "13", "aria-label": "Code Talka" });
  const known = prefill ? state.contacts.get(prefill) : null;
  const nameInput = h("input", { class: "field", placeholder: "Nom (facultatif)", value: name || known?.name || known?.profileName || "", maxlength: "40", "aria-label": "Nom du contact" });
  const err = h("p", { class: "error" });
  codeInput.addEventListener("input", () => {
    const raw = codeInput.value.toUpperCase().replace(/[^0-9A-Z]/g, "").slice(0, 10);
    codeInput.value = raw.length > 5 ? `${raw.slice(0, 5)}-${raw.slice(5)}` : raw;
    err.textContent = "";
  });
  const scan = "BarcodeDetector" in window ? h("button", { class: "btn ghost", type: "button", onclick: () => scanQr((code) => { codeInput.value = pretty(code); }) }, icon("camera"), " Scanner un QR code") : null;
  const md = modal(
    "Ajouter un contact",
    h(
      "div",
      { class: "stack" },
      h("p", { class: "muted" }, "Entrez le code Talka de votre ami : 10 caractères, comme K7Q2M-X9PRT."),
      codeInput,
      nameInput,
      err,
      scan,
    ),
    {
      actions: [
        { label: "Annuler", cls: "ghost", onclick: (c) => c() },
        {
          label: "Ajouter",
          cls: "primary",
          onclick: async (close) => {
            const code = normalize(codeInput.value);
            if (!code) {
              err.textContent = "Ce code n'est pas valide : 10 caractères, sans 0, 1, I ni O.";
              return;
            }
            try {
              const { reachable } = await core.addContact(code, nameInput.value);
              close();
              toast(reachable ? "Contact ajouté ✓" : "Contact ajouté. Il sera joignable dès qu'il ouvrira Talka.");
              openChat(code);
            } catch (e) {
              err.textContent = e.message;
            }
          },
        },
      ],
    },
  );
  if (prefill) setTimeout(() => nameInput.focus(), 60);
  return md;
}

async function scanQr(onCode) {
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } });
  } catch {
    return toast("Caméra refusée.");
  }
  const video = h("video", { class: "scan-video", autoplay: true, playsinline: true, muted: true });
  video.srcObject = stream;
  let done = false;
  const md = modal("Scanner un QR code", h("div", { class: "stack" }, video, h("p", { class: "muted small" }, "Visez le QR code de votre ami.")), {
    onClose: () => {
      done = true;
      stream.getTracks().forEach((t) => t.stop());
    },
  });
  const detector = new window.BarcodeDetector({ formats: ["qr_code"] });
  const loop = async () => {
    if (done) return;
    try {
      const codes = await detector.detect(video);
      for (const c of codes) {
        const m = /\/c\/([0-9A-Z]{10})/i.exec(c.rawValue) || [null, c.rawValue];
        const code = normalize(m[1]);
        if (code) {
          onCode(code);
          md.close();
          return;
        }
      }
    } catch {
      /* image pas prête */
    }
    setTimeout(loop, 300);
  };
  video.addEventListener("loadeddata", loop);
}

function openNewChat() {
  const people = [...state.contacts.values()].filter((c) => c.saved && !c.blocked).sort((a, b) => core.displayName(a.code).localeCompare(core.displayName(b.code), "fr"));
  const md = modal(
    "Nouvelle discussion",
    h(
      "div",
      { class: "list" },
      h("button", { type: "button", class: "list-item action", onclick: () => { md.close(); openNewGroup(); } }, h("span", { class: "avatar action" }, icon("group")), h("div", { class: "grow strong" }, "Nouveau groupe")),
      h("button", { type: "button", class: "list-item action", onclick: () => { md.close(); openAddContact(); } }, h("span", { class: "avatar action" }, icon("person")), h("div", { class: "grow strong" }, "Ajouter un contact par son code")),
      h("button", { type: "button", class: "list-item action", onclick: () => { md.close(); showMyCode(); } }, h("span", { class: "avatar action" }, icon("qr")), h("div", { class: "grow strong" }, "Mon code et QR code")),
      people.length ? h("div", { class: "list-title" }, "Contacts sur Talka") : h("p", { class: "muted pad" }, "Vous n'avez pas encore de contacts."),
      people.map((c) => contactItem(c.code, () => { md.close(); openChat(c.code); })),
    ),
  );
}

// Choisir des contacts (groupes) : renvoie la liste des codes.
function pickContacts(title, { exclude = [], okLabel = "Suivant" } = {}) {
  return new Promise((resolve) => {
    const chosen = new Set();
    const people = [...state.contacts.values()].filter((c) => c.saved && !c.blocked && !exclude.includes(c.code));
    let done = false;
    const md = modal(
      title,
      people.length
        ? h(
            "div",
            { class: "list" },
            people.map((c) => {
              const box = h("input", { type: "checkbox", "aria-label": core.displayName(c.code) });
              box.addEventListener("change", () => (box.checked ? chosen.add(c.code) : chosen.delete(c.code)));
              return h("label", { class: "list-item" }, avatar(c.code, 40), h("div", { class: "grow" }, core.displayName(c.code)), box);
            }),
          )
        : h("p", { class: "muted" }, "Ajoutez d'abord des contacts avec leur code."),
      {
        onClose: () => !done && resolve(null),
        actions: [
          { label: "Annuler", cls: "ghost", onclick: (c) => c() },
          { label: okLabel, cls: "primary", onclick: (c) => { done = true; resolve([...chosen]); c(); } },
        ],
      },
    );
    return md;
  });
}

// Choisir des discussions (transférer) : renvoie leurs identifiants.
export function pickChats(title) {
  return new Promise((resolve) => {
    const chosen = new Set();
    let done = false;
    const chats = [...state.chats.values()].filter((c) => !c.left && !state.contacts.get(c.id)?.blocked).sort((a, b) => (b.lastAt || 0) - (a.lastAt || 0));
    modal(
      title,
      h(
        "div",
        { class: "list" },
        chats.map((c) => {
          const box = h("input", { type: "checkbox", "aria-label": core.chatTitle(c) });
          box.addEventListener("change", () => (box.checked ? chosen.add(c.id) : chosen.delete(c.id)));
          return h("label", { class: "list-item" }, avatar(c.id, 40), h("div", { class: "grow" }, core.chatTitle(c)), box);
        }),
      ),
      {
        onClose: () => !done && resolve(null),
        actions: [
          { label: "Annuler", cls: "ghost", onclick: (c) => c() },
          { label: "Envoyer", cls: "primary", onclick: (c) => { done = true; resolve([...chosen]); c(); } },
        ],
      },
    );
  });
}

async function openNewGroup() {
  const members = await pickContacts("Nouveau groupe : les membres");
  if (!members) return;
  if (!members.length) return toast("Choisissez au moins un contact.");
  let pic = "";
  const picBtn = h("button", { type: "button", class: "avatar-pick", "aria-label": "Photo du groupe" }, icon("camera"));
  picBtn.addEventListener("click", async () => {
    const f = await pickFile("image/*");
    if (!f) return;
    pic = await avatarFrom(f);
    clear(picBtn, h("img", { src: pic, alt: "" }));
  });
  const name = h("input", { class: "field", placeholder: "Nom du groupe", maxlength: "60", "aria-label": "Nom du groupe" });
  modal("Nouveau groupe", h("div", { class: "stack center" }, picBtn, name, h("p", { class: "muted small" }, `${members.length} membre${members.length > 1 ? "s" : ""} : ${members.map((c) => core.displayName(c)).join(", ")}`)), {
    actions: [
      { label: "Annuler", cls: "ghost", onclick: (c) => c() },
      {
        label: "Créer",
        cls: "primary",
        onclick: async (c) => {
          if (!name.value.trim()) return name.focus();
          const chat = await core.createGroup(name.value, members, pic);
          c();
          openChat(chat.id);
        },
      },
    ],
  });
}

// ----- Les fiches -----------------------------------------------------------------

export async function openInfo(id) {
  const chat = state.chats.get(id);
  if (chat?.type === "group") return groupInfo(chat);
  return contactInfo(id);
}

async function mediaGrid(chatId) {
  const list = (await core.messages(chatId, 500)).filter((m) => m.kind === "image" && !m.deleted).reverse().slice(0, 24);
  if (!list.length) return null;
  return h("div", { class: "info-section" }, h("h3", {}, "Photos"), h("div", { class: "media-grid" }, list.map((m) => h("button", { type: "button", onclick: () => openImage(m.data, m.text) }, h("img", { src: m.data, alt: "", loading: "lazy" })))));
}

async function contactInfo(code) {
  const c = state.contacts.get(code) || { code };
  const chat = state.chats.get(code);
  const p = state.presence.get(code);
  const safety = await safetyNumber(state.me.code, code);
  const md = modal(
    "Infos du contact",
    h(
      "div",
      { class: "info" },
      h(
        "div",
        { class: "info-head" },
        avatar(code, 120),
        h("h2", {}, core.displayName(code)),
        c.profileName && c.name ? h("p", { class: "muted" }, `~${c.profileName}`) : null,
        h("p", { class: "code-small" }, pretty(code)),
        p?.online ? h("p", { class: "online" }, "en ligne") : p?.seen ? h("p", { class: "muted small" }, lastSeen(p.seen)) : null,
        h(
          "div",
          { class: "row center" },
          h("button", { class: "round-btn", type: "button", onclick: () => { md.close(); openChat(code); } }, icon("chat"), h("span", {}, "Message")),
          h("button", { class: "round-btn", type: "button", onclick: () => { md.close(); calls.startCall(code, false); } }, icon("phone"), h("span", {}, "Audio")),
          h("button", { class: "round-btn", type: "button", onclick: () => { md.close(); calls.startCall(code, true); } }, icon("video"), h("span", {}, "Vidéo")),
        ),
      ),
      c.about ? h("div", { class: "info-section" }, h("h3", {}, "Actu"), h("p", {}, c.about)) : null,
      chat ? await mediaGrid(code) : null,
      h(
        "div",
        { class: "info-section" },
        h("h3", {}, icon("shield"), " Chiffrement"),
        h("p", { class: "muted small" }, "Les messages et appels sont chiffrés de bout en bout. Comparez ce numéro de sécurité avec celui affiché chez votre contact : s'il est identique, personne ne s'est glissé entre vous."),
        h("p", { class: "safety" }, safety),
      ),
      h(
        "div",
        { class: "info-section actions" },
        c.saved
          ? h("button", { class: "btn ghost", type: "button", onclick: async () => { const n = await promptBox("Renommer le contact", { value: c.name || c.profileName || "", maxlength: 40 }); if (n !== null) { await core.updateContact(code, { name: n.trim() }); md.close(); contactInfo(code); } } }, icon("edit"), " Renommer")
          : h("button", { class: "btn primary", type: "button", onclick: () => { md.close(); openAddContact(code, c.profileName); } }, icon("person"), " Ajouter aux contacts"),
        chat ? h("button", { class: "btn ghost", type: "button", onclick: () => conv.timerDialog(chat) }, icon("timer"), " Messages éphémères") : null,
        h("button", { class: "btn ghost danger", type: "button", onclick: async () => { md.close(); await core.setBlocked(code, !c.blocked); toast(c.blocked ? "Débloqué" : "Bloqué"); } }, icon("block"), c.blocked ? " Débloquer" : " Bloquer"),
        c.saved ? h("button", { class: "btn ghost danger", type: "button", onclick: async () => { if (await confirmBox("Retirer ce contact ?", "La discussion reste, mais le contact n'est plus enregistré.", "Retirer", true)) { await core.removeContact(code); md.close(); } } }, icon("trash"), " Retirer des contacts") : null,
      ),
    ),
    { wide: true },
  );
}

async function groupInfo(chat) {
  const isAdmin = (chat.admins || []).includes(state.me.code) && !chat.left;
  const render = async () => {
    const fresh = state.chats.get(chat.id);
    const members = (fresh.members || []).slice().sort((a, b) => (a === state.me.code ? -1 : b === state.me.code ? 1 : core.displayName(a).localeCompare(core.displayName(b), "fr")));
    return h(
      "div",
      { class: "info" },
      h(
        "div",
        { class: "info-head" },
        avatar(chat.id, 120),
        h("h2", {}, fresh.name),
        h("p", { class: "muted" }, `Groupe · ${members.length} membre${members.length > 1 ? "s" : ""}`),
        isAdmin
          ? h(
              "div",
              { class: "row center" },
              h("button", { class: "btn ghost", type: "button", onclick: async () => { const n = await promptBox("Nom du groupe", { value: fresh.name, maxlength: 60 }); if (n?.trim()) { await core.updateGroup(chat.id, { name: n.trim() }, `Vous avez renommé le groupe en « ${n.trim()} ».`); refresh(); } } }, icon("edit"), " Renommer"),
              h("button", { class: "btn ghost", type: "button", onclick: async () => { const f = await pickFile("image/*"); if (!f) return; await core.updateGroup(chat.id, { avatar: await avatarFrom(f) }, "Vous avez changé la photo du groupe."); refresh(); } }, icon("camera"), " Photo"),
            )
          : null,
      ),
      await mediaGrid(chat.id),
      h(
        "div",
        { class: "info-section" },
        h("h3", {}, `${members.length} membres`),
        isAdmin
          ? h("button", { type: "button", class: "list-item action", onclick: async () => { const add = await pickContacts("Ajouter des membres", { exclude: fresh.members, okLabel: "Ajouter" }); if (add?.length) { await core.updateGroup(chat.id, { members: [...fresh.members, ...add] }, `Vous avez ajouté ${add.map((c) => core.displayName(c)).join(", ")}.`); refresh(); } } }, h("span", { class: "avatar action" }, icon("plus")), h("div", { class: "grow strong" }, "Ajouter des membres"))
          : null,
        members.map((code) =>
          h(
            "div",
            { class: "list-item" },
            avatar(code, 40),
            h("div", { class: "grow" }, h("div", {}, code === state.me.code ? "Vous" : core.displayName(code)), h("small", { class: "muted" }, pretty(code))),
            (fresh.admins || []).includes(code) ? h("span", { class: "tag" }, "Admin") : null,
            isAdmin && code !== state.me.code
              ? iconButton("more", "Options", (e) =>
                  menu(
                    [
                      !state.contacts.get(code)?.saved ? { icon: "person", label: "Ajouter aux contacts", onclick: () => openAddContact(code) } : { icon: "chat", label: "Envoyer un message", onclick: () => openChat(code) },
                      !(fresh.admins || []).includes(code) ? { icon: "key", label: "Nommer admin", onclick: async () => { await core.updateGroup(chat.id, { admins: [...fresh.admins, code] }, `Vous avez nommé ${core.displayName(code)} admin.`); refresh(); } } : null,
                      { icon: "trash", label: "Retirer du groupe", danger: true, onclick: async () => { await core.updateGroup(chat.id, { members: fresh.members.filter((c) => c !== code), admins: fresh.admins.filter((c) => c !== code) }, `Vous avez retiré ${core.displayName(code)}.`); refresh(); } },
                    ],
                    e.currentTarget,
                  ),
                )
              : null,
          ),
        ),
      ),
      h(
        "div",
        { class: "info-section actions" },
        h("button", { class: "btn ghost", type: "button", onclick: () => conv.timerDialog(fresh) }, icon("timer"), " Messages éphémères"),
        !fresh.left
          ? h("button", { class: "btn ghost danger", type: "button", onclick: async () => { if (await confirmBox("Quitter le groupe ?", "Vous ne recevrez plus ses messages.", "Quitter", true)) { await core.leaveGroup(chat.id); md.close(); } } }, icon("logout"), " Quitter le groupe")
          : null,
      ),
    );
  };
  const md = modal("Infos du groupe", await render(), { wide: true });
  const refresh = async () => {
    const body = md.el.querySelector(".modal-body");
    clear(body, await render());
  };
}

export function openImage(src, caption = "") {
  const viewer = h(
    "div",
    { class: "viewer", role: "dialog", "aria-label": "Photo" },
    h("div", { class: "viewer-bar" }, iconButton("download", "Enregistrer", () => download("photo-talka.jpg", src)), iconButton("close", "Fermer", () => close())),
    h("img", { src, alt: caption || "Photo" }),
    caption ? h("p", { class: "viewer-caption" }, caption) : null,
  );
  const close = () => {
    viewer.remove();
    document.removeEventListener("keydown", onKey);
  };
  const onKey = (e) => e.key === "Escape" && close();
  document.addEventListener("keydown", onKey);
  viewer.addEventListener("click", (e) => e.target === viewer && close());
  document.body.append(viewer);
}

async function openStarred() {
  const list = await core.starredMessages();
  modal(
    "Messages favoris",
    list.length
      ? h(
          "div",
          { class: "list" },
          list.map((m) =>
            h(
              "button",
              { type: "button", class: "list-item", onclick: () => openChat(m.chat) },
              avatar(m.from, 36),
              h("div", { class: "grow" }, h("small", { class: "muted" }, `${core.displayName(m.from)} · ${core.chatTitle(state.chats.get(m.chat))} · ${listTime(m.at)}`), h("div", { class: "ellipsis" }, m.kind === "text" ? m.text : m.kind === "image" ? "📷 Photo" : m.kind === "voice" ? "🎙️ Vocal" : `📄 ${m.name || ""}`)),
            ),
          ),
        )
      : h("p", { class: "muted" }, "Appuyez longuement sur un message (ou ouvrez son menu) pour le mettre en favori."),
  );
}

// ----- Actus (statuts) ----------------------------------------------------------------

const BGS = ["#008069", "#5e47de", "#c4532d", "#1f7aec", "#a62c71", "#b7820b", "#243b4a"];

async function renderStatuses() {
  const list = await core.statuses();
  if (ui.tab !== "status") return;
  const mine = list.filter((s) => s.owner === state.me.code);
  const byOwner = new Map();
  for (const s of list) {
    if (s.owner === state.me.code || state.contacts.get(s.owner)?.blocked) continue;
    if (!byOwner.has(s.owner)) byOwner.set(s.owner, []);
    byOwner.get(s.owner).push(s);
  }
  const owners = [...byOwner.entries()].map(([owner, items]) => ({ owner, items, unseen: items.some((s) => !s.seen), last: items[items.length - 1].at }));
  const fresh = owners.filter((o) => o.unseen).sort((a, b) => b.last - a.last);
  const seen = owners.filter((o) => !o.unseen).sort((a, b) => b.last - a.last);
  const row = (o) =>
    h(
      "button",
      { type: "button", class: "list-item", onclick: () => viewStatuses(o.items, o.items.findIndex((s) => !s.seen)) },
      statusRing(avatar(o.owner, 46), !o.unseen),
      h("div", { class: "grow" }, h("div", { class: "strong" }, core.displayName(o.owner)), h("small", { class: "muted" }, listTime(o.last))),
    );
  clear(
    ui.body,
    h(
      "div",
      { class: "list" },
      h(
        "div",
        { class: "list-item my-status" },
        h(
          "button",
          { type: "button", class: "my-status-avatar", "aria-label": "Voir mon statut", onclick: () => (mine.length ? viewStatuses(mine, 0) : newTextStatus()) },
          mine.length ? statusRing(avatar(state.me.code, 46), true) : avatar(state.me.code, 46),
          mine.length ? null : h("span", { class: "plus-badge" }, "+"),
        ),
        h("div", { class: "grow" }, h("div", { class: "strong" }, "Mon statut"), h("small", { class: "muted" }, mine.length ? `${mine.length} actu${mine.length > 1 ? "s" : ""} · ${listTime(mine[mine.length - 1].at)}` : "Appuyez pour ajouter une actu")),
        iconButton("text", "Statut texte", newTextStatus),
        iconButton("camera", "Statut photo", newPhotoStatus),
      ),
      fresh.length ? h("div", { class: "list-title" }, "Récentes") : null,
      fresh.map(row),
      seen.length ? h("div", { class: "list-title" }, "Vues") : null,
      seen.map(row),
      !owners.length ? h("p", { class: "muted pad" }, "Les actus de vos contacts apparaîtront ici. Elles disparaissent au bout de 24 heures.") : null,
    ),
  );
}

function newTextStatus() {
  let bg = 0;
  const ta = h("textarea", { class: "status-input", placeholder: "Écrivez une actu", maxlength: "700", "aria-label": "Texte de l'actu" });
  const card = h("div", { class: "status-compose", style: { background: BGS[0] } }, ta);
  const md = modal("Nouvelle actu", h("div", { class: "stack" }, card), {
    actions: [
      { label: "Couleur", cls: "ghost", onclick: () => { bg = (bg + 1) % BGS.length; card.style.background = BGS[bg]; } },
      { label: "Publier", cls: "primary", onclick: async (c) => { if (!ta.value.trim()) return ta.focus(); await core.postStatus({ type: "text", text: ta.value.trim(), bg: BGS[bg] }); c(); toast("Actu publiée pour 24 heures"); } },
    ],
  });
  return md;
}

async function newPhotoStatus() {
  const f = await pickFile("image/*");
  if (!f) return;
  const img = await compressImage(f, 1280, 0.8);
  const caption = h("input", { class: "field", placeholder: "Légende", maxlength: "300" });
  modal("Nouvelle actu", h("div", { class: "stack" }, h("img", { class: "preview-img", src: img.data, alt: "" }), caption), {
    wide: true,
    actions: [
      { label: "Annuler", cls: "ghost", onclick: (c) => c() },
      { label: "Publier", cls: "primary", onclick: async (c) => { await core.postStatus({ type: "image", data: img.data, text: caption.value.trim() }); c(); toast("Actu publiée pour 24 heures"); } },
    ],
  });
}

function viewStatuses(items, start = 0) {
  let i = Math.max(0, start);
  let timer = null;
  let paused = false;
  let begin = 0;
  let left = 5000;
  const bars = h("div", { class: "story-bars" }, items.map(() => h("span", {}, h("i"))));
  const stage = h("div", { class: "story-stage" });
  const headInfo = h("div", { class: "story-head-info" });
  const footer = h("div", { class: "story-foot" });
  const root = h(
    "div",
    { class: "story", role: "dialog", "aria-label": "Actus" },
    bars,
    h("div", { class: "story-head" }, headInfo, iconButton("close", "Fermer", () => close())),
    stage,
    footer,
    h("button", { type: "button", class: "story-prev", "aria-label": "Précédente", onclick: () => go(i - 1) }),
    h("button", { type: "button", class: "story-next", "aria-label": "Suivante", onclick: () => go(i + 1) }),
  );
  const close = () => {
    clearTimeout(timer);
    root.remove();
    document.removeEventListener("keydown", onKey);
    renderSideBody();
  };
  const onKey = (e) => {
    if (e.key === "Escape") close();
    if (e.key === "ArrowRight") go(i + 1);
    if (e.key === "ArrowLeft") go(i - 1);
  };
  const go = (n) => {
    if (n < 0) n = 0;
    if (n >= items.length) return close();
    i = n;
    const s = items[i];
    [...bars.children].forEach((b, k) => {
      b.className = k < i ? "done" : k === i ? "run" : "";
    });
    clear(headInfo, avatar(s.owner, 36), h("div", {}, h("strong", {}, s.owner === state.me.code ? "Mon statut" : core.displayName(s.owner)), h("small", {}, listTime(s.at))));
    clear(
      stage,
      s.type === "image"
        ? h("div", { class: "story-img" }, h("img", { src: s.data, alt: "" }), s.text ? h("p", {}, s.text) : null)
        : h("div", { class: "story-text", style: { background: s.bg || BGS[0] } }, s.text),
    );
    if (s.owner === state.me.code) {
      const viewers = Object.entries(s.viewers || {});
      clear(
        footer,
        h("button", { type: "button", class: "btn ghost light", onclick: () => { paused = true; clearTimeout(timer); modal("Vue par", viewers.length ? h("div", { class: "list" }, viewers.map(([c, t]) => h("div", { class: "list-item" }, avatar(c, 36), h("div", { class: "grow" }, core.displayName(c)), h("small", { class: "muted" }, time(t))))) : h("p", { class: "muted" }, "Personne pour l'instant."), { onClose: () => { paused = false; go(i); } }); } }, `👁 ${viewers.length}`),
        h("button", { type: "button", class: "btn ghost light", onclick: async () => { if (await confirmBox("Supprimer cette actu ?", "Elle disparaîtra aussi chez vos contacts.", "Supprimer", true)) { await core.deleteStatus(s.id); close(); } } }, "Supprimer"),
      );
    } else {
      core.markStatusSeen(s);
      clear(footer, h("button", { type: "button", class: "btn ghost light", onclick: async () => { close(); await openChat(s.owner); } }, "Répondre"));
    }
    clearTimeout(timer);
    begin = Date.now();
    left = 5000;
    timer = setTimeout(() => go(i + 1), left);
  };
  // Appui long : pause.
  stage.addEventListener("pointerdown", () => {
    paused = true;
    clearTimeout(timer);
    left -= Date.now() - begin;
    root.classList.add("paused");
  });
  const resume = () => {
    if (!paused) return;
    paused = false;
    root.classList.remove("paused");
    begin = Date.now();
    timer = setTimeout(() => go(i + 1), Math.max(300, left));
  };
  stage.addEventListener("pointerup", resume);
  stage.addEventListener("pointerleave", resume);
  document.addEventListener("keydown", onKey);
  document.body.append(root);
  go(i);
}

// ----- Appels ---------------------------------------------------------------------

async function renderCalls() {
  const list = await core.callLog();
  if (ui.tab !== "calls") return;
  clear(
    ui.body,
    h(
      "div",
      { class: "list" },
      h("button", { type: "button", class: "list-item action", onclick: async () => { const [code] = (await pickContacts("Appeler…", { okLabel: "Appeler" })) || []; if (code) calls.startCall(code, false); } }, h("span", { class: "avatar action" }, icon("phone")), h("div", { class: "grow strong" }, "Nouvel appel")),
      list.length ? h("div", { class: "list-title row" }, h("span", { class: "grow" }, "Récents"), h("button", { type: "button", class: "link", onclick: async () => (await confirmBox("Effacer l'historique des appels ?", "", "Effacer", true)) && core.clearCalls() }, "Effacer")) : h("p", { class: "muted pad" }, "Aucun appel pour l'instant. Les appels audio et vidéo passent directement d'un appareil à l'autre."),
      list.map((c) =>
        h(
          "div",
          { class: "list-item" },
          avatar(c.peer, 44),
          h(
            "div",
            { class: "grow" },
            h("div", { class: `strong ${c.missed ? "missed" : ""}` }, core.displayName(c.peer)),
            h("small", { class: `muted call-line ${c.missed ? "missed" : ""}` }, icon(c.dir === "in" ? "incoming" : "outgoing", "small"), ` ${listTime(c.at)}${c.dur ? ` · ${core.fmtDur(c.dur)}` : ""}`),
          ),
          iconButton(c.video ? "video" : "phone", "Rappeler", () => calls.startCall(c.peer, c.video)),
        ),
      ),
    ),
  );
}

// ----- Réglages ---------------------------------------------------------------------

function toggle(label, desc, checked, onchange) {
  const input = h("input", { type: "checkbox", class: "switch", checked, onchange: (e) => onchange(e.target.checked) });
  return h("label", { class: "setting" }, h("div", { class: "grow" }, h("div", {}, label), desc ? h("small", { class: "muted" }, desc) : null), input);
}

function setting(ic, label, desc, onclick, danger = false) {
  return h("button", { type: "button", class: `setting ${danger ? "danger" : ""}`, onclick }, icon(ic), h("div", { class: "grow" }, h("div", {}, label), desc ? h("small", { class: "muted" }, desc) : null));
}

export async function openSettings() {
  const s = state.settings;
  const usage = await db.usage();
  const hasPin = await lock.hasPin();
  const name = h("input", { class: "field", value: state.profile.name, maxlength: "40", "aria-label": "Votre nom" });
  const about = h("input", { class: "field", value: state.profile.about, maxlength: "140", "aria-label": "Votre actu" });
  const saveProfile = async () => {
    if (name.value.trim() && (name.value.trim() !== state.profile.name || about.value.trim() !== state.profile.about)) {
      await core.saveProfile({ name: name.value.trim(), about: about.value.trim() });
      toast("Profil enregistré");
    }
  };
  name.addEventListener("change", saveProfile);
  about.addEventListener("change", saveProfile);
  const avatarBtn = h("button", { type: "button", class: "avatar-pick big", "aria-label": "Changer de photo" }, avatar(state.me.code, 110), h("span", { class: "avatar-edit" }, icon("camera")));
  avatarBtn.addEventListener("click", async () => {
    const choice = await new Promise((resolve) => {
      menu([{ icon: "image", label: "Choisir une photo", onclick: () => resolve("pick") }, state.profile.avatar ? { icon: "trash", label: "Retirer la photo", danger: true, onclick: () => resolve("remove") } : null], avatarBtn);
    });
    if (choice === "remove") await core.saveProfile({ avatar: "" });
    else {
      const f = await pickFile("image/*");
      if (!f) return;
      await core.saveProfile({ avatar: await avatarFrom(f) });
    }
    clear(avatarBtn, avatar(state.me.code, 110), h("span", { class: "avatar-edit" }, icon("camera")));
    renderSide();
  });
  const theme = h(
    "select",
    { class: "field", "aria-label": "Thème", onchange: (e) => core.saveSettings({ theme: e.target.value }) },
    [["auto", "Comme l'appareil"], ["light", "Clair"], ["dark", "Sombre"]].map(([v, l]) => h("option", { value: v, selected: s.theme === v }, l)),
  );
  const font = h(
    "select",
    { class: "field", "aria-label": "Taille du texte", onchange: (e) => core.saveSettings({ fontSize: Number(e.target.value) }) },
    [[13, "Petite"], [15, "Moyenne"], [17, "Grande"], [19, "Très grande"]].map(([v, l]) => h("option", { value: String(v), selected: s.fontSize === v }, l)),
  );
  const lockAfter = h(
    "select",
    { class: "field", "aria-label": "Verrouillage automatique", onchange: (e) => core.saveSettings({ lockAfter: Number(e.target.value) }) },
    [[0, "Dès que je quitte Talka"], [60, "Après 1 minute"], [900, "Après 15 minutes"], [3600, "Après 1 heure"]].map(([v, l]) => h("option", { value: String(v), selected: (s.lockAfter || 0) === v }, l)),
  );

  const md = modal(
    "Réglages",
    h(
      "div",
      { class: "settings" },
      h("div", { class: "settings-profile" }, avatarBtn, h("div", { class: "stack grow" }, h("label", { class: "muted small" }, "Votre nom"), name, h("label", { class: "muted small" }, "Actu"), about)),
      h("div", { class: "settings-group" }, h("h3", {}, "Mon code"), setting("qr", pretty(state.me.code), "Votre identifiant Talka : il remplace le numéro de téléphone", () => showMyCode())),
      h(
        "div",
        { class: "settings-group" },
        h("h3", {}, "Confidentialité"),
        toggle("Confirmations de lecture", "Désactivées, vous ne voyez pas non plus celles des autres", s.readReceipts, (v) => core.saveSettings({ readReceipts: v })),
        toggle("Masquer « en ligne »", "Vos contacts ne voient plus quand vous êtes connecté", s.hideOnline, (v) => core.saveSettings({ hideOnline: v })),
        setting("lock", hasPin ? "Changer le code de verrouillage" : "Code de verrouillage", hasPin ? "Talka demande votre code à l'ouverture" : "Protégez Talka par un code à 4 à 6 chiffres", () => { md.close(); setupPin(); }),
        hasPin ? h("div", { class: "setting" }, h("div", { class: "grow" }, "Verrouillage automatique"), lockAfter) : null,
        hasPin ? setting("lock", "Retirer le code de verrouillage", "", async () => { if (await confirmBox("Retirer le code ?", "Talka s'ouvrira sans code.", "Retirer", true)) { await lock.setPin(""); setLockAvailable(false); md.close(); toast("Code retiré"); renderSide(); } }, true) : null,
        setting("block", "Contacts bloqués", `${[...state.contacts.values()].filter((c) => c.blocked).length} bloqué(s)`, () => blockedList()),
      ),
      h(
        "div",
        { class: "settings-group" },
        h("h3", {}, "Discussions"),
        h("div", { class: "setting" }, h("div", { class: "grow" }, "Thème"), theme),
        h("div", { class: "setting" }, h("div", { class: "grow" }, "Taille du texte"), font),
        setting("palette", "Fond d'écran", "Pour toutes les discussions", () => conv.wallpaperDialog(null)),
        toggle("Entrée pour envoyer", "Sur ordinateur ; Maj + Entrée va à la ligne", s.enterSends, (v) => core.saveSettings({ enterSends: v })),
      ),
      h(
        "div",
        { class: "settings-group" },
        h("h3", {}, "Notifications"),
        toggle("Notifications", typeof Notification === "undefined" ? "Non disponibles sur ce navigateur" : "Quand Talka est en arrière-plan", s.notify && typeof Notification !== "undefined" && Notification.permission === "granted", async (v) => {
          if (v && typeof Notification !== "undefined") {
            const p = await Notification.requestPermission();
            if (p !== "granted") {
              toast("Notifications refusées par le navigateur");
              return core.saveSettings({ notify: false });
            }
          }
          core.saveSettings({ notify: v });
        }),
        toggle("Sons", "Messages, envois, appels", s.sound, (v) => core.saveSettings({ sound: v })),
      ),
      h(
        "div",
        { class: "settings-group" },
        h("h3", {}, "Stockage et sauvegarde"),
        h("p", { class: "muted small pad" }, "Talka n'a pas de base de données : tout est gardé dans ce navigateur", usage ? ` (${bytes(usage.used)} utilisés).` : "."),
        setting("download", "Exporter une sauvegarde chiffrée", "Un fichier protégé par mot de passe : vos clés, contacts et discussions", () => exportBackup()),
        setting("upload", "Importer une sauvegarde", "Remplace tout ce qui est sur cet appareil", () => importBackup()),
      ),
      h(
        "div",
        { class: "settings-group" },
        h("h3", {}, "Aide"),
        setting("info", "À propos de Talka", "Le site, la confidentialité, les questions", () => open("/", "_blank", "noopener")),
        setting("trash", "Supprimer toutes mes données", "Efface votre code, vos contacts et vos discussions de cet appareil", () => wipe(), true),
      ),
      h("p", { class: "muted tiny center" }, "Talka · fait avec Paquets 📦 · chiffré avec WebCrypto"),
    ),
    { wide: true, cls: "settings-modal", onClose: saveProfile },
  );
}

function blockedList() {
  const blocked = [...state.contacts.values()].filter((c) => c.blocked);
  modal(
    "Contacts bloqués",
    blocked.length
      ? h("div", { class: "list" }, blocked.map((c) => h("div", { class: "list-item" }, avatar(c.code, 40), h("div", { class: "grow" }, core.displayName(c.code)), h("button", { class: "btn ghost", type: "button", onclick: async (e) => { await core.setBlocked(c.code, false); e.target.closest(".list-item").remove(); } }, "Débloquer"))))
      : h("p", { class: "muted" }, "Aucun contact bloqué."),
  );
}

export function setupPin() {
  return new Promise((resolve) => {
    let first = null;
    const host = h("div", { class: "lock-screen setup" });
    const cancel = h("button", { type: "button", class: "btn ghost light", onclick: () => { pad.destroy(); host.remove(); resolve(false); } }, "Annuler");
    const pad = lock.pinPad({
      title: "Code de verrouillage",
      subtitle: "Choisissez un code de 4 à 6 chiffres, puis OK",
      onDone: async (pin, api) => {
        if (!first) {
          first = pin;
          api.info("Confirmez votre code");
          return;
        }
        if (pin !== first) {
          first = null;
          api.error("Les codes ne correspondent pas. Recommencez.");
          return;
        }
        await lock.setPin(pin);
        setLockAvailable(true);
        api.destroy();
        host.remove();
        toast("Code de verrouillage activé 🔒");
        renderSide();
        resolve(true);
      },
    });
    host.append(pad.el, cancel);
    document.body.append(host);
  });
}

async function exportBackup() {
  const pw = await promptBox("Mot de passe de la sauvegarde", { label: "Il sera demandé pour la restaurer. Sans lui, la sauvegarde est illisible (8 caractères au moins).", type: "password", okLabel: "Exporter" });
  if (pw === null) return;
  if (pw.length < 8) return toast("8 caractères au moins.");
  toast("Préparation de la sauvegarde…");
  const file = await core.exportBackup(pw);
  const day = new Date().toISOString().slice(0, 10);
  download(`talka-sauvegarde-${day}.talka`, new Blob([JSON.stringify(file)], { type: "application/json" }));
}

export async function importBackup() {
  const f = await pickFile(".talka,application/json");
  if (!f) return;
  let file;
  try {
    file = JSON.parse(await f.text());
  } catch {
    return toast("Ce fichier n'est pas une sauvegarde Talka.");
  }
  const pw = await promptBox("Mot de passe de la sauvegarde", { type: "password", okLabel: "Restaurer" });
  if (pw === null) return;
  if (state.me && !(await confirmBox("Remplacer les données ?", "Tout ce qui est sur cet appareil sera remplacé par la sauvegarde.", "Remplacer", true))) return;
  try {
    state.net?.stop();
    await core.importBackup(pw, file);
    toast("Sauvegarde restaurée ✓");
    setTimeout(() => location.reload(), 600);
  } catch (e) {
    toast(e.message);
    if (state.net) location.reload();
  }
}

async function wipe() {
  if (!(await confirmBox("Supprimer toutes vos données ?", "Votre code, vos clés, vos contacts et vos discussions seront effacés de cet appareil. Sans sauvegarde, ils seront perdus pour toujours.", "Tout supprimer", true))) return;
  await core.wipeEverything();
  location.reload();
}

// ----- L'accueil -----------------------------------------------------------------------

export function welcome() {
  return new Promise((resolve) => {
    const root = document.getElementById("root");
    root.className = "";
    let pic = "";
    const picBtn = h("button", { type: "button", class: "avatar-pick big", "aria-label": "Ajouter une photo" }, icon("camera"));
    picBtn.addEventListener("click", async () => {
      const f = await pickFile("image/*");
      if (!f) return;
      pic = await avatarFrom(f);
      clear(picBtn, h("img", { src: pic, alt: "" }));
    });
    const name = h("input", { class: "field", placeholder: "Votre nom", maxlength: "40", autocomplete: "name", "aria-label": "Votre nom" });
    const err = h("p", { class: "error" });
    const go = h("button", { class: "btn primary big", type: "button" }, "Créer mon code");
    go.addEventListener("click", async () => {
      if (!name.value.trim()) {
        err.textContent = "Choisissez un nom : c'est lui que verront vos contacts.";
        name.focus();
        return;
      }
      go.disabled = true;
      go.textContent = "Création de vos clés…";
      await core.createAccount(name.value, pic);
      resolve();
    });
    name.addEventListener("keydown", (e) => e.key === "Enter" && go.click());
    clear(
      root,
      h(
        "div",
        { class: "welcome" },
        h(
          "div",
          { class: "welcome-card" },
          h("img", { src: "/logo.svg", alt: "", width: "80", height: "80" }),
          h("h1", {}, "Bienvenue sur Talka"),
          h("p", { class: "muted" }, "Pas de numéro de téléphone ici : votre appareil va créer vos clés de chiffrement et votre code Talka, que vous partagerez avec vos proches."),
          picBtn,
          name,
          err,
          go,
          h("button", { class: "link", type: "button", onclick: importBackup }, "Restaurer une sauvegarde"),
          h("p", { class: "muted tiny" }, icon("lock"), " Tout reste sur cet appareil. ", h("a", { href: "/confidentialite", target: "_blank" }, "Confidentialité")),
        ),
      ),
    );
    setTimeout(() => name.focus(), 50);
  });
}

// Juste après la création : le code, à partager.
export function revealCode() {
  return new Promise((resolve) => {
    modal(
      "Voici votre code Talka 🎉",
      h(
        "div",
        { class: "my-code" },
        h("div", { class: "code-big" }, pretty(state.me.code)),
        h("p", { class: "muted" }, "C'est votre identifiant : il remplace le numéro de téléphone. Donnez-le à vos amis, ou partagez votre lien d'invitation."),
        h("p", { class: "muted small" }, "Conseil : protégez Talka avec un code de verrouillage, et exportez de temps en temps une sauvegarde chiffrée (Réglages)."),
      ),
      {
        onClose: resolve,
        actions: [
          { label: "Code de verrouillage", cls: "ghost", onclick: async (c) => { c(); await setupPin(); } },
          { label: "C'est parti", cls: "primary", onclick: (c) => c() },
        ],
      },
    );
  });
}
