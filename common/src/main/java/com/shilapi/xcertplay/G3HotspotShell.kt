package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.provider.Settings
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlin.system.exitProcess

/** Only the explicit setup button may offer the ADB key or grant these app-scoped permissions. */
internal class G3HotspotShellClient internal constructor(
    private val apk: String,
    private val packageName: String,
    private val permissionsReady: () -> Boolean,
    private val open: () -> G3HotspotShellConnection,
    private val requestTimeoutMillis: Long = 15_000,
) {
    constructor(context: Context) : this(
        context.applicationInfo.sourceDir,
        context.packageName,
        { Settings.System.canWrite(context) &&
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED },
        { G3HotspotLocalAdb(LocalAdb(AdbKeys.load(context), port = 5050)) },
    )

    fun authorize(): Boolean {
        if (packageName != PACKAGE) return false
        return withConnection(timeoutMillis = 75_000) { connection ->
            if (connection.connect(mayAsk = true) != LocalAdb.Access.READY) return@withConnection false
            if (!G3HotspotShellProtocol.accepted(connection.shell(WRITE_SETTINGS_COMMAND, SHELL_TIMEOUT))) {
                return@withConnection false
            }
            if (!G3HotspotShellProtocol.accepted(connection.shell(SECURE_SETTINGS_COMMAND, SHELL_TIMEOUT))) {
                return@withConnection false
            }
            permissionsReady()
        } ?: false
    }

    fun readConfiguration(): WifiConfiguration? {
        if (packageName != PACKAGE) return null
        return withConnection { connection ->
            if (connection.connect(mayAsk = false) != LocalAdb.Access.READY) return@withConnection null
            val command = G3HotspotShellProtocol.command(apk, "read") ?: return@withConnection null
            G3HotspotShellProtocol.readReply(connection.shell(command, SHELL_TIMEOUT))
        }
    }

    fun writeConfiguration(config: WifiConfiguration): Boolean {
        if (packageName != PACKAGE) return false
        if (G3HotspotValidation.error(config.SSID.orEmpty(), config.preSharedKey.orEmpty()) != null) return false
        val payload = G3HotspotShellProtocol.encode(config) ?: return false
        val command = G3HotspotShellProtocol.command(apk, "write", payload) ?: return false
        return withConnection { connection ->
            if (connection.connect(mayAsk = false) != LocalAdb.Access.READY) return@withConnection false
            G3HotspotShellProtocol.accepted(connection.shell(command, SHELL_TIMEOUT))
        } ?: false
    }

    private fun <T> withConnection(
        timeoutMillis: Long = requestTimeoutMillis,
        action: (G3HotspotShellConnection) -> T,
    ): T? {
        if (requestTimeoutMillis !in 1..15_000) return null
        return try {
            open().use { connection ->
                val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "g3-hotspot-adb-deadline").apply { isDaemon = true }
                }
                val expired = AtomicBoolean(false)
                val deadline = timer.schedule({ expired.set(true); connection.cancel() }, timeoutMillis, TimeUnit.MILLISECONDS)
                try { action(connection).takeUnless { expired.get() } }
                finally { deadline.cancel(false); timer.shutdownNow() }
            }
        } catch (_: Exception) { null }
    }

    private companion object {
        const val PACKAGE = "com.techeek.diplay.g3"
        const val SHELL_TIMEOUT = 12_000
        const val WRITE_SETTINGS_COMMAND =
            "appops set com.techeek.diplay.g3 WRITE_SETTINGS allow && printf 'DIPLAY_G3_CONFIG_V1|OK\\n'"
        const val SECURE_SETTINGS_COMMAND =
            "pm grant com.techeek.diplay.g3 android.permission.WRITE_SECURE_SETTINGS && printf 'DIPLAY_G3_CONFIG_V1|OK\\n'"
    }
}

internal interface G3HotspotShellConnection : Closeable {
    fun connect(mayAsk: Boolean): LocalAdb.Access
    fun shell(command: String, timeoutMillis: Int): String?
    fun cancel()
}

private class G3HotspotLocalAdb(private val adb: LocalAdb) : G3HotspotShellConnection {
    override fun connect(mayAsk: Boolean) = adb.connect(mayAsk)
    override fun shell(command: String, timeoutMillis: Int) = adb.shell(command, timeoutMillis)
    override fun cancel() = adb.cancelPendingOperations()
    override fun close() = adb.close()
}

/** Credentials are carried only in the process request/reply, never a file or diagnostic. */
internal object G3HotspotShellProtocol {
    const val HEADER = "DIPLAY_G3_CONFIG_V1"
    private const val MAX_BYTES = 16_384
    private const val FRAME_MAGIC = 0x47335031 // G3P1
    private const val FRAME_OVERHEAD = 12 // version, exact Parcel length, CRC32
    private const val MAX_ENCODED = ((MAX_BYTES + FRAME_OVERHEAD) * 4 + 2) / 3
    private const val MAX_OUTPUT = MAX_ENCODED + 4096
    private const val MAIN = "com.shilapi.xcertplay.G3HotspotConfigMain"
    private val payloadPattern = Regex("[A-Za-z0-9_-]+")

    fun command(apk: String, action: String, payload: String? = null): String? {
        if (!apk.startsWith('/') || apk.length > 4096 || apk.any { it.isISOControl() }) return null
        if (action == "read" && payload == null) return prefix(apk) + " read"
        if (action == "write" && payload != null && validPayload(payload)) return prefix(apk) + " write $payload"
        return null
    }

    private fun prefix(apk: String) =
        "CLASSPATH='${apk.replace("'", "'\"'\"'")}' app_process /system/bin $MAIN"

    fun encode(config: WifiConfiguration): String? = runCatching {
        val parcel = Parcel.obtain()
        try {
            config.writeToParcel(parcel, 0)
            if (parcel.hasFileDescriptors()) return null
            val bytes = parcel.marshall()
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
            val frame = ByteBuffer.allocate(bytes.size + FRAME_OVERHEAD)
                .putInt(FRAME_MAGIC).putInt(bytes.size).put(bytes).putInt(checksum(bytes)).array()
            Base64.getUrlEncoder().withoutPadding().encodeToString(frame)
        } finally { parcel.recycle() }
    }.getOrNull()

    fun decode(payload: String): WifiConfiguration? {
        if (!validPayload(payload)) return null
        return runCatching {
            val frameBytes = Base64.getUrlDecoder().decode(payload)
            if (frameBytes.size !in (FRAME_OVERHEAD + 1)..(MAX_BYTES + FRAME_OVERHEAD) ||
                Base64.getUrlEncoder().withoutPadding().encodeToString(frameBytes) != payload) return null
            // Frame checks precede Android's Creator. They also reject tails ignored by some Parcel
            // implementations without assuming OEM fields remain byte-identical after recreation.
            val frame = ByteBuffer.wrap(frameBytes)
            if (frame.int != FRAME_MAGIC) return null
            val size = frame.int
            if (size !in 1..MAX_BYTES || frameBytes.size != size + FRAME_OVERHEAD) return null
            val bytes = ByteArray(size)
            frame.get(bytes)
            if (frame.int != checksum(bytes)) return null
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val creator = WifiConfiguration::class.java.getField("CREATOR").get(null) as Parcelable.Creator<*>
                val result = creator.createFromParcel(parcel) as? WifiConfiguration
                result?.takeIf { parcel.dataAvail() == 0 && !parcel.hasFileDescriptors() }
            } finally { parcel.recycle() }
        }.getOrNull()
    }

    private fun validPayload(value: String) = value.length in 1..MAX_ENCODED &&
        value.length % 4 != 1 && payloadPattern.matches(value)

    private fun checksum(bytes: ByteArray) = CRC32().apply { update(bytes) }.value.toInt()

    private fun reply(output: String?): String? {
        if (output == null || output.length > MAX_OUTPUT) return null
        val protocolLines = output.lineSequence().map(String::trim).filter { it.startsWith("$HEADER|") }.toList()
        return protocolLines.singleOrNull()?.removePrefix("$HEADER|")
    }

    fun readReply(output: String?): WifiConfiguration? = reply(output)?.let(::decode)
    fun accepted(output: String?) = reply(output) == "OK"
}

/** A one-shot, approved-ADB shell process. No ActivityThread, system context, or general command API. */
object G3HotspotConfigMain {
    @JvmStatic fun main(args: Array<String>) {
        Thread({ Thread.sleep(10_000); exitProcess(2) }, "g3-hotspot-config-deadline")
            .apply { isDaemon = true; start() }
        val answer = try {
            if (Process.myUid() != 2000 || Build.VERSION.SDK_INT != 28) "ERROR"
            else execute(args, ::wifiService)
        } catch (_: Throwable) { "ERROR" }
        println("${G3HotspotShellProtocol.HEADER}|$answer")
        exitProcess(0)
    }

    internal fun execute(args: Array<String>, service: () -> Any): String {
        if (args.contentEquals(arrayOf("read"))) {
            val wifi = service()
            val config = wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
                ?: return "ERROR"
            return G3HotspotShellProtocol.encode(config) ?: "ERROR"
        }
        if (args.size != 2 || args[0] != "write") return "ERROR"
        val config = G3HotspotShellProtocol.decode(args[1]) ?: return "ERROR"
        if (G3HotspotValidation.error(config.SSID.orEmpty(), config.preSharedKey.orEmpty()) != null) return "ERROR"
        val wifi = service()
        return if (wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java, String::class.java)
            .invoke(wifi, config, "com.android.shell") == true) "OK" else "ERROR"
    }

    private fun wifiService(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "wifi") as? IBinder ?: throw IllegalStateException()
        return Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: throw IllegalStateException()
    }
}
