// Les tests de propriétés du protocole v3 (proto/tests/protocole.rs et les
// unitaires de session.rs, paquet.rs, groupe.rs, jeton_v3.rs, jetons.rs) :
// aller-retour, désordre, pertes, rejeu, sessions croisées, altérations d'un
// bit, MAX_SKIP, persistance JSON. Les scénarios sont tirés par un
// générateur déterministe (graines fixes) : un échec se rejoue à l'identique.
//
// ML-KEM est toujours là (BouncyCastle) : les cas hybrides le sont vraiment.

package talka.brocolis

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Une session établie et confirmée (B a répondu). */
fun established(seed: String, pq: Boolean): Triple<Session, Session, Deterministic> {
    val rng = Deterministic(seed)
    val alice = Device.generate(rng, T0, pq)
    val bob = Device.generate(rng, T0, pq)
    val a = alice.initiate(bob.bundle(), pq, rng)
    val i = assertNotNull(a.encryptToken("bonjour").initialMessage)
    val (b, c) = bob.accept(i, T0, rng)
    assertEquals("bonjour", texte(c))
    val r = b.encryptToken("re")
    assertEquals("re", texte(a.decrypt(r, T0, rng)))
    return Triple(a, b, rng)
}

fun below(rng: CryptoRng, n: Int): Int = (rng.nextU32() % n.toUInt()).toInt()

class ProtocoleTests {
    // MARK: Session (session.rs)

    @Test
    fun premierEchange() {
        for (usePq in listOf(false, true)) {
            val rng = Deterministic("session")
            val alice = Device.generate(rng, 0u, usePq)
            val bob = Device.generate(rng, 0u, usePq)
            val a = alice.initiate(bob.bundle(), usePq, rng)
            val t = a.encryptToken("Salut 🥦")
            val i = assertNotNull(t.initialMessage)
            assertEquals(if (usePq) Mode.Hybrid else Mode.Classic, i.mode)
            val (b, c) = bob.accept(i, 10u, rng)
            assertEquals("Salut 🥦", texte(c))
            assertContentEquals(a.sessionId, b.sessionId)
            assertEquals(usePq, b.isPostQuantum)
            // Le même premier message une seconde fois : rejeu.
            attendreErreur(Erreur.Rejoue) { bob.accept(i, 11u, rng) }
            attendreErreur(Erreur.Rejoue) { b.decrypt(t, 11u, rng) }
            val r = b.encrypt("Bien reçu")
            assertFalse(a.isConfirmed)
            assertEquals("Bien reçu", texte(a.decryptText(r, 12u, rng)))
            assertTrue(a.isConfirmed)
            assertTrue(a.encryptToken("ok") is Token.Message, "0x02 attendu")
        }
    }

    @Test
    fun jsonAllerRetour() {
        val rng = Deterministic("session")
        val alice = Device.generate(rng, 0u, true)
        val bob = Device.generate(rng, 0u, true)
        val a = alice.initiate(bob.bundle(), true, rng)
        val i = assertNotNull(a.encryptToken("x").initialMessage)
        val (b, _) = bob.accept(i, 0u, rng)
        val b2 = Session.fromJson(b.toJson())
        val a2 = Session.fromJson(a.toJson())
        val t = b2.encrypt("y")
        assertEquals("y", texte(a2.decryptText(t, 0u, rng)))
        attendreErreur(Erreur.Etat) { Session.fromJson("{}") }
        // L'état relu se réécrit à l'identique.
        assertEquals(a2.toJson(), Session.fromJson(a2.toJson()).toJson())
        assertEquals(bob.toJson(), Device.fromJson(bob.toJson()).toJson())
    }

    @Test
    fun pskUnitaire() {
        val rng = Deterministic("psk")
        val key = repeated(7, 32)
        val a = Session.initiatePsk(key, rng)
        val i = assertNotNull(a.encryptToken("par la clé").initialMessage)
        val g = ReplayGuard()
        attendreErreur(Erreur.SessionInconnue) { Session.acceptPsk(listOf(repeated(1, 32)), i, g, 0u, rng) }
        val (b, c) = Session.acceptPsk(listOf(repeated(1, 32), key), i, g, 0u, rng)
        assertEquals("par la clé", texte(c))
        assertEquals(Mode.Psk, b.mode)
        attendreErreur(Erreur.Rejoue) { Session.acceptPsk(listOf(key), i, g, 0u, rng) }
        val r = b.encrypt("réponse")
        assertEquals("réponse", texte(a.decryptText(r, 0u, rng)))
    }

    // MARK: Propriétés (protocole.rs)

    private class EnVol(val fromA: Boolean, val token: Token, val text: String)

    /**
     * Un échange au hasard : envois des deux côtés, livraisons dans le
     * désordre, pertes, rejeux et sauvegardes JSON. Chaque message livré est
     * lu une fois, exactement ; un rejeu ne l'est jamais.
     */
    @Test
    fun echangesAleatoires() {
        var horsDes16 = 0
        for (seed in 0 until 24) {
            var (a, b, rng) = established("échanges/$seed", seed % 2 == 0)
            val inFlight = mutableListOf<EnVol>()
            val delivered = mutableListOf<EnVol>()
            var now = T0
            for (step in 0 until 300) {
                now += 7u
                val choix = below(rng, 10)
                when {
                    choix in 0..3 -> {
                        val fromA = below(rng, 2) == 0
                        val msg = "$seed/$step " + "é".repeat(below(rng, 200))
                        inFlight.add(EnVol(fromA, (if (fromA) a else b).encryptToken(msg), msg))
                    }
                    choix in 4..7 && inFlight.isNotEmpty() -> {
                        val k = below(rng, inFlight.size)
                        val m = inFlight.removeAt(k)
                        assertEquals(m.text, texte((if (m.fromA) b else a).decrypt(m.token, now, rng)), "graine $seed")
                        delivered.add(m)
                    }
                    choix == 8 && inFlight.isNotEmpty() -> {
                        inFlight.removeAt(below(rng, inFlight.size)) // perdu
                    }
                    choix == 9 && delivered.isNotEmpty() -> {
                        val d = delivered[below(rng, delivered.size)]
                        val r = if (d.fromA) b else a
                        val before = r.toJson()
                        // Rejeu : `Rejoue`, sauf si la clé de cliquet du message est
                        // sortie des 16 dernières retenues (§7.5) : alors le message
                        // est refusé autrement (`Dechiffrement`). La référence fait
                        // pareil, au même endroit (graine 6, étape 297).
                        try {
                            r.decrypt(d.token, now, rng)
                            fail("rejeu accepté : graine $seed étape $step")
                        } catch (e: Erreur) {
                            assertTrue(e == Erreur.Rejoue || e == Erreur.Dechiffrement, "graine $seed étape $step : $e")
                            if (e == Erreur.Dechiffrement) horsDes16++
                        }
                        assertEquals(before, r.toJson())
                    }
                    else -> {
                        a = Session.fromJson(a.toJson())
                        b = Session.fromJson(b.toJson())
                    }
                }
                assertTrue(a.skippedCount <= Protocole.maxSkip.toInt())
                assertTrue(b.skippedCount <= Protocole.maxSkip.toInt())
            }
            // Tout ce qui reste en vol arrive encore.
            while (inFlight.isNotEmpty()) {
                val m = inFlight.removeAt(inFlight.size - 1)
                assertEquals(m.text, texte((if (m.fromA) b else a).decrypt(m.token, now, rng)))
            }
        }
        assertEquals(1, horsDes16)
    }

    /** Un bit modifié n'importe où dans un jeton : refusé, et la session est exactement celle d'avant. */
    @Test
    fun alterationsDUnBit() {
        val (a, b, rng) = established("bits", true)
        val token = a.encryptToken("un message à ne pas modifier")
        val bytes = token.toBytes()
        val before = b.toJson()
        for (i in bytes.indices) {
            for (bit in 0 until 8) {
                val x = bytes.copyOf()
                x[i] = (x[i].toInt() xor (1 shl bit)).toByte()
                val t = essai { Token.fromBytes(x) } ?: continue
                attendreEchec("octet $i bit $bit") { b.decrypt(t, T0, rng) }
            }
        }
        assertEquals(before, b.toJson())
        assertEquals("un message à ne pas modifier", texte(b.decrypt(token, T0, rng)))
    }

    /**
     * Un bit modifié dans un premier message (préambule, CT ML-KEM compris) :
     * aucune session n'est ouverte, l'appareil ne retient rien.
     */
    @Test
    fun alterationsDuPremierMessage() {
        val rng = Deterministic("premier")
        val alice = Device.generate(rng, T0, true)
        val bob = Device.generate(rng, T0, true)
        val a = alice.initiate(bob.bundle(), true, rng)
        val token = a.encryptToken("établir")
        val bytes = token.toBytes()
        val before = bob.toJson()
        for (i in bytes.indices step 3) {
            for (bit in 0 until 8) {
                val x = bytes.copyOf()
                x[i] = (x[i].toInt() xor (1 shl bit)).toByte()
                val m = essai { Token.fromBytes(x) }?.initialMessage ?: continue
                attendreEchec("octet $i bit $bit") { bob.accept(m, T0, rng) }
            }
        }
        assertEquals(before, bob.toJson())
        assertEquals("établir", texte(bob.accept(assertNotNull(token.initialMessage), T0, rng).second))
    }

    @Test
    fun maxSkipEtAgeDesClesSautees() {
        val (a, b, rng) = established("skip", false)
        val max = Protocole.maxSkip.toInt()
        val tokens = (0..max + 1).map { a.encryptToken(it.toString()) }
        // Le message n = MAX_SKIP + 1 demanderait 1 001 clés sautées : refusé.
        val before = b.toJson()
        attendreErreur(Erreur.TropDeClesSautees) { b.decrypt(tokens[max + 1], T0, rng) }
        assertEquals(before, b.toJson())
        assertEquals("1000", texte(b.decrypt(tokens[max], T0, rng)))
        assertEquals(max, b.skippedCount)
        assertEquals("1001", texte(b.decrypt(tokens[max + 1], T0, rng)))
        // Un retardataire, encore dans les 30 jours.
        assertEquals("5", texte(b.decrypt(tokens[5], T0 + Protocole.maxSkipAge - 1u, rng)))
        attendreErreur(Erreur.Rejoue) { b.decrypt(tokens[5], T0, rng) }
        // Après 30 jours, les clés sautées sont effacées : le message est perdu.
        attendreEchec { b.decrypt(tokens[6], T0 + Protocole.maxSkipAge, rng) }
        b.purge(T0 + Protocole.maxSkipAge)
        assertEquals(0, b.skippedCount)
    }

    @Test
    fun lePlafondDeClesSauteesEffaceLesPlusVieilles() {
        val (a, b, rng) = established("plafond", false)
        val first = (0 until 600).map { a.encryptToken(it.toString()) }
        val second = (600 until 1200).map { a.encryptToken(it.toString()) }
        b.decrypt(first[599], T0, rng)
        b.decrypt(second[599], T0, rng)
        assertEquals(Protocole.maxSkip.toInt(), b.skippedCount)
        // Les plus vieilles (0..198) sont parties ; 198 et suivantes restent.
        attendreEchec { b.decrypt(first[0], T0, rng) }
        assertEquals("198", texte(b.decrypt(first[198], T0, rng)))
    }

    @Test
    fun sessionsCroisees() {
        val (a1, b1, rng) = established("croise-1", true)
        val (a2, b2, _) = established("croise-2", true)
        val t1 = a1.encryptToken("pour b1")
        val t2 = a2.encryptToken("pour b2")
        val before = b2.toJson()
        attendreErreur(Erreur.SessionInconnue) { b2.decrypt(t1, T0, rng) }
        assertEquals(before, b2.toJson())
        // Même identifiant forcé : la clé ne correspond pas.
        val e = (t1 as? Token.Message)?.envelope ?: fail("0x02 attendu")
        val forced = e.copy(sessionId = b2.sessionId)
        attendreEchec { b2.decrypt(Token.Message(forced), T0, rng) }
        assertEquals(before, b2.toJson())
        assertEquals("pour b1", texte(b1.decrypt(t1, T0, rng)))
        assertEquals("pour b2", texte(b2.decrypt(t2, T0, rng)))
        // Ses propres messages renvoyés : ignorés.
        val own = a1.encryptToken("à moi")
        attendreErreur(Erreur.Rejoue) { a1.decrypt(own, T0, rng) }
    }

    @Test
    fun nouvelleSessionMemeIdentifiant() {
        val rng = Deterministic("nouvelle")
        val alice = Device.generate(rng, T0, true)
        val bob = Device.generate(rng, T0, true)
        val bundle = bob.bundle()
        val a1 = alice.initiate(bundle, true, rng)
        val i1 = assertNotNull(a1.encryptToken("un").initialMessage)
        val (b1, _) = bob.accept(i1, T0, rng)
        // Alice a perdu sa session et en ouvre une autre : même AD, même id.
        val a2 = alice.initiate(bundle, true, rng)
        val t = a2.encryptToken("deux")
        assertContentEquals(b1.sessionId, t.sessionId)
        attendreErreur(Erreur.NouvelleSession) { b1.decrypt(t, T0, rng) }
        val (b2, c) = bob.accept(assertNotNull(t.initialMessage), T0, rng)
        assertEquals("deux", texte(c))
        val r = b2.encryptToken("ok")
        assertEquals("ok", texte(a2.decrypt(r, T0, rng)))
    }

    @Test
    fun sansPqEtPaquetSansMlKem() {
        val rng = Deterministic("sans-pq")
        val alice = Device.generate(rng, T0, true)
        val bob = Device.generate(rng, T0, false)
        val bundle = bob.bundle()
        assertNull(bundle.pq)
        attendreErreur(Erreur.PqIndisponible) { alice.initiate(bundle, true, rng) }
        val a = alice.initiate(bundle, false, rng)
        assertFalse(a.isPostQuantum)
        val i = assertNotNull(a.encryptToken("classique").initialMessage)
        val (b, _) = bob.accept(i, T0, rng)
        assertFalse(b.isPostQuantum)
        assertContentEquals(alice.identity().dh, b.peerIdentity)
        // `initiateBest` retombe en X25519 seul.
        assertFalse(alice.initiateBest(bundle, rng).isPostQuantum)
    }

    @Test
    fun initiateBestHybrideSiPossible() {
        assertTrue(MlKem.isAvailable)
        val rng = Deterministic("meilleur")
        val alice = Device.generate(rng, T0, true)
        val bob = Device.generate(rng, T0, true)
        assertTrue(alice.initiateBest(bob.bundle(), rng).isPostQuantum)
    }

    @Test
    fun ancienneSpkGardeeUnMois() {
        val rng = Deterministic("spk")
        val alice = Device.generate(rng, T0, true)
        val bob = Device.generate(rng, T0, true)
        val old = bob.bundle()
        val a = alice.initiate(old, true, rng)
        val late = a.encryptToken("en retard")
        bob.rotate(rng, T0 + Paquet.prekeyRotation)
        assertNotEquals(old.spkId, bob.bundle().spkId)
        val i = assertNotNull(late.initialMessage)
        // Encore acceptée pendant le mois de grâce…
        val copy = Device.fromJson(bob.toJson())
        copy.accept(i, T0 + Paquet.prekeyRotation + 1u, rng)
        // … puis effacée.
        bob.purge(T0 + Paquet.prekeyRotation + Paquet.prekeyGrace)
        attendreErreur(Erreur.CleInconnue) { bob.accept(i, T0, rng) }
    }

    @Test
    fun pskAllerRetourEtConfidentialiteApresReponse() {
        val rng = Deterministic("psk")
        val key = repeated(9, 32)
        val a = Session.initiatePsk(key, rng)
        val g = ReplayGuard()
        val t1 = a.encryptToken("un")
        val t2 = a.encryptToken("deux")
        val (b, c) = Session.acceptPsk(listOf(key), assertNotNull(t2.initialMessage), g, T0, rng)
        assertEquals("deux", texte(c))
        assertEquals("un", texte(b.decrypt(t1, T0, rng)))
        val r = b.encryptToken("trois")
        assertEquals("trois", texte(a.decrypt(r, T0, rng)))
        assertTrue(a.isConfirmed)
        // Après la réponse, quelqu'un qui n'a que la clé partagée ne lit plus
        // rien.
        val next = a.encryptToken("quatre")
        val (spy, _) = Session.acceptPsk(listOf(key), assertNotNull(t1.initialMessage), ReplayGuard(), T0, Deterministic("espion"))
        attendreEchec { spy.decrypt(next, T0, rng) }
        assertEquals("quatre", texte(b.decrypt(next, T0, rng)))
    }

    @Test
    fun texteTropLongRefuse() {
        val (a, _, _) = established("long", false)
        attendreErreur(Erreur.TropGrand) { a.encrypt("x".repeat(Session.maxPlaintext + 1)) }
        val t = a.encrypt("x".repeat(Session.maxPlaintext))
        Token.decode(t)
        assertNotNull(Base64.decode(t.substring(JetonV3.head.length, t.length - 2)))
    }

    // MARK: Groupes (protocole.rs, groupe.rs)

    @Test
    fun groupeParSessionsADeux() {
        val (a, b, rng) = established("groupe", true)
        val gid = Groupe.newGroupId(rng)
        val chain = SenderKeyState.generate(gid, rng)
        val dist = a.encryptDistribution(chain.distribution())
        assertTrue(Token.decode(dist) is Token.Distribution, "0x03 attendu")
        val d = (b.decryptText(dist, T0, rng) as? Content.Distribution)?.distribution ?: fail("distribution attendue")
        val recv = ReceivedSenderKey.fromDistribution(d)
        // Un 0x02 relu comme 0x03 est refusé : le type est authentifié.
        val e = (a.encryptToken("texte") as? Token.Message)?.envelope ?: fail("0x02 attendu")
        val before = b.toJson()
        attendreEchec { b.decrypt(Token.Distribution(e), T0, rng) }
        assertEquals(before, b.toJson())
        assertEquals("texte", texte(b.decrypt(Token.Message(e), T0, rng)))
        val msgs = (0 until 50).map { chain.encrypt("g$it") }
        val order = (0 until 50).toMutableList()
        for (i in order.size - 1 downTo 1) {
            val j = below(rng, i + 1)
            val x = order[i]
            order[i] = order[j]
            order[j] = x
        }
        for ((k, i) in order.withIndex()) {
            if (k % 7 == 3) continue
            val g = assertNotNull(Token.decode(msgs[i]).groupMessage)
            assertEquals("g$i", recv.decrypt(g, T0))
            attendreErreur(Erreur.Rejoue) { recv.decrypt(g, T0) }
        }
        // Un membre qui n'a que l'ancienne chaîne ne lit pas la nouvelle.
        val renewed = SenderKeyState.generate(gid, rng)
        val g = assertNotNull(renewed.encryptToken("après départ").groupMessage)
        attendreErreur(Erreur.ExpediteurInconnu) { recv.decrypt(g, T0) }
    }

    @Test
    fun groupeAlterationsEtSaut() {
        val rng = Deterministic("groupe-bits")
        val chain = SenderKeyState.generate(repeated(3, 16), rng)
        val recv = ReceivedSenderKey.fromDistribution(chain.distribution())
        val t = chain.encryptToken("signé")
        val bytes = t.toBytes()
        for (i in bytes.indices) {
            val x = bytes.copyOf()
            x[i] = (x[i].toInt() xor 0x40).toByte()
            val g = essai { Token.fromBytes(x) }?.groupMessage ?: continue
            attendreEchec("octet $i") { recv.decrypt(g, T0) }
        }
        assertEquals("signé", recv.decrypt(assertNotNull(t.groupMessage), T0))
        repeat(Protocole.maxSkip.toInt() + 1) { chain.encryptToken("x") }
        val far = assertNotNull(chain.encryptToken("trop loin").groupMessage)
        attendreErreur(Erreur.TropDeClesSautees) { recv.decrypt(far, T0) }
    }

    @Test
    fun groupeDesordreRejeuJson() {
        val rng = Deterministic("groupe")
        val gid = Groupe.newGroupId(rng)
        val s = SenderKeyState.generate(gid, rng)
        val d = SenderKeyDistribution.fromBytes(s.distribution().toBytes())
        val r = ReceivedSenderKey.fromDistribution(d)
        val msgs = (0 until 5).map { s.encrypt("m$it") }
        for (i in listOf(3, 0, 4, 1, 2)) {
            assertEquals("m$i", r.decrypt(assertNotNull(Token.decode(msgs[i]).groupMessage), 0u))
        }
        assertEquals(0, r.skippedCount)
        attendreErreur(Erreur.Rejoue) { r.decrypt(assertNotNull(Token.decode(msgs[2]).groupMessage), 0u) }
        val bad = assertNotNull(Token.decode(msgs[1]).groupMessage)
        bad.ciphertext[0] = (bad.ciphertext[0].toInt() xor 1).toByte()
        attendreErreur(Erreur.Signature) { r.decrypt(bad, 0u) }
        assertContentEquals(s.senderId, ReceivedSenderKey.fromJson(r.toJson()).senderId)
        val s2 = SenderKeyState.fromJson(s.toJson())
        assertEquals(5u, s2.iteration)
        assertContentEquals(s.senderId, s2.senderId)
        // La distribution est vérifiée.
        val bytes = s.distribution().toBytes()
        bytes[16] = (bytes[16].toInt() xor 1).toByte()
        attendreErreur(Erreur.Format) { SenderKeyDistribution.fromBytes(bytes) }
        attendreErreur(Erreur.Format) { SenderKeyDistribution.fromBytes(bytes.copyOf(91)) }
    }

    // MARK: Paquet (paquet.rs)

    @Test
    fun paquetAllerRetourEtSignatures() {
        for (usePq in listOf(false, true)) {
            val d = Device.generate(Deterministic("paquet"), 1_000u, usePq)
            val b = d.bundle()
            val bytes = b.toBytes()
            assertEquals(if (usePq) Paquet.bundleLengthPq else Paquet.bundleLengthClassic, bytes.size)
            assertEquals(b, KeyBundle.fromBytes(bytes))
            assertEquals(b, KeyBundle.fromBase64(b.toBase64()))
            // Chaque bit modifié est refusé.
            for (i in bytes.indices) {
                val x = bytes.copyOf()
                x[i] = (x[i].toInt() xor 0x01).toByte()
                attendreEchec("octet $i") { KeyBundle.fromBytes(x) }
            }
        }
    }

    @Test
    fun rotationEtPurge() {
        val rng = Deterministic("rotation")
        val d = Device.generate(rng, 0u, true)
        val first = d.bundle()
        assertFalse(d.needsRotation(Paquet.prekeyRotation - 1u))
        assertTrue(d.needsRotation(Paquet.prekeyRotation))
        d.rotate(rng, Paquet.prekeyRotation)
        val second = d.bundle()
        assertEquals(maxOf(first.spkId + 1u, 1u), second.spkId)
        d.spkSecret(first.spkId)
        d.pqSeed(assertNotNull(first.pq).id)
        d.purge(Paquet.prekeyRotation + Paquet.prekeyGrace)
        attendreErreur(Erreur.CleInconnue) { d.spkSecret(first.spkId) }
        d.spkSecret(second.spkId)
        val back = Device.fromJson(d.toJson())
        assertEquals(second, back.bundle())
        assertEquals(second.spkId, back.bundle().spkId)
        attendreErreur(Erreur.Etat) { Device.fromJson("{}") }
    }

    @Test
    fun replayGuardBorne() {
        val g = ReplayGuard()
        for (i in 0 until ReplayGuard.max + 10) {
            g.insert(Octets.u64be(i.toULong()) + ByteArray(24), 0u)
        }
        assertEquals(ReplayGuard.max, g.count)
        assertFalse(g.seen(ByteArray(32)))
        g.purge(ReplayGuard.maxAge)
        assertTrue(g.isEmpty)
    }

    // MARK: Jeton v3 (jeton_v3.rs)

    private fun env() = Envelope(repeated(1, 8), Header(repeated(2, 32), 3u, 0x0102_0304u), repeated(9, 176))

    @Test
    fun jetonV3AllerRetourDeChaqueType() {
        val tokens = listOf(
            Token.Message(env()), Token.Distribution(env()),
            Token.Initial(Initial(repeated(4, 32), repeated(5, 32), Mode.Hybrid, repeated(6, JetonV3.kemCtLength), 7u, 8u, env())),
            Token.Initial(Initial(repeated(4, 32), repeated(5, 32), Mode.Classic, null, 7u, 0u, env())),
            Token.Initial(Initial(repeated(0, 32), repeated(5, 32), Mode.Psk, null, 0u, 0u, env())),
            Token.Group(GroupMessage(repeated(1, 16), repeated(2, 8), 3u, repeated(4, 176), repeated(5, 64))),
        )
        for (t in tokens) {
            assertEquals(t, Token.decode(t.encode()))
            // Toute troncature est refusée.
            val bytes = t.toBytes()
            for (n in bytes.indices) {
                attendreEchec("$n") { Token.fromBytes(bytes.copyOf(n)) }
            }
        }
    }

    @Test
    fun jetonV3RefusStricts() {
        var b = Token.Message(env()).toBytes() + bytes(0)
        attendreErreur(Erreur.Format) { Token.fromBytes(b) }
        b = Token.Message(env()).toBytes()
        b[0] = 0x05
        attendreErreur(Erreur.Format) { Token.fromBytes(b) }
        val classicWithPq = Token.Initial(Initial(repeated(4, 32), repeated(5, 32), Mode.Classic, null, 7u, 1u, env()))
        attendreErreur(Erreur.Format) { Token.fromBytes(classicWithPq.toBytes()) }
        attendreErreur(Erreur.JetonAbsent) { Token.decode("[[BROCOLIS:v3:AAAA]] ") }
        attendreErreur(Erreur.Base64) { Token.decode("[[BROCOLIS:v3:A===]]") }
        attendreErreur(Erreur.Format) { Token.fromBytes(ByteArray(0)) }
        attendreErreur(Erreur.TropGrand) { Token.fromBytes(repeated(2, JetonV3.maxTokenBytes + 1)) }
    }

    @Test
    fun jetonsDansUnTexte() {
        val a = Token.Message(env()).encode()
        val text = "avant $a milieu [[BROCOLIS:v3:AAAA]] fin [[BROCOLIS:v3:"
        val found = JetonV3.findAll(text)
        assertEquals(2, found.size)
        assertEquals(a, found[0].text)
        assertNotNull(found[0].token.getOrNull())
        assertNull(found[1].token.getOrNull())
        assertTrue(found[1].token.exceptionOrNull() is Erreur)
    }

    // MARK: Jetons v1/v2 (jetons.rs)

    private fun key(n: Int) = repeated(n, 32)

    @Test
    fun uneCleDonneV1() {
        val token = Jetons.seal("Salut 🥦", listOf(key(1)))
        assertTrue(token.startsWith("[[BROCOLIS:v1:") && token.endsWith("]]"))
        val o = Jetons.open(token, listOf(key(9), key(1)))
        assertEquals("Salut 🥦", o.text)
        assertEquals(1, o.opened)
        assertEquals(0, o.locked)
    }

    @Test
    fun plusieursClesDonnentUnV2() {
        val token = Jetons.seal("À tous", listOf(key(1), key(2), key(3)))
        assertTrue(token.startsWith("[[BROCOLIS:v2:"))
        for (k in 1..3) assertEquals("À tous", Jetons.open(token, listOf(key(k))).text)
        val other = Jetons.open(token, listOf(key(4)))
        assertEquals(token, other.text)
        assertEquals(0, other.opened)
        assertEquals(1, other.locked)
    }

    @Test
    fun seulsLesPassagesMarques() {
        val text = Jetons.seal("Rendez-vous ((au parc)) à ((18 h)) !", listOf(key(1)))
        assertTrue(text.startsWith("Rendez-vous [[BROCOLIS:v1:"))
        assertTrue(text.endsWith("]] !") && !text.contains("parc"))
        val o = Jetons.open(text, listOf(key(1)))
        assertEquals("Rendez-vous au parc à 18 h !", o.text)
        assertEquals(2, o.opened)
        // Un accent combinant collé aux parenthèses ne change rien (octets).
        val combining = Jetons.seal("a((́x))", listOf(key(1)))
        assertEquals("áx", Jetons.open(combining, listOf(key(1))).text)
    }

    @Test
    fun jetonsExistantsEtTexteVide() {
        val token = Jetons.seal("x", listOf(key(1)))
        assertEquals(token, Jetons.seal(token, listOf(key(2))))
        assertEquals("", Jetons.seal("", listOf(key(1))))
        assertEquals("clair", Jetons.seal("clair", emptyList()))
        assertTrue(Jetons.seal("pas fermé ((", listOf(key(1))).startsWith("[[BROCOLIS:v1:"))
    }

    @Test
    fun ouvreUnV2DeLAppBrocolis() {
        // Fait par `BrocolisCrypto.encryptTokenMulti` (l'app Brocolis) pour
        // deux clés ; la seconde suffit à l'ouvrir.
        val token = "[[BROCOLIS:v2:AQLvnoxtj3mqrlMIb1HabsfA84XQ4aZVWz8VLKLbBBRDFX2RZlp6az6BeW8WOMl5FTYB94D0rDj6fDJYoNN7ChsNt7sw0O9LKxrI0KCf7Mc0w0x7mIAiuEK2GEtWrVaagyR3wxedqSiL4QrBphtW7PNxDrLzxNe53E58AnsGGchl3SjQ5700f6rruRdr66oPOJOMYxtHEHd7nqKZ1PAEoDBJD9w=]]"
        val k = assertNotNull(Jetons.keyFromB64("Ie2Z71cbvIw+OLOnnmIdfTenOUIxKShDsFQhDH5C7Uw="))
        assertEquals("Pour vous deux", Jetons.open(token, listOf(key(1), k)).text)
    }

    @Test
    fun ouvreUnJetonFaitALaMain() {
        val k = key(7)
        val nonce = repeated(3, 12)
        val combined = nonce + Kdf.aesGcmSeal(k, nonce, ByteArray(0), utf8("brocolis"))
        val token = "avant [[BROCOLIS:v1:${Base64.encode(combined)}]] après"
        assertEquals("avant brocolis après", Jetons.open(token, listOf(k)).text)
    }

    @Test
    fun trouveUneCleDansUneInvitation() {
        val k = Jetons.generateKey(Hasard.system())
        val invite = "3) Colle CETTE clé :\n\n$k\n\n(C'est notre clé commune.)"
        assertEquals(k, Jetons.findKey(invite))
        assertEquals(k, Jetons.findKey("«$k»"))
        assertNull(Jetons.findKey("pas de clé ici"))
        assertContentEquals(Base64.decode(k), Jetons.keyFromB64("　 $k\u0085"))
    }

    @Test
    fun empreintesCourtesEtStables() {
        val k = Base64.encode(key(7))
        val f = Jetons.fingerprint(k)
        assertEquals(19, f?.length)
        assertEquals(f, Jetons.fingerprint(k))
        assertNotEquals(f, Jetons.fingerprint(Base64.encode(key(8))))
        assertNull(Jetons.fingerprint("pas une clé"))
    }

    @Test
    fun jetonsVerrouillesMasques() {
        val token = Jetons.seal("secret", listOf(key(1)))
        assertEquals("Voici 🔒🥦 !", Jetons.masked("Voici $token !"))
        assertEquals("rien à cacher", Jetons.masked("rien à cacher"))
    }

    @Test
    fun jetonsAbimesRestentTelsQuels() {
        for (text in listOf("[[BROCOLIS:v1:pas-du-base64]]", "[[BROCOLIS:v3:AAAA]]", "[[BROCOLIS:v1:AAAA")) {
            val o = Jetons.open(text, listOf(key(1)))
            assertEquals(text, o.text)
            assertEquals(0, o.opened)
        }
    }

    // MARK: Déterminisme (au-delà du portage Swift)

    /**
     * Une session hybride ouverte par `initiate` avec `Deterministic` est
     * entièrement déterminée par le hasard (chiffré ML-KEM compris, comme la
     * référence) : deux appareils identiques donnent les mêmes jetons.
     */
    @Test
    fun initiateHybrideDeterministe() {
        fun jeton(): String {
            val rng = Deterministic("déterministe")
            val alice = Device.generate(rng, T0, true)
            val bob = Device.generate(rng, T0, true)
            return alice.initiate(bob.bundle(), true, rng).encrypt("même jeton")
        }
        val t = jeton()
        assertEquals(t, jeton())
        assertEquals(JetonV3.kemCtLength, assertNotNull(Token.decode(t).initialMessage?.kemCiphertext).size)
    }
}
