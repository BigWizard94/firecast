package info.bigwizardmedia.firecast

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import info.bigwizardmedia.firecast.discovery.DiscoveryManager
import info.bigwizardmedia.firecast.server.MediaServer
import java.util.UUID

class FirecastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

/**
 * Tiny manual service locator (no DI framework needed at this size).
 * Initialized once from [FirecastApp].
 */
object ServiceLocator {
    private lateinit var appContext: Context

    val discovery: DiscoveryManager by lazy { DiscoveryManager(appContext) }
    val mediaServer: MediaServer by lazy { MediaServer() }

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("firecast", Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Stable per-install sender fingerprint used for receiver pairing memory. */
    fun fingerprint(): String {
        var fp = prefs.getString("fingerprint", null)
        if (fp == null) {
            fp = UUID.randomUUID().toString()
            prefs.edit().putString("fingerprint", fp).apply()
        }
        return fp
    }
}
