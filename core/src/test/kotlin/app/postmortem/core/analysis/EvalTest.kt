package app.postmortem.core.analysis

import app.postmortem.core.board.PieceColor
import app.postmortem.core.uci.Score
import kotlin.test.Test
import kotlin.test.assertEquals

class EvalTest {
    @Test
    fun scoresAreTurnedIntoWhitesPointOfView() {
        assertEquals(Eval.Cp(35), Eval.fromScore(Score.Cp(35), whiteToMove = true))
        assertEquals(Eval.Cp(-35), Eval.fromScore(Score.Cp(35), whiteToMove = false))
        assertEquals(Eval.Mate(3), Eval.fromScore(Score.Mate(3), whiteToMove = true))
        assertEquals(Eval.Mate(-3), Eval.fromScore(Score.Mate(3), whiteToMove = false))
        assertEquals(Eval.Mate(2), Eval.fromScore(Score.Mate(-2), whiteToMove = false))
    }

    @Test
    fun beingMatedNowMeansTheOtherSideWon() {
        assertEquals(Eval.Checkmate(PieceColor.BLACK), Eval.fromScore(Score.Mate(0), whiteToMove = true))
        assertEquals(Eval.Checkmate(PieceColor.WHITE), Eval.fromScore(Score.Mate(0), whiteToMove = false))
    }

    @Test
    fun formatting() {
        assertEquals("+0.35", Eval.Cp(35).format())
        assertEquals("-1.20", Eval.Cp(-120).format())
        assertEquals("0.00", Eval.Cp(0).format())
        assertEquals("+12.04", Eval.Cp(1204).format())
        assertEquals("M3", Eval.Mate(3).format())
        assertEquals("-M1", Eval.Mate(-1).format())
        assertEquals("1-0", Eval.Checkmate(PieceColor.WHITE).format())
        assertEquals("0-1", Eval.Checkmate(PieceColor.BLACK).format())
    }
}
