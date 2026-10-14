package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class G3LauncherTest {
    @Test fun legacySelectionsCannotEnableAnotherTransportOrAuthenticationTarget() {
        val app = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveWirelessEnabled(app, false)
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.USB_CH341)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(app))
        assertEquals(WirelessHotspotMode.MANUAL, AirPlayPersistence.loadWirelessHotspotMode(app))
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(app))
    }

    @Test fun passwordIsMaskedAndNeverSavedAsViewState() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val root = controller.get().window.decorView
            val password = root.findViewWithTag<EditText>("g3_password")
            assertNotNull(password)
            assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                password.inputType and InputType.TYPE_MASK_VARIATION)
            assertFalse(password.isSaveEnabled)
            assertFalse(root.findViewWithTag<EditText>("g3_ssid").isSaveEnabled)
            assertNotNull(root.findViewWithTag<View>("g3_apply"))
            val authorize = root.findViewWithTag<View>("g3_authorize")
            assertNotNull(authorize)
            assertFalse(password.isEnabled)
            assertFalse(authorize.isEnabled)
            authorize.performClick()
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        } finally { controller.destroy() }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w360dp-h800dp-mdpi")
    fun compactLargeTextLayout() = screenshot("compact-large-font", 360, 800, false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "zh-rCN-w1200dp-h720dp-mdpi")
    fun fullLargeTextLayout() = screenshot("full-large-font", 1200, 720, false)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rSA-w1200dp-h720dp-mdpi")
    fun rtlLargeTextLayout() = screenshot("rtl-large-font", 1200, 720, true)

    private fun screenshot(name: String, width: Int, height: Int, rtl: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        app.resources.configuration.fontScale = 1.3f
        app.resources.displayMetrics.scaledDensity = app.resources.displayMetrics.density * 1.3f
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).create()
        try {
            val decor = controller.get().window.decorView
            decor.layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, width, height)
            val input = decor.findViewWithTag<EditText>("g3_ssid")
            if (rtl) assertEquals(View.LAYOUT_DIRECTION_RTL, input.layoutDirection)
            assertTrue(input.width > 0)
            assertTrue(input.width <= width)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(bitmap))
            File("build/reports/g3-ui/$name.png").apply {
                parentFile.mkdirs()
                outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        } finally { controller.destroy() }
    }
}
