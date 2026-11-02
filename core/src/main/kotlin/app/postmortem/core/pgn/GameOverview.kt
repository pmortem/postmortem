package app.postmortem.core.pgn

/**
 * What the PGN tags say about a game. A tag that is missing, or holds a placeholder (`?`, `????.??.??`, `-`), is null, so
 * the screen can leave its line out instead of showing "?".
 */
data class GameOverview(
    val white: String?,
    val whiteElo: String?,
    val black: String?,
    val blackElo: String?,
    val date: String?,
    val event: String?,
    val site: String?,
    val result: String?,
    val termination: String?,
    val opening: String?,
    val eco: String?,
) {
    /** True when no tag gave anything to show. */
    val isEmpty: Boolean
        get() = listOf(white, whiteElo, black, blackElo, date, event, site, result, termination, opening, eco).all { it == null }

    companion object {
        private val placeholders = setOf("?", "-", "*")

        fun of(headers: Map<String, String>): GameOverview {
            fun tag(name: String): String? = headers[name]?.trim()?.takeIf { it.isNotEmpty() && it !in placeholders }
            return GameOverview(
                white = tag("White"),
                whiteElo = tag("WhiteElo"),
                black = tag("Black"),
                blackElo = tag("BlackElo"),
                date = tag("Date")?.let(::knownDate),
                event = tag("Event"),
                site = tag("Site"),
                result = tag("Result"),
                termination = tag("Termination"),
                opening = tag("Opening"),
                eco = tag("ECO"),
            )
        }

        /** `2024.03.??` is shown as `2024.03`, and a date with no known part as nothing. */
        private fun knownDate(date: String): String? =
            date
                .split('.')
                .takeWhile { '?' !in it }
                .joinToString(".")
                .takeIf { it.isNotEmpty() }
    }
}

/** The tags of [Game], read as an overview. */
val Game.overview: GameOverview get() = GameOverview.of(headers)
