// Fuzzing « du pauvre » (proto/tests/robustesse.rs) : des milliers d'entrées
// aléatoires et de mutations de jetons valides passent par chaque décodeur et
// par le déchiffrement v3. Aucune ne doit faire planter (seules des `Erreur`
// sortent) ni boucler, et aucune entrée mutée ne doit être acceptée par le
// déchiffrement.

package talka.brocolis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RobustesseTests {
    private fun randomBytes(rng: Deterministic, max: Int): ByteArray = rng.bytes((rng.nextU32() % (max + 1).toUInt()).toInt())

    /** Une mutation : bits, octets, coupe, insertion, duplication. */
    private fun mutate(rng: Deterministic, data: ByteArray): ByteArray {
        var v = data.toMutableList()
        repeat(1 + (rng.nextU32() % 4u).toInt()) {
            val i = (rng.nextU32() % maxOf(v.size, 1).toUInt()).toInt()
            val c = rng.nextU32() % 6u
            when {
                c == 0u && v.isNotEmpty() -> v[i] = (v[i].toInt() xor (1 shl (rng.nextU32() % 8u).toInt())).toByte()
                c == 1u && v.isNotEmpty() -> v[i] = rng.nextU32().toByte()
                c == 2u -> v = v.subList(0, minOf(i, v.size)).toMutableList()
                c == 3u -> v.add(minOf(i, v.size), rng.nextU32().toByte())
                c == 4u && v.isNotEmpty() -> {
                    val j = (rng.nextU32() % v.size.toUInt()).toInt()
                    val a = minOf(i, j)
                    val b = maxOf(i, j)
                    v.addAll(a, v.subList(a, b).toList())
                }
                else -> v.addAll(randomBytes(rng, 8).toList())
            }
        }
        return v.toByteArray()
    }

    /** Ce que donne `bloc` : rien ou une `Erreur`, jamais une autre exception. */
    private fun sansPlanter(bloc: () -> Any?) {
        essai(bloc)
    }

    @Test
    fun decodeursEntreesAleatoires() {
        val rng = Deterministic("aleatoire")
        val key = listOf(repeated(1, 32))
        for (i in 0 until 3_000) {
            val data = randomBytes(rng, 2_000)
            if (data.isNotEmpty() && i % 2 == 0) data[0] = (1 + i % 4).toByte()
            sansPlanter { Token.fromBytes(data) }
            sansPlanter { KeyBundle.fromBytes(data) }
            sansPlanter { SenderKeyDistribution.fromBytes(data) }
            sansPlanter { Remplissage.unpad(data) }
            val text = String(data, Charsets.UTF_8)
            sansPlanter { Token.decode(text) }
            Jetons.open(text, key)
            Jetons.masked(text)
            Jetons.findKey(text)
            sansPlanter { CleProtegee.v2Params(text) }
            val b64 = Base64.encode(data)
            sansPlanter { Token.decode("[[BROCOLIS:v3:$b64]]") }
            Jetons.open("[[BROCOLIS:v1:$b64]] [[BROCOLIS:v2:$b64]]", key)
            sansPlanter { CleProtegee.v2Params("[[BROCOLIS-CLE:v2:$b64]]") }
            sansPlanter { Session.fromJson(text) }
            sansPlanter { Device.fromJson(text) }
            sansPlanter { SenderKeyState.fromJson(text) }
            sansPlanter { ReceivedSenderKey.fromJson(text) }
            sansPlanter { ReplayGuard.fromJson(text) }
            JetonV3.findAll(text)
        }
    }

    @Test
    fun mutationsDeJetonsV3() {
        val rng = Deterministic("mutations")
        val alice = Device.generate(rng, T0, true)
        val bob = Device.generate(rng, T0, true)
        val a = alice.initiate(bob.bundle(), true, rng)
        val first = a.encryptToken("premier")
        val (b, _) = bob.accept(assertNotNull(first.initialMessage), T0, rng)
        val reply = b.encryptToken("réponse")
        val chain = SenderKeyState.generate(repeated(5, 16), rng)
        val recv = ReceivedSenderKey.fromDistribution(chain.distribution())
        val group = chain.encryptToken("groupe")
        val dist = Token.decode(b.encryptDistribution(chain.distribution()))
        val samples = listOf(first.toBytes(), reply.toBytes(), group.toBytes(), dist.toBytes())
        val bobBefore = bob.toJson()
        val aBefore = a.toJson()
        var decoded = 0
        for (k in 0 until 2_000) {
            val base = samples[k % samples.size]
            val m = mutate(rng, base)
            if (m.contentEquals(base)) continue
            val t = essai { Token.fromBytes(m) } ?: continue
            decoded++
            // Le jeton muté peut rester bien formé : il ne doit jamais être lu.
            if (t.toBytes().contentEquals(base)) continue
            when (t) {
                is Token.Initial -> {
                    attendreEchec { bob.accept(t.initial, T0, rng) }
                    attendreEchec { Session.acceptPsk(listOf(repeated(1, 32)), t.initial, ReplayGuard(), T0, rng) }
                    attendreEchec { b.decrypt(t, T0, rng) }
                }
                is Token.Message, is Token.Distribution -> attendreEchec { a.decrypt(t, T0, rng) }
                is Token.Group -> attendreEchec { recv.decrypt(t.message, T0) }
            }
        }
        assertTrue(decoded > 50)
        assertEquals(bobBefore, bob.toJson())
        assertEquals(aBefore, a.toJson())
        // Les originaux passent toujours.
        a.decrypt(reply, T0, rng)
        recv.decrypt(assertNotNull(group.groupMessage), T0)
    }

    @Test
    fun mutationsDEtatsJson() {
        val rng = Deterministic("json")
        val alice = Device.generate(rng, T0, false)
        val bob = Device.generate(rng, T0, false)
        val a = alice.initiate(bob.bundle(), false, rng)
        val (b, _) = bob.accept(assertNotNull(a.encryptToken("x").initialMessage), T0, rng)
        val texts = listOf(b.toJson(), bob.toJson())
        for (k in 0 until 1_500) {
            val m = mutate(rng, utf8(texts[k % 2]))
            val text = String(m, Charsets.UTF_8)
            // Un état relu, même altéré, ne fait jamais planter.
            essai { Session.fromJson(text) }?.let { s ->
                sansPlanter { s.encrypt("y") }
                sansPlanter { s.decryptText("[[BROCOLIS:v3:AAAA]]", T0, rng) }
            }
            essai { Device.fromJson(text) }?.let { d -> sansPlanter { d.bundle() } }
        }
    }

    @Test
    fun mutationsDAnciensJetons() {
        val rng = Deterministic("anciens")
        val key = repeated(3, 32)
        val v1 = Jetons.sealWith("ancien", listOf(key), rng)
        val v2 = Jetons.sealWith("ancien", listOf(key, repeated(4, 32)), rng)
        for (k in 0 until 2_000) {
            val m = mutate(rng, utf8(if (k % 2 == 0) v1 else v2))
            val o = Jetons.open(String(m, Charsets.UTF_8), listOf(key))
            // Une altération qui garde l'enveloppe ne s'ouvre jamais en un
            // autre texte.
            if (o.opened > 0) assertNotNull(Octets.find(utf8(o.text), utf8("ancien")), o.text)
        }
    }
}
