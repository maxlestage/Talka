// Photos, vocaux et fichiers : préparés dans le navigateur avant d'être
// chiffrés. Les photos sont réduites (1600 px, JPEG), les vocaux enregistrés
// avec MediaRecorder.

export const MAX_FILE = 4 * 1024 * 1024;

export function readAsDataURL(blob) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = () => reject(r.error);
    r.readAsDataURL(blob);
  });
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("Image illisible"));
    img.src = src;
  });
}

// Une photo réduite : { data, w, h }.
export async function compressImage(file, max = 1600, quality = 0.82) {
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    const scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
    const w = Math.max(1, Math.round(img.naturalWidth * scale));
    const hgt = Math.max(1, Math.round(img.naturalHeight * scale));
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = hgt;
    const c = canvas.getContext("2d");
    c.fillStyle = "#fff";
    c.fillRect(0, 0, w, hgt);
    c.drawImage(img, 0, 0, w, hgt);
    return { data: canvas.toDataURL("image/jpeg", quality), w, h: hgt };
  } finally {
    URL.revokeObjectURL(url);
  }
}

// Une petite photo carrée (profil, groupe).
export async function avatarFrom(file, size = 160) {
  const url = URL.createObjectURL(file);
  try {
    const img = await loadImage(url);
    const s = Math.min(img.naturalWidth, img.naturalHeight);
    const canvas = document.createElement("canvas");
    canvas.width = size;
    canvas.height = size;
    canvas
      .getContext("2d")
      .drawImage(img, (img.naturalWidth - s) / 2, (img.naturalHeight - s) / 2, s, s, 0, 0, size, size);
    return canvas.toDataURL("image/jpeg", 0.8);
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function pickFile(accept, { capture } = {}) {
  return new Promise((resolve) => {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = accept;
    if (capture) input.capture = capture;
    input.onchange = () => resolve(input.files?.[0] || null);
    input.click();
  });
}

// L'enregistreur de vocaux.
export class Recorder {
  constructor() {
    this.chunks = [];
    this.started = 0;
    this.rec = null;
    this.stream = null;
  }

  static supported() {
    return !!(navigator.mediaDevices?.getUserMedia && window.MediaRecorder);
  }

  async start() {
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } });
    const types = ["audio/webm;codecs=opus", "audio/mp4", "audio/ogg;codecs=opus", "audio/webm"];
    const mimeType = types.find((t) => MediaRecorder.isTypeSupported?.(t));
    this.rec = new MediaRecorder(this.stream, mimeType ? { mimeType, audioBitsPerSecond: 32000 } : undefined);
    this.chunks = [];
    this.rec.ondataavailable = (e) => e.data.size && this.chunks.push(e.data);
    this.rec.start(250);
    this.started = performance.now();
  }

  elapsed() {
    return (performance.now() - this.started) / 1000;
  }

  // Arrête l'enregistrement : { data, dur, mime } (ou null si annulé).
  stop(cancel = false) {
    return new Promise((resolve) => {
      if (!this.rec) return resolve(null);
      const dur = this.elapsed();
      this.rec.onstop = async () => {
        this.stream?.getTracks().forEach((t) => t.stop());
        if (cancel || dur < 0.6) return resolve(null);
        const blob = new Blob(this.chunks, { type: this.rec.mimeType || "audio/webm" });
        resolve({ data: await readAsDataURL(blob), dur, mime: blob.type });
      };
      this.rec.stop();
    });
  }
}

// Les octets d'une adresse `data:` (sans `fetch`, que la CSP réserve au relais).
export function dataUrlBytes(dataUrl) {
  const b = atob(dataUrl.slice(dataUrl.indexOf(",") + 1));
  const out = new Uint8Array(b.length);
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i);
  return out;
}

export function dataUrlBlob(dataUrl) {
  const mime = dataUrl.slice(5, dataUrl.indexOf(";")) || "application/octet-stream";
  return new Blob([dataUrlBytes(dataUrl)], { type: mime });
}

// La forme d'onde (barres) d'un vocal, calculée une fois.
const waves = new Map();
export async function waveform(dataUrl, bars = 40) {
  if (waves.has(dataUrl)) return waves.get(dataUrl);
  try {
    const AC = window.AudioContext || window.webkitAudioContext;
    const ctx = new AC();
    const buf = dataUrlBytes(dataUrl).buffer;
    const audio = await ctx.decodeAudioData(buf);
    ctx.close();
    const ch = audio.getChannelData(0);
    const step = Math.floor(ch.length / bars) || 1;
    const out = [];
    for (let i = 0; i < bars; i++) {
      let sum = 0;
      for (let j = 0; j < step; j += 16) sum += Math.abs(ch[i * step + j] || 0);
      out.push(sum / (step / 16));
    }
    const max = Math.max(...out, 0.001);
    const norm = out.map((v) => Math.max(0.12, v / max));
    waves.set(dataUrl, norm);
    return norm;
  } catch {
    return Array.from({ length: bars }, (_, i) => 0.3 + 0.5 * Math.abs(Math.sin(i * 1.7)));
  }
}
