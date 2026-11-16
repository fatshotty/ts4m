package org.tgstream.app

import android.app.Application

class TgStreamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: TgStreamApp
            private set
    }
}
