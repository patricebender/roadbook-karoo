package io.roadbook.karoo

import android.app.Application
import timber.log.Timber

class RoadbookApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
