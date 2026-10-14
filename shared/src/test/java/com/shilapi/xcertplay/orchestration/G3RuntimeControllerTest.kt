package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.ContextWrapper
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class G3RuntimeControllerTest {
    @Test fun rejectsWiredBeforeAcquiringAnyRuntimeResources() {
        assertRejected(config(transport = CarPlayTransport.WIRED))
    }

    @Test fun rejectsEveryOtherWifiBackendBeforeAcquiringAnyRuntimeResources() {
        for (mode in WirelessHotspotMode.entries.filter { it != WirelessHotspotMode.MANUAL }) {
            assertRejected(config(mode = mode))
        }
    }

    @Test fun rejectsRemoteAuthenticationBeforeAcquiringAnyRuntimeResources() {
        assertRejected(config(target = MfiTarget.REMOTE))
    }

    private fun assertRejected(config: CarPlayRuntimeConfig) {
        val noResources = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context =
                throw AssertionError("Unsupported configuration acquired the application context")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayController(
                noResources, config,
                AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
                AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
            )
        }
    }

    private fun config(
        transport: CarPlayTransport = CarPlayTransport.WIRELESS,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
        target: MfiTarget = MfiTarget.LOCAL,
    ) = CarPlayRuntimeConfig(
        identification = Iap2IdentificationConfig(
            name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
            firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
        ),
        transport = transport, wirelessHotspotMode = mode, mfiTarget = target,
        manualHotspotSsid = "Test car hotspot", manualHotspotPassphrase = "test-only-pass",
        existingWifiSsid = "Test legacy station", existingWifiPassphrase = "test-only-pass",
        remoteMfiServer = "https://example.invalid",
    )
}
