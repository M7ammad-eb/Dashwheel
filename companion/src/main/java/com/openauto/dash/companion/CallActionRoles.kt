package com.openauto.dash.companion

/**
 * Which of a call notification's actions answer, decline and hang up, from
 * their titles (in the languages this app speaks), for an app not using
 * CallStyle. Pure, so it is unit-tested.
 */
internal object CallActionRoles {
    data class Roles(val answer: Int? = null, val decline: Int? = null, val hangUp: Int? = null) {
        val isEmpty: Boolean get() = answer == null && decline == null && hangUp == null
        /** Ringing: something answers or declines it. */
        val incoming: Boolean get() = answer != null || decline != null
    }

    private val ANSWER = setOf(
        "answer", "accept", "pick up",
        "répondre", "accepter", "décrocher",
        "annehmen", "antworten", "abheben",
        "responder", "aceptar", "contestar", "atender", "aceitar",
        "rispondi", "accetta",
        "opnemen", "beantwoorden", "accepteren", "aannemen",
        "odbierz", "odebrać", "przyjmij"
    )
    private val DECLINE = setOf(
        "decline", "reject", "ignore",
        "refuser", "rejeter", "ignorer",
        "ablehnen", "abweisen",
        "rechazar", "declinar", "recusar", "rejeitar",
        "rifiuta", "declina",
        "weigeren", "afwijzen",
        "odrzuć", "odrzucić"
    )
    private val HANG_UP = setOf(
        "hang up", "end call", "end", "leave",
        "raccrocher", "terminer",
        "auflegen", "beenden",
        "colgar", "finalizar", "desligar", "encerrar",
        "riaggancia", "termina",
        "ophangen", "beëindigen",
        "rozłącz", "zakończ"
    )

    /** What separates the words of a title: anything but letters, in any script. */
    private val NOT_LETTERS = Regex("[^\\p{L}]+")

    /** The roles of the actions titled [titles], in order. */
    fun of(titles: List<String>): Roles {
        val named = titles.map { it.trim().lowercase() }
        val answer = named.indexOfFirst { matches(it, ANSWER) }.takeIf { it >= 0 }
        val decline = named.indexOfFirst { matches(it, DECLINE) }.takeIf { it >= 0 }
        val hangUp = named.indexOfFirst { matches(it, HANG_UP) }.takeIf { it >= 0 }
        return when {
            answer != null || decline != null -> {
                // One of the two named and one other action: that one is the other.
                val other = named.indices.filter { it != answer && it != decline }.singleOrNull()
                Roles(answer = answer ?: other, decline = decline ?: other)
            }
            hangUp != null -> Roles(hangUp = hangUp)
            // Unnamed: two actions are decline then answer, as Android lays them out; one is hang up.
            named.size == 2 -> Roles(answer = 1, decline = 0)
            named.size == 1 -> Roles(hangUp = 0)
            else -> Roles()
        }
    }

    private fun matches(title: String, words: Set<String>): Boolean {
        if (title.isEmpty()) return false
        val parts = title.split(NOT_LETTERS).filter { it.isNotEmpty() }
        return title in words || parts.any { it in words } || words.any { ' ' in it && it in title }
    }
}
