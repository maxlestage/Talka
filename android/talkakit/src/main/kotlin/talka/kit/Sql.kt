// SQLite, vu de TalkaKit : ce dont la mémoire de Talka (`Store`) a besoin, et
// rien de plus. Sur Android, l'application le branche sur
// `android.database.sqlite` ; les tests de la JVM, sur `sqlite-jdbc`.
//
// Comme l'API C de SQLite qu'emploie l'iPhone : une erreur ne lève rien,
// une instruction ratée rend `false` (`exec`) ou ne rend aucune ligne
// (`query`).

package talka.kit

import java.io.File

/** Une base SQLite ouverte. */
interface SqlDb {
    /**
     * Une instruction sans paramètre (`PRAGMA`, `CREATE`, `BEGIN`, `COMMIT`,
     * `VACUUM`…), ses lignes éventuelles ignorées ; `false` si SQLite l'a
     * refusée.
     */
    fun exec(sql: String): Boolean

    /**
     * Une requête préparée, ses paramètres liés dans l'ordre (`String` : du
     * texte, `ByteArray` : des octets, `Double` : un réel, `Int` ou `Long` :
     * un entier, autre chose : `NULL`), et chaque ligne rendue à `each`.
     * Une requête que SQLite refuse ne rend rien.
     */
    fun query(sql: String, params: List<Any?> = emptyList(), each: (SqlRow) -> Unit = {})

    /** Ferme la base (le journal est reporté dans le fichier). */
    fun close()
}

/** Une ligne du résultat ; les colonnes comptent à partir de 0, comme `sqlite3_column_*`. */
interface SqlRow {
    /** La valeur de cette colonne est-elle des octets (`BLOB`) ? */
    fun isBlob(i: Int): Boolean

    /** Les octets d'une colonne `BLOB` (vide : aucun). */
    fun blob(i: Int): ByteArray

    /** Le texte d'une colonne (vide pour `NULL`). */
    fun text(i: Int): String

    /** Le nombre réel d'une colonne (0 pour `NULL`). */
    fun double(i: Int): Double
}

/** Ouvre (ou crée) la base d'un fichier ; rien si SQLite ne l'ouvre pas. */
fun interface SqlOpener {
    fun open(file: File): SqlDb?
}
