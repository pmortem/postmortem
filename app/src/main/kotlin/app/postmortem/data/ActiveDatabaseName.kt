package app.postmortem.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.postmortem.core.store.DatabaseName
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.databaseStore by preferencesDataStore(name = "database")

/** The name of the active database file, remembered across app restarts; `postmortem.sqlite` until the user changes it. */
class ActiveDatabaseName(
    private val context: Context,
) {
    private val key = stringPreferencesKey("active_database")

    /** Read from a background thread: the first read of the file takes a moment. */
    fun get(): String =
        runBlocking {
            context.databaseStore.data
                .map { it[key] ?: DatabaseName.DEFAULT }
                .first()
        }

    fun set(fileName: String) {
        runBlocking { context.databaseStore.edit { it[key] = fileName } }
    }
}
