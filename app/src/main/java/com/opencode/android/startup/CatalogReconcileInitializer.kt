package com.opencode.android.startup

import android.content.Context
import androidx.startup.Initializer
import com.opencode.android.OpenCodeApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class CatalogReconcileInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val app = context.applicationContext as OpenCodeApplication
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            app.catalogRepository.state.collectLatest { catalog ->
                if (catalog.health != null) {
                    app.preferences.reconcile(catalog.providers, catalog.agents)
                }
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
