package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.pgn.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameReportTest {
    private fun cps(vararg values: Int): List<Eval> = values.map { Eval.Cp(it) }

    @Test
    fun aGameOfBestMovesIsAboutHundredPercentWithNothingFlagged() {
        val report = GameReport.of(cps(20, 20, 20, 20, 20, 20, 20, 20, 20))
        for (player in listOf(report.white, report.black)) {
            assertEquals(100.0, player.accuracy!!, 1e-9)
            assertEquals(0, player.acpl)
            assertEquals(0, player.inaccuracies + player.mistakes + player.blunders)
        }
        assertTrue(report.judgements.all { it == null })
    }

    @Test
    fun oneMoveOfEqualEvalsIsAlmostHundred() {
        assertEquals(100.0, GameReport.of(cps(0, 0)).white.accuracy!!, 1e-9)
    }

    @Test
    fun aKnownBlunderIsCountedForTheRightSide() {
        // White's third move (ply 5) drops from +0.20 to -5.00; nothing else changes.
        val report = GameReport.of(cps(20, 20, 20, 20, 20, -500, -500, -500, -500, -500))
        assertEquals(1, report.white.blunders)
        assertEquals(0, report.white.mistakes + report.white.inaccuracies)
        assertEquals(0, report.black.blunders + report.black.mistakes + report.black.inaccuracies)
        assertEquals(Judgement.BLUNDER, report.judgements[4])
        assertEquals(1, report.judgements.count { it != null })
        assertTrue(report.white.accuracy!! < report.black.accuracy!! - 10)
        assertEquals(104, report.white.acpl)
        assertEquals(0, report.black.acpl)
    }

    @Test
    fun aBlackBlunderIsAFallInBlacksOwnEval() {
        // Ply 2 is Black's: White's eval rises from 0 to +500, which is Black's loss.
        val report = GameReport.of(cps(0, 0, 500, 500))
        assertEquals(1, report.black.blunders)
        assertEquals(0, report.white.blunders)
    }

    @Test
    fun theDropThresholdsMatchLichess() {
        // From equal, a Black move that lets White's eval rise to +0.70 / +1.30 / +2.00 drops Black's winning chances by
        // about 0.13 / 0.22 / 0.35: an inaccuracy (0.1), a mistake (0.2) and a blunder (0.3).
        assertEquals(Judgement.INACCURACY, GameReport.of(cps(0, 0, 70)).judgements[1])
        assertEquals(Judgement.MISTAKE, GameReport.of(cps(0, 0, 130)).judgements[1])
        assertEquals(Judgement.BLUNDER, GameReport.of(cps(0, 0, 200)).judgements[1])
        assertNull(GameReport.of(cps(0, 0, 40)).judgements[1])
    }

    @Test
    fun aPositionThatStaysWonIsNotFlagged() {
        // From +8 to +6 is a real loss in pawns but the win is still clear: Lichess's curve is flat there.
        assertNull(GameReport.of(cps(800, 600)).judgements[0])
    }

    @Test
    fun allowingAMateAgainstYouIsAMistakeOrABlunderDependingOnHowMuchYouWereWinning() {
        // Black (ply 2) from equal allows White to mate in 3.
        assertEquals(Judgement.BLUNDER, GameReport.of(listOf(Eval.Cp(0), Eval.Cp(0), Eval.Mate(3))).judgements[1])
        // Black was lost already (-8.00 for Black) and the mate only confirms it.
        assertEquals(Judgement.MISTAKE, GameReport.of(listOf(Eval.Cp(800), Eval.Cp(800), Eval.Mate(3))).judgements[1])
        assertEquals(Judgement.INACCURACY, GameReport.of(listOf(Eval.Cp(1200), Eval.Cp(1200), Eval.Mate(3))).judgements[1])
    }

    @Test
    fun lettingAMateGoIsJudgedByWhatIsLeft() {
        // White (ply 1) had mate in 3 and now is +1.50: a blunder; if it is still +15, only an inaccuracy.
        assertEquals(Judgement.BLUNDER, GameReport.of(listOf(Eval.Mate(3), Eval.Cp(150))).judgements[0])
        assertEquals(Judgement.INACCURACY, GameReport.of(listOf(Eval.Mate(3), Eval.Cp(1500))).judgements[0])
        // From mate in 3 to being mated is always a blunder.
        assertEquals(Judgement.BLUNDER, GameReport.of(listOf(Eval.Mate(3), Eval.Mate(-2))).judgements[0])
    }

    @Test
    fun keepingAMateIsNotFlagged() {
        assertNull(GameReport.of(listOf(Eval.Mate(3), Eval.Mate(2))).judgements[0])
    }

    @Test
    fun aGameWithoutMovesHasNothingToReport() {
        val report = GameReport.of(cps(0))
        assertNull(report.white.accuracy)
        assertNull(report.black.acpl)
        assertEquals(emptyList(), report.judgements)
        assertEquals(emptyList(), GameReport.of(emptyList()).judgements)
    }

    @Test
    fun aGameThatStartsWithBlackToMoveCreditsTheMovesToTheRightSide() {
        // Ply 1 is Black's: White's eval rises by 500, so Black blundered.
        val report = GameReport.of(cps(0, 500, 500), whiteMovesFirst = false)
        assertEquals(1, report.black.blunders)
        assertEquals(0, report.white.blunders)
    }

    @Test
    fun aRealGameEndingInMateIsReportedForBothSides() {
        // lichess-clocks-evals-mate carries Lichess's own evaluation after every ply (White's point of view) and ends in mate.
        val raw =
            Regex(
                """\[%eval (#?-?\d+(?:\.\d+)?)]""",
            ).findAll(Fixtures.text("lichess-clocks-evals-mate")).map { it.groupValues[1] }.toList()
        val game = Fixtures.game("lichess-clocks-evals-mate")
        val evals =
            listOf<Eval>(Eval.Cp(15)) +
                raw.map { if (it.startsWith("#")) Eval.Mate(it.drop(1).toInt()) else Eval.Cp((it.toDouble() * 100).toInt()) }
        assertEquals(game.plies.size + 1, evals.size)
        val report = GameReport.of(evals)
        assertEquals(game.plies.size, report.judgements.size)
        for (player in listOf(report.white, report.black)) {
            assertTrue(assertNotNull(player.accuracy) in 0.0..100.0)
            assertTrue(player.acpl!! >= 0)
        }
        val flagged = report.judgements.count { it != null }
        assertEquals(flagged, listOf(report.white, report.black).sumOf { it.inaccuracies + it.mistakes + it.blunders })
        // The mating move itself is never flagged.
        assertNull(report.judgements.last())
    }

    @Test
    fun checkmateOnTheBoardIsTheWinnersBestMove() {
        val report = GameReport.of(listOf(Eval.Cp(0), Eval.Cp(0), Eval.Mate(1), Eval.Checkmate(PieceColor.WHITE)))
        assertNull(report.judgements[2])
        // Black allowed the mate in 1 from an equal position.
        assertEquals(Judgement.BLUNDER, report.judgements[1])
    }

    @Test
    fun countsPerJudgementAddUpBothSides() {
        // Ply 2 (Black) loses 0.7 pawns of eval, ply 3 (White) loses 3 pawns: an inaccuracy and a blunder.
        val report = GameReport.of(cps(0, 0, 70, -230))
        assertEquals(1, report.count(Judgement.INACCURACY))
        assertEquals(1, report.count(Judgement.BLUNDER))
        assertEquals(0, report.count(Judgement.MISTAKE))
        assertEquals(report.judgements.count { it != null }, Judgement.entries.sumOf { report.count(it) })
    }

    @Test
    fun aLossIsNotCappedAtAThousandCentipawns() {
        // Lichess clamps the evals to +-1000 and leaves the loss alone: +10.00 to -10.00 loses 2000 cp.
        assertEquals(2000, GameReport.of(cps(1500, -1500)).white.acpl)
    }

    @Test
    fun theFirstMoveIsScoredAgainstLichessStartValueWhenAskedTo() {
        // The engine likes the start position (+0.60); against Lichess's 15 cp the first White move is almost free.
        val engine = GameReport.of(cps(60, 15))
        val lichess = GameReport.of(cps(60, 15), startEval = Eval.Cp(GameReport.LICHESS_START_CP))
        assertEquals(45, engine.white.acpl)
        assertEquals(0, lichess.white.acpl)
    }

    @Test
    fun aMoveThatLosesWinPercentGetsLichessUncertaintyBonus() {
        // 0 cp to -80 cp is 50.00 % to 42.68 %, a 7.32 point drop: 103.1668100711649*exp(-0.04354415386753951*7.32)
        // - 3.166924740191411 + 1 = 72.87 (it would be 71.87 without the bonus).
        assertEquals(72.87, GameReport.of(cps(0, -80)).white.accuracy!!, 0.01)
        // Gaining win percent is a perfect move.
        assertEquals(100.0, GameReport.of(cps(0, 0, -80)).black.accuracy!!, 1e-9)
    }
}
