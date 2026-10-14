package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerUsbServiceTest {
    private val controllers = mutableListOf<CarPlayController>()
    private val statuses = mutableListOf<CarPlayStatus>()

    @After fun tearDown() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        BydNavigationOutputs.endNow()
    }

    @Test fun wirelessLocalCanBeCreatedAndReachMfiWithoutAUsbService() {
        val context = UsbContext(false)
        val controller = controller(context)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.contains(CarPlayStatus.DiscoveringMfi))
        assertFalse(statuses.filterIsInstance<CarPlayStatus.Failed>().any { it.message.contains("USB service") })
        assertEquals(0, context.receivers.size)
    }

    private fun controller(context: Context): CarPlayController =
        CarPlayController(context, CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = WirelessHotspotMode.MANUAL,
            manualHotspotSsid = "Test car hotspot", manualHotspotPassphrase = "test-only-pass",
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add).also(controllers::add)

    private class UsbContext(private val hasUsb: Boolean) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val receivers = mutableMapOf<BroadcastReceiver, IntentFilter>()
        var unregistered = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        override fun getSystemService(name: String): Any? =
            if (name == USB_SERVICE && !hasUsb) null else super.getSystemService(name)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            if (receiver != null) receivers[receiver] = filter
            return super.registerReceiver(receiver, filter)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            if (receivers.remove(receiver) != null) unregistered++
            super.unregisterReceiver(receiver)
        }
    }
}
