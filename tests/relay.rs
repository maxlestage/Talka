//! Le relais, en vraie WebSocket : présentation signée, messages déposés pour
//! un destinataire hors ligne, oubliés une fois rangés, clés publiques.

use std::time::Duration;

use base64::Engine;
use base64::engine::general_purpose::STANDARD as B64;
use futures_util::{SinkExt, StreamExt};
use p256::ecdsa::signature::Signer;
use p256::ecdsa::{Signature, SigningKey};
use rand_core::{OsRng, RngCore};
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

/// Une clé P-256 tirée au hasard (32 octets, refusés seulement s'ils sortent
/// de l'ordre de la courbe).
fn random_key() -> SigningKey {
    loop {
        let mut seed = [0u8; 32];
        OsRng.fill_bytes(&mut seed);
        if let Ok(key) = SigningKey::from_slice(&seed) {
            return key;
        }
    }
}

fn device() -> Device {
    let sign = random_key();
    let dh = random_key();
    let sign_raw = sign
        .verifying_key()
        .to_sec1_point(false)
        .as_bytes()
        .to_vec();
    let dh_raw = dh.verifying_key().to_sec1_point(false).as_bytes().to_vec();
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
    // L'identifiant du démarrage : le même pour toutes les connexions.
    let boot = ready["boot"].as_str().unwrap_or_default().to_owned();
    assert!(!boot.is_empty());
    assert_eq!(boot, talka::relay::boot_id());

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
    let ready = next_json(&mut b).await;
    assert_eq!(ready["t"], "ready");
    assert_eq!(ready["boot"], boot.as_str());
    send(&mut b, json!({ "t": "ping" })).await;
    assert_eq!(next_json(&mut b).await["t"], "pong");
    // Sans clé VAPID, pas de notifications : le relais ne l'annonce pas et
    // refuse les adresses (sans erreur).
    assert!(ready.get("push").is_none());
    send(
        &mut b,
        json!({ "t": "push", "endpoint": "https://fcm.googleapis.com/fcm/send/x" }),
    )
    .await;
    let answer = next_json(&mut b).await;
    assert_eq!(
        (answer["t"].as_str(), answer["ok"].as_bool()),
        (Some("push"), Some(false))
    );

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
    // Les pages partent compressées si le navigateur le veut.
    let home = client
        .get("/")
        .header("accept-encoding", "gzip, deflate, br")
        .await;
    assert_eq!(home.header("content-encoding"), Some("br"));
    assert!(home.bytes().len() * 2 < client.get("/").await.bytes().len());
    assert_eq!(client.get("/app").await.status(), 200);
    assert_eq!(client.get("/confidentialite").await.status(), 200);
    assert_eq!(client.get("/nulle-part").await.status(), 404);
    assert_eq!(client.get("/manifest.webmanifest").await.status(), 200);
    let sw = client.get("/talka-sw.js").await;
    assert_eq!(sw.status(), 200);
    assert!(sw.text().contains("showNotification"));
    // Revérifié sans être retéléchargé tant qu'il n'a pas changé.
    let etag = sw.header("etag").expect("ETag").to_owned();
    let again = client
        .get("/talka-sw.js")
        .header("if-none-match", &etag)
        .await;
    assert_eq!(again.status(), 304);
    assert!(again.bytes().is_empty());
    let other = client
        .get("/talka-sw.js")
        .header("if-none-match", "\"autre\"")
        .await;
    assert_eq!(other.status(), 200);
    assert_eq!(other.text(), sw.text());
    assert_eq!(client.get("/api/ice").await.status(), 200);
    assert_eq!(client.get("/api/qr/K7Q2MX9PRT").await.status(), 200);
    assert_eq!(client.get("/api/qr/pas-un-code").await.status(), 404);
    let main = format!("/s/{}/talka_web.js", talka::assets::version());
    assert_eq!(client.get(&main).await.status(), 200);
    // Compressé une fois à la compilation, envoyé selon `Accept-Encoding`.
    if talka::assets::has_client() {
        let wasm = format!("/s/{}/talka_web_bg.wasm", talka::assets::version());
        let br = client
            .get(&wasm)
            .header("accept-encoding", "gzip, deflate, br")
            .await;
        assert_eq!(br.header("content-encoding"), Some("br"));
        let gz = client.get(&wasm).header("accept-encoding", "gzip").await;
        assert_eq!(gz.header("content-encoding"), Some("gzip"));
        let plain = client.get(&wasm).await;
        assert_eq!(plain.header("content-encoding"), None);
        assert!(br.bytes().len() * 3 < plain.bytes().len());
    }
    assert_eq!(client.get("/ws").await.status(), 426);
}

/// Le navigateur (crate `talka-web`, compilée ici normalement) et le serveur
/// calculent le même code et s'accordent sur la signature du défi.
#[test]
fn browser_and_server_agree() {
    use talka_web::crypto::{Identity, code_of, unb64};
    assert_eq!(code_of(&[], &[]), talka::code::derive(&[], &[]));
    let id = Identity::create(0.0);
    let (sign, dh) = (unb64(&id.sign).unwrap(), unb64(&id.dh).unwrap());
    assert_eq!(talka::code::derive(&sign, &dh), id.code);
    assert!(talka::code::public_key(&id.sign).is_some());
    let sig = id.sign_challenge("abc");
    assert!(talka::code::verify(
        &sign,
        &talka::code::challenge_message("abc"),
        &sig
    ));
    assert!(!talka::code::verify(
        &sign,
        &talka::code::challenge_message("abd"),
        &sig
    ));
}

/// Une page déjà reçue ne repart pas : un 304 vide, avec sa politique de
/// sécurité ; une autre empreinte, ou aucune, et la page repart entière.
#[tokio::test]
async fn a_page_already_received_is_not_sent_again() {
    let client = talka::app().test().await;
    for path in ["/", "/app", "/confidentialite"] {
        let first = client
            .get(path)
            .header("accept-encoding", "br")
            .header("host", "talka.example")
            .await;
        let etag = first.header("etag").expect("ETag").to_owned();
        assert!(etag.starts_with("W/\""), "{path} : {etag}");
        let again = client
            .get(path)
            .header("accept-encoding", "br")
            .header("host", "talka.example")
            .header("if-none-match", &etag)
            .await;
        assert_eq!(again.status(), 304, "{path}");
        assert!(again.bytes().is_empty());
        assert_eq!(again.header("etag"), Some(etag.as_str()));
        assert!(
            again
                .header("content-security-policy")
                .is_some_and(|c| c.contains("wss://talka.example"))
        );
        // La même empreinte dans un autre codage (faible), ou dans une liste.
        let plain = client
            .get(path)
            .header(
                "if-none-match",
                &format!("\"autre\", {}", etag.trim_start_matches("W/")),
            )
            .await;
        assert_eq!(plain.status(), 304, "{path}");
        let changed = client
            .get(path)
            .header("if-none-match", "W/\"autre\"")
            .await;
        assert_eq!(changed.status(), 200);
        assert!(!changed.bytes().is_empty());
    }
    // Une page d'erreur n'a pas d'empreinte.
    let missing = client.get("/nulle-part").header("if-none-match", "*").await;
    assert_eq!(missing.status(), 404);
    assert_eq!(missing.header("etag"), None);
}

/// Les pages sont faites et compressées une fois : ce qui part, dans
/// chaque codage, est la page telle qu'elle était, avec ses en-têtes.
#[tokio::test]
async fn pages_are_the_same_in_every_coding() {
    use std::io::Read;
    let client = talka::app().test().await;
    for (path, status) in [
        ("/", 200),
        ("/app", 200),
        ("/confidentialite", 200),
        ("/nulle-part", 404),
    ] {
        let plain = client.get(path).header("host", "talka.example").await;
        assert_eq!(plain.status(), status, "{path}");
        assert_eq!(plain.header("content-encoding"), None);
        let csp = plain
            .header("content-security-policy")
            .expect("CSP")
            .to_owned();
        assert!(csp.contains("wss://talka.example"), "{path}");
        let page = plain.bytes().to_vec();
        for coding in ["br", "gzip"] {
            for _ in 0..2 {
                let res = client
                    .get(path)
                    .header("host", "talka.example")
                    .header("accept-encoding", coding)
                    .await;
                assert_eq!(res.status(), status, "{path}");
                assert_eq!(res.header("content-encoding"), Some(coding), "{path}");
                assert_eq!(res.header("content-security-policy"), Some(csp.as_str()));
                assert_eq!(res.header("content-type"), plain.header("content-type"));
                let mut out = Vec::new();
                match coding {
                    "br" => brotli::Decompressor::new(&res.bytes()[..], 4096).read_to_end(&mut out),
                    _ => flate2::read::GzDecoder::new(&res.bytes()[..]).read_to_end(&mut out),
                }
                .expect("décompressée");
                assert_eq!(out, page, "{path} en {coding}");
            }
        }
        // Un autre hôte : sa propre politique, la même page.
        let other = client.get(path).header("host", "autre.example").await;
        assert!(
            other
                .header("content-security-policy")
                .unwrap()
                .contains("wss://autre.example")
        );
        assert_eq!(other.bytes(), page.as_slice());
    }
}
