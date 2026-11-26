package com.octopas.sexy

import android.app.Application
import com.octopas.sexy.data.AppSettings
import com.octopas.sexy.data.ChatStore
import com.octopas.sexy.data.TailscaleMonitor

class SexyApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var chats: ChatStore
        private set
    lateinit var tailscale: TailscaleMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        chats = ChatStore(this)
        tailscale = TailscaleMonitor(this)
    }
}
