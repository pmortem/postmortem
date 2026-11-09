package app.postmortem.core.report

import app.postmortem.core.board.PieceColor

/** Games and results of a set of games. The percentages are of the finished games, so a `*` game counts in [games] only. */
data class Record(
    val games: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    /** The mean accuracy of the games that have one, or null when none does. */
    val accuracy: Double?,
) {
    val decided: Int get() = wins + draws + losses
    val winPercent: Double? get() = percent(wins)
    val drawPercent: Double? get() = percent(draws)
    val lossPercent: Double? get() = percent(losses)

    private fun percent(count: Int): Double? = if (decided == 0) null else 100.0 * count / decided

    companion object {
        fun of(games: List<PlayerGame>): Record =
            Record(
                games = games.size,
                wins = games.count { it.outcome == Outcome.WIN },
                draws = games.count { it.outcome == Outcome.DRAW },
                losses = games.count { it.outcome == Outcome.LOSS },
                accuracy = games.mapNotNull { it.accuracy }.takeIf { it.isNotEmpty() }?.average(),
            )
    }
}

/** One opening by its full name, with the games, newest first. A null [name] is the games with no detected opening. */
data class OpeningVariation(
    val name: String?,
    val record: Record,
    val games: List<PlayerGame>,
)

/** The openings that share a name before the first `:` (the family, such as `Sicilian Defense`), with their variations. A null [name] is "unknown". */
data class OpeningFamily(
    val name: String?,
    val record: Record,
    val variations: List<OpeningVariation>,
)

/** The player's openings as White and as Black, most played first (equal counts by name, the unknown ones last). */
data class OpeningReport(
    val white: List<OpeningFamily>,
    val black: List<OpeningFamily>,
)

/** The grouping behind the Report's openings: by colour, then family, then variation. */
object OpeningStats {
    private const val FAMILY_SEPARATOR = ':'

    /** The family of an opening name: what comes before the first `:`, or the whole name. */
    fun family(opening: String): String = opening.substringBefore(FAMILY_SEPARATOR).trim()

    fun of(games: List<PlayerGame>): OpeningReport =
        OpeningReport(
            white = families(games.filter { it.color == PieceColor.WHITE }),
            black = families(games.filter { it.color == PieceColor.BLACK }),
        )

    private fun families(games: List<PlayerGame>): List<OpeningFamily> =
        games
            .groupBy { game -> game.opening?.let(::family) }
            .map { (name, inFamily) ->
                OpeningFamily(
                    name,
                    Record.of(inFamily),
                    inFamily
                        .groupBy { it.opening }
                        .map { (full, inVariation) ->
                            OpeningVariation(full, Record.of(inVariation), inVariation.sortedWith(PlayerGame.newestFirst))
                        }.sortedWith(
                            compareByDescending<OpeningVariation> {
                                it.record.games
                            }.thenBy { it.name == null }.thenBy { it.name.orEmpty() },
                        ),
                )
            }.sortedWith(compareByDescending<OpeningFamily> { it.record.games }.thenBy { it.name == null }.thenBy { it.name.orEmpty() })
}
