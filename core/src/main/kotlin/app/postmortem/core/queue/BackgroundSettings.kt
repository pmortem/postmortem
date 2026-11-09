package app.postmortem.core.queue

/**
 * The user's choices for the background analysis (Settings): how many games run together, how many engines there are in all while
 * the app is in the background, and whether the analysis runs at full speed while Postmortem is open. The defaults are "gentle":
 * one game at a time, light on the phone while the user plays something else. Anything else is "not gentle" and the Settings page
 * says so.
 */
data class BackgroundSettings(
    val gamesAtOnce: Int = DEFAULT_GAMES,
    val engines: Int = DEFAULT_ENGINES,
    val fullSpeedOpen: Boolean = DEFAULT_FULL_SPEED,
) {
    /** True when every value is the default. */
    val isGentle: Boolean get() = this == BackgroundSettings()

    /**
     * True when changing to [new] needs the question "more heat and battery, really?": only the step from gentle to not gentle does,
     * because after that the user has been told (and the Settings page keeps saying so).
     */
    fun needsWarning(new: BackgroundSettings): Boolean = isGentle && !new.isGentle

    /** The values brought into range: games 1 to [MAX_GAMES], engines 1 to [maxEngines]. */
    fun coerced(maxEngines: Int): BackgroundSettings =
        copy(gamesAtOnce = gamesAtOnce.coerceIn(1, MAX_GAMES), engines = engines.coerceIn(1, maxEngines.coerceAtLeast(1)))

    /** The plan for the queue: while the app is open at full speed [openEngines] engines (when [fullSpeedOpen]), else [engines]. */
    fun plan(
        appOpen: Boolean,
        openEngines: Int,
        maxEngines: Int,
    ): QueuePlan {
        val total = if (appOpen && fullSpeedOpen) openEngines else coerced(maxEngines).engines
        return QueuePlan(gamesAtOnce.coerceIn(1, MAX_GAMES), total)
    }

    companion object {
        const val DEFAULT_GAMES = 1
        const val DEFAULT_ENGINES = 2
        const val DEFAULT_FULL_SPEED = true
        const val MAX_GAMES = 3
    }
}
