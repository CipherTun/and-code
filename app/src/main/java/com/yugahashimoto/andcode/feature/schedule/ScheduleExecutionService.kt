package com.yugahashimoto.andcode.feature.schedule

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yugahashimoto.andcode.AndCodeApplication
import com.yugahashimoto.andcode.MainActivity
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeEvent
import com.yugahashimoto.andcode.core.api.PromptRequest
import com.yugahashimoto.andcode.data.schedule.Schedule
import com.yugahashimoto.andcode.data.schedule.ScheduleRun
import com.yugahashimoto.andcode.data.schedule.ScheduleRunStatus
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import com.yugahashimoto.andcode.runtime.PermissionResponse
import com.yugahashimoto.andcode.runtime.RuntimeTarget
import com.yugahashimoto.andcode.runtime.RuntimeType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Executes a scheduled prompt in the foreground.
 *
 * Woken by an exact alarm (or by "Run now"), it boots the local runtime if needed, creates a
 * session, sends the prompt and watches both the event stream and the transcript until the run
 * finishes - then records the outcome, re-arms the next alarm and stops itself.
 */
class ScheduleExecutionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var app: AndCodeApplication
    private var inForeground = false
    private var executionJob: Job? = null

    /** Why the event stream stopped, when it stopped before the run settled. */
    @Volatile
    private var streamFailure: String? = null

    override fun onCreate() {
        super.onCreate()
        app = application as AndCodeApplication
        createChannel()
        // Android 14+ can still reject the foreground promotion here even though the start itself
        // was accepted. Bailing out is the only safe answer: a service that cannot enter the
        // foreground is killed with a crash by the system anyway.
        inForeground =
            runCatching {
                startForeground(NOTIFICATION_ID, notification(app.getString(R.string.schedule_notification_starting)))
            }.onFailure { error -> Log.w(TAG, "Could not enter the foreground", error) }
                .isSuccess
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID)
        if (scheduleId == null || !inForeground) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (executionJob?.isActive == true) return START_NOT_STICKY
        executionJob =
            scope.launch {
                try {
                    execute(scheduleId)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Holds a [com.yugahashimoto.andcode.core.runtime.RuntimeWorkTracker] lease for the whole run.
     *
     * This path never touches [com.yugahashimoto.andcode.data.repository.RuntimeActivityRepository]
     * - it drives the runtime directly - so without a lease of its own the wake lock would see no
     * work in flight and let the device suspend mid-run, freezing the proot agent process.
     */
    private suspend fun execute(scheduleId: String) {
        app.runtimeWork.withLease("schedule:$scheduleId") { executeWithRuntimeAwake(scheduleId) }
    }

    private suspend fun executeWithRuntimeAwake(scheduleId: String) {
        val schedule = app.scheduleRepository.schedule(scheduleId) ?: return
        if (!schedule.enabled) return
        // A previous run of the same schedule may still be in flight (e.g. a run-now while a
        // recurring alarm is pending). Do not stack sessions on top of each other.
        if (app.scheduleRepository.hasActiveRun(scheduleId)) return

        val target = app.runtimeRegistry.target(schedule.runtimeId)
        if (target == null) {
            recordSkipped(schedule, getString(R.string.schedule_runtime_unavailable))
            return
        }
        var activeRun: ScheduleRun? = null

        try {
            // Remote runtimes live on the user's PC and need no boot; local agents share the
            // PRoot Linux environment, which has to be running before the agent can start.
            if (target.type == RuntimeType.LOCAL && !ensureLocalRuntimeReady()) {
                recordSkipped(schedule, getString(R.string.schedule_runtime_not_ready))
                return
            }
            if (!connectWithRetry(target)) {
                recordSkipped(schedule, getString(R.string.schedule_runtime_connection_failed))
                return
            }
            val session =
                target.createSession(
                    title = schedule.displayName.ifBlank { null },
                    directory = schedule.workspacePath.takeIf(String::isNotBlank),
                )
            val run = app.scheduleRepository.recordRunStarted(schedule, session.id, target.id)
            activeRun = run
            updateForegroundNotification(app.getString(R.string.schedule_notification_running))
            runPrompt(target, schedule, run)
        } catch (error: Exception) {
            activeRun?.let { recordFailed(it, error.message ?: error.javaClass.simpleName) }
        } finally {
            // A cron schedule arms its next alarm when this run settles, whatever the outcome.
            app.scheduleManager.rescheduleAll()
        }
    }

    /**
     * Connects the runtime, giving a refused first attempt a few more tries.
     *
     * An alarm fires the moment the device wakes, which is when the agent is least likely to
     * answer: the local runtime reports Ready as soon as its process is up, before the HTTP server
     * accepts connections, and a remote one is reached over a network the device has only just
     * re-joined. A single refused health check used to skip the whole run.
     */
    private suspend fun connectWithRetry(target: RuntimeTarget): Boolean {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (target.connect().isSuccess) return true
            if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_DELAY_MS * (attempt + 1))
        }
        return false
    }

    /**
     * Sends the prompt and records what the run did.
     *
     * The run settles on whichever watcher sees it first: the event stream, which is immediate, or
     * the transcript poll, which keeps working after the stream drops.
     */
    private suspend fun runPrompt(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
    ) {
        val autoAccept = schedule.autoAcceptPermissions ?: app.settings.autoAcceptPermissions
        streamFailure = null
        // Subscribe before sending: fast runtimes can emit idle during sendMessage itself.
        val watcher: Deferred<ScheduleCompletion> =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                awaitStreamCompletion(target, schedule, run, autoAccept)
            }
        // The poll sleeps before its first read, so it only ever sees this prompt's own turn.
        val transcriptPoll: Deferred<ScheduleCompletion> = scope.async { pollForCompletion(target, run) }
        try {
            target.sendMessage(
                run.sessionId,
                PromptRequest(
                    text = schedule.prompt,
                    providerId = schedule.providerId,
                    modelId = schedule.modelId,
                    agent = schedule.agentId,
                ),
            )
            val completion =
                withTimeoutOrNull(COMPLETION_TIMEOUT_MS) {
                    select<ScheduleCompletion> {
                        watcher.onAwait { it }
                        transcriptPoll.onAwait { it }
                    }
                }
            if (completion == null) {
                // A stream that stopped early explains the silence better than the timeout does.
                recordFailed(run, streamFailure ?: getString(R.string.schedule_completion_timeout))
            } else {
                settle(target, schedule, run, completion)
            }
        } finally {
            watcher.cancel()
            transcriptPoll.cancel()
        }
    }

    /** Records the outcome, announcing it unless the user is already looking at this runtime. */
    private suspend fun settle(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
        completion: ScheduleCompletion,
    ) {
        val notifyUser = target.id != app.runtimeRegistry.selected.value?.id
        when (completion) {
            ScheduleCompletion.Completed -> {
                if (notifyUser) {
                    val title =
                        runCatching { target.session(run.sessionId).title }
                            .getOrDefault(schedule.displayName)
                    app.notifications.notifySessionComplete(run.sessionId, title, target.id)
                }
                recordCompleted(run)
            }
            is ScheduleCompletion.Failed -> {
                // Stopping the run by hand settles it as MessageAbortedError; the user made that
                // decision, so it is not announced as a failure.
                if (notifyUser && !completion.silent) {
                    app.notifications.notifySessionError(run.sessionId, completion.message, target.id)
                }
                recordFailed(run, completion.message)
            }
        }
    }

    /**
     * Waits for the shared Linux runtime when the schedule targets a local agent. Returns false
     * when the runtime is missing, broken or fails to come up in time.
     */
    private suspend fun ensureLocalRuntimeReady(): Boolean {
        val status = app.localRuntimeManager.status()
        if (status is LocalRuntimeStatus.Ready) return true
        if (status is LocalRuntimeStatus.NotInstalled || status is LocalRuntimeStatus.Broken) return false

        app.localRuntimeController.start()
        val ready =
            withTimeoutOrNull(LOCAL_RUNTIME_START_TIMEOUT_MS) {
                app.localRuntimeManager.state.first { it is LocalRuntimeStatus.Ready }
            }
        return ready != null
    }

    /**
     * Waits for the event stream to settle the run.
     *
     * A stream that stops before the run does is not itself a failed run - the agent keeps working
     * on the runtime - so this hands the outcome to [pollForCompletion] and suspends, leaving the
     * reason behind for the timeout message.
     */
    private suspend fun awaitStreamCompletion(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
        autoAccept: Boolean,
    ): ScheduleCompletion {
        val reason =
            try {
                watchForCompletion(target, schedule, run, autoAccept)?.let { return it }
                getString(R.string.schedule_event_stream_ended)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            }
        Log.w(TAG, "Event stream stopped before the run settled: $reason")
        streamFailure = reason
        awaitCancellation()
    }

    /**
     * Settles the run from its transcript, which survives what the event stream cannot: a doze
     * window, a runtime restart, a dropped socket. Without it a run whose `session.idle` was lost
     * failed on the completion timeout even though the agent had answered long before.
     */
    private suspend fun pollForCompletion(
        target: RuntimeTarget,
        run: ScheduleRun,
    ): ScheduleCompletion {
        var settling: String? = null
        while (true) {
            delay(TRANSCRIPT_POLL_INTERVAL_MS)
            val messages =
                runCatching { target.listMessages(run.sessionId) }
                    .onFailure { error -> Log.w(TAG, "Could not read the run transcript", error) }
                    .getOrNull()
                    ?: continue
            val newest = messages.lastOrNull()?.info
            val outcome = scheduleCompletionOf(messages)
            if (newest == null || outcome == null) {
                settling = null
                continue
            }
            // A turn can finish one message and open another (a tool step, a subagent hop). The
            // event stream knows the difference; the transcript only shows it a read later, so a
            // run counts as settled once the same finished message is still the newest one.
            val fingerprint = "${newest.id}:${newest.time.completed}:${newest.error?.name}"
            if (settling == fingerprint) return outcome
            settling = fingerprint
        }
    }

    /**
     * Streams events for the run's session until it settles, or returns null when the stream ends
     * without settling it.
     */
    private suspend fun watchForCompletion(
        target: RuntimeTarget,
        schedule: Schedule,
        run: ScheduleRun,
        autoAccept: Boolean,
    ): ScheduleCompletion? {
        val targetSessionId = run.sessionId
        val notifyUser = target.id != app.runtimeRegistry.selected.value?.id

        try {
            target.events().collect { event ->
                when (event) {
                    is OpenCodeEvent.SessionIdle -> {
                        if (event.sessionId == targetSessionId) {
                            throw CompletionSignal(ScheduleCompletion.Completed)
                        }
                    }
                    is OpenCodeEvent.SessionError -> {
                        if (event.sessionId == null || event.sessionId == targetSessionId) {
                            throw CompletionSignal(ScheduleCompletion.Failed(event.message, silent = event.isAbort))
                        }
                    }
                    is OpenCodeEvent.PermissionAsked -> {
                        if (event.request.sessionId != targetSessionId) return@collect
                        if (autoAccept) {
                            runCatching {
                                target.respondToPermission(
                                    event.request.sessionId,
                                    event.request.id,
                                    PermissionResponse.ONCE,
                                    remember = false,
                                )
                            }
                        } else if (notifyUser) {
                            app.notifications.notifyPermission(event.request, schedule.displayName, target.id)
                        }
                    }
                    is OpenCodeEvent.QuestionAsked -> {
                        if (event.request.sessionId == targetSessionId && notifyUser) {
                            app.notifications.notifyQuestion(event.request, schedule.displayName, target.id)
                        }
                    }
                    else -> Unit
                }
            }
        } catch (signal: CompletionSignal) {
            // The run settled; the event stream is drained.
            return signal.result
        }
        return null
    }

    private fun recordCompleted(run: ScheduleRun) {
        app.scheduleRepository.finishRun(run.id, ScheduleRunStatus.COMPLETED)
    }

    private fun recordFailed(
        run: ScheduleRun,
        error: String?,
    ) {
        app.scheduleRepository.finishRun(run.id, ScheduleRunStatus.FAILED, error = error)
    }

    private fun recordSkipped(
        schedule: Schedule,
        reason: String,
    ) {
        app.scheduleRepository.recordRunSkipped(schedule, reason)
    }

    private fun updateForegroundNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_schedules),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(text: String): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.schedule_notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    /** Thrown to unwind the event collector once the run has settled. */
    private class CompletionSignal(val result: ScheduleCompletion) : Exception()

    companion object {
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        private const val TAG = "ScheduleExecution"
        private const val CHANNEL_ID = "andcode_schedule_runs"
        private const val NOTIFICATION_ID = 4201
        private const val LOCAL_RUNTIME_START_TIMEOUT_MS = 5 * 60_000L
        private const val COMPLETION_TIMEOUT_MS = 30 * 60_000L
        private const val CONNECT_ATTEMPTS = 3
        private const val CONNECT_RETRY_DELAY_MS = 3_000L
        private const val TRANSCRIPT_POLL_INTERVAL_MS = 30_000L

        /**
         * Starts a run, returning false when the platform refused the foreground start.
         *
         * Android 12+ only lets a background app start a foreground service when it is exempt,
         * and an alarm grants that exemption only when it was scheduled exactly. Devices that
         * withhold the exact-alarm permission therefore wake us through an inexact alarm with no
         * exemption, and the start throws ForegroundServiceStartNotAllowedException - callers
         * have to handle the refusal instead of letting it crash the app.
         */
        fun start(
            context: Context,
            scheduleId: String,
        ): Boolean {
            val intent =
                Intent(context, ScheduleExecutionService::class.java).apply {
                    putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: Exception) {
                Log.w(TAG, "Foreground start refused for schedule $scheduleId", error)
                false
            }
        }
    }
}
