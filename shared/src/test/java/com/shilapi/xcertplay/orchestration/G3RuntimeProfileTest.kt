package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class G3RuntimeProfileTest {
    @Test fun defaultsUseCarHotspotAndOfflineAuthentication() {
        val config = config()
        assertEquals(CarPlayTransport.WIRELESS, config.transport)
        assertEquals(WirelessHotspotMode.MANUAL, config.wirelessHotspotMode)
        assertEquals(MfiTarget.LOCAL, config.mfiTarget)
        G3RuntimeProfile.requireSupported(config)
    }

    @Test fun legacyWiredPreferenceCannotEnterRuntime() {
        assertThrows(IllegalArgumentException::class.java) {
            G3RuntimeProfile.requireSupported(config(transport = CarPlayTransport.WIRED))
        }
    }

    @Test fun everyAlternateWifiBackendIsRejected() {
        for (mode in WirelessHotspotMode.entries.filter { it != WirelessHotspotMode.MANUAL }) {
            assertThrows("Unexpected supported backend $mode", IllegalArgumentException::class.java) {
                G3RuntimeProfile.requireSupported(config(mode = mode))
            }
        }
    }

    @Test fun externalAuthenticationCannotEnterRuntime() {
        for (target in MfiTarget.entries.filter { it != MfiTarget.LOCAL }) {
            assertThrows("Unexpected supported authentication target $target", IllegalArgumentException::class.java) {
                G3RuntimeProfile.requireSupported(config(target = target))
            }
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
        transport = transport,
        wirelessHotspotMode = mode,
        mfiTarget = target,
        manualHotspotSsid = "Test car hotspot",
        manualHotspotPassphrase = "test-only-pass",
        existingWifiSsid = "Test legacy station",
        existingWifiPassphrase = "test-only-pass",
        ch341Devices = listOf(UsbDeviceId(0x1a86, 0x5512)),
        linuxI2cPath = "/dev/test-i2c",
        remoteMfiServer = "https://example.invalid",
    )
}
