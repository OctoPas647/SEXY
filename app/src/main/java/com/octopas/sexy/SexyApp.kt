package com.octopas.sexy

import android.app.Application
import com.octopas.sexy.data.AppSettings
import com.octopas.sexy.data.ChatRepository
import com.octopas.sexy.data.TailscaleMonitor

class SexyApp : Application() {
    lateinit var settings: AppSettings
        private set
    /** Chats: on the PC in LM Studio's folder, copied to the phone only if that setting is on. */
    lateinit var chats: ChatRepository
        private set
    lateinit var tailscale: TailscaleMonitor
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        chats = ChatRepository(this, settings)
        tailscale = TailscaleMonitor(this)
    }
}
