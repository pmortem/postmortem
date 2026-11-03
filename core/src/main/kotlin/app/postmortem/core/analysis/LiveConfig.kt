package app.postmortem.core.analysis

/** The settings of the live engine that shows the best moves of the shown position; the stored analysis does not use them. */
data class LiveConfig(
    /** How many best moves (engine lines) are searched and shown. */
    val lines: Int = DEFAULT_LINES,
    /** Threads of the live search. Its result is not reproducible, which is fine: it is only an overlay. */
    val threads: Int = DEFAULT_THREADS,
    /** Hash size of the live engine process, in MB. */
    val hashMb: Int = DEFAULT_HASH_MB,
    /** A search of one position stops by itself after this long, so it does not drain the battery or heat the phone. */
    val maxSearchMs: Long = DEFAULT_MAX_SEARCH_MS,
    /** The shortest time between two updates handed to the screen while a search runs. */
    val updateIntervalMs: Long = DEFAULT_UPDATE_INTERVAL_MS,
    /** How long the position has to stay shown before its search starts, so stepping quickly starts no search. */
    val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    companion object {
        const val DEFAULT_LINES = 3
        const val DEFAULT_THREADS = 2
        const val DEFAULT_HASH_MB = 64
        const val DEFAULT_MAX_SEARCH_MS = 20_000L
        const val DEFAULT_UPDATE_INTERVAL_MS = 250L
        const val DEFAULT_DEBOUNCE_MS = 300L
    }
}
