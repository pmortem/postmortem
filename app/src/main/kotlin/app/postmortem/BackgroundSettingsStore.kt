package app.postmortem

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.postmortem.core.queue.BackgroundSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** The background analysis settings as the screens and the queue see them: the current value, and a way to change it. */
interface BackgroundSettingsControl {
    val settings: StateFlow<BackgroundSettings>

    fun set(new: BackgroundSettings)
}

/** For tests and previews: nothing is stored. */
class InMemoryBackgroundSettings(
    initial: BackgroundSettings = BackgroundSettings(),
) : BackgroundSettingsControl {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<BackgroundSettings> = state.asStateFlow()

    override fun set(new: BackgroundSettings) {
        state.value = new
    }
}

private val Context.backgroundStore by preferencesDataStore(name = "background")

/**
 * The settings kept in a DataStore file, read once when the app starts (the queue needs them at once) and written as they change.
 * [persist] runs the write off the caller's thread.
 */
class DataStoreBackgroundSettings(
    private val context: Context,
    private val persist: (suspend () -> Unit) -> Unit,
) : BackgroundSettingsControl {
    private val games = intPreferencesKey("games_at_once")
    private val engines = intPreferencesKey("engines")
    private val fullSpeed = booleanPreferencesKey("full_speed_open")

    private val state =
        MutableStateFlow(
            runBlocking {
                context.backgroundStore.data
                    .map {
                        BackgroundSettings(
                            gamesAtOnce = it[games] ?: BackgroundSettings.DEFAULT_GAMES,
                            engines = it[engines] ?: BackgroundSettings.DEFAULT_ENGINES,
                            fullSpeedOpen = it[fullSpeed] ?: BackgroundSettings.DEFAULT_FULL_SPEED,
                        )
                    }.first()
            },
        )
    override val settings: StateFlow<BackgroundSettings> = state.asStateFlow()

    override fun set(new: BackgroundSettings) {
        state.value = new
        persist {
            context.backgroundStore.edit {
                it[games] = new.gamesAtOnce
                it[engines] = new.engines
                it[fullSpeed] = new.fullSpeedOpen
            }
        }
    }
}
