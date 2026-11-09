package app.postmortem.core.analysis

/**
 * The one home of the engine settings. A position is searched for a fixed number of [nodes] by a single-threaded engine with a
 * cleared hash, so the same position always gets the same evaluation, whatever else was searched before it or beside it.
 * Speed comes from running [workers] such engines at once on different positions, not from threads inside a search.
 */
data class AnalysisConfig(
    /** Nodes searched per position. A budget of nodes, not of time or depth, is what makes a search repeatable. */
    val nodes: Long = DEFAULT_NODES,
    /** Engine processes running at once, each on its own positions. */
    val workers: Int = 1,
    /** Hash size of each engine process, in MB. Fixed, since it changes a search. */
    val hashMb: Int = DEFAULT_HASH_MB,
    /** How long the engine has to answer `uci` and `isready`. */
    val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    /** How long one search may take before the engine counts as hung. It is a safety net, not a budget. */
    val searchTimeoutMs: Long = DEFAULT_SEARCH_TIMEOUT_MS,
    /** How long `quit` gets before the process is killed. */
    val quitGraceMs: Long = DEFAULT_QUIT_GRACE_MS,
) {
    /** The settings as a line to store next to a result, so an analysis can say how it was made. */
    fun describe(engine: String): String = "$engine, nodes=$nodes, hash=${hashMb}MB, threads=$THREADS, ucinewgame per position"

    companion object {
        const val DEFAULT_NODES = 1_000_000L
        const val DEFAULT_HASH_MB = 16
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 20_000L
        const val DEFAULT_SEARCH_TIMEOUT_MS = 300_000L
        const val DEFAULT_QUIT_GRACE_MS = 500L

        /** Every engine process is single-threaded: more threads in one search make it non-reproducible. */
        const val THREADS = 1

        /** Most engine processes at once, whatever the number of cores (memory, heat). */
        const val MAX_WORKERS = 4

        /** Engines for a game analysed while Postmortem is not on screen: gentle, so the app the user is playing in stays smooth. */
        const val BACKGROUND_WORKERS = 2

        /** One core is left for the app and the system. */
        fun forCores(cores: Int) = AnalysisConfig(workers = (cores - 1).coerceIn(1, MAX_WORKERS))
    }
}
