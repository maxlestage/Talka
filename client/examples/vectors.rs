//! Des vecteurs de test pour l'application iOS : ce que produit le navigateur
//! (le même code Rust), que TalkaKit (Swift) doit relire à l'identique.
//!
//! ```sh
//! cargo run -p talka-web --example vectors > ios/Tests/TalkaKitTests/vectors.json
//! ```

use std::collections::BTreeMap;

use serde_json::json;
use talka_web::crypto::{self, Identity};
use talka_web::jetons;
use talka_web::model::*;

fn main() {
    let a = Identity::create(1_700_000_000_000.0);
    let b = Identity::create(1_700_000_000_000.0);

    let msg = Wire {
        body: Body::Msg {
            id: "m1".into(),
            at: 1_700_000_000_123.0,
            pn: "Léa".into(),
            content: Content {
                kind: "text".into(),
                text: "Salut 👋 *Talka*".into(),
                reply: Some(Reply {
                    id: "m0".into(),
                    from: a.code.clone(),
                    text: "avant".into(),
                }),
                ..Default::default()
            },
        },
        grp: Some(GroupInfo {
            id: "g1".into(),
            name: "Les amis".into(),
            members: vec![a.code.clone(), b.code.clone()],
            admins: vec![a.code.clone()],
            avatar: String::new(),
            v: 3,
        }),
        g: None,
        ttl: Some(86_400),
    };
    let wires = vec![
        msg.clone(),
        Wire {
            body: Body::Receipt {
                st: "read".into(),
                ids: vec!["m1".into(), "m2".into()],
            },
            grp: None,
            g: Some("g1".into()),
            ttl: None,
        },
        Wire::new(Body::React {
            id: "m1".into(),
            emoji: "❤️".into(),
        }),
        Wire::new(Body::Edit {
            id: "m1".into(),
            text: "modifié".into(),
        }),
        Wire::new(Body::Delete { id: "m1".into() }),
        Wire::new(Body::Typing { on: true }),
        Wire::new(Body::Profile {
            profile: Profile {
                name: "Léa".into(),
                about: "Disponible".into(),
                avatar: String::new(),
                v: 2,
            },
            jeton: String::new(),
        }),
        Wire::new(Body::Group {
            op: "update".into(),
            note: "Vous avez renommé le groupe.".into(),
        }),
        Wire::new(Body::Timer { secs: 604_800 }),
        Wire::new(Body::Status {
            id: "s1".into(),
            kind: "text".into(),
            text: "Vacances".into(),
            bg: "#008069".into(),
            data: String::new(),
            at: 1.0,
        }),
        Wire::new(Body::StatusSeen { id: "s1".into() }),
        Wire::new(Body::StatusDelete { id: "s1".into() }),
        Wire::new(Body::Call {
            op: "ice".into(),
            call_id: "c1".into(),
            video: true,
            sdp: String::new(),
            cand: Some(
                json!({ "candidate": "candidate:1 1 udp 1 10.0.0.1 9 typ host", "sdpMid": "0", "sdpMLineIndex": 0 }),
            ),
        }),
        // Une capsule temporelle : un message scellé jusqu'à `open_at`.
        Wire::new(Body::Msg {
            id: "m9".into(),
            at: 1_700_000_000_000.0,
            pn: String::new(),
            content: Content {
                kind: "text".into(),
                text: "À ouvrir plus tard".into(),
                open_at: 1_800_000_000_000.0,
                ..Default::default()
            },
        }),
    ];
    let wire_json: Vec<String> = wires
        .iter()
        .map(|w| serde_json::to_string(w).unwrap())
        .collect();
    let plain = &wire_json[0];
    let sealed = crypto::seal(&a, &b.code, &b.dh, plain).unwrap();
    let pin = crypto::hash_pin("2580");
    let backup_data = json!({ "app": "talka", "at": 1.0, "data": { "kv": [["identity", serde_json::to_string(&a).unwrap()]] } }).to_string();
    let backup = crypto::encrypt_backup("motdepasse", &backup_data).unwrap();

    let mut reactions = BTreeMap::new();
    reactions.insert(b.code.clone(), "👍".to_owned());
    let message = Message {
        chat: b.code.clone(),
        id: "m1".into(),
        from: a.code.clone(),
        at: 1.0,
        kind: "image".into(),
        data: "data:image/jpeg;base64,AAAA".into(),
        w: 4,
        h: 3,
        to: vec![b.code.clone()],
        sent_to: vec![b.code.clone()],
        reactions,
        exp: Some(2.0),
        bro: true,
        ..Default::default()
    };
    let chat = Chat {
        id: "g:g1".into(),
        brocolis: vec!["k1".into()],
        group: true,
        name: "Les amis".into(),
        members: vec![a.code.clone(), b.code.clone()],
        last: Some(Last {
            id: "m1".into(),
            from: a.code.clone(),
            text: "📷 Photo".into(),
            at: 1.0,
            kind: "image".into(),
        }),
        ..Default::default()
    };
    let contact = Contact {
        code: b.code.clone(),
        name: "Bob".into(),
        saved: true,
        sign: b.sign.clone(),
        dh: b.dh.clone(),
        ..Default::default()
    };
    let status = StatusItem {
        owner: b.code.clone(),
        id: "s1".into(),
        kind: "text".into(),
        text: "Vacances".into(),
        at: 1.0,
        ..Default::default()
    };
    let call = CallEntry {
        id: "c1".into(),
        peer: b.code.clone(),
        video: true,
        incoming: true,
        at: 1.0,
        dur: 42.0,
        missed: false,
    };
    let out_item = OutItem {
        id: "m1:X".into(),
        to: b.code.clone(),
        payload: plain.clone(),
        chat: b.code.clone(),
        msg_id: "m1".into(),
        seq: 1.0,
        boot: "B00T".into(),
    };

    // Brocolis 🥦 : des jetons du navigateur, que l'iPhone doit ouvrir.
    let (k1, k2) = (jetons::generate_key(), jetons::generate_key());
    let keys = [k1.as_str(), k2.as_str()].map(|k| jetons::key_from_b64(k).unwrap());
    let brocolis = json!({
        "keys": [
            { "id": "k1", "name": "Bob", "key": k1 },
            { "id": "k2", "name": "Travail", "key": k2 },
        ],
        "v1": jetons::seal("Le code du portail est 4521", &keys[..1]),
        "v1_plain": "Le code du portail est 4521",
        "v2": jetons::seal("À tous 🥦", &keys),
        "v2_plain": "À tous 🥦",
        "marked": jetons::seal("On se voit ((au parc)) demain", &keys[1..]),
        "marked_plain": "On se voit au parc demain",
        "fingerprint_k1": jetons::fingerprint(&k1),
    });

    let vectors = json!({
        "brocolis": brocolis,
        "code_of_empty": crypto::code_of(&[], &[]),
        "a": a,
        "b": b,
        "plain": plain,
        "sealed_a_to_b": sealed,
        "challenge": { "nonce": "abc", "sig": a.sign_challenge("abc") },
        "safety": crypto::safety_number(&a.code, &b.code),
        "pin": { "pin": "2580", "hash": pin },
        "backup": { "password": "motdepasse", "file": backup, "plain": backup_data },
        "wires": wire_json,
        "store": {
            "message": serde_json::to_string(&message).unwrap(),
            "chat": serde_json::to_string(&chat).unwrap(),
            "contact": serde_json::to_string(&contact).unwrap(),
            "status": serde_json::to_string(&status).unwrap(),
            "call": serde_json::to_string(&call).unwrap(),
            "outbox": serde_json::to_string(&out_item).unwrap(),
            "settings": serde_json::to_string(&Settings::default()).unwrap(),
            "profile": serde_json::to_string(&Profile { name: "Léa".into(), about: "Salut".into(), avatar: String::new(), v: 1 }).unwrap(),
        },
    });
    println!("{}", serde_json::to_string_pretty(&vectors).unwrap());
}
