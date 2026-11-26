// Les appels à plusieurs : chacun relié à chacun, de pair à pair (WebRTC),
// huit personnes au plus. Ce fichier ne fait que décider : qui fait sonner
// qui, qui propose une liaison à qui, à qui répondre, quand l'appel est
// fini. Le moteur des appels exécute ces décisions avec WebRTC. Les mêmes
// règles que le navigateur (`client/src/mesh.rs`) et l'iPhone (`Mesh.swift`),
// qui y sont expliquées :
//
// - `offer` fait sonner (une invitation, avec `members` à plusieurs) ;
// - `link` propose une liaison sans faire sonner, entre deux personnes
//   déjà dans l'appel ;
// - `join` : « je suis dans l'appel », à ceux qu'on n'a pas encore ; entre
//   deux personnes dans l'appel, le plus petit code propose la liaison,
//   l'autre lui renvoie `join` une fois.

package talka.kit

class Mesh private constructor(val me: String) {
    /** Où en est la liaison avec un participant. */
    sealed class Link {
        /** Nous l'avons fait sonner (`offer`) : en attente de sa réponse. */
        data object Inviting : Link()

        /** Nous lui avons proposé une liaison (`link`) : en attente. */
        data object Offered : Link()

        /**
         * Il nous a fait sonner ou proposé une liaison : son offre, à
         * reprendre pour répondre.
         */
        data class Incoming(val sdp: String) : Link()

        /** Reliés. */
        data object Open : Link()
    }

    /** Ce qu'il faut faire, dans l'ordre. */
    sealed class Do {
        /**
         * Préparer une liaison et lui envoyer une offre (`ring` : la faire
         * sonner, `offer` ; sinon `link`).
         */
        data class Offer(val peer: String, val ring: Boolean) : Do()

        /** Préparer une liaison, y poser son offre et lui répondre. */
        data class Answer(val peer: String, val sdp: String) : Do()

        /** Lui dire que nous sommes dans l'appel. */
        data class Join(val peer: String) : Do()

        /** Lui dire que nous partons (`end`) et fermer la liaison. */
        data class Hangup(val peer: String) : Do()

        /** Fermer la liaison sans rien lui dire. */
        data class Close(val peer: String) : Do()

        /** Plus personne : l'appel est fini. */
        data object End : Do()
    }

    /** Pourquoi on ne peut pas faire sonner quelqu'un de plus. */
    sealed class AddError : Exception() {
        data object NotInCall : AddError()

        data object Already : AddError()

        data object Full : AddError()
    }

    /** Tous ceux de l'appel, invités ou reliés (nous non compris). */
    private val roster = mutableSetOf<String>()
    private val links = mutableMapOf<String, Link>()

    /** Nous sommes dans l'appel : appelant, ou appelé qui a répondu. */
    var joined = false
        private set

    /** Ceux à qui nous avons déjà renvoyé `join`. */
    private val joinedBack = mutableSetOf<String>()

    /** Le compte dans l'appel, s'il y a de la place (vrai s'il y est). */
    private fun admit(peer: String): Boolean {
        if (peer.isEmpty() || peer == me) return false
        if (peer in roster) return true
        if (roster.size + 1 >= maxPeople) return false
        roster.add(peer)
        return true
    }

    private fun absorb(members: List<String>) {
        // Dans l'ordre, comme le navigateur (une liste triée).
        for (m in members.sorted()) admit(m)
    }

    /** Ceux de l'appel, nous compris (triés) : ce qu'une invitation annonce. */
    val members: List<String> get() = (roster + me).sorted()

    /** Les autres participants, invités ou reliés (triés). */
    val others: List<String> get() = roster.sorted()

    /** Un appel à plusieurs (plus d'un autre participant). */
    val isGroup: Boolean get() = roster.size > 1

    fun link(peer: String): Link? = links[peer]

    /** Ceux avec qui nous sommes reliés (triés). */
    val open: List<String> get() = links.filterValues { it == Link.Open }.keys.sorted()

    /** Une offre de `from` pour cet appel : une invitation (`ring`) ou une liaison. */
    fun onOffer(from: String, sdp: String, members: List<String>, ring: Boolean): List<Do> {
        if (!joined) {
            if (ring && admit(from) && links[from] != Link.Open) {
                links[from] = Link.Incoming(sdp)
                absorb(members)
            }
            return emptyList()
        }
        if (!admit(from)) return emptyList()
        absorb(members)
        return when (links[from]) {
            Link.Open -> emptyList()
            Link.Inviting, Link.Offered -> {
                // Nous lui avons aussi proposé : l'offre du plus petit code l'emporte.
                if (me < from) return emptyList()
                links[from] = Link.Open
                listOf(Do.Close(from), Do.Answer(from, sdp))
            }
            else -> {
                links[from] = Link.Open
                listOf(Do.Answer(from, sdp))
            }
        }
    }

    /**
     * Nous répondons : une réponse à chaque offre reçue, puis `join` à ceux
     * de l'appel que nous n'avons pas encore.
     */
    fun accept(): List<Do> {
        if (joined) return emptyList()
        joined = true
        val todo = mutableListOf<Do>()
        for (peer in links.keys.sorted()) {
            val l = links[peer]
            if (l is Link.Incoming) {
                todo.add(Do.Answer(peer, l.sdp))
                links[peer] = Link.Open
            }
        }
        for (peer in roster.sorted()) if (links[peer] == null) todo.add(Do.Join(peer))
        return todo
    }

    /** `from` est dans l'appel et ne nous a pas encore. */
    fun onJoin(from: String, members: List<String>): List<Do> {
        if (!admit(from)) return emptyList()
        absorb(members)
        if (!joined || links[from] != null) return emptyList()
        if (me < from) {
            links[from] = Link.Offered
            return listOf(Do.Offer(from, ring = false))
        }
        if (joinedBack.add(from)) return listOf(Do.Join(from))
        return emptyList()
    }

    /** Sa réponse à notre offre : vrai si nous l'attendions. */
    fun onAnswer(from: String): Boolean =
        when (links[from]) {
            Link.Inviting, Link.Offered -> {
                links[from] = Link.Open
                true
            }
            else -> false
        }

    /** `from` s'en va, ne vient pas, ou sa liaison a échoué. */
    fun onGone(from: String): List<Do> {
        val todo = mutableListOf<Do>()
        if (links.remove(from) != null) todo.add(Do.Close(from))
        roster.remove(from)
        joinedBack.remove(from)
        if (links.isEmpty()) todo.add(Do.End)
        return todo
    }

    /** Personne n'a répondu à temps : les invitations en attente s'arrêtent. */
    fun noAnswer(): List<Do> {
        val todo = mutableListOf<Do>()
        for (peer in links.keys.sorted()) {
            if (links[peer] == Link.Inviting) {
                links.remove(peer)
                roster.remove(peer)
                todo.add(Do.Hangup(peer))
            }
        }
        if (links.isEmpty()) todo.add(Do.End)
        return todo
    }

    /** Nous raccrochons (ou refusons) : au revoir à chacun. */
    fun hangup(): List<Do> {
        val todo = links.keys.sorted().map<String, Do> { Do.Hangup(it) } + Do.End
        links.clear()
        return todo
    }

    /** Faire sonner quelqu'un de plus, pendant l'appel. */
    fun add(peer: String): Result<List<Do>> {
        if (!joined) return Result.failure(AddError.NotInCall)
        if (peer == me || links[peer] != null) return Result.failure(AddError.Already)
        if (!admit(peer)) return Result.failure(AddError.Full)
        links[peer] = Link.Inviting
        return Result.success(listOf(Do.Offer(peer, ring = true)))
    }

    companion object {
        /** Huit personnes au plus dans un appel (nous compris). */
        const val maxPeople = 8

        /** Nous appelons `invitees` (un seul : un appel à deux). */
        fun outgoing(me: String, invitees: List<String>): Pair<Mesh, List<Do>> {
            val mesh = Mesh(me)
            mesh.joined = true
            val todo = mutableListOf<Do>()
            for (peer in invitees) {
                if (mesh.admit(peer) && mesh.links[peer] == null) {
                    mesh.links[peer] = Link.Inviting
                    todo.add(Do.Offer(peer, ring = true))
                }
            }
            return mesh to todo
        }

        /**
         * `from` nous fait sonner, avec son offre et ceux qu'il appelle avec
         * nous (vide : un appel à deux).
         */
        fun incoming(me: String, from: String, sdp: String, members: List<String>): Mesh {
            val mesh = Mesh(me)
            mesh.admit(from)
            mesh.links[from] = Link.Incoming(sdp)
            mesh.absorb(members)
            return mesh
        }
    }
}
