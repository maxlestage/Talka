//! La passkey : la clé de l'appareil (qui chiffre toute la mémoire de Talka)
//! ne s'ouvre qu'avec elle — empreinte, visage, Windows Hello, clé de
//! sécurité. WebAuthn avec l'extension PRF : la passkey rend 32 octets
//! secrets, toujours les mêmes pour le même sel, que seul son
//! authentificateur sait calculer ; Talka en tire la clé qui ouvre la clé de
//! l'appareil (`crypto::passkey_key`). Rien de ce secret n'est gardé.
//!
//! Perdre la passkey, c'est perdre l'accès à cette mémoire : seule une
//! sauvegarde la rend.

use js_sys::{Array, Object, Reflect, Uint8Array};
use wasm_bindgen::prelude::*;

use crate::crypto;
use crate::dom::{Deferred, await_promise, call, document, get, h, icon, window};
use crate::store;

/// Le navigateur sait-il faire des passkeys ?
pub fn supported() -> bool {
    !get(&window(), "PublicKeyCredential").is_undefined()
}

fn set(obj: &Object, key: &str, value: impl Into<JsValue>) {
    let _ = Reflect::set(obj, &key.into(), &value.into());
}

fn u8a(bytes: &[u8]) -> JsValue {
    Uint8Array::from(bytes).into()
}

fn hostname() -> String {
    window().location().hostname().unwrap_or_default()
}

/// La sortie PRF d'une assertion avec cette passkey et ce sel.
async fn prf(id: &[u8], salt: &[u8]) -> Result<Vec<u8>, String> {
    let allow = Object::new();
    set(&allow, "type", "public-key");
    set(&allow, "id", u8a(id));
    let eval = Object::new();
    set(&eval, "first", u8a(salt));
    let prf = Object::new();
    set(&prf, "eval", eval);
    let ext = Object::new();
    set(&ext, "prf", prf);
    let pk = Object::new();
    set(&pk, "challenge", u8a(&crypto::random_bytes(32)));
    set(&pk, "rpId", hostname());
    set(&pk, "allowCredentials", Array::of1(&allow));
    set(&pk, "userVerification", "required");
    set(&pk, "timeout", 120_000);
    set(&pk, "extensions", ext);
    let opts = Object::new();
    set(&opts, "publicKey", pk);
    let creds = get(&window().navigator(), "credentials");
    let cred = await_promise(call(&creds, "get", &[opts.into()]))
        .await
        .map_err(|_| "La passkey n'a pas répondu.".to_owned())?;
    let results = call(&cred, "getClientExtensionResults", &[]).map_err(|_| "prf")?;
    let first = get(&get(&get(&results, "prf"), "results"), "first");
    if first.is_undefined() || first.is_null() {
        return Err("Cette passkey ne sait pas protéger Talka (extension PRF absente). Essayez-en une autre, ou un navigateur récent.".into());
    }
    Ok(Uint8Array::new(&first).to_vec())
}

/// Crée une passkey pour Talka et protège la clé de l'appareil par elle.
pub async fn enable(code: &str, name: &str) -> Result<(), String> {
    if !supported() {
        return Err("Ce navigateur ne connaît pas les passkeys.".into());
    }
    let rp = Object::new();
    set(&rp, "name", "Talka");
    set(&rp, "id", hostname());
    let user = Object::new();
    set(&user, "id", u8a(&crypto::random_bytes(16)));
    set(&user, "name", format!("Talka {}", crypto::pretty(code)));
    set(
        &user,
        "displayName",
        if name.is_empty() { "Talka" } else { name },
    );
    let params = Array::new();
    for alg in [-7, -257] {
        let p = Object::new();
        set(&p, "type", "public-key");
        set(&p, "alg", alg);
        params.push(&p);
    }
    let sel = Object::new();
    set(&sel, "residentKey", "preferred");
    set(&sel, "userVerification", "required");
    let ext = Object::new();
    set(&ext, "prf", Object::new());
    let pk = Object::new();
    set(&pk, "rp", rp);
    set(&pk, "user", user);
    set(&pk, "challenge", u8a(&crypto::random_bytes(32)));
    set(&pk, "pubKeyCredParams", params);
    set(&pk, "authenticatorSelection", sel);
    set(&pk, "timeout", 120_000);
    set(&pk, "extensions", ext);
    let opts = Object::new();
    set(&opts, "publicKey", pk);
    let creds = get(&window().navigator(), "credentials");
    let cred = await_promise(call(&creds, "create", &[opts.into()]))
        .await
        .map_err(|_| "La passkey n'a pas été créée.".to_owned())?;
    let id = Uint8Array::new(&get(&cred, "rawId")).to_vec();
    // La passkey doit savoir faire PRF : on l'essaie tout de suite.
    let salt = crypto::random_bytes(32);
    let out = prf(&id, &salt).await?;
    if !store::protect(store::NewLock::Passkey { id, salt, prf: out }).await {
        return Err("La protection n'a pas pu être enregistrée.".into());
    }
    Ok(())
}

/// Talka s'ouvre et sa mémoire est protégée par une passkey : un bouton
/// (les navigateurs demandent un geste), jusqu'à ce qu'elle l'ouvre.
pub async fn unlock_screen(id: &[u8], salt: &[u8]) {
    let host = h("div.lock-screen").el();
    let _ = document().body().expect("body").append_child(&host);
    let d = Deferred::<()>::new();
    let msg = h("p.pin-msg")
        .text("Vos messages sont chiffrés par votre passkey.")
        .el();
    let (id, salt) = (id.to_vec(), salt.to_vec());
    let (s, m, done_host) = (d.sender(), msg.clone(), host.clone());
    let button = h("button.btn")
        .attr("type", "button")
        .text("Déverrouiller")
        .click(move || {
            let (s, m, host, id, salt) = (
                s.clone(),
                m.clone(),
                done_host.clone(),
                id.clone(),
                salt.clone(),
            );
            crate::dom::spawn(async move {
                m.set_text_content(Some("Vérification…"));
                match prf(&id, &salt).await {
                    Ok(out) if store::unlock_passkey(&out).await => {
                        host.remove();
                        s.send(Some(()));
                    }
                    Ok(_) => {
                        m.set_text_content(Some("Ce n'est pas la passkey de ce Talka."));
                    }
                    Err(e) => {
                        m.set_text_content(Some(&e));
                    }
                }
            });
        });
    let lost = h("button.link").attr("type", "button").text("Passkey perdue ?").click(|| {
        crate::dom::spawn(async {
            if crate::dom::confirm_box(
                "Passkey perdue",
                "Sans elle, la mémoire de Talka sur cet appareil ne peut plus être ouverte. Vous pouvez tout effacer, puis restaurer une sauvegarde.",
                "Tout effacer",
                true,
            )
            .await
            {
                store::forget_everything().await;
                let _ = window().location().reload();
            }
        });
    });
    let _ = host.append_child(
        &h("div.passkey-unlock")
            .child(h("div.pin-lock").child(icon("lock")))
            .child(h("h2").text("Talka est verrouillé"))
            .child(msg)
            .child(button)
            .child(lost)
            .el(),
    );
    d.wait().await;
}
