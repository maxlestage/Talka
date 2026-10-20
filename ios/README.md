# Talka pour iPhone et iPad

L'application iOS de Talka, en Swift et SwiftUI (iOS 17 et plus). Elle parle le même protocole et utilise le même chiffrement que le navigateur : un iPhone et un ordinateur discutent ensemble, dans les mêmes groupes, avec le même code.

## Ce qu'elle fait

- **L'accueil** : votre nom et une photo facultative, puis votre **code** (par exemple `K7Q2M-X9PRT`), qui remplace le numéro de téléphone. Il est calculé à partir de vos clés, sur l'iPhone. On peut aussi restaurer une sauvegarde, de l'iPhone ou du navigateur.
- **Discussions** :
  - recherche, filtres (non lues, groupes, archivées) ;
  - balayer pour épingler, marquer lu/non lu, mettre en silence, archiver, supprimer ;
  - ajouter un contact par son code, en **scannant son QR code** ou par un lien d'invitation (`talka://add/…`, `https://…/c/…`) ;
  - créer un groupe ; montrer son code et son QR code ; les messages importants.
- **Une conversation** :
  - messages chiffrés de bout en bout, avec *gras*, _italique_, ~barré~, `code` et liens ;
  - ✓ envoyé, ✓✓ distribué, ✓✓ bleus lu ; « écrit… », en ligne, vu à… ;
  - réponses (balayer une bulle vers la droite), réactions 👍 ❤️ 😂 😮 😢 🙏, transfert, messages importants, modification (15 min), suppression pour moi ou pour tout le monde, infos de lecture ;
  - **photos** (réduites, sans métadonnées), **documents** (3 Mo), **contacts**, **messages vocaux** (AAC, lus aussi par le navigateur ; ceux de Chrome ou Firefox sont lus par WebKit) ;
  - messages éphémères (24 h, 7 jours, 90 jours), fond d'écran, brouillons.
- **Infos** : nom local du contact, **numéro de sécurité** (le même sur les deux appareils), blocage ; membres et admins d'un groupe, photo, nom, ajout et retrait.
- **Actus** : texte sur fond de couleur ou photo, 24 heures, avec qui les a vues.
- **Appels audio et vidéo** (WebRTC, de pair à pair, DTLS-SRTP) : l'offre, la réponse et les candidats passent chiffrés, comme les messages. Un iPhone appelle un ordinateur.
- **Réglages** : profil, confirmations de lecture, « en ligne » masqué, contacts bloqués, **code PIN** et Face ID, thème, taille du texte, notifications, sons, serveur, **sauvegarde chiffrée** (exporter, restaurer), tout effacer.

Tout reste sur l'iPhone, dans une base SQLite protégée par iOS : le serveur ne fait que passer des messages chiffrés, et les garde au plus 30 jours pour un destinataire hors ligne.

Les notifications sont locales : l'iPhone, qui a déchiffré le message, se prévient lui-même. Rien ne passe par Apple. En contrepartie, iOS coupe la connexion quelques instants après le passage en arrière-plan : les messages arrivent à la réouverture de Talka.

## Organisation

| Dossier | Rôle |
|---|---|
| `Sources/TalkaKit/` | le cœur, sans interface, testé aussi sous Linux : `Crypto.swift` (codes, ECDSA et ECDH P-256, HKDF, AES-256-GCM, PIN, sauvegardes), `Model.swift` (le format des messages, identique au navigateur), `Relay.swift` et `Socket.swift` (le relais), `Store.swift` (SQLite, la même table que le modèle Memoire du navigateur), `Engine.swift` (les discussions, groupes, accusés, actus, appels) |
| `Tests/TalkaKitTests/` | les tests. `vectors.json` est produit par le code Rust du navigateur (`cargo run -p talka-web --example vectors`) : TalkaKit doit retrouver les mêmes codes et ouvrir les mêmes messages |
| `Talka/` | l'application SwiftUI : écrans, appels, messages vocaux, verrou, icône |
| `project.yml` | le projet Xcode, pour [XcodeGen](https://github.com/yonaskolb/XcodeGen) |
| `ci/` | l'envoi sur TestFlight depuis GitHub Actions (`asc.py` : App Store Connect ; `ExportOptions.plist`) |
| `interop.mjs` | un navigateur (Playwright) et un iPhone (TalkaKit) dans la même conversation |

## Le serveur

L'application parle au même serveur Talka que le navigateur.

- Sur TestFlight, il vient de la variable GitHub `TALKA_API_URL` (*Settings* → *Secrets and variables* → *Actions* → *Variables*), la même que pour GitHub Pages.
- Sans elle, l'application demande l'adresse au premier lancement. Elle se change ensuite dans les réglages.
- Dans Xcode : le réglage `TALKA_SERVER` de `project.yml`.

## Sans ordinateur : TestFlight depuis l'iPhone

Un Mac de GitHub compile l'application, la signe et l'envoie sur TestFlight (workflow `TestFlight`). Vous l'installez ensuite avec l'app TestFlight. Tout se fait depuis l'iPhone, dans Safari.

1. **Compte Apple Developer** (99 € par an). Dans l'app *Apple Developer* de l'iPhone : *Compte* → *S'inscrire*.
2. **Clé d'API.** Sur [appstoreconnect.apple.com](https://appstoreconnect.apple.com) : *Utilisateurs et accès* → *Intégrations* → *Clés API App Store Connect* → **+**, accès *Admin*, puis *Générer*.
   - Notez l'**Issuer ID** (en haut) et l'**ID de la clé**.
   - Téléchargez la clé (`AuthKey_….p8`, une seule fois possible). Pour en copier le texte : dans *Fichiers*, renommez-la en `.txt`, ouvrez-la, *Tout sélectionner* → *Copier*.
3. **Secrets GitHub.** Dans le dépôt Talka : *Settings* → *Secrets and variables* → *Actions* → *New repository secret* :
   - `ASC_KEY_ID` : l'ID de la clé ;
   - `ASC_ISSUER_ID` : l'Issuer ID ;
   - `ASC_KEY` : tout le texte du fichier, de `-----BEGIN PRIVATE KEY-----` à `-----END PRIVATE KEY-----`.
4. **Premier lancement.** *Actions* → *TestFlight* → *Run workflow*. Il enregistre l'identifiant `com.maxlestage.talka`, puis s'arrête en demandant de créer l'app.
5. **L'app dans App Store Connect.** *Apps* → **+** → *Nouvelle app* : iOS, nom « Talka », langue Français, identifiant de bundle `com.maxlestage.talka`, SKU `talka`. Puis relancez le workflow : il compile, signe, envoie l'app et ajoute toute l'équipe aux testeurs internes.
6. **Sur l'iPhone.** Installez *TestFlight* depuis l'App Store. Après le traitement par Apple (10 à 30 minutes), Talka y apparaît : *Installer*.

Pour chaque nouvelle version : *Actions* → *TestFlight* → *Run workflow*.

## Ouvrir dans Xcode

```sh
brew install xcodegen
cd ios && xcodegen && open Talka.xcodeproj
```

Choisissez votre équipe dans *Signing & Capabilities*, puis lancez sur un simulateur ou sur votre iPhone.

## Tests

```sh
cd ios && swift test                                          # codes et chiffrement recoupés avec le navigateur
TALKA_SERVER=http://localhost:3000 swift test                 # + deux iPhones qui discutent sur un vrai serveur
TALKA_SERVER=http://localhost:3000 node interop.mjs           # + un navigateur et un iPhone ensemble
# + le relais redémarre pendant une absence : le message est renvoyé. Le test crée
#   le fichier TALKA_RESTART_FILE ; à son lanceur de redémarrer le serveur en le voyant.
TALKA_SERVER=… TALKA_RESTART_FILE=/tmp/talka-restart swift test --filter testResendAfterRelayRestart
```

Le workflow GitHub `iOS` lance `swift test`, puis compile l'application pour le simulateur sur un Mac.
