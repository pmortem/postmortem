package app.postmortem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one way moves are played by hand, on the result screen and in practice. */
class MoveInputTest {
    private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    private val promoting = "4k3/1P6/8/8/8/8/8/4K3 w - - 0 1"

    private var fen: String? = start
    private val played = ArrayList<Triple<String, String, Char?>>()
    private val input = MoveInput({ fen }) { from, to, piece -> played += Triple(from, to, piece) }

    @Test
    fun aTapPicksUpAPieceThatCanMoveAndATapOnATargetPlaysIt() {
        input.tap("e2")
        assertEquals("e2", input.selected)
        assertEquals(listOf("e3", "e4"), input.targets.map { it.square })
        input.tap("e4")
        assertEquals(listOf(Triple<String, String, Char?>("e2", "e4", null)), played)
        assertNull(input.selected)
    }

    @Test
    fun aTapOnThePickedPieceOrAnEmptySquarePutsItDownAndAnotherPieceIsPickedInstead() {
        input.tap("e2")
        input.tap("e2")
        assertNull(input.selected)
        input.tap("e2")
        input.tap("g1")
        assertEquals("g1", input.selected)
        input.tap("e5")
        assertNull(input.selected)
        input.tap("e7")
        assertNull(input.selected, "the other side's piece cannot be picked up")
        assertTrue(played.isEmpty())
    }

    @Test
    fun aDragPlaysOnlyALegalMove() {
        input.drag("e2", "e5")
        input.drag("e2", "e2")
        assertTrue(played.isEmpty())
        input.drag("g1", "f3")
        assertEquals(listOf(Triple<String, String, Char?>("g1", "f3", null)), played)
    }

    @Test
    fun aPawnReachingTheLastRankAsksForItsPieceAndNothingElseIsTakenUntilThen() {
        fen = promoting
        input.drag("b7", "b8")
        assertEquals("b8", input.promotion?.to)
        assertTrue(played.isEmpty())
        input.tap("e1")
        assertNull(input.selected, "no other input while a promotion waits")
        input.choose('n')
        assertEquals(listOf(Triple<String, String, Char?>("b7", "b8", 'n')), played)
        assertNull(input.promotion)
        input.tap("b7")
        input.tap("b8")
        assertTrue(input.cancelPromotion())
        assertFalse(input.cancelPromotion(), "nothing was waiting any more")
        assertEquals(1, played.size)
    }

    @Test
    fun whatWasPickedOnAnotherPositionOrWhenNoMoveCanBeMadeDoesNotCount() {
        input.tap("e2")
        fen = promoting
        assertNull(input.selected)
        assertTrue(input.targets.isEmpty())
        fen = null
        input.tap("b7")
        input.drag("b7", "b8")
        assertNull(input.selected)
        assertNull(input.promotion)
        fen = start
        input.tap("e2")
        input.clear()
        assertNull(input.selected)
    }
}
