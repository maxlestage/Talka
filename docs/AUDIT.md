# Talka — dossier pour un audit indépendant

Ce document prépare le travail d'auditeurs extérieurs : ce qu'il faut
regarder, où, et comment tout reconstruire et tester. Les revues faites
jusqu'ici sont **internes** (voir [SECURITE.md §8](SECURITE.md)) : elles ne
remplacent pas un audit indépendant.

## 1. Ce qu'est Talka

Une messagerie sans numéro ni e-mail. Un appareil = une identité (deux clés
P-256 : signature et échange) ; son **code** (10 caractères) est l'empreinte
de ses clés publiques. Trois parties :

| Partie | Langage | Où |
|---|---|---|
| Relais (serveur) | Rust (Paquets / Vitesse) | `src/` (`relay.rs`, `push.rs`, `code.rs`, `lib.rs`) |
| Navigateur | Rust → WebAssembly | `client/src/` (`crypto.rs`, `v3.rs`, `core.rs`, `store.rs`, `model.rs`, `push.rs`) |
| iPhone | Swift (CryptoKit) | `ios/Sources/TalkaKit/` (`Crypto.swift`, `V3.swift`, `Engine.swift`, `Store.swift`, `StoreVault.swift`) |

Le protocole v3 (double cliquet, établissement hybride X25519 + ML-KEM-768)
vient de la crate `brocolis-proto` et de son portage Swift `BrocolisProto`
(dépôt `maxlestage/Messagerie-`, révisions épinglées dans `client/Cargo.toml`
et `ios/Package.swift`).

## 2. Modèle de menace

[SECURITE.md §1](SECURITE.md). En résumé : le relais est **non fiable**
(il peut lire, garder, rejouer, retarder, supprimer tout ce qui passe) ; le
réseau aussi ; un contact peut être malveillant ; un appareil volé ou une
copie de ses fichiers ne doit rien livrer sans l'utilisateur.

## 3. Ce que nous demandons de vérifier, par priorité

1. **Confidentialité et authenticité des messages** : enveloppes
   (`crypto::seal`/`open`), protocole v3 (`v3.rs`, `V3.swift`), expéditeur
   scellé (`seal_sender`/`open_sealed`), pièces jointes en v3
   (`split_attachment`/`join_attachment`), refus de l'ancien format et
   pierres tombales (`core::receive`, `Engine.receive`).
2. **Identité** : dérivation du code, empreinte des invitations
   (`fingerprint`), numéro de sécurité (`safety_number`), vérification des
   clés (`verify_keys`, `keys_for`), règle « un code garde ses clés » du
   relais (`Relay::taken`).
3. **Mémoire chiffrée au repos** : `store.rs` (clé d'emballage WebCrypto non
   exportable, protection par code Argon2id ou passkey PRF), `Store.swift` +
   `StoreVault.swift` (trousseau, Face ID, `ThisDeviceOnly`).
4. **Relais** : authentification par défi, jetons de remise (un par contact,
   révocation), limites (débits, files bornées, boîtes, IP), Web Push
   (RFC 8291, liste fermée d'hôtes), en-têtes et CSP (`lib.rs`).
5. **Application** : contenu reçu (`safe_data`, citations, groupes,
   messages système), service worker des notifications (`push.rs`,
   `SERVICE_WORKER`), passkey (`passkey.rs`).

## 4. Reconstruire et tester

```sh
# Serveur et navigateur
cargo fmt --all --check
cargo clippy --workspace --all-targets -- -D warnings
cargo clippy -p talka-web --target wasm32-unknown-unknown -- -D warnings
cargo test --workspace
cargo xtask web && cargo run          # http://localhost:3000/app

# iPhone (macOS, ou Linux avec Swift 6)
cd ios && swift test

# Navigateur ↔ iPhone sur un vrai serveur (Playwright + swift test)
cargo run &   # port 3000
cd ios && TALKA_SERVER=http://127.0.0.1:3000 node interop.mjs
```

**Vecteurs communs** : `cargo run -p talka-web --example vectors` écrit
`ios/Tests/TalkaKitTests/vectors.json` (identités, enveloppes, expéditeur
scellé, jetons, aperçus, pièces jointes, Argon2id, sauvegardes, numéro de
sécurité, empreintes) ; l'iPhone les relit octet pour octet
(`CompatTests.swift`).

La CI (`.github/workflows/`) refait tout cela à chaque PR, plus
`cargo audit` (`securite.yml`) et la compilation de l'application iOS.

## 5. Limites connues (à ne pas signaler comme nouvelles)

- Le relais voit qui est connecté, l'adresse IP, les codes suivis
  (présence), les demandes de clés, et le code du destinataire de chaque
  envoi (les boîtes aux lettres tournantes sont prévues).
- Un code ne compte que 50 bits : la sécurité repose sur l'empreinte des
  invitations et le numéro de sécurité (clés complètes), pas sur le code.
- Les aperçus de notification n'ont pas de confidentialité persistante.
- Avec un contact qui n'a pas la dernière version, l'ancien format (sans
  protection contre le rejeu) reste accepté.
- Un code de verrouillage à 6 chiffres reste devinable hors ligne par qui a
  copié les fichiers du navigateur (Argon2id le ralentit) ; la passkey et
  Face ID ne le sont pas.

## 6. Contact

Les résultats, en privé : [SECURITY.md](../SECURITY.md).
