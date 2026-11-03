package app.postmortem.ui

import app.postmortem.core.analysis.LiveLine
import app.postmortem.core.board.BoardArrow
import app.postmortem.core.uci.Score
import app.postmortem.ui.theme.ArrowStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardArrowsTest {
    private fun line(
        rank: Int,
        move: String,
        score: Score = Score.Cp(0),
    ) = LiveLine(rank, move, score, 10, listOf(move))

    @Test
    fun theBestArrowIsTheThickestMostOpaqueWithTheBiggestLabelAndTheOthersGetLess() {
        val ranks = listOf(1, 2, 3)
        assertTrue(ranks.map(ArrowStyle::width).zipWithNext().all { (a, b) -> a > b })
        assertTrue(ranks.map(ArrowStyle::alpha).zipWithNext().all { (a, b) -> a > b })
        assertTrue(ranks.map(ArrowStyle::labelScale).zipWithNext().all { (a, b) -> a > b })
        assertEquals(1f, ArrowStyle.width(1))
        assertEquals(ArrowStyle.width(3), ArrowStyle.width(9), "a rank beyond the list gets what the last gets")
    }

    @Test
    fun theArrowsAreWhereTheBoardIsDrawnAndCarryTheEvalFromTheSideToMove() {
        val lines = listOf(line(1, "e2e4", Score.Cp(80)), line(2, "g1f3", Score.Cp(-130)), line(3, "h5f7", Score.Mate(1)))
        val white = arrowsOf(lines, flipped = false)
        assertEquals(BoardArrow(6, 4, 4, 4), white[0].arrow)
        assertEquals(listOf("+0.8", "−1.3", "#1"), white.map { it.label })
        assertEquals(listOf(1, 2, 3), white.map { it.rank })
        assertEquals(BoardArrow(1, 3, 3, 3), arrowsOf(lines, flipped = true)[0].arrow)
    }

    @Test
    fun fewerLinesGiveFewerArrows() {
        assertEquals(1, arrowsOf(listOf(line(1, "e2e4")), flipped = false).size)
        assertEquals(0, arrowsOf(emptyList(), flipped = false).size)
    }

    @Test
    fun labelsOfArrowsEndingOnTheSameSquareGetDifferentSlots() {
        val arrows = arrowsOf(listOf(line(1, "g1f3"), line(2, "e2f3"), line(3, "d2d4"), line(4, "b1f3")), flipped = false)
        assertEquals(listOf(0, 1, 0, 2), labelSlots(arrows))
    }
}
