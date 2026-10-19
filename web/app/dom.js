// De quoi construire l'interface sans framework : `h()`, les icônes, les
// fenêtres, les toasts, les dates.

export function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k.startsWith("on") && typeof v === "function") el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === "html") el.innerHTML = v;
    else if (k in el && typeof v !== "string") el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

export function clear(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

// Les icônes, dessinées en SVG (24×24, trait ou plein).
const PATHS = {
  chat: "M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H8l-4 4V6a2 2 0 0 1 2-2z",
  status: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 4a6 6 0 1 1 0 12 6 6 0 0 1 0-12z",
  phone: "M6.6 10.8a15.1 15.1 0 0 0 6.6 6.6l2.2-2.2a1 1 0 0 1 1-.25 11.4 11.4 0 0 0 3.6.57 1 1 0 0 1 1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 1z",
  video: "M17 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3.5l4 4v-11z",
  plus: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z",
  more: "M12 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4zm0 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4zm0 6a2 2 0 1 0 0 4 2 2 0 0 0 0-4z",
  back: "M20 11H7.8l5.6-5.6L12 4l-8 8 8 8 1.4-1.4L7.8 13H20z",
  send: "M2 21l21-9L2 3v7l15 2-15 2z",
  mic: "M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5.3-3a5.3 5.3 0 0 1-10.6 0H5a7 7 0 0 0 6 6.9V21h2v-3.1A7 7 0 0 0 19 11z",
  attach: "M16.5 6v11.5a4 4 0 0 1-8 0V5a2.5 2.5 0 0 1 5 0v10.5a1 1 0 0 1-2 0V6H10v9.5a2.5 2.5 0 0 0 5 0V5a4 4 0 0 0-8 0v12.5a5.5 5.5 0 0 0 11 0V6z",
  smile: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm-3.5 6a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zm7 0a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM12 17.5a5.5 5.5 0 0 1-5-3.5h10a5.5 5.5 0 0 1-5 3.5z",
  search: "M15.5 14h-.8l-.3-.3A6.5 6.5 0 1 0 14 15.5l.3.3v.8l5 5 1.5-1.5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z",
  close: "M19 6.4L17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z",
  check: "M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z",
  lock: "M18 8h-1V6a5 5 0 0 0-10 0v2H6a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10a2 2 0 0 0-2-2zM9 6a3 3 0 0 1 6 0v2H9z",
  settings: "M19.4 13a7.5 7.5 0 0 0 0-2l2.1-1.6-2-3.5-2.5 1a7 7 0 0 0-1.7-1L15 3h-4l-.4 2.9a7 7 0 0 0-1.7 1l-2.5-1-2 3.5L6.6 11a7.5 7.5 0 0 0 0 2l-2.1 1.6 2 3.5 2.5-1c.5.4 1.1.7 1.7 1L11 21h4l.4-2.9c.6-.3 1.2-.6 1.7-1l2.5 1 2-3.5zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z",
  group: "M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm-8 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm0 2c-2.3 0-7 1.2-7 3.5V19h14v-2.5C15 14.2 10.3 13 8 13zm8 0c-.3 0-.6 0-1 .1a4.2 4.2 0 0 1 2 3.4V19h6v-2.5c0-2.3-4.7-3.5-7-3.5z",
  person: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm0 2c-2.7 0-8 1.3-8 4v2h16v-2c0-2.7-5.3-4-8-4z",
  camera: "M12 9a4 4 0 1 0 0 8 4 4 0 0 0 0-8zm8-3h-3.2L15 4H9L7.2 6H4a2 2 0 0 0-2 2v11a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2z",
  image: "M21 19V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2zM8.5 13.5l2.5 3 3.5-4.5 4.5 6H5z",
  file: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zm-1 7V3.5L18.5 9z",
  reply: "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z",
  forward: "M14 9V5l7 7-7 7v-4.1c-5 0-8.5 1.6-11 5.1 1-5 4-10 11-11z",
  copy: "M16 1H4a2 2 0 0 0-2 2v14h2V3h12zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11z",
  star: "M12 17.3l6.2 3.7-1.6-7L22 9.2l-7.2-.6L12 2 9.2 8.6 2 9.2 7.4 14l-1.6 7z",
  trash: "M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6zM19 4h-3.5l-1-1h-5l-1 1H5v2h14z",
  edit: "M3 17.2V21h3.8L17.8 10l-3.8-3.8zM20.7 7a1 1 0 0 0 0-1.4l-2.3-2.3a1 1 0 0 0-1.4 0l-1.8 1.8 3.8 3.8z",
  play: "M8 5v14l11-7z",
  pause: "M6 19h4V5H6zm8-14v14h4V5z",
  hangup: "M12 9c-1.6 0-3.1.3-4.6.7v3.1a1 1 0 0 1-.6.9 11.5 11.5 0 0 0-2.7 1.9 1 1 0 0 1-1.4 0L.3 13.2a1 1 0 0 1 0-1.4A17 17 0 0 1 12 7a17 17 0 0 1 11.7 4.8 1 1 0 0 1 0 1.4l-2.5 2.5a1 1 0 0 1-1.4 0 11.3 11.3 0 0 0-2.7-1.9 1 1 0 0 1-.6-.9V9.7A15 15 0 0 0 12 9z",
  micoff: "M19 11h-1.7a5 5 0 0 1-.5 2.2l1.2 1.2A7 7 0 0 0 19 11zm-4 .2V5a3 3 0 0 0-6 0v.2zM4.3 3L3 4.3l6 6V11a3 3 0 0 0 4.2 2.8l1.6 1.6A5.3 5.3 0 0 1 6.7 11H5a7 7 0 0 0 6 6.9V21h2v-3.1a7 7 0 0 0 3.3-1.3l3.4 3.4 1.3-1.3z",
  videooff: "M21 6.5l-4 4V7a1 1 0 0 0-1-1H9.8L21 17.2zM3.3 2L2 3.3 4.7 6H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h12a1 1 0 0 0 .6-.2l3.1 3.2 1.3-1.3z",
  switchcam: "M20 5h-3.2L15 3H9L7.2 5H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm-5 11.5V14H9v2.5L5.5 13 9 9.5V12h6V9.5l3.5 3.5z",
  speaker: "M3 9v6h4l5 5V4L7 9zm13.5 3A4.5 4.5 0 0 0 14 8v8a4.5 4.5 0 0 0 2.5-4z",
  qr: "M3 11h8V3H3zm2-6h4v4H5zm8-2v8h8V3zm6 6h-4V5h4zM3 21h8v-8H3zm2-6h4v4H5zm13 0h-2v-2h-3v3h2v2h-2v3h2v-2h2v2h3v-3h-2zm2-2h1v2h-1zm-7 8h2v-2h-2z",
  archive: "M20.5 5.2l-1.4-1.7A1.5 1.5 0 0 0 18 3H6a1.5 1.5 0 0 0-1.2.5L3.5 5.2A2 2 0 0 0 3 6.5V19a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6.5a2 2 0 0 0-.5-1.3zM12 17.5L6.5 12H10v-2h4v2h3.5zM5.1 5l.8-1h12l1 1z",
  pin: "M16 9V4h1V2H7v2h1v5l-2 2v2h5.2v7h1.6v-7H18v-2z",
  bell: "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2zm6-6v-5c0-3.1-1.6-5.6-4.5-6.3V4a1.5 1.5 0 0 0-3 0v.7C7.6 5.4 6 7.9 6 11v5l-2 2v1h16v-1z",
  belloff: "M20 18.7L5.3 4 4 5.3l2.9 2.9A6.7 6.7 0 0 0 6 11v5l-2 2v1h13.7l1 1zM12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2zm6-7.7V11c0-3.1-1.6-5.6-4.5-6.3V4a1.5 1.5 0 0 0-3 0v.7c-.5.1-1 .3-1.4.5z",
  timer: "M15 1H9v2h6zm-4 13h2V8h-2zm8-6.6l1.4-1.4c-.4-.5-.9-1-1.4-1.4L17.6 6A9 9 0 1 0 21 13a9 9 0 0 0-2-5.6zM12 20a7 7 0 1 1 0-14 7 7 0 0 1 0 14z",
  key: "M12.6 10a6 6 0 1 0 0 4H17v4h4v-4h2v-4zM7 14a2 2 0 1 1 0-4 2 2 0 0 1 0 4z",
  download: "M19 9h-4V3H9v6H5l7 7zM5 18v2h14v-2z",
  upload: "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z",
  share: "M18 16a3 3 0 0 0-2 .8L8.9 12.7a3 3 0 0 0 0-1.4L16 7.2A3 3 0 1 0 15 5v.3L8 9.4a3 3 0 1 0 0 5.2l7 4.1v.3a3 3 0 1 0 3-3z",
  block: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM4 12a8 8 0 0 1 12.9-6.3L5.7 16.9A8 8 0 0 1 4 12zm8 8a8 8 0 0 1-4.9-1.7L18.3 7.1A8 8 0 0 1 12 20z",
  info: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 15h-2v-6h2zm0-8h-2V7h2z",
  logout: "M10 17l1.4-1.4L8.8 13H20v-2H8.8l2.6-2.6L10 7l-5 5zM4 5h8V3H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h8v-2H4z",
  palette: "M12 3a9 9 0 0 0 0 18 1.5 1.5 0 0 0 1.1-2.5 1.5 1.5 0 0 1 1.1-2.5H16a5 5 0 0 0 5-5c0-4.4-4-8-9-8zM6.5 12a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3-4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm5 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3zm3 4a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3z",
  text: "M5 4v3h5.5v12h3V7H19V4z",
  arrowdown: "M7 10l5 5 5-5z",
  incoming: "M20 5.4L18.6 4 7 15.6V9H5v10h10v-2H8.4z",
  outgoing: "M9 5v2h6.6L4 18.6 5.4 20 17 8.4V15h2V5z",
  shield: "M12 1L3 5v6c0 5.6 3.8 10.7 9 12 5.2-1.3 9-6.4 9-12V5z",
};

export function icon(name, cls = "") {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("class", `icon ${cls}`.trim());
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", PATHS[name] || PATHS.info);
  path.setAttribute("fill", "currentColor");
  svg.append(path);
  return svg;
}

export function iconButton(name, label, onclick, cls = "") {
  return h("button", { class: `icon-btn ${cls}`.trim(), type: "button", title: label, "aria-label": label, onclick }, icon(name));
}

// ----- Dates ------------------------------------------------------------------

const timeFmt = new Intl.DateTimeFormat("fr-FR", { hour: "2-digit", minute: "2-digit" });
const dayFmt = new Intl.DateTimeFormat("fr-FR", { weekday: "long" });
const dateFmt = new Intl.DateTimeFormat("fr-FR", { day: "numeric", month: "long", year: "numeric" });
const shortDate = new Intl.DateTimeFormat("fr-FR", { day: "2-digit", month: "2-digit", year: "2-digit" });

export const time = (t) => timeFmt.format(t);

function startOfDay(t) {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export function dayLabel(t) {
  const diff = Math.round((startOfDay(Date.now()) - startOfDay(t)) / 86400000);
  if (diff === 0) return "Aujourd'hui";
  if (diff === 1) return "Hier";
  if (diff < 7) return cap(dayFmt.format(t));
  return dateFmt.format(t);
}

export function listTime(t) {
  const diff = Math.round((startOfDay(Date.now()) - startOfDay(t)) / 86400000);
  if (diff === 0) return time(t);
  if (diff === 1) return "Hier";
  if (diff < 7) return cap(dayFmt.format(t));
  return shortDate.format(t);
}

export function lastSeen(t) {
  if (!t) return "";
  const diff = Math.round((startOfDay(Date.now()) - startOfDay(t)) / 86400000);
  if (diff === 0) return `vu(e) aujourd'hui à ${time(t)}`;
  if (diff === 1) return `vu(e) hier à ${time(t)}`;
  return `vu(e) le ${shortDate.format(t)}`;
}

export function sameDay(a, b) {
  return startOfDay(a) === startOfDay(b);
}

export const cap = (s) => s.charAt(0).toUpperCase() + s.slice(1);

export function bytes(n) {
  if (!n) return "0 o";
  const units = ["o", "Ko", "Mo", "Go"];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i++;
  }
  return `${n.toFixed(i ? 1 : 0).replace(".", ",")} ${units[i]}`;
}

// ----- Toasts, fenêtres -----------------------------------------------------

export function toast(text, ms = 2600) {
  const host = document.getElementById("toasts") || document.body.appendChild(h("div", { id: "toasts" }));
  const t = h("div", { class: "toast", role: "status" }, text);
  host.append(t);
  requestAnimationFrame(() => t.classList.add("show"));
  setTimeout(() => {
    t.classList.remove("show");
    setTimeout(() => t.remove(), 300);
  }, ms);
}

// Une fenêtre modale ; renvoie { el, close }. `onClose` est appelé une fois.
export function modal(title, body, { actions = [], wide = false, onClose, cls = "" } = {}) {
  const prev = document.activeElement;
  const backdrop = h("div", { class: "modal-backdrop" });
  const close = () => {
    backdrop.classList.remove("show");
    setTimeout(() => backdrop.remove(), 180);
    document.removeEventListener("keydown", onKey);
    onClose?.();
    prev?.focus?.();
  };
  const onKey = (e) => {
    if (e.key === "Escape") close();
  };
  document.addEventListener("keydown", onKey);
  const box = h(
    "div",
    { class: `modal ${wide ? "wide" : ""} ${cls}`.trim(), role: "dialog", "aria-modal": "true", "aria-label": title || "Fenêtre" },
    title ? h("header", { class: "modal-head" }, h("h2", {}, title), iconButton("close", "Fermer", close)) : null,
    h("div", { class: "modal-body" }, body),
    actions.length
      ? h(
          "footer",
          { class: "modal-actions" },
          actions.map((a) =>
            h("button", { class: `btn ${a.cls || ""}`.trim(), type: "button", onclick: () => a.onclick(close) }, a.label),
          ),
        )
      : null,
  );
  backdrop.append(box);
  backdrop.addEventListener("mousedown", (e) => {
    if (e.target === backdrop) close();
  });
  document.body.append(backdrop);
  requestAnimationFrame(() => backdrop.classList.add("show"));
  setTimeout(() => box.querySelector("input,textarea,button.primary")?.focus(), 50);
  return { el: box, close };
}

export function confirmBox(title, text, okLabel = "OK", danger = false) {
  return new Promise((resolve) => {
    let done = false;
    const m = modal(title, h("p", {}, text), {
      onClose: () => !done && resolve(false),
      actions: [
        { label: "Annuler", cls: "ghost", onclick: (close) => close() },
        {
          label: okLabel,
          cls: danger ? "danger" : "primary",
          onclick: (close) => {
            done = true;
            resolve(true);
            close();
          },
        },
      ],
    });
    return m;
  });
}

export function promptBox(title, { label = "", value = "", placeholder = "", type = "text", okLabel = "OK", maxlength = 200 } = {}) {
  return new Promise((resolve) => {
    let done = false;
    const input = h("input", { class: "field", type, value, placeholder, maxlength: String(maxlength), "aria-label": label || title });
    const submit = (close) => {
      done = true;
      resolve(input.value);
      close();
    };
    const m = modal(title, h("div", { class: "stack" }, label ? h("label", { class: "muted" }, label) : null, input), {
      onClose: () => !done && resolve(null),
      actions: [
        { label: "Annuler", cls: "ghost", onclick: (close) => close() },
        { label: okLabel, cls: "primary", onclick: submit },
      ],
    });
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter") submit(m.close);
    });
  });
}

// Un menu contextuel près d'un point ou d'un élément.
export function menu(items, at) {
  document.querySelector(".ctx-menu")?.remove();
  const el = h(
    "div",
    { class: "ctx-menu", role: "menu" },
    items.filter(Boolean).map((it) =>
      h(
        "button",
        {
          type: "button",
          role: "menuitem",
          class: it.danger ? "danger" : "",
          onclick: () => {
            el.remove();
            it.onclick();
          },
        },
        it.icon ? icon(it.icon) : null,
        h("span", {}, it.label),
      ),
    ),
  );
  document.body.append(el);
  const r = at instanceof Element ? at.getBoundingClientRect() : { left: at.x, right: at.x, top: at.y, bottom: at.y };
  const w = el.offsetWidth;
  const hgt = el.offsetHeight;
  let x = Math.min(r.right - w, innerWidth - w - 8);
  if (x < 8) x = Math.min(r.left, innerWidth - w - 8);
  let y = r.bottom + 4;
  if (y + hgt > innerHeight - 8) y = Math.max(8, r.top - hgt - 4);
  el.style.left = `${Math.max(8, x)}px`;
  el.style.top = `${y}px`;
  const away = (e) => {
    if (!el.contains(e.target)) {
      el.remove();
      document.removeEventListener("pointerdown", away, true);
    }
  };
  setTimeout(() => document.addEventListener("pointerdown", away, true));
  return el;
}

// Le texte d'un message : liens cliquables, *gras*, _italique_, ~barré~, `code`.
export function richText(text) {
  const frag = document.createDocumentFragment();
  const re = /(https?:\/\/[^\s<]+[^\s<.,;:!?)\]'"])|(\*[^*\n]+\*)|(_[^_\n]+_)|(~[^~\n]+~)|(`[^`\n]+`)/g;
  let last = 0;
  let m;
  while ((m = re.exec(text))) {
    if (m.index > last) frag.append(text.slice(last, m.index));
    const s = m[0];
    if (m[1]) frag.append(h("a", { href: s, target: "_blank", rel: "noopener noreferrer" }, s));
    else if (m[2]) frag.append(h("strong", {}, s.slice(1, -1)));
    else if (m[3]) frag.append(h("em", {}, s.slice(1, -1)));
    else if (m[4]) frag.append(h("s", {}, s.slice(1, -1)));
    else frag.append(h("code", {}, s.slice(1, -1)));
    last = m.index + s.length;
  }
  if (last < text.length) frag.append(text.slice(last));
  return frag;
}

// Un message fait seulement d'emojis (1 à 3) s'affiche en grand.
export function onlyEmoji(text) {
  const t = (text || "").trim();
  if (!t || t.length > 24) return false;
  return /^(\p{Extended_Pictographic}|\p{Emoji_Component}|‍|️|\s)+$/u.test(t) && !/^[\d#*\s]+$/.test(t)
    ? [...new Intl.Segmenter("fr", { granularity: "grapheme" }).segment(t)].filter((s) => s.segment.trim()).length <= 3
    : false;
}

export function download(name, blobOrUrl) {
  const url = typeof blobOrUrl === "string" ? blobOrUrl : URL.createObjectURL(blobOrUrl);
  const a = h("a", { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  if (typeof blobOrUrl !== "string") setTimeout(() => URL.revokeObjectURL(url), 5000);
}
