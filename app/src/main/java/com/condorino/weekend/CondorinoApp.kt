package com.condorino.weekend

import android.app.Application
import com.condorino.weekend.di.AppContainer
import com.condorino.weekend.work.WeekendRefreshWorker

class CondorinoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Best-effort cache warming. Failing to schedule must never prevent the app from starting.
        runCatching { WeekendRefreshWorker.schedule(this) }
    }
}
