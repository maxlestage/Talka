// La connexion au relais : une WebSocket qui se présente (signature du
// défi), se reconnecte toute seule, et transporte les enveloppes chiffrées.

import { signChallenge } from "./crypto.js";

export class Net extends EventTarget {
  constructor(identity) {
    super();
    this.identity = identity;
    this.hidden = false;
    this.state = "offline";
    this.ws = null;
    this.retry = 0;
    this.timer = null;
    this.pending = new Map(); // id → { resolve, reject, timer }
    this.lookups = new Map(); // code → [resolve]
    this.watched = new Set();
    this.skew = 0; // heure du serveur - heure locale
    this.stopped = false;
    addEventListener("online", () => this.connectSoon(0));
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible" && this.state === "offline") this.connectSoon(0);
    });
  }

  now() {
    return Date.now() + this.skew;
  }

  setState(state) {
    if (this.state === state) return;
    this.state = state;
    this.dispatchEvent(new CustomEvent("state", { detail: state }));
  }

  connectSoon(delay) {
    if (this.stopped) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.connect(), delay);
  }

  connect() {
    if (this.stopped || (this.ws && this.ws.readyState <= 1)) return;
    const scheme = location.protocol === "https:" ? "wss" : "ws";
    let ws;
    try {
      ws = new WebSocket(`${scheme}://${location.host}/ws`);
    } catch {
      this.scheduleRetry();
      return;
    }
    this.ws = ws;
    this.setState("connecting");
    ws.onmessage = (event) => this.onMessage(event.data);
    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.ws = null;
      this.setState("offline");
      for (const [id, p] of this.pending) {
        clearTimeout(p.timer);
        p.reject(new Error("offline"));
        this.pending.delete(id);
      }
      this.scheduleRetry();
    };
    ws.onerror = () => {};
  }

  scheduleRetry() {
    const delay = Math.min(30000, 1000 * 2 ** this.retry) * (0.7 + Math.random() * 0.6);
    this.retry = Math.min(this.retry + 1, 5);
    this.connectSoon(delay);
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.ws?.close();
  }

  raw(obj) {
    if (this.ws?.readyState === 1 && this.state === "online") {
      this.ws.send(JSON.stringify(obj));
      return true;
    }
    return false;
  }

  async onMessage(data) {
    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }
    switch (msg.t) {
      case "challenge": {
        if (msg.time) this.skew = msg.time - Date.now();
        const sig = await signChallenge(this.identity, msg.nonce);
        this.ws?.send(
          JSON.stringify({ t: "hello", sign: this.identity.sign, dh: this.identity.dh, sig, hidden: this.hidden }),
        );
        break;
      }
      case "ready":
        this.retry = 0;
        this.setState("online");
        if (this.watched.size) this.raw({ t: "watch", codes: [...this.watched] });
        break;
      case "ack":
      case "error": {
        const p = this.pending.get(msg.id);
        if (!p) break;
        this.pending.delete(msg.id);
        clearTimeout(p.timer);
        if (msg.t === "ack") p.resolve(msg);
        else p.reject(Object.assign(new Error(msg.error), { code: msg.error }));
        break;
      }
      case "msg":
        this.dispatchEvent(new CustomEvent("msg", { detail: msg }));
        break;
      case "keys": {
        const waiting = this.lookups.get(msg.code) || [];
        this.lookups.delete(msg.code);
        for (const resolve of waiting) resolve(msg.unknown ? null : msg);
        break;
      }
      case "presence":
        this.dispatchEvent(new CustomEvent("presence", { detail: msg }));
        break;
    }
  }

  // Envoie une enveloppe ; la promesse est tenue quand le serveur l'a reçue.
  send(id, to, body, live = false) {
    return new Promise((resolve, reject) => {
      if (!this.raw({ t: "send", id, to, body, live })) {
        reject(new Error("offline"));
        return;
      }
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error("timeout"));
      }, 30000);
      this.pending.set(id, { resolve, reject, timer });
    });
  }

  got(numbers) {
    if (numbers.length) this.raw({ t: "got", n: numbers });
  }

  // Les clés publiques d'un code (null si le serveur ne le connaît pas).
  lookup(code) {
    return new Promise((resolve, reject) => {
      if (!this.raw({ t: "lookup", code })) {
        reject(new Error("offline"));
        return;
      }
      const list = this.lookups.get(code) || [];
      list.push(resolve);
      this.lookups.set(code, list);
      setTimeout(() => reject(new Error("timeout")), 15000);
    });
  }

  watch(codes) {
    const fresh = codes.filter((c) => !this.watched.has(c));
    for (const c of fresh) this.watched.add(c);
    if (fresh.length) this.raw({ t: "watch", codes: fresh });
  }

  // Masquer « en ligne » : se reconnecter avec le nouveau réglage.
  setHidden(hidden) {
    if (this.hidden === hidden) return;
    this.hidden = hidden;
    this.ws?.close();
  }
}
