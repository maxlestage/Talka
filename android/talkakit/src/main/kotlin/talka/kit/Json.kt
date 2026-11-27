// Le JSON de Talka, comme serde_json (le navigateur) et `Codable` (l'iPhone) :
// chaque type s'écrit et se relit lui-même, champ par champ, pour garder
// exactement les mêmes noms, les mêmes valeurs par défaut et les mêmes champs
// omis. Un champ absent, `null` ou d'un autre type prend sa valeur par défaut
// (comme `c.v(_:_:)` de l'iPhone et `#[serde(default)]`).

package talka.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** Un JSON illisible pour ce type (pas un objet, un champ obligatoire absent). */
class JSONError(message: String) : Exception(message)

/** Ce qui s'écrit en JSON. */
interface JsonEncodable {
    fun toJson(): JsonElement
}

/** Ce qui se relit du JSON (le compagnon d'un type) ; lève [JSONError] si c'est illisible. */
interface JsonDecodable<T> {
    fun fromJson(json: JsonElement): T
}

/** JSON ↔ structures, comme serde_json. */
object JSON {
    fun encode(v: JsonEncodable): String = write(v.toJson())

    fun encode(s: String): String = write(JsonPrimitive(s))

    fun encode(e: JsonElement): String = write(e)

    fun <T> decode(type: JsonDecodable<T>, text: String): T? = parse(text)?.let { decode(type, it) }

    fun <T> decode(type: JsonDecodable<T>, json: JsonElement): T? =
        try {
            type.fromJson(json)
        } catch (e: JSONError) {
            null
        } catch (e: RuntimeException) {
            null
        }

    /** Le texte lu en JSON, ou rien s'il n'en est pas. */
    fun parse(text: String): JsonElement? =
        try {
            Json.parseToJsonElement(text)
        } catch (e: RuntimeException) {
            null
        }

    /** Le texte d'un JSON : compact, les clés dans l'ordre où elles ont été mises. */
    fun write(e: JsonElement): String = StringBuilder().also { writeTo(it, e) }.toString()

    private fun writeTo(sb: StringBuilder, e: JsonElement) {
        when (e) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> if (e.isString) quote(sb, e.content) else sb.append(literal(e.content))
            is JsonArray -> {
                sb.append('[')
                e.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, x)
                }
                sb.append(']')
            }
            is JsonObject -> {
                sb.append('{')
                var first = true
                for ((k, x) in e) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k)
                    sb.append(':')
                    writeTo(sb, x)
                }
                sb.append('}')
            }
        }
    }

    /** Un nombre : entier tel quel ; un `f64` entier comme serde_json (`1.0`). */
    private fun literal(content: String): String {
        if (content == "true" || content == "false" || content == "null") return content
        if (content.toLongOrNull() != null) return content
        val d = content.toDoubleOrNull() ?: return "null"
        // JSON n'a ni NaN ni infini : serde_json écrit `null`.
        if (!d.isFinite()) return "null"
        if (d == Math.rint(d) && abs(d) < 1e15) return "${d.toLong()}.0"
        // Comme serde_json (ryu) : sans exposant de 1e-5 à 1e16 (une heure
        // en millisecondes avec ses fractions : `1795740793297.5`).
        if (abs(d) >= 1e-5 && abs(d) < 1e16) {
            val plain = java.math.BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
            return if (plain.contains('.')) plain else "$plain.0"
        }
        return d.toString()
    }

    private fun quote(sb: StringBuilder, s: String) {
        // Presque rien n'est à échapper (une photo en base64 : rien du tout) :
        // ce qui ne l'est pas est recopié par blocs, pas caractère par caractère.
        sb.ensureCapacity(sb.length + s.length + 2)
        sb.append('"')
        var from = 0
        for (i in s.indices) {
            val ch = s[i]
            if (ch >= ' ' && ch != '"' && ch != '\\') continue
            sb.append(s, from, i)
            from = i + 1
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> sb.append("\\u%04x".format(ch.code))
            }
        }
        sb.append(s, from, s.length)
        sb.append('"')
    }
}

// ----- Lire un champ, avec sa valeur par défaut --------------------------------

/** L'objet attendu, ou [JSONError]. */
internal fun JsonElement.obj(): JsonObject = this as? JsonObject ?: throw JSONError("un objet JSON est attendu")

private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonElement?.asString(): String? = prim()?.takeIf { it.isString }?.content

internal fun JsonElement?.asBool(): Boolean? =
    prim()?.takeIf { !it.isString }?.content?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

internal fun JsonElement?.asDouble(): Double? = prim()?.takeIf { !it.isString }?.content?.toDoubleOrNull()

/** Un entier ; un nombre à virgule entier (`2.0`) aussi. */
internal fun JsonElement?.asLong(): Long? {
    val p = prim()?.takeIf { !it.isString } ?: return null
    p.content.toLongOrNull()?.let { return it }
    val d = p.content.toDoubleOrNull() ?: return null
    return if (d == Math.rint(d) && abs(d) < 9.2e18) d.toLong() else null
}

internal fun JsonElement?.asInt(): Int? = asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal fun JsonElement?.asUInt(): UInt? = asLong()?.takeIf { it in 0L..0xFFFF_FFFFL }?.toUInt()

internal fun JsonObject.str(k: String, d: String = ""): String = this[k].asString() ?: d

internal fun JsonObject.bool(k: String, d: Boolean = false): Boolean = this[k].asBool() ?: d

internal fun JsonObject.int(k: String, d: Int = 0): Int = this[k].asInt() ?: d

internal fun JsonObject.long(k: String, d: Long = 0): Long = this[k].asLong() ?: d

internal fun JsonObject.uint(k: String, d: UInt = 0u): UInt = this[k].asUInt() ?: d

internal fun JsonObject.double(k: String, d: Double = 0.0): Double = this[k].asDouble() ?: d

internal fun JsonObject.doubleOrNull(k: String): Double? = this[k].asDouble()

internal fun JsonObject.strOrNull(k: String): String? = this[k].asString()

internal fun JsonObject.boolOrNull(k: String): Boolean? = this[k].asBool()

internal fun JsonObject.longOrNull(k: String): Long? = this[k].asLong()

/** Un champ obligatoire (sans valeur par défaut chez l'iPhone non plus). */
internal fun JsonObject.reqStr(k: String): String = this[k].asString() ?: throw JSONError("$k manque")

internal fun JsonObject.reqDouble(k: String): Double = this[k].asDouble() ?: throw JSONError("$k manque")

internal fun JsonObject.reqInt(k: String): Int = this[k].asInt() ?: throw JSONError("$k manque")

internal fun JsonObject.reqUInt(k: String): UInt = this[k].asUInt() ?: throw JSONError("$k manque")

/** Une liste de textes : tout ou rien, comme `[String]` en Swift. */
internal fun JsonObject.strings(k: String): List<String> {
    val a = this[k] as? JsonArray ?: return emptyList()
    return a.map { it.asString() ?: return emptyList() }
}

/** Une liste d'objets : tout ou rien. */
internal fun <T> JsonObject.list(k: String, type: JsonDecodable<T>): List<T> {
    val a = this[k] as? JsonArray ?: return emptyList()
    return try {
        a.map { type.fromJson(it) }
    } catch (e: JSONError) {
        emptyList()
    }
}

/** Un objet facultatif : absent, `null` ou illisible, rien. */
internal fun <T> JsonObject.of(k: String, type: JsonDecodable<T>): T? {
    val e = this[k] ?: return null
    if (e is JsonNull) return null
    return try {
        type.fromJson(e)
    } catch (e: JSONError) {
        null
    }
}

internal fun JsonObject.stringMap(k: String): Map<String, String> {
    val o = this[k] as? JsonObject ?: return emptyMap()
    return o.mapValues { (_, v) -> v.asString() ?: return emptyMap() }
}

internal fun JsonObject.doubleMap(k: String): Map<String, Double> {
    val o = this[k] as? JsonObject ?: return emptyMap()
    return o.mapValues { (_, v) -> v.asDouble() ?: return emptyMap() }
}

// ----- Écrire ------------------------------------------------------------------

internal fun JsonObjectBuilder.putAll(o: JsonElement) {
    for ((k, v) in o.obj()) put(k, v)
}

internal fun JsonObjectBuilder.put(k: String, list: List<String>) {
    put(k, JsonArray(list.map { JsonPrimitive(it) }))
}

internal fun JsonObjectBuilder.put(k: String, n: UInt) {
    put(k, JsonPrimitive(n.toLong()))
}

/** Les dictionnaires s'écrivent dans l'ordre des clés, comme le `BTreeMap` du navigateur. */
internal fun JsonObjectBuilder.putStringMap(k: String, m: Map<String, String>) {
    put(k, JsonObject(m.toSortedMap().mapValues { JsonPrimitive(it.value) }))
}

internal fun JsonObjectBuilder.putDoubleMap(k: String, m: Map<String, Double>) {
    put(k, JsonObject(m.toSortedMap().mapValues { JsonPrimitive(it.value) }))
}

internal fun <T : JsonEncodable> JsonObjectBuilder.putList(k: String, list: List<T>) {
    put(k, JsonArray(list.map { it.toJson() }))
}

/** Une valeur JSON quelconque (les candidats ICE d'un appel). */
sealed class JSONValue : JsonEncodable {
    data object Null : JSONValue()

    data class Bool(val value: Boolean) : JSONValue()

    data class Number(val value: Double) : JSONValue()

    data class Str(val value: String) : JSONValue()

    data class Array(val value: List<JSONValue>) : JSONValue()

    data class Object(val value: Map<String, JSONValue>) : JSONValue()

    override fun toJson(): JsonElement =
        when (this) {
            Null -> JsonNull
            is Bool -> JsonPrimitive(value)
            // Un entier s'écrit en entier (`sdpMLineIndex` : 0, pas 0.0).
            is Number -> if (value == Math.rint(value) && abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
            is Str -> JsonPrimitive(value)
            is Array -> JsonArray(value.map { it.toJson() })
            // Les clés dans l'ordre, comme `serde_json::Value` du navigateur
            // (un `BTreeMap` : par points de code).
            is Object -> JsonObject(value.toSortedMap(::compareCodePoints).mapValues { it.value.toJson() })
        }

    /** En objets Kotlin (pour WebRTC). */
    val any: Any?
        get() =
            when (this) {
                Null -> null
                is Bool -> value
                is Number -> if (value == Math.rint(value) && abs(value) < 9.2e18) value.toLong() else value
                is Str -> value
                is Array -> value.map { it.any }
                is Object -> value.mapValues { it.value.any }
            }

    operator fun get(key: String): JSONValue? = (this as? Object)?.value?.get(key)

    val string: String? get() = (this as? Str)?.value
    val number: Double? get() = (this as? Number)?.value
    val bool: Boolean? get() = (this as? Bool)?.value
    val array: List<JSONValue>? get() = (this as? Array)?.value

    companion object : JsonDecodable<JSONValue> {
        override fun fromJson(json: JsonElement): JSONValue =
            when (json) {
                is JsonNull -> Null
                is JsonPrimitive ->
                    when {
                        json.isString -> Str(json.content)
                        json.asBool() != null -> Bool(json.asBool()!!)
                        else -> Number(json.asDouble() ?: throw JSONError("nombre illisible"))
                    }
                is JsonArray -> Array(json.map { fromJson(it) })
                is JsonObject -> Object(json.mapValues { fromJson(it.value) })
            }
    }
}
