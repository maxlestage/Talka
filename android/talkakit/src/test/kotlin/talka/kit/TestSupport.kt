// Ce que les tests de la JVM branchent à la place de l'appareil : SQLite par
// `sqlite-jdbc` (sur Android : `android.database.sqlite`), et une garde de
// la clé en mémoire (sur Android : le Keystore).

package talka.kit

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/** SQLite par JDBC. */
object JdbcSql : SqlOpener {
    override fun open(file: File): SqlDb? =
        try {
            JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))
        } catch (e: SQLException) {
            null
        }
}

class JdbcSqlDb(private val conn: Connection) : SqlDb {
    override fun exec(sql: String): Boolean =
        try {
            conn.createStatement().use { it.execute(sql) }
            true
        } catch (e: SQLException) {
            false
        }

    private fun bind(ps: PreparedStatement, params: List<Any?>) {
        for ((i, p) in params.withIndex()) {
            val index = i + 1
            when (p) {
                is String -> ps.setString(index, p)
                is ByteArray -> ps.setBytes(index, p)
                is Double -> ps.setDouble(index, p)
                is Int -> ps.setLong(index, p.toLong())
                is Long -> ps.setLong(index, p)
                else -> ps.setNull(index, Types.NULL)
            }
        }
    }

    override fun query(sql: String, params: List<Any?>, each: (SqlRow) -> Unit) {
        val ps = try {
            conn.prepareStatement(sql)
        } catch (e: SQLException) {
            return
        }
        ps.use {
            val rs = try {
                bind(ps, params)
                if (!ps.execute()) return
                ps.resultSet
            } catch (e: SQLException) {
                return
            }
            rs.use {
                val row = JdbcRow(rs)
                while (try { rs.next() } catch (e: SQLException) { false }) each(row)
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (e: SQLException) {
        }
    }
}

private class JdbcRow(val rs: ResultSet) : SqlRow {
    override fun isBlob(i: Int): Boolean = rs.getObject(i + 1) is ByteArray

    override fun blob(i: Int): ByteArray = rs.getBytes(i + 1) ?: ByteArray(0)

    override fun text(i: Int): String = rs.getString(i + 1) ?: ""

    override fun double(i: Int): Double = rs.getDouble(i + 1)
}

/** La garde de la clé, en mémoire. */
class MemoryKeyGuard : KeyGuard {
    val items = HashMap<String, Pair<ByteArray, KeyGuard.Access>>()
    val flags = HashMap<String, Boolean>()

    /** Ce que la garde répond aux lectures (`Failed` : verrouillée). */
    var failing = false

    /** Les copies qui demandent une authentification ne s'ouvrent qu'avec `auth`. */
    override fun read(account: String, auth: Any?): KeyGuard.Read {
        if (failing) return KeyGuard.Read.Failed
        val (data, access) = items[account] ?: return KeyGuard.Read.NotFound
        if (access != KeyGuard.Access.plain && auth == null) return KeyGuard.Read.Failed
        return KeyGuard.Read.Found(data.copyOf())
    }

    override fun add(account: String, data: ByteArray, access: KeyGuard.Access): Boolean {
        if (failing) return false
        items[account] = data.copyOf() to access
        return true
    }

    override fun delete(account: String) {
        items.remove(account)
    }

    override fun flag(name: String): Boolean = flags[name] ?: false

    override fun setFlag(name: String, on: Boolean) {
        flags[name] = on
    }

    override fun removeFlag(name: String) {
        flags.remove(name)
    }
}

/** La garde des éléments secrets (l'appareil v3, les clés Brocolis), en mémoire. */
class MemorySecretGuard : SecretGuard {
    val items = HashMap<Pair<String, String>, ByteArray>()

    /** La garde ne répond plus (verrouillée) : lectures `Failed`, écritures refusées. */
    var failing = false

    override fun read(service: String, account: String): KeyGuard.Read {
        if (failing) return KeyGuard.Read.Failed
        return items[service to account]?.let { KeyGuard.Read.Found(it.copyOf()) } ?: KeyGuard.Read.NotFound
    }

    override fun write(service: String, account: String, data: ByteArray): Boolean {
        if (failing) return false
        items[service to account] = data.copyOf()
        return true
    }

    override fun delete(service: String, account: String) {
        items.remove(service to account)
    }

    override fun clear(service: String) {
        items.keys.removeAll { it.first == service }
    }
}

/** Un dossier neuf (pas encore créé : `Store` le crée, comme sur l'iPhone). */
fun newTempDir(prefix: String = "talka-store-"): File {
    val dir = Files.createTempDirectory(prefix).toFile()
    dir.delete()
    return dir
}

/** Les mémoires ouvertes par un test, fermées et effacées à la fin. */
class Stores {
    private val opened = ArrayList<Store>()
    private val dirs = ArrayList<File>()

    fun dir(): File = newTempDir().also { dirs.add(it) }

    fun open(dir: File, key: ByteArray? = null): Store = Store(dir, JdbcSql, key).also { opened.add(it) }

    fun closeAll() {
        for (s in opened) s.close()
        opened.clear()
        for (d in dirs) d.deleteRecursively()
        dirs.clear()
    }
}

/** Une clé d'appareil neuve (`SymmetricKey(size: .bits256)`). */
fun newKey(): ByteArray = randomBytes(32)
