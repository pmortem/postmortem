package app.postmortem

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while the background queue has games, with a notification that shows how far it is ("Analysing White vs Black
 * · 34 % · 2 waiting") and a Cancel button. It stops itself when the queue is empty. Android's daily limit for this kind of service
 * ([onTimeout]) holds the queue until the app is opened again; it never crashes it.
 */
class AnalysisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    private val app: PostmortemApp get() = application as PostmortemApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        app.startQueue()
        ServiceCompat.startForeground(this, Notifications.PROGRESS_ID, Notifications.progress(this, app.queue.progress.value), dataSync())
        if (intent?.action == ACTION_CANCEL) app.queue.cancel(intent.getLongExtra(EXTRA_ID, -1))
        if (watching == null) {
            watching =
                scope.launch {
                    app.queue.progress.collect { games ->
                        if (games.isEmpty()) {
                            ServiceCompat.stopForeground(this@AnalysisService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            Notifications.showProgress(this@AnalysisService, games)
                        }
                    }
                }
        }
        return START_STICKY
    }

    private fun dataSync(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    /** Android 15's daily limit for data sync services: the queue waits for the app to be opened, and says so. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        app.held.value = true
        Notifications.held(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "app.postmortem.CANCEL"
        const val EXTRA_ID = "id"
    }
}
