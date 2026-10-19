// Les appels audio et vidéo : WebRTC, d'appareil à appareil. Le relais ne
// transporte que la mise en relation (offre, réponse, candidats ICE), chiffrée
// comme un message, et seulement si l'autre est en ligne.

import * as core from "./core.js";
import * as sounds from "./sounds.js";
import { h, icon, toast, time } from "./dom.js";
import { randomId } from "./crypto.js";

let ice = null;
let current = null; // l'appel en cours

async function iceServers() {
  if (ice) return ice;
  try {
    ice = (await (await fetch("/api/ice")).json()).iceServers;
  } catch {
    ice = [{ urls: "stun:stun.l.google.com:19302" }];
  }
  return ice;
}

export function inCall() {
  return !!current;
}

export async function startCall(peer, video) {
  if (current) return toast("Un appel est déjà en cours.");
  if (core.state.net?.state !== "online") return toast("Pas de connexion au réseau.");
  if (!navigator.mediaDevices?.getUserMedia) return toast("Votre navigateur ne permet pas les appels.");
  const call = newCall({ id: randomId(10), peer, video, dir: "out" });
  try {
    await getMedia(call);
  } catch {
    end(call, "Micro ou caméra refusés.", false);
    return;
  }
  await createPeer(call);
  const offer = await call.pc.createOffer();
  await call.pc.setLocalDescription(offer);
  core.sendCallSignal(peer, { op: "offer", callId: call.id, video, sdp: offer.sdp });
  setStatus(call, "Appel…");
  sounds.ring(true);
  call.noAnswer = setTimeout(() => {
    core.sendCallSignal(peer, { op: "end", callId: call.id, reason: "timeout" });
    end(call, "Pas de réponse", false);
  }, 45000);
}

function newCall(base) {
  const call = { ...base, at: Date.now(), connectedAt: 0, pending: [], muted: false, camOff: false, facing: "user" };
  current = call;
  call.ui = buildUi(call);
  document.body.append(call.ui.root);
  return call;
}

async function getMedia(call) {
  call.local = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true },
    video: call.video ? { facingMode: call.facing, width: { ideal: 1280 }, height: { ideal: 720 } } : false,
  });
  call.ui.local.srcObject = call.local;
}

async function createPeer(call) {
  const pc = new RTCPeerConnection({ iceServers: await iceServers() });
  call.pc = pc;
  for (const track of call.local.getTracks()) pc.addTrack(track, call.local);
  pc.onicecandidate = (e) => {
    if (e.candidate) core.sendCallSignal(call.peer, { op: "ice", callId: call.id, cand: e.candidate.toJSON() });
  };
  pc.ontrack = (e) => {
    call.ui.remote.srcObject = e.streams[0];
    call.ui.remoteAudio.srcObject = e.streams[0];
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === "connected" && !call.connectedAt) {
      call.connectedAt = Date.now();
      sounds.stopRing();
      call.ui.root.classList.add("live");
      tick(call);
    } else if (pc.connectionState === "failed") {
      end(call, "La connexion a échoué", true);
    } else if (pc.connectionState === "disconnected") {
      setStatus(call, "Reconnexion…");
    }
  };
}

function tick(call) {
  if (current !== call) return;
  const s = Math.floor((Date.now() - call.connectedAt) / 1000);
  setStatus(call, core.fmtDur(s));
  call.ticker = setTimeout(() => tick(call), 1000);
}

function setStatus(call, text) {
  call.ui.status.textContent = text;
}

async function flushCandidates(call) {
  for (const c of call.pending) {
    try {
      await call.pc.addIceCandidate(c);
    } catch {
      /* ignoré */
    }
  }
  call.pending = [];
}

// Les signaux reçus (core émet "call-signal").
export async function onSignal({ from, p, live }) {
  if (p.op === "offer") {
    if (!live) return; // une vieille offre : l'appel est passé
    if (current || core.state.contacts.get(from)?.blocked) {
      core.sendCallSignal(from, { op: "busy", callId: p.callId });
      await core.logCall({ id: p.callId, peer: from, video: !!p.video, dir: "in", at: Date.now(), dur: 0, missed: true });
      return;
    }
    const call = newCall({ id: p.callId, peer: from, video: !!p.video, dir: "in", offer: p.sdp });
    call.ui.root.classList.add("ringing");
    setStatus(call, p.video ? "Appel vidéo entrant…" : "Appel audio entrant…");
    sounds.ring(false);
    notify(from, p.video);
    call.noAnswer = setTimeout(() => end(call, "Appel manqué", false, true), 45000);
    return;
  }
  const call = current;
  if (!call || call.id !== p.callId || call.peer !== from) return;
  switch (p.op) {
    case "answer":
      clearTimeout(call.noAnswer);
      setStatus(call, "Connexion…");
      await call.pc.setRemoteDescription({ type: "answer", sdp: p.sdp });
      await flushCandidates(call);
      break;
    case "ice":
      if (call.pc?.remoteDescription) {
        try {
          await call.pc.addIceCandidate(p.cand);
        } catch {
          /* ignoré */
        }
      } else call.pending.push(p.cand);
      break;
    case "reject":
      end(call, "Appel refusé", false);
      break;
    case "busy":
      end(call, "Occupé", false);
      break;
    case "end":
      end(call, call.connectedAt ? "Appel terminé" : call.dir === "in" ? "Appel manqué" : "Appel terminé", false, call.dir === "in" && !call.connectedAt);
      break;
  }
}

async function accept(call) {
  clearTimeout(call.noAnswer);
  sounds.stopRing();
  call.ui.root.classList.remove("ringing");
  setStatus(call, "Connexion…");
  try {
    await getMedia(call);
  } catch {
    core.sendCallSignal(call.peer, { op: "reject", callId: call.id });
    end(call, "Micro ou caméra refusés.", false);
    return;
  }
  await createPeer(call);
  await call.pc.setRemoteDescription({ type: "offer", sdp: call.offer });
  await flushCandidates(call);
  const answer = await call.pc.createAnswer();
  await call.pc.setLocalDescription(answer);
  core.sendCallSignal(call.peer, { op: "answer", callId: call.id, sdp: answer.sdp });
}

function hangup(call) {
  core.sendCallSignal(call.peer, { op: call.dir === "in" && !call.connectedAt && !call.pc ? "reject" : "end", callId: call.id });
  end(call, "Appel terminé", false);
}

async function end(call, reason, _failed, missed = false) {
  if (call.ended) return;
  call.ended = true;
  clearTimeout(call.noAnswer);
  clearTimeout(call.ticker);
  sounds.stopRing();
  if (call.connectedAt || call.pc) sounds.hangup();
  call.local?.getTracks().forEach((t) => t.stop());
  try {
    call.pc?.close();
  } catch {
    /* déjà fermé */
  }
  setStatus(call, reason);
  call.ui.root.classList.add("ended");
  const dur = call.connectedAt ? Math.round((Date.now() - call.connectedAt) / 1000) : 0;
  await core.logCall({ id: call.id, peer: call.peer, video: call.video, dir: call.dir, at: call.at, dur, missed: missed || (call.dir === "in" && !dur) });
  setTimeout(() => {
    call.ui.root.remove();
    if (current === call) current = null;
  }, 1400);
}

function notify(from, video) {
  if (document.visibilityState === "visible" || !core.state.settings.notify || Notification?.permission !== "granted") return;
  try {
    new Notification(core.displayName(from), { body: video ? "📹 Appel vidéo entrant" : "📞 Appel entrant", icon: "/icons/icon-192.png", tag: "talka-call" });
  } catch {
    /* rien */
  }
}

function buildUi(call) {
  const name = core.displayName(call.peer);
  const avatar = core.avatarOf(call.peer);
  const remote = h("video", { class: "call-remote", autoplay: true, playsinline: true });
  const remoteAudio = h("audio", { autoplay: true });
  const local = h("video", { class: "call-local", autoplay: true, playsinline: true, muted: true });
  local.muted = true;
  const status = h("div", { class: "call-status" }, "");
  const btn = (name, label, onclick, cls = "") => h("button", { type: "button", class: `call-btn ${cls}`, "aria-label": label, title: label, onclick }, icon(name));
  const mute = btn("mic", "Couper le micro", () => {
    call.muted = !call.muted;
    call.local?.getAudioTracks().forEach((t) => (t.enabled = !call.muted));
    mute.replaceChildren(icon(call.muted ? "micoff" : "mic"));
    mute.classList.toggle("off", call.muted);
  });
  const cam = btn("video", "Caméra", () => {
    call.camOff = !call.camOff;
    call.local?.getVideoTracks().forEach((t) => (t.enabled = !call.camOff));
    cam.replaceChildren(icon(call.camOff ? "videooff" : "video"));
    cam.classList.toggle("off", call.camOff);
  });
  const flip = btn("switchcam", "Changer de caméra", async () => {
    if (!call.local || !call.pc) return;
    call.facing = call.facing === "user" ? "environment" : "user";
    try {
      const s = await navigator.mediaDevices.getUserMedia({ video: { facingMode: call.facing } });
      const track = s.getVideoTracks()[0];
      const sender = call.pc.getSenders().find((x) => x.track?.kind === "video");
      await sender?.replaceTrack(track);
      call.local.getVideoTracks().forEach((t) => {
        t.stop();
        call.local.removeTrack(t);
      });
      call.local.addTrack(track);
      local.srcObject = call.local;
    } catch {
      toast("Impossible de changer de caméra.");
    }
  });
  const root = h(
    "div",
    { class: `call ${call.video ? "video" : "audio"}`, role: "dialog", "aria-label": `Appel avec ${name}` },
    remote,
    remoteAudio,
    call.video ? local : null,
    h(
      "div",
      { class: "call-info" },
      h("div", { class: "call-avatar" }, avatar ? h("img", { src: avatar, alt: "" }) : name.replace("~", "").slice(0, 1).toUpperCase()),
      h("div", { class: "call-name" }, name),
      h("div", { class: "call-e2e" }, icon("lock"), " Chiffré de bout en bout"),
      status,
    ),
    h(
      "div",
      { class: "call-actions incoming-actions" },
      btn("hangup", "Refuser", () => hangup(call), "red"),
      btn(call.video ? "video" : "phone", "Répondre", () => accept(call), "green"),
    ),
    h(
      "div",
      { class: "call-actions live-actions" },
      call.video ? flip : null,
      call.video ? cam : null,
      mute,
      btn("hangup", "Raccrocher", () => hangup(call), "red"),
    ),
  );
  return { root, remote, remoteAudio, local, status };
}

export function describe(entry) {
  const kind = entry.video ? "vidéo" : "audio";
  if (entry.missed) return `Appel ${kind} manqué · ${time(entry.at)}`;
  return `Appel ${kind} ${entry.dir === "in" ? "reçu" : "émis"}${entry.dur ? ` · ${core.fmtDur(entry.dur)}` : ""}`;
}
