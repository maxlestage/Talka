// La v4 sans réseau (`docs/SECURITE.md` §7.11 à §7.13), comme les tests du
// navigateur et de l'iPhone (`V4Tests.swift`) : la liste des appareils, une
// session par appareil, la session ouverte avant de connaître les
// appareils qui passe à l'adresse de l'appareil, et la liaison (ce qui
// part, ce qui est rangé).

package talka.kit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import talka.brocolis.KeyBundle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V4Tests {
    private val phones = Phones()

    @AfterTest
    fun cleanUp() = phones.close()

    private fun device(dev: String, name: String = "iPhone") = AccountDevice(dev, name, 1.0)

    /** `merge` du Swift : la liste reçue remplace la nôtre si elle est gardée. */
    private class Mine(var list: Devices) {
        fun merge(other: Devices): Boolean {
            val merged = list.merge(other) ?: return false
            list = merged
            return true
        }
    }

    @Test
    fun testDevicesMerge() {
        val mine = Mine(Devices(v = 2, list = listOf(device("00000000000000a1"), device("00000000000000a2"))))
        // Plus ancienne ou égale : ignorée.
        assertFalse(mine.merge(Devices(v = 2, list = listOf(device("00000000000000a1")))))
        assertFalse(mine.merge(Devices(v = 1, list = emptyList())))
        // Mal formée : ignorée.
        assertFalse(mine.merge(Devices(v = 3, list = listOf(device("pas-un-appareil")))))
        val seven = (1..7).map { device("00000000000000%02x".format(it)) }
        assertFalse(mine.merge(Devices(v = 3, list = seven)))
        assertEquals(2, mine.list.v)
        // Plus récente : gardée.
        assertTrue(mine.merge(Devices(v = 3, list = listOf(device("00000000000000a1")))))
        assertEquals(listOf("00000000000000a1"), mine.list.list.map { it.dev })
        assertTrue(mine.list.has("00000000000000a1"))
        assertFalse(mine.list.has("00000000000000a2"))
        assertEquals(listOf(DevInfo("00000000000000a1")), mine.list.announced)
        // Le JSON du navigateur (`v`, `list`, `nk` seulement s'il y en a une).
        val json = JSON.encode(Devices(v = 1, list = listOf(AccountDevice("00000000000000a1", "iPad", 5.0))))
        val obj = JSON.parse(json) as? JsonObject
        assertEquals(1, obj?.get("v").asInt())
        val first = (obj?.get("list") as? JsonArray)?.firstOrNull() as? JsonObject
        assertEquals(setOf("dev", "name", "at"), first?.keys)
        assertEquals(
            "x",
            JSON.decode(Devices, """{"v":4,"list":[{"dev":"00000000000000b1","name":"Chrome sur Mac","at":1.5,"nk":"x"}]}""")?.list?.first()?.nk,
        )
        assertEquals(Devices(), JSON.decode(Devices, "{}"))
        assertNull(obj?.get("gone"), "sans appareil retiré, le JSON est celui d'avant")
    }

    /**
     * Un contact revient en ligne : ses absences de paquet retenues sont
     * oubliées, à son code comme à chacun de ses appareils (un appareil
     * tout juste lié n'attend pas dix minutes).
     */
    @Test
    fun testAContactBackOnlineIsAskedAgainForItsBundles() = phones.run {
        val e = phones.engine(phones.store(), "http://127.0.0.1:9")
        val known = deviceBundle(Side(), "00000000000000a2")
        e.bundleCache["CODE"] = CachedBundle(null, 1.0)
        e.bundleCache["CODE/00000000000000a1"] = CachedBundle(null, 1.0)
        e.bundleCache["CODE/00000000000000a2"] = CachedBundle(known, 1.0)
        e.bundleCache["CODEX/00000000000000a1"] = CachedBundle(null, 1.0)
        e.bundleCache["AUTRE"] = CachedBundle(null, 1.0)
        e.relay.onPresence?.invoke("CODE", true, null)
        assertEquals(setOf("AUTRE", "CODE/00000000000000a2", "CODEX/00000000000000a1"), e.bundleCache.keys)
        assertFalse(e.forgetMissingBundles("CODE"))
    }

    /**
     * Une sauvegarde restaurée remplace l'appareil d'où elle vient : un
     * identifiant neuf, et l'ancien parmi les appareils retirés (jamais deux
     * appareils avec la même identité).
     */
    @Test
    fun testRestoredBackupReplacesItsDevice() = phones.run {
        val first = phones.store()
        Engine.createAccount(first, "Alice", "")
        val a = phones.engine(first, "http://127.0.0.1:9")
        assertTrue(a.devices.has(a.dev))
        val file = assertNotNull(a.exportBackup("motdepasse"))
        val other = phones.store()
        Engine.importBackup(other, "motdepasse", file)
        val b = phones.engine(other, "http://127.0.0.1:9")
        assertEquals(a.me.code, b.me.code)
        assertTrue(TalkaV4.isDev(b.dev))
        assertNotEquals(a.dev, b.dev)
        assertEquals(listOf(b.dev), b.devices.list.map { it.dev })
        assertEquals(listOf(a.dev), b.devices.gone)
    }

    /** Les appareils retirés sont retenus (seize au plus), comme dans le navigateur : chaque appareil les redit au relais à chaque connexion. */
    @Test
    fun testRemovedDevicesAreRemembered() {
        var list = Devices(v = 3, list = listOf(device("00000000000000a1"), device("00000000000000a2")))
        list = list.remove("00000000000000a2")
        assertEquals(4, list.v)
        assertFalse(list.has("00000000000000a2"))
        assertEquals(listOf("00000000000000a2"), list.gone)
        list = list.remove("00000000000000a2")
        assertEquals(1, list.gone.size)
        for (i in 0 until 20) list = list.remove("%016x".format(i))
        assertEquals(maxGone, list.gone.size)
        assertEquals("%016x".format(19), list.gone.first())
        // Le JSON du navigateur, dans les deux sens.
        val back = JSON.decode(Devices, JSON.encode(list))
        assertEquals(list.gone, back?.gone)
        assertEquals(listOf("00000000000000c1"), JSON.decode(Devices, """{"v":2,"list":[],"gone":["00000000000000c1"]}""")?.gone)
        // Reçue avec des retirés mal formés, ou trop : refusée.
        val mine = Mine(Devices())
        val bad = list.copy(gone = list.gone + "pas-un-appareil")
        assertFalse(mine.merge(bad))
        assertTrue(mine.merge(list))
        assertEquals(list.gone, mine.list.gone)
    }

    class Side(val id: Identity = Identity.create()) {
        val engine = V3Engine(null, 1_000uL)
    }

    private fun deviceBundle(s: Side, dev: String): KeyBundle {
        val p = s.engine.publishedDevice(s.id, dev)!!
        return V3.verifyDeviceBundle(s.id.code, s.id.sign, s.id.dh, dev, p.bundle, p.sig)!!
    }

    /** Le paquet d'un appareil est lié à cet appareil ; une session par appareil d'un même compte (les adresses `<code>/<dev>`). */
    @Test
    fun testDeviceBundlesAndSessionsPerDevice() {
        val d1 = "00000000000000b1"
        val d2 = "00000000000000b2"
        val a = Side()
        val b1 = Side()
        // B2 : un autre appareil du compte de B1 (la même identité, son propre
        // appareil v3).
        val b2 = Side(b1.id)
        val b = b1.id
        val p = assertNotNull(b1.engine.publishedDevice(b, d1))
        assertNotNull(V3.verifyDeviceBundle(b.code, b.sign, b.dh, d1, p.bundle, p.sig))
        // Présenté pour un autre appareil, ou comme un paquet d'avant : refusé.
        assertNull(V3.verifyDeviceBundle(b.code, b.sign, b.dh, d2, p.bundle, p.sig))
        assertNull(V3.verifyBundle(b.code, b.sign, b.dh, p.bundle, p.sig))
        // A écrit à chacun : une session par adresse, l'enveloppe des codes.
        val addr1 = "${b.code}/$d1"
        val addr2 = "${b.code}/$d2"
        val m1 = assertNotNull(a.engine.sealTo(a.id, addr1, b.code, b.dh, deviceBundle(b1, d1), """{"k":1}"""))
        val m2 = assertNotNull(a.engine.sealTo(a.id, addr2, b.code, b.dh, deviceBundle(b2, d2), """{"k":2}"""))
        assertTrue(m1.v3 && m2.v3)
        val from = "${a.id.code}/00000000000000a1"
        fun open(side: Side, body: String): V3.Opened = side.engine.openFrom(side.id, from, a.id.code, a.id.dh, body, 2_000uL)
        assertEquals(V3.Opened.V3("""{"k":1}"""), open(b1, m1.body))
        assertEquals(V3.Opened.V3("""{"k":2}"""), open(b2, m2.body))
        // Chacun ne lit que la sienne.
        assertTrue(open(b1, m2.body) is V3.Opened.Failed, "lu par le mauvais appareil")
        assertTrue(a.engine.hasSession(addr1) && a.engine.hasSession(addr2))
        assertFalse(a.engine.hasSession(b.code))
    }

    /**
     * B (un appareil) écrit à A sans connaître ses appareils (à son code) ;
     * A, qui sait de quel appareil vient B, range la session sous son
     * adresse ; la réponse de A, dans cette session, se lit chez B, qui la
     * cherche sous l'adresse de l'appareil de A : la session y passe.
     */
    @Test
    fun testASessionOpenedBeforeKnowingDevicesMovesToTheDevice() {
        val a = Side()
        val b = Side()
        val aDev = "${a.id.code}/00000000000000a1"
        val bDev = "${b.id.code}/00000000000000b1"
        val bundleA = assertNotNull(a.engine.published(a.id))
        val first = assertNotNull(
            b.engine.seal(
                b.id, a.id.code, a.id.dh,
                V3.verifyBundle(a.id.code, a.id.sign, a.id.dh, bundleA.bundle, bundleA.sig),
                """{"k":1}""",
            ),
        )
        assertEquals(V3.Opened.V3("""{"k":1}"""), a.engine.openFrom(a.id, bDev, b.id.code, b.id.dh, first.body, 2_000uL))
        val reply = assertNotNull(a.engine.sealTo(a.id, bDev, b.id.code, b.id.dh, null, """{"k":2}"""))
        assertTrue(reply.v3)
        b.engine.takeDirty()
        assertEquals(V3.Opened.V3("""{"k":2}"""), b.engine.openFrom(b.id, aDev, a.id.code, a.id.dh, reply.body, 2_000uL))
        assertTrue(b.engine.hasSession(aDev))
        assertFalse(b.engine.hasSession(a.id.code))
        // Les deux adresses sont à réécrire (l'ancienne, vidée).
        val dirty = b.engine.takeDirty().peers
        assertEquals(setOf(a.id.code, aDev), dirty.map { it.code }.toSet())
        assertEquals("", dirty.first { it.code == a.id.code }.json)
        val again = assertNotNull(b.engine.sealTo(b.id, aDev, a.id.code, a.id.dh, null, """{"k":3}"""))
        assertTrue(again.v3)
        assertEquals(V3.Opened.V3("""{"k":3}"""), a.engine.openFrom(a.id, bDev, b.id.code, b.id.dh, again.body, 2_000uL))
    }

    /** Cet appareil a son identifiant, gardé ; lié depuis un autre appareil, il prend son vrai nom dans la liste. */
    @Test
    fun testEnsureDeviceAndItsName() = phones.run {
        val store = phones.store()
        Engine.createAccount(store, "Léa", "")
        val e = phones.engine(store)
        assertTrue(TalkaV4.isDev(e.dev))
        assertEquals(listOf(e.dev), e.devices.list.map { it.dev })
        assertEquals(1, e.devices.v)
        assertTrue(e.siblings.isEmpty())
        val again = phones.engine(store)
        assertEquals(e.dev, again.dev)
        assertEquals(1, again.devices.v)
        // Le profil annonce nos appareils et nos boîtes.
        val profile = again.profileBody(Identity.create().code) as Body.Profile
        assertEquals(listOf(DevInfo(e.dev)), profile.devs)
        assertEquals(1, profile.dv)
        assertTrue(profile.bx)

        var list = again.devices
        list = list.copy(list = listOf(list.list[0].copy(name = Engine.newDeviceName)) + AccountDevice("00000000000000f1", "Chrome sur Mac", 1.0))
        store.write("kv", "appareils", list)
        val name = Engine.deviceName
        Engine.deviceName = "iPad"
        try {
            val renamed = phones.engine(store)
            assertEquals("iPad", renamed.devices.list.first { it.dev == e.dev }.name)
            assertEquals(2, renamed.devices.v)
            assertEquals(listOf("00000000000000f1"), renamed.siblings)
            assertEquals(listOf("00000000000000f1"), renamed.targets(renamed.me.code))
        } finally {
            Engine.deviceName = name
        }
    }

    /** Ce que la liaison emporte (et pas : ce qui est propre à l'appareil), puis ce que le nouvel appareil range. */
    @Test
    fun testLinkDumpAndImport() = phones.run {
        val store = phones.store()
        Engine.createAccount(store, "Léa", "")
        val e = phones.engine(store)
        val bob = Identity.create()
        e.saveContact(Contact(code = bob.code, name = "Bob", saved = true, sign = bob.sign, dh = bob.dh))
        e.ensureDirectChat(bob.code)
        val small = e.send(bob.code, Content(kind = "text", text = "Salut"))
        val bigContent = Content(kind = "image", text = "", data = "data:image/jpeg;base64," + "A".repeat(600 * 1024))
        val big = e.send(bob.code, bigContent)
        store.write("kv", "pin", TalkaCrypto.hashPin("123456"))
        store.writeRaw("kv", V3.sessionPrefix + bob.code, "{}")
        val dump = e.linkDump()
        val keys = dumpRows(dump["kv"]).mapNotNull { it.rowKey() }
        assertTrue("identity" in keys)
        assertTrue("profile" in keys)
        for (own in listOf("dev", "pin", "appareils", V3.sessionPrefix + bob.code, V3.deviceKey)) {
            assertFalse(own in keys, own)
        }
        assertEquals(0, (dump["outbox"] as? JsonArray)?.size)
        val rows = dumpRows(dump["messages"])
        assertEquals(2, rows.size)
        val kept = rows.mapNotNull { r -> r[1].asString()?.let { JSON.decode(Message, it) } }
        assertEquals("Salut", kept.firstOrNull { it.id == small?.id }?.text)
        assertEquals("", kept.firstOrNull { it.id == big?.id }?.data, "une pièce jointe de plus de 512 Kio reste là")

        // Le nouvel appareil range le compte, avec son propre identifiant.
        val newDev = "00000000000000c3"
        val list = e.devices.let { it.copy(v = it.v + 1, list = it.list + AccountDevice(newDev, Engine.newDeviceName, 2.0)) }
        fun payload(code: String, devices: Devices): ByteArray =
            JSON.write(jsonOf("talka" to "lien", "v" to 1, "code" to code, "devices" to devices.toJson(), "data" to dump)).toByteArray()
        val other = phones.store()
        // Pas pour cet appareil, ou pas l'identité annoncée : refusé.
        assertFalse(Engine.importLink(payload(e.me.code, e.devices), newDev, other))
        assertFalse(Engine.importLink(payload(bob.code, list), newDev, other))
        assertFalse(Engine.hasAccount(other))
        assertTrue(Engine.importLink(payload(e.me.code, list), newDev, other))
        val linked = phones.engine(other)
        assertEquals(e.me, linked.me)
        assertEquals(newDev, linked.dev)
        assertEquals(listOf(e.dev, newDev), linked.devices.list.map { it.dev })
        assertEquals(Engine.deviceName, linked.devices.list.last().name, "il prend son vrai nom")
        assertEquals(listOf(e.dev), linked.siblings)
        assertEquals("Bob", linked.contact(bob.code)?.name)
        assertEquals(2, linked.messages(bob.code).size)
        assertNull(other.read("kv", "pin", PinHash))
        assertTrue(other.all("outbox", OutItem).isEmpty())
    }

    /**
     * Ce qui part vers nos autres appareils (`sync`) : les objets `d` ont
     * leurs clés dans l'ordre, comme `serde_json::json!` du navigateur.
     */
    @Test
    fun testSyncPayloadsHaveTheBrowserShape() = phones.run {
        val store = phones.store()
        Engine.createAccount(store, "Léa", "")
        val e = phones.engine(store)
        // Un autre appareil du compte : ce que fait celui-ci lui est redit.
        store.write("kv", "appareils", e.devices.copy(v = e.devices.v + 1, list = e.devices.list + AccountDevice("00000000000000f1", "Chrome sur Mac", 1.0)))
        val e2 = phones.engine(store)
        store.clear("outbox")
        val bob = Identity.create()
        e2.saveContact(Contact(code = bob.code, sign = bob.sign, dh = bob.dh))
        e2.updateContact(bob.code) { it.copy(name = "Bob", saved = true) }
        e2.ensureDirectChat(bob.code)
        val m = assertNotNull(e2.send(bob.code, Content(text = "Salut")))
        e2.toggleStar(bob.code, m.id)
        e2.clearChat(bob.code, keepStarred = true)
        val syncs = store.all("outbox", OutItem).filter { it.to == e2.me.code }.sortedBy { it.seq }
            .mapNotNull { (Wire.parse(it.payload)?.body as? Body.Sync) }
        fun d(op: String) = JSON.write(assertNotNull(syncs.firstOrNull { it.op == op }, op).d.toJson())
        assertEquals("""{"blocked":false,"code":"${bob.code}","dh":"${bob.dh}","name":"Bob","saved":true,"sign":"${bob.sign}"}""", d("contact"))
        assertEquals("""{"id":"${m.id}","on":true}""", d("star"))
        assertEquals("""{"cleared":true,"keep_starred":true}""", d("chat"))
        // Le message lui-même part entier à l'autre appareil (`wire`).
        val wire = assertNotNull(syncs.firstOrNull { it.op == "wire" }?.w)
        assertEquals(m.id, (wire.body as Body.Msg).id)
    }

    /** L'état d'une discussion qui suit sur nos autres appareils : le JSON du navigateur (`mute_until` à 0 : pour toujours). */
    @Test
    fun testChatStateHasTheBrowserShape() {
        val c = Chat(id = "WGSEAJNSZJ").copy(pinned = true, muted = true)
        val state = ChatState(c)
        val json = assertNotNull(JSON.parse(JSON.encode(state)) as? JsonObject)
        assertEquals(0.0, json["mute_until"].asDouble())
        assertEquals(true, json["pinned"].asBool())
        // Les champs dans l'ordre du navigateur.
        assertEquals("""{"marked_unread":false,"pinned":true,"archived":false,"muted":true,"mute_until":0.0,"wallpaper":"","pin_msg":""}""", JSON.encode(state))
        val other = assertNotNull(JSON.decode(ChatState, """{"muted":true,"mute_until":0,"pin_msg":"m1"}""")).apply(Chat(id = "WGSEAJNSZJ").copy(muteUntil = 5.0))
        assertTrue(other.muted)
        assertNull(other.muteUntil)
        assertEquals("m1", other.pinMsg)
    }
}
