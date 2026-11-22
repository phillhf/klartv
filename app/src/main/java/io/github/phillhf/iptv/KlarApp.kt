package io.github.phillhf.iptv

import android.app.Application
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.transfer.Transfers

class KlarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        I18n.init(this)
        Transfers.init(this)
    }
}
