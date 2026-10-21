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
| Navigateur (Talka) — toute la mémoire | chaque valeur d'IndexedDB (messages, contacts, clés…) chiffrée AES-256-GCM, liée à sa clé de ligne ; la clé de l'appareil est chiffrée par une clé WebCrypto **non extractible** (base `talka-cle`) et, au choix, **par le code de verrouillage (Argon2id) ou une passkey (WebAuthn PRF)** | sans le code ou la passkey, les fichiers ne s'ouvrent pas, même avec le navigateur |
| iPhone (Talka) — toute la mémoire | chaque JSON de la base SQLite chiffré AES-256-GCM (CryptoKit), lié à son magasin et sa clé ; clé de l'appareil dans le trousseau (`AfterFirstUnlockThisDeviceOnly`, groupe de l'application) : **chiffrée par le code de verrouillage** (Argon2id, comme le navigateur) dès qu'il y en a un, avec au choix une copie **liée aux visages enregistrés** (`biometryCurrentSet`) ; sans code, au choix **derrière Face ID** (`userPresence`, Secure Enclave) ; base exclue des sauvegardes, `secure_delete` | protection de données d'iOS **et** trousseau (**et** le code ou Face ID) : ni une copie du fichier, ni une sauvegarde, ni l'appareil déverrouillé sans le code ou le visage ne montrent rien |

Talka affiche les messages en clair ; seul le disque est chiffré. Les
données d'avant sont réécrites chiffrées au premier lancement (sur
l'iPhone, suivi d'un `VACUUM`).

**La clé de l'appareil protégée par l'utilisateur.**

- *Code de verrouillage (navigateur)* : 6 à 12 chiffres pour un nouveau
  code (les codes trop simples sont refusés), haché par **Argon2id**
  (19 Mio, 2 passes) ; la clé de l'appareil est chiffrée par
  `Argon2id(code, sel)` (AES-256-GCM, AD `talka-cle`). Talka demande le code
  **avant** d'ouvrir sa mémoire. Les codes d'avant (PBKDF2) sont rehachés
  au premier déverrouillage, et la clé de l'appareil est alors protégée.
  Les essais manqués sont comptés même après un rechargement.
- *Passkey (navigateur)* : l'extension PRF de WebAuthn rend 32 octets que
  seul l'authentificateur sait calculer ; `HKDF(prf, "talka-passkey",
  "talka-cle-v1")` chiffre la clé de l'appareil. Rien de ce secret n'est
  gardé. La passkey perdue, seule une sauvegarde rend les messages.
- *Code de verrouillage (iPhone, iPad)* : le même que dans le navigateur.
  La clé de l'appareil n'est plus gardée qu'enveloppée par
  `Argon2id(code, sel)` (AES-256-GCM, AD `talka-cle`, élément `cle-code`
  du trousseau ; vecteur `cle` de `vectors.json`, ouvert des deux côtés).
  Talka demande le code **avant** d'ouvrir sa base. Face ID peut le
  remplacer par une seconde copie gardée avec `biometryCurrentSet` : un
  visage enregistré, ni le code de l'iPhone ni un visage ajouté plus tard.
  Un code d'avant cette version chiffre la clé au premier déverrouillage
  où il est tapé.
- *Face ID sans code (iPhone)* : la clé est gardée avec un contrôle d'accès
  `userPresence` (Face ID, Touch ID ou code de l'iPhone), vérifié par la
  Secure Enclave ; la nouvelle copie est écrite avant que l'ancienne soit
  effacée.
- *Partout* : au choix, **tout est effacé après 10 codes faux** (le
  réglage est gardé hors de la mémoire chiffrée, pour être lu avant
  qu'elle s'ouvre) ; « Code oublié ? » efface tout pour repartir d'une
  sauvegarde. Les essais sont espacés après 5 erreurs (15 s, 30 s… 5 min).
- *L'écran (iPhone, iPad)* : un écran neutre dans le sélecteur d'apps, et
  Talka se cache pendant un enregistrement, une recopie (AirPlay) ou un
  partage de l'écran. *Les notifications* (tous) : l'aperçu montre le nom
  et le message, le nom seulement, ou rien (« Nouveau message »), y compris
  celles du service worker.

Limites : un code à 6 chiffres reste devinable hors ligne par qui a copié
les fichiers (Argon2id le ralentit beaucoup, sans l'empêcher) ; la passkey
ou Face ID ne le sont pas. Dans le navigateur, d'anciennes pages
d'IndexedDB peuvent garder du texte d'avant tant que le navigateur ne les
a pas compactées. La sauvegarde Talka est chiffrée par son mot de passe,
désormais avec **Argon2id** (46 Mio, 1 passe ; `talka: 3`) ; les
sauvegardes d'avant (PBKDF2, `talka: 2`) s'ouvrent toujours.

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
doit pas épuiser la mémoire), et aussi t > 16, p = 0 et m < 8p (un jeton
piégé ne doit pas non plus tourner des heures ; voir §7.1).

### 3.3 Remplissage (padding)

La longueur d'un message ne doit pas se lire sur le jeton. Avant
chiffrement : texte UTF-8 ‖ `0x80` ‖ des `0x00` jusqu'au **multiple de 160
octets** suivant (comme Signal) : longueur totale = (⌊n / 160⌋ + 1) × 160,
donc 159 octets → 160, 160 → 320. Au déchiffrement : refuser une longueur qui
n'est pas un multiple non nul de 160, retirer les `0x00` de fin puis le
`0x80` ; absent → refusé (§7.2). S'applique à v3 (§4) ; v1/v2 restent tels
quels pour rester lisibles par les anciennes versions.

### 3.4 L'écran et le presse-papiers

- iPhone et Android : contenu masqué dans le sélecteur d'apps ;
  `FLAG_SECURE` sur Android (pas de capture) ; sur iPhone, les clés et
  phrases ne s'affichent jamais en clair.
- Presse-papiers : local à l'appareil (pas de presse-papiers universel),
  expiration après 2 minutes (iOS) ; marqué « sensible » (Android 13+).

### 3.5 Passkey (WebAuthn PRF) — fait, voir §3.1

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
    AD = IK_A ‖ IK_B          (les clés X25519 d'identité, 32 + 32 octets)

Puis le Double Ratchet (§4.3) part comme chez Signal : la clé de cliquet
initiale de B est sa SPK ; A tire une clé de cliquet neuve et calcule
`(RK, CKs) = KDF_RK(SK, X25519(cliquet_A, SPK_B))`. Toute clé publique X25519
reçue doit être canonique (bit 255 nul, u < 2²⁵⁵ − 19) et tout DH nul est
refusé (§7.3).

Si l'un des appareils ne sait pas faire ML-KEM (iPhone avant iOS 26), le
drapeau `pq = 0` retire `SS` et l'info devient `"Brocolis_v3_X25519_SHA-256"`.
L'appli l'affiche (« session non post-quantique »).

**Clé Brocolis partagée sans appairage** : la clé symétrique sert de secret
pré-partagé, `SK = HKDF(sel = 32 × 0x00, ikm = clé, info =
"Brocolis_v3_PSK_SHA-256", 32)`, et le ratchet Diffie-Hellman ajoute la
confidentialité persistante dès le premier aller-retour. Une telle session
vaut ce que vaut la clé partagée tant que personne n'a répondu. La clé de
cliquet initiale du répondeur, l'AD et le premier message sont précisés au
§7.4 (mode `0x02`).

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
    type 0x01 : IK_A (32) ‖ EK_A (32) ‖ mode (1) ‖ [CT (1 088) si mode = 1] ‖ id SPK (4) ‖ id PQPK (4)
    puis    : en-tête (40) ‖ chiffré
    mode    : 0x00 X25519 seul (id PQPK = 0), 0x01 X25519 + ML-KEM-768,
              0x02 clé partagée (IK_A, id SPK, id PQPK nuls ; §7.4)
    chiffré : 160·k + 16 octets, k ≥ 1 ; toute autre longueur est refusée
    type 0x03 : comme 0x02, données associées AD ‖ en-tête ‖ 0x03 (§7.6)

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
| Clé protégée v2 (Argon2id), padding | `brocolis-proto` (`proto/`) | | |
| v3 (établissement, ratchet, Sender Keys) | `brocolis-proto` (`proto/`) | | |

Les vecteurs de référence sont `proto/vectors.json` (regénérés par
`cargo run --example vectors`, vérifiés indépendamment par
`proto/scripts/verifier_vecteurs.py` avec OpenSSL).

Toute implémentation passe les vecteurs de la référence Rust **et** les
vecteurs Wycheproof des primitives. Aucune n'est publiée sans eux.

## 7. Précisions de l'implémentation de référence

Ce que la spécification laissait ouvert, et la façon dont `brocolis-proto` le
tranche. Chaque point fait partie du contrat : les portages font pareil.

### 7.1 Clé protégée v2

- Argon2id version 0x13, sans secret ni données associées Argon2, sortie
  32 octets.
- Bornes à l'import : 19 456 ≤ m ≤ 1 048 576, 2 ≤ t ≤ **16**, p ≥ **1**,
  m ≥ 8p. Les bornes en gras sont ajoutées : t ≤ 255 avec 1 Gio ferait
  tourner un jeton piégé pendant des dizaines de minutes ; p = 0 et m < 8p
  sont refusés par Argon2 lui-même.
- Export : phrase de 8 caractères au moins (comme la v1). Dans un texte
  collé, `[[BROCOLIS-CLE:v2:` est cherché avant `v1`.
- Les vecteurs donnent m = 19 456, t = 2, p = 1 (rapide) **et** le défaut.

### 7.2 Remplissage

Longueur totale (⌊n / 160⌋ + 1) × 160 (au moins un octet de remplissage). Au
retrait, une longueur qui n'est pas un multiple non nul de 160 est refusée :
plus strict que le texte d'origine, sans effet sur l'interopérabilité
(un émetteur conforme produit toujours un multiple).

### 7.3 Paquet de clés (§4.1) et établissement (§4.2)

Format binaire v1 du paquet :

    octet 0      : 0x01 (version)
    1–32         : IK X25519        33–64 : IK Ed25519
    65–68        : id SPK (u32 BE)  69–100 : SPK
    101–164      : Ed25519(IK, "Brocolis_v3_SPK" ‖ IK X25519 ‖ id SPK ‖ SPK)
    165          : 0x00 sans ML-KEM, 0x01 avec
    si 0x01 :
      166–169    : id PQPK (u32 BE, non nul)
      170–1353   : PQPK (clé d'encapsulation ML-KEM-768)
      1354–1417  : Ed25519(IK, "Brocolis_v3_PQPK" ‖ IK X25519 ‖ id PQPK ‖ PQPK)

- La spécification dit « l'empreinte porte sur IK » et « signé par IK » :
  une clé X25519 ne signe pas. Les signatures sont faites par IK Ed25519 et
  portent IK X25519 (ce qui lie les deux moitiés de l'identité) ; l'empreinte
  et le numéro de sécurité portent sur **IK X25519 ‖ IK Ed25519** :
  `SHA-256(IK X25519 ‖ IK Ed25519)[0..8]`, écrit `XXXX-XXXX-XXXX-XXXX`.
- Vérification Ed25519 **stricte** (RFC 8032 §5.1.7 : S canonique, R et A
  pas de petit ordre). La PQPK doit être une clé ML-KEM valide (contrôle du
  module de FIPS 203).
- Secret ML-KEM gardé sous sa forme FIPS 203 « graine » `d ‖ z` (64 octets).
  L'aléa d'encapsulation `m` (32 octets) est tiré par l'appelant ; les
  vecteurs le donnent (CryptoKit n'accepte pas de `m` imposé : un portage
  vérifie alors par la décapsulation).
- AD = IK_A ‖ IK_B avec les clés **X25519** d'identité.
- La clé de cliquet initiale de B est SPK_B ; celle de A est tirée à part
  (pas EK_A).
- Toute clé publique X25519 reçue doit être **canonique** (bit 255 nul,
  u < 2²⁵⁵ − 19), et un DH nul (clé de petit ordre) est refusé. Sans cela,
  EK_A avec le bit 255 levé donne les mêmes DH : un premier message rejoué
  ainsi « réécrit » passait la mémoire des clés éphémères (trouvé par les
  tests d'altération).
- **Rejeu d'un premier message** : sans clés pré-signées à usage unique
  (les « one-time prekeys » de Signal), un premier message rejoué rouvrirait
  la session tant que la SPK vit. Le répondeur garde donc les EK déjà vues
  (4 096 au plus, 37 jours = la vie d'une SPK) et refuse un premier message
  dont EK a déjà servi. Ajouter des clés à usage unique (Talka : publiées sur
  le relais) fermerait ce point pour de bon.
- Le mode `0x00` (sans ML-KEM) est accepté même si les deux appareils savent
  faire ML-KEM : un attaquant ne peut pas le forcer (il faudrait le secret
  d'IK_A), c'est un choix de l'initiateur, que l'appli affiche.
- Renouvellement : SPK et PQPK neuves chaque semaine, les anciennes gardées
  30 jours après leur remplacement, puis effacées.

### 7.4 Clé partagée (PSK)

La spécification ne disait ni la clé de cliquet initiale du répondeur, ni
l'AD, ni le premier message. Choix :

    SK = HKDF(sel = 32 × 0x00, ikm = clé, "Brocolis_v3_PSK_SHA-256", 32)
    r  = HKDF(sel = 32 × 0x00, ikm = SK, "Brocolis_v3_PSK_Ratchet", 32)   (secret X25519, connu des deux)
    R  = X25519(r, 9)
    AD = EK_A ‖ R
    A : clé de cliquet initiale = EK_A ; (RK, CKs) = KDF_RK(SK, X25519(EK_A, R))
    B : clé de cliquet initiale = (r, R)
    premier message : mode 0x02, IK_A = 32 × 0x00, id SPK = id PQPK = 0,
                      en-tête.DH = EK_A (vérifié)

Le répondeur essaie chacune de ses clés : l'identifiant de session
(`SHA-256(AD)[0..8]`) dit laquelle. Il garde les EK vues (comme §7.3). Dès
que B répond (clé de cliquet neuve), quelqu'un qui n'a que la clé partagée ne
lit plus rien. **Deux personnes seulement** : une clé connue de trois
personnes ou plus reste en v1/v2 (plusieurs répondeurs se disputeraient la
même session).

### 7.5 Double Ratchet

- `MAX_SKIP` = 1 000 : un message qui demanderait de sauter plus de 1 000
  clés d'un coup est refusé, et la session ne garde jamais plus de 1 000 clés
  sautées (les plus anciennes partent d'abord) ; une clé sautée de plus de
  30 jours est effacée. L'heure est fournie par l'appelant (secondes Unix).
- Déchiffrement **transactionnel** : un échec (altération, rejeu, saut trop
  grand) laisse l'état exactement tel quel.
- Rejeu reconnu : numéro déjà passé sur la chaîne courante, ou clé de
  cliquet distante parmi les 16 dernières, ou notre propre clé de cliquet ;
  l'appli l'ignore sans bruit.
- Compteurs u32 : une chaîne épuisée (2³² messages) refuse d'envoyer.
- Un premier message (0x01) de même identifiant de session mais d'une autre
  EK est une nouvelle session (A a perdu la sienne) : `NouvelleSession`, à
  accepter comme un premier message.
- Tailles : texte de 200 Kio au plus, jeton décodé de 256 Kio au plus.

### 7.6 Distribution (type 0x03)

    0x03 ‖ id session (8) ‖ en-tête (40) ‖ chiffré
    chiffré = AES-256-GCM(clé, nonce, pad(distribution), AD ‖ en-tête ‖ 0x03)
    distribution (92) = id groupe (16) ‖ id expéditeur (8) ‖ itération (u32 BE)
                        ‖ clé de chaîne (32) ‖ clé publique Ed25519 (32)

Le `0x03` final authentifie le type : sinon un 0x03 relu comme 0x02
afficherait la clé de chaîne comme un texte. Pour 0x01/0x02 le type n'est
pas authentifié (le changer ne sert à rien : 0x01 → 0x02 retire le préambule
d'un message déjà valide). **Proposition pour une future version** :
`AD ‖ type ‖ en-tête` pour tous les types. Un 0x03 n'a pas de préambule : un
initiateur non confirmé l'envoie après un 0x01 ; reçu avant, il donne
`SessionInconnue` et l'appli le garde pour plus tard.

### 7.7 Groupes (type 0x04)

- id expéditeur = `SHA-256(clé publique Ed25519 de la chaîne)[0..8]` (il
  change à chaque chaîne neuve) ; id groupe : 16 octets aléatoires.
- `mk` de l'itération i par `KDF_CK`, puis `ENCRYPT(mk)` du §4.3 (même info
  `Brocolis_v3_MessageKeys`) ; données associées AES-GCM = les 29 premiers
  octets (type ‖ id groupe ‖ id expéditeur ‖ itération) ; signature
  Ed25519 (stricte) de tout ce qui précède, type compris, **vérifiée
  avant** tout le reste.
- Mêmes règles que §7.5 : `MAX_SKIP`, 30 jours, rejeu ignoré,
  transactionnel.

### 7.8 Notifications (Web Push)

Quand un destinataire n'a aucune connexion ouverte, le relais peut réveiller
ses navigateurs par Web Push (RFC 8030, VAPID RFC 8292). La notification
montre **qui écrit et quoi** (« Léa : On se voit à 19 h ? »), mais cet
aperçu est chiffré de bout en bout : ni le relais ni le service de
notifications du navigateur (Google, Mozilla, Apple, Microsoft) ne peuvent
le lire.

**La clé de notification.** Le service worker de chaque navigateur tire une
paire P-256 (WebCrypto, partie privée **non exportable**, gardée dans son
IndexedDB `talka-notifications`). La moitié publique `nk` (65 octets,
base64) part chez les contacts dans le profil (`{"k":"profile",…,"nk"}`) ;
les versions qui ne la connaissent pas l'ignorent. Une clé qui n'est pas un
point P-256 valable est refusée.

**L'aperçu.** Pour un vrai message (`wake`) à un contact qui a donné sa clé,
l'expéditeur compose `{"n": son nom choisi, "g": le groupe, "t": texte,
"c": discussion}` — le même texte que la liste des discussions : une photo
dit « 📷 Photo », une capsule temporelle « ⏳ Capsule temporelle » (jamais
son contenu), un jeton Brocolis reste masqué ; 40 caractères pour les
noms, 120 pour le texte. Il le chiffre, **authentifié par sa clé
d'identité** :

```
eph      = P-256 éphémère
ikm      = ECDH(eph, nk) ‖ ECDH(dh de l'expéditeur, nk)
clé      = HKDF-SHA256(ikm, salt = eph_pub (65) ‖ nk (65), info = "talka-apercu-v2:" ‖ code, 32)
apercu   = base64(0x02 ‖ code (10) ‖ eph_pub ‖ iv (12) ‖ AES-256-GCM(clé, iv, json, aad = "talka-apercu:" ‖ code))
```

et l'ajoute à l'envoi (`"apercu"`, 2 048 octets au plus). Le relais le
recopie, sans pouvoir l'ouvrir, dans `{"a": apercu}` qu'il chiffre à son
tour pour l'abonnement du navigateur (RFC 8291, `aes128gcm`, avec les clés
`p256dh` et `auth` de l'abonnement) : le service de notifications ne voit
qu'un bloc chiffré. Le service worker ne l'ouvre que pour un **contact de
son carnet** (code, `dh` et le nom que vous lui avez donné, transmis par
Talka et gardés chiffrés par une autre clé non exportable) : un inconnu ou
quelqu'un qui se ferait passer pour un contact ne fait rien afficher. Il
montre le nom que vous lui avez donné (sinon `~` et son nom choisi), et
« Nouveau message » s'il n'y arrive pas.

Limites, assumées :

- Pas de confidentialité persistante pour les aperçus : qui volerait un
  jour la clé de notification d'un navigateur (elle ne sort pas de
  WebCrypto, mais un appareil compromis…) pourrait lire les aperçus
  enregistrés plus tôt. Le message lui-même reste protégé par le double
  cliquet (§4.3).

Le reste ne change pas :

- L'expéditeur marque `wake` ses vrais messages (pas les accusés, réactions
  ni profils) : le relais l'apprend, en plus de ce qu'il voyait déjà.
- Le relais ne contacte que les services connus (liste fermée d'hôtes en
  HTTPS, sans port ni redirection) : pas de requête vers une adresse choisie
  par un client.
- Un réveil vide par code toutes les 20 secondes au plus (`Topic` fusionne
  ceux qui attendent) ; avec un aperçu, une seconde au moins entre deux ;
  `TTL` d'un jour.
- Les adresses et leurs clés ne vivent qu'en mémoire (4 par code), redonnées
  à chaque connexion ; une adresse disparue (404, 410) est oubliée.
- Talka à l'écran : pas de notification système, l'application s'en
  occupe.

### 7.9 Expéditeur scellé (v4)

But : le relais ne sait plus **qui écrit**. Il sait toujours à quel code
il livre (§4 de la feuille de route v4 : les boîtes aux lettres
tournantes), qui est connecté, et l'adresse IP des connexions.

**Jeton de remise, un par contact.** Chaque contact reçoit son propre jeton
`nonce.signature` (base64) : `nonce` = 16 premiers octets de
`SHA-256("talka-jeton-nonce:" ‖ clé privée ECDH ‖ ":<code du contact>:<époque>")`
(rien à garder) ; `signature` = ECDSA P-256 (`r ‖ s`) de
`talka-jeton:<code>:<nonce>` par la clé de signature du code. Il voyage dans
le profil chiffré (`{"k":"profile",…,"jeton"}`), donné seulement à un
contact **enregistré et pas bloqué** ; il est vérifié avant d'être gardé
(`Contact.jeton`). Le relais le vérifie à chaque envoi anonyme, avec les
deux clés publiques du destinataire (dont l'empreinte doit être son code).
Sans jeton, pas d'envoi anonyme vers ce code : un inconnu ne peut pas
inonder une boîte sans se présenter.

**Révocation.** Bloquer un contact révoque son jeton : son nonce rejoint la
liste des jetons révoqués de l'appareil (`{"t":"revoke","nonces":[…]}`,
1 000 au plus), que le relais garde en mémoire et qui lui est redonnée à
chaque connexion ; l'époque du contact avance (débloqué, il reçoit un
nouveau jeton). L'ancien jeton commun à tous (avant cette version) est
révoqué de la même façon. Un relais qui vient de redémarrer ne connaît pas
encore les révocations d'un appareil hors ligne : le dépôt passe, mais
l'appareil ignore de toute façon les messages d'un contact bloqué.

**Enveloppe scellée.** Autour de l'enveloppe ordinaire `E` (§7.2–7.6, la
clé de paire et ses données associées `expéditeur>destinataire`, v3 compris)
:

- `eph` : une clé P-256 éphémère ; `Z = ECDH(eph, dh_destinataire)` ;
- `k = HKDF-SHA256(sel = eph_pub ‖ dh_destinataire_pub, ikm = Z,
  info = "talka-scelle-v1:<code destinataire>")`, 32 octets ;
- `scellé = base64(eph_pub (65) ‖ iv (12) ‖ AES-256-GCM(k, iv,
  {"from":<code>,"body":E}, ad = "talka-scelle:<code destinataire>"))`.

Le destinataire l'ouvre, lit `from`, puis ouvre `E` avec la clé de paire de
`from` : **c'est `E` qui prouve l'auteur**. Annoncer un autre `from` donne
une enveloppe intérieure qui ne s'ouvre pas (testé des deux côtés).

**Transport.** Une seconde WebSocket qui répond `{"t":"anon"}` au défi au
lieu de se présenter : elle ne peut que déposer
(`{"t":"send","id","to","sign","dh","jeton","body","live","wake"}`). Le
relais livre `{"t":"msg",…,"sealed":true}`, sans `from`. Un relais qui ne
l'annonce pas (`ready.sealed`) ne reçoit que des envois présentés.

**Ce qui passe encore en clair pour le relais.** Le premier message à un
inconnu (on n'a pas encore son jeton) ; la présence et les codes suivis
(`watch`), les demandes de clés (`lookup`, `fetch-bundle`) ; l'adresse IP
(la connexion anonyme vient de la même que la connexion présentée : un
relais malveillant peut les rapprocher, comme pour Signal). Un jeton refusé
est oublié et l'envoi repasse par la connexion présentée ; une connexion
anonyme indisponible fait réessayer plus tard, jamais repasser en clair.

**Compatibilité.** Les anciennes versions ignorent le champ `jeton` du
profil : on ne leur écrit jamais scellé, et elles n'en reçoivent jamais.
Elles vérifient un jeton par contact comme l'ancien (la signature porte sur
n'importe quel nonce).

**Plafond par adresse IP.** 100 connexions à la fois par adresse (un /64 en
IPv6), vue à travers le proxy (dernière entrée de `X-Forwarded-For` sur
Heroku) ; au-delà, la connexion est fermée (`4005`). Le bouclier limite en
plus les requêtes HTTP (dont l'ouverture des WebSocket) à 30 par seconde et
par adresse.

### 7.10 Pièces jointes en v3, et le rejeu

**Pièces jointes.** Un jeton v3 porte 200 Kio au plus. Pour une photo, un
vocal ou un fichier plus gros, vers un contact dont la version l'annonce
(`{"k":"profile",…,"pj":true}`) :

```
k        = 32 octets aléatoires (pour ce message seulement)
pièce    = iv (12) ‖ AES-256-GCM(k, iv, données, ad = "talka-pj:" ‖ id du message)
message  = le même, avec data = "talka-pj:" ‖ base64(k), envoyé en v3
enveloppe = <enveloppe v3 du message> "|" base64(pièce)
```

La clé voyage dans le message v3 (double cliquet, confidentialité
persistante) ; la pièce ne s'ouvre qu'avec elle, et seulement pour ce
message. Une pièce absente ou abîmée laisse les données vides.

**L'ancien format n'est plus accepté d'un contact à jour.** Un contact qui
annonce `pj` n'envoie plus que du v3 (hors signaux en direct : « écrit… »
et appels vers un appareil hors ligne ou masqué) : sans session ni paquet,
son message attend dans la boîte d'envoi que le destinataire republie son
paquet, plutôt que de partir dans l'ancien format. De lui, l'ancien format
est refusé. D'un contact passé en v3 (sans `pj`), l'ancien format ne change
plus d'état (minuterie, modification, suppression, groupe, appel).

**Pierres tombales.** Un message effacé (pour moi, éphémère, trop vieux,
discussion vidée ou supprimée) laisse son identifiant 31 jours : un rejeu ne
le fait pas revenir. Un message dans l'ancien format daté de plus de 31
jours est refusé d'office (le relais ne garde rien plus de 30 jours : c'est
un rejeu). Une offre d'appel déjà vue (les 200 dernières) ne fait pas
sonner une seconde fois.

### 7.11 Plusieurs appareils (v4)

But : le même compte (le même code) sur l'iPhone, l'iPad et un navigateur
en même temps, chaque appareil avec **ses propres clés de session**, chaque
message chiffré pour chacun.

**Ce qui est commun, ce qui est propre.** L'identité Talka (clés P-256 de
signature et d'échange, donc le code) est la même sur tous les appareils
du compte, comme la clé d'identité chez Signal : elle signe, elle ouvre
l'ancien format et l'expéditeur scellé. Chaque appareil a en plus :

- un identifiant `dev`, 8 octets aléatoires en hexadécimal (16 caractères),
  tiré à la création du compte, à la liaison ou à la restauration d'une
  sauvegarde (jamais copié : `kv/dev` ne part pas dans la sauvegarde) ;
- son appareil v3 (§4.1 : IK, SPK, PQPK), donc ses propres sessions à
  double cliquet ; son adresse est `<code>/<dev>` (l'adresse d'un appareil
  d'avant la v4 reste `<code>`) ;
- sa clé de notification (§7.8) et son abonnement Web Push.

**La liste des appareils** (`kv/appareils`) : `{"v", "list":[{"dev",
"name", "at", "nk"}]}`, six appareils au plus. Elle change à la liaison et
au retrait d'un appareil (`v` + 1), passe aux autres appareils du compte
(`sync`, ci-dessous) et aux contacts, dans le profil chiffré :
`{"k":"profile",…,"devs":[{"d":<dev>,"nk":<clé>}],"dv":<v>}`. Un contact
garde la liste de plus grand `dv`. Un ancien client l'ignore.

**Les paquets, appareil par appareil.** Chaque appareil publie son paquet
v3 avec son `dev` ; la signature ECDSA de l'identité porte alors sur
`talka-v4-appareil:<dev>:` ‖ paquet, ce qui lie le paquet à cet appareil.
Le relais en garde six par code et les rend tous (`fetch-bundle` →
`"devices":[{"dev","bundle","sig"}]`), en plus du champ habituel pour les
anciens clients. Un relais ne peut pas ajouter d'appareil (il n'a pas
l'identité) ; il peut en cacher un, ou en rejouer un retiré : l'expéditeur
n'écrit qu'aux appareils de la dernière liste annoncée par le compte
(`devs`), dont il a un paquet bien signé.

**Les envois.** À un contact qui annonce sa liste : une copie par appareil,
chiffrée par la session v3 de cet appareil (`<code>/<dev>`), déposée pour
lui (`"dev"` dans `send`, scellé ou non). Sinon, comme avant. Aux autres
appareils du compte : une copie `sync` (ci-dessous), par la session v3
entre les deux appareils. L'enveloppe scellée (§7.9) dit en plus de quel
appareil elle vient : `{"from","fd","body"}` ; une enveloppe présentée le
porte dans `"fd"`, ajouté par le relais. Les accusés de réception disent
quel appareil a reçu (`"dev"`) : un message attend dans la boîte d'envoi
tant qu'un appareil de la liste ne l'a pas reçu, et repart vers lui si le
relais redémarre.

**La synchronisation entre ses appareils** :
`{"k":"sync","op","c","w","d"}`, accepté seulement d'un appareil du compte
(`from` = notre code, `fd` dans notre liste) :

| `op` | Effet |
|---|---|
| `wire` | `w` (un `Wire` que l'appareil a envoyé dans la discussion `c` : message, réaction, modification, suppression, groupe, minuterie, épingle), appliqué comme venant de nous |
| `read` | la discussion `c` est lue jusqu'à `d.at` (pas de nouvel accusé) |
| `contact` | un contact ajouté, renommé, bloqué (`d`) |
| `chat` | l'état d'une discussion : archivée, épinglée, en sourdine, vidée, supprimée (`d`) |
| `forget` | des messages effacés pour moi (`d.ids`) |
| `profile` | notre nom, notre actu, notre photo (`d`) |
| `devices` | la liste des appareils (`d`) ; la plus grande `v` l'emporte |
| `status` | une actu publiée (`d`) |
| `call` | un appel pris ou refusé sur cet appareil (`d.id`) : les autres arrêtent de sonner |
| `unlink` | cet appareil est retiré : il efface tout |

**Le relais.** `hello` porte `"dev"` ; les boîtes sont par appareil
(`<code>/<dev>`). Un envoi sans `dev` (un ancien client) est copié dans la
boîte de chaque appareil connu du code (connecté ou ayant publié son
paquet depuis le démarrage du relais), sinon dans la boîte du code.
`{"t":"unlink","dev"}` (d'un appareil du code) oublie le paquet et la boîte
de cet appareil, coupe ses connexions et refuse son `hello` jusqu'au
redémarrage du relais. Les abonnements Web Push sont par appareil.

**Retirer un appareil** : il reçoit `sync`/`unlink` et efface tout ; il
quitte la liste (`v` + 1), annoncée aux contacts. **Limite** : un appareil
lié détient l'identité du compte. Volé avant d'être retiré, il peut
continuer d'écrire au nom du compte et se réannoncer ; le seul remède sûr
est alors un nouveau code (comme pour un téléphone volé chez Signal avant
la déconnexion de ses appareils).

### 7.12 La liaison par QR code (v4)

1. Le **nouvel appareil** N tire `dev`, un identifiant de liaison `lid`
   (16 octets) et une clé P-256 éphémère `lk`. Il montre
   `talka-lien:<base64url(0x01 ‖ dev (8) ‖ lid (16) ‖ lk_pub (65))>`, en QR
   code et en texte, et attend (connexion anonyme) dans la boîte
   `base64url(SHA-256("talka-lien:" ‖ lid)[0..16])`.
2. L'**appareil du compte** E lit le QR code (ou colle le texte), tire une
   clé éphémère `ek`, et calcule
   `K = HKDF-SHA256(sel = lid, ikm = ECDH(ek, lk_pub),
   info = "talka-lien-v1:" ‖ ek_pub ‖ lk_pub, 32)`.
3. E chiffre ce que N doit savoir : l'identité, la liste des appareils
   (avec N), le profil, les contacts, les discussions et leurs messages
   (les pièces jointes de plus de 512 Kio en sont retirées, 32 Mio en
   tout) ; jamais son appareil v3, ses sessions, son `dev` ni sa clé de
   notification. Le JSON est coupé en morceaux de 2 Mio :
   `{"e":ek_pub,"i","n","c":base64(iv ‖ AES-256-GCM(K, iv, morceau,
   ad = "talka-lien:" ‖ lid ‖ ":<i>/<n>"))}`, déposés dans la boîte de
   liaison.
4. Les deux appareils affichent un **code de vérification** à 6 chiffres,
   `u32(SHA-256("talka-lien-verif:" ‖ ek_pub ‖ lk_pub)[0..4]) mod 10⁶`, à
   comparer ; N ne garde le compte qu'après avoir tout ouvert.
5. N range tout, publie son paquet ; E annonce la nouvelle liste à ses
   autres appareils et aux contacts.

Seul qui voit le QR code peut répondre à N : la clé `lk` ne sort pas
d'ailleurs. Le relais ne voit qu'une boîte anonyme et des morceaux
illisibles ; il les oublie au bout d'une heure.

### 7.13 Boîtes aux lettres tournantes (v4)

But : entre contacts, le relais ne voit plus **à qui** un message est
adressé, seulement une boîte pseudonyme qui change chaque jour.

**Liées au relais.** Talka n'a plus qu'un relais (`OFFICIAL`) : son
adresse entre dans la dérivation, et les boîtes ne valent que pour lui.

    k_paire = HKDF-SHA256(sel = "talka-boites-v1:" ‖ <relais>,
                          ikm = ECDH(dh_A, dh_B) (32 octets),
                          info = min(A, B) ‖ ":" ‖ max(A, B), 32)
    boîte(A → B/dev, jour) = base64url(HMAC-SHA256(k_paire,
                          "talka-boite:" ‖ B ‖ "/" ‖ dev ‖ ":" ‖ jour)[0..16])
    jour = ⌊ms Unix / 86 400 000⌋ (UTC)

Seuls A et B savent calculer `k_paire` ; une boîte par sens, par appareil
destinataire et par jour. `<relais>` est l'adresse du relais officiel,
`https://talka-maxlestage-e497dd868a8c.herokuapp.com`.

**Envoi.** Vers un appareil d'un contact qui l'annonce (`"bx":true` dans
le profil) : l'enveloppe scellée (§7.9), déposée par la connexion anonyme
dans `{"t":"send","id","boite","body","live","wake","apercu"}` — ni code,
ni jeton : connaître la boîte prouve qu'on est le contact.

**Réception.** Chaque appareil ouvre une connexion anonyme qui s'abonne à
ses boîtes : `{"t":"boites","list":[…],"push"?}`, pour chaque contact et
chaque jour depuis la dernière réception (un jour de marge, 30 au plus)
jusqu'à demain ; puis chaque jour. Le relais rend ce qui attend
(`{"t":"msg","n","boite","body","ts","sealed":true}`) puis ce qui arrive ;
`got` l'oublie. Un message n'est accepté que si l'enveloppe scellée vient
bien du contact de cette boîte.

**Ce que le relais voit encore** : l'adresse IP des connexions (la
connexion anonyme vient de la même adresse que celle qui se présente, et
peut leur être rapprochée) ; l'abonnement Web Push donné avec les boîtes
(pour réveiller un navigateur fermé), le même que celui de la connexion
présentée ; le premier message à un inconnu, et les anciens clients, qui
restent adressés au code.

### 7.14 Hygiène

Comparaisons de secrets en temps constant (tags GCM, identifiants dérivés
d'une clé partagée), secrets effacés (`zeroize`), aucune panique sur une
entrée hostile, aucune heure ni hasard implicite (passés par l'appelant :
`wasm32-unknown-unknown` n'a pas d'horloge).

## 8. Revue de sécurité (octobre 2026)

Une revue adversariale du relais, du navigateur et de l'iPhone a relevé
17 points. Ce qui a été corrigé :

| # | Point | Correction |
|---|---|---|
| 1 | Code de 50 bits : d'autres clés au même code | numéro de sécurité sur les clés complètes (`talka-safety-v2`) ; empreinte de 96 bits dans les liens d'invitation (`/c/<empreinte>/<code>`), vérifiée à l'ajout ; le relais ne laisse pas un code déjà lié passer à d'autres clés |
| 2 | Prise en main d'un groupe | seuls les admins changent un groupe ; un membre peut seulement partir ; version bornée (+1 000) |
| 3 | Mémoire du relais | files de sortie bornées (connexion coupée si l'appareil ne lit plus), toutes les demandes limitées (60/s), codes suivis plafonnés par connexion, connexions anonymes plafonnées et fermées après 2 min sans envoi, 64 Mio par minute et par connexion |
| 4 | Boîtes vidées par un tiers | plus d'éviction : une boîte (ou le total) pleine refuse (`full`), l'expéditeur réessaie |
| 5 | Rejeu de l'ancien format | d'un contact passé en v3, l'ancien format ne change plus d'état (minuterie, modifications, suppressions, groupe, appels) |
| 6 | Images distantes (pixel espion) | seulement des `data:image/…` ; CSP sans images distantes |
| 7 | Fichier reçu : adresse distante | seulement `data:`, téléchargé comme octets (`application/octet-stream`), nom nettoyé |
| 8 | « Vu à » malgré « masquer » | plus d'heure de dernière connexion pour qui se masque |
| 9 | iPhone : clé et base dans les sauvegardes | `ThisDeviceOnly`, base exclue des sauvegardes |
| 10 | Faux messages système | `group` hors d'un groupe ignoré ; seulement les annonces de Talka, d'un admin |
| 11 | Citation falsifiée | la citation vient de notre copie du message cité |
| 12 | Relais amplificateur de notifications | 50 réveils par seconde au plus, tous codes confondus |
| 13 | Essais du code remis à zéro | comptés même après un rechargement ; la mémoire chiffrée par le code (§3.1) |
| 15 | Contacts créés par un inconnu scellé | l'expéditeur scellé n'est enregistré qu'une fois l'enveloppe intérieure ouverte |
| 16 | Sauvegarde en PBKDF2 | Argon2id |
| 17 | Jetons Brocolis d'une autre discussion | ouverts seulement par les clés de la discussion (ou liées à aucune) |

Corrigé ensuite (octobre 2026) :

| # | Point | Correction |
|---|---|---|
| 5 | Pièces jointes de plus de 200 Kio dans l'ancien format | chiffrées par une clé aléatoire transmise dans le message v3 (§7.10) ; l'ancien format refusé d'un contact à jour |
| 5 | Message effacé qui revient par rejeu | pierres tombales (31 jours), ancien format de plus de 31 jours refusé, offres d'appel déjà vues ignorées |
| 14 | Jeton de remise unique, non révocable | un jeton par contact, révoqué au blocage (§7.9) |
| — | Connexions par adresse IP | 100 à la fois par adresse (/64 en IPv6) |
| 3 | Fichier de 3 Mo scellé refusé par le relais (trop gros) | limite d'un envoi portée à 8 Mio |

Ce qui reste, hors de portée du code :

- **Aucun audit indépendant.** Ces revues sont internes. Le dossier pour
  des auditeurs extérieurs est prêt (`docs/AUDIT.md`), et la politique de
  signalement des failles est publique (`SECURITY.md`).
- Avec un contact qui n'a pas encore cette version, l'ancien format reste
  accepté (sinon il ne pourrait plus écrire) : les protections ci-dessus
  s'appliquent dès qu'il l'a.

