# Brocolis 🥦 et Talka : la sécurité, repensée

Ce document est la référence commune aux deux dépôts
([Talka](https://github.com/maxlestage/Talka) et
[Brocolis](https://github.com/maxlestage/messagerie-)). Chaque format décrit ici
a une implémentation de référence en Rust et des **vecteurs de test** que les
autres implémentations (Swift pour l'iPhone, Kotlin pour Android, Rust
WebAssembly pour les navigateurs) doivent retrouver octet pour octet.

Il s'inspire des outils qui font référence : **Signal** (X3DH, PQXDH, Double
Ratchet, Sender Keys, remplissage, numéros de sécurité), **iMessage PQ3**,
**age** et **libsodium** (primitives modernes, formats simples), **KeePassXC**,
**Bitwarden** et **1Password** (Argon2id, coffre chiffré), les **clés matérielles**
(Secure Enclave, StrongBox, YubiKey, passkeys), **MLS** (RFC 9420) pour les
groupes, et les outils d'audit (**Wycheproof**, **cargo-audit**, **cargo-deny**,
**CodeQL**, **Semgrep**, **fuzzing**).

Rien n'est « inviolable » dans l'absolu : ce document dit précisément contre
quoi chaque mesure protège, et ce qui reste hors de portée.

## 1. Modèle de menace

| Adversaire | Ce qu'il peut faire | Ce qu'on garantit |
|---|---|---|
| Le serveur (relais Talka, salon Brocolis), ou quiconque le contrôle | lire, garder, rejouer, réordonner, supprimer tout ce qui passe | il ne lit aucun message ni aucune clé ; un message modifié est refusé ; un rejeu est ignoré |
| Le réseau, une messagerie hôte (WhatsApp, iMessage…) qui transporte les jetons Brocolis | idem, et garder les jetons des années | idem ; **confidentialité persistante** : voler une clé plus tard n'ouvre pas les messages passés |
| L'attaquant « qui garde tout pour plus tard » avec un ordinateur quantique | casser X25519 et P-256 rétroactivement | l'établissement des sessions est **hybride X25519 + ML-KEM-768** : il faut casser les deux |
| Le vol d'un appareil verrouillé, la copie de ses fichiers, une sauvegarde | lire le stockage | les secrets sont emballés par une clé **matérielle non extractible** (Secure Enclave, Android Keystore/StrongBox, WebCrypto non extractible) ; rien n'est dans les sauvegardes de l'OS |
| La compromission passagère d'un appareil (un logiciel espion qui part) | lire l'état des sessions à un instant | **guérison** (post-compromise security) : le ratchet Diffie-Hellman reprend le secret au premier aller-retour |
| Un correspondant usurpé (homme du milieu à l'appairage) | substituer ses clés | empreintes et numéros de sécurité comparés de vive voix ; **alerte** dès qu'une clé d'identité change |
| Qui devine une phrase secrète ou un code | essais hors ligne sur GPU | **Argon2id** (gourmand en mémoire) ; un code à 4–6 chiffres reste faible : il protège par la clé matérielle, pas par lui-même |

**Hors de portée** (aucun logiciel ne le promet honnêtement) : un appareil
déverrouillé entre de mauvaises mains, un correspondant qui fait une capture
d'écran, un système d'exploitation compromis pendant l'usage, les métadonnées
visibles de la messagerie hôte (qui écrit à qui, quand).

## 2. Primitives

| Rôle | Choix | Pourquoi |
|---|---|---|
| Échange de clés | **X25519** | Signal, age, WireGuard ; partout (CryptoKit, RustCrypto, Android) |
| Post-quantique | **ML-KEM-768** (FIPS 203), en hybride | Signal PQXDH, iMessage PQ3 ; CryptoKit (iOS 26), RustCrypto `ml-kem`, BouncyCastle |
| Signature | **Ed25519** (Brocolis) ; ECDSA P-256 existante (identité Talka) | Sender Keys, paquets de clés publiés |
| Chiffrement | **AES-256-GCM** | matériel partout, déjà utilisé ; nonces jamais réutilisés (clé neuve par message) |
| Dérivation | **HKDF-SHA256**, **HMAC-SHA256** | Signal |
| Mots de passe | **Argon2id** (RFC 9106) | KeePassXC, Bitwarden ; remplace PBKDF2 |
| Hasard | CSPRNG du système uniquement | `SecRandomCopyBytes`, `SecureRandom`, `crypto.getRandomValues` |

Règles d'hygiène, partout : comparaisons en temps constant ; secrets effacés de
la mémoire après usage quand le langage le permet (`zeroize` en Rust) ; aucun
secret dans les journaux ; aucune primitive « maison ».

## 3. Phase 1 — durcir sans rien casser

### 3.1 Les secrets au repos

| Plateforme | Où | Protégé par |
|---|---|---|
| iPhone (Talka, Brocolis) | Trousseau, `AfterFirstUnlockThisDeviceOnly` (groupe de l'App Group pour Brocolis) | Secure Enclave et le code de l'iPhone ; jamais iCloud ni sauvegarde |
| Android (Brocolis) | valeurs chiffrées AES-256-GCM par une clé `AndroidKeyStore` non exportable (StrongBox si présent) | matériel ; préférences exclues des sauvegardes et transferts |
| Navigateur (Talka) | `brocolis_sealed` : clé tirée du code de verrouillage | code (faible seul) ; à terme passkey (§3.5) |
| Navigateur (site Brocolis) | valeurs chiffrées par une clé WebCrypto **non extractible** gardée dans IndexedDB | le navigateur ne la donne jamais au code |

### 3.2 Clé protégée par une phrase secrète

`[[BROCOLIS-CLE:v1:…]]` (PBKDF2-SHA256, 600 000 tours) reste lisible.
Nouveau format par défaut, **v2 avec Argon2id** :

    [[BROCOLIS-CLE:v2:<base64>]]

    octet 0       : 0x02 (version)
    octets 1–4    : m, mémoire en Kio, u32 gros-boutiste (défaut 65 536 = 64 Mio)
    octet 5       : t, passes (défaut 3)
    octet 6       : p, parallélisme (défaut 1)
    octets 7–22   : sel (16)
    octets 23–34  : nonce (12)
    octets 35–82  : AES-256-GCM(clé brute 32) ‖ tag 16
    clé de chiffrement = Argon2id(phrase UTF-8, sel, m, t, p, 32 octets)
    données associées  = les octets 0–34 (en-tête et paramètres compris)

À l'import, refuser m < 19 456, t < 2, ou m > 1 048 576 (un jeton piégé ne
doit pas épuiser la mémoire).

### 3.3 Remplissage (padding)

La longueur d'un message ne doit pas se lire sur le jeton. Avant
chiffrement : texte UTF-8 ‖ `0x80` ‖ des `0x00` jusqu'au **multiple de 160
octets** suivant (comme Signal). Au déchiffrement : retirer les `0x00` de fin
puis le `0x80` ; absent → refusé. S'applique à v3 (§4) ; v1/v2 restent tels
quels pour rester lisibles par les anciennes versions.

### 3.4 L'écran et le presse-papiers

- iPhone et Android : contenu masqué dans le sélecteur d'apps ;
  `FLAG_SECURE` sur Android (pas de capture) ; sur iPhone, les clés et
  phrases ne s'affichent jamais en clair.
- Presse-papiers : local à l'appareil (pas de presse-papiers universel),
  expiration après 2 minutes (iOS) ; marqué « sensible » (Android 13+).

### 3.5 Plus tard dans la phase 1 : passkey (WebAuthn PRF)

Dans les navigateurs, la clé qui scelle le coffre pourra venir d'une passkey
(extension PRF de WebAuthn : un secret stable que seul l'authentificateur
matériel — Face ID, Windows Hello, YubiKey — sait produire), au lieu du code.

### 3.6 Contrôles automatiques (CI)

- `cargo audit` et `cargo deny` (failles connues, licences, sources) sur toutes
  les crates ;
- CodeQL (Swift, Kotlin, Rust) et Semgrep ;
- vecteurs **Wycheproof** pour AES-GCM, X25519, HKDF : chaque implémentation
  doit refuser les cas piégés ;
- fuzzing (`cargo fuzz`) des lecteurs de jetons et du déchiffrement v3 :
  aucune entrée ne doit faire planter ni boucler.

## 4. Phase 2 — le protocole v3

v3 remplace la clé fixe (Brocolis v1/v2, et l'ECDH fixe de Talka) par des
**sessions à cliquet** : une clé neuve par message, effacée aussitôt utilisée.

### 4.1 Paquet de clés publiques

Chaque appareil a :

- **IK** : clé d'identité X25519, longue durée (et, pour Brocolis, une clé
  Ed25519 de signature ; pour Talka, l'identité ECDSA P-256 existante signe) ;
- **SPK** : clé pré-signée X25519, renouvelée chaque semaine (l'ancienne
  gardée un mois pour les messages en retard) ;
- **PQPK** : clé d'encapsulation ML-KEM-768, signée, renouvelée chaque semaine.

Le paquet `IK ‖ SPK ‖ PQPK ‖ signatures` s'échange à l'appairage (salon
Brocolis), ou se publie sur le relais (Talka). L'empreinte et le numéro de
sécurité portent sur IK : comparés de vive voix, ils authentifient tout le
paquet (signé par IK).

### 4.2 Établissement (inspiré de PQXDH)

L'initiateur A, qui connaît le paquet de B, tire une clé éphémère EK et :

    DH1 = X25519(IK_A, SPK_B)
    DH2 = X25519(EK_A, IK_B)
    DH3 = X25519(EK_A, SPK_B)
    (CT, SS) = ML-KEM-768.Encaps(PQPK_B)
    SK = HKDF-SHA256(sel = 32 × 0x00,
                     ikm = 32 × 0xFF ‖ DH1 ‖ DH2 ‖ DH3 ‖ SS,
                     info = "Brocolis_v3_X25519_MLKEM768_SHA-256", 32)
    AD = IK_A ‖ IK_B

Si l'un des appareils ne sait pas faire ML-KEM (iPhone avant iOS 26), le
drapeau `pq = 0` retire `SS` et l'info devient `"Brocolis_v3_X25519_SHA-256"`.
L'appli l'affiche (« session non post-quantique »).

**Clé Brocolis partagée sans appairage** : la clé symétrique sert de secret
pré-partagé, `SK = HKDF(sel = 32 × 0x00, ikm = clé, info =
"Brocolis_v3_PSK_SHA-256", 32)`, et le ratchet Diffie-Hellman ajoute la
confidentialité persistante dès le premier aller-retour. Une telle session
vaut ce que vaut la clé partagée tant que personne n'a répondu.

### 4.3 Double Ratchet

Comme la [spécification de Signal](https://signal.org/docs/specifications/doubleratchet/),
avec :

    KDF_RK(rk, dh) = HKDF-SHA256(sel = rk, ikm = dh, info = "Brocolis_v3_Ratchet", 64)
                     → (rk', ck)
    KDF_CK(ck)     = mk = HMAC-SHA256(ck, 0x01), ck' = HMAC-SHA256(ck, 0x02)
    ENCRYPT(mk)    = HKDF-SHA256(sel = 32 × 0x00, ikm = mk,
                                 info = "Brocolis_v3_MessageKeys", 44)
                     → clé AES (32) ‖ nonce (12)
    en-tête        = DH_pub (32) ‖ PN (u32 BE) ‖ N (u32 BE)
    chiffré        = AES-256-GCM(clé, nonce, pad(texte), AD ‖ en-tête)
    MAX_SKIP       = 1 000 clés sautées gardées par session, 30 jours au plus

Les messages arrivent dans le désordre (copier-coller, hors ligne) : les clés
sautées sont gardées puis effacées à l'usage. Un message rejoué ne trouve plus
sa clé : il est ignoré.

### 4.4 Le jeton v3

    [[BROCOLIS:v3:<base64>]]

    octet 0 : type — 0x01 premier message (établit la session), 0x02 message
    octets  : identifiant de session (8) = SHA-256(AD)[0..8]
    type 0x01 : IK_A (32) ‖ EK_A (32) ‖ pq (1) ‖ [CT (1 088) si pq] ‖ id SPK (4) ‖ id PQPK (4)
    puis    : en-tête (40) ‖ chiffré

L'initiateur envoie des messages de type 0x01 tant qu'il n'a pas reçu de
réponse (comme les « prekey messages » de Signal).

### 4.5 Groupes : Sender Keys

Chaque membre tire une **chaîne d'envoi** (clé de chaîne 32 octets + clé de
signature Ed25519) et la distribue à chaque membre par sa session v3 à deux
(type 0x03). Ses messages de groupe (type 0x04) :

    id de groupe (16) ‖ id d'expéditeur (8) ‖ itération (u32 BE) ‖ chiffré ‖ signature Ed25519 (64)

La chaîne avance par `KDF_CK` (confidentialité persistante dans la chaîne).
Un membre qui part : chacun tire une chaîne neuve (le partant ne lit plus
rien). C'est le modèle de Signal ; MLS (RFC 9420) est la cible pour les très
grands groupes.

### 4.6 Compatibilité

- v1 et v2 restent **lisibles** pour toujours.
- Tout ce qui part est en **v3** dès qu'une session existe (ou peut être
  établie : paquet connu, ou clé partagée → §4.2).
- Talka : le relais transporte le paquet de clés (§4.1) ; les enveloppes de
  Talka portent des jetons v3 à l'intérieur de son propre chiffrement.

## 5. Phase 3 — vérifier et durcir encore

- Post-quantique partout où l'OS le permet (§4.2), et affiché.
- **Alerte de changement de clé** : un IK différent pour un contact connu
  bloque l'envoi jusqu'à une nouvelle vérification (comme Signal).
- En-têtes de ratchet chiffrés (header encryption) : un observateur ne relie
  plus les messages d'une même session.
- Fuzzing continu, revue de sécurité extérieure du protocole avant de le dire
  « stable ».

## 6. Implémentations et vecteurs

| Brique | Rust (référence) | Swift | Kotlin |
|---|---|---|---|
| Jetons v1/v2, clé protégée v1 | `talka-web::jetons` | TalkaKit `Brocolis`, Brocolis `BrocolisCrypto` | `BrocolisCrypto.kt` |
| Clé protégée v2 (Argon2id), padding | `brocolis-proto` (à venir) | | |
| v3 (établissement, ratchet, Sender Keys) | `brocolis-proto` | | |

Toute implémentation passe les vecteurs de la référence Rust **et** les
vecteurs Wycheproof des primitives. Aucune n'est publiée sans eux.
