package app.postmortem.core.analysis

import app.postmortem.core.uci.ProcessFactory
import java.io.File

/**
 * A stand-in for the engine, as a shell script: it answers the handshake, prints canned MultiPV lines (line 2 before line 1, and
 * line 1 first at a lower depth) when it gets `go`, and then either answers `bestmove` at once or, with [waitForStop], only after
 * `stop`. It writes every command it receives to [log].
 */
class FakeLiveEngine(
    private val log: File,
    private val waitForStop: Boolean = false,
    /** The `info` lines printed for `go`, instead of the canned ones. */
    private val infoLines: List<String>? = null,
) {
    var started = 0
        private set

    val commands: List<String> get() = if (log.exists()) log.readLines() else emptyList()

    val factory: ProcessFactory = {
        started++
        ProcessBuilder("bash", "-c", SCRIPT, "fake", log.path, waitForStop.toString(), infoLines?.joinToString("\n") ?: "").start()
    }

    private companion object {
        val SCRIPT =
            """
            while IFS= read -r line; do
              echo "${'$'}line" >> "${'$'}1"
              case "${'$'}line" in
                uci) echo uciok ;;
                isready) echo readyok ;;
                go*)
                  if [ -n "${'$'}3" ]; then echo "${'$'}3"; else
                  echo "info depth 5 multipv 1 score cp 10 nodes 1000 time 10 pv e2e4 e7e5"
                  echo "info depth 6 multipv 2 score cp 30 nodes 2000 time 20 pv d2d4 d7d5"
                  echo "info depth 6 multipv 1 score cp 50 nodes 3000 time 30 pv g1f3 g8f6"
                  echo "info depth 6 multipv 3 score mate -2 nodes 4000 time 40 pv c2c4"
                  echo "info depth 4 multipv 2 score cp 99 nodes 5000 time 50 pv a2a3"
                  fi
                  if [ "${'$'}2" != "true" ]; then echo "bestmove g1f3"; fi ;;
                stop) echo "bestmove g1f3" ;;
                quit) exit 0 ;;
              esac
            done
            """.trimIndent()
    }
}
