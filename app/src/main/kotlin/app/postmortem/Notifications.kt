package app.postmortem

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.postmortem.core.queue.QueueEvent
import app.postmortem.core.queue.QueueState
import app.postmortem.core.queue.QueuedGame
import kotlin.math.roundToInt

/**
 * The notifications of the background queue: one that shows the progress while the queue works (the service's), one per finished or
 * failed game, and one when Android's daily limit holds the queue. Without the notification permission nothing is shown and the work
 * goes on just the same.
 */
object Notifications {
    const val PROGRESS_ID = 1
    private const val HELD_ID = 2
    private const val QUEUE_CHANNEL = "queue"
    private const val RESULTS_CHANNEL = "results"
    private const val PERCENT = 100.0

    /** Where a tap on a finished game's notification sends the screen: the id of the game to open. */
    const val EXTRA_OPEN_GAME = "open_game"

    fun channels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(QUEUE_CHANNEL, context.getString(R.string.notify_channel_queue), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RESULTS_CHANNEL,
                context.getString(R.string.notify_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** The service's notification: the game being analysed, how far, and how many wait; with Cancel for the running game. */
    fun progress(
        context: Context,
        games: List<QueuedGame>,
    ): Notification {
        val first = games.firstOrNull()
        val text =
            when {
                first == null -> {
                    context.getString(R.string.notify_queue_starting)
                }

                first.state == QueueState.PAUSED_HOT -> {
                    context.getString(R.string.notify_queue_hot)
                }

                else -> {
                    context.getString(
                        R.string.notify_queue_progress,
                        players(context, first),
                        if (first.total == 0) 0 else (PERCENT * first.done / first.total).roundToInt(),
                        games.size - 1,
                    )
                }
            }
        val builder =
            NotificationCompat
                .Builder(context, QUEUE_CHANNEL)
                .setSmallIcon(R.drawable.ic_tab_analyse)
                .setContentTitle(context.getString(R.string.notify_queue_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context, null))
        if (first != null) {
            builder.setProgress(first.total, first.done, false)
            val cancel =
                Intent(context, AnalysisService::class.java)
                    .setAction(AnalysisService.ACTION_CANCEL)
                    .putExtra(AnalysisService.EXTRA_ID, first.id)
            builder.addAction(
                0,
                context.getString(R.string.notify_cancel),
                PendingIntent.getService(
                    context,
                    first.id.toInt(),
                    cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        return builder.build()
    }

    fun showProgress(
        context: Context,
        games: List<QueuedGame>,
    ) = post(context, PROGRESS_ID, progress(context, games))

    /** "Analysed: White vs Black · accuracy 82 / 74" (a tap opens it), or "Could not analyse" with the reason. */
    fun finished(
        context: Context,
        event: QueueEvent,
    ) {
        val players = players(context, event.game)
        val builder =
            NotificationCompat
                .Builder(context, RESULTS_CHANNEL)
                .setSmallIcon(R.drawable.ic_tab_analyse)
                .setAutoCancel(true)
        when (event) {
            is QueueEvent.Finished -> {
                builder
                    .setContentTitle(context.getString(R.string.notify_done_title, players))
                    .setContentText(
                        context.getString(
                            R.string.notify_done_text,
                            event.whiteAccuracy?.roundToInt()?.toString() ?: context.getString(R.string.summary_none),
                            event.blackAccuracy?.roundToInt()?.toString() ?: context.getString(R.string.summary_none),
                        ),
                    ).setContentIntent(openApp(context, event.gameId))
            }

            is QueueEvent.Failed -> {
                builder
                    .setContentTitle(context.getString(R.string.notify_failed_title, players))
                    .setContentText(event.message)
                    .setContentIntent(openApp(context, null))
            }
        }
        // one notification per game: its queue id is free once it has left the queue
        post(context, RESULTS_BASE + event.game.id.toInt(), builder.build())
    }

    /** The queue waits because Android's daily limit for background work was reached. */
    fun held(context: Context) {
        val notification =
            NotificationCompat
                .Builder(context, QUEUE_CHANNEL)
                .setSmallIcon(R.drawable.ic_tab_analyse)
                .setContentTitle(context.getString(R.string.notify_queue_title))
                .setContentText(context.getString(R.string.notify_queue_held))
                .setContentIntent(openApp(context, null))
                .setAutoCancel(true)
                .build()
        post(context, HELD_ID, notification)
    }

    private fun players(
        context: Context,
        game: QueuedGame,
    ): String =
        context.getString(
            R.string.notify_players,
            game.white ?: context.getString(R.string.summary_white),
            game.black ?: context.getString(R.string.summary_black),
        )

    private fun openApp(
        context: Context,
        gameId: Long?,
    ): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (gameId != null) putExtra(EXTRA_OPEN_GAME, gameId) }
        return PendingIntent.getActivity(
            context,
            gameId?.toInt() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Shows [notification] when the user lets the app post notifications; otherwise nothing is shown. */
    private fun post(
        context: Context,
        id: Int,
        notification: Notification,
    ) {
        val allowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(context).notify(id, notification)
    }

    private const val RESULTS_BASE = 1000
}
