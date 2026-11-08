package com.shreyas.wizsync.tv

import android.app.Application
import com.shreyas.wizsync.tv.store.PrefsStore
import com.shreyas.wizsync.tv.store.Settings
import com.shreyas.wizsync.tv.wiz.WizClient

/** Process-wide singletons: one [Settings] and one [WizClient] shared by the UI and the sync service. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        settings = Settings(PrefsStore(this))
        wiz = WizClient()
    }

    companion object {
        lateinit var settings: Settings
        lateinit var wiz: WizClient
    }
}
