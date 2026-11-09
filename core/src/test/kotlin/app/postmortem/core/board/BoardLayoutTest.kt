package app.postmortem.core.board

import app.postmortem.core.pgn.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardLayoutTest {
    private val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    private fun List<BoardCell>.at(square: String): BoardPiece? =
        BoardLayout.position(square).let { (row, col) -> firstOrNull { it.row == row && it.col == col }?.piece }

    private fun piece(letter: Char) =
        BoardPiece(
            if (letter.isUpperCase()) PieceColor.WHITE else PieceColor.BLACK,
            when (letter.lowercaseChar()) {
                'k' -> PieceKind.KING
                'q' -> PieceKind.QUEEN
                'r' -> PieceKind.ROOK
                'b' -> PieceKind.BISHOP
                'n' -> PieceKind.KNIGHT
                else -> PieceKind.PAWN
            },
        )

    @Test
    fun theStartPositionHasWhiteAtTheBottom() {
        val cells = BoardLayout.cells(startFen)
        assertEquals(32, cells.size)
        assertEquals(piece('R'), cells.at("a1"))
        assertEquals(piece('K'), cells.at("e1"))
        assertEquals(piece('Q'), cells.at("d1"))
        assertEquals(piece('k'), cells.at("e8"))
        assertEquals(piece('p'), cells.at("h7"))
        assertEquals(BoardCell(7, 0, piece('R')), cells.single { it.row == 7 && it.col == 0 })
        assertEquals(BoardCell(0, 4, piece('k')), cells.single { it.row == 0 && it.col == 4 })
        assertNull(cells.at("e4"))
    }

    @Test
    fun squaresMapToRowsAndColumns() {
        assertEquals(7 to 0, BoardLayout.position("a1"))
        assertEquals(0 to 7, BoardLayout.position("h8"))
        assertEquals(4 to 4, BoardLayout.position("e4"))
    }

    @Test
    fun everyMoveOfEveryFixtureLandsOnItsSquare() {
        // Covers castling, promotion and en passant from real games: the UCI move says where the piece must be.
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            Fixtures.game(name).plies.forEachIndexed { i, ply ->
                val cells = BoardLayout.cells(ply.fen)
                val label = "$name ply ${i + 1} ${ply.san}"
                val landed = checkNotNull(cells.at(ply.uci.substring(2, 4))) { label }
                assertEquals(if (i % 2 == 0) PieceColor.WHITE else PieceColor.BLACK, landed.color, label)
                if (ply.uci.length == 5) assertEquals(piece(ply.uci[4]).kind, landed.kind, label)
                // Castling is the one move after which the king's start square can hold the rook's neighbour, not the king.
                if (!ply.san.startsWith("O-O")) assertNull(cells.at(ply.uci.substring(0, 2)), label)
            }
        }
    }

    @Test
    fun castlingMovesKingAndRook() {
        val game = Fixtures.game("lichess-mate-castle")
        val index = game.plies.indexOfFirst { it.san == "O-O" }
        val white = index % 2 == 0
        val cells = BoardLayout.cells(game.plies[index].fen)
        val rank = if (white) "1" else "8"
        assertEquals(piece(if (white) 'K' else 'k'), cells.at("g$rank"))
        assertEquals(piece(if (white) 'R' else 'r'), cells.at("f$rank"))
        assertNull(cells.at("e$rank"))
        assertNull(cells.at("h$rank"))
    }

    @Test
    fun aPromotionPutsTheNewPieceOnTheBackRank() {
        val game = Fixtures.game("lichess-promotion-long-castle")
        val index = game.plies.indexOfFirst { it.uci.length == 5 }
        val ply = game.plies[index]
        val letter = ply.uci[4].let { if (index % 2 == 0) it.uppercaseChar() else it }
        assertEquals(piece(letter), BoardLayout.cells(ply.fen).at(ply.uci.substring(2, 4)))
    }

    @Test
    fun finalPositionsMatchChessComsCurrentPositionTag() {
        for (name in Fixtures.names.filter { it.startsWith("chesscom") && it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            val expected = BoardLayout.cells(game.headers.getValue("CurrentPosition"))
            assertEquals(expected.toSet(), BoardLayout.cells(game.plies.last().fen).toSet(), name)
        }
    }

    @Test
    fun enPassantRemovesTheCapturedPawn() {
        val game = Fixtures.game("chesscom-en-passant")
        var before = BoardLayout.cells(game.startFen)
        var found = false
        game.plies.forEachIndexed { i, ply ->
            val after = BoardLayout.cells(ply.fen)
            val to = ply.uci.substring(2, 4)
            val pawnCapture = ply.san.contains('x') && ply.san[0].isLowerCase()
            if (pawnCapture && before.at(to) == null) {
                found = true
                val behind = to[0] + (if (i % 2 == 0) (to[1] - 1) else (to[1] + 1)).toString()
                assertEquals(piece(if (i % 2 == 0) 'p' else 'P'), before.at(behind))
                assertNull(after.at(behind))
            }
            before = after
        }
        assertTrue(found, "the fixture has no en passant capture")
    }

    @Test
    fun malformedFensAreRefused() {
        assertFailsWith<IllegalArgumentException> { BoardLayout.cells("8/8/8 w") }
        assertFailsWith<IllegalArgumentException> { BoardLayout.cells("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBN w") }
        assertFailsWith<IllegalArgumentException> { BoardLayout.cells("xnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w") }
    }

    @Test
    fun coordinatesRunAToHAndEightToOne() {
        assertEquals("abcdefgh", (0 until BoardLayout.SIZE).map(BoardLayout::fileName).joinToString(""))
        assertEquals("87654321", (0 until BoardLayout.SIZE).map(BoardLayout::rankName).joinToString(""))
    }

    @Test
    fun coordinatesAgreeWithWherePiecesAreDrawn() {
        // In the start position the white king is on e1: the bottom row, and the column named e.
        val king =
            BoardLayout
                .cells("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                .single { it.piece == BoardPiece(PieceColor.WHITE, PieceKind.KING) }
        assertEquals("e1", "${BoardLayout.fileName(king.col)}${BoardLayout.rankName(king.row)}")
    }

    @Test
    fun coordinatesOutsideTheBoardAreRefused() {
        assertFailsWith<IllegalArgumentException> { BoardLayout.fileName(8) }
        assertFailsWith<IllegalArgumentException> { BoardLayout.rankName(-1) }
    }

    @Test
    fun flippedCoordinatesReadHToAAndOneToEight() {
        assertEquals("hgfedcba", (0 until BoardLayout.SIZE).map { BoardLayout.fileName(it, flipped = true) }.joinToString(""))
        assertEquals("12345678", (0 until BoardLayout.SIZE).map { BoardLayout.rankName(it, flipped = true) }.joinToString(""))
    }

    private val square = 100f

    private fun label(
        labels: List<BoardLabel>,
        text: Char,
        isRank: Boolean,
    ) = labels.single { it.text == text && it.isRank == isRank }

    @Test
    fun thereAreSixteenLabelsOnTheRightFileAndTheBottomRank() {
        val labels = BoardLabels.all()
        assertEquals(16, labels.size)
        assertTrue(labels.filter { it.isRank }.all { it.col == BoardLayout.SIZE - 1 })
        assertTrue(labels.filterNot { it.isRank }.all { it.row == BoardLayout.SIZE - 1 })
        assertEquals("87654321", labels.filter { it.isRank }.map { it.text }.joinToString(""))
        assertEquals("abcdefgh", labels.filterNot { it.isRank }.map { it.text }.joinToString(""))
    }

    @Test
    fun theLabelsOnA1AndH1AreInTheBottomLeftOfTheirSquares() {
        val labels = BoardLabels.all()
        // The file letter "a" is in a1 (row 7, col 0): starts 6 px in, baseline 89 px below the square's top.
        val a = label(labels, 'a', isRank = false)
        assertEquals(7 to 0, a.row to a.col)
        assertEquals(6f, a.x(square), 1e-4f)
        assertEquals(7 * square + 89f, a.y(square), 1e-4f)
        // "h" is in h1 (row 7, col 7).
        val h = label(labels, 'h', isRank = false)
        assertEquals(7 to 7, h.row to h.col)
        assertEquals(7 * square + 6f, h.x(square), 1e-4f)
    }

    @Test
    fun theRankLabelsAreAtTheTopRightOfTheRightFile() {
        val labels = BoardLabels.all()
        // "8" is in h8 (row 0, col 7): its right end is 93 px from the left edge of the square, baseline 21 px below the top.
        val eight = label(labels, '8', isRank = true)
        assertEquals(0 to 7, eight.row to eight.col)
        assertEquals(7 * square + 93f, eight.x(square), 1e-4f)
        assertEquals(21f, eight.y(square), 1e-4f)
        // "1" is in h1 (row 7, col 7).
        val one = label(labels, '1', isRank = true)
        assertEquals(7 to 7, one.row to one.col)
        assertEquals(7 * square + 21f, one.y(square), 1e-4f)
    }

    @Test
    fun flippedLabelsKeepTheirCornersAndReverseTheirNames() {
        val flipped = BoardLabels.all(flipped = true)
        // Still on the right file and the bottom rank; the board is turned, so the top row of the right file is rank 1
        // and the bottom row reads h to a from the left.
        assertTrue(flipped.filter { it.isRank }.all { it.col == BoardLayout.SIZE - 1 })
        assertEquals("12345678", flipped.filter { it.isRank }.map { it.text }.joinToString(""))
        assertEquals("hgfedcba", flipped.filterNot { it.isRank }.map { it.text }.joinToString(""))
        assertEquals(0, flipped.first { it.isRank }.row)
        // a1 is the bottom-right square when flipped: its letter is the last one and its digit "1" is at the top right.
        val a = label(flipped, 'a', isRank = false)
        assertEquals(7 to 7, a.row to a.col)
        assertEquals(7 * square + 6f, a.x(square), 1e-4f)
        val one = label(flipped, '1', isRank = true)
        assertEquals(0 to 7, one.row to one.col)
        assertEquals(7 * square + 93f, one.x(square), 1e-4f)
    }

    @Test
    fun aFlippedBoardTurnsHalfAround() {
        val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        assertEquals(32, BoardLayout.cells(start, flipped = true).size)
        // Every piece is on the opposite square, none lost or doubled.
        assertEquals(
            BoardLayout.cells(start).map { it.piece to (7 - it.row to 7 - it.col) }.toSet(),
            BoardLayout.cells(start, flipped = true).map { it.piece to (it.row to it.col) }.toSet(),
        )
    }

    @Test
    fun aMoveIsAnArrowBetweenTheDrawnSquares() {
        assertEquals(BoardArrow(6, 4, 4, 4), BoardLayout.arrow("e2e4"))
        assertEquals(BoardArrow(7, 6, 5, 5), BoardLayout.arrow("g1f3"))
        // castling is the king's move, a promotion's letter is ignored
        assertEquals(BoardArrow(7, 4, 7, 6), BoardLayout.arrow("e1g1"))
        assertEquals(BoardArrow(1, 4, 0, 4), BoardLayout.arrow("e7e8q"))
    }

    @Test
    fun theArrowTurnsWithTheBoardWhenItIsFlipped() {
        assertEquals(BoardArrow(1, 3, 3, 3), BoardLayout.arrow("e2e4", flipped = true))
        // the squares of the arrow are where the flipped board's pieces are
        val cells = BoardLayout.cells("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", flipped = true)
        val arrow = BoardLayout.arrow("e2e4", flipped = true)
        assertEquals(PieceKind.PAWN, cells.single { it.row == arrow.fromRow && it.col == arrow.fromCol }.piece.kind)
    }

    @Test
    fun aCellIsTheSquareDrawnThereWithAndWithoutFlip() {
        assertEquals("a8", BoardLayout.squareAt(0, 0))
        assertEquals("h1", BoardLayout.squareAt(7, 7))
        assertEquals("e4", BoardLayout.squareAt(4, 4))
        assertEquals("h1", BoardLayout.squareAt(0, 0, flipped = true))
        assertEquals("a8", BoardLayout.squareAt(7, 7, flipped = true))
        for (row in 0 until BoardLayout.SIZE) {
            for (col in 0 until BoardLayout.SIZE) {
                val (r, c) = BoardLayout.position(BoardLayout.squareAt(row, col))
                assertEquals(row to col, r to c)
                val (fr, fc) = BoardLayout.position(BoardLayout.squareAt(row, col, flipped = true))
                assertEquals(BoardLayout.SIZE - 1 - row to BoardLayout.SIZE - 1 - col, fr to fc)
            }
        }
    }

    @Test
    fun theMoverOfAPlyAndTheSignOfAColourAgreeWithEveryFixtureGame() {
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val game = Fixtures.game(name)
            for (ply in 1..game.plies.size) {
                assertEquals(Moves.sideToMove(game.fenAt(ply - 1)), moverAt(ply, game.whiteMovesFirst), "$name ply $ply")
            }
        }
        assertEquals(PieceColor.BLACK, moverAt(1, whiteMovesFirst = false))
        assertEquals(1, PieceColor.WHITE.sign)
        assertEquals(-1, PieceColor.BLACK.sign)
    }
}
