//! L'expéditeur scellé, en vraie WebSocket : une connexion anonyme dépose
//! un message scellé avec le jeton de remise du destinataire ; le relais le
//! livre sans expéditeur ; seul le destinataire l'ouvre, et y retrouve
//! l'auteur, prouvé par l'enveloppe intérieure.

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use talka_web::crypto::{self, Identity};
use tokio_tungstenite::tungstenite::Message;

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

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

async fn send(ws: &mut Ws, value: Value) {
    ws.send(Message::text(value.to_string())).await.unwrap();
}

async fn open(port: u16) -> Ws {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    assert_eq!(next_json(&mut ws).await["t"], "challenge");
    ws
}

async fn connect(port: u16, id: &Identity) -> Ws {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    let challenge = next_json(&mut ws).await;
    let nonce = challenge["nonce"].as_str().unwrap();
    send(
        &mut ws,
        json!({ "t": "hello", "sign": id.sign, "dh": id.dh, "sig": id.sign_challenge(nonce) }),
    )
    .await;
    let ready = next_json(&mut ws).await;
    assert_eq!(ready["t"], "ready");
    assert_eq!(
        ready["sealed"], true,
        "le relais annonce l'expéditeur scellé"
    );
    ws
}

#[tokio::test]
async fn sealed_sender_end_to_end() {
    let port = 4519;
    let mut app = talka::app();
    app.port(port).quiet();
    tokio::spawn(app.serve());
    tokio::time::sleep(Duration::from_millis(300)).await;

    let alice = Identity::create(0.0);
    let bob = Identity::create(0.0);
    let eve = Identity::create(0.0);

    // Alice écrit, anonymement : le relais ne sait pas qui elle est.
    let mut anon = open(port).await;
    send(&mut anon, json!({ "t": "anon" })).await;
    let ready = next_json(&mut anon).await;
    assert_eq!(
        (ready["t"].as_str(), ready["anon"].as_bool()),
        (Some("ready"), Some(true))
    );

    let inner = crypto::seal(&alice, &bob.code, &bob.dh, "{\"t\":\"msg\",\"x\":1}").unwrap();
    let sealed = crypto::seal_sender(&alice.code, &bob.code, &bob.dh, &inner).unwrap();
    let envoi = |id: &str, jeton: String| {
        json!({ "t": "send", "id": id, "to": bob.code, "sign": bob.sign, "dh": bob.dh,
                "jeton": jeton, "body": sealed, "wake": true })
    };

    // Sans le bon jeton : refusé (le jeton d'un autre, ou rien).
    send(&mut anon, envoi("s0", eve.jeton())).await;
    let refused = next_json(&mut anon).await;
    assert_eq!(
        (refused["t"].as_str(), refused["error"].as_str()),
        (Some("error"), Some("jeton"))
    );
    send(&mut anon, envoi("s0", String::new())).await;
    assert_eq!(next_json(&mut anon).await["error"], "jeton");
    // Les clés d'Eve avec le code de Bob : refusé aussi.
    let mut wrong_keys = envoi("s0", bob.jeton());
    wrong_keys["sign"] = json!(eve.sign);
    send(&mut anon, wrong_keys).await;
    assert_eq!(next_json(&mut anon).await["error"], "jeton");

    // Avec le jeton de Bob (qu'il ne donne qu'à ses contacts) : déposé.
    send(&mut anon, envoi("s1", bob.jeton())).await;
    let ack = next_json(&mut anon).await;
    assert_eq!(
        (ack["t"].as_str(), ack["id"].as_str()),
        (Some("ack"), Some("s1"))
    );
    assert_eq!(ack["online"], false);

    // Bob se connecte : le message attend, sans expéditeur.
    let mut b = connect(port, &bob).await;
    let msg = next_json(&mut b).await;
    assert_eq!(msg["t"], "msg");
    assert_eq!(msg["sealed"], true);
    assert!(
        msg.get("from").is_none(),
        "le relais ne connaît pas l'expéditeur"
    );
    assert!(!msg.to_string().contains(&alice.code));
    let (from, body) = crypto::open_sealed(&bob, msg["body"].as_str().unwrap()).unwrap();
    assert_eq!(from, alice.code);
    assert_eq!(
        crypto::open(&bob, &from, &alice.dh, &body).as_deref(),
        Some("{\"t\":\"msg\",\"x\":1}")
    );

    // En ligne, il arrive tout de suite ; `live` aussi passe scellé.
    let mut live = envoi("s2", bob.jeton());
    live["live"] = json!(true);
    send(&mut anon, live).await;
    assert_eq!(next_json(&mut anon).await["online"], true);
    let msg = next_json(&mut b).await;
    assert_eq!(
        (msg["sealed"].as_bool(), msg["live"].as_bool()),
        (Some(true), Some(true))
    );

    // Une connexion anonyme ne peut rien faire d'autre qu'envoyer.
    send(&mut anon, json!({ "t": "lookup", "code": bob.code })).await;
    send(&mut anon, json!({ "t": "ping" })).await;
    assert_eq!(next_json(&mut anon).await["t"], "pong");
}
