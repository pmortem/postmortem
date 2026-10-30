package app.postmortem.core.pgn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LooksLikePgnTest {
    @Test
    fun everyFixtureLooksLikeAGame() {
        for (name in Fixtures.names) assertTrue(looksLikePgn(Fixtures.text(name)), name)
    }

    @Test
    fun bareMovesLookLikeAGame() {
        assertTrue(looksLikePgn("1. e4 e5 2. Nf3 Nc6"))
        assertTrue(looksLikePgn("  1.e4 c5"))
        assertTrue(looksLikePgn("1... Nf6"))
    }

    @Test
    fun otherTextDoesNot() {
        assertFalse(looksLikePgn(""))
        assertFalse(looksLikePgn("hello world"))
        assertFalse(looksLikePgn("1. buy milk"))
        assertFalse(looksLikePgn("https://lichess.org/Di9AJHpT"))
    }
}
