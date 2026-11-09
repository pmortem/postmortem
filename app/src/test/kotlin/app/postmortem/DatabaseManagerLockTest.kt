package app.postmortem

import app.postmortem.core.store.GameLibrary
import app.postmortem.data.DatabaseControl
import app.postmortem.data.DatabaseManager
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `DatabaseManager` closes and reopens the database around its file actions, so every call into it must hold its lock: a call that
 * does not can run on a database being closed, or reopen the old file in the middle of a rename (#200).
 */
class DatabaseManagerLockTest {
    @Test
    fun everyLibraryAndDatabaseCallOfTheManagerHoldsItsLock() {
        val wanted =
            (GameLibrary::class.java.methods + DatabaseControl::class.java.methods)
                .map { it.name to it.parameterTypes.toList() }
                .toSet()
        val unlocked =
            DatabaseManager::class.java.declaredMethods
                .filter { (it.name to it.parameterTypes.toList()) in wanted }
                .filterNot { Modifier.isSynchronized(it.modifiers) }
                .map { it.name }
                .sorted()
        assertEquals(emptyList(), unlocked)
    }
}
