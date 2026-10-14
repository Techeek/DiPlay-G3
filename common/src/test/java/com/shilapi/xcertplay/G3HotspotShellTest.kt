package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import com.shilapi.xcertplay.adb.LocalAdb
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class G3HotspotShellTest {
    @Test fun fullParcelRoundTripKeepsRadioAndSecurityFields() {
        val original = configuration()
        val encoded = G3HotspotShellProtocol.encode(original)!!
        assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
        val actual = G3HotspotShellProtocol.decode(encoded)!!
        assertConfiguration(original, actual)
    }

    @Test fun apkPathIsQuotedAndPayloadNeverBecomesShellSyntax() {
        val apk = "/data/app/owner's \$(ignored); name/base.apk"
        assertEquals(
            "CLASSPATH='/data/app/owner'\"'\"'s \$(ignored); name/base.apk' app_process /system/bin " +
                "com.shilapi.xcertplay.G3HotspotConfigMain read",
            G3HotspotShellProtocol.command(apk, "read"),
        )
        val config = configuration().apply { SSID = "\$(touch x);'quote" }
        val payload = G3HotspotShellProtocol.encode(config)!!
        val command = G3HotspotShellProtocol.command("/app/base.apk", "write", payload)!!
        assertFalse(command.contains(config.SSID))
        assertEquals(payload, command.substringAfterLast(' '))
        assertNull(G3HotspotShellProtocol.command("/app/base.apk", "write", "a;id"))
        assertNull(G3HotspotShellProtocol.command("/app/base.apk", "read;id"))
        assertNull(G3HotspotShellProtocol.command("/app/base.apk\nother", "read"))
    }

    @Test fun malformedNoncanonicalOversizedAndTrailingParcelsAreRejected() {
        val encoded = G3HotspotShellProtocol.encode(configuration())!!
        for (invalid in listOf("", "A", "AA=", "A+AA", "AA/AA", "AAA\n", "A".repeat(30_000), encoded + "=")) {
            assertNull(G3HotspotShellProtocol.decode(invalid))
        }
        val bytes = Base64.getUrlDecoder().decode(encoded) + byteArrayOf(0, 0, 0, 0)
        assertNull(G3HotspotShellProtocol.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
        val corrupt = Base64.getUrlDecoder().decode(encoded)
        corrupt[8] = (corrupt[8].toInt() xor 1).toByte()
        assertNull(G3HotspotShellProtocol.decode(Base64.getUrlEncoder().withoutPadding().encodeToString(corrupt)))
    }

    @Test fun repliesMustContainOneValidProtocolLine() {
        val encoded = G3HotspotShellProtocol.encode(configuration())!!
        assertNotNull(G3HotspotShellProtocol.readReply("runtime startup\n$HEADER|$encoded\r\n"))
        assertNull(G3HotspotShellProtocol.readReply("$HEADER|ERROR"))
        assertNull(G3HotspotShellProtocol.readReply("$HEADER|$encoded\n$HEADER|$encoded"))
        assertNull(G3HotspotShellProtocol.readReply("wrong|$encoded"))
        assertNull(G3HotspotShellProtocol.readReply("x".repeat(30_000) + "\n$HEADER|$encoded"))
        assertFalse(G3HotspotShellProtocol.accepted("$HEADER|OK extra"))
        assertFalse(G3HotspotShellProtocol.accepted("$HEADER|OK\n$HEADER|ERROR"))
    }

    @Test fun readsOnlyThroughReadyConnectionAndNeverAsksForApproval() {
        val original = configuration()
        val link = FakeConnection().apply { response = "$HEADER|${G3HotspotShellProtocol.encode(original)}" }
        val actual = client(link).readConfiguration()!!
        assertConfiguration(original, actual)
        assertEquals(listOf(false), link.approvalRequests)
        assertEquals(1, link.commands.size)
        assertTrue(link.commands.single().endsWith("G3HotspotConfigMain read"))
        assertTrue(link.closed)
    }

    @Test fun unavailableOrUnapprovedConnectionsNeverRunAnyCommand() {
        for (access in LocalAdb.Access.entries.filter { it != LocalAdb.Access.READY }) {
            val link = FakeConnection().apply { this.access = access }
            assertNull(client(link).readConfiguration())
            assertFalse(client(link).writeConfiguration(configuration()))
            assertEquals(listOf(false, false), link.approvalRequests)
            assertTrue(link.commands.isEmpty())
        }
    }

    @Test fun writeDoesNotAskForApprovalOrGrantPermissions() {
        val link = FakeConnection()
        val original = configuration()
        assertTrue(client(link).writeConfiguration(original))
        assertEquals(listOf(false), link.approvalRequests)
        assertEquals(1, link.commands.size)
        val command = link.commands.single()
        assertTrue(command.contains("G3HotspotConfigMain write "))
        assertFalse(command.contains("appops"))
        assertFalse(command.contains("pm grant"))
        assertConfiguration(original, G3HotspotShellProtocol.decode(command.substringAfterLast(' '))!!)
    }

    @Test fun invalidCredentialsCannotOpenAdbOrCallWifiService() {
        var opened = 0
        val client = G3HotspotShellClient("/app/base.apk", PACKAGE, { true }, { opened++; FakeConnection() })
        val invalid = configuration().apply { SSID = "not|allowed" }
        assertFalse(client.writeConfiguration(invalid))
        assertEquals(0, opened)
        val payload = G3HotspotShellProtocol.encode(invalid)!!
        var serviceCalls = 0
        assertEquals("ERROR", G3HotspotConfigMain.execute(arrayOf("write", payload)) { serviceCalls++; FakeWifiService() })
        assertEquals(0, serviceCalls)
    }

    @Test fun explicitAuthorizationGrantsOnlyDedicatedAppPermissionsAndReadsBack() {
        val link = FakeConnection()
        var verified = 0
        assertTrue(client(link, permissions = { verified++; true }).authorize())
        assertEquals(listOf(true), link.approvalRequests)
        assertEquals(listOf(
            "appops set $PACKAGE WRITE_SETTINGS allow && printf '$HEADER|OK\\n'",
            "pm grant $PACKAGE android.permission.WRITE_SECURE_SETTINGS && printf '$HEADER|OK\\n'",
        ), link.commands)
        assertEquals(1, verified)
        assertTrue(link.closed)
    }

    @Test fun grantFailureStopsSetupAndReadbackFailureCannotClaimAuthorization() {
        val failed = FakeConnection().apply { response = "$HEADER|ERROR" }
        var verified = false
        assertFalse(client(failed, permissions = { verified = true; true }).authorize())
        assertEquals(1, failed.commands.size)
        assertFalse(verified)
        val notGranted = FakeConnection()
        assertFalse(client(notGranted, permissions = { false }).authorize())
        assertEquals(2, notGranted.commands.size)
    }

    @Test fun anotherApplicationCannotUseSetupOrConfigDelegation() {
        var opened = 0
        val client = G3HotspotShellClient("/app/base.apk", "com.other.application", { true }, { opened++; FakeConnection() })
        assertFalse(client.authorize())
        assertNull(client.readConfiguration())
        assertFalse(client.writeConfiguration(configuration()))
        assertEquals(0, opened)
    }

    @Test fun helperAcceptsOnlyExactReadAndWriteActionsAndUsesShellIdentity() {
        val service = FakeWifiService()
        val encoded = G3HotspotConfigMain.execute(arrayOf("read")) { service }
        assertConfiguration(service.config, G3HotspotShellProtocol.decode(encoded)!!)
        assertEquals("OK", G3HotspotConfigMain.execute(arrayOf("write", encoded)) { service })
        assertEquals("com.android.shell", service.caller)
        assertConfiguration(service.config, service.written!!)
        var called = false
        for (args in listOf(arrayOf("delete"), arrayOf("read", "extra"), arrayOf("write", encoded, "extra"), arrayOf("write", "bad;id"))) {
            assertEquals("ERROR", G3HotspotConfigMain.execute(args) { called = true; service })
        }
        assertFalse(called)
    }

    @Test fun totalDeadlineCancelsIoAndRejectsEvenALateSuccess() {
        val release = CountDownLatch(1)
        val link = object : FakeConnection() {
            override fun shell(command: String, timeoutMillis: Int): String? {
                release.await(2, TimeUnit.SECONDS)
                return "$HEADER|${G3HotspotShellProtocol.encode(configuration())}"
            }
            override fun cancel() { super.cancel(); release.countDown() }
        }
        assertNull(client(link, timeout = 20).readConfiguration())
        assertTrue(link.cancelled)
        assertTrue(link.closed)
    }

    private fun client(link: FakeConnection, permissions: () -> Boolean = { true }, timeout: Long = 15_000) =
        G3HotspotShellClient("/app/base.apk", PACKAGE, permissions, { link }, timeout)

    internal open class FakeConnection : G3HotspotShellConnection {
        var access = LocalAdb.Access.READY
        var response: String? = "$HEADER|OK"
        val approvalRequests = mutableListOf<Boolean>()
        val commands = mutableListOf<String>()
        var closed = false
        @Volatile var cancelled = false
        override fun connect(mayAsk: Boolean): LocalAdb.Access { approvalRequests += mayAsk; return access }
        override fun shell(command: String, timeoutMillis: Int): String? { commands += command; return response }
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }

    internal class FakeWifiService {
        val config = configuration()
        var caller: String? = null
        var written: WifiConfiguration? = null
        fun getWifiApConfiguration() = config
        fun setWifiApConfiguration(config: WifiConfiguration, packageName: String): Boolean {
            written = config; caller = packageName; return true
        }
    }

    private fun assertConfiguration(expected: WifiConfiguration, actual: WifiConfiguration) {
        assertEquals(expected.SSID, actual.SSID)
        assertEquals(expected.preSharedKey, actual.preSharedKey)
        assertEquals(expected.BSSID, actual.BSSID)
        assertEquals(expected.networkId, actual.networkId)
        assertEquals(expected.hiddenSSID, actual.hiddenSSID)
        assertEquals(expected.allowedKeyManagement, actual.allowedKeyManagement)
        assertEquals(expected.allowedProtocols, actual.allowedProtocols)
        assertEquals(expected.allowedPairwiseCiphers, actual.allowedPairwiseCiphers)
        assertEquals(expected.allowedGroupCiphers, actual.allowedGroupCiphers)
        for (field in listOf("apBand", "apChannel")) {
            assertEquals(WifiConfiguration::class.java.getField(field).get(expected),
                WifiConfiguration::class.java.getField(field).get(actual))
        }
    }

    private companion object {
        const val HEADER = "DIPLAY_G3_CONFIG_V1"
        const val PACKAGE = "com.techeek.diplay.g3"
        fun configuration() = WifiConfiguration().apply {
            SSID = "Test car hotspot"; preSharedKey = "test-only-pass"
            BSSID = "02:00:00:00:00:03"; networkId = 17; hiddenSSID = true
            allowedKeyManagement.set(4); allowedProtocols.set(1)
            allowedPairwiseCiphers.set(2); allowedGroupCiphers.set(3)
            WifiConfiguration::class.java.getField("apBand").setInt(this, 1)
            WifiConfiguration::class.java.getField("apChannel").setInt(this, 149)
        }
    }
}
