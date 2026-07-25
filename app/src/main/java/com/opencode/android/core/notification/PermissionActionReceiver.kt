package com.opencode.android.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.opencode.android.OpenCodeApplication
import com.opencode.android.R
import com.opencode.android.runtime.PermissionResponse
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class PermissionActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != RuntimeNotificationHelper.ACTION_PERMISSION_RESPONSE) return
        val sessionId = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_SESSION_ID) ?: return
        val permissionId = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_PERMISSION_ID) ?: return
        val responseValue = intent.getStringExtra(RuntimeNotificationHelper.EXTRA_PERMISSION_RESPONSE) ?: return
        val remember = intent.getBooleanExtra(RuntimeNotificationHelper.EXTRA_PERMISSION_REMEMBER, false)

        val response = PermissionResponse.entries.firstOrNull { it.apiValue == responseValue } ?: return
        val app = context.applicationContext as? OpenCodeApplication ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                // Everything below can throw: the local runtime may no longer be installed,
                // the permission may already have been answered in-app (4xx), or the network
                // may be down. An escaping exception here kills the whole process, so the tap
                // on the notification must never propagate one.
                val result =
                    runCatching {
                        // goAsync() only keeps the process alive for a short window, so a hung
                        // request must not outlive it.
                        withTimeout(RESPONSE_TIMEOUT_MS) {
                            val backend =
                                app.runtimeRegistry.selected.value
                                    ?: error("No OpenCode runtime is selected")
                            backend.respondToPermission(sessionId, permissionId, response, remember)
                        }
                    }

                result
                    .onSuccess { accepted ->
                        if (accepted) {
                            // Also cancels the notification and tells the chat screen to drop
                            // the card for this request.
                            runCatching { app.activityRepository.resolvePermission(permissionId) }
                        } else {
                            notifyFailure(app, appContext, sessionId)
                        }
                    }
                    .onFailure { error ->
                        Log.w(TAG, "Failed to answer permission $permissionId", error)
                        notifyFailure(app, appContext, sessionId)
                    }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    /**
     * Leaves the approval notification in place (so the request is not silently lost) and tells
     * the user the tap did not get through.
     */
    private fun notifyFailure(
        app: OpenCodeApplication,
        context: Context,
        sessionId: String,
    ) {
        runCatching {
            app.notifications.notifySessionError(
                sessionId,
                context.getString(R.string.notification_permission_failed),
            )
        }
    }

    companion object {
        private const val TAG = "PermissionAction"
        private const val RESPONSE_TIMEOUT_MS = 8_000L
        private val handler =
            CoroutineExceptionHandler { _, error ->
                Log.e(TAG, "Unhandled permission response failure", error)
            }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + handler)
    }
}
