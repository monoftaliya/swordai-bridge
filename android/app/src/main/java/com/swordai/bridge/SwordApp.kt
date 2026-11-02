package com.swordai.bridge

import android.app.Application
import com.swordai.bridge.service.Notifications

class SwordApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        AppGraph.init(this)
    }
}
