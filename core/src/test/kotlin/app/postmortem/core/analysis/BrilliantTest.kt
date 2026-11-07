package app.postmortem.core.analysis

import app.postmortem.core.board.Moves
import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.Game
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BrilliantTest {
    private val game: Game = Fixtures.game("byrne-fischer-1956")

    /** What Stockfish (1,000,000 nodes, as the app runs it) said about every position of the game. */
    private val evals: List<Eval> =
        checkNotNull(
            BrilliantTest::class.java.getResource("/evals/byrne-fischer-1956.txt"),
        ).readText().lines().filter { it.isNotBlank() }.map {
            val (kind, value) = it.split(" ")
            when (kind) {
                "cp" -> Eval.Cp(value.toInt())
                "mate" -> Eval.Mate(value.toInt())
                else -> Eval.Checkmate(PieceColor.valueOf(value))
            }
        }

    private fun moves(game: Game) = game.plies.mapIndexed { index, ply -> PlayedMove(game.fenAt(index), ply.uci, ply.fen) }

    private fun report(evals: List<Eval> = this.evals) = GameReport.of(evals, moves = moves(game))

    @Test
    fun theQueenSacrificeBe6IsBrilliantAndSoIsTheHangingKnightNa4() {
        val report = report()
        assertEquals("Be6", game.plies[33].san)
        assertTrue(report.brilliant[33])
        // what the rule says about the rest of the game at 1M nodes: 11...Na4 leaves a knight en prise, soundly, and nothing else does
        assertEquals("Na4", game.plies[21].san)
        assertEquals(listOf(21, 33), report.brilliant.indices.filter { report.brilliant[it] })
        assertEquals(2, report.black.brilliants)
        assertEquals(0, report.white.brilliants)
    }

    @Test
    fun withoutTheMovesNothingIsBrilliant() {
        val report = GameReport.of(evals)
        assertTrue(report.brilliant.none { it })
        assertEquals(0, report.black.brilliants)
    }

    @Test
    fun aMoveThatHasAJudgementIsNeverBrilliant() {
        // the same move, but after it White is suddenly much better: it is a blunder, not a brilliancy
        val worse = evals.toMutableList().also { it[34] = Eval.Cp(250) }
        val report = report(worse)
        assertEquals(Judgement.BLUNDER, report.judgements[33])
        assertFalse(report.brilliant[33])
    }

    @Test
    fun aSacrificeFromAnAlreadyWonPositionIsNotBrilliant() {
        val won =
            evals.toMutableList().also {
                it[33] = Eval.Cp(-1500)
                it[34] = Eval.Cp(-1500)
            }
        assertFalse(report(won).brilliant[33])
    }

    @Test
    fun aSacrificeThatThrowsAwayTheAdvantageIsNotBrilliant() {
        // before: Black clearly better; after: only equal. Not "as good as the best move"
        val unsound = evals.toMutableList().also { it[34] = Eval.Cp(0) }
        assertFalse(report(unsound).brilliant[33])
    }

    @Test
    fun aRecaptureIsNeverBrilliantWhateverTheEvalsSay() {
        // with every eval equal every move is "sound": the rule must still not flag a capture on the square just taken on
        var recaptures = 0
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val fixture = Fixtures.game(name)
            val flat = GameReport.of(List(fixture.positionCount) { Eval.Cp(0) }, fixture.whiteMovesFirst, moves = moves(fixture))
            for (index in 1 until fixture.plies.size) {
                val now = fixture.plies[index]
                val before = fixture.plies[index - 1]
                if ('x' in now.san && 'x' in before.san && now.uci.substring(2, 4) == before.uci.substring(2, 4)) {
                    recaptures++
                    assertFalse(flat.brilliant[index], "$name: recapture ${now.san} flagged")
                }
            }
        }
        assertTrue(recaptures >= 5, "the fixtures have recaptures to test on, found $recaptures")
    }

    @Test
    fun aSacrificeFromALostPositionIsNotBrilliant() {
        val lost =
            evals.toMutableList().also {
                it[33] = Eval.Cp(900)
                it[34] = Eval.Cp(900)
            }
        assertFalse(report(lost).brilliant[33])
    }

    private fun rookMove(
        fen: String,
        to: String,
    ): GameReport {
        val ply = assertNotNull(Moves.play(fen, "f8", to))
        return GameReport.of(listOf(Eval.Cp(0), Eval.Cp(0)), whiteMovesFirst = false, moves = listOf(PlayedMove(fen, ply.uci, ply.fen)))
    }

    @Test
    fun aRookLeftToBeTakenForNothingIsBrilliantWhenTheEvalsStaySound() {
        assertEquals(listOf(true), rookMove("5r1k/7p/8/8/4Q3/8/8/4K3 b - - 0 1", "f4").brilliant)
        // a quiet rook move leaves nothing hanging
        assertEquals(listOf(false), rookMove("5r1k/7p/8/8/4Q3/8/8/4K3 b - - 0 1", "g8").brilliant)
    }

    @Test
    fun aMoveThatAnswersACheckIsNotBrilliantEvenWhenItLeavesAPieceHanging() {
        // Rf6 blocks the check from the queen on e5 and can then be taken for nothing; Kg8 was another way out
        val fen = "5r1k/7p/8/4Q3/8/8/8/4K3 b - - 0 1"
        assertTrue(Moves.inCheck(fen))
        assertEquals(listOf(false), rookMove(fen, "f6").brilliant)
    }

    @Test
    fun aNormalGameWithLichessEvalsHasNoBrilliantMoves() {
        val annotated = LichessAnnotated(Fixtures.text("lichess-64-annotated"))
        val club = Fixtures.game("lichess-64-annotated")
        val report = GameReport.of(annotated.evals(), club.whiteMovesFirst, moves = moves(club))
        assertEquals(
            emptyList(),
            report.brilliant.indices
                .filter { report.brilliant[it] }
                .map { club.plies[it].san },
        )
    }
}
