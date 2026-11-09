package app.postmortem.core.pgn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedPgnTest {
    private val names = Fixtures.names.filter { it != "chesscom-no-moves" }

    @Test
    fun oneSharedGameIsOneGameAndKeepsItsText() {
        val shared = assertIs<Shared.Games>(SharedPgn.classify(Fixtures.text("lichess-short-resign")))
        assertEquals(1, shared.pgns.size)
        assertEquals(Fixtures.game("lichess-short-resign"), (PgnParser.parse(shared.pgns.single()) as PgnResult.Success).game)
    }

    @Test
    fun aPgnWithSeveralGamesIsSplitIntoThemInOrder() {
        val all = names.joinToString("\n\n") { Fixtures.text(it) }
        val shared = assertIs<Shared.Games>(SharedPgn.classify(all))
        assertEquals(names.map { Fixtures.game(it) }, shared.pgns.map { (PgnParser.parse(it) as PgnResult.Success).game })
        assertEquals(0, shared.more)
    }

    @Test
    fun atMostTwentyGamesAreTakenFromOneShare() {
        val many = (1..25).joinToString("\n\n") { Fixtures.text("scholars-mate") }
        val shared = assertIs<Shared.Games>(SharedPgn.classify(many))
        assertEquals(SharedPgn.MAX_GAMES, shared.pgns.size)
        assertEquals(5, shared.more)
    }

    @Test
    fun aLinkToAGameIsALinkAndOtherTextIsNotAGame() {
        assertEquals(Shared.Link, SharedPgn.classify("https://lichess.org/abcdEFGH"))
        assertEquals(Shared.Link, SharedPgn.classify("  https://www.chess.com/game/live/123456789\n"))
        assertEquals(Shared.NotAGame, SharedPgn.classify("https://example.com/game/1"))
        assertEquals(Shared.NotAGame, SharedPgn.classify("good game! https://lichess.org/abc and more"))
        assertEquals(Shared.NotAGame, SharedPgn.classify("hello"))
        assertEquals(Shared.NotAGame, SharedPgn.classify(""))
    }

    @Test
    fun movesWithoutTagsAreAGameAndBrokenGamesAreLeftOut() {
        assertIs<Shared.Games>(SharedPgn.classify("1. e4 e5 2. Qh5 Nc6 3. Bc4 Nf6 4. Qxf7#"))
        val withBroken = Fixtures.text("scholars-mate") + "\n\n[Event \"x\"]\n\n1. e4 e5 2. Kh5\n"
        assertEquals(1, assertIs<Shared.Games>(SharedPgn.classify(withBroken)).pgns.size)
    }

    @Test
    fun aHugeShareIsNotReadAtAll() {
        val huge = Fixtures.text("scholars-mate") + " ".repeat(SharedPgn.MAX_CHARS)
        assertEquals(Shared.NotAGame, SharedPgn.classify(huge))
    }
}
