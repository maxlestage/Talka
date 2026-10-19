//! Le sélecteur d'emojis : quelques catégories, les récents gardés sur l'appareil.

use std::rc::Rc;

use web_sys::Element;

use crate::dom::{El, h, query_all_in, replace, toggle_class};
use crate::store::{local_get, local_set};

pub const QUICK: [&str; 6] = ["👍", "❤️", "😂", "😮", "😢", "🙏"];

const SETS: [(&str, &str); 7] = [
    (
        "😀",
        "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😙 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 🥸 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖 😺 😸 😹 😻 😼 😽 🙀 😿 😾",
    ),
    (
        "👋",
        "👋 🤚 🖐️ ✋ 🖖 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ 👍 👎 ✊ 👊 🤛 🤜 👏 🙌 👐 🤲 🤝 🙏 ✍️ 💅 🤳 💪 🦾 👀 👁️ 👅 👄 💋 🧠 🫶 👶 🧒 👦 👧 🧑 👨 👩 🧓 👴 👵 🙋 🙆 🙅 🤷 🤦 💁 🙇 🧏 💃 🕺 👯 🧘 🏃 🚶",
    ),
    (
        "❤️",
        "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 💟 ♥️ 💯 💢 💥 💫 💦 💨 🕳️ 💬 💭 💤 ✨ 🌟 ⭐ 🔥 🎉 🎊 🎈 🎁 🏆 🥇 ✅ ❌ ❓ ❗ ⚠️ 🔔 🎵 🎶",
    ),
    (
        "🐶",
        "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🦇 🐺 🐗 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐢 🐍 🦖 🐙 🦑 🦀 🐠 🐟 🐬 🐳 🦈 🐊 🐘 🦒 🦓 🐪 🌵 🌲 🌴 🌱 🌿 🍀 🍁 🍂 🌷 🌹 🌺 🌸 🌼 🌻 🌞 🌝 🌛 🌎 🌈 ☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ ☃️ 🌊",
    ),
    (
        "🍕",
        "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🥕 🌽 🌶️ 🥐 🍞 🥖 🧀 🥚 🍳 🥞 🧇 🥓 🍗 🍖 🌭 🍔 🍟 🍕 🥪 🌮 🌯 🥗 🍝 🍜 🍲 🍣 🍱 🥟 🍤 🍙 🍚 🍰 🎂 🧁 🍩 🍪 🍫 🍬 🍭 🍮 🍯 🍼 ☕ 🍵 🧃 🥤 🧋 🍺 🍻 🥂 🍷 🍸 🍹 🍾",
    ),
    (
        "⚽",
        "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏉 🎱 🏓 🏸 🥊 🥋 ⛳ ⛸️ 🎿 🛹 🏋️ 🚴 🏊 🏄 🧗 🎮 🕹️ 🎲 🧩 🎯 🎳 🎭 🎨 🎬 🎤 🎧 🎸 🎹 🥁 🎺 🎻 🚗 🚕 🚌 🏎️ 🚓 🚑 🚒 🚲 🛵 🏍️ ✈️ 🚀 🛸 🚁 ⛵ 🚢 🏠 🏡 🏢 🏰 🗼 🗽 ⛪ 🕌 🏖️ 🏝️ 🏔️ 🗻 🌋 🎡 🎢",
    ),
    (
        "💡",
        "⌚ 📱 💻 ⌨️ 🖥️ 🖨️ 📷 📸 📹 🎥 📞 ☎️ 📺 📻 ⏰ ⏳ 🔋 🔌 💡 🔦 🕯️ 💸 💵 💶 💳 💎 🔧 🔨 🛠️ 🔩 ⚙️ 🧲 💊 💉 🩺 🧪 🔬 🔭 📡 🧹 🧺 🧻 🛁 🔑 🗝️ 🚪 🛋️ 🛏️ 🧸 🎀 ✉️ 📦 📝 📁 📅 📌 📎 ✂️ 🔒 🔓 🔐",
    ),
];

const RECENT: &str = "talka.recentEmoji";

fn recents() -> Vec<String> {
    local_get(RECENT)
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

pub fn remember(e: &str) {
    let mut list = recents();
    list.retain(|x| x != e);
    list.insert(0, e.to_owned());
    list.truncate(24);
    if let Ok(json) = serde_json::to_string(&list) {
        local_set(RECENT, &json);
    }
}

/// Un panneau d'emojis ; `on_pick` à chaque choix.
pub fn picker(on_pick: impl Fn(&str) + 'static) -> El {
    let on_pick: Rc<dyn Fn(&str)> = Rc::new(on_pick);
    let grid = h("div.emoji-grid").el();
    let tabs = h("div.emoji-tabs").attr("role", "tablist").el();
    let show: Rc<dyn Fn(&str)> = {
        let (grid, tabs, on_pick) = (grid.clone(), tabs.clone(), on_pick.clone());
        Rc::new(move |key: &str| {
            let list: Vec<String> = if key == "🕘" {
                recents()
            } else {
                SETS.iter()
                    .find(|(k, _)| *k == key)
                    .map(|(_, s)| s.split(' ').map(str::to_owned).collect())
                    .unwrap_or_default()
            };
            let list = if list.is_empty() {
                vec!["🙂".to_owned()]
            } else {
                list
            };
            let buttons: Vec<Element> = list
                .into_iter()
                .map(|e| {
                    let p = on_pick.clone();
                    h("button.emoji")
                        .attr("type", "button")
                        .text(&e)
                        .click(move || {
                            remember(&e);
                            p(&e);
                        })
                        .el()
                })
                .collect();
            replace(&grid, buttons);
            for b in query_all_in(&tabs, "button") {
                toggle_class(&b, "on", b.get_attribute("data-k").as_deref() == Some(key));
            }
        })
    };
    for key in std::iter::once("🕘").chain(SETS.iter().map(|(k, _)| *k)) {
        let s = show.clone();
        let b = h("button")
            .attr("type", "button")
            .attr("data-k", key)
            .attr("aria-label", "Catégorie")
            .text(key)
            .click(move || s(key));
        let _ = tabs.append_child(&b.el());
    }
    show(if recents().is_empty() { "😀" } else { "🕘" });
    h("div.emoji-picker").child(tabs).child(grid)
}
