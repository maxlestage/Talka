// Les outils des tests : les fichiers de référence (copiés dans
// src/test/resources depuis proto/vectors.json, proto/tests/wycheproof/ et
// proto-swift/Tests/BrocolisProtoTests/Fixtures/), l'hexadécimal, un
// générateur qui note ce qu'il donne, et un compteur des valeurs vérifiées.

package talka.brocolis

import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

object Depot {
    private val cache = ConcurrentHashMap<String, JsonObject>()

    fun json(chemin: String): JsonObject = cache.getOrPut(chemin) {
        val stream = Depot::class.java.getResourceAsStream("/$chemin") ?: error("ressource absente : $chemin")
        val text = stream.use { String(it.readBytes(), Charsets.UTF_8) }
        KJson.parseToJsonElement(text).jsonObject
    }

    fun vecteurs(): JsonObject = json("vectors.json")

    fun wycheproof(nom: String): JsonObject = json("wycheproof/$nom")

    fun referenceRust(): JsonObject = json("reference-rust.json")
}

/** Les valeurs comparées aux vecteurs (pour le compte rendu). */
object Compteur {
    private val valeurs = ConcurrentHashMap<String, AtomicInteger>()

    fun ajouter(categorie: String, n: Int = 1) {
        valeurs.getOrPut(categorie) { AtomicInteger() }.addAndGet(n)
    }

    fun total(categorie: String): Int = valeurs[categorie]?.get() ?: 0
}

// MARK: JSON

fun JsonObject.obj(k: String): JsonObject = this[k]?.jsonObject ?: fail("objet $k attendu")

fun JsonObject.arr(k: String): JsonArray = this[k]?.jsonArray ?: fail("tableau $k attendu")

fun JsonObject.str(k: String): String {
    val p = this[k]?.jsonPrimitive ?: fail("texte $k attendu")
    assertTrue(p.isString, "texte $k attendu")
    return p.content
}

fun JsonObject.int(k: String): Int = this[k]?.jsonPrimitive?.content?.toInt() ?: fail("entier $k attendu")

fun JsonObject.isNull(k: String): Boolean = this[k] is JsonNull

fun JsonElement.str(): String = jsonPrimitive.content

// MARK: Hexadécimal

fun unhex(s: String): ByteArray {
    fun nib(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }
    return ByteArray(s.length / 2) { ((nib(s[2 * it]) shl 4) or nib(s[2 * it + 1])).toByte() }
}

fun unhex(o: JsonObject, k: String): ByteArray = unhex(o.str(k))

fun hex(b: ByteArray): String = Octets.hex(b)

fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

fun repeated(b: Int, n: Int): ByteArray = ByteArray(n) { b.toByte() }

fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

/** `SHA-256(étiquette UTF-8)`, le `h32` du générateur de vecteurs. */
fun h32(label: String): ByteArray = Kdf.sha256(utf8(label))

/** Une comparaison à un vecteur, comptée (les tableaux d'octets par leur contenu). */
fun egal(a: Any?, b: Any?, quoi: String, categorie: String = "vecteurs") {
    if (a is ByteArray && b is ByteArray) {
        assertContentEquals(b, a, quoi)
    } else {
        assertEquals(b, a, quoi)
    }
    Compteur.ajouter(categorie)
}

/** Un générateur déterministe qui note tout ce qu'il donne (pour les secrets tirés pendant le cliquet, `*_drawn`). */
class Recorder(label: String) : CryptoRng {
    private val inner = Deterministic(label)
    private var drawn = java.io.ByteArrayOutputStream()

    override fun fill(buffer: ByteArray) {
        inner.fill(buffer)
        drawn.write(buffer)
    }

    fun take(): ByteArray {
        val out = drawn.toByteArray()
        drawn = java.io.ByteArrayOutputStream()
        return out
    }
}

fun texte(c: Content): String = (c as? Content.Text)?.text ?: fail("texte attendu")

val Token.initialMessage: Initial? get() = (this as? Token.Initial)?.initial

val Token.groupMessage: GroupMessage? get() = (this as? Token.Group)?.message

/** Attend une erreur précise. */
fun attendreErreur(attendue: Erreur, quoi: String = "", bloc: () -> Any?) {
    try {
        bloc()
    } catch (e: Erreur) {
        assertEquals(attendue, e, quoi)
        return
    } catch (e: Throwable) {
        fail("erreur inattendue $e $quoi")
    }
    fail("erreur $attendue attendue $quoi")
}

/** Attend une erreur, quelle qu'elle soit (une `Erreur` : jamais un plantage). */
fun attendreEchec(quoi: String = "", bloc: () -> Any?) {
    try {
        bloc()
    } catch (e: Erreur) {
        return
    }
    fail("échec attendu $quoi")
}

/** Le résultat, ou `null` sur une `Erreur` (le `try?` de Swift). */
inline fun <T> essai(bloc: () -> T): T? = try {
    bloc()
} catch (e: Erreur) {
    null
}

const val T0: ULong = 1_790_000_000uL

fun JsonPrimitive.bool(): Boolean = content.toBooleanStrict()
