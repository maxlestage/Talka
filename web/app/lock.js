// Le code de verrouillage : 4 à 6 chiffres demandés à l'ouverture de Talka
// (et au retour, après un délai choisi). Gardé haché (PBKDF2), jamais en clair.
// Après 5 essais manqués, il faut attendre de plus en plus longtemps.

import { h, icon } from "./dom.js";
import * as db from "./db.js";
import { checkPin, hashPin } from "./crypto.js";

export async function hasPin() {
  return !!(await db.kv.get("pin"));
}

export async function setPin(pin) {
  if (!pin) return db.kv.del("pin");
  return db.kv.set("pin", await hashPin(pin));
}

export async function verify(pin) {
  const stored = await db.kv.get("pin");
  return !stored || checkPin(pin, stored);
}

// Un pavé numérique ; `onDone(code)` quand `length` chiffres sont tapés
// (ou à « OK » si `length` vaut 0 : 4 à 6 chiffres).
export function pinPad({ title, subtitle = "", length = 0, onDone }) {
  let value = "";
  const dots = h("div", { class: "pin-dots", "aria-live": "polite" });
  const msg = h("p", { class: "pin-msg" }, subtitle);
  const draw = () => {
    const n = length || Math.max(4, value.length);
    dots.replaceChildren(...Array.from({ length: n }, (_, i) => h("span", { class: i < value.length ? "on" : "" })));
  };
  const press = async (d) => {
    if (d === "del") value = value.slice(0, -1);
    else if (d === "ok") {
      if (value.length >= 4) {
        const v = value;
        value = "";
        draw();
        await onDone(v, api);
      }
      return;
    } else if (value.length < 6) value += d;
    draw();
    if (length && value.length === length) {
      const v = value;
      value = "";
      setTimeout(async () => {
        draw();
        await onDone(v, api);
      }, 120);
    }
  };
  const keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", length ? "" : "ok", "0", "del"];
  const pad = h(
    "div",
    { class: "pin-pad" },
    keys.map((k) =>
      k
        ? h(
            "button",
            { type: "button", class: `pin-key ${k === "ok" || k === "del" ? "fn" : ""}`, onclick: () => press(k), "aria-label": k === "del" ? "Effacer" : k },
            k === "del" ? "⌫" : k === "ok" ? "OK" : k,
          )
        : h("span"),
    ),
  );
  const el = h("div", { class: "pin" }, h("div", { class: "pin-lock" }, icon("lock")), h("h2", {}, title), msg, dots, pad);
  const onKey = (e) => {
    if (/^[0-9]$/.test(e.key)) press(e.key);
    else if (e.key === "Backspace") press("del");
    else if (e.key === "Enter") press("ok");
  };
  document.addEventListener("keydown", onKey);
  const api = {
    el,
    error(text) {
      msg.textContent = text;
      msg.classList.add("err");
      el.classList.remove("shake");
      void el.offsetWidth;
      el.classList.add("shake");
    },
    info(text) {
      msg.textContent = text;
      msg.classList.remove("err");
    },
    destroy() {
      document.removeEventListener("keydown", onKey);
      el.remove();
    },
  };
  draw();
  return api;
}

// L'écran verrouillé : se résout quand le bon code est tapé.
export function lockScreen() {
  return new Promise((resolve) => {
    const host = h("div", { class: "lock-screen" });
    document.body.append(host);
    let fails = Number(sessionStorage.getItem("talka.fails") || 0);
    let until = 0;
    const pad = pinPad({
      title: "Talka est verrouillé",
      subtitle: "Entrez votre code",
      onDone: async (pin, api) => {
        if (Date.now() < until) {
          api.error(`Patientez ${Math.ceil((until - Date.now()) / 1000)} s`);
          return;
        }
        if (await verify(pin)) {
          sessionStorage.removeItem("talka.fails");
          api.destroy();
          host.remove();
          resolve();
        } else {
          fails++;
          sessionStorage.setItem("talka.fails", String(fails));
          if (fails >= 5) until = Date.now() + Math.min(300, 15 * 2 ** (fails - 5)) * 1000;
          api.error(fails >= 5 ? "Code incorrect. Trop d'essais : patientez." : "Code incorrect");
        }
      },
    });
    host.append(pad.el);
  });
}
