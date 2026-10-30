package app.postmortem

import android.content.Context
import app.postmortem.core.uci.ProcessFactory
import java.io.File

/** Stockfish ships as a native library, the one place an app may execute a file from. */
object EngineLocator {
    fun factory(context: Context): ProcessFactory {
        val binary = File(context.applicationInfo.nativeLibraryDir, BuildConfig.ENGINE_LIBRARY)
        return { ProcessBuilder(binary.path).start() }
    }
}
