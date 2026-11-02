package app.postmortem.core.analysis

/** The one home of the engine settings. */
data class AnalysisConfig(
    val moveTimeMs: Long = DEFAULT_MOVE_TIME_MS,
    val threads: Int = 1,
    val hashMb: Int = DEFAULT_HASH_MB,
    /** How long the engine has to answer `uci` and `isready`. */
    val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    /** How long past the move time a search may take before the engine counts as hung. */
    val searchMarginMs: Long = DEFAULT_SEARCH_MARGIN_MS,
    /** How long `quit` gets before the process is killed. */
    val quitGraceMs: Long = DEFAULT_QUIT_GRACE_MS,
) {
    companion object {
        const val DEFAULT_MOVE_TIME_MS = 1000L
        const val DEFAULT_HASH_MB = 128
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 20_000L
        const val DEFAULT_SEARCH_MARGIN_MS = 10_000L
        const val DEFAULT_QUIT_GRACE_MS = 500L

        /** One thread is left for the app and the system. */
        fun forCores(cores: Int) = AnalysisConfig(threads = maxOf(1, cores - 1))
    }
}
