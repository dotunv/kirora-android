package io.kirora.demo

import android.app.Application

class DemoApplication : Application() {
    lateinit var store: DemoStore
        private set

    override fun onCreate() {
        super.onCreate()
        // Created with the process so a push or a tap that starts the app finds the SDK running.
        store = DemoStore(this)
    }
}
