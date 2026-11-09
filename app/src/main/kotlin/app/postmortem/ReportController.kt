package app.postmortem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.postmortem.core.board.PieceColor
import app.postmortem.core.report.Drill
import app.postmortem.core.report.DrillQueue
import app.postmortem.core.report.MissedReport
import app.postmortem.core.report.MissedWins
import app.postmortem.core.report.OpeningReport
import app.postmortem.core.report.OpeningStats
import app.postmortem.core.report.PlayerGame
import app.postmortem.core.report.PlayerGames
import app.postmortem.core.report.PlayerName
import app.postmortem.core.report.PlayerStats
import app.postmortem.core.report.Record
import app.postmortem.core.report.ReportFilter
import app.postmortem.core.report.filtered
import app.postmortem.core.store.DrillKey
import app.postmortem.core.store.DrillProgress
import app.postmortem.core.store.GameLibrary
import app.postmortem.core.store.MainLine
import app.postmortem.core.store.ReportRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One player's numbers: [record] over all their games, and their openings. */
data class PlayerView(
    val name: String,
    val record: Record,
    val openings: OpeningReport,
)

/** What the Report tab shows. [loaded] is false until the saved games have been read; [view] is null when no game matches [player]. */
data class ReportUi(
    val loaded: Boolean = false,
    val games: Int = 0,
    val names: List<PlayerName> = emptyList(),
    val player: String = "",
    val view: PlayerView? = null,
    /** Missed wins and missed mates of [player]; null when [view] is. */
    val missed: MissedReport? = null,
    /** The positions to practise, and how many were solved; null when [view] is. */
    val drill: DrillQueue? = null,
    /** The filters every tab follows (period and time control). */
    val filter: ReportFilter = ReportFilter(),
    /** Every number the charts show, for the games the filters let through; null when [view] is. */
    val stats: PlayerStats? = null,
    /** How many games [player] has before the filters, so "nothing for this filter" can be told from "no such player". */
    val unfiltered: Int = 0,
)

/** The parts of the Report under the player picker and the filters. */
enum class ReportTab { Overview, Openings, Mistakes, Practice }

/** An opening family of one colour in the list; [family] is null for the games with no opening. */
data class FamilyKey(
    val color: PieceColor,
    val family: String?,
)

/** One opening (variation) of one colour whose games are listed; [name] is null for the games with no opening. */
data class VariationKey(
    val color: PieceColor,
    val name: String?,
)

/**
 * The state of the Report tab: which player, their numbers, what is expanded and which opening's games are listed. It lives as long
 * as the ViewModel, so the list is where it was after a game was opened and Back is pressed. The games are read from [library] off
 * the main thread whenever the tab is opened, and the numbers are worked out there too.
 */
class ReportController(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val library: GameLibrary?,
    private val setting: ReportPlayerSetting,
    /** Today, for "last 30 days"; the clock in the app, a fixed day in tests. */
    private val today: () -> LocalDate = LocalDate::now,
    /** Says how long the statistics took, for the log. */
    private val log: (String) -> Unit = {},
) {
    var ui: ReportUi by mutableStateOf(ReportUi())
        private set

    var expanded: Set<FamilyKey> by mutableStateOf(emptySet())
        private set

    /** The part of the Report shown under the filters; it stays while a game is opened and Back is pressed. */
    var tab: ReportTab by mutableStateOf(ReportTab.Overview)
        private set

    /** The opening whose games are listed instead of the report, or null. */
    var listed: VariationKey? by mutableStateOf(null)
        private set

    private var rows: List<ReportRow> = emptyList()

    /** The names of [rows], worked out when they are read (not on every keystroke). */
    private var names: List<PlayerName> = emptyList()

    /** The main lines read so far, by game id; cleared when the games are read again. */
    private val lines = HashMap<Long, MainLine>()

    /** The games whose main line has been asked for, found or not (a game without stored evals is not asked again either). */
    private val asked = HashSet<Long>()
    private var work: Job? = null
    private var remembered = false

    /** Reads the saved games again and works the numbers out; the player is the typed one, or the remembered one the first time. */
    fun refresh() {
        val saved = library ?: return
        work?.cancel()
        work =
            scope.launch {
                if (!remembered) {
                    remembered = true
                    ui = ui.copy(player = setting.get(), filter = setting.filter())
                }
                val read = withContext(dispatcher) { runCatching { saved.reportRows() }.getOrDefault(emptyList()) }
                rows = read
                names = withContext(dispatcher) { PlayerGames.names(read) }
                lines.clear()
                asked.clear()
                rebuild()
            }
    }

    /** The player field changed. A name that is one of the saved games' is remembered. */
    fun setPlayer(text: String) {
        ui = ui.copy(player = text)
        work?.cancel()
        work =
            scope.launch {
                rebuild()
                val known = ui.names.firstOrNull { it.name.equals(text.trim(), ignoreCase = true) }
                if (known != null) setting.set(known.name)
            }
    }

    /** A filter chip was chosen: every tab follows, and the choice is remembered with the player. */
    fun setFilter(filter: ReportFilter) {
        ui = ui.copy(filter = filter)
        work?.cancel()
        work =
            scope.launch {
                rebuild()
                setting.setFilter(filter)
            }
    }

    fun selectTab(new: ReportTab) {
        tab = new
    }

    fun toggleFamily(key: FamilyKey) {
        expanded = if (key in expanded) expanded - key else expanded + key
    }

    fun list(key: VariationKey) {
        listed = key
    }

    /** Closes the list of games and shows the report again; false when no list was open. */
    fun closeList(): Boolean {
        if (listed == null) return false
        listed = null
        return true
    }

    private suspend fun rebuild() {
        val player = ui.player
        val filter = ui.filter
        val built =
            withContext(dispatcher) {
                val names = names
                val all = PlayerGames.of(rows, player)
                val games = all.filtered(filter, today())
                val view =
                    games.takeIf { it.isNotEmpty() }?.let { found ->
                        PlayerView(
                            names
                                .firstOrNull {
                                    it.name.equals(player.trim(), ignoreCase = true)
                                }?.name ?: player.trim(),
                            Record.of(found),
                            OpeningStats.of(found),
                        )
                    }
                val lines = view?.let { mainLinesOf(games) }.orEmpty()
                val missed = view?.let { MissedWins.of(games, lines) }
                val stats = view?.let { timed(games.size) { PlayerStats.of(games, lines) } }
                val queue = missed?.let { Drill.queue(games, lines, progressOf(games), it) }
                Built(names, view, missed, queue, stats, rows.size, all.size)
            }
        if (ui.player != player || ui.filter != filter) return
        ui =
            ui.copy(
                loaded = true,
                games = built.games,
                names = built.names,
                view = built.view,
                missed = built.missed,
                drill = built.drill,
                stats = built.stats,
                unfiltered = built.unfiltered,
            )
        listed = listed?.takeIf { key -> built.view?.openings.let { report -> key in variationKeys(report) } }
    }

    private class Built(
        val names: List<PlayerName>,
        val view: PlayerView?,
        val missed: MissedReport?,
        val drill: DrillQueue?,
        val stats: PlayerStats?,
        val games: Int,
        val unfiltered: Int,
    )

    private fun <T> timed(
        games: Int,
        work: () -> T,
    ): T {
        val started = System.nanoTime()
        return work().also { log("PlayerStats: $games games in ${(System.nanoTime() - started) / NANOS_PER_MS} ms") }
    }

    /** The practice progress of [games], read fresh each time: it changes while the player practises. */
    private fun progressOf(games: List<PlayerGame>): Map<DrillKey, DrillProgress> =
        runCatching { library?.drillProgress(games.map { it.gameId }.distinct()) }.getOrNull().orEmpty()

    /** The main lines of [games], reading only the ones not read yet. */
    private fun mainLinesOf(games: List<PlayerGame>): Map<Long, MainLine> {
        val wanted = games.map { it.gameId }.distinct()
        val missing = wanted.filter { it !in asked }
        if (missing.isNotEmpty()) {
            lines += runCatching { library?.mainLines(missing) }.getOrNull().orEmpty()
            asked += missing
        }
        return wanted.mapNotNull { id -> lines[id]?.let { id to it } }.toMap()
    }

    private fun variationKeys(report: OpeningReport?): Set<VariationKey> =
        report
            ?.let { r ->
                r.white.flatMap { family -> family.variations.map { VariationKey(PieceColor.WHITE, it.name) } } +
                    r.black.flatMap { family -> family.variations.map { VariationKey(PieceColor.BLACK, it.name) } }
            }.orEmpty()
            .toSet()
}

private const val NANOS_PER_MS = 1_000_000L
