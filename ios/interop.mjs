// Un navigateur (Playwright) et un iPhone (TalkaKit, `swift test`) dans la
// même conversation, sur un vrai serveur Talka :
//
//     TALKA_SERVER=http://127.0.0.1:3000 node interop.mjs
//
// Le navigateur et l'iPhone s'échangent leurs codes par deux fichiers.

import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
let chromium;
try {
  ({ chromium } = require("playwright"));
} catch {
  ({ chromium } = createRequire("/opt/node22/lib/node_modules/")("playwright"));
}

const SERVER = process.env.TALKA_SERVER || "http://127.0.0.1:3000";
const dir = mkdtempSync(join(tmpdir(), "talka-interop-"));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(what, ok, ms = 60000) {
  const end = Date.now() + ms;
  while (!(await ok())) {
    if (Date.now() > end) throw new Error(`trop long : ${what}`);
    await sleep(200);
  }
}

const swift = spawn("swift", ["test", "--filter", "InteropTests"], {
  cwd: new URL(".", import.meta.url).pathname,
  env: { ...process.env, TALKA_SERVER: SERVER, TALKA_INTEROP_DIR: dir },
  stdio: ["ignore", "pipe", "pipe"],
});
let log = "";
swift.stdout.on("data", (d) => (log += d));
swift.stderr.on("data", (d) => (log += d));
const swiftDone = new Promise((resolve) => swift.on("exit", resolve));

const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
const page = await (await browser.newContext({ viewport: { width: 1200, height: 800 } })).newPage();
page.on("pageerror", (e) => console.log("navigateur :", e.message));
await page.goto(`${SERVER}/app`);
await page.fill('input[aria-label="Votre nom"]', "Ordinateur");
await page.click("text=Créer mon code");
await page.waitForSelector(".modal .code-big");
const code = (await page.textContent(".modal .code-big")).replace("-", "");
await page.click("text=C'est parti");
writeFileSync(join(dir, "browser.code"), code);
console.log("• navigateur :", code);

await until("code de l'iPhone", () => existsSync(join(dir, "swift.code")), 300000);
const phone = readFileSync(join(dir, "swift.code"), "utf8").trim();
console.log("• iPhone :", phone);

// Le message de l'iPhone arrive ; le navigateur répond (et lit).
await page.waitForSelector(".chat-item", { timeout: 60000 });
await page.click(".chat-item");
await page.waitForSelector("text=Bonjour du iPhone 📱", { timeout: 60000 });
console.log("• message de l'iPhone reçu et déchiffré par le navigateur");
await page.fill(".conv textarea", "Bonjour de l'ordinateur 💻");
await page.click(".send-btn");
// La réaction de l'iPhone, et les ✓✓ bleus.
await page.waitForSelector(".bubble.out .reactions", { timeout: 60000 });
console.log("• réaction 👍 de l'iPhone reçue");
await until("lu par l'iPhone", async () => (await page.getAttribute(".msg-row.out:last-child .tick", "class"))?.includes("read"));
console.log("• ✓✓ bleus : l'iPhone a lu");
// La fiche de l'iPhone : une session à double cliquet, post-quantique (v3).
await page.click(".conv-title");
await page.waitForSelector("text=Session à double cliquet (post-quantique)", { timeout: 15000 });
console.log("• fiche de l'iPhone : 🔐 session à double cliquet (post-quantique)");
await page.click('.modal button[aria-label="Fermer"]');
// Le groupe créé sur l'iPhone.
await page.click('button[aria-label="Retour"]').catch(() => {});
await page.waitForSelector(".chat-item:has-text('iPhone et navigateur')", { timeout: 60000 });
await page.click(".chat-item:has-text('iPhone et navigateur')");
await page.waitForSelector("text=Dans le groupe");
await page.fill(".conv textarea", "Réponse dans le groupe");
await page.click(".send-btn");
console.log("• groupe créé sur l'iPhone : le navigateur y écrit");

const status = await Promise.race([swiftDone, sleep(120000).then(() => "délai")]);
await browser.close();
const ok = status === 0 && existsSync(join(dir, "swift.done"));
console.log(log.split("\n").filter((l) => /error|Executed|passed|failed/.test(l)).slice(-6).join("\n"));
console.log(ok ? "✓ iPhone et navigateur se comprennent" : "✗ échec");
process.exit(ok ? 0 : 1);
