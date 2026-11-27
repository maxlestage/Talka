// La vérification Ed25519 **stricte** (RFC 8032 §5.1.7), exactement celle de
// `verify_strict` d'ed25519-dalek (la référence) :
//
// - A (la clé publique) et R (la première moitié de la signature) doivent
//   être des points de la courbe (y lu modulo p, comme curve25519-dalek) ;
// - ni A ni R ne doivent être de petit ordre ([8]P = identité) ;
// - S doit être canonique : S < L = 2²⁵² + 27742317777372353535851937790883648493 ;
// - l'équation sans cofacteur : l'écriture canonique de [S]B − [k]A, k =
//   SHA-512(R ‖ A ‖ M) mod L, doit être **les octets mêmes** de R.
//
// Les contrôles de points et de scalaire sont faits ici. L'équation, elle,
// est celle de BouncyCastle quand A est écrite canoniquement (y < p) : sans
// cofacteur, R comparé à l'écriture canonique du résultat, exactement celle
// de dalek une fois A et R contrôlés — et bien plus rapide. BouncyCastle
// refuse en revanche une clé A dont y ≥ p, que dalek accepte : pour celle-là
// seulement, l'équation est calculée ici (`verifyEquationSlow`). Les deux
// chemins sont comparés par les tests (Wycheproof et signatures tirées).
//
// L'arithmétique de GF(2²⁵⁵ − 19) est celle de TweetNaCl (16 membres de 16
// bits), en temps variable — elle ne voit que des données publiques.

package talka.brocolis

import java.math.BigInteger

internal object Ed25519Strict {
    // MARK: Corps GF(p), p = 2²⁵⁵ − 19

    val zero: LongArray get() = LongArray(16)
    val one: LongArray get() = LongArray(16).also { it[0] = 1 }

    fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) {
                o[i + 1] += c - 1
            } else {
                o[0] += 38 * (c - 1)
            }
            o[i] -= c shl 16
        }
    }

    fun add(a: LongArray, b: LongArray): LongArray = LongArray(16) { a[it] + b[it] }

    fun sub(a: LongArray, b: LongArray): LongArray = LongArray(16) { a[it] - b[it] }

    fun mul(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(31)
        for (i in 0 until 16) {
            val ai = a[i]
            for (j in 0 until 16) t[i + j] += ai * b[j]
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        val o = t.copyOf(16)
        car(o)
        car(o)
        return o
    }

    fun sq(a: LongArray): LongArray = mul(a, a)

    /** L'écriture canonique (réduite modulo p), 32 octets petit-boutistes. */
    fun pack(n: LongArray): ByteArray {
        var t = n.copyOf()
        car(t)
        car(t)
        car(t)
        repeat(2) {
            val m = LongArray(16)
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = (m[15] shr 16) and 1
            m[14] = m[14] and 0xffff
            if (b == 0L) t = m
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    /** Lit 32 octets petit-boutistes, bit 255 ignoré (non réduit : les opérations travaillent modulo p). */
    fun unpack(b: ByteArray): LongArray {
        val o = LongArray(16) { (b[2 * it].toLong() and 0xff) + ((b[2 * it + 1].toLong() and 0xff) shl 8) }
        o[15] = o[15] and 0x7fff
        return o
    }

    fun fromInt(v: Long): LongArray {
        val o = LongArray(16)
        o[0] = v and 0xffff
        o[1] = v shr 16
        return o
    }

    /** aᵉ, l'exposant en octets petit-boutistes. */
    fun pow(a: LongArray, e: ByteArray): LongArray {
        var r = one
        for (i in e.size * 8 - 1 downTo 0) {
            r = sq(r)
            if ((e[i / 8].toInt() shr (i % 8)) and 1 == 1) r = mul(r, a)
        }
        return r
    }

    private fun le(low: Int, high: Int): ByteArray {
        val e = ByteArray(32) { 0xff.toByte() }
        e[0] = low.toByte()
        e[31] = high.toByte()
        return e
    }

    /** p − 2 (inverse), (p − 5)/8 = 2²⁵² − 3 (racine carrée), (p − 1)/4 = 2²⁵³ − 5 (√−1). */
    private val pMinus2 = le(0xeb, 0x7f)
    private val pMinus5Over8 = le(0xfd, 0x0f)
    private val pMinus1Over4 = le(0xfb, 0x1f)

    fun inv(a: LongArray): LongArray = pow(a, pMinus2)

    fun equal(a: LongArray, b: LongArray): Boolean = pack(a).contentEquals(pack(b))

    fun isNegative(a: LongArray): Boolean = pack(a)[0].toInt() and 1 == 1

    /** d = −121665/121666, la constante de la courbe d'Edwards. */
    val d: LongArray = mul(sub(LongArray(16), fromInt(121_665)), inv(fromInt(121_666)))
    val d2: LongArray = add(d, d)

    /** √−1 = 2^((p−1)/4). */
    val sqrtM1: LongArray = pow(fromInt(2), pMinus1Over4)

    // MARK: Points (coordonnées étendues X, Y, Z, T)

    class Point(val x: LongArray, val y: LongArray, val z: LongArray, val t: LongArray)

    /**
     * Décode un point (RFC 8032 §5.1.3, y réduit modulo p comme
     * curve25519-dalek) ; `null` si ce n'est pas un point de la courbe.
     */
    fun decode(s: ByteArray): Point? {
        if (s.size != 32) return null
        val y = unpack(s)
        val yy = sq(y)
        val u = sub(yy, one) // y² − 1
        val v = add(mul(yy, d), one) // d·y² + 1
        // x = u·v³·(u·v⁷)^((p−5)/8)
        val v3 = mul(sq(v), v)
        val v7 = mul(sq(v3), v)
        var x = mul(mul(u, v3), pow(mul(u, v7), pMinus5Over8))
        val vxx = mul(v, sq(x))
        if (!equal(vxx, u)) {
            if (!equal(vxx, sub(zero, u))) return null
            x = mul(x, sqrtM1)
        }
        if (isNegative(x) != (s[31].toInt() and 0x80 != 0)) {
            x = sub(zero, x)
        }
        return Point(x, y, one, mul(x, y))
    }

    /** P + Q (formule unifiée, complète sur Ed25519 : elle sert aussi au doublement). */
    fun add(p: Point, q: Point): Point {
        val a = mul(sub(p.y, p.x), sub(q.y, q.x))
        val b = mul(add(p.x, p.y), add(q.x, q.y))
        val c = mul(mul(p.t, q.t), d2)
        var dd = mul(p.z, q.z)
        dd = add(dd, dd)
        val e = sub(b, a)
        val f = sub(dd, c)
        val g = add(dd, c)
        val h = add(b, a)
        return Point(mul(e, f), mul(h, g), mul(g, f), mul(e, h))
    }

    /** −P. */
    fun negate(p: Point): Point = Point(sub(zero, p.x), p.y, p.z, sub(zero, p.t))

    /** L'identité (0, 1). */
    fun identity(): Point = Point(zero, one, one, zero)

    /** Vrai si [8]P est l'identité (0, 1). */
    fun isSmallOrder(p: Point): Boolean {
        var q = p
        repeat(3) { q = add(q, q) }
        return pack(q.x).contentEquals(pack(zero)) && equal(q.y, q.z)
    }

    /**
     * Vrai si l'écriture désigne un point de petit ordre (aussi vrai pour une
     * écriture qui n'est pas un point : à refuser de toute façon).
     */
    fun isWeak(s: ByteArray): Boolean {
        val p = decode(s) ?: return true
        return isSmallOrder(p)
    }

    /** [n]P, n en octets petit-boutistes (doublement et addition, temps variable). */
    fun scalarMult(p: Point, n: ByteArray): Point {
        var r = identity()
        for (i in n.size * 8 - 1 downTo 0) {
            r = add(r, r)
            if ((n[i / 8].toInt() shr (i % 8)) and 1 == 1) r = add(r, p)
        }
        return r
    }

    /** L'écriture canonique d'un point : y réduit, bit 255 = parité de x. */
    fun encode(p: Point): ByteArray {
        val zi = inv(p.z)
        val x = mul(p.x, zi)
        val y = mul(p.y, zi)
        val out = pack(y)
        if (isNegative(x)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /** Le point de base B (y = 4/5, x positif). */
    private val base: Point = decode(ByteArray(32) { if (it == 0) 0x58 else 0x66 })!!

    // MARK: Scalaires

    /** L, l'ordre du sous-groupe, en octets petit-boutistes. */
    val order: ByteArray
        get() = byteArrayOf(
            0xed.toByte(), 0xd3.toByte(), 0xf5.toByte(), 0x5c, 0x1a, 0x63, 0x12, 0x58,
            0xd6.toByte(), 0x9c.toByte(), 0xf7.toByte(), 0xa2.toByte(), 0xde.toByte(), 0xf9.toByte(), 0xde.toByte(), 0x14,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
        )

    private val orderInt = BigInteger(1, order.reversedArray())

    /** Vrai si S < L (32 octets petit-boutistes). */
    fun isCanonicalScalar(s: ByteArray): Boolean {
        if (s.size != 32) return false
        val l = order
        for (i in 31 downTo 0) {
            val a = s[i].toInt() and 0xff
            val b = l[i].toInt() and 0xff
            if (a < b) return true
            if (a > b) return false
        }
        return false // S = L
    }

    /** Des octets petit-boutistes réduits modulo L, en 32 octets petit-boutistes. */
    fun reduce(le: ByteArray): ByteArray {
        val be = BigInteger(1, le.reversedArray()).mod(orderInt).toByteArray()
        val out = ByteArray(32)
        // `toByteArray` est gros-boutiste, avec parfois un octet de signe nul en tête.
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    /** Vrai si l'écriture d'un point a y < p (bit 255, le signe de x, mis à part). */
    fun isCanonicalY(b: ByteArray): Boolean {
        if (b.size != 32) return false
        // y ≥ p ⇔ y = 2²⁵⁵ − 19 + k, k < 19 : 0x7f, 30 × 0xff, puis ≥ 0xed.
        return !(b[31].toInt() and 0x7f == 0x7f && (1 until 31).all { b[it] == 0xff.toByte() } &&
            (b[0].toInt() and 0xff) >= 0xed)
    }

    /**
     * L'équation de vérification de dalek (`RCompute`) : l'écriture de
     * [S]B + [k](−A), k = SHA-512(R ‖ A ‖ M) mod L, est-elle les octets de R ?
     * A et R doivent avoir été contrôlés (points, pas de petit ordre), S
     * canonique.
     */
    fun verifyEquation(publicKey: ByteArray, r: ByteArray, s: ByteArray, message: ByteArray): Boolean {
        if (!isCanonicalY(publicKey)) return verifyEquationSlow(publicKey, r, s, message)
        val sig = Octets.concat(r, s)
        return org.bouncycastle.math.ec.rfc8032.Ed25519.verify(sig, 0, publicKey, 0, message, 0, message.size)
    }

    /** La même équation, calculée ici (toute clé A qui est un point). */
    fun verifyEquationSlow(publicKey: ByteArray, r: ByteArray, s: ByteArray, message: ByteArray): Boolean {
        val a = decode(publicKey) ?: return false
        val k = reduce(Kdf.sha512(Octets.concat(r, publicKey, message)))
        val expected = add(scalarMult(base, s), scalarMult(negate(a), k))
        return encode(expected).contentEquals(r)
    }
}
