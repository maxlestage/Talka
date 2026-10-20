//! L'iPhone (TalkaKit, en Swift) et le navigateur (son code Rust,
//! `talka_web::v3`) en protocole v3, par un vrai relais : chacun vérifie le
//! paquet de clés publié par l'autre, chacun ouvre une session vers l'autre
//! (les deux en même temps), puis réponses, désordre, doublon, vieux message
//! rejoué (sans réparation), gros contenu dans l'ancien format, session
//! perdue qui guérit.
//!
//! Lancé par `ios/interop-v3.sh`, qui démarre le relais (`cargo run`), ce
//! test et `swift test --filter V3InteropTests` ; les deux côtés s'échangent
//! leurs codes et quelques signaux par des fichiers. Sans
//! `TALKA_V3_INTEROP_DIR`, il ne fait rien.

use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as B64;
use futures_util::{SinkExt, StreamExt};
use serde_json::{Value, json};
use talka_web::crypto::{self, Identity};
use talka_web::model::{Body, Content, Profile, Wire};
use talka_web::v3::{self, Engine, Failure, KeyBundle, Opened};
use tokio_tungstenite::tungstenite::Message;

type Ws =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

fn now_ms() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_millis() as f64
}

/// Le type d'un jeton v3 (0x01 : premier message, 0x02 : message).
fn token_type(token: &str) -> u8 {
    let b64 = &token[v3_head().len()..token.len() - 2];
    B64.decode(b64).unwrap()[0]
}

fn v3_head() -> &'static str {
    "[[BROCOLIS:v3:"
}

/// L'iPhone, vu du navigateur : son code et ses clés.
struct Phone {
    code: String,
    sign: String,
    dh: String,
}

/// Le navigateur : son identité, son état v3, sa connexion, et ce que
/// l'iPhone lui a envoyé.
struct Browser {
    id: Identity,
    v3: Engine,
    /// Le dernier JSON de l'appareil v3 (pour « perdre » les sessions).
    device: String,
    ws: Ws,
    dir: PathBuf,
    /// Les messages reçus : (identifiant, texte, par une session v3).
    texts: Vec<(String, String, bool)>,
    /// Les profils reçus (le premier, puis toute réparation).
    profiles: usize,
    /// Les contenus reçus dans l'ancien format (taille du `data`).
    legacy: Vec<usize>,
    /// Les messages reçus, par type de jeton.
    initials: usize,
    /// Les sessions sont perdues : des messages illisibles sont attendus, le
    /// premier déclenche la réparation.
    lost: bool,
    repaired: bool,
    /// Le paquet de l'iPhone, vérifié.
    bundle: Option<KeyBundle>,
}

async fn next_json(ws: &mut Ws, until: Instant) -> Value {
    loop {
        let left = until.saturating_duration_since(Instant::now());
        let msg = tokio::time::timeout(left, ws.next())
            .await
            .expect("trop long")
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

/// Attend qu'un fichier existe, et le lit.
async fn wait_file(path: &Path) -> String {
    let until = Instant::now() + Duration::from_secs(300);
    loop {
        if let Ok(text) = std::fs::read_to_string(path) {
            let text = text.trim().to_owned();
            if !text.is_empty() {
                return text;
            }
        }
        assert!(Instant::now() < until, "trop long : {}", path.display());
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

fn text(id: &str, t: &str) -> Wire {
    Wire::new(Body::Msg {
        id: id.into(),
        at: now_ms(),
        pn: "Rust".into(),
        content: Content {
            kind: "text".into(),
            text: t.into(),
            ..Default::default()
        },
    })
}

impl Browser {
    /// Le prochain message d'un type donné ; les enveloppes de l'iPhone qui
    /// arrivent entre-temps sont ouvertes et rangées.
    async fn next(&mut self, phone: &Phone, t: &str) -> Value {
        let until = Instant::now() + Duration::from_secs(60);
        loop {
            let v = next_json(&mut self.ws, until).await;
            assert_ne!(v["t"], "error", "{v}");
            if v["t"] == t {
                return v;
            }
            if v["t"] == "msg" {
                self.receive(phone, &v).await;
            }
        }
    }

    /// Ouvre une enveloppe de l'iPhone comme `core::receive`.
    async fn receive(&mut self, phone: &Phone, env: &Value) {
        assert_eq!(env["from"], phone.code.as_str());
        let body = env["body"].as_str().unwrap();
        let plain = crypto::open(&self.id, &phone.code, &phone.dh, body).expect("enveloppe Talka");
        if v3::is_token(&plain) && token_type(&plain) == 1 {
            self.initials += 1;
        }
        let opened = self
            .v3
            .open(&self.id, &phone.code, &phone.dh, body, v3::secs(now_ms()));
        if let Some(device) = self.v3.take_dirty().device {
            self.device = device;
        }
        let (json, by_v3) = match opened {
            Opened::V3(json) => (json, true),
            Opened::Legacy(json) => (json, false),
            Opened::Replay => panic!("l'iPhone n'envoie jamais deux fois le même chiffré"),
            Opened::Failed(Failure::Envelope) => panic!("enveloppe illisible"),
            Opened::Failed(Failure::V3(e)) => {
                // Seule une session perdue est attendue (et réparée une fois,
                // comme `core::repair_v3`).
                assert!(self.lost, "message de l'iPhone illisible : {e}");
                assert!(
                    matches!(format!("{e:?}").as_str(), "SessionInconnue" | "CleInconnue"),
                    "{e:?}"
                );
                if !self.repaired {
                    self.repaired = true;
                    self.v3.forget(&phone.code);
                    let profile = Wire::new(Body::Profile {
                        profile: Profile {
                            name: "Rust réparé".into(),
                            about: String::new(),
                            avatar: String::new(),
                            v: 2,
                        },
                    });
                    let bundle = self.bundle.clone();
                    assert!(self.send_wire(phone, &profile, bundle.as_ref()).await);
                }
                self.got(env).await;
                return;
            }
        };
        if by_v3 && self.repaired {
            self.lost = false;
        }
        let wire: Wire = serde_json::from_str(&json).expect("un Wire");
        match wire.body {
            Body::Msg { id, content, .. } => {
                if by_v3 {
                    self.texts.push((id, content.text, true));
                } else {
                    // Seuls les gros contenus (plus de 200 Kio) gardent
                    // l'ancien format.
                    assert!(json.len() > 200 * 1024, "ancien format inattendu : {json}");
                    self.legacy.push(content.data.len());
                }
            }
            Body::Profile { .. } => {
                assert!(by_v3, "profil dans l'ancien format");
                self.profiles += 1;
            }
            _ => assert!(by_v3, "ancien format inattendu : {json}"),
        }
        self.got(env).await;
    }

    async fn got(&mut self, env: &Value) {
        send(&mut self.ws, json!({ "t": "got", "n": [env["n"]] })).await;
    }

    /// Attend un message de l'iPhone.
    async fn wait_text(&mut self, phone: &Phone, t: &str) {
        let until = Instant::now() + Duration::from_secs(90);
        while !self.texts.iter().any(|(_, x, _)| x == t) {
            let v = next_json(&mut self.ws, until).await;
            assert_ne!(v["t"], "error", "{v}");
            if v["t"] == "msg" {
                self.receive(phone, &v).await;
            }
        }
    }

    /// Le paquet de l'iPhone, demandé au relais et vérifié contre son code
    /// (comme `core::bundle_for`).
    async fn bundle_of(&mut self, phone: &Phone) -> KeyBundle {
        send(
            &mut self.ws,
            json!({ "t": "fetch-bundle", "code": phone.code }),
        )
        .await;
        let b = self.next(phone, "bundle").await;
        assert_ne!(b["none"], true, "l'iPhone a publié son paquet");
        assert_eq!(b["sign"], phone.sign.as_str());
        v3::verify_bundle(
            &phone.code,
            &phone.sign,
            &phone.dh,
            b["bundle"].as_str().unwrap(),
            b["sig"].as_str().unwrap(),
        )
        .expect("paquet signé par l'iPhone, lié à son code")
    }

    /// Chiffre (comme `core::send_now`) ; `true` : un jeton v3.
    fn seal(&mut self, phone: &Phone, wire: &Wire, bundle: Option<&KeyBundle>) -> v3::Sealed {
        let plain = serde_json::to_string(wire).unwrap();
        self.v3
            .seal(&self.id, &phone.code, &phone.dh, bundle, &plain)
            .unwrap()
    }

    async fn send_body(&mut self, phone: &Phone, body: &str) {
        let id = crypto::random_id(8);
        send(
            &mut self.ws,
            json!({ "t": "send", "id": format!("{id}:{}", phone.code), "to": phone.code, "body": body }),
        )
        .await;
    }

    async fn send_wire(&mut self, phone: &Phone, wire: &Wire, bundle: Option<&KeyBundle>) -> bool {
        let sealed = self.seal(phone, wire, bundle);
        self.send_body(phone, &sealed.body).await;
        sealed.v3
    }
}

#[tokio::test]
async fn with_an_iphone() {
    let (Ok(server), Ok(dir)) = (
        std::env::var("TALKA_SERVER"),
        std::env::var("TALKA_V3_INTEROP_DIR"),
    ) else {
        eprintln!("lancé par ios/interop-v3.sh (TALKA_SERVER, TALKA_V3_INTEROP_DIR)");
        return;
    };
    let dir = PathBuf::from(dir);
    let url = format!(
        "{}/ws",
        server
            .trim_end_matches('/')
            .replacen("https://", "wss://", 1)
            .replacen("http://", "ws://", 1)
    );

    // Le navigateur se connecte et publie son paquet (signé par son identité).
    let id = Identity::create(now_ms());
    let (mut ws, _) = tokio_tungstenite::connect_async(&url).await.unwrap();
    let until = Instant::now() + Duration::from_secs(10);
    let challenge = loop {
        let v = next_json(&mut ws, until).await;
        if v["t"] == "challenge" {
            break v;
        }
    };
    send(
        &mut ws,
        json!({ "t": "hello", "sign": id.sign, "dh": id.dh, "sig": id.sign_challenge(challenge["nonce"].as_str().unwrap()) }),
    )
    .await;
    let mut engine = Engine::start(None, v3::secs(now_ms()));
    let device = engine.take_dirty().device.unwrap();
    let mut me = Browser {
        id,
        v3: engine,
        device,
        ws,
        dir: dir.clone(),
        texts: Vec::new(),
        profiles: 0,
        legacy: Vec::new(),
        initials: 0,
        lost: false,
        repaired: false,
        bundle: None,
    };
    let nobody = Phone {
        code: String::new(),
        sign: String::new(),
        dh: String::new(),
    };
    me.next(&nobody, "ready").await;
    let (bundle, sig) = me.v3.published(&me.id).unwrap();
    send(
        &mut me.ws,
        json!({ "t": "publish-bundle", "bundle": bundle, "sig": sig }),
    )
    .await;
    assert_eq!(me.next(&nobody, "published").await["ok"], true);

    // L'iPhone : ses clés, liées à son code (comme `core::keys_for`). Il a
    // écrit son code avant d'attendre le nôtre : il ne nous écrit pas avant.
    let code = wait_file(&dir.join("swift.code")).await;
    send(&mut me.ws, json!({ "t": "lookup", "code": code })).await;
    let keys = me.next(&nobody, "keys").await;
    let phone = Phone {
        code: code.clone(),
        sign: keys["sign"].as_str().unwrap().to_owned(),
        dh: keys["dh"].as_str().unwrap().to_owned(),
    };
    assert!(crypto::verify_keys(&phone.code, &phone.sign, &phone.dh));
    println!("• iPhone (Swift) : {code}");
    std::fs::write(me.dir.join("rust.code"), &me.id.code).unwrap();
    println!("• navigateur (Rust) : {}", me.id.code);

    // Son paquet, signé par l'iPhone : le navigateur le vérifie, et il est
    // hybride (ML-KEM-768).
    let bundle = me.bundle_of(&phone).await;
    assert!(bundle.pq.is_some(), "paquet post-quantique");
    me.bundle = Some(bundle.clone());
    println!("• paquet de l'iPhone vérifié par le navigateur (hybride ML-KEM-768)");

    // L'iPhone a ouvert sa session vers notre paquet ; le navigateur, sans
    // l'avoir lue, ouvre la sienne vers celui de l'iPhone : deux sessions,
    // une dans chaque sens.
    wait_file(&dir.join("swift.sent")).await;
    let first = me.seal(&phone, &text("r1", "Bonjour de Rust 🦀"), Some(&bundle));
    assert!(first.v3);
    me.send_body(&phone, &first.body).await;
    me.wait_text(&phone, "Bonjour du iPhone 📱").await;
    assert!(
        me.initials >= 1,
        "le premier message de l'iPhone ouvre sa session"
    );
    assert!(me.profiles >= 1, "le profil de l'iPhone, par v3");
    println!("• sessions dans les deux sens : chacun lit le premier message de l'autre");

    // Le gros fichier de l'iPhone (plus de 200 Kio) : l'ancien format.
    let until = Instant::now() + Duration::from_secs(60);
    while me.legacy.is_empty() {
        let v = next_json(&mut me.ws, until).await;
        if v["t"] == "msg" {
            me.receive(&phone, &v).await;
        }
    }
    assert!(me.legacy[0] > 300 * 1024);
    println!("• gros fichier de l'iPhone : ancien format, lu");

    // Dans le désordre, et un doublon (le même chiffré, renvoyé).
    let sealed: Vec<_> = (1..=3)
        .map(|i| me.seal(&phone, &text(&format!("n{i}"), &format!("n°{i}")), None))
        .collect();
    assert!(sealed.iter().all(|s| s.v3));
    for i in [2, 0, 1, 1] {
        me.send_body(&phone, &sealed[i].body).await;
    }
    me.wait_text(&phone, "reçu dans le désordre").await;
    println!("• désordre (3, 1, 2) et doublon : l'iPhone les range une fois");

    // Des allers-retours (le cliquet tourne à chaque fois), puis un vieux
    // message rejoué : l'iPhone l'ignore, sans réparer (pas de profil
    // renvoyé), et la session tient.
    for i in 0..18 {
        assert!(
            me.send_wire(&phone, &text(&format!("p{i}"), &format!("ping {i}")), None)
                .await
        );
        me.wait_text(&phone, &format!("pong {i}")).await;
    }
    // (L'iPhone a pu renvoyer son profil à notre premier message : c'est
    // ce qui suit qui compte.)
    let profiles = me.profiles;
    me.send_body(&phone, &sealed[0].body).await;
    me.send_body(&phone, &first.body).await;
    assert!(me.send_wire(&phone, &text("ar", "après rejeu"), None).await);
    me.wait_text(&phone, "après rejeu : rien n'a bougé").await;
    assert_eq!(
        me.profiles, profiles,
        "un vieux message rejoué ne répare rien"
    );
    println!("• vieux message rejoué : ignoré, sans réparation");

    // Le navigateur perd ses sessions (données effacées, appareil gardé) :
    // le message suivant de l'iPhone est illisible, le navigateur répare
    // (son profil, par une session neuve), l'iPhone l'accepte.
    me.v3 = Engine::start(Some(&me.device), v3::secs(now_ms()));
    me.lost = true;
    std::fs::write(dir.join("rust.lost"), "1").unwrap();
    me.wait_text(&phone, "après la réparation").await;
    assert!(me.repaired && !me.lost);
    assert_eq!(me.profiles, profiles, "l'iPhone n'a rien eu à réparer");
    assert!(me.send_wire(&phone, &text("fin", "fin"), None).await);
    println!("• session perdue côté navigateur : réparée, l'iPhone suit");

    // Puis l'iPhone perd les siennes : notre message suivant lui est
    // illisible, il répare à son tour (son profil, par une session neuve
    // vers notre paquet), et nous lit de nouveau.
    wait_file(&dir.join("swift.lost")).await;
    let initials = me.initials;
    assert!(
        me.send_wire(&phone, &text("tp", "après ta perte"), None)
            .await
    );
    let until = Instant::now() + Duration::from_secs(60);
    while me.profiles == profiles {
        let v = next_json(&mut me.ws, until).await;
        if v["t"] == "msg" {
            me.receive(&phone, &v).await;
        }
    }
    assert_eq!(me.profiles, profiles + 1, "une seule réparation");
    assert!(me.initials > initials, "par une session neuve");
    assert!(me.send_wire(&phone, &text("rb", "re-bonjour"), None).await);
    println!("• session perdue côté iPhone : l'iPhone répare, le navigateur suit");

    // Un ancien client (sans paquet v3) : l'iPhone lui écrit dans l'ancien
    // format, exactement, et le lit.
    let old_id = Identity::create(now_ms());
    let (mut old, _) = tokio_tungstenite::connect_async(&url).await.unwrap();
    let until = Instant::now() + Duration::from_secs(10);
    let challenge = loop {
        let v = next_json(&mut old, until).await;
        if v["t"] == "challenge" {
            break v;
        }
    };
    send(
        &mut old,
        json!({ "t": "hello", "sign": old_id.sign, "dh": old_id.dh, "sig": old_id.sign_challenge(challenge["nonce"].as_str().unwrap()) }),
    )
    .await;
    std::fs::write(dir.join("old.code"), &old_id.code).unwrap();
    let until = Instant::now() + Duration::from_secs(60);
    loop {
        let v = next_json(&mut old, until).await;
        if v["t"] != "msg" {
            continue;
        }
        let plain = crypto::open(&old_id, &phone.code, &phone.dh, v["body"].as_str().unwrap())
            .expect("l'ancien format");
        assert!(!v3::is_token(&plain), "un jeton v3 pour un ancien client");
        send(&mut old, json!({ "t": "got", "n": [v["n"]] })).await;
        let wire: Wire = serde_json::from_str(&plain).unwrap();
        if let Body::Msg { content, .. } = wire.body
            && content.text == "pour l'ancien"
        {
            break;
        }
    }
    let reply = serde_json::to_string(&text("o1", "de l'ancien")).unwrap();
    let body = crypto::seal(&old_id, &phone.code, &phone.dh, &reply).unwrap();
    send(
        &mut old,
        json!({ "t": "send", "id": format!("o1:{}", phone.code), "to": phone.code, "body": body }),
    )
    .await;
    println!("• un ancien client : l'ancien format, dans les deux sens");

    // L'appareil v3 part dans la sauvegarde : celui du navigateur se relit
    // sur l'iPhone, et celui de l'iPhone ici (même paquet publié).
    std::fs::write(dir.join("rust.device"), &me.device).unwrap();
    wait_file(&dir.join("swift.done")).await;
    let swift_device = std::fs::read_to_string(dir.join("swift.device")).unwrap();
    let restored = Engine::start(Some(&swift_device), v3::secs(now_ms()));
    let (b, sig) = restored.published(&me.id).unwrap();
    let restored_bundle = v3::verify_bundle(&me.id.code, &me.id.sign, &me.id.dh, &b, &sig).unwrap();
    assert_eq!(restored_bundle.identity, bundle.identity);
    assert_eq!(restored_bundle.spk, bundle.spk);
    assert_eq!(
        restored_bundle.pq.map(|p| p.key),
        bundle.pq.clone().map(|p| p.key)
    );
    println!("• l'appareil v3 de chacun se relit chez l'autre (sauvegardes)");
    // Tout ce que l'iPhone a écrit (hors gros fichier) est passé par v3.
    assert!(me.texts.iter().all(|(_, _, by_v3)| *by_v3));
    println!("✓ iPhone et navigateur se parlent en v3");
}
