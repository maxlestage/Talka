// Les règles des appels à plusieurs : les mêmes que le navigateur
// (`client/src/mesh.rs`, ses tests) et l'iPhone (`MeshTests.swift`).

package talka.kit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshTests {
    /** Un signal en route : de qui, pour qui, lequel, avec ceux de l'appel. */
    private data class Signal(val from: String, val to: String, val op: String, val members: List<String>)

    /** Des participants qui s'échangent les signaux, sans WebRTC. */
    private class Net {
        val people = mutableMapOf<String, Mesh>()
        val queue = ArrayDeque<Signal>()

        fun run(who: String, todo: List<Mesh.Do>) {
            val members = people[who]!!.members
            for (d in todo) {
                when (d) {
                    is Mesh.Do.Offer -> queue.addLast(Signal(who, d.peer, if (d.ring) "offer" else "link", members))
                    is Mesh.Do.Answer -> queue.addLast(Signal(who, d.peer, "answer", members))
                    is Mesh.Do.Join -> queue.addLast(Signal(who, d.peer, "join", members))
                    is Mesh.Do.Hangup -> queue.addLast(Signal(who, d.peer, "end", members))
                    is Mesh.Do.Close, Mesh.Do.End -> {}
                }
            }
        }

        fun settle(accepts: Set<String>) {
            var guardCount = 0
            while (queue.isNotEmpty()) {
                guardCount++
                check(guardCount < 10_000) { "les signaux ne s'arrêtent jamais" }
                val (from, to, op, members) = queue.removeFirst()
                val ring = op == "offer"
                var todo = listOf<Mesh.Do>()
                val m = people[to]
                if (m != null) {
                    when (op) {
                        "offer", "link" -> {
                            todo = m.onOffer(from, "sdp", members, ring)
                            if (ring && !m.joined && to in accepts) todo = todo + m.accept()
                        }
                        "answer" -> m.onAnswer(from)
                        "join" -> todo = m.onJoin(from, members)
                        "end" -> todo = m.onGone(from)
                    }
                } else if (ring) {
                    val fresh = Mesh.incoming(to, from, "sdp", members)
                    if (to in accepts) todo = fresh.accept()
                    people[to] = fresh
                }
                run(to, todo)
            }
        }

        fun assertAllLinked(who: List<String>) {
            for (a in who) assertEquals(who.filter { it != a }.sorted(), people[a]!!.open, "$a n'est pas relié à tous")
        }
    }

    @Test
    fun testATwoPersonCallIsAPlainCall() {
        val (a, todo) = Mesh.outgoing("AAAA", listOf("BBBB"))
        assertEquals(listOf<Mesh.Do>(Mesh.Do.Offer("BBBB", ring = true)), todo)
        assertFalse(a.isGroup)
        val b = Mesh.incoming("BBBB", "AAAA", "sdp-a", emptyList())
        assertEquals(listOf<Mesh.Do>(Mesh.Do.Answer("AAAA", "sdp-a")), b.accept())
        assertTrue(a.onAnswer("BBBB"))
        assertEquals(listOf("BBBB"), a.open)
        assertEquals(listOf(Mesh.Do.Close("AAAA"), Mesh.Do.End), b.onGone("AAAA"))
    }

    @Test
    fun testEveryoneWhoAnswersEndsUpLinkedToEveryone() {
        val net = Net()
        val (a, todo) = Mesh.outgoing("AAAA", listOf("BBBB", "CCCC", "DDDD", "EEEE"))
        assertTrue(a.isGroup)
        net.people["AAAA"] = a
        net.run("AAAA", todo)
        net.settle(setOf("BBBB", "CCCC", "DDDD", "EEEE"))
        net.assertAllLinked(listOf("AAAA", "BBBB", "CCCC", "DDDD", "EEEE"))
    }

    @Test
    fun testWhoeverAnswersLateStillJoinsEveryone() {
        val net = Net()
        val (c, todo) = Mesh.outgoing("CCCC", listOf("AAAA", "BBBB", "DDDD"))
        net.people["CCCC"] = c
        net.run("CCCC", todo)
        net.settle(setOf("AAAA", "BBBB"))
        net.assertAllLinked(listOf("CCCC", "AAAA", "BBBB"))
        val d = net.people["DDDD"]!!
        val late = d.accept()
        net.run("DDDD", late)
        net.settle(emptySet())
        net.assertAllLinked(listOf("CCCC", "AAAA", "BBBB", "DDDD"))
    }

    @Test
    fun testSomeoneAddedDuringACallJoinsEveryone() {
        val net = Net()
        val (b, todo) = Mesh.outgoing("BBBB", listOf("DDDD"))
        net.people["BBBB"] = b
        net.run("BBBB", todo)
        net.settle(setOf("DDDD"))
        val d = net.people["DDDD"]!!
        val addA = d.add("AAAA").getOrThrow()
        net.run("DDDD", addA)
        net.settle(setOf("AAAA"))
        val b2 = net.people["BBBB"]!!
        val addC = b2.add("CCCC").getOrThrow()
        net.run("BBBB", addC)
        net.settle(setOf("CCCC"))
        net.assertAllLinked(listOf("AAAA", "BBBB", "CCCC", "DDDD"))
    }

    @Test
    fun testTwoWhoAnswerAtOnceDoNotBothOffer() {
        val b = Mesh.incoming("BBBB", "AAAA", "x", listOf("AAAA", "BBBB", "CCCC"))
        val c = Mesh.incoming("CCCC", "AAAA", "x", listOf("AAAA", "BBBB", "CCCC"))
        assertTrue(b.accept().contains(Mesh.Do.Join("CCCC")))
        assertTrue(c.accept().contains(Mesh.Do.Join("BBBB")))
        assertEquals(listOf<Mesh.Do>(Mesh.Do.Offer("CCCC", ring = false)), b.onJoin("CCCC", emptyList()))
        assertEquals(listOf<Mesh.Do>(Mesh.Do.Join("BBBB")), c.onJoin("BBBB", emptyList()))
        assertEquals(emptyList(), c.onJoin("BBBB", emptyList()))
        assertEquals(emptyList(), b.onJoin("CCCC", emptyList()))
        assertEquals(listOf<Mesh.Do>(Mesh.Do.Answer("BBBB", "sdp-b")), c.onOffer("BBBB", "sdp-b", emptyList(), ring = false))
    }

    @Test
    fun testTwoInvitationsAtOnceKeepTheSmallerOne() {
        val (a, _) = Mesh.outgoing("AAAA", listOf("BBBB"))
        val (b, _) = Mesh.outgoing("BBBB", listOf("AAAA"))
        assertEquals(emptyList(), a.onOffer("BBBB", "sdp-b", emptyList(), ring = true))
        assertEquals(
            listOf(Mesh.Do.Close("AAAA"), Mesh.Do.Answer("AAAA", "sdp-a")),
            b.onOffer("AAAA", "sdp-a", emptyList(), ring = true),
        )
    }

    @Test
    fun testACallHoldsEightPeopleAtMost() {
        val many = (0 until 12).map { "P%03d".format(it) }
        val (a, todo) = Mesh.outgoing("AAAA", many)
        assertEquals(Mesh.maxPeople - 1, todo.size)
        assertEquals(Mesh.maxPeople, a.members.size)
        assertEquals(Result.failure<List<Mesh.Do>>(Mesh.AddError.Full), a.add("ZZZZ"))
        assertEquals(Result.failure<List<Mesh.Do>>(Mesh.AddError.Already), a.add("P000"))
        val b = Mesh.incoming("BBBB", "AAAA", "x", many)
        assertEquals(Mesh.maxPeople, b.members.size)
        assertTrue(b.others.contains("AAAA"))
        val (c, once) = Mesh.outgoing("AAAA", listOf("AAAA", "BBBB", "BBBB"))
        assertEquals(1, once.size)
        assertEquals(listOf("BBBB"), c.others)
    }

    @Test
    fun testACallEndsWhenEveryoneHasLeftOrDeclined() {
        val (a, _) = Mesh.outgoing("AAAA", listOf("BBBB", "CCCC", "DDDD"))
        assertEquals(listOf<Mesh.Do>(Mesh.Do.Close("BBBB")), a.onGone("BBBB"))
        assertTrue(a.onAnswer("CCCC"))
        assertEquals(listOf<Mesh.Do>(Mesh.Do.Hangup("DDDD")), a.noAnswer())
        assertEquals(listOf("CCCC"), a.open)
        assertEquals(listOf(Mesh.Do.Close("CCCC"), Mesh.Do.End), a.onGone("CCCC"))
        val (e, _) = Mesh.outgoing("AAAA", listOf("BBBB"))
        assertEquals(listOf(Mesh.Do.Hangup("BBBB"), Mesh.Do.End), e.noAnswer())
        val (f, _) = Mesh.outgoing("AAAA", listOf("BBBB", "CCCC"))
        assertEquals(listOf(Mesh.Do.Hangup("BBBB"), Mesh.Do.Hangup("CCCC"), Mesh.Do.End), f.hangup())
        // Pas encore dans l'appel : on ne fait sonner personne de plus.
        val g = Mesh.incoming("BBBB", "AAAA", "x", emptyList())
        assertEquals(Result.failure<List<Mesh.Do>>(Mesh.AddError.NotInCall), g.add("CCCC"))
    }

    @Test
    fun testARingingCallIgnoresLinksAndJoins() {
        val b = Mesh.incoming("BBBB", "AAAA", "x", listOf("AAAA", "BBBB", "CCCC"))
        assertEquals(emptyList(), b.onOffer("CCCC", "y", emptyList(), ring = false))
        assertEquals(emptyList(), b.onJoin("CCCC", emptyList()))
        assertNull(b.link("CCCC"))
        assertEquals(emptyList(), b.onOffer("CCCC", "z", emptyList(), ring = true))
        val todo = b.accept()
        assertTrue(todo.contains(Mesh.Do.Answer("AAAA", "x")))
        assertTrue(todo.contains(Mesh.Do.Answer("CCCC", "z")))
        assertEquals(emptyList(), b.accept())
    }

    /**
     * Le signal d'un appel à plusieurs traverse le JSON : ses participants
     * et son groupe ; un appel à deux n'en porte pas.
     */
    @Test
    fun testAGroupCallSignalCarriesItsMembers() {
        val group = Wire(Body.Call("join", "c2", false, "", null, members = listOf("AAAAAAAAAA", "BBBBBBBBBB"), chat = "g:famille"))
        val json = group.json()
        val back = Wire.parse(json)?.body as Body.Call
        assertEquals("join", back.op)
        assertEquals("c2", back.callId)
        assertEquals(listOf("AAAAAAAAAA", "BBBBBBBBBB"), back.members)
        assertEquals("g:famille", back.chat)
        val plain = Wire(Body.Call("end", "c1", false, "", null)).json()
        assertFalse(plain.contains("members"))
        assertFalse(plain.contains("chat"))
    }
}
