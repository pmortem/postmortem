package app.postmortem.core.report

import java.time.LocalDate
import java.time.ZoneOffset

/** The speed of a game by its time control, as Lichess groups them. */
enum class Speed { BULLET, BLITZ, RAPID, CLASSICAL }

/** Reading the `TimeControl` tag. */
object TimeControl {
    /** Seconds added per increment second in Lichess's estimate of how long a game takes: base + 40 × increment. */
    private const val MOVES_ESTIMATED = 40

    private const val BLITZ_FROM = 180
    private const val RAPID_FROM = 480
    private const val CLASSICAL_FROM = 1500

    /**
     * The speed a `TimeControl` tag names: `base+increment` (or `base`) in seconds is bullet under 180 seconds by the estimate, blitz
     * under 480, rapid under 1500 and classical above. Correspondence (`1/259200`, chess.com daily) is classical. A missing tag, `-`
     * or anything else is null (unknown).
     */
    fun of(tag: String?): Speed? {
        val text = tag?.trim().orEmpty()
        if (text.isEmpty() || text == "-") return null
        if ('/' in
            text
        ) {
            return if (text.substringBefore('/').toIntOrNull() != null &&
                text.substringAfter('/').toIntOrNull() != null
            ) {
                Speed.CLASSICAL
            } else {
                null
            }
        }
        val base = text.substringBefore('+').toIntOrNull() ?: return null
        val increment = if ('+' in text) text.substringAfter('+').toIntOrNull() ?: return null else 0
        val seconds = base + MOVES_ESTIMATED * increment
        return when {
            seconds < BLITZ_FROM -> Speed.BULLET
            seconds < RAPID_FROM -> Speed.BLITZ
            seconds < CLASSICAL_FROM -> Speed.RAPID
            else -> Speed.CLASSICAL
        }
    }
}

/** How far back the Report looks. */
enum class Period { ALL, LAST_30_DAYS, LAST_100_GAMES }

/** The Report's filters: a period and a time control (null for all). Every tab of the Report follows them. */
data class ReportFilter(
    val period: Period = Period.ALL,
    val speed: Speed? = null,
) {
    val isDefault: Boolean get() = this == ReportFilter()

    companion object {
        const val RECENT_DAYS = 30L
        const val RECENT_GAMES = 100
    }
}

/** The day a game was played: its `Date` tag (a part left as `?` counts as the first), or the day it was saved when the tag says nothing. */
fun playedOn(
    dateTag: String?,
    savedAt: Long,
): LocalDate = parseDate(dateTag) ?: LocalDate.ofInstant(java.time.Instant.ofEpochMilli(savedAt), ZoneOffset.UTC)

/** `2025.03.12`, `2025.03` or `2025` as a date; null when there is no year. */
fun parseDate(tag: String?): LocalDate? {
    val parts = tag?.trim()?.split('.').orEmpty()
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull() ?: 1
    val day = parts.getOrNull(2)?.toIntOrNull() ?: 1
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

/**
 * The games the filters let through, in their order. The time control comes first (games of unknown speed only pass "all"), then the
 * period: the last 30 days up to [today], or the 100 most recent of what is left, by the day played and then the order they were saved.
 */
fun List<PlayerGame>.filtered(
    filter: ReportFilter,
    today: LocalDate,
): List<PlayerGame> {
    val bySpeed = if (filter.speed == null) this else filter { it.speed == filter.speed }
    return when (filter.period) {
        Period.ALL -> {
            bySpeed
        }

        Period.LAST_30_DAYS -> {
            val from = today.minusDays(ReportFilter.RECENT_DAYS)
            bySpeed.filter { !it.day.isBefore(from) }
        }

        Period.LAST_100_GAMES -> {
            val keep =
                bySpeed
                    .sortedWith(
                        compareByDescending<PlayerGame> {
                            it.day
                        }.thenByDescending { it.gameId },
                    ).take(ReportFilter.RECENT_GAMES)
                    .toSet()
            bySpeed.filter { it in keep }
        }
    }
}
