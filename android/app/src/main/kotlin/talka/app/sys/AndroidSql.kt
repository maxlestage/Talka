// SQLite d'Android (`android.database.sqlite`) derrière l'interface de
// TalkaKit (`SqlDb`) : la mémoire de Talka (`Store`) sur l'appareil.
//
// Comme l'API C qu'emploie l'iPhone, rien ne lève : une instruction refusée
// rend `false` (`exec`), une requête refusée ne rend aucune ligne (`query`).
//
// Deux particularités d'Android :
// - `execSQL` refuse une instruction qui rend une ligne (`PRAGMA
//   journal_mode`, `PRAGMA wal_checkpoint`) : celles-là passent par
//   `rawQuery` ;
// - `BEGIN`, `COMMIT` et `ROLLBACK` passés à `execSQL` sont repris par la
//   session d'Android (son `beginTransaction`), sur le fil qui les envoie :
//   `Store` les envoie toujours sous son verrou, du même fil.

package talka.app.sys

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteProgram
import talka.kit.SqlDb
import talka.kit.SqlOpener
import talka.kit.SqlRow
import java.io.File

/** Ouvre (ou crée) la base d'un fichier. */
object AndroidSql : SqlOpener {
    override fun open(file: File): SqlDb? =
        try {
            file.parentFile?.mkdirs()
            AndroidSqlDb(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY))
        } catch (e: SQLiteException) {
            null
        }
}

class AndroidSqlDb(private val db: SQLiteDatabase) : SqlDb {
    override fun exec(sql: String): Boolean =
        try {
            if (returnsRows(sql)) {
                db.rawQuery(sql, null).use { it.moveToFirst() }
            } else {
                db.execSQL(sql)
            }
            true
        } catch (e: RuntimeException) {
            // SQLiteException, ou IllegalStateException (un COMMIT sans BEGIN).
            false
        }

    override fun query(sql: String, params: List<Any?>, each: (SqlRow) -> Unit) {
        try {
            if (returnsRows(sql)) {
                val factory = SQLiteDatabase.CursorFactory { _, driver, table, query ->
                    bind(query, params)
                    SQLiteCursor(driver, table, query)
                }
                db.rawQueryWithFactory(factory, sql, null, "").use { c ->
                    val row = AndroidRow(c)
                    while (c.moveToNext()) each(row)
                }
            } else {
                db.compileStatement(sql).use { st ->
                    bind(st, params)
                    st.executeUpdateDelete()
                }
            }
        } catch (e: RuntimeException) {
            // Une requête refusée ne rend rien.
        }
    }

    override fun close() {
        try {
            db.close()
        } catch (e: RuntimeException) {
        }
    }

    private companion object {
        /** Une instruction qui rend des lignes : un curseur ; sinon une instruction préparée. */
        fun returnsRows(sql: String): Boolean {
            val head = sql.trimStart().take(6).uppercase()
            return head.startsWith("SELECT") || head.startsWith("PRAGMA") || head.startsWith("WITH")
        }

        /** Les paramètres dans l'ordre, comme `sqlite3_bind_*` (à partir de 1). */
        fun bind(p: SQLiteProgram, params: List<Any?>) {
            for ((i, v) in params.withIndex()) {
                val index = i + 1
                when (v) {
                    is String -> p.bindString(index, v)
                    is ByteArray -> p.bindBlob(index, v)
                    is Double -> p.bindDouble(index, v)
                    is Int -> p.bindLong(index, v.toLong())
                    is Long -> p.bindLong(index, v)
                    else -> p.bindNull(index)
                }
            }
        }
    }
}

private class AndroidRow(private val c: Cursor) : SqlRow {
    override fun isBlob(i: Int): Boolean = c.getType(i) == Cursor.FIELD_TYPE_BLOB

    override fun blob(i: Int): ByteArray = if (c.isNull(i)) ByteArray(0) else c.getBlob(i) ?: ByteArray(0)

    override fun text(i: Int): String = if (c.isNull(i)) "" else c.getString(i) ?: ""

    override fun double(i: Int): Double = if (c.isNull(i)) 0.0 else c.getDouble(i)
}
