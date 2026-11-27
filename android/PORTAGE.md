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
- [x] `brocolis` : BrocolisProto en Kotlin, vecteurs Rust et Wycheproof verts (72 tests)
- [x] `talkakit` : modèle (`Model.swift`), chiffrement (`Crypto.swift`), appels à plusieurs (`Mesh.swift`), vecteurs verts (39 tests)
- [x] `talkakit` : v3 (`V3.swift`, `V3Vault.swift`), Brocolis (`Brocolis*.swift`), v6 (`Pq6.swift`) ; 111 tests (`EngineV3.swift` va avec le moteur)
- [x] `talkakit` : relais (`Relay.swift`, `Socket.swift`, OkHttp) et stockage (`Store.swift`, chiffré, SQLite derrière une interface) ; 79 tests
- [x] `talkakit` : moteur (`Engine.swift`, `EngineV3.swift`, `EngineV4.swift`, montre, widgets) ; 194 tests, dont les 7 en direct contre le relais local (sautés sans `TALKA_SERVER`)
- [x] `app` : base (modèle, onglets, verrou PIN/biométrie, SQLite et Keystore), bienvenue, discussions, réglages ; 19 tests Robolectric
- [x] `app` : conversation, fiche, appareils (QR), actus, Brocolis
- [x] `app` : appels (WebRTC, à plusieurs), vocaux (AAC/MP4), photos ; 59 tests Robolectric
- [x] `app` : widgets d'écran d'accueil (Glance), pont vers la montre ; `wear` : l'app Wear OS (14 tests)

Comme l'iPhone, pas de notification quand Talka est fermée (l'iPhone n'en a pas : il prévient tant que l'app tourne) ni d'extension « Partager vers Talka ».
- [ ] Essayer sur un vrai téléphone (pas d'émulateur ici) : WebRTC, caméra, micro, biométrie, Keystore
- [x] CI : tests JVM + APK (`.github/workflows/android.yml`) ; signature : les secrets restent à ajouter

## Parité avec l'iPhone

Chaque fonction de l'app iPhone (`ios/Talka`, `ios/Sources/TalkaKit`), à
cocher quand l'Android la fait de la même façon (et parle le même protocole).
Toutes sont portées et testées sur la JVM (Robolectric) ; aucune n'a encore
tourné sur un vrai téléphone ni une vraie montre.

**Compte et sécurité**
- [x] Créer son code (nom, photo) ; restaurer une sauvegarde chiffrée
- [x] Clé de l'appareil gardée par le Keystore Android ; code de verrouillage (PIN), empreinte / visage
- [x] Sauvegarde chiffrée (exporter, importer), mot de passe
- [x] Numéro de sécurité, protection de la discussion (double cliquet ou ancien chiffrement), blocage
- [x] Plusieurs appareils (v4) : lier par QR code, liste des appareils, retirer un appareil
- [x] Expéditeur scellé (v4/v6), boîtes tournantes, relais authentifié, cliquet post-quantique (v6)

**Discussions** (onglet Discussions)
- [x] Liste triée, épinglées, archivées, non lues, recherche dans toutes les discussions
- [x] Ajouter un contact (code, QR code, lien d'invitation), montrer son code (QR)
- [x] Groupes : créer, nom, photo, description, membres, admins, « seuls les admins écrivent », quitter
- [x] Sourdine, archiver, épingler, vider, supprimer, exporter une discussion, fond d'écran

**Conversation**
- [x] Bulles : texte (mise en forme, liens), photo, vocal, fichier, carte de contact
- [x] Répondre, réagir, modifier, supprimer pour tous, transférer, important (étoile), épingler un message
- [x] ✓ envoyé, ✓✓ distribué, ✓✓ lu (accusés de lecture réglables), « en train d'écrire »
- [x] Messages éphémères, capsules temporelles, mentions « @ », recherche dans la discussion
- [x] Brouillons, messages plus anciens à la demande, bandeau des non-lus, saut vers un message cité

**Brocolis 🥦**
- [x] Clés partagées avec l'app Brocolis (PSK), jetons Brocolis dans les messages, l'œuf de Pâques

**Actus** (onglet Actus)
- [x] Actus texte sur fond de couleur et photo, 24 heures, vues, supprimer

**Appels** (onglet Appels)
- [x] Audio et vidéo (WebRTC), haut-parleur, caméra avant/arrière, couper le micro
- [x] Appels à plusieurs (8 au plus) : un groupe, des contacts choisis, ajouter en cours d'appel
- [x] Journal des appels, rappeler, appel entrant en plein écran (même verrouillé)

**Réglages**
- [x] Profil, code, confidentialité (présence, accusés de lecture), verrou, apparence, notifications, conservation, sauvegarde

**Système**
- [x] Notifications (avec aperçu chiffré si permis), sonnerie d'appel
- [x] Widgets d'écran d'accueil (non-lus, discussions récentes), comme ceux de l'iPhone
- [x] Montre (Wear OS), comme l'Apple Watch : à faire en dernier

## Construire

```sh
cd android
./gradlew :brocolis:test :talkakit:test   # sans SDK Android
./gradlew :app:testDebugUnitTest :app:assembleDebug :wear:assembleDebug   # avec ANDROID_HOME
```
