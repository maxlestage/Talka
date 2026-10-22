//! La v6, en vraies WebSocket : le relais prouve sa clé à chaque connexion
//! et signe l'ordre « cet appareil est retiré » (docs/SECURITE.md §7.16) ;
//! le navigateur le vérifie avec son propre code.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use talka_web::crypto::{self, Identity};
use tokio_tungstenite::tungstenite::Message;

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

const D1: &str = "00000000000000d1";
const D2: &str = "00000000000000d2";

async fn next_of(ws: &mut Ws, t: &str) -> Value {
    loop {
        let msg = tokio::time::timeout(Duration::from_secs(5), ws.next())
            .await
            .expect("un message attendu")
            .expect("connexion ouverte")
            .expect("message valide");
        if let Message::Text(text) = msg {
            let v: Value = serde_json::from_str(&text).unwrap();
            if v["t"] == t {
                return v;
            }
        }
    }
}

/// Se présente comme l'appareil `dev` : (la connexion, le défi, « ready »).
async fn connect(port: u16, id: &Identity, dev: &str) -> (Ws, String, Value) {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    let challenge = next_of(&mut ws, "challenge").await;
    let nonce = challenge["nonce"].as_str().unwrap().to_owned();
    let hello = json!({ "t": "hello", "sign": id.sign, "dh": id.dh, "sig": id.sign_challenge(&nonce), "dev": dev });
    ws.send(Message::text(hello.to_string())).await.unwrap();
    let ready = next_of(&mut ws, "ready").await;
    (ws, nonce, ready)
}

#[tokio::test]
async fn the_relay_proves_its_key_and_signs_removals() {
    let (secret, public) = talka::serverkey::ServerKey::generate().unwrap();
    // SAFETY : ce test est seul dans son exécutable, avant tout fil.
    unsafe { std::env::set_var("TALKA_SERVER_KEY", &secret) };
    let port = 4524;
    let mut app = talka::app();
    app.port(port).quiet();
    tokio::spawn(app.serve());
    tokio::time::sleep(Duration::from_millis(300)).await;

    let alice = Identity::create(0.0);
    let (mut a1, nonce, ready) = connect(port, &alice, D1).await;
    assert_eq!(ready["relais"], public);
    let signed = format!("talka-relais-v6:pret:{nonce}:{}/{D1}", alice.code);
    let sig = ready["relais_sig"].as_str().unwrap();
    assert!(crypto::verify(&public, signed.as_bytes(), sig));
    // La signature est liée au défi de cette connexion.
    assert!(!crypto::verify(
        &public,
        b"talka-relais-v6:pret:autre:x/y",
        sig
    ));

    // Le retrait, en direct puis au retour : signé tous les deux.
    let (mut a2, _, _) = connect(port, &alice, D2).await;
    a1.send(Message::text(
        json!({ "t": "unlink", "dev": D2 }).to_string(),
    ))
    .await
    .unwrap();
    let removed = format!("talka-relais-v6:retire:{}/{D2}", alice.code);
    let live = next_of(&mut a2, "unlinked").await;
    assert!(crypto::verify(
        &public,
        removed.as_bytes(),
        live["sig"].as_str().unwrap()
    ));
    let (mut again, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    let challenge = next_of(&mut again, "challenge").await;
    let n = challenge["nonce"].as_str().unwrap();
    let hello = json!({ "t": "hello", "sign": alice.sign, "dh": alice.dh, "sig": alice.sign_challenge(n), "dev": D2 });
    again.send(Message::text(hello.to_string())).await.unwrap();
    let back = next_of(&mut again, "unlinked").await;
    assert!(crypto::verify(
        &public,
        removed.as_bytes(),
        back["sig"].as_str().unwrap()
    ));
}
