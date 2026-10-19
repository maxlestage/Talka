# Talka 💬

**La messagerie où un code remplace votre numéro de téléphone.** Discussions,
groupes, appels audio et vidéo, vocaux, photos, statuts : tout ce qu'on attend
d'une messagerie façon WhatsApp, chiffré de bout en bout, **sans compte et sans
base de données**. Tout reste dans le navigateur.

- `/` : le site de présentation
- `/app` : l'application (installable sur iPhone, Android et ordinateur)
- `/confidentialite` : ce que Talka garde, et ce qu'il ne garde pas

Servi par [Paquets](https://github.com/maxlestage/Paquets) 📦, comme
[Alley](https://github.com/maxlestage/Alley).

## Le système de code

À la première ouverture, le navigateur tire deux clés P-256 avec WebCrypto :
une pour signer (ECDSA), une pour chiffrer (ECDH). **Le code Talka est
l'empreinte de ces deux clés** : 50 bits de `SHA-256(signature ‖ chiffrement)`,
écrits en 10 caractères sans ambiguïté (`K7Q2M-X9PRT` : ni `0`, `1`, `I` ni
`O`). Voir [`src/code.rs`](src/code.rs) et [`web/app/crypto.js`](web/app/crypto.js),
qui font le même calcul (vérifié par un vecteur de test).

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
| **Le navigateur** (IndexedDB, [`web/app/db.js`](web/app/db.js)) | identité et clés, profil, contacts, discussions, messages, photos, vocaux, statuts, historique des appels, réglages |
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
| [`src/lib.rs`](src/lib.rs) | l'`App` Paquets : routes, bouclier (Securite), CSP élargie pour la WebSocket et les médias, PWA |
| [`src/relay.rs`](src/relay.rs) | le relais WebSocket (`/ws`) et son protocole |
| [`src/code.rs`](src/code.rs) | le code Talka et la vérification des signatures |
| [`src/site.rs`](src/site.rs) | le site, rendu par le serveur avec Active (SEO, Open Graph) |
| [`src/assets.rs`](src/assets.rs) | `web/` rangé dans le binaire, servi sous `/s/<empreinte>/` (cache d'un an) |
| [`web/app/`](web/app) | l'application : `core.js` (logique), `net.js` (relais), `crypto.js`, `db.js`, `ui.js` et `conv.js` (interface), `calls.js` (WebRTC), `lock.js`, `media.js` |

## Lancer

```sh
cargo run            # http://localhost:3000
cargo test           # le code, le relais en vraie WebSocket, les pages
```

Ouvrez `/app` dans deux navigateurs (ou une fenêtre privée), échangez vos
codes, et discutez.

## Déployer

- **Heroku** : `app.json`, `Procfile` et `RustConfig` (buildpack `emk/rust`).
  Une seule instance (`web: 1`) : le relais vit en mémoire.
- **Docker** : `docker build -t talka . && docker run -p 8080:8080 talka`.

Les appels ont besoin d'HTTPS (caméra et micro) : c'est le cas sur Heroku ; en
local, `localhost` suffit.
