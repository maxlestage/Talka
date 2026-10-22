# Talka 💬

**La messagerie où un code remplace votre numéro de téléphone.** Discussions,
groupes, appels audio et vidéo, vocaux, photos, statuts : tout ce qu'on attend
d'une messagerie façon WhatsApp, chiffré de bout en bout, **sans compte et sans
base de données**. Tout reste dans le navigateur.

- `/` : le site de présentation
- `/app` : l'application (installable sur iPhone, Android et ordinateur)
- `/confidentialite` : ce que Talka garde, et ce qu'il ne garde pas
- [`ios/`](ios/) : l'application **iPhone et iPad** (Swift, SwiftUI), qui
  discute avec le navigateur : même code, même chiffrement, mêmes groupes

Servi par [Paquets](https://github.com/maxlestage/Paquets) 📦, comme
[Alley](https://github.com/maxlestage/Alley), et **100 % Rust** 🦀 : le
serveur, et tout le navigateur compilé en WebAssembly ([`client/`](client/)).
Pas une ligne de JavaScript écrite à la main : le seul JavaScript livré est
la colle que génère wasm-bindgen pour charger le module.

## Le système de code

À la première ouverture, le navigateur tire deux clés P-256 avec WebCrypto :
une pour signer (ECDSA), une pour chiffrer (ECDH). **Le code Talka est
l'empreinte de ces deux clés** : 50 bits de `SHA-256(signature ‖ chiffrement)`,
écrits en 10 caractères sans ambiguïté (`K7Q2M-X9PRT` : ni `0`, `1`, `I` ni
`O`). Voir [`src/code.rs`](src/code.rs) et [`client/src/crypto.rs`](client/src/crypto.rs),
qui font le même calcul (vérifié par un vecteur de test et par un test qui fait
signer le navigateur et vérifier le serveur).

Conséquences :

- **on s'ajoute par code**, par lien d'invitation (`/c/K7Q2MX9PRT`) ou par
  QR code (`/api/qr/K7Q2MX9PRT`, scanné dans l'application quand le navigateur
  connaît `BarcodeDetector`) : jamais de numéro, d'e-mail ni de carnet
  d'adresses ;
- **personne ne peut se faire passer pour un code** : pour recevoir ses
  messages, l'appareil signe un défi du relais avec sa clé ; et quand on ajoute
  un contact, l'appareil vérifie lui-même que les clés données par le relais
  ont bien ce code pour empreinte. Le serveur ne peut pas glisser les siennes ;
- un **numéro de sécurité** (fiche du contact) se compare de vive voix ;
- en plus, un **code de verrouillage** (4 à 6 chiffres, haché par PBKDF2)
  protège l'application sur l'appareil, avec verrouillage automatique.

## Pas de base de données

| Où | Quoi |
|---|---|
| **Le navigateur** (IndexedDB, [`client/src/store.rs`](client/src/store.rs)) | identité et clés, profil, contacts, discussions, messages, photos, vocaux, statuts, historique des appels, réglages |
| **Le relais** (mémoire vive, [`src/relay.rs`](src/relay.rs)) | les clés publiques des codes connectés et leur dernier paquet de clés v3 (signé), l'heure de dernière connexion, et les messages chiffrés qui attendent un destinataire hors ligne (30 jours au plus, effacés dès que le destinataire confirme les avoir rangés) |

Le relais ne peut lire aucun message. Chaque message est chiffré pour chaque
destinataire (ECDH → HKDF-SHA256 → AES-256-GCM, les deux codes en données
associées) ; dans un groupe, il part en autant d'enveloppes que de membres. Un
message écrit hors ligne attend dans la boîte d'envoi du navigateur et part
au retour du réseau.

## Sessions à double cliquet (protocole v3)

Avec un contact qui a la mise à jour, chaque message part en plus dans une
**session à double cliquet** (une clé neuve par message, effacée aussitôt
utilisée), établie en **hybride X25519 + ML-KEM-768** : le protocole v3 de
[`docs/SECURITE.md`](docs/SECURITE.md), avec son implémentation de référence
(`brocolis-proto`, voir [`client/src/v3.rs`](client/src/v3.rs)) ; sur
l'iPhone, son portage Swift vérifié octet pour octet (`BrocolisProto`, voir
[`ios/Sources/TalkaKit/V3.swift`](ios/Sources/TalkaKit/V3.swift)) : un
iPhone et un navigateur à jour discutent en sessions à double cliquet.

- chaque navigateur (et chaque iPhone) a un appareil v3 (clés X25519, Ed25519, ML-KEM-768),
  dont les clés pré-signées changent chaque semaine ; son **paquet de clés
  est signé par la clé du code** et publié sur le relais. Un paquet n'est
  accepté que si cette signature vérifie avec les clés dont le code est
  l'empreinte : rien de plus à comparer que le code ;
- le jeton v3 voyage **dans** l'enveloppe chiffrée habituelle : le relais ne
  voit aucune différence ; avec un contact qui n'a pas publié de paquet
  (une version d'avant v3), l'ancien format, exactement ;
- l'état des sessions est écrit dans IndexedDB avec le message qu'il a
  ouvert, et rien ne part avant qu'il y soit ; il ne va pas dans la
  sauvegarde (restauré en retard, il reprendrait des clés déjà servies) :
  après une restauration, les sessions se rouvrent toutes seules ;
- un document de plus de 200 Kio (photo lourde, vocal, fichier) dépasse ce
  que porte un jeton v3 : il part dans l'ancien format ;
- la fiche du contact dit « 🔐 Session à double cliquet (post-quantique) »,
  « (classique) », ou « Ancien chiffrement » ; un iPhone d'avant iOS 26 (pas
  de ML-KEM dans CryptoKit) n'ouvre que des sessions classiques.

Comme tout est dans le navigateur, **Réglages → Exporter une sauvegarde
chiffrée** produit un fichier protégé par mot de passe (PBKDF2 + AES-GCM), à
importer sur un autre appareil ou après avoir effacé les données du site.

## Brocolis 🥦 : une seconde couche

La sécurité de Talka et de Brocolis est repensée en trois phases (stockage
matériel, Argon2id, protocole à double cliquet, post-quantique) : voir
[`docs/SECURITE.md`](docs/SECURITE.md), la référence commune aux deux dépôts.
Une faille ? Signalez-la en privé : [`SECURITY.md`](SECURITY.md). Pour un
audit indépendant, le dossier est prêt : [`docs/AUDIT.md`](docs/AUDIT.md).

Talka parle le format de [Brocolis](https://github.com/maxlestage/messagerie-),
l'app qui chiffre du texte par-dessus n'importe quelle messagerie. **Réglages →
Clés Brocolis** : collez une clé de l'app Brocolis (ou toute l'invitation
reçue), ou créez-en une. Puis, dans une discussion, **⋮ → Chiffrer avec
Brocolis 🥦** et cochez la ou les clés :

- tout le texte qui part (messages, modifications, citations, légendes,
  capsules) est scellé en jeton `[[BROCOLIS:v1:…]]` (une clé) ou
  `[[BROCOLIS:v2:…]]` (plusieurs : une enveloppe, lisible par chacune), **en
  plus** du chiffrement de Talka ; `((comme ceci))` ne chiffre qu'un passage ;
- à l'arrivée, les jetons s'ouvrent avec toutes vos clés (marque 🥦 sur la
  bulle) ; ceux qu'aucune clé n'ouvre s'affichent « 🔒🥦 », et s'ouvrent dès
  que vous ajoutez la bonne clé ;
- les jetons sont exactement ceux de l'app Brocolis (AES-256-GCM, clés de 32
  octets) : un jeton collé depuis son clavier s'ouvre dans Talka, et
  inversement. Voir [`client/src/jetons.rs`](client/src/jetons.rs) et
  [`ios/Sources/TalkaKit/Brocolis.swift`](ios/Sources/TalkaKit/Brocolis.swift),
  recoupés par les vecteurs de test et par un jeton fait par l'app Brocolis.

**Des clés plus sûres.** Une clé fait 256 bits tirés au hasard (le base64
n'est que sa façon de s'écrire) ; ce qui l'expose, c'est la façon dont on la
partage et dont on la garde :

- **envoyée par Talka** : « Créer et envoyer » (ou « Envoyer les clés
  cochées ») la fait voyager dans le chiffrement de bout en bout de Talka ;
  le correspondant l'ajoute d'un geste (bulle « Clé Brocolis »). Une fois
  ajoutée, elle n'est plus gardée dans les messages ;
- **une empreinte** (`AB12-CD34-EF56-7890`, les 8 premiers octets de son
  SHA-256) s'affiche partout, à comparer de vive voix ;
- **renouveler** une clé en crée une neuve, du même nom, qui la remplace dans
  ses discussions et y part par Talka ; chez le correspondant aussi, elle
  remplace l'ancienne. L'ancienne garde l'historique lisible mais ne chiffre
  plus rien ;
- **jamais affichée** : pour sortir une clé de Talka, « Exporter » la copie
  **protégée par une phrase secrète** (`[[BROCOLIS-CLE:v1:…]]` :
  PBKDF2-SHA256 600 000 tours → AES-256-GCM, le même format que l'app
  Brocolis), à donner à part, de vive voix ; sans la phrase, le jeton ne
  dit rien de la clé. La copie en clair reste possible, après le code de
  verrouillage (Face ID ou le code de l'iPhone sur iPhone, où le
  presse-papiers reste local et s'efface au bout de deux minutes) ;
- **au repos** : dans le navigateur, avec un code de verrouillage, les clés
  sont chiffrées par lui (PBKDF2-SHA256, 300 000 tours → AES-256-GCM) et
  ouvertes au déverrouillage ; un code de 4 à 6 chiffres reste court, il
  ralentit sans arrêter qui aurait copié le navigateur. Sur l'iPhone, elles
  sont dans le trousseau d'iOS, pour cet appareil seulement (ni iCloud, ni
  sauvegarde de l'iPhone).

L'appareil garde le texte ouvert, comme le reste de Talka ; les clés partent
en clair dans la sauvegarde, elle-même chiffrée par son mot de passe.

## Fonctionnalités

- 💬 discussions : ✓ envoyé, ✓✓ distribué, ✓✓ bleu lu ; « écrit… » ; en ligne
  et « vu à » ; réponses, transferts, copie, favoris, modification (15 min),
  suppression pour tous ; réactions (double-clic ❤️) ; recherche ; brouillons ;
  `*gras*`, `_italique_`, `~barré~`, `` `code` ``, liens
- 👥 groupes : création, photo, renommage, description (écrite par les
  admins, lue par tous), « seuls les admins peuvent écrire », ajout et
  retrait de membres, admins, départ ; mentions « @Léa » (la personne est prévenue même en
  sourdine, « @ » dans la liste des discussions)
- 📹 appels audio et vidéo en WebRTC (d'appareil à appareil), micro, caméra,
  changement de caméra, historique ; serveurs TURN avec `TALKA_ICE_SERVERS`
- 🎙️ vocaux avec forme d'onde et vitesse 1×/1,5×/2× ; 📷 photos réduites
  avant l'envoi ; 📄 fichiers (3 Mo) ; 👤 cartes de contact
- ⭕ statuts (texte coloré ou photo) pendant 24 heures, avec « vu par »
- ⏳ messages éphémères (24 h, 7 j, 90 j) ; 📌 épingler, archiver (et, au
  choix, garder archivées les discussions où arrive un message), sourdine,
  non lu ; 🚫 bloquer
- 🔐 verrouillage : code de 6 à 12 chiffres qui chiffre aussi la mémoire de
  l'appareil (Argon2id), passkey (navigateur) ou Face ID (iPhone, iPad),
  verrouillage automatique, effacement après 10 codes faux ; aperçu des
  notifications au choix (nom et message, nom seulement, rien)
- 📱 iPhone, iPad (liste et discussion côte à côte) et navigateur ;
  l'application web installée sur l'écran d'accueil occupe tout l'écran
- 🔗 **v4 : plusieurs appareils à la fois** (six au plus), liés par un QR
  code avec un code de vérification ; chacun a ses propres clés et reçoit sa
  copie de chaque message ; messages envoyés, lectures, contacts, groupes,
  discussions archivées, profil et actus restent synchronisés ; retirer un
  appareil l'efface (`docs/SECURITE.md` §7.11, §7.12)
- 📮 **v4 : boîtes aux lettres tournantes** : entre contacts, le relais ne
  voit ni qui écrit, ni à qui, seulement une boîte anonyme qui change chaque
  jour, liée au relais officiel (§7.13)
- 🌙 thème clair, sombre ou automatique, fonds d'écran, taille du texte ;
  🔔 notifications et sons (synthétisés, aucun fichier) ; badge d'application
- 📲 PWA installable (manifeste et service worker de Paquets)
- 🥦 un œuf de Pâques : un message qui contient 🥦 ou « brocoli » fait
  pleuvoir des brocolis, dans le navigateur comme sur l'iPhone (sauf si
  l'appareil demande moins d'animations)

## Architecture

| Fichier | Rôle |
|---|---|
| [`src/lib.rs`](src/lib.rs) | l'`App` Paquets : routes, bouclier (Securite, CORS pour GitHub Pages), CSP élargie pour le relais et les médias, PWA |
| [`src/relay.rs`](src/relay.rs) | le relais WebSocket (`/ws`) et son protocole |
| [`src/code.rs`](src/code.rs) | le code Talka et la vérification des signatures |
| [`src/site.rs`](src/site.rs) | le site, rendu par le serveur avec Active (SEO, Open Graph) |
| [`src/assets.rs`](src/assets.rs) | le navigateur compilé et `web/` (styles, icônes) rangés dans le binaire, servis sous `/s/<empreinte>/` |
| [`src/export.rs`](src/export.rs) | `talka export` : la version statique pour GitHub Pages |
| [`client/`](client/) | le navigateur, en Rust → WebAssembly : `crypto` (codes, ECDH, AES-GCM, PBKDF2), `v3` (sessions à double cliquet), `store` (IndexedDB), `net` (relais), `core` (logique), `ui` et `conv` (interface), `calls` (WebRTC), `lock`, `media`, `sounds`, `emoji`, `site` |
| [`xtask/`](xtask/) | `cargo xtask web` : compile `client/` et génère la colle wasm-bindgen (sans outil à installer) |
| [`ios/`](ios/) | l'application iOS : `TalkaKit` (le même protocole en Swift, recoupé avec le navigateur par des vecteurs de test) et l'interface SwiftUI ; TestFlight depuis GitHub Actions ([`ios/README.md`](ios/README.md)) |

## Lancer

```sh
rustup target add wasm32-unknown-unknown
cargo xtask web      # le navigateur → client/pkg/ (rangé dans le binaire)
cargo run            # http://localhost:3000
cargo test --workspace
```

Ouvrez `/app` dans deux navigateurs (ou une fenêtre privée), échangez vos
codes, et discutez.

## Déployer

- **Heroku** : `app.json`, `Procfile` et `RustConfig` (buildpack `emk/rust`,
  qui compile d'abord le navigateur en WebAssembly). Une seule instance
  (`web: 1`) : le relais vit en mémoire.
- **Docker** : `docker build -t talka . && docker run -p 8080:8080 talka`.
- **GitHub Pages** ([`.github/workflows/pages.yml`](.github/workflows/pages.yml)) :
  à chaque fusion dans `master`, le site et l'application sont exportés
  (`talka export`) sous `https://<propriétaire>.github.io/Talka/`. Il faut un
  relais Talka en ligne (Heroku…) : son adresse vient de la variable
  `TALKA_API_URL`, ou de l'app Heroku (`HEROKU_API_KEY` + `HEROKU_APP_NAME`),
  sinon le relais officiel (`OFFICIAL` dans `client/src/net.rs`,
  `TalkaServer.official` sur l'iPhone) : il n'y a pas de serveur à choisir
  dans l'application. Côté serveur,
  `TALKA_ALLOWED_ORIGINS` (par défaut `https://maxlestage.github.io`) autorise
  la page à l'utiliser. À faire une fois : *Settings → Pages → Source :
  GitHub Actions*.
- **Notifications quand Talka est fermé** (Web Push) : la notification
  montre qui écrit et quoi, mais cet aperçu est chiffré par l'expéditeur pour
  la clé de notification du destinataire ; le relais le transmet sans le lire
  (voir [`src/push.rs`](src/push.rs) et `docs/SECURITE.md` §7.8).
  Il lui faut une clé, à fabriquer une fois et à ne plus changer :

  ```sh
  cargo run -- vapid   # affiche TALKA_VAPID_KEY=…
  heroku config:set TALKA_VAPID_KEY=… -a <app>
  ```

  ```sh
  BASE_PATH=/Talka TALKA_API_URL=https://talka.herokuapp.com cargo run -- export dist
  ```

Les appels ont besoin d'HTTPS (caméra et micro) : c'est le cas sur Heroku et
GitHub Pages ; en local, `localhost` suffit.
