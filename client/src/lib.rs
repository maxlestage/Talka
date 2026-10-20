//! Le navigateur de Talka, en Rust compilé en WebAssembly : pas une ligne de
//! JavaScript écrite à la main (seule la colle générée par wasm-bindgen
//! charge le module).
//!
//! - [`crypto`] : les codes, les clés et le chiffrement — compilé aussi hors
//!   du navigateur, pour les tests du serveur ;
//! - [`model`] : ce que garde l'appareil et ce qui voyage, chiffré ;
//! - [`rows`] : ces données dans Memoire ORM (testé aussi hors du navigateur) ;
//! - le reste n'existe que dans le navigateur : la mémoire (`store`, Memoire
//!   gardé dans IndexedDB), le relais (`net`), la logique (`core`), l'interface (`ui`,
//!   `conv`), les appels WebRTC (`calls`), le site (`site`).

pub mod crypto;
pub mod model;
pub mod rows;

#[cfg(target_arch = "wasm32")]
mod calls;
#[cfg(target_arch = "wasm32")]
mod conv;
#[cfg(target_arch = "wasm32")]
mod core;
#[cfg(target_arch = "wasm32")]
mod dom;
#[cfg(target_arch = "wasm32")]
mod emoji;
#[cfg(target_arch = "wasm32")]
mod lock;
#[cfg(target_arch = "wasm32")]
mod media;
#[cfg(target_arch = "wasm32")]
mod net;
#[cfg(target_arch = "wasm32")]
mod site;
#[cfg(target_arch = "wasm32")]
mod sounds;
#[cfg(target_arch = "wasm32")]
mod store;
#[cfg(target_arch = "wasm32")]
mod ui;

/// Le point d'entrée : la page dit si elle est le site ou l'application
/// (`<meta name="talka-page">`).
#[cfg(target_arch = "wasm32")]
#[wasm_bindgen::prelude::wasm_bindgen(start)]
pub fn start() {
    match dom::meta("talka-page").as_str() {
        "app" => dom::spawn(ui::boot()),
        _ => site::start(),
    }
}
