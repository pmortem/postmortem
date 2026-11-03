package app.postmortem

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Whether the live engine shows its best moves as arrows. On by default, and remembered across games and app restarts. */
interface EngineArrowsSetting {
    val enabled: Flow<Boolean>

    suspend fun set(enabled: Boolean)
}

/** For tests and previews: nothing is stored. */
class InMemoryEngineArrowsSetting(
    initial: Boolean = true,
) : EngineArrowsSetting {
    private val state = MutableStateFlow(initial)
    override val enabled: Flow<Boolean> get() = state

    override suspend fun set(enabled: Boolean) {
        state.value = enabled
    }
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class DataStoreEngineArrowsSetting(
    private val context: Context,
) : EngineArrowsSetting {
    private val key = booleanPreferencesKey("engine_arrows")

    override val enabled: Flow<Boolean> get() = context.settingsStore.data.map { it[key] ?: true }

    override suspend fun set(enabled: Boolean) {
        context.settingsStore.edit { it[key] = enabled }
    }
}
