package app.postmortem.core.board

import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.STANDARD_START_FEN
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovesTest {
    private fun squares(
        fen: String,
        from: String,
    ) = Moves.legalTargets(fen, from).map { it.square }

    @Test
    fun everyMoveOfEveryFixtureGameIsReplayedWithTheSameSanUciAndPosition() {
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            game.plies.forEachIndexed { index, ply ->
                val promotion = ply.uci.getOrNull(4)
                val played = Moves.play(game.fenAt(index), ply.uci.substring(0, 2), ply.uci.substring(2, 4), promotion)
                assertEquals(ply, played, "$name ply ${index + 1}")
            }
        }
    }

    @Test
    fun aPawnAndAKnightHaveTheirLegalTargetsAtTheStart() {
        assertEquals(listOf("e3", "e4"), squares(STANDARD_START_FEN, "e2"))
        assertEquals(listOf("a3", "c3"), squares(STANDARD_START_FEN, "b1"))
        assertEquals(emptyList(), squares(STANDARD_START_FEN, "a1"))
    }

    @Test
    fun theOtherSidesPiecesAndEmptySquaresHaveNoTargets() {
        assertEquals(emptyList(), squares(STANDARD_START_FEN, "e7"))
        assertEquals(emptyList(), squares(STANDARD_START_FEN, "e4"))
        assertEquals(emptyList(), squares(STANDARD_START_FEN, "z9"))
    }

    @Test
    fun capturesAreMarkedIncludingEnPassant() {
        val capture = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2"
        assertEquals(
            listOf(MoveTarget("d5", capture = true, promotion = false), MoveTarget("e5", false, false)),
            Moves.legalTargets(capture, "e4"),
        )
        val enPassant = "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3"
        val target = Moves.legalTargets(enPassant, "e5").first { it.square == "f6" }
        assertTrue(target.capture)
        val played = assertNotNull(Moves.play(enPassant, "e5", "f6"))
        assertEquals("exf6", played.san)
        assertEquals("rnbqkbnr/ppp1p1pp/5P2/3p4/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3", played.fen)
    }

    @Test
    fun castlingBothWaysIsTheKingsTwoStepAndIsWrittenAsOO() {
        val fen = "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1"
        assertEquals(listOf("c1", "d1", "f1", "g1"), squares(fen, "e1"))
        assertEquals("O-O", Moves.play(fen, "e1", "g1")?.san)
        assertEquals("O-O-O", Moves.play(fen, "e1", "c1")?.san)
        assertEquals("e1g1", Moves.play(fen, "e1", "g1")?.uci)
        assertEquals("O-O-O", Moves.play("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R b KQkq - 0 1", "e8", "c8")?.san)
    }

    @Test
    fun aPawnOnTheLastRankNeedsAPieceAndPromotesToIt() {
        val fen = "8/4P3/8/8/8/8/k7/4K3 w - - 0 1"
        assertTrue(Moves.needsPromotion(fen, "e7", "e8"))
        assertTrue(Moves.legalTargets(fen, "e7").single().promotion)
        assertNull(Moves.play(fen, "e7", "e8"), "a promotion without a piece is not a move yet")
        val queen = assertNotNull(Moves.play(fen, "e7", "e8", 'q'))
        assertEquals("e8=Q", queen.san)
        assertEquals("e7e8q", queen.uci)
        assertEquals("e8=N", Moves.play(fen, "e7", "e8", 'n')?.san)
        assertEquals(false, Moves.needsPromotion(fen, "e1", "e2"))
    }

    @Test
    fun aCapturingPromotionNamesTheFileAndThePiece() {
        val fen = "3r4/4P3/8/8/8/8/k7/4K3 w - - 0 1"
        assertEquals("exd8=R", Moves.play(fen, "e7", "d8", 'r')?.san)
    }

    @Test
    fun checkAndMateAreMarked() {
        val check = "4k3/8/8/8/8/8/8/R3K3 w - - 0 1"
        assertEquals("Ra8+", Moves.play(check, "a1", "a8")?.san)
        val scholars = "r1bqkb1r/pppp1ppp/2n2n2/4p2Q/2B1P3/8/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        assertEquals("Qxf7#", Moves.play(scholars, "h5", "f7")?.san)
    }

    @Test
    fun twoPiecesThatCanGoToTheSameSquareAreToldApartByFileThenRankThenBoth() {
        val byFile = "4k3/8/8/8/8/8/4K3/R6R w - - 0 1"
        assertEquals("Rad1", Moves.play(byFile, "a1", "d1")?.san)
        assertEquals("Rhd1", Moves.play(byFile, "h1", "d1")?.san)
        val byRank = "R7/4k3/8/8/8/8/8/R3K3 w - - 0 1"
        assertEquals("R1a4", Moves.play(byRank, "a1", "a4")?.san)
        assertEquals("R8a4", Moves.play(byRank, "a8", "a4")?.san)
        val knights = "4k3/8/8/3N1N2/8/8/2N5/4K3 w - - 0 1"
        assertEquals("Nfe3", Moves.play(knights, "f5", "e3")?.san)
        assertEquals("Ncd4", Moves.play(knights, "c2", "d4")?.san)
        val three = "4k3/8/8/8/1Q1Q4/8/1Q6/4K3 w - - 0 1"
        assertEquals("Qb4d2", Moves.play(three, "b4", "d2")?.san)
    }

    @Test
    fun anIllegalMoveIsNull() {
        assertNull(Moves.play(STANDARD_START_FEN, "e2", "e5"))
        assertNull(Moves.play(STANDARD_START_FEN, "e7", "e5"), "the other side's piece")
        assertNull(Moves.play(STANDARD_START_FEN, "e4", "e5"), "an empty square")
        val pinned = "4k3/4r3/8/8/8/8/4B3/4K3 w - - 0 1"
        assertNull(Moves.play(pinned, "e2", "d3"), "a pinned bishop cannot leave the file")
        val inCheck = "k7/8/8/8/8/8/8/r3K3 w - - 0 1"
        assertNull(Moves.play(inCheck, "e1", "d1"), "still on the checking rook's rank")
    }
}
