package app.postmortem.core.store

import app.postmortem.core.pgn.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals

class HeadersJsonTest {
    @Test
    fun tagsWithQuotesBackslashesNewlinesAndUnicodeComeBackEqualAndInOrder() {
        val headers =
            linkedMapOf(
                "Event" to "Rated \"blitz\" game",
                "White" to "Zoë \\ Müller",
                "Annotator" to "line one\nline two\ttab\r",
                "Control" to "bell\u0007end",
                "Empty" to "",
                "Emoji" to "♞ vs ♘",
            )
        val decoded = HeadersJson.decode(HeadersJson.encode(headers))
        assertEquals(headers, decoded)
        assertEquals(headers.keys.toList(), decoded.keys.toList())
    }

    @Test
    fun theTagsOfEveryFixtureComeBackEqual() {
        for (name in Fixtures.names.filter { it != "chesscom-no-moves" }) {
            val headers = Fixtures.game(name).headers
            assertEquals(headers, HeadersJson.decode(HeadersJson.encode(headers)), name)
        }
    }

    @Test
    fun noTagsIsAnEmptyObject() {
        assertEquals("{}", HeadersJson.encode(emptyMap()))
        assertEquals(emptyMap(), HeadersJson.decode(" { } "))
    }
}
