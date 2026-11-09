package app.postmortem.core.report

import app.postmortem.core.board.PieceColor
import app.postmortem.core.store.ReportRow

/** How a game went for one player. [UNFINISHED] is a missing or `*` result: it counts as a game but never in the percentages. */
enum class Outcome { WIN, DRAW, LOSS, UNFINISHED }

/** A name found in the White and Black tags of the saved games, and in how many games it plays. [name] is the spelling used most. */
data class PlayerName(
    val name: String,
    val games: Int,
)

/** One saved game seen from one player's side. [accuracy] is the player's own colour's; [opening] and [date] are null when unknown. */
data class PlayerGame(
    val gameId: Long,
    val color: PieceColor,
    val opponent: String?,
    val outcome: Outcome,
    val opening: String?,
    val accuracy: Double?,
    val date: String?,
) {
    companion object {
        /** The newest game first (by the date tag, then the most recently saved), the order every list of the Report uses. */
        val newestFirst: Comparator<PlayerGame> = compareByDescending<PlayerGame> { it.date.orEmpty() }.thenByDescending { it.gameId }
    }
}

/** Finding a player in the saved games, and the games they played. Names match without regard to case, with spaces trimmed. */
object PlayerGames {
    private fun key(name: String): String = name.trim().lowercase()

    /** Every name in the White and Black tags, the one with most games first; a game with no name (`?`, empty) adds nothing. */
    fun names(rows: List<ReportRow>): List<PlayerName> {
        val spellings = HashMap<String, MutableMap<String, Int>>()
        for (row in rows) {
            val overview = row.overview
            for (name in listOfNotNull(overview.white, overview.black)) {
                spellings.getOrPut(key(name)) { LinkedHashMap() }.merge(name.trim(), 1, Int::plus)
            }
        }
        return spellings.values
            .map { counts -> PlayerName(counts.maxByOrNull { it.value }!!.key, counts.values.sum()) }
            .sortedWith(compareByDescending<PlayerName> { it.games }.thenBy { it.name.lowercase() })
    }

    /** The games of [name] in [rows], with the colour they had, in the order of [rows]. A game where they play both sides counts twice. */
    fun of(
        rows: List<ReportRow>,
        name: String,
    ): List<PlayerGame> {
        val wanted = key(name)
        if (wanted.isEmpty()) return emptyList()
        return rows.flatMap { row ->
            val overview = row.overview
            listOf(
                PieceColor.WHITE to overview.white,
                PieceColor.BLACK to overview.black,
            ).filter { (_, player) -> player != null && key(player) == wanted }
                .map { (color, _) ->
                    PlayerGame(
                        gameId = row.id,
                        color = color,
                        opponent = if (color == PieceColor.WHITE) overview.black else overview.white,
                        outcome = outcome(overview.result, color),
                        opening = row.opening?.trim()?.takeIf { it.isNotEmpty() },
                        accuracy = if (color == PieceColor.WHITE) row.whiteAccuracy else row.blackAccuracy,
                        date = overview.date,
                    )
                }
        }
    }

    /** The result tag seen from [color]: `1-0`, `0-1` and `1/2-1/2` (or `½-½`); anything else is not finished. */
    fun outcome(
        result: String?,
        color: PieceColor,
    ): Outcome =
        when (result?.trim()) {
            "1-0" -> if (color == PieceColor.WHITE) Outcome.WIN else Outcome.LOSS
            "0-1" -> if (color == PieceColor.BLACK) Outcome.WIN else Outcome.LOSS
            "1/2-1/2", "½-½" -> Outcome.DRAW
            else -> Outcome.UNFINISHED
        }
}
