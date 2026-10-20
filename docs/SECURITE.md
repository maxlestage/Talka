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

### 7.8 Hygiène

Comparaisons de secrets en temps constant (tags GCM, identifiants dérivés
d'une clé partagée), secrets effacés (`zeroize`), aucune panique sur une
entrée hostile, aucune heure ni hasard implicite (passés par l'appelant :
`wasm32-unknown-unknown` n'a pas d'horloge).
