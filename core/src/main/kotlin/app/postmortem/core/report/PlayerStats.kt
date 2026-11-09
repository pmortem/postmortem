package app.postmortem.core.report

import app.postmortem.core.analysis.Judgement
import app.postmortem.core.board.PieceColor
import app.postmortem.core.board.moverAt
import app.postmortem.core.opening.OpeningBook
import app.postmortem.core.store.MainLine
import java.time.LocalDate

/** Wins, draws and losses of a set of games; an unfinished game is in none of them. */
data class Wdl(
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
) {
    val decided: Int get() = wins + draws + losses

    /** The score in percent: a win is 1, a draw one half; null when no game was decided. */
    val scorePercent: Double? get() = if (decided == 0) null else 100.0 * (wins + draws / 2.0) / decided

    companion object {
        fun of(games: List<PlayerGame>) =
            Wdl(
                wins = games.count { it.outcome == Outcome.WIN },
                draws = games.count { it.outcome == Outcome.DRAW },
                losses = games.count { it.outcome == Outcome.LOSS },
            )
    }
}

/** The headline numbers over every game. The per-game averages cover the games with stored evals ([analysed]). */
data class Totals(
    val games: Int,
    val analysed: Int,
    val white: Wdl,
    val black: Wdl,
    val both: Wdl,
    val accuracy: Double?,
    val acpl: Double?,
    val inaccuraciesPerGame: Double?,
    val mistakesPerGame: Double?,
    val blundersPerGame: Double?,
) {
    val scorePercent: Double? get() = both.scorePercent
}

/** One game in the trend charts. [rolling] is the mean accuracy of this game and the ones before it, up to ten. */
data class SeriesPoint(
    val gameId: Long,
    val day: LocalDate,
    val accuracy: Double?,
    val rolling: Double?,
    val elo: Int?,
    val opponentElo: Int?,
    val outcome: Outcome,
)

/** Games, score and mean accuracy of one group of games (a time control, an opponent strength). */
data class Group<K>(
    val key: K,
    val games: Int,
    val scorePercent: Double?,
    val accuracy: Double?,
)

/** How strong the opponent was against the player, by Elo: [UNKNOWN] when either Elo is missing. */
enum class Strength { STRONGER, EQUAL, WEAKER, UNKNOWN }

/** The player's moves in one phase: how many, the mean centipawn loss, and how many were inaccuracies, mistakes and blunders. */
data class PhaseStats(
    val moves: Int,
    val acpl: Double?,
    val inaccuracies: Int,
    val mistakes: Int,
    val blunders: Int,
)

/** The player's inaccuracies, mistakes and blunders in one game. */
data class GameJudgements(
    val gameId: Long,
    val day: LocalDate,
    val inaccuracies: Int,
    val mistakes: Int,
    val blunders: Int,
)

/** Every number the graphical Report shows, worked out in one pass over the player's games. See [PlayerStats.of]. */
data class PlayerStats(
    val totals: Totals,
    val series: List<SeriesPoint>,
    val byTimeControl: List<Group<Speed?>>,
    val byStrength: List<Group<Strength>>,
    val byPhase: Map<Phase, PhaseStats>,
    val perGame: List<GameJudgements>,
    val endings: Map<Outcome, Map<Ending, Int>>,
) {
    companion object {
        /** The Elo difference (opponent minus player) from which an opponent counts as stronger, or (negated) weaker. */
        const val STRENGTH_ELO = 100

        /** The rolling accuracy averages this many games, this one included. */
        const val ROLLING_GAMES = 10

        /**
         * The statistics of [games] (the ones the Report's filters let through) and their stored main lines [lines]. [book] decides how
         * long a game's opening lasts. A game without a stored main line still counts in the totals, the groups and the series (with no
         * accuracy beyond the one saved with the game), but not in the numbers that need its evals: the phases, the per-game
         * judgements, ACPL, how it ended. The accuracy and the judgements are the ones of the game's own Summary tab.
         */
        fun of(
            games: List<PlayerGame>,
            lines: Map<Long, MainLine>,
            book: OpeningBook = OpeningBook.shared,
        ): PlayerStats {
            val ordered = games.sortedWith(compareBy<PlayerGame> { it.day }.thenBy { it.gameId })
            val withLines = ordered.filter { lines[it.gameId] != null }
            val reports = withLines.associate { it.gameId to playerReport(it, lines.getValue(it.gameId)) }
            return PlayerStats(
                totals = totals(games, withLines, reports),
                series = series(ordered),
                byTimeControl =
                    (Speed.entries.map<Speed, Speed?> { it } + null).map { speed ->
                        group(
                            speed,
                            ordered.filter { g ->
                                g.speed ==
                                    speed
                            },
                        )
                    },
                byStrength = Strength.entries.map { strength -> group(strength, ordered.filter { strengthOf(it) == strength }) },
                byPhase = phases(withLines, lines, book),
                perGame =
                    withLines.map { game ->
                        val r = reports.getValue(game.gameId)
                        GameJudgements(game.gameId, game.day, r.inaccuracies, r.mistakes, r.blunders)
                    },
                endings = endings(withLines, lines),
            )
        }

        private class PlayerLine(
            val acpl: Int?,
            val inaccuracies: Int,
            val mistakes: Int,
            val blunders: Int,
        )

        private fun playerReport(
            game: PlayerGame,
            line: MainLine,
        ): PlayerLine {
            val report = line.report
            val side = if (game.color == PieceColor.WHITE) report.white else report.black
            return PlayerLine(side.acpl, side.inaccuracies, side.mistakes, side.blunders)
        }

        private fun totals(
            games: List<PlayerGame>,
            withLines: List<PlayerGame>,
            reports: Map<Long, PlayerLine>,
        ): Totals {
            fun mean(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()
            val counted = reports.values
            return Totals(
                games = games.size,
                analysed = withLines.size,
                white = Wdl.of(games.filter { it.color == PieceColor.WHITE }),
                black = Wdl.of(games.filter { it.color == PieceColor.BLACK }),
                both = Wdl.of(games),
                accuracy = mean(games.mapNotNull { it.accuracy }),
                acpl = mean(counted.mapNotNull { it.acpl?.toDouble() }),
                inaccuraciesPerGame = mean(counted.map { it.inaccuracies.toDouble() }),
                mistakesPerGame = mean(counted.map { it.mistakes.toDouble() }),
                blundersPerGame = mean(counted.map { it.blunders.toDouble() }),
            )
        }

        private fun series(ordered: List<PlayerGame>): List<SeriesPoint> =
            ordered.mapIndexed { index, game ->
                val recent = ordered.subList(maxOf(0, index + 1 - ROLLING_GAMES), index + 1).mapNotNull { it.accuracy }
                val rolling = recent.takeIf { it.isNotEmpty() }?.average()
                SeriesPoint(game.gameId, game.day, game.accuracy, rolling, game.elo, game.opponentElo, game.outcome)
            }

        private fun <K> group(
            key: K,
            games: List<PlayerGame>,
        ) = Group(key, games.size, Wdl.of(games).scorePercent, games.mapNotNull { it.accuracy }.takeIf { it.isNotEmpty() }?.average())

        /** The opponent's strength against the player: [Strength.UNKNOWN] when either Elo is missing. */
        fun strengthOf(game: PlayerGame): Strength {
            val own = game.elo ?: return Strength.UNKNOWN
            val other = game.opponentElo ?: return Strength.UNKNOWN
            return when {
                other - own >= STRENGTH_ELO -> Strength.STRONGER
                other - own <= -STRENGTH_ELO -> Strength.WEAKER
                else -> Strength.EQUAL
            }
        }

        private fun phases(
            games: List<PlayerGame>,
            lines: Map<Long, MainLine>,
            book: OpeningBook,
        ): Map<Phase, PhaseStats> {
            class Tally {
                var moves = 0
                var loss = 0L
                var inaccuracies = 0
                var mistakes = 0
                var blunders = 0
            }
            val tallies = Phase.entries.associateWith { Tally() }
            for (game in games) {
                val line = lines.getValue(game.gameId)
                if (line.fens.size != line.evals.size) continue
                val depth = book.depthOf(line.fens)
                val report = line.report
                for (ply in 1 until line.evals.size) {
                    if (moverAt(ply, line.whiteMovesFirst) != game.color) continue
                    val tally = tallies.getValue(GamePhases.of(ply, line.fens[ply - 1], depth))
                    tally.moves++
                    tally.loss += report.losses[ply - 1]
                    when (report.judgements[ply - 1]) {
                        Judgement.INACCURACY -> tally.inaccuracies++
                        Judgement.MISTAKE -> tally.mistakes++
                        Judgement.BLUNDER -> tally.blunders++
                        null -> Unit
                    }
                }
            }
            return tallies.mapValues { (_, t) ->
                val acpl = if (t.moves == 0) null else t.loss.toDouble() / t.moves
                PhaseStats(t.moves, acpl, t.inaccuracies, t.mistakes, t.blunders)
            }
        }

        private fun endings(
            games: List<PlayerGame>,
            lines: Map<Long, MainLine>,
        ): Map<Outcome, Map<Ending, Int>> {
            val counts = listOf(Outcome.WIN, Outcome.LOSS, Outcome.DRAW).associateWith { HashMap<Ending, Int>() }
            for (game in games) {
                val tally = counts[game.outcome] ?: continue
                val line = lines.getValue(game.gameId)
                val ending = GamePhases.ending(game.termination, game.outcome, line.sans.lastOrNull(), line.fens)
                tally.merge(ending, 1, Int::plus)
            }
            return counts.mapValues { (_, tally) -> Ending.entries.filter { it in tally }.associateWith { tally.getValue(it) } }
        }
    }
}
