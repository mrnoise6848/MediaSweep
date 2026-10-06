package com.noise.mediasweep

import android.app.Application
import com.noise.mediasweep.core.di.AppContainer

class MediaSweepApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
