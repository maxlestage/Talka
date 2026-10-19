//! Le relais, en vraie WebSocket : présentation signée, messages déposés pour
//! un destinataire hors ligne, oubliés une fois rangés, clés publiques.

use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use futures_util::{SinkExt, StreamExt};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use rand_core::OsRng;
use serde_json::{Value, json};
use tokio_tungstenite::tungstenite::Message;

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

struct Device {
    sign: SigningKey,
    sign_pub: String,
    dh_pub: String,
    code: String,
}

fn device() -> Device {
    let sign = SigningKey::random(&mut OsRng);
    let dh = SigningKey::random(&mut OsRng);
    let sign_raw = sign
        .verifying_key()
        .to_encoded_point(false)
        .as_bytes()
        .to_vec();
    let dh_raw = dh
        .verifying_key()
        .to_encoded_point(false)
        .as_bytes()
        .to_vec();
    Device {
        code: talka::code::derive(&sign_raw, &dh_raw),
        sign,
        sign_pub: B64.encode(sign_raw),
        dh_pub: B64.encode(dh_raw),
    }
}

async fn next_json(ws: &mut Ws) -> Value {
    loop {
        let msg = tokio::time::timeout(Duration::from_secs(5), ws.next())
            .await
            .expect("un message attendu")
            .expect("connexion ouverte")
            .expect("message valide");
        if let Message::Text(text) = msg {
            return serde_json::from_str(&text).unwrap();
        }
    }
}

async fn connect(port: u16, d: &Device, forge: bool) -> Ws {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    let challenge = next_json(&mut ws).await;
    assert_eq!(challenge["t"], "challenge");
    let nonce = challenge["nonce"].as_str().unwrap();
    let message = if forge {
        "talka-auth:autre".to_owned()
    } else {
        format!("talka-auth:{nonce}")
    };
    let sig: Signature = d.sign.sign(message.as_bytes());
    let hello = json!({ "t": "hello", "sign": d.sign_pub, "dh": d.dh_pub, "sig": B64.encode(sig.to_bytes()) });
    ws.send(Message::text(hello.to_string())).await.unwrap();
    ws
}

async fn send(ws: &mut Ws, value: Value) {
    ws.send(Message::text(value.to_string())).await.unwrap();
}

#[tokio::test]
async fn relay_end_to_end() {
    let port = 4517;
    let mut app = talka::app();
    app.port(port).quiet();
    tokio::spawn(app.serve());
    tokio::time::sleep(Duration::from_millis(300)).await;

    let alice = device();
    let bob = device();

    // Une signature qui ne correspond pas au défi : connexion refusée.
    let mut forged = connect(port, &alice, true).await;
    let closed = tokio::time::timeout(Duration::from_secs(5), forged.next())
        .await
        .unwrap();
    assert!(matches!(
        closed,
        Some(Ok(Message::Close(_))) | None | Some(Err(_))
    ));

    // Alice se présente : son code est l'empreinte de ses clés.
    let mut a = connect(port, &alice, false).await;
    let ready = next_json(&mut a).await;
    assert_eq!(ready["t"], "ready");
    assert_eq!(ready["code"], alice.code.as_str());

    // Bob n'est pas en ligne : le message attend dans sa boîte.
    send(
        &mut a,
        json!({ "t": "send", "id": "m1", "to": bob.code, "body": "chiffré" }),
    )
    .await;
    let ack = next_json(&mut a).await;
    assert_eq!(ack["t"], "ack");
    assert_eq!(ack["online"], false);

    // Un message « live » (saisie, appel) ne l'attend pas.
    send(
        &mut a,
        json!({ "t": "send", "id": "t1", "to": bob.code, "body": "écrit", "live": true }),
    )
    .await;
    assert_eq!(next_json(&mut a).await["t"], "ack");

    // Un code mal formé est refusé.
    send(
        &mut a,
        json!({ "t": "send", "id": "x", "to": "PAS-UN-CODE", "body": "?" }),
    )
    .await;
    let err = next_json(&mut a).await;
    assert_eq!(err["error"], "code");

    // Alice suit la présence de Bob.
    send(&mut a, json!({ "t": "watch", "codes": [bob.code] })).await;
    let p = next_json(&mut a).await;
    assert_eq!(p["t"], "presence");
    assert_eq!(p["online"], false);

    // Bob arrive : il reçoit le message en attente, et Alice le voit en ligne.
    let mut b = connect(port, &bob, false).await;
    assert_eq!(next_json(&mut b).await["t"], "ready");
    let msg = next_json(&mut b).await;
    assert_eq!(msg["t"], "msg");
    assert_eq!(msg["from"], alice.code.as_str());
    assert_eq!(msg["body"], "chiffré");
    let n = msg["n"].as_u64().unwrap();
    let p = next_json(&mut a).await;
    assert_eq!(
        (p["code"].as_str(), p["online"].as_bool()),
        (Some(bob.code.as_str()), Some(true))
    );

    // Bob cherche les clés d'Alice : elles correspondent à son code.
    send(&mut b, json!({ "t": "lookup", "code": alice.code })).await;
    let keys = next_json(&mut b).await;
    let sign = B64.decode(keys["sign"].as_str().unwrap()).unwrap();
    let dh = B64.decode(keys["dh"].as_str().unwrap()).unwrap();
    assert_eq!(talka::code::derive(&sign, &dh), alice.code);

    // Rangé : le serveur l'oublie. Bob revient : plus rien en attente.
    send(&mut b, json!({ "t": "got", "n": [n] })).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    drop(b);
    tokio::time::sleep(Duration::from_millis(100)).await;
    let mut b = connect(port, &bob, false).await;
    assert_eq!(next_json(&mut b).await["t"], "ready");
    send(&mut b, json!({ "t": "ping" })).await;
    assert_eq!(next_json(&mut b).await["t"], "pong");

    // En ligne, le message arrive tout de suite.
    send(
        &mut a,
        json!({ "t": "send", "id": "m2", "to": bob.code, "body": "direct" }),
    )
    .await;
    let msg = next_json(&mut b).await;
    assert_eq!(msg["body"], "direct");
}

#[tokio::test]
async fn pages_and_api() {
    let client = talka::app().test().await;
    assert_eq!(client.get("/").await.status(), 200);
    assert!(client.get("/").await.text().contains("Talka"));
    assert_eq!(client.get("/app").await.status(), 200);
    assert_eq!(client.get("/confidentialite").await.status(), 200);
    assert_eq!(client.get("/nulle-part").await.status(), 404);
    assert_eq!(client.get("/manifest.webmanifest").await.status(), 200);
    assert_eq!(client.get("/api/ice").await.status(), 200);
    assert_eq!(client.get("/api/qr/K7Q2MX9PRT").await.status(), 200);
    assert_eq!(client.get("/api/qr/pas-un-code").await.status(), 404);
    let main = format!("/s/{}/app/main.js", talka::assets::version());
    assert_eq!(client.get(&main).await.status(), 200);
    assert_eq!(client.get("/ws").await.status(), 426);
}
