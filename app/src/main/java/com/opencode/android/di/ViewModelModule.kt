package com.opencode.android.di

import com.opencode.android.feature.activity.ActivityViewModel
import com.opencode.android.feature.chat.ChatViewModel
import com.opencode.android.feature.home.HomeViewModel
import com.opencode.android.feature.schedule.ScheduleViewModel
import com.opencode.android.feature.settings.SettingsViewModel
import com.opencode.android.feature.workspace.WorkspaceViewModel
import java.io.File
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {

    viewModel {
        ChatViewModel(
            draftRepo = get()
        )
    }

    viewModel {
        WorkspaceViewModel(
            registry = get(),
            catalog = get(),
            localRuntimeManager = get(),
            localRuntimeController = get(),
            settings = get(),
            workspaceHostDir = File(androidContext().filesDir, "runtime/workspace")
        )
    }

    viewModel {
        SettingsViewModel(
            catalog = get(),
            preferences = get(),
            credentials = get(),
            settings = get(),
            registry = get()
        )
    }

    viewModel {
        ActivityViewModel(
            catalog = get(),
            activity = get(),
            registry = get()
        )
    }

    viewModel {
        HomeViewModel(
            catalog = get(),
            preferences = get()
        )
    }

    viewModel {
        ScheduleViewModel()
    }
}
