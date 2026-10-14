package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.Settings
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicInteger

/** Kept only in the editing UI. Never put this object in diagnostics or app preferences. */
internal class G3HotspotSnapshot(val ssid: String, val password: String, val active: Boolean) {
    override fun toString() = "G3HotspotSnapshot(credentials=redacted, active=$active)"
}

internal sealed class G3HotspotReadResult {
    class Success(val snapshot: G3HotspotSnapshot) : G3HotspotReadResult() {
        override fun toString() = "G3HotspotReadResult.Success(credentials=redacted)"
    }
    data class Failure(val status: G3HotspotStatus) : G3HotspotReadResult()
}

internal enum class G3HotspotStatus {
    APPLIED, INVALID_SSID, INVALID_PASSWORD, NEEDS_WRITE_SETTINGS, NEEDS_WRITE_SECURE_SETTINGS,
    RESTART_CONFIRMATION_REQUIRED, BUSY, UNSUPPORTED, READ_FAILED, APPLY_FAILED
}

/** No credentials or exception messages are carried into user-facing results. */
internal data class G3HotspotApplyResult(val status: G3HotspotStatus, val rollbackSucceeded: Boolean? = null)

internal object G3HotspotValidation {
    fun error(ssid: String, password: String): G3HotspotStatus? {
        if (ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size > 32 ||
            ssid.any { it == '|' || it.isISOControl() || it == '\u2028' || it == '\u2029' } || hasUnpairedSurrogate(ssid)) {
            return G3HotspotStatus.INVALID_SSID
        }
        // OEM stores SSID|password. Its delimiter cannot appear in either component.
        if (password.length !in 8..63 || password.any { it !in ' '..'~' || it == '|' }) {
            return G3HotspotStatus.INVALID_PASSWORD
        }
        return null
    }

    private fun hasUnpairedSurrogate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i++]
            if (Character.isHighSurrogate(c)) {
                if (i >= value.length || !Character.isLowSurrogate(value[i++])) return true
            } else if (Character.isLowSurrogate(c)) return true
        }
        return false
    }
}

/**
 * Android 9 D21 ordinary SoftAP only. Call load/apply on a worker thread, only after explicit
 * user action. Does not start a disabled AP, change client Wi-Fi, or grant its own permissions.
 * Manifest needs ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, WRITE_SETTINGS, WRITE_SECURE_SETTINGS.
 * WRITE_SETTINGS app-op and an externally authorized WRITE_SECURE_SETTINGS grant are required.
 */
internal class G3HotspotController internal constructor(private val backend: G3HotspotBackend) {
    constructor(context: Context) : this(G3HotspotAndroidBackend(context.applicationContext))

    @Synchronized fun load(): G3HotspotReadResult {
        if (!backend.supported) return G3HotspotReadResult.Failure(G3HotspotStatus.UNSUPPORTED)
        return try {
            val state = backend.state()
            if (state != G3HotspotState.ENABLED && state != G3HotspotState.DISABLED) {
                G3HotspotReadResult.Failure(G3HotspotStatus.BUSY)
            } else {
                val config = backend.configuration()
                val ssid = config?.SSID
                if (ssid == null) G3HotspotReadResult.Failure(G3HotspotStatus.READ_FAILED)
                else G3HotspotReadResult.Success(G3HotspotSnapshot(ssid, config.preSharedKey.orEmpty(),
                    state == G3HotspotState.ENABLED))
            }
        } catch (_: Exception) { G3HotspotReadResult.Failure(G3HotspotStatus.READ_FAILED) }
    }

    @Synchronized fun apply(ssid: String, password: String, restartActive: Boolean = false): G3HotspotApplyResult {
        G3HotspotValidation.error(ssid, password)?.let { return G3HotspotApplyResult(it) }
        if (!backend.supported) return G3HotspotApplyResult(G3HotspotStatus.UNSUPPORTED)
        // Check both permissions before any cache, radio or configuration mutation.
        try {
            if (!backend.canWriteSettings()) return G3HotspotApplyResult(G3HotspotStatus.NEEDS_WRITE_SETTINGS)
            if (!backend.canWriteSecureSettings()) return G3HotspotApplyResult(G3HotspotStatus.NEEDS_WRITE_SECURE_SETTINGS)
        } catch (_: Exception) { return G3HotspotApplyResult(G3HotspotStatus.READ_FAILED) }
        val previous: WifiConfiguration
        val previousCache: String?
        val wasActive: Boolean
        try {
            val state = backend.state()
            if (state != G3HotspotState.ENABLED && state != G3HotspotState.DISABLED) {
                return G3HotspotApplyResult(G3HotspotStatus.BUSY)
            }
            wasActive = state == G3HotspotState.ENABLED
            previous = copyConfiguration(backend.configuration() ?: return G3HotspotApplyResult(G3HotspotStatus.READ_FAILED))
            // A missing or stale factory cache can make the OEM regenerate or overwrite credentials
            // on restart. Restore a coherent cache for the actual old config rather than that hazard.
            val previousSsid = previous.SSID ?: return G3HotspotApplyResult(G3HotspotStatus.READ_FAILED)
            val previousPassword = previous.preSharedKey ?: return G3HotspotApplyResult(G3HotspotStatus.READ_FAILED)
            if (G3HotspotValidation.error(previousSsid, previousPassword) != null) {
                return G3HotspotApplyResult(G3HotspotStatus.READ_FAILED)
            }
            val coherentCache = "$previousSsid|$previousPassword"
            previousCache = backend.cache().takeIf { it == coherentCache } ?: coherentCache
        } catch (_: Exception) { return G3HotspotApplyResult(G3HotspotStatus.READ_FAILED) }

        val desired = copyConfiguration(previous).apply {
            SSID = ssid
            preSharedKey = password
            allowedKeyManagement.clear()
            allowedKeyManagement.set(4) // Android 9 hotspot WPA2_PSK; not client WPA_PSK (1).
        }
        val desiredCache = "$ssid|$password"
        val previousState = if (wasActive) G3HotspotState.ENABLED else G3HotspotState.DISABLED
        // An unchanged running configuration can be verified and have its factory cache repaired
        // without dropping the radio or asking for a restart that has no purpose.
        if (sameCredentials(previous, desired)) {
            return try {
                if (backend.cache() != desiredCache) check(backend.writeCache(desiredCache))
                check(backend.awaitConfiguration { matches(desired, desiredCache) })
                check(backend.state() == previousState)
                G3HotspotApplyResult(G3HotspotStatus.APPLIED)
            } catch (_: Exception) {
                val restored = runCatching {
                    backend.writeCache(previousCache) && backend.awaitConfiguration { matches(previous, previousCache) } &&
                        backend.state() == previousState
                }.getOrDefault(false)
                G3HotspotApplyResult(G3HotspotStatus.APPLY_FAILED, restored)
            }
        }
        if (wasActive && !restartActive) return G3HotspotApplyResult(G3HotspotStatus.RESTART_CONFIRMATION_REQUIRED)
        if (wasActive && !backend.restartSupported) return G3HotspotApplyResult(G3HotspotStatus.UNSUPPORTED)
        try {
            if (wasActive) {
                check(backend.setEnabled(false))
                check(backend.awaitState(G3HotspotState.DISABLED))
            }
            check(backend.writeCache(desiredCache))
            check(backend.setConfiguration(desired))
            check(backend.awaitConfiguration { matches(desired, desiredCache) })
            if (wasActive) {
                check(backend.setEnabled(true))
                check(backend.awaitState(G3HotspotState.ENABLED))
                // The factory helper may reapply credentials during restart; read back afterward.
                check(backend.awaitConfiguration { matches(desired, desiredCache) })
            } else check(backend.state() == G3HotspotState.DISABLED)
            return G3HotspotApplyResult(G3HotspotStatus.APPLIED)
        } catch (_: Exception) {
            return G3HotspotApplyResult(G3HotspotStatus.APPLY_FAILED, rollback(previous, previousCache, wasActive))
        }
    }

    private fun matches(expected: WifiConfiguration, expectedCache: String?): Boolean {
        val actual = backend.configuration() ?: return false
        return sameCredentials(actual, expected) && sameRadioConfiguration(actual, expected) &&
            backend.cache() == expectedCache
    }

    private fun sameCredentials(first: WifiConfiguration, second: WifiConfiguration) =
        first.SSID == second.SSID && first.preSharedKey == second.preSharedKey &&
            first.allowedKeyManagement == second.allowedKeyManagement

    private fun sameRadioConfiguration(first: WifiConfiguration, second: WifiConfiguration): Boolean =
        listOf("apBand", "apChannel").all { name ->
            val field = WifiConfiguration::class.java.getField(name)
            field.getInt(first) == field.getInt(second)
        }

    private fun rollback(previous: WifiConfiguration, previousCache: String?, wasActive: Boolean): Boolean {
        // Attempt every restoration step even if an earlier operation fails.
        var restored = true
        fun attempt(action: () -> Boolean) {
            if (!runCatching(action).getOrDefault(false)) restored = false
        }
        attempt {
            if (backend.state() == G3HotspotState.DISABLED) true
            else backend.setEnabled(false) && backend.awaitState(G3HotspotState.DISABLED)
        }
        attempt { backend.writeCache(previousCache) }
        attempt { backend.setConfiguration(previous) }
        attempt { backend.awaitConfiguration { matches(previous, previousCache) } }
        attempt {
            val expected = if (wasActive) G3HotspotState.ENABLED else G3HotspotState.DISABLED
            if (backend.state() == expected) true
            else backend.setEnabled(wasActive) && backend.awaitState(expected)
        }
        attempt { backend.awaitConfiguration { matches(previous, previousCache) } }
        return restored
    }

    private fun copyConfiguration(config: WifiConfiguration): WifiConfiguration {
        val parcel = Parcel.obtain()
        return try {
            config.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val creator = WifiConfiguration::class.java.getField("CREATOR").get(null) as Parcelable.Creator<*>
            creator.createFromParcel(parcel) as WifiConfiguration
        } finally { parcel.recycle() }
    }
}

internal enum class G3HotspotState { DISABLED, ENABLED, BUSY }

internal interface G3HotspotBackend {
    val supported: Boolean
    val restartSupported: Boolean get() = supported
    fun canWriteSettings(): Boolean
    fun canWriteSecureSettings(): Boolean
    fun configuration(): WifiConfiguration?
    fun setConfiguration(config: WifiConfiguration): Boolean
    fun cache(): String?
    fun writeCache(value: String?): Boolean
    fun state(): G3HotspotState
    fun setEnabled(enabled: Boolean): Boolean
    fun awaitState(expected: G3HotspotState): Boolean
    fun awaitConfiguration(matches: () -> Boolean): Boolean = matches()
}

private class G3HotspotAndroidBackend(private val context: Context) : G3HotspotBackend {
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    // Only uses already-approved local ADB access; authorization belongs to the explicit UI action.
    private val shellClient by lazy { G3HotspotShellClient(context) }
    private val methods = if (Build.VERSION.SDK_INT == 28 && wifi != null) runCatching {
        val manager = wifi ?: throw NoSuchMethodException()
        val api = manager.javaClass
        G3HotspotMethods(api.getMethod("getWifiApConfiguration"),
            api.getMethod("setWifiApConfiguration", WifiConfiguration::class.java),
            api.getMethod("getWifiApState"))
    }.getOrNull() else null
    // These are the exact Android 9 D21 ConnectivityManager service signatures. In this firmware
    // ordinary callers are authorized through WRITE_SETTINGS, using their own package identity.
    private val tethering = if (Build.VERSION.SDK_INT == 28) runCatching {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw NoSuchMethodException()
        val service = ConnectivityManager::class.java.getDeclaredField("mService")
            .apply { isAccessible = true }.get(connectivity) ?: throw NoSuchMethodException()
        G3TetheringMethods(service,
            service.javaClass.getMethod("startTethering", Int::class.javaPrimitiveType,
                ResultReceiver::class.java, Boolean::class.javaPrimitiveType, String::class.java),
            service.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType, String::class.java))
    }.getOrNull() else null
    private var startResult = AtomicInteger(-1)
    override val supported get() = methods != null
    override val restartSupported get() = tethering != null
    override fun canWriteSettings() = Settings.System.canWrite(context)
    override fun canWriteSecureSettings() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    override fun configuration(): WifiConfiguration? =
        runCatching { methods!!.get.invoke(wifi) as? WifiConfiguration }.getOrNull()
            ?: shellClient.readConfiguration()
    override fun setConfiguration(config: WifiConfiguration): Boolean =
        runCatching { methods!!.set.invoke(wifi, config) == true }.getOrDefault(false) ||
            shellClient.writeConfiguration(config)
    override fun cache(): String? = Settings.Global.getString(context.contentResolver, CACHE_KEY)
    override fun writeCache(value: String?) = Settings.Global.putString(context.contentResolver, CACHE_KEY, value)
    override fun state() = when (methods!!.state.invoke(wifi) as Int) {
        11 -> G3HotspotState.DISABLED
        13 -> G3HotspotState.ENABLED
        else -> G3HotspotState.BUSY
    }
    override fun setEnabled(enabled: Boolean): Boolean {
        val api = tethering ?: return false
        val response = AtomicInteger(-1)
        startResult = response
        if (enabled) {
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    response.set(resultCode)
                }
            }
            api.start.invoke(api.service, 0, receiver, false, context.packageName)
        } else api.stop.invoke(api.service, 0, context.packageName)
        return true // Request accepted; awaitState verifies actual radio completion.
    }
    override fun awaitState(expected: G3HotspotState): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        do {
            if (expected == G3HotspotState.ENABLED && startResult.get() > 0) return false
            if (state() == expected) return true
            Thread.sleep(200)
        } while (SystemClock.elapsedRealtime() < deadline)
        return state() == expected
    }

    override fun awaitConfiguration(matches: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            if (matches()) return true
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        return matches()
    }

    private class G3HotspotMethods(val get: Method, val set: Method, val state: Method)
    private class G3TetheringMethods(val service: Any, val start: Method, val stop: Method)
    private companion object { const val CACHE_KEY = "hotspot_name_and_pass" }
}
