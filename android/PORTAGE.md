# Talka pour Android — le portage

L'application Android native de Talka, en Kotlin (Jetpack Compose), avec
toutes les fonctions de l'iPhone. Elle parle exactement le même protocole
que le navigateur (`client/`) et l'iPhone (`ios/`) : chaque pièce est
vérifiée contre les vecteurs produits par le code Rust du navigateur.

Bibliothèques : AndroidX / Jetpack Compose, OkHttp (WebSocket),
BouncyCastle (X25519, Ed25519, ML-KEM-768, Argon2id), WebRTC (appels),
kotlinx.serialization. Rien d'autre que du Kotlin pour le code de l'app.

## Modules

| Module | Ce que c'est | D'après | Tests |
|---|---|---|---|
| `brocolis` | protocole v3 : paquets, établissement X25519 + ML-KEM-768, double cliquet, groupes, jetons | BrocolisProto (Swift `Messagerie-/proto-swift`, Rust `proto/`) | vecteurs Rust, Wycheproof |
| `talkakit` | le cœur : codes, chiffrement, modèle, relais, stockage, moteur, v3/v4/v6, appels à plusieurs | TalkaKit (`ios/Sources/TalkaKit`) | `ios/Tests/TalkaKitTests/vectors.json` |
| `app` | l'application : écrans, appels WebRTC, notifications, Keystore | l'app iPhone (`ios/Talka`) | compilée par la CI |

## Avancement

- [x] Squelette Gradle (`brocolis`, `talkakit`, `app` quand le SDK est là)
- [ ] `brocolis` : BrocolisProto en Kotlin, vecteurs et Wycheproof verts
- [ ] `talkakit` : modèle (`Model.swift`) et chiffrement (`Crypto.swift`), vecteurs verts
- [ ] `talkakit` : v3 (`V3.swift`, `EngineV3.swift`), Brocolis (`Brocolis*.swift`), v6 (`Pq6.swift`)
- [ ] `talkakit` : relais (`Relay.swift`, `Socket.swift`) et stockage (`Store.swift`, chiffré)
- [ ] `talkakit` : moteur (`Engine.swift`, `EngineV4.swift`), appels à plusieurs (`Mesh.swift`)
- [ ] `app` : bienvenue, discussions, conversation, fiche, réglages, appareils, actus, Brocolis
- [ ] `app` : appels (WebRTC), vocaux, photos, notifications
- [ ] CI : tests JVM + APK (`.github/workflows/android.yml`), signature par secret

## Construire

```sh
cd android
./gradlew :brocolis:test :talkakit:test   # sans SDK Android
./gradlew :app:assembleDebug              # avec ANDROID_HOME
```
