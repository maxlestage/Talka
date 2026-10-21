//! La v4, en vraies WebSocket : plusieurs appareils d'un même compte (une
//! boîte chacun, un paquet chacun), le retrait d'un appareil, et les boîtes
//! aux lettres tournantes, lues par une connexion anonyme
//! (docs/SECURITE.md §7.11 à §7.13).

use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use talka_web::crypto::{self, Identity};
use talka_web::v3;
use tokio_tungstenite::tungstenite::Message;

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

const A1: &str = "00000000000000a1";
const A2: &str = "00000000000000a2";
const B1: &str = "00000000000000b1";

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

/// Le prochain message d'un type donné (les autres sont passés).
async fn next_of(ws: &mut Ws, t: &str) -> Value {
    loop {
        let v = next_json(ws).await;
        if v["t"] == t {
            return v;
        }
    }
}

/// Rien d'autre n'arrive (pendant un instant).
async fn quiet(ws: &mut Ws) {
    let got = tokio::time::timeout(Duration::from_millis(300), ws.next()).await;
    if let Ok(Some(Ok(Message::Text(text)))) = got {
        panic!("rien n'était attendu : {text}");
    }
}

async fn send(ws: &mut Ws, value: Value) {
    ws.send(Message::text(value.to_string())).await.unwrap();
}

async fn hello(port: u16, id: &Identity, dev: &str) -> Ws {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    let challenge = next_json(&mut ws).await;
    let nonce = challenge["nonce"].as_str().unwrap();
    send(
        &mut ws,
        json!({ "t": "hello", "sign": id.sign, "dh": id.dh, "sig": id.sign_challenge(nonce), "dev": dev }),
    )
    .await;
    ws
}

async fn connect(port: u16, id: &Identity, dev: &str) -> Ws {
    let mut ws = hello(port, id, dev).await;
    let ready = next_json(&mut ws).await;
    assert_eq!(ready["t"], "ready");
    assert_eq!(ready["v4"], true, "le relais annonce les appareils");
    ws
}

async fn anon(port: u16) -> Ws {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    assert_eq!(next_json(&mut ws).await["t"], "challenge");
    send(&mut ws, json!({ "t": "anon" })).await;
    let ready = next_json(&mut ws).await;
    assert_eq!(
        ready["boites"], true,
        "le relais sait lire les boîtes tournantes"
    );
    ws
}

#[tokio::test]
async fn devices_and_rotating_boxes_end_to_end() {
    let port = 4523;
    let mut app = talka::app();
    app.port(port).quiet();
    tokio::spawn(app.serve());
    tokio::time::sleep(Duration::from_millis(300)).await;

    let alice = Identity::create(0.0);
    let bob = Identity::create(0.0);

    // Deux appareils d'Alice (la même identité), chacun son paquet.
    let mut a1 = connect(port, &alice, A1).await;
    let mut a2 = connect(port, &alice, A2).await;
    for (ws, dev) in [(&mut a1, A1), (&mut a2, A2)] {
        let engine = v3::Engine::start(None, 1_000);
        let (bundle, sig) = engine.published_device(&alice, dev).unwrap();
        send(
            ws,
            json!({ "t": "publish-bundle", "bundle": bundle, "sig": sig, "dev": dev }),
        )
        .await;
        assert_eq!(next_of(ws, "published").await["ok"], true);
    }
    let mut b = connect(port, &bob, B1).await;
    send(&mut b, json!({ "t": "fetch-bundle", "code": alice.code })).await;
    let found = next_of(&mut b, "bundle").await;
    let devices = found["devices"].as_array().unwrap();
    assert_eq!(devices.len(), 2);
    for d in devices {
        let dev = d["dev"].as_str().unwrap();
        assert!(
            v3::verify_device_bundle(
                &alice.code,
                &alice.sign,
                &alice.dh,
                dev,
                d["bundle"].as_str().unwrap(),
                d["sig"].as_str().unwrap()
            )
            .is_some(),
            "paquet de {dev}"
        );
    }

    // Un envoi sans appareil (un ancien client) : une copie pour chacun.
    send(
        &mut b,
        json!({ "t": "send", "id": "m1", "to": alice.code, "body": "pour tous" }),
    )
    .await;
    assert_eq!(next_of(&mut b, "ack").await["id"], "m1");
    let c1 = next_of(&mut a1, "msg").await;
    let c2 = next_of(&mut a2, "msg").await;
    assert_eq!(
        (c1["body"].as_str(), c2["body"].as_str()),
        (Some("pour tous"), Some("pour tous"))
    );
    assert_eq!(c1["fd"], B1, "l'appareil de l'expéditeur");
    // Un envoi pour A1 seulement.
    send(
        &mut b,
        json!({ "t": "send", "id": "m2", "to": alice.code, "dev": A1, "body": "pour a1" }),
    )
    .await;
    next_of(&mut b, "ack").await;
    assert_eq!(next_of(&mut a1, "msg").await["body"], "pour a1");
    quiet(&mut a2).await;

    // Les boîtes tournantes : Bob dépose anonymement dans la boîte du jour
    // de l'appareil A1 ; A1 la lit par une connexion anonyme.
    let day = crypto::day(1_800_000_000_000.0);
    let key_b = crypto::boites_key(&bob, &alice.code, &alice.dh).unwrap();
    let key_a = crypto::boites_key(&alice, &bob.code, &bob.dh).unwrap();
    assert_eq!(key_a, key_b, "la même clé des deux côtés");
    let boite = crypto::boite(&key_b, &alice.code, A1, day);
    let inner = crypto::seal(
        &bob,
        &alice.code,
        &alice.dh,
        "{\"k\":\"typing\",\"on\":true}",
    )
    .unwrap();
    let sealed = crypto::seal_sender_from(&bob.code, B1, &alice.code, &alice.dh, &inner).unwrap();
    let mut drop = anon(port).await;
    send(
        &mut drop,
        json!({ "t": "send", "id": "x1", "boite": boite, "body": sealed }),
    )
    .await;
    let ack = next_of(&mut drop, "ack").await;
    assert_eq!(ack["online"], false);
    let mut reader = anon(port).await;
    let mine = crypto::boite(&key_a, &alice.code, A1, day);
    assert_eq!(mine, boite);
    send(
        &mut reader,
        json!({ "t": "boites", "list": [mine, crypto::boite(&key_a, &alice.code, A1, day + 1)] }),
    )
    .await;
    let got = next_of(&mut reader, "msg").await;
    assert_eq!(got["boite"], boite);
    assert!(
        got.get("from").is_none()
            && !got.to_string().contains(&alice.code)
            && !got.to_string().contains(&bob.code)
    );
    let (from, fd, body) = crypto::open_sealed_from(&alice, got["body"].as_str().unwrap()).unwrap();
    assert_eq!((from.as_str(), fd.as_str()), (bob.code.as_str(), B1));
    assert!(crypto::open(&alice, &from, &bob.dh, &body).is_some());
    // Rangé : oublié ; la connexion abonnée reçoit la suite en direct.
    send(&mut reader, json!({ "t": "got", "n": [got["n"]] })).await;
    send(
        &mut drop,
        json!({ "t": "send", "id": "x2", "boite": boite, "body": sealed, "live": true }),
    )
    .await;
    assert_eq!(next_of(&mut drop, "ack").await["online"], true);
    assert_eq!(next_of(&mut reader, "msg").await["live"], true);

    // A1 retire A2 : A2 est prévenu, coupé, et ne revient plus.
    send(&mut a1, json!({ "t": "unlink", "dev": A2 })).await;
    assert_eq!(next_of(&mut a2, "unlinked").await["t"], "unlinked");
    let mut again = hello(port, &alice, A2).await;
    match tokio::time::timeout(Duration::from_secs(5), again.next()).await {
        Ok(Some(Ok(Message::Close(Some(frame))))) => assert_eq!(u16::from(frame.code), 4006),
        other => panic!("fermeture attendue : {other:?}"),
    }
    send(
        &mut b,
        json!({ "t": "send", "id": "m3", "to": alice.code, "dev": A2, "body": "trop tard" }),
    )
    .await;
    assert_eq!(next_of(&mut b, "error").await["error"], "dev");
    send(&mut b, json!({ "t": "fetch-bundle", "code": alice.code })).await;
    assert_eq!(
        next_of(&mut b, "bundle").await["devices"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
}
