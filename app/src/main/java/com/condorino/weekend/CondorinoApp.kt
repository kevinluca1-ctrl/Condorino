package com.condorino.weekend

import android.app.Application
import com.condorino.weekend.di.AppContainer

class CondorinoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
