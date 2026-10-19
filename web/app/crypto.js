// Les clés et le chiffrement, avec WebCrypto.
//
// - L'identité : une clé ECDSA P-256 (signer le défi du serveur) et une clé
//   ECDH P-256 (chiffrer), tirées sur l'appareil.
// - Le code : 50 bits de SHA-256(signature ‖ chiffrement) en 10 caractères,
//   exactement comme `code::derive` côté serveur.
// - Une conversation à deux : ECDH → HKDF-SHA256 → AES-256-GCM, avec les deux
//   codes en données associées (un message ne peut pas être renvoyé à
//   quelqu'un d'autre ni attribué à un autre).

const subtle = crypto.subtle;
const enc = new TextEncoder();
const dec = new TextDecoder();
export const ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

export function b64(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode(...u8.subarray(i, i + 0x8000));
  return btoa(s);
}

export function unb64(text) {
  const s = atob(text);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

export function randomId(bytes = 12) {
  return [...crypto.getRandomValues(new Uint8Array(bytes))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export async function codeOf(signRaw, dhRaw) {
  const both = new Uint8Array(signRaw.length + dhRaw.length);
  both.set(signRaw, 0);
  both.set(dhRaw, signRaw.length);
  const h = new Uint8Array(await subtle.digest("SHA-256", both));
  let out = "";
  for (let i = 0; i < 10; i++) {
    let v = 0;
    for (let b = 0; b < 5; b++) {
      const bit = i * 5 + b;
      v = (v << 1) | ((h[bit >> 3] >> (7 - (bit & 7))) & 1);
    }
    out += ALPHABET[v];
  }
  return out;
}

// `K7Q2MX9PRT` → `K7Q2M-X9PRT`.
export function pretty(code) {
  return code ? `${code.slice(0, 5)}-${code.slice(5)}` : "";
}

// Ce que l'on tape (minuscules, tirets, espaces) → un code, ou null.
export function normalize(text) {
  const code = String(text || "")
    .toUpperCase()
    .replace(/[\s-]/g, "");
  return code.length === 10 && [...code].every((c) => ALPHABET.includes(c)) ? code : null;
}

const SIGN = { name: "ECDSA", namedCurve: "P-256" };
const DH = { name: "ECDH", namedCurve: "P-256" };

// Une nouvelle identité. Les clés privées sont exportables : c'est ce qui
// permet la sauvegarde chiffrée et le passage à un autre appareil.
export async function createIdentity() {
  const sign = await subtle.generateKey(SIGN, true, ["sign", "verify"]);
  const dh = await subtle.generateKey(DH, true, ["deriveBits"]);
  const signRaw = new Uint8Array(await subtle.exportKey("raw", sign.publicKey));
  const dhRaw = new Uint8Array(await subtle.exportKey("raw", dh.publicKey));
  return {
    code: await codeOf(signRaw, dhRaw),
    sign: b64(signRaw),
    dh: b64(dhRaw),
    signPriv: await subtle.exportKey("jwk", sign.privateKey),
    dhPriv: await subtle.exportKey("jwk", dh.privateKey),
    createdAt: Date.now(),
  };
}

// Les clés utilisables d'une identité gardée.
export async function loadIdentity(id) {
  return {
    ...id,
    signKey: await subtle.importKey("jwk", id.signPriv, SIGN, false, ["sign"]),
    dhKey: await subtle.importKey("jwk", id.dhPriv, DH, false, ["deriveBits"]),
  };
}

export async function signChallenge(identity, nonce) {
  const sig = await subtle.sign({ name: "ECDSA", hash: "SHA-256" }, identity.signKey, enc.encode(`talka-auth:${nonce}`));
  return b64(sig);
}

// Les clés publiques d'un contact correspondent-elles à son code ?
export async function verifyKeys(code, sign, dh) {
  try {
    return (await codeOf(unb64(sign), unb64(dh))) === code;
  } catch {
    return false;
  }
}

// L'empreinte de sécurité d'une conversation, à comparer de vive voix : la
// même des deux côtés.
export async function safetyNumber(codeA, codeB) {
  const [a, b] = [codeA, codeB].sort();
  const h = new Uint8Array(await subtle.digest("SHA-256", enc.encode(`talka-safety:${a}:${b}`)));
  const groups = [];
  for (let i = 0; i < 12; i++) groups.push(String(((h[i * 2] << 8) | h[i * 2 + 1]) % 100000).padStart(5, "0"));
  return groups.join(" ");
}

const pairKeys = new Map();

async function pairKey(identity, peerCode, peerDh) {
  const cacheKey = `${peerCode}:${peerDh}`;
  if (pairKeys.has(cacheKey)) return pairKeys.get(cacheKey);
  const promise = (async () => {
    const pub = await subtle.importKey("raw", unb64(peerDh), DH, false, []);
    const secret = await subtle.deriveBits({ name: "ECDH", public: pub }, identity.dhKey, 256);
    const hkdf = await subtle.importKey("raw", secret, "HKDF", false, ["deriveKey"]);
    const [a, b] = [identity.code, peerCode].sort();
    return subtle.deriveKey(
      { name: "HKDF", hash: "SHA-256", salt: enc.encode(`talka:${a}:${b}`), info: enc.encode("talka-message-v1") },
      hkdf,
      { name: "AES-GCM", length: 256 },
      false,
      ["encrypt", "decrypt"],
    );
  })();
  pairKeys.set(cacheKey, promise);
  return promise;
}

// Chiffre un objet pour un contact : base64(iv ‖ chiffré).
export async function seal(identity, peer, payload) {
  const key = await pairKey(identity, peer.code, peer.dh);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ad = enc.encode(`${identity.code}>${peer.code}`);
  const ct = new Uint8Array(
    await subtle.encrypt({ name: "AES-GCM", iv, additionalData: ad }, key, enc.encode(JSON.stringify(payload))),
  );
  const out = new Uint8Array(12 + ct.length);
  out.set(iv, 0);
  out.set(ct, 12);
  return b64(out);
}

export async function open(identity, peer, body) {
  const key = await pairKey(identity, peer.code, peer.dh);
  const bytes = unb64(body);
  const ad = enc.encode(`${peer.code}>${identity.code}`);
  const pt = await subtle.decrypt({ name: "AES-GCM", iv: bytes.subarray(0, 12), additionalData: ad }, key, bytes.subarray(12));
  return JSON.parse(dec.decode(pt));
}

// Le code de verrouillage : PBKDF2, jamais gardé en clair.
export async function hashPin(pin, salt = b64(crypto.getRandomValues(new Uint8Array(16)))) {
  const base = await subtle.importKey("raw", enc.encode(pin), "PBKDF2", false, ["deriveBits"]);
  const bits = await subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: unb64(salt), iterations: 200000 }, base, 256);
  return { salt, hash: b64(bits) };
}

export async function checkPin(pin, stored) {
  const { hash } = await hashPin(pin, stored.salt);
  return hash === stored.hash;
}

// La sauvegarde : un mot de passe → PBKDF2 → AES-256-GCM.
async function passwordKey(password, salt) {
  const base = await subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveKey"]);
  return subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: 310000 },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

export async function encryptBackup(password, data) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await passwordKey(password, salt);
  const ct = await subtle.encrypt({ name: "AES-GCM", iv }, key, enc.encode(JSON.stringify(data)));
  return { talka: 1, salt: b64(salt), iv: b64(iv), data: b64(ct) };
}

export async function decryptBackup(password, file) {
  if (!file || file.talka !== 1) throw new Error("Ce fichier n'est pas une sauvegarde Talka.");
  const key = await passwordKey(password, unb64(file.salt));
  try {
    const pt = await subtle.decrypt({ name: "AES-GCM", iv: unb64(file.iv) }, key, unb64(file.data));
    return JSON.parse(dec.decode(pt));
  } catch {
    throw new Error("Mot de passe incorrect.");
  }
}
