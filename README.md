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
| **Le relais** (mémoire vive, [`src/relay.rs`](src/relay.rs)) | les clés publiques des codes connectés, l'heure de dernière connexion, et les messages chiffrés qui attendent un destinataire hors ligne (30 jours au plus, effacés dès que le destinataire confirme les avoir rangés) |

Le relais ne peut lire aucun message. Chaque message est chiffré pour chaque
destinataire (ECDH → HKDF-SHA256 → AES-256-GCM, les deux codes en données
associées) ; dans un groupe, il part en autant d'enveloppes que de membres. Un
message écrit hors ligne attend dans la boîte d'envoi du navigateur et part
au retour du réseau.

Comme tout est dans le navigateur, **Réglages → Exporter une sauvegarde
chiffrée** produit un fichier protégé par mot de passe (PBKDF2 + AES-GCM), à
importer sur un autre appareil ou après avoir effacé les données du site.

## Fonctionnalités

- 💬 discussions : ✓ envoyé, ✓✓ distribué, ✓✓ bleu lu ; « écrit… » ; en ligne
  et « vu à » ; réponses, transferts, copie, favoris, modification (15 min),
  suppression pour tous ; réactions (double-clic ❤️) ; recherche ; brouillons ;
  `*gras*`, `_italique_`, `~barré~`, `` `code` ``, liens
- 👥 groupes : création, photo, renommage, ajout et retrait de membres,
  admins, départ
- 📹 appels audio et vidéo en WebRTC (d'appareil à appareil), micro, caméra,
  changement de caméra, historique ; serveurs TURN avec `TALKA_ICE_SERVERS`
- 🎙️ vocaux avec forme d'onde et vitesse 1×/1,5×/2× ; 📷 photos réduites
  avant l'envoi ; 📄 fichiers (4 Mo) ; 👤 cartes de contact
- ⭕ statuts (texte coloré ou photo) pendant 24 heures, avec « vu par »
- ⏳ messages éphémères (24 h, 7 j, 90 j) ; 📌 épingler, archiver, sourdine,
  non lu ; 🚫 bloquer
- 🌙 thème clair, sombre ou automatique, fonds d'écran, taille du texte ;
  🔔 notifications et sons (synthétisés, aucun fichier) ; badge d'application
- 📲 PWA installable (manifeste et service worker de Paquets)

## Architecture

| Fichier | Rôle |
|---|---|
| [`src/lib.rs`](src/lib.rs) | l'`App` Paquets : routes, bouclier (Securite, CORS pour GitHub Pages), CSP élargie pour le relais et les médias, PWA |
| [`src/relay.rs`](src/relay.rs) | le relais WebSocket (`/ws`) et son protocole |
| [`src/code.rs`](src/code.rs) | le code Talka et la vérification des signatures |
| [`src/site.rs`](src/site.rs) | le site, rendu par le serveur avec Active (SEO, Open Graph) |
| [`src/assets.rs`](src/assets.rs) | le navigateur compilé et `web/` (styles, icônes) rangés dans le binaire, servis sous `/s/<empreinte>/` |
| [`src/export.rs`](src/export.rs) | `talka export` : la version statique pour GitHub Pages |
| [`client/`](client/) | le navigateur, en Rust → WebAssembly : `crypto` (codes, ECDH, AES-GCM, PBKDF2), `store` (IndexedDB), `net` (relais), `core` (logique), `ui` et `conv` (interface), `calls` (WebRTC), `lock`, `media`, `sounds`, `emoji`, `site` |
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
  sinon l'application la demande au premier lancement. Côté serveur,
  `TALKA_ALLOWED_ORIGINS` (par défaut `https://maxlestage.github.io`) autorise
  la page à l'utiliser. À faire une fois : *Settings → Pages → Source :
  GitHub Actions*.

  ```sh
  BASE_PATH=/Talka TALKA_API_URL=https://talka.herokuapp.com cargo run -- export dist
  ```

Les appels ont besoin d'HTTPS (caméra et micro) : c'est le cas sur Heroku et
GitHub Pages ; en local, `localhost` suffit.
