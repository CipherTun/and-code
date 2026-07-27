package com.yugahashimoto.androidcode.di

import com.yugahashimoto.androidcode.feature.activity.ActivityViewModel
import com.yugahashimoto.androidcode.feature.chat.ChatViewModel
import com.yugahashimoto.androidcode.feature.home.HomeViewModel
import com.yugahashimoto.androidcode.feature.schedule.ScheduleViewModel
import com.yugahashimoto.androidcode.feature.settings.SettingsViewModel
import com.yugahashimoto.androidcode.feature.workspace.WorkspaceViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

val viewModelModule =
    module {

        viewModel {
            ChatViewModel(
                draftRepo = get(),
            )
        }

        viewModel {
            WorkspaceViewModel(
                registry = get(),
                catalog = get(),
                localRuntimeManager = get(),
                localRuntimeController = get(),
                settings = get(),
                workspaceHostDir = File(androidContext().filesDir, "runtime/workspace"),
            )
        }

        viewModel {
            SettingsViewModel(
                catalog = get(),
                preferences = get(),
                credentials = get(),
                settings = get(),
                registry = get(),
            )
        }

        viewModel {
            ActivityViewModel(
                catalog = get(),
                activity = get(),
                registry = get(),
            )
        }

        viewModel {
            HomeViewModel(
                catalog = get(),
                preferences = get(),
            )
        }

        viewModel {
            ScheduleViewModel()
        }
    }
