// Les sons de Talka, synthétisés avec WebAudio : aucun fichier à charger.

let ctx = null;

function audio() {
  if (!ctx) {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
  }
  if (ctx.state === "suspended") ctx.resume().catch(() => {});
  return ctx;
}

function tone(freq, start, dur, gain = 0.12, type = "sine") {
  const a = audio();
  if (!a) return;
  const o = a.createOscillator();
  const g = a.createGain();
  o.type = type;
  o.frequency.value = freq;
  const t = a.currentTime + start;
  g.gain.setValueAtTime(0, t);
  g.gain.linearRampToValueAtTime(gain, t + 0.01);
  g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
  o.connect(g).connect(a.destination);
  o.start(t);
  o.stop(t + dur + 0.02);
}

export function incoming() {
  tone(880, 0, 0.12);
  tone(1320, 0.1, 0.18);
}

export function sent() {
  tone(660, 0, 0.08, 0.05);
}

let ringTimer = null;

// La sonnerie (appel entrant) ou la tonalité (appel sortant), en boucle.
export function ring(outgoing = false) {
  stopRing();
  const once = () => {
    if (outgoing) {
      tone(425, 0, 1.2, 0.06);
    } else {
      for (let i = 0; i < 6; i++) tone(i % 2 ? 784 : 988, i * 0.13, 0.12, 0.1, "triangle");
    }
  };
  once();
  ringTimer = setInterval(once, outgoing ? 3000 : 2200);
}

export function stopRing() {
  clearInterval(ringTimer);
  ringTimer = null;
}

export function hangup() {
  tone(480, 0, 0.2, 0.08);
  tone(380, 0.22, 0.3, 0.08);
}

// Les navigateurs n'autorisent le son qu'après un geste : on le prépare au premier.
export function unlock() {
  const go = () => {
    audio();
    removeEventListener("pointerdown", go);
    removeEventListener("keydown", go);
  };
  addEventListener("pointerdown", go);
  addEventListener("keydown", go);
}
