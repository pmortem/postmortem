package app.postmortem.core.uci

import org.junit.Assume.assumeTrue
import java.io.File

/** The real Stockfish (Linux build of the release the phone runs), found through the system property Gradle sets. */
object EngineBinary {
    private val path: String = System.getProperty("postmortem.stockfish").orEmpty()
    private val required = System.getProperty("postmortem.engineRequired") == "true"
    private val slow = System.getProperty("postmortem.slow") == "true"

    /** Skips the test where there is no engine (coreOnly builds), and fails it everywhere else: run scripts/lab/prepare. */
    fun require(): ProcessFactory {
        if (path.isEmpty()) {
            check(!required) { "Stockfish is missing: run scripts/lab/prepare (it needs LAB_CACHE)" }
            assumeTrue("no Stockfish binary here", false)
        }
        return { ProcessBuilder(File(path).path).start() }
    }

    /** Skips the test unless the slow pass was asked for (-Ppostmortem.slow=true, as lab-check does). */
    fun requireSlow(): ProcessFactory {
        assumeTrue("slow pass only", slow)
        return require()
    }
}
