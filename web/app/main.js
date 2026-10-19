// Le démarrage de Talka : verrouillage, accueil ou application.

import * as core from "./core.js";
import * as ui from "./ui.js";
import * as lock from "./lock.js";
import * as sounds from "./sounds.js";
import { normalize } from "./crypto.js";
import { h, clear } from "./dom.js";

function fatal(text) {
  clear(document.getElementById("root"), h("div", { class: "welcome" }, h("div", { class: "welcome-card" }, h("img", { src: "/logo.svg", alt: "", width: "72", height: "72" }), h("h1", {}, "Talka ne peut pas démarrer"), h("p", { class: "muted" }, text))));
}

// Le lien d'invitation : `/app#add=K7Q2MX9PRT`.
function pendingInvite() {
  const m = /add=([0-9A-Za-z-]+)/.exec(location.hash);
  if (!m) return null;
  history.replaceState(null, "", location.pathname);
  return normalize(m[1]);
}

// Le verrouillage automatique, quand on quitte Talka.
function autoLock() {
  let hiddenAt = 0;
  document.addEventListener("visibilitychange", async () => {
    if (document.visibilityState === "hidden") {
      hiddenAt = Date.now();
      return;
    }
    if (!hiddenAt || !(await lock.hasPin()) || document.querySelector(".lock-screen")) return;
    if (Date.now() - hiddenAt >= (core.state.settings.lockAfter || 0) * 1000) {
      document.body.classList.add("locked");
      await lock.lockScreen();
      document.body.classList.remove("locked");
    }
  });
}

async function boot() {
  if (!window.indexedDB || !crypto?.subtle) {
    fatal("Ce navigateur ne permet pas à Talka de garder vos messages (IndexedDB) ou de les chiffrer (WebCrypto). Essayez une version récente de Chrome, Firefox, Safari ou Edge, hors navigation privée.");
    return;
  }
  const invite = pendingInvite();
  sounds.unlock();
  try {
    if (!(await core.hasAccount())) {
      await ui.welcome();
      await core.start();
      ui.mount();
      await ui.revealCode();
    } else {
      if (await lock.hasPin()) {
        document.getElementById("root").replaceChildren();
        await lock.lockScreen();
        ui.setLockAvailable(true);
      }
      await core.start();
      ui.mount();
    }
  } catch (e) {
    console.error(e);
    fatal(`Une erreur est survenue : ${e.message || e}`);
    return;
  }
  autoLock();
  if (invite && invite !== core.state.me.code) {
    if (core.state.contacts.get(invite)?.saved) ui.openChat(invite);
    else ui.openAddContact(invite);
  }
}

boot();
