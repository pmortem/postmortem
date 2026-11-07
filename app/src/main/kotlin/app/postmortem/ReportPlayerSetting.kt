package app.postmortem

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** The player the Report was last looking at, remembered across app restarts. Empty until a name has been picked. */
interface ReportPlayerSetting {
    suspend fun get(): String

    suspend fun set(name: String)
}

/** For tests: nothing is stored. */
class InMemoryReportPlayerSetting(
    private var name: String = "",
) : ReportPlayerSetting {
    override suspend fun get(): String = name

    override suspend fun set(name: String) {
        this.name = name
    }
}

private val Context.reportStore by preferencesDataStore(name = "report")

class DataStoreReportPlayerSetting(
    private val context: Context,
) : ReportPlayerSetting {
    private val key = stringPreferencesKey("player")

    override suspend fun get(): String =
        context.reportStore.data
            .map { it[key].orEmpty() }
            .first()

    override suspend fun set(name: String) {
        context.reportStore.edit { it[key] = name }
    }
}
