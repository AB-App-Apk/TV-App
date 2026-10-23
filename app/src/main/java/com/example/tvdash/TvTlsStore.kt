package com.example.tvdash

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.tvlink.TvTls
import javax.net.ssl.SSLContext

/**
 * TV Dash's TLS identity: one self-signed key and certificate, made on the first start and kept in this app's private
 * storage. It never leaves the TV. The phone pins its fingerprint, so it must stay the same: if TV Dash is uninstalled
 * or its data is cleared, a new one is made and every phone has to verify the TV again.
 */
object TvTlsStore {
    private const val TAG = "TvTlsStore"
    private const val PREFS = "tvdash_tls"
    private const val KEY = "key"
    private const val CERT = "cert"

    private var cachedIdentity: TvTls.Identity? = null
    private var cachedContext: SSLContext? = null

    /** Loads the saved identity, or makes and saves a new one. Never call it on the main thread the first time. */
    @Synchronized
    fun identity(ctx: Context): TvTls.Identity {
        cachedIdentity?.let { return it }
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedKey = prefs.getString(KEY, null)
        val savedCert = prefs.getString(CERT, null)
        if (savedKey != null && savedCert != null) {
            try {
                val restored = TvTls.restore(Base64.decode(savedKey, Base64.NO_WRAP), Base64.decode(savedCert, Base64.NO_WRAP))
                cachedIdentity = restored
                return restored
            } catch (e: Exception) {
                Log.w(TAG, "The saved certificate is unreadable; making a new one", e)
            }
        }
        val fresh = TvTls.generate()
        val saved = prefs.edit()
            .putString(KEY, Base64.encodeToString(fresh.privateKey.encoded, Base64.NO_WRAP))
            .putString(CERT, Base64.encodeToString(fresh.certificate.encoded, Base64.NO_WRAP))
            .commit()
        if (!saved) Log.w(TAG, "The new certificate could not be saved; phones must verify the TV again after a restart")
        cachedIdentity = fresh
        return fresh
    }

    /** The context the TV's HTTPS listener uses. */
    @Synchronized
    fun serverContext(ctx: Context): SSLContext {
        cachedContext?.let { return it }
        return TvTls.serverContext(identity(ctx)).also { cachedContext = it }
    }

    /** The short code the dashboard shows and the phone asks the person to compare. */
    fun shortCode(ctx: Context): String = identity(ctx).shortCode
}
