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
  - un œuf de Pâques : un message avec 🥦 ou « brocoli » fait pleuvoir des brocolis, comme dans le navigateur (sauf avec *Réduire les animations*).
- **Brocolis 🥦** : les clés de l'app Brocolis (Réglages → Clés Brocolis), et par discussion « Chiffrer avec Brocolis 🥦 » : le texte part aussi en jetons `[[BROCOLIS:…]]`, ouverts à l'arrivée avec vos clés (`Sources/TalkaKit/Brocolis.swift`, le même format que le navigateur et que l'app Brocolis). Une clé s'envoie par Talka, s'affiche avec son empreinte, se renouvelle, et reste dans le trousseau d'iOS (`BrocolisVault.swift`).
- **Infos** : nom local du contact, **numéro de sécurité** (le même sur les deux appareils), blocage ; membres et admins d'un groupe, photo, nom, ajout et retrait.
- **Actus** : texte sur fond de couleur ou photo, 24 heures, avec qui les a vues.
- **Appels audio et vidéo** (WebRTC, de pair à pair, DTLS-SRTP) : l'offre, la réponse et les candidats passent chiffrés, comme les messages. Un iPhone appelle un ordinateur.
- **Réglages** : profil, confirmations de lecture, « en ligne » masqué, contacts bloqués ; **verrouillage et sécurité** comme le navigateur : code de 6 à 12 chiffres qui **chiffre aussi la base** (Argon2id), Face ID, verrouillage automatique, effacement après 10 codes faux, Talka masqué pendant un enregistrement de l'écran ; aperçu des notifications (nom et message, nom seulement, rien) ; thème, taille du texte, Entrée pour envoyer (clavier), sons, **sauvegarde chiffrée** (exporter, restaurer), tout effacer. Pas de serveur à choisir : un seul relais, intégré.
- **iPad** : la liste des discussions et la discussion côte à côte, sur tout l'écran, dans toutes les orientations et en multitâche (Split View, Stage Manager) ; une fenêtre étroite repasse en une colonne, comme sur l'iPhone.
- **Plusieurs appareils (v4)** : le même compte sur l'iPhone, l'iPad et le navigateur (six appareils au plus). Le nouvel appareil montre un QR code (« J'ai déjà Talka sur un autre appareil ») ; l'autre le scanne (Réglages → Appareils liés), les deux affichent le même code de vérification, et le compte arrive chiffré pour ce seul appareil. Chacun a ses clés et sa copie de chaque message ; messages envoyés, lectures, contacts, groupes, discussions, profil et appels restent synchronisés ; un appareil retiré efface tout. Entre contacts, les messages passent par des **boîtes aux lettres tournantes** : le relais ne voit plus qui écrit à qui (`docs/SECURITE.md` §7.11 à §7.13).

Tout reste sur l'iPhone, dans une base SQLite protégée par iOS : le serveur ne fait que passer des messages chiffrés, et les garde au plus 30 jours pour un destinataire hors ligne.

Avec un contact à jour (navigateur ou iPhone), les messages passent par des **sessions à double cliquet** (le protocole v3, le même que le navigateur : hybride X25519 + ML-KEM-768 ; X25519 seul avant iOS 26, dont CryptoKit n'a pas ML-KEM). L'appareil v3 vit dans le trousseau (cet iPhone seulement), les sessions dans la base, écrites avec le message qu'elles ont ouvert et avant tout envoi ; elles ne vont pas dans la sauvegarde (l'appareil, si). Avec un ancien client, l'ancien format, exactement. La fiche du contact dit lequel.

Les notifications sont locales : l'iPhone, qui a déchiffré le message, se prévient lui-même. Rien ne passe par Apple. En contrepartie, iOS coupe la connexion quelques instants après le passage en arrière-plan : les messages arrivent à la réouverture de Talka.

## Organisation

| Dossier | Rôle |
|---|---|
| `Sources/TalkaKit/` | le cœur, sans interface, testé aussi sous Linux : `Crypto.swift` (codes, ECDSA et ECDH P-256, HKDF, AES-256-GCM, PIN, sauvegardes), `Model.swift` (le format des messages, identique au navigateur), `Relay.swift` et `Socket.swift` (le relais), `Store.swift` (SQLite, la même table que le modèle Memoire du navigateur), `Engine.swift` (les discussions, groupes, accusés, actus, appels), `V3.swift`, `EngineV3.swift` et `V3Vault.swift` (le protocole v3 : sessions à double cliquet, avec `BrocolisProto`, le portage Swift de la crate du navigateur ; l'appareil v3 dans le trousseau), `EngineV4.swift` (v4 : les appareils du compte, leur synchronisation, la liaison par QR code, les boîtes aux lettres tournantes) |
| `Tests/TalkaKitTests/` | les tests. `vectors.json` est produit par le code Rust du navigateur (`cargo run -p talka-web --example vectors`) : TalkaKit doit retrouver les mêmes codes et ouvrir les mêmes messages |
| `Talka/` | l'application SwiftUI : écrans, appels, messages vocaux, verrou, icône ; `Devices.swift` : les appareils liés et la liaison ; `WatchBridge.swift` : le lien avec la montre |
| `TalkaWatch/` | l'app Apple Watch, livrée avec celle de l'iPhone : les discussions récentes, répondre (dictée, réponses rapides, emoji), réagir, marquer comme lu. La montre n'a pas de clés : elle passe par l'iPhone (WatchConnectivity, `Sources/TalkaKit/WatchLink.swift`) ; loin de lui, elle affiche le dernier résumé reçu et ses réponses partent à son retour |
| `TalkaWidgets/` | les widgets, le même code pour l'iPhone (écran d'accueil : « Non lus », « Discussions » ; écran verrouillé) et la montre (complications). L'app leur écrit le nombre de non-lus et les discussions récentes dans un groupe de trousseau commun (`Shared.entitlements`, `Sources/TalkaKit/WidgetData.swift`) ; avec un code de verrouillage, ils n'affichent que le nombre |
| `project.yml` | le projet Xcode, pour [XcodeGen](https://github.com/yonaskolb/XcodeGen) |
| `ci/` | l'envoi sur TestFlight depuis GitHub Actions (`asc.py` : App Store Connect ; `ExportOptions.plist`) |
| `interop.mjs` | un navigateur (Playwright) et un iPhone (TalkaKit) dans la même conversation |
| `interop-v3.sh` | l'iPhone (TalkaKit) et le code Rust du navigateur (`tests/ios_v3.rs`) en protocole v3, par un vrai relais : paquets, sessions dans les deux sens, désordre, doublon, rejeu, session perdue, ancien client |

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
./interop-v3.sh                                               # + le protocole v3, iPhone (Swift) et navigateur (Rust), par le relais
# + le relais redémarre pendant une absence : le message est renvoyé. Le test crée
#   le fichier TALKA_RESTART_FILE ; à son lanceur de redémarrer le serveur en le voyant.
TALKA_SERVER=… TALKA_RESTART_FILE=/tmp/talka-restart swift test --filter testResendAfterRelayRestart
```

Le workflow GitHub `iOS` lance `swift test`, puis compile l'application pour le simulateur sur un Mac.
