// Les erreurs, typées : une entrée hostile ne fait jamais planter, elle donne
// une de ces erreurs. Ce sont exactement celles de la référence Rust
// (`brocolis_proto::Erreur`), cas pour cas. Les messages sont faits pour les
// journaux de développement (ils ne contiennent jamais de secret).

package talka.brocolis

/**
 * Tout ce qui peut échouer. Chaque fonction du paquet qui lance une
 * exception ne lance que des `Erreur`. Chaque cas est un objet unique (sans
 * pile d'appels : il ne dit rien de plus que son nom), qu'on compare par `==`
 * ou qu'on attrape par son type (`catch (e: Erreur.Rejoue)`).
 */
sealed class Erreur(val description: String) : Exception(description, null, false, false) {
    /** Le texte ne contient pas le jeton attendu, ou son enveloppe (`[[…:` / `]]`) est abîmée. */
    object JetonAbsent : Erreur("jeton absent ou abîmé")

    /** Le base64 est invalide (alphabet standard avec `=`, strict). */
    object Base64 : Erreur("base64 invalide")

    /** Un encodage binaire est malformé : longueur, octet de type ou de version, champ réservé non nul… */
    object Format : Erreur("encodage malformé")

    /** L'entrée dépasse la taille maximale acceptée. */
    object TropGrand : Erreur("entrée trop grande")

    /** Des paramètres Argon2id hors des bornes acceptées (§3.2). */
    object Parametres : Erreur("paramètres Argon2id refusés")

    /** La phrase secrète est trop courte pour protéger une clé. */
    object PhraseTropCourte : Erreur("phrase secrète trop courte")

    /** Le déchiffrement authentifié a échoué : mauvaise clé ou phrase, message modifié. */
    object Dechiffrement : Erreur("déchiffrement impossible (clé, phrase ou message altéré)")

    /** Le remplissage (§3.3) est absent ou malformé. */
    object Remplissage : Erreur("remplissage invalide")

    /** Le texte déchiffré n'est pas de l'UTF-8. */
    object Utf8 : Erreur("texte non UTF-8")

    /** Une signature Ed25519 est fausse. */
    object Signature : Erreur("signature invalide")

    /** Une clé publique est invalide (X25519 de petit ordre, ML-KEM mal formée, Ed25519 faible…). */
    object CleInvalide : Erreur("clé publique invalide")

    /**
     * Le paquet ne propose pas de clé ML-KEM alors qu'elle est demandée, ou
     * cet appareil ne sait pas faire ML-KEM.
     */
    object PqIndisponible : Erreur("pas de clé post-quantique dans le paquet")

    /** La clé pré-signée (ou ML-KEM) désignée par le message n'existe pas ou plus sur cet appareil. */
    object CleInconnue : Erreur("clé pré-signée inconnue ou expirée")

    /** Le message n'appartient pas à cette session (identifiant différent). */
    object SessionInconnue : Erreur("message d'une autre session")

    /**
     * Un premier message (type 0x01) avec une autre clé éphémère pour le même
     * identifiant : c'est une nouvelle session, à accepter par `Device.accept`.
     */
    object NouvelleSession : Erreur("premier message d'une nouvelle session")

    /** Un message déjà lu (rejeu) : à ignorer. */
    object Rejoue : Erreur("message déjà lu (rejeu ignoré)")

    /** Le message demanderait de sauter plus de `Protocole.maxSkip` clés. */
    object TropDeClesSautees : Erreur("trop de messages sautés")

    /** Ce message de groupe vient d'un expéditeur (ou d'un groupe) dont on n'a pas la chaîne. */
    object ExpediteurInconnu : Erreur("expéditeur ou groupe inconnu")

    /** L'état sérialisé (JSON) est invalide, ou l'opération est impossible dans l'état courant. */
    object Etat : Erreur("état invalide")

    /** Un compteur est arrivé au bout (2³² messages sur une chaîne). */
    object Epuise : Erreur("compteur épuisé")

    override fun toString(): String = description
}

/** Les constantes communes du protocole (`brocolis_proto::{JOUR, MAX_SKIP, MAX_SKIP_AGE}`). */
object Protocole {
    /**
     * Un jour, en secondes. L'heure est un nombre de secondes depuis l'époque
     * Unix, fourni par l'appelant.
     */
    const val jour: ULong = 86_400uL // 24 × 60 × 60

    /**
     * Le nombre maximal de clés sautées gardées par session (ou par chaîne de
     * groupe), et le plus grand saut accepté en un message (§4.3).
     */
    const val maxSkip: UInt = 1_000u

    /** L'âge maximal d'une clé sautée : 30 jours (§4.3). */
    const val maxSkipAge: ULong = 2_592_000uL // 30 × jour
}
