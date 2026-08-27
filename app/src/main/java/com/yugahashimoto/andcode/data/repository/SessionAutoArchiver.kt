package com.yugahashimoto.andcode.data.repository

import com.yugahashimoto.andcode.data.settings.AppPreferences
import com.yugahashimoto.andcode.runtime.RuntimeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.TimeUnit

/**
 * Keeps the chat drawer from growing without bound by archiving sessions that the user's settings
 * say are no longer worth keeping active: ones untouched for a configured number of days, and/or
 * the oldest ones once the active count passes a configured cap. Both rules are opt-in and off by
 * default (see [AppPreferences.autoArchiveStaleEnabled] and [AppPreferences.autoArchiveMaxSessionsEnabled]).
 */
class SessionAutoArchiver(
    private val registry: RuntimeRegistry,
    private val catalog: RuntimeCatalogRepository,
    private val activeSessionIds: () -> Set<String>,
    private val preferences: StateFlow<AppPreferences>,
    private val scope: CoroutineScope,
) {
    // Sessions an archive call was already issued for this process lifetime, so a session list that
    // has not yet caught up with a just-issued archive is not resubmitted on every recomputation.
    private val alreadyArchived = mutableSetOf<String>()

    fun start() {
        scope.launch {
            combine(catalog.allSessions, preferences) { sessions, prefs -> sessions to prefs }
                .collect { (sessions, prefs) -> maybeArchive(sessions, prefs) }
        }
    }

    private suspend fun maybeArchive(
        sessions: List<RuntimeSessionRef>,
        prefs: AppPreferences,
    ) {
        if (!prefs.autoArchiveStaleEnabled && !prefs.autoArchiveMaxSessionsEnabled) return

        // Never archive a chat that is actively running or that an archive call was already sent
        // for - the latter avoids resubmitting the same id every time the list recomputes before the
        // runtime's own listSessions() catches up and drops it.
        val active = activeSessionIds()
        val candidates = sessions.filterNot { it.session.id in active || it.session.id in alreadyArchived }

        val stale =
            if (prefs.autoArchiveStaleEnabled) {
                val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(prefs.autoArchiveStaleDays.toLong())
                candidates.filter { updatedAt(it) < cutoff }
            } else {
                emptyList()
            }

        val overflow =
            if (prefs.autoArchiveMaxSessionsEnabled) {
                candidates
                    .sortedByDescending(::updatedAt)
                    .drop(prefs.autoArchiveMaxSessions.coerceAtLeast(0))
            } else {
                emptyList()
            }

        val toArchive = (stale + overflow).distinctBy { it.session.id }
        if (toArchive.isEmpty()) return

        toArchive.forEach { alreadyArchived += it.session.id }
        val targets = registry.targets.value
        supervisorScope {
            toArchive
                .mapNotNull { ref -> targets.firstOrNull { it.id == ref.runtimeId }?.let { it to ref.session.id } }
                .map { (target, sessionId) -> async { runCatching { target.archiveSession(sessionId) } } }
                .forEach { it.await() }
        }
        catalog.refreshAllSessions()
    }

    private fun updatedAt(ref: RuntimeSessionRef): Long = ref.session.time.updated ?: ref.session.time.created
}
