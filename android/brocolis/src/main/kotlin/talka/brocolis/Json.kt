// Les états sérialisés en JSON, **de la même forme que la référence Rust**
// (serde) : mêmes noms de champs (snake_case) dans le même ordre, octets en
// base64 standard, `null` pour une option vide (écrit, jamais omis : serde
// l'exige pour les champs `serde_b64::opt`), énumérations par leur nom
// (`"Hybrid"`). Un état écrit par l'une se relit par l'autre.
//
// Les entiers sont lus comme serde les lit : un nombre JSON entier, sans
// signe ni exposant, dans les bornes du type (`u8`, `u32`, `u64`).

package talka.brocolis

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

internal object Json {
    private val entier = Regex("0|[1-9][0-9]*")

    /** Relit un objet JSON ; toute erreur devient `Erreur.Etat`. */
    fun parse(text: String): JsonObject = try {
        KJson.parseToJsonElement(text) as? JsonObject ?: throw Erreur.Etat
    } catch (e: Erreur) {
        throw e
    } catch (e: Exception) {
        throw Erreur.Etat
    } catch (e: StackOverflowError) {
        throw Erreur.Etat
    }

    /** Lit une valeur ; toute erreur de forme devient `Erreur.Etat`. */
    inline fun <T> lire(bloc: () -> T): T = try {
        bloc()
    } catch (e: Erreur) {
        throw Erreur.Etat
    } catch (e: RuntimeException) {
        throw Erreur.Etat
    }

    // MARK: Lecture

    fun field(o: JsonObject, key: String): JsonElement = o[key] ?: throw Erreur.Etat

    fun obj(e: JsonElement): JsonObject = e as? JsonObject ?: throw Erreur.Etat

    fun array(e: JsonElement): JsonArray = e as? JsonArray ?: throw Erreur.Etat

    fun string(e: JsonElement): String {
        val p = e as? JsonPrimitive ?: throw Erreur.Etat
        if (!p.isString) throw Erreur.Etat
        return p.content
    }

    /** Un entier non signé ≤ `max`. */
    fun unsigned(e: JsonElement, max: ULong): ULong {
        val p = e as? JsonPrimitive ?: throw Erreur.Etat
        if (p.isString || p is JsonNull || !entier.matches(p.content)) throw Erreur.Etat
        val v = p.content.toULongOrNull() ?: throw Erreur.Etat
        if (v > max) throw Erreur.Etat
        return v
    }

    fun u8(o: JsonObject, key: String): UByte = unsigned(field(o, key), UByte.MAX_VALUE.toULong()).toUByte()

    fun u32(o: JsonObject, key: String): UInt = unsigned(field(o, key), UInt.MAX_VALUE.toULong()).toUInt()

    fun u64(o: JsonObject, key: String): ULong = unsigned(field(o, key), ULong.MAX_VALUE)

    /** Un `Option<u64>` de serde : absent ou `null` donnent `null`. */
    fun optionalU64(o: JsonObject, key: String): ULong? {
        val e = o[key] ?: return null
        if (e is JsonNull) return null
        return unsigned(e, ULong.MAX_VALUE)
    }

    /** Des octets en base64 strict, de longueur `length` si elle est donnée (`[u8; N]` côté Rust). */
    fun bytes(e: JsonElement, length: Int? = null): ByteArray {
        val b = Base64.decode(string(e)) ?: throw Erreur.Etat
        if (length != null && b.size != length) throw Erreur.Etat
        return b
    }

    fun bytes(o: JsonObject, key: String, length: Int? = null): ByteArray = bytes(field(o, key), length)

    /**
     * Des octets optionnels : le champ doit être présent (`null` ou base64),
     * comme `serde_b64::opt` sans `default`.
     */
    fun optionalBytes(o: JsonObject, key: String, length: Int? = null): ByteArray? {
        val e = field(o, key)
        if (e is JsonNull) return null
        return bytes(e, length)
    }

    // MARK: Écriture

    fun JsonObjectBuilder.bytes(key: String, value: ByteArray) {
        put(key, JsonPrimitive(Base64.encode(value)))
    }

    /** Une option : base64 ou `null` (jamais omise). */
    fun JsonObjectBuilder.optionalBytes(key: String, value: ByteArray?) {
        put(key, if (value == null) JsonNull else JsonPrimitive(Base64.encode(value)))
    }

    fun JsonObjectBuilder.number(key: String, value: UInt) {
        put(key, JsonPrimitive(value.toLong()))
    }

    fun JsonObjectBuilder.number(key: String, value: UByte) {
        put(key, JsonPrimitive(value.toInt()))
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun number(value: ULong): JsonElement = JsonUnquotedLiteral(value.toString())

    fun JsonObjectBuilder.number(key: String, value: ULong) {
        put(key, number(value))
    }

    /** Un entier optionnel : nombre ou `null`. */
    fun JsonObjectBuilder.optionalNumber(key: String, value: ULong?) {
        put(key, if (value == null) JsonNull else number(value))
    }
}
