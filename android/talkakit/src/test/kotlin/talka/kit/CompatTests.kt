// TalkaKit relit exactement ce que produit le navigateur (le même code Rust,
// `cargo run -p talka-web --example vectors`) : codes, chiffrement,
// signatures, code de verrouillage, sauvegarde, enveloppes, données gardées.
// Les mêmes vecteurs et les mêmes vérifications que l'iPhone
// (`ios/Tests/TalkaKitTests/CompatTests.swift`).

package talka.kit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.experimental.xor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Les vecteurs du navigateur (`talka.vectors`, donné par Gradle). */
internal object Vectors {
    val v: JsonObject by lazy {
        val path = System.getProperty("talka.vectors") ?: error("la propriété talka.vectors manque")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }
}

internal fun JsonObject.s(k: String): String = this[k]!!.jsonPrimitive.also { check(it.isString) }.content

internal fun JsonObject.o(k: String): JsonObject = this[k]!!.jsonObject

internal fun JsonObject.i(k: String): Int = this[k]!!.jsonPrimitive.content.toInt()

internal fun JsonObject.texts(k: String): List<String> = this[k]!!.jsonArray.map { it.jsonPrimitive.content }

class CompatTests {
    private val v get() = Vectors.v

    private fun identity(key: String): Identity = Identity.fromJson(v[key]!!)

    @Test
    fun testCodes() {
        assertEquals(v.s("code_of_empty"), TalkaCode.of(ByteArray(0), ByteArray(0)))
        assertEquals("WGSEAJNSZJ", TalkaCode.of(ByteArray(0), ByteArray(0)))
        assertEquals("WGSEAJNSZJ", TalkaCode.normalize("wgsea-jnszj"))
        assertNull(TalkaCode.normalize("WGSEA-JNSZ0"))
        assertEquals("WGSEA-JNSZJ", TalkaCode.pretty("WGSEAJNSZJ"))
        assertEquals("WGSEAJNSZJ", TalkaCode.find("https://talka.app/c/WGSEAJNSZJ"))
        assertEquals("WGSEAJNSZJ", TalkaCode.find("https://x.github.io/Talka/app/#add=WGSEAJNSZJ"))
        val a = identity("a")
        assertTrue(a.isValid, "l'identité du navigateur est relue telle quelle")
        assertTrue(TalkaCrypto.verifyKeys(a.code, a.sign, a.dh))
        // Et elle se réécrit à l'identique.
        assertEquals(a, JSON.decode(Identity, JSON.encode(a)))
    }

    @Test
    fun testOpenWhatTheBrowserSealed() {
        val a = identity("a")
        val b = identity("b")
        val opened = TalkaCrypto.open(b, a.code, a.dh, v.s("sealed_a_to_b"))
        assertEquals(v.s("plain"), opened)
        // Attribué à quelqu'un d'autre : illisible.
        assertNull(TalkaCrypto.open(a, b.code, b.dh, v.s("sealed_a_to_b")))
    }

    /**
     * L'expéditeur scellé : ce que le navigateur a scellé s'ouvre ici, et
     * réciproquement ; son jeton de remise se vérifie.
     */
    @Test
    fun testSealedSenderWithTheBrowser() {
        val a = identity("a")
        val b = identity("b")
        val scelle = v.o("scelle")
        val opened = assertNotNull(TalkaCrypto.openSealed(b, scelle.s("a_to_b")))
        assertEquals(a.code, opened.from)
        assertEquals(v.s("sealed_a_to_b"), opened.body)
        assertEquals(v.s("plain"), TalkaCrypto.open(b, opened.from, a.dh, opened.body))
        assertNull(TalkaCrypto.openSealed(a, scelle.s("a_to_b")))

        val jeton = scelle.s("jeton_a")
        assertTrue(TalkaCrypto.verifyJeton(a.code, a.sign, a.dh, jeton))
        assertFalse(TalkaCrypto.verifyJeton(b.code, b.sign, b.dh, jeton))
        // Le même nonce que le navigateur (la signature, elle, varie).
        assertEquals(jeton.split(".").first(), a.jeton.split(".").first())
        assertTrue(TalkaCrypto.verifyJeton(a.code, a.sign, a.dh, a.jeton))

        // Dans l'autre sens, et quelqu'un qui se fait passer pour A.
        val inner = assertNotNull(TalkaCrypto.seal(b, a.code, a.dh, "{}"))
        val sealed = assertNotNull(TalkaCrypto.sealSender(b.code, a.code, a.dh, inner))
        assertFalse(sealed.contains(b.code))
        val back = assertNotNull(TalkaCrypto.openSealed(a, sealed))
        assertEquals(b.code, back.from)
        assertEquals("{}", TalkaCrypto.open(a, back.from, b.dh, back.body))
        val c = Identity.create()
        val forged = assertNotNull(
            TalkaCrypto.sealSender(b.code, a.code, a.dh, assertNotNull(TalkaCrypto.seal(c, a.code, a.dh, "{}"))),
        )
        val claimed = assertNotNull(TalkaCrypto.openSealed(a, forged))
        assertNull(TalkaCrypto.open(a, claimed.from, b.dh, claimed.body))
    }

    /**
     * L'aperçu des notifications : ce que le navigateur a chiffré pour une
     * clé de notification s'ouvre ici, ce qu'Android chiffre aussi, et
     * Android compose le même aperçu que le navigateur.
     */
    @Test
    fun testApercuWithTheBrowser() {
        val ap = v.o("apercu")
        val nk = ap.s("nk")
        val plain = ap.s("plain")
        val priv = assertNotNull(unb64(ap.s("nk_priv")))
        val a = identity("a")
        val b = identity("b")
        val contacts = { code: String -> if (code == a.code) a.dh else null }
        assertTrue(TalkaCrypto.validNk(nk))
        assertFalse(TalkaCrypto.validNk("abc"))
        val opened = assertNotNull(TalkaCrypto.openApercu(priv, ap.s("sealed"), contacts))
        assertEquals(a.code, opened.from)
        assertEquals(plain, opened.plain)
        val mine = assertNotNull(TalkaCrypto.sealApercu(b, nk, "{\"t\":\"🙂\"}"))
        assertEquals("{\"t\":\"🙂\"}", TalkaCrypto.openApercu(priv, mine) { if (it == b.code) b.dh else null }?.plain)
        // Un inconnu, ou B qui se ferait passer pour A : rien.
        assertNull(TalkaCrypto.openApercu(priv, mine, contacts))
        assertNull(TalkaCrypto.openApercu(priv, mine) { a.dh })
        assertNull(TalkaCrypto.openApercu(ByteArray(32) { 7 }, mine) { b.dh })

        val wires = v.texts("wires")
        val msg = assertNotNull(JSON.decode(Wire, wires[0]))
        val ours = assertNotNull(Engine.apercu(a.code, "Léa", msg))
        fun parse(s: String): Map<String, String> = Json.parseToJsonElement(s).jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(parse(plain), parse(ours))
        // La clé de notification voyage dans le profil, et se garde.
        val profile = assertNotNull(wires.mapNotNull { JSON.decode(Wire, it) }.firstOrNull { it.body is Body.Profile })
        val p = profile.body as Body.Profile
        assertEquals(nk, p.nk)
        assertTrue(p.pj, "le navigateur annonce les pièces jointes en v3")
        // v4 : ses appareils, la version de la liste, et ses boîtes.
        assertEquals(listOf(DevInfo("00000000000000b1", nk), DevInfo("00000000000000b2")), p.devs)
        assertEquals(3L, p.dv)
        assertTrue(p.bx)
        val store = v.o("store")
        assertEquals(nk, JSON.decode(Contact, store.s("contact"))?.nk)
    }

    /**
     * Un jeton de remise par contact, comme le navigateur ; et la pièce
     * jointe chiffrée à part, qu'Android sait réunir (et produire).
     */
    @Test
    fun testJetonPerContactAndAttachments() {
        val a = identity("a")
        val b = identity("b")
        val jeton = v.s("jeton_a_pour_b")
        assertEquals(a.jetonNonce(b.code, 0), jeton.split(".").first())
        assertEquals(v.s("jeton_nonce_a_pour_b_1"), a.jetonNonce(b.code, 1))
        assertTrue(TalkaCrypto.verifyJeton(a.code, a.sign, a.dh, jeton))
        assertTrue(TalkaCrypto.verifyJeton(a.code, a.sign, a.dh, a.jeton(b.code, 3)))
        assertNotEquals(a.jetonNonce(b.code, 0), a.jetonNonce(a.code, 0))

        val pj = v.o("pj")
        val small = pj.s("small")
        val blob = assertNotNull(unb64(pj.s("blob")))
        assertTrue(small.contains(Attachment.prefix))
        val joined = assertNotNull(JSON.decode(Wire, Attachment.join(small, blob)))
        val msg = joined.body as Body.Msg
        assertEquals("pj1", msg.id)
        assertEquals(pj.s("data"), msg.content.data)
        val bad = blob.copyOf()
        bad[40] = bad[40] xor 1
        val broken = assertNotNull(JSON.decode(Wire, Attachment.join(small, bad)))
        assertTrue((broken.body as Body.Msg).content.data.isEmpty())
        // Et dans l'autre sens.
        val wire = Wire(Body.Msg("x1", 1.0, "", Content(kind = "image", data = "data:image/png;base64," + "Q".repeat(300_000))))
        val split = assertNotNull(Attachment.split(wire.json()))
        assertTrue(split.small.toByteArray().size < 2_000)
        assertEquals(wire, JSON.decode(Wire, Attachment.join(split.small, split.blob)))
    }

    @Test
    fun testSealBothWays() {
        val a = identity("a")
        val b = identity("b")
        val sealed = assertNotNull(TalkaCrypto.seal(b, a.code, a.dh, "{\"k\":\"typing\",\"on\":true}"))
        assertEquals("{\"k\":\"typing\",\"on\":true}", TalkaCrypto.open(a, b.code, b.dh, sealed))
        val fresh = Identity.create()
        assertTrue(fresh.isValid)
        assertEquals(10, fresh.code.length)
    }

    @Test
    fun testSignatures() {
        val a = identity("a")
        val challenge = v.o("challenge")
        assertTrue(TalkaCrypto.verify(a.sign, "talka-auth:abc".toByteArray(), challenge.s("sig")))
        val mine = a.signChallenge("xyz")
        assertTrue(TalkaCrypto.verify(a.sign, "talka-auth:xyz".toByteArray(), mine))
        assertFalse(TalkaCrypto.verify(a.sign, "talka-auth:xyw".toByteArray(), mine))
    }

    @Test
    fun testSafetyNumber() {
        val a = identity("a")
        val b = identity("b")
        val ka = TalkaCrypto.Keys(a.code, a.sign, a.dh)
        val kb = TalkaCrypto.Keys(b.code, b.sign, b.dh)
        assertEquals(v.s("safety"), TalkaCrypto.safetyNumber(ka, kb))
        assertEquals(v.s("safety"), TalkaCrypto.safetyNumber(kb, ka))
        assertNotEquals(v.s("safety"), TalkaCrypto.safetyNumber(ka, TalkaCrypto.Keys(b.code, a.sign, b.dh)))
        // L'empreinte des liens d'invitation, comme le navigateur.
        val fp = TalkaCode.fingerprint(a.sign, a.dh)
        assertEquals(v.s("fingerprint_a"), fp)
        assertEquals(fp, TalkaCode.fingerprint("https://talka.example/c/$fp/${a.code}"))
        assertEquals(a.code, TalkaCode.find("https://talka.example/c/$fp/${a.code}"))
        assertEquals(fp, TalkaCode.fingerprint("https://x.github.io/Talka/app/#k=$fp&add=${a.code}"))
        assertEquals(a.code, TalkaCode.find("https://x.github.io/Talka/app/#k=$fp&add=${a.code}"))
        assertNull(TalkaCode.fingerprint("https://talka.example/c/${a.code}"))
    }

    /**
     * La clé de l'appareil chiffrée par le code : celle du navigateur
     * s'ouvre sur Android, et inversement ; un mauvais code n'ouvre rien.
     */
    @Test
    fun testDeviceKeyLockedByCode() {
        val cle = v.o("cle")
        val code = cle.s("code")
        val lock = StoreVault.CodeLock(cle.s("salt"), cle.i("m").toUInt(), cle.i("t").toUInt(), cle.s("locked"))
        val device = assertNotNull(unb64(cle.s("device")))
        val key = assertNotNull(StoreVault.unwrap(lock, code))
        assertContentEquals(device, key)
        assertNull(StoreVault.unwrap(lock, "147036"))
        // Dans l'autre sens.
        val mine = assertNotNull(StoreVault.wrap(device, "963852"))
        assertContentEquals(device, StoreVault.unwrap(mine, "963852"))
        assertNull(StoreVault.unwrap(mine, code))
        // Des réglages d'Argon2id absurdes ne font rien tourner.
        val absurd = mine.copy(m = 1u shl 30)
        assertNull(StoreVault.unwrap(absurd, "963852"))
        // Le verrou se garde en JSON et se relit.
        assertEquals(mine, JSON.decode(StoreVault.CodeLock, JSON.encode(mine)))
    }

    @Test
    fun testPinAndBackup() {
        val pin = v.o("pin")
        val hash = PinHash.fromJson(pin["hash"]!!)
        assertTrue(TalkaCrypto.checkPin("2580", hash))
        assertFalse(TalkaCrypto.checkPin("1111", hash))

        val backup = v.o("backup")
        val file = Backup.fromJson(backup["file"]!!)
        assertEquals(backup.s("plain"), TalkaCrypto.decryptBackup("motdepasse", file))
        assertFailsWith<TalkaCrypto.BackupError.WrongPassword> { TalkaCrypto.decryptBackup("autre", file) }
        val mine = assertNotNull(TalkaCrypto.encryptBackup("secret123", "{\"x\":1}"))
        assertEquals("{\"x\":1}", TalkaCrypto.decryptBackup("secret123", mine))
        assertEquals(3, mine.talka)
        assertEquals(mine, JSON.decode(Backup, JSON.encode(mine)))

        // Argon2id, comme le navigateur ; les codes et sauvegardes d'avant
        // (PBKDF2) s'ouvrent toujours, et les codes se rehachent.
        assertEquals(2u, hash.v)
        assertFalse(hash.isOld)
        val ours = TalkaCrypto.hashPin("739154")
        assertTrue(TalkaCrypto.checkPin("739154", ours) && !TalkaCrypto.checkPin("739155", ours))
        assertEquals(ours, JSON.decode(PinHash, JSON.encode(ours)))
        val salt = ByteArray(16) { 3 }
        val old = PinHash(b64(salt), b64(TalkaCrypto.pbkdf2("2580", salt, TalkaCrypto.pinRounds)))
        assertTrue(old.isOld && TalkaCrypto.checkPin("2580", old) && !TalkaCrypto.checkPin("2581", old))
        assertFalse(JSON.encode(old).contains("\"v\""), "comme le navigateur : v, m, t seulement s'il y en a")
        val greedy = ours.copy(m = UInt.MAX_VALUE)
        assertFalse(TalkaCrypto.checkPin("739154", greedy))
        val oldKey = TalkaCrypto.pbkdf2("motdepasse", salt, TalkaCrypto.backupRounds)
        val nonce = randomBytes(12)
        val box = assertNotNull(AesGcm.sealParts(oldKey, nonce, "{}".toByteArray()))
        val oldFile = Backup(talka = 2, salt = b64(salt), iv = b64(nonce), data = b64(box))
        assertEquals("{}", TalkaCrypto.decryptBackup("motdepasse", oldFile))
    }

    private fun isEmptyValue(v: JsonElement): Boolean {
        if (v is JsonNull) return true
        val p = v as? JsonPrimitive ?: return false
        if (p.isString) return p.content.isEmpty()
        if (p.content == "false") return true
        return p.content.toDoubleOrNull() == 0.0
    }

    @Test
    fun testWires() {
        val wires = v.texts("wires")
        assertEquals(17, wires.size)
        for (text in wires) {
            val w = assertNotNull(Wire.parse(text), text)
            assertFalse(w.body is Body.Unknown, "sorte inconnue : $text")
            // Réécrite puis relue : identique.
            assertEquals(w, Wire.parse(w.json()), text)
            // Les mêmes champs que le navigateur (rien ne manque).
            val theirs = Json.parseToJsonElement(text).jsonObject
            val ours = Json.parseToJsonElement(w.json()).jsonObject
            for ((key, value) in theirs) {
                if (!isEmptyValue(value)) assertNotNull(ours[key], "$key manque dans ${w.json()}")
            }
        }
        val msg = assertNotNull(Wire.parse(wires[0]))
        val m = msg.body as Body.Msg
        assertEquals("m1", m.id)
        assertEquals("Léa", m.pn)
        assertEquals("Salut 👋 *Talka*", m.content.text)
        assertEquals("avant", m.content.reply?.text)
        assertEquals("g1", msg.groupId)
        assertEquals(3L, msg.grp?.v)
        assertEquals(86_400L, msg.ttl)
        val mention = assertNotNull(Wire.parse(wires[14]))
        assertEquals(listOf(identity("b").code), (mention.body as Body.Msg).content.mentions)
        assertEquals("g1", mention.groupId)
        val call = assertNotNull(Wire.parse(wires[12])).body as Body.Call
        assertEquals("ice", call.op)
        assertEquals("c1", call.callId)
        assertTrue(call.video)
        assertEquals("0", call.cand?.get("sdpMid")?.string)
        assertEquals(0.0, call.cand?.get("sdpMLineIndex")?.number)
        // Un entier y reste un entier (WebRTC l'attend ainsi).
        assertTrue(Wire(call).json().contains("\"sdpMLineIndex\":0,") || Wire(call).json().contains("\"sdpMLineIndex\":0}"))
        // Un appel à plusieurs : ses participants et son groupe (le dernier).
        val group = assertNotNull(Wire.parse(wires.last())).body as Body.Call
        assertEquals("join", group.op)
        assertEquals("c2", group.callId)
        assertEquals(listOf("AAAAAAAAAA", "BBBBBBBBBB", "CCCCCCCCCC"), group.members)
        assertEquals("g:famille", group.chat)
        // La capsule temporelle : son heure d'ouverture traverse intacte.
        val capsule = assertNotNull(Wire.parse(wires[13])).body as Body.Msg
        assertEquals(1_800_000_000_000.0, capsule.content.openAt)
        assertEquals("À ouvrir plus tard", capsule.content.text)
        // Et un message ordinaire n'en a pas.
        assertNull(m.content.openAt)
        // v4 : l'accusé dit quel appareil a reçu.
        val receipt = assertNotNull(Wire.parse(wires[1]))
        assertEquals(Body.Receipt("read", listOf("m1", "m2"), "00000000000000b1"), receipt.body)
        assertEquals("g1", receipt.g)
        // v4 : un message envoyé depuis un autre appareil du compte.
        val sync = assertNotNull(Wire.parse(wires[15])).body as Body.Sync
        assertEquals("wire", sync.op)
        assertEquals(identity("b").code, sync.c)
        assertEquals(JSONValue.Null, sync.d)
        val sent = assertNotNull(sync.w).body as Body.Msg
        assertEquals("m11", sent.id)
        assertEquals("Envoyé de l'iPad", sent.content.text)
    }

    /**
     * v6 (`docs/SECURITE.md` §7.15) : la même boîte de couverture, les mêmes
     * paliers, et une enveloppe remplie par le navigateur s'ouvre ici.
     */
    @Test
    fun testV6() {
        val b = identity("b")
        val v6 = v.o("v6")
        val cover = v6.o("couverture")
        val secret = assertNotNull(unb64(cover.s("secret")))
        assertEquals(cover.s("boite"), TalkaV4.coverBox(secret, cover.i("jour"), cover.i("i")))
        for (pair in v6["paliers"]!!.jsonArray) {
            val (n, bucket) = pair.jsonArray.map { it.jsonPrimitive.content.toInt() }
            assertEquals(bucket, TalkaCrypto.sealedBucket(n), "$n")
        }
        val filled = v6.s("scelle_rempli")
        assertEquals("x", TalkaCrypto.openSealed(b, filled)?.body)
        // Ce qu'Android scelle a la taille d'un palier, comme le navigateur.
        for (len in listOf(0, 100, 900, 5_000)) {
            val plain = TalkaCrypto.sealedPlain("K7Q2MX9PRT", "", "x".repeat(len))
            assertEquals(TalkaCrypto.sealedBucket(plain.size), plain.size)
        }
        val short = assertNotNull(TalkaCrypto.sealSender("K7Q2MX9PRT", b.code, b.dh, "court"))
        assertEquals(filled.length, short.length, "même palier, même taille")
    }

    /**
     * v4 (`docs/SECURITE.md` §7.11 à §7.13) : les boîtes tournantes,
     * l'expéditeur scellé avec son appareil et la liaison, comme le
     * navigateur. (Le paquet d'un appareil, `V3.verifyDeviceBundle`, viendra
     * avec la v3.)
     */
    @Test
    fun testV4() {
        val a = identity("a")
        val b = identity("b")
        val v4 = v.o("v4")
        assertEquals(TalkaV4.relay, v4.s("relais"))
        val jour = v4.o("jour")
        assertEquals(jour.i("jour"), TalkaV4.day(jour["ms"]!!.jsonPrimitive.content.toDouble()))
        // La clé des boîtes, la même des deux côtés.
        val ab = assertNotNull(TalkaV4.boitesKey(a, b.code, b.dh))
        assertEquals(v4.s("boites_ab"), b64(ab))
        assertContentEquals(ab, TalkaV4.boitesKey(b, a.code, a.dh))
        assertEquals(v4.s("boite_a_vers_b1"), TalkaV4.boite(ab, b.code, "00000000000000b1", 19_675))
        assertNotEquals(v4.s("boite_a_vers_b1"), TalkaV4.boite(ab, b.code, "00000000000000b1", 19_676))
        assertNotEquals(v4.s("boite_a_vers_b1"), TalkaV4.boite(ab, b.code, "00000000000000b2", 19_675))
        // L'expéditeur scellé dit de quel appareil il vient.
        val opened = assertNotNull(TalkaCrypto.openSealedFrom(b, v4.s("scelle_fd")))
        assertEquals(a.code, opened.from)
        assertEquals("00000000000000a1", opened.fd)
        assertEquals("corps", opened.body)
        // Et ce qu'Android scelle s'ouvre pareil (sans `fd` : un ancien client).
        val ours = assertNotNull(TalkaCrypto.sealSender(a.code, "00000000000000a2", b.code, b.dh, "x"))
        assertEquals("00000000000000a2", TalkaCrypto.openSealedFrom(b, ours)?.fd)
        val old = assertNotNull(TalkaCrypto.sealSender(a.code, b.code, b.dh, "x"))
        assertEquals("", TalkaCrypto.openSealedFrom(b, old)?.fd)
        // La liaison : le texte du QR code, sa boîte, la clé, les morceaux.
        val lien = v4.o("lien")
        val offer = assertNotNull(TalkaV4.LinkOffer.parse(lien.s("texte")))
        assertEquals("00000000000000c3", offer.dev)
        assertContentEquals(ByteArray(16) { 7 }, offer.lid)
        assertEquals(lien.s("texte"), offer.text)
        assertEquals(lien.s("boite"), offer.boite)
        assertNull(TalkaV4.LinkOffer.parse("talka-lien:AAAA"))
        assertNull(TalkaV4.LinkOffer.parse(a.code))
        val secret = assertNotNull(TalkaV4.LinkSecret.of(offer, assertNotNull(unb64(lien.s("lk_priv")))))
        val ek = assertNotNull(unb64(lien.s("ek")))
        val key = assertNotNull(secret.key(ek))
        assertEquals(lien.s("cle"), b64(key))
        assertEquals(lien.s("verif"), TalkaV4.linkVerification(ek, offer.lk))
        val morceaux = lien.texts("morceaux")
        var plain = ByteArray(0)
        for (m in morceaux) {
            val chunk = Json.parseToJsonElement(m).jsonObject
            val part = assertNotNull(TalkaV4.linkOpen(key, offer.lid, chunk))
            assertEquals(morceaux.size, part.n)
            plain += part.plain
            // Une autre clé n'ouvre rien.
            assertNull(TalkaV4.linkOpen(ByteArray(32) { 1 }, offer.lid, chunk))
        }
        assertEquals(lien.s("clair"), String(plain))
        // Et dans l'autre sens : ce qu'Android répond s'ouvre chez le nouvel appareil.
        val fresh = TalkaV4.LinkSecret()
        val parsed = assertNotNull(TalkaV4.LinkOffer.parse(fresh.offer.text))
        assertEquals(fresh.offer, parsed)
        val answer = assertNotNull(TalkaV4.linkAnswer(parsed))
        assertContentEquals(answer.key, fresh.key(answer.ek))
        val big = ByteArray(TalkaV4.linkChunk + 10) { it.toByte() }
        val chunks = assertNotNull(TalkaV4.linkSeal(answer.key, parsed.lid, answer.ek, big))
        assertEquals(2, chunks.size)
        val back = arrayOfNulls<ByteArray>(2)
        for (text in chunks.reversed()) {
            val chunk = Json.parseToJsonElement(text).jsonObject
            val part = assertNotNull(TalkaV4.linkOpen(answer.key, parsed.lid, chunk))
            back[part.i] = part.plain
        }
        assertContentEquals(big, back.filterNotNull().fold(ByteArray(0)) { acc, x -> acc + x })
        assertEquals(7, TalkaV4.linkVerification(answer.ek, parsed.lk).length)
        // Les identifiants d'appareil.
        assertTrue(TalkaV4.isDev(TalkaV4.newDev()))
        assertFalse(TalkaV4.isDev("00000000000000A1"))
        assertFalse(TalkaV4.isDev("00000000000000a"))
        assertContentEquals(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x01), TalkaV4.unb64url(TalkaV4.b64url(byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0x01))))
        assertNull(TalkaV4.unb64url("+/"))
    }

    @Test
    fun testStoredData() {
        val store = v.o("store")
        val m = assertNotNull(JSON.decode(Message, store.s("message")))
        assertEquals("image", m.kind)
        assertEquals(4, m.w)
        assertEquals(2.0, m.exp)
        assertEquals(1, m.reactions.size)
        assertEquals(Message.Tick.sent, m.tick)
        val chat = assertNotNull(JSON.decode(Chat, store.s("chat")))
        assertTrue(chat.group)
        assertEquals("📷 Photo", chat.last?.text)
        val contact = assertNotNull(JSON.decode(Contact, store.s("contact")))
        assertTrue(contact.saved)
        assertEquals("Bob", contact.name)
        assertNotNull(JSON.decode(StatusItem, store.s("status")))
        assertEquals(42.0, JSON.decode(CallEntry, store.s("call"))?.dur)
        assertEquals("m1", JSON.decode(OutItem, store.s("outbox"))?.msgId)
        assertEquals("B00T", JSON.decode(OutItem, store.s("outbox"))?.boot)
        // v4 : les appareils déjà servis.
        val out = assertNotNull(JSON.decode(OutItem, store.s("outbox")))
        assertEquals(listOf("00000000000000b1"), out.sent)
        assertEquals(emptyList(), out.got)
        assertEquals(out, JSON.decode(OutItem, JSON.encode(out)))
        assertFalse(JSON.encode(out).contains("\"got\""), "comme le navigateur : une liste vide n'est pas écrite")
        // Le contenu déjà échappé une fois donne le même JSON.
        assertEquals(JSON.encode(out), out.json(JSON.encode(out.payload)))
        assertEquals(Settings(), JSON.decode(Settings, store.s("settings")))
        assertEquals("Léa", JSON.decode(Profile, store.s("profile"))?.name)
        // Et dans l'autre sens : ce que garde Android se relit à l'identique.
        assertEquals(m, JSON.decode(Message, JSON.encode(m)))
        assertEquals(chat, JSON.decode(Chat, JSON.encode(chat)))
        assertEquals(contact, JSON.decode(Contact, JSON.encode(contact)))
        val status = assertNotNull(JSON.decode(StatusItem, store.s("status")))
        assertEquals(status, JSON.decode(StatusItem, JSON.encode(status)))
        val call = assertNotNull(JSON.decode(CallEntry, store.s("call")))
        assertEquals(call, JSON.decode(CallEntry, JSON.encode(call)))
    }

    /**
     * L'épingle d'une discussion : la même clé que le navigateur (`pin_msg`),
     * absente des anciennes données.
     */
    @Test
    fun testPinnedMessageField() {
        val old = assertNotNull(JSON.decode(Chat, """{"id":"WGSEAJNSZJ","unread":2}"""))
        assertEquals("", old.pinMsg)
        val chat = old.copy(pinMsg = "m1")
        val json = JSON.encode(chat)
        assertTrue(json.contains(""""pin_msg":"m1""""))
        assertEquals("m1", JSON.decode(Chat, json)?.pinMsg)
    }

    /** Un champ d'un autre type prend sa valeur par défaut, comme `c.v(_:_:)`. */
    @Test
    fun testFieldsOfTheWrongTypeFallBack() {
        val c = assertNotNull(JSON.decode(Chat, """{"id":"X","unread":"2","pinned":1,"members":["a",3],"last":"?"}"""))
        assertEquals(0, c.unread)
        assertFalse(c.pinned)
        assertEquals(emptyList(), c.members)
        assertNull(c.last)
        assertNull(JSON.decode(Chat, "[1]"))
        assertNull(JSON.decode(Identity, """{"code":"X"}"""), "une identité incomplète ne se relit pas")
        assertNull(Wire.parse("pas du JSON"))
        assertEquals(Body.Unknown("plus-tard"), Wire.parse("""{"k":"plus-tard"}""")?.body)
    }
}

/**
 * La pastille « en ligne » : un réglage que le navigateur et l'iPhone
 * partagent (sauvegardes), activé par défaut, même dans les anciens réglages.
 */
class PresenceSettingTests {
    @Test
    fun testPresenceDotDefaultsAndRoundTrip() {
        val old = """{"theme":"dark","hide_online":true}"""
        val s = assertNotNull(JSON.decode(Settings, old))
        assertTrue(s.presenceDot)
        assertTrue(s.hideOnline)
        val off = s.copy(presenceDot = false)
        val json = JSON.encode(off)
        assertTrue(json.contains("\"presence_dot\":false"))
        assertEquals(false, JSON.decode(Settings, json)?.presenceDot)
    }
}
