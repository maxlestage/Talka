//! Le protocole v3 par le vrai relais : publication et demande du paquet de
//! clés, refus d'un paquet mal signé ou trop gros, deux clients Rust (le
//! code du navigateur, `talka_web::v3`) qui échangent en sessions à double
//! cliquet, et un client « ancien » qui reçoit toujours l'ancien format.

use std::time::Duration;

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use talka_web::crypto::{self, Identity};
use talka_web::model::{Body, Content, Wire};
use talka_web::v3::{self, Engine, Opened, Protection};
use tokio_tungstenite::tungstenite::Message;

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

/// Un appareil : l'identité Talka et, s'il sait faire v3, son état v3.
struct Client {
    id: Identity,
    v3: Option<Engine>,
    ws: Ws,
    /// Ce qui est arrivé pendant qu'on attendait autre chose.
    early: Vec<Value>,
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

/// Le prochain message d'un type donné (la présence, les pings… passent).
async fn next_of(ws: &mut Ws, t: &str) -> Value {
    loop {
        let v = next_json(ws).await;
        if v["t"] == t {
            return v;
        }
    }
}

async fn send(ws: &mut Ws, value: Value) {
    ws.send(Message::text(value.to_string())).await.unwrap();
}

async fn connect(port: u16, id: &Identity) -> Ws {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws"))
        .await
        .unwrap();
    let challenge = next_of(&mut ws, "challenge").await;
    let nonce = challenge["nonce"].as_str().unwrap();
    send(
        &mut ws,
        json!({ "t": "hello", "sign": id.sign, "dh": id.dh, "sig": id.sign_challenge(nonce) }),
    )
    .await;
    assert_eq!(next_of(&mut ws, "ready").await["code"], id.code.as_str());
    ws
}

impl Client {
    /// Un client qui sait faire v3 publie son paquet en se connectant.
    async fn new(port: u16, v3: bool) -> Client {
        let id = Identity::create(0.0);
        let ws = connect(port, &id).await;
        let mut c = Client {
            v3: v3.then(|| Engine::start(None, 1_000)),
            id,
            ws,
            early: Vec::new(),
        };
        if v3 {
            c.publish().await;
        }
        c
    }

    /// Le prochain message d'un type donné ; les autres sont gardés (sauf
    /// la présence et les `pong`).
    async fn next(&mut self, t: &str) -> Value {
        if let Some(i) = self.early.iter().position(|v| v["t"] == t) {
            return self.early.remove(i);
        }
        loop {
            let v = next_json(&mut self.ws).await;
            if v["t"] == t {
                return v;
            }
            if v["t"] == "msg" || v["t"] == "bundle" {
                self.early.push(v);
            }
        }
    }

    async fn publish(&mut self) {
        let (bundle, sig) = self.v3.as_ref().unwrap().published(&self.id).unwrap();
        send(
            &mut self.ws,
            json!({ "t": "publish-bundle", "bundle": bundle, "sig": sig }),
        )
        .await;
        assert_eq!(self.next("published").await["ok"], true);
    }

    async fn reconnect(&mut self, port: u16) {
        self.ws = connect(port, &self.id).await;
        self.early.clear();
        // Comme le navigateur : le paquet est republié à chaque connexion.
        if self.v3.is_some() {
            self.publish().await;
        }
    }

    /// Le paquet d'un contact, vérifié contre son code (comme `core::bundle_for`).
    async fn bundle_of(&mut self, to: &Identity) -> Option<v3::KeyBundle> {
        send(
            &mut self.ws,
            json!({ "t": "fetch-bundle", "code": to.code }),
        )
        .await;
        let b = self.next("bundle").await;
        if b["none"] == true {
            return None;
        }
        // Les clés du relais doivent être celles du code.
        assert_eq!(b["sign"], to.sign.as_str());
        Some(
            v3::verify_bundle(
                &to.code,
                &to.sign,
                &to.dh,
                b["bundle"].as_str()?,
                b["sig"].as_str()?,
            )
            .expect("paquet valide"),
        )
    }

    /// Chiffre un `Wire` pour `to` (comme `core::send_now`) et l'envoie.
    async fn send_wire(&mut self, to: &Identity, wire: &Wire, msg_id: &str) -> bool {
        let plain = serde_json::to_string(wire).unwrap();
        let has_session = self.v3.as_ref().is_some_and(|e| e.has_session(&to.code));
        let bundle = if self.v3.is_some() && !has_session {
            self.bundle_of(to).await
        } else {
            None
        };
        let sealed = match self.v3.as_mut() {
            Some(e) => e
                .seal(&self.id, &to.code, &to.dh, bundle.as_ref(), &plain)
                .unwrap(),
            None => v3::Sealed {
                body: crypto::seal(&self.id, &to.code, &to.dh, &plain).unwrap(),
                v3: false,
            },
        };
        send(
            &mut self.ws,
            json!({ "t": "send", "id": format!("{msg_id}:{}", to.code), "to": to.code, "body": sealed.body }),
        )
        .await;
        assert_eq!(self.next("ack").await["t"], "ack");
        sealed.v3
    }

    /// Le prochain message du relais (sans l'ouvrir).
    async fn next_msg(&mut self) -> Value {
        self.next("msg").await
    }

    /// Ouvre une enveloppe comme `core::receive` (un ancien client :
    /// `crypto::open`, puis le `Wire`).
    fn open(&mut self, from: &Identity, env: &Value) -> Opened {
        let body = env["body"].as_str().unwrap();
        match self.v3.as_mut() {
            Some(e) => e.open(&self.id, &from.code, &from.dh, body, 2_000),
            None => match crypto::open(&self.id, &from.code, &from.dh, body) {
                Some(plain) => Opened::Legacy(plain),
                None => Opened::Failed(v3::Failure::Envelope),
            },
        }
    }

    async fn got(&mut self, env: &Value) {
        send(&mut self.ws, json!({ "t": "got", "n": [env["n"]] })).await;
    }
}

fn text(id: &str, t: &str) -> Wire {
    Wire::new(Body::Msg {
        id: id.into(),
        at: 1.0,
        pn: String::new(),
        content: Content {
            kind: "text".into(),
            text: t.into(),
            ..Default::default()
        },
    })
}

/// Le texte d'un message, ou le `Wire` illisible pour l'application.
fn text_of(opened: &Opened) -> Option<String> {
    let (Opened::V3(json) | Opened::Legacy(json)) = opened else {
        return None;
    };
    match serde_json::from_str::<Wire>(json).ok()?.body {
        Body::Msg { content, .. } => Some(content.text),
        _ => None,
    }
}

async fn relay(port: u16) {
    let mut app = talka::app();
    app.port(port).quiet();
    tokio::spawn(app.serve());
    tokio::time::sleep(Duration::from_millis(300)).await;
}

#[tokio::test]
async fn bundle_publish_fetch_and_refusals() {
    let port = 4518;
    relay(port).await;
    let mut alice = Client::new(port, true).await;
    let mut bob = Client::new(port, false).await;

    // Bob demande le paquet d'Alice : il est lié à son code.
    let first = bob.bundle_of(&alice.id).await.expect("paquet publié");
    assert!(first.pq.is_some(), "hybride ML-KEM-768");

    // Un code inconnu, ou connu sans paquet (un ancien client) : rien.
    send(
        &mut alice.ws,
        json!({ "t": "fetch-bundle", "code": bob.id.code }),
    )
    .await;
    assert_eq!(alice.next("bundle").await["none"], true);
    send(
        &mut alice.ws,
        json!({ "t": "fetch-bundle", "code": "PAS-UN-CODE" }),
    )
    .await;
    assert_eq!(alice.next("bundle").await["none"], true);

    // Un paquet signé par une autre clé que celle du code : refusé.
    let (bundle, _) = alice.v3.as_ref().unwrap().published(&alice.id).unwrap();
    let forged = bob
        .id
        .sign(&v3::bundle_message(&B64.decode(&bundle).unwrap()));
    send(
        &mut alice.ws,
        json!({ "t": "publish-bundle", "bundle": bundle, "sig": forged }),
    )
    .await;
    let r = alice.next("published").await;
    assert_eq!(
        (r["ok"].as_bool(), r["error"].as_str()),
        (Some(false), Some("signature"))
    );
    // Signé sans le contexte : refusé aussi.
    let raw_sig = alice.id.sign(&B64.decode(&bundle).unwrap());
    send(
        &mut alice.ws,
        json!({ "t": "publish-bundle", "bundle": bundle, "sig": raw_sig }),
    )
    .await;
    assert_eq!(alice.next("published").await["error"], "signature");

    // Trop gros (même bien signé) : refusé.
    let big = vec![7u8; v3::MAX_BUNDLE + 1];
    let sig = alice.id.sign(&v3::bundle_message(&big));
    send(
        &mut alice.ws,
        json!({ "t": "publish-bundle", "bundle": B64.encode(&big), "sig": sig }),
    )
    .await;
    assert_eq!(alice.next("published").await["error"], "size");

    // Les refus n'ont rien changé.
    assert_eq!(bob.bundle_of(&alice.id).await, Some(first.clone()));

    // Le relais ne relit pas le paquet (c'est l'appareil qui le vérifie) :
    // un paquet abîmé mais signé passe le relais, puis le client le refuse.
    let mut broken = B64.decode(&bundle).unwrap();
    broken[50] ^= 1;
    let sig = alice.id.sign(&v3::bundle_message(&broken));
    send(
        &mut alice.ws,
        json!({ "t": "publish-bundle", "bundle": B64.encode(&broken), "sig": sig }),
    )
    .await;
    assert_eq!(alice.next("published").await["ok"], true);
    send(
        &mut bob.ws,
        json!({ "t": "fetch-bundle", "code": alice.id.code }),
    )
    .await;
    let b = bob.next("bundle").await;
    assert!(
        v3::verify_bundle(
            &alice.id.code,
            &alice.id.sign,
            &alice.id.dh,
            b["bundle"].as_str().unwrap(),
            b["sig"].as_str().unwrap()
        )
        .is_none()
    );

    // Republier remplace : après la rotation, le nouveau paquet.
    alice.v3.as_mut().unwrap().maintain(1_000 + 7 * 24 * 3600);
    alice.publish().await;
    let second = bob.bundle_of(&alice.id).await.unwrap();
    assert_ne!(second.spk_id, first.spk_id);

    // Une connexion du même code qui ne publie rien (une ancienne version
    // avec la même identité) : le paquet est oublié, plus de jetons v3 qu'elle
    // ne saurait lire. Republié, il revient.
    let old = connect(port, &alice.id).await;
    assert_eq!(bob.bundle_of(&alice.id).await, None);
    drop(old);
    alice.publish().await;
    assert_eq!(bob.bundle_of(&alice.id).await, Some(second.clone()));

    // Il reste connu quand Alice est partie (comme ses clés publiques).
    let alice_id = alice.id.clone();
    drop(alice);
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(bob.bundle_of(&alice_id).await, Some(second));
    assert_eq!(bob.bundle_of(&Identity::create(0.0)).await, None);
}

#[tokio::test]
async fn two_clients_talk_v3_through_the_relay() {
    let port = 4519;
    relay(port).await;
    let mut alice = Client::new(port, true).await;
    let mut bob = Client::new(port, true).await;
    let bob_id = bob.id.clone();
    let alice_id = alice.id.clone();

    // Premier message : une session neuve vers le paquet de Bob.
    assert!(
        alice
            .send_wire(&bob_id, &text("m1", "Salut 🥦"), "m1")
            .await
    );
    let env = bob.next_msg().await;
    // Le relais ne voit que l'enveloppe de Talka, comme avant.
    assert_eq!(env["from"], alice_id.code.as_str());
    assert!(!env["body"].as_str().unwrap().contains("BROCOLIS"));
    let opened = bob.open(&alice_id, &env);
    assert!(matches!(opened, Opened::V3(_)));
    assert_eq!(text_of(&opened).as_deref(), Some("Salut 🥦"));
    bob.got(&env).await;
    assert_eq!(
        bob.v3.as_ref().unwrap().session_protection(&alice_id.code),
        Some(Protection::Ratchet { pq: true })
    );

    // La réponse : par la session, sans redemander le paquet.
    assert!(
        bob.send_wire(&alice_id, &text("r1", "Bien reçu"), "r1")
            .await
    );
    let env = alice.next_msg().await;
    assert_eq!(
        text_of(&alice.open(&bob_id, &env)).as_deref(),
        Some("Bien reçu")
    );
    alice.got(&env).await;

    // Dans le désordre : trois messages, ouverts 3, 1, 2.
    for i in 2..5 {
        assert!(
            alice
                .send_wire(
                    &bob_id,
                    &text(&format!("m{i}"), &format!("n°{i}")),
                    &format!("m{i}")
                )
                .await
        );
    }
    let mut envs = Vec::new();
    for _ in 0..3 {
        envs.push(bob.next_msg().await);
    }
    for i in [2, 0, 1] {
        assert_eq!(
            text_of(&bob.open(&alice_id, &envs[i])),
            Some(format!("n°{}", i + 2))
        );
    }
    // Rangés, sauf le dernier : Bob se reconnecte, le relais le renvoie, et
    // ce doublon est ignoré sans bruit.
    bob.got(&envs[0]).await;
    bob.got(&envs[1]).await;
    tokio::time::sleep(Duration::from_millis(100)).await;
    bob.reconnect(port).await;
    let again = bob.next_msg().await;
    assert_eq!(again["n"], envs[2]["n"]);
    assert_eq!(bob.open(&alice_id, &again), Opened::Replay);
    bob.got(&again).await;

    // Un nouvel essai d'envoi chiffre à nouveau (autre numéro de message) :
    // le même message arrive deux fois, lisible deux fois ; l'application
    // le range par son identifiant (`get_message` : « déjà reçu »).
    let wire = text("m5", "renvoyé");
    alice.send_wire(&bob_id, &wire, "m5").await;
    alice.send_wire(&bob_id, &wire, "m5").await;
    let (e1, e2) = (bob.next_msg().await, bob.next_msg().await);
    assert_ne!(e1["body"], e2["body"]);
    let (o1, o2) = (bob.open(&alice_id, &e1), bob.open(&alice_id, &e2));
    assert_eq!(text_of(&o1), text_of(&o2));

    // Les deux côtés écrivent en même temps à un troisième, qui n'a encore
    // aucune session : chacun lit l'autre.
    let mut carol = Client::new(port, true).await;
    let carol_id = carol.id.clone();
    assert!(
        carol
            .send_wire(&bob_id, &text("c1", "de Carol"), "c1")
            .await
    );
    assert!(bob.send_wire(&carol_id, &text("b1", "de Bob"), "b1").await);
    let to_bob = bob.next_msg().await;
    let to_carol = carol.next_msg().await;
    assert_eq!(
        text_of(&bob.open(&carol_id, &to_bob)).as_deref(),
        Some("de Carol")
    );
    assert_eq!(
        text_of(&carol.open(&bob_id, &to_carol)).as_deref(),
        Some("de Bob")
    );
    for i in 0..3 {
        bob.send_wire(&carol_id, &text(&format!("x{i}"), "x"), "x")
            .await;
        carol
            .send_wire(&bob_id, &text(&format!("y{i}"), "y"), "y")
            .await;
        let (to_carol, to_bob) = (carol.next_msg().await, bob.next_msg().await);
        assert_eq!(
            text_of(&carol.open(&bob_id, &to_carol)).as_deref(),
            Some("x")
        );
        assert_eq!(text_of(&bob.open(&carol_id, &to_bob)).as_deref(), Some("y"));
    }
}

#[tokio::test]
async fn an_old_client_still_gets_the_old_format() {
    let port = 4520;
    relay(port).await;
    let mut alice = Client::new(port, true).await;
    let mut old = Client::new(port, false).await;
    let (alice_id, old_id) = (alice.id.clone(), old.id.clone());

    // L'ancien client n'a pas publié de paquet : l'ancien format, exactement.
    assert!(
        !alice
            .send_wire(&old_id, &text("o1", "pour l'ancien"), "o1")
            .await
    );
    let env = old.next_msg().await;
    assert_eq!(
        text_of(&old.open(&alice_id, &env)).as_deref(),
        Some("pour l'ancien")
    );
    assert_eq!(
        alice.v3.as_ref().unwrap().session_protection(&old_id.code),
        None
    );

    // Et ce qu'il envoie se lit toujours.
    assert!(
        !old.send_wire(&alice_id, &text("o2", "de l'ancien"), "o2")
            .await
    );
    let env = alice.next_msg().await;
    let opened = alice.open(&old_id, &env);
    assert!(matches!(opened, Opened::Legacy(_)));
    assert_eq!(text_of(&opened).as_deref(), Some("de l'ancien"));

    // Un jeton v3 reçu par erreur par un ancien client : son enveloppe
    // s'ouvre, mais ce n'est pas un `Wire` : il l'ignore.
    let mut bob = Client::new(port, true).await;
    let bob_id = bob.id.clone();
    bob.send_wire(&alice_id, &text("b1", "v3"), "b1").await;
    let env = alice.next_msg().await;
    let plain = crypto::open(
        &alice_id,
        &bob_id.code,
        &bob_id.dh,
        env["body"].as_str().unwrap(),
    )
    .unwrap();
    assert!(v3::is_token(&plain));
    assert!(serde_json::from_str::<Wire>(&plain).is_err());
}

/// Le navigateur et le serveur signent et vérifient le même message.
#[test]
fn browser_and_server_agree_on_the_bundle_signature() {
    assert_eq!(
        v3::bundle_message(b"abc"),
        talka::code::bundle_message(b"abc")
    );
    assert_eq!(v3::MAX_BUNDLE, talka::relay::MAX_BUNDLE);
}
