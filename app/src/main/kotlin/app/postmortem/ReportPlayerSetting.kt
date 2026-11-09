package app.postmortem

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.postmortem.core.report.Period
import app.postmortem.core.report.ReportFilter
import app.postmortem.core.report.Speed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The player the Report was last looking at (empty until a name has been picked) and the filters it was looking through, remembered
 * across app restarts.
 */
interface ReportPlayerSetting {
    suspend fun get(): String

    suspend fun set(name: String)

    suspend fun filter(): ReportFilter

    suspend fun setFilter(filter: ReportFilter)
}

/** For tests: nothing is stored. */
class InMemoryReportPlayerSetting(
    private var name: String = "",
    private var filter: ReportFilter = ReportFilter(),
) : ReportPlayerSetting {
    override suspend fun get(): String = name

    override suspend fun set(name: String) {
        this.name = name
    }

    override suspend fun filter(): ReportFilter = filter

    override suspend fun setFilter(filter: ReportFilter) {
        this.filter = filter
    }
}

private val Context.reportStore by preferencesDataStore(name = "report")

class DataStoreReportPlayerSetting(
    private val context: Context,
) : ReportPlayerSetting {
    private val key = stringPreferencesKey("player")
    private val period = stringPreferencesKey("period")
    private val speed = stringPreferencesKey("speed")

    override suspend fun get(): String =
        context.reportStore.data
            .map { it[key].orEmpty() }
            .first()

    override suspend fun set(name: String) {
        context.reportStore.edit { it[key] = name }
    }

    override suspend fun filter(): ReportFilter =
        context.reportStore.data
            .map {
                ReportFilter(
                    period = Period.entries.firstOrNull { p -> p.name == it[period] } ?: Period.ALL,
                    speed = Speed.entries.firstOrNull { s -> s.name == it[speed] },
                )
            }.first()

    override suspend fun setFilter(filter: ReportFilter) {
        context.reportStore.edit {
            it[period] = filter.period.name
            filter.speed?.let { chosen -> it[speed] = chosen.name } ?: it.remove(speed)
        }
    }
}
