package com.shilapi.xcertplay

import android.content.Context
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [DiPlayAuthenticationTest.NavigationOutputs::class])
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlayAuthenticationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.noBackupFilesDir, "offline-mfi")

    @Before fun setUp() {
        // The JVM test host has no vehicle SOME/IP gateway.
        shadowOf(context).declareActionUnbindable("com.ts.car.someip.SomeIpServerService")
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
            .setBoolean(null, false)
    }

    @Test fun freshInstallStillDefaultsToLocalAuthentication() {
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
    }

    @Test fun legacyUsbPreferenceCannotBypassRequiredLocalBootstrap() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
        assertThrows(Exception::class.java) {
            DiPlayBootstrap.ensure(context, AirPlayPersistence.loadMfiTarget(context))
        }
        assertFalse(directory.exists())
        assertFalse(File(context.noBackupFilesDir, "offline-mfi-staging").exists())
    }

    @Test fun localBootstrapRejectsExistingBrokenIdentityWithoutOverwritingIt() {
        assertTrue(directory.mkdirs())
        File(directory, "identity.pk8").writeText("invalid local identity")
        assertThrows(Exception::class.java) { DiPlayBootstrap.ensure(context, MfiTarget.LOCAL) }
        assertEquals("invalid local identity", File(directory, "identity.pk8").readText())
        assertFalse(File(directory, "certificate.p7b").exists())
    }

    @Test fun brokenLocalIdentityFailsWithoutFallingBackToHardware() {
        assertTrue(directory.mkdirs())
        File(directory, "identity.pk8").writeText("invalid local identity")
        val statuses = startAuthentication()
        assertTrue(statuses.any { it is CarPlayStatus.Failed })
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
    }

    @Test fun missingLocalIdentityFailsWithoutFallingBackToUsb() {
        val statuses = startAuthentication()
        assertTrue(statuses.any { it is CarPlayStatus.Failed })
        assertFalse(statuses.contains(CarPlayStatus.WaitingForMfi))
    }

    // Authentication tests do not have a real SOME/IP service. Avoid starting a periodic OEM
    // output worker that can bind again after Robolectric replaces the application context.
    @Implements(com.shilapi.xcertplay.hud.BydNavigationOutputs::class, isInAndroidSdk = false)
    class NavigationOutputs {
        @Implementation fun start(context: Context) = Unit
    }

    private fun startAuthentication(): List<CarPlayStatus> {
        val statuses = mutableListOf<CarPlayStatus>()
        val controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                transport = CarPlayTransport.WIRELESS,
                wirelessHotspotMode = WirelessHotspotMode.MANUAL,
                manualHotspotSsid = "Test car hotspot",
                manualHotspotPassphrase = "test-only-pass",
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
            { statuses.add(it) },
        )
        try {
            controller.javaClass.getDeclaredMethod("startMfi").apply { isAccessible = true }.invoke(controller)
            val executor = controller.javaClass.getDeclaredField("executor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            executor.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            return statuses.toList()
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2_000))
        }
    }
}
