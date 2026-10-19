// Le sélecteur d'emojis : quelques catégories, les récents gardés sur l'appareil.

import { h } from "./dom.js";

export const QUICK = ["👍", "❤️", "😂", "😮", "😢", "🙏"];

const SETS = {
  "😀": "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾",
  "👋": "👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 👀 👁️ 👅 👄 💋 🧠 🫶 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙋 🙆 🙅 🤷 🤦 💁 🙇 🧏 💃 🕺 👯 🧘 🏃 🚶",
  "❤️": "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭 💤 ✨ 🌟 ⭐ 🔥 🎉 🎊 🎈 🎁 🏆 🥇 ✅ ❌ ❓ ❗ ⚠️ 🔔 🎵 🎶",
  "🐶": "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐢 🐍 🦖 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🐘 🦒 🦓 🐪 🌵 🌲 🌴 🌱 🌿 🍀 🍁 🍂 🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌎 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 🌊",
  "🍕": "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕 🌽 🌶️ 🥐 🍞 🥖 🧀 🥚 🍳 🥞 🧇 🥓 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍣 🍱 🥟 🍤 🍙 🍚 🍰 🎂 🧁 🍩 🍪 🍫 🍬 🍭 🍮 🍯 🍼 ☕ 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷 🍸 🍹 🍾",
  "⚽": "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ ⛸️ 🎿 🛹 🏋️ 🚴 🏊 🏄 🧗 🎮 🕹️ 🎲 🧩 🎯 🎳 🎭 🎨 🎬 🎤 🎧 🎸 🎹 🥁 🎺 🎻 🚗 🚕 🚌 🏎️ 🚓 🚑 🚒 🚲 🛵 🏍️ ✈️ 🚀 🛸 🚁 ⛵ 🚢 🏠 🏡 🏢 🏰 🗼 🗽 ⛪ 🕌 🏖️ 🏝️ 🏔️ 🗻 🌋 🎡 🎢",
  "💡": "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 📷 📸 📹 🎥 📞 ☎️ 📺 📻 ⏰ ⏳ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💶 💳 💎 🔧 🔨 🛠️ 🔩 ⚙️ 🧲 💊 💉 🩺 🧪 🔬 🔭 📡 🧹 🧺 🧻 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🎀 ✉️ 📦 📝 📁 📅 📌 📎 ✂️ 🔒 🔓 🔐",
};

const RECENT_KEY = "talka.recentEmoji";

function recents() {
  try {
    return JSON.parse(localStorage.getItem(RECENT_KEY) || "[]");
  } catch {
    return [];
  }
}

export function remember(e) {
  try {
    const list = [e, ...recents().filter((x) => x !== e)].slice(0, 24);
    localStorage.setItem(RECENT_KEY, JSON.stringify(list));
  } catch {
    /* navigation privée */
  }
}

// Un panneau d'emojis ; `onPick(emoji)` à chaque choix.
export function picker(onPick) {
  const grid = h("div", { class: "emoji-grid" });
  const tabs = h("div", { class: "emoji-tabs", role: "tablist" });
  const show = (key) => {
    const list = key === "🕘" ? recents() : SETS[key].split(" ");
    grid.replaceChildren(
      ...(list.length ? list : ["🙂"]).map((e) =>
        h("button", { type: "button", class: "emoji", onclick: () => { remember(e); onPick(e); } }, e),
      ),
    );
    for (const b of tabs.children) b.classList.toggle("on", b.dataset.k === key);
  };
  for (const key of ["🕘", ...Object.keys(SETS)]) {
    tabs.append(h("button", { type: "button", "data-k": key, onclick: () => show(key), "aria-label": "Catégorie" }, key));
  }
  show(recents().length ? "🕘" : "😀");
  return h("div", { class: "emoji-picker" }, tabs, grid);
}
