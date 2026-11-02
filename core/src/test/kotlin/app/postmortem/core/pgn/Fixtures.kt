package app.postmortem.core.pgn

import java.io.File

/** Real game exports (Lichess, Chess.com) kept under `src/test/resources/pgn`. */
object Fixtures {
    private const val DIRECTORY = "/pgn"
    private const val EXTENSION = ".pgn"

    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResource("$DIRECTORY/$name$EXTENSION")) { "no fixture $name" }.readText()

    fun game(name: String): Game = (PgnParser.parse(text(name)) as PgnResult.Success).game

    /** The names of every fixture. */
    val names: List<String> by lazy {
        File(checkNotNull(Fixtures::class.java.getResource(DIRECTORY)).toURI())
            .listFiles { f -> f.name.endsWith(EXTENSION) }!!
            .map { it.name.removeSuffix(EXTENSION) }
            .sorted()
    }
}
