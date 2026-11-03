package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, qualifiers = "en")
class G3LocaleTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun bothSupportedLanguagesResolveTheHotspotControlsWithoutMutatingBaseResources() {
        val original = app.resources.configuration.locales.toLanguageTags()
        AppLocale.save(app, AppLocale.SIMPLIFIED_CHINESE)
        assertEquals("热点名称", AppLocale.wrap(app).getString(R.string.settings_g3_ssid))
        AppLocale.save(app, AppLocale.ENGLISH)
        assertEquals("Wi-Fi name", AppLocale.wrap(app).getString(R.string.settings_g3_ssid))
        assertEquals(original, app.resources.configuration.locales.toLanguageTags())
    }

    @Test fun unsupportedLegacyChoiceFallsBackToSystemWithoutLosingOtherPreferences() {
        val prefs = app.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        prefs.edit().putString("app_language", "ja").putString("phone_name", "Test iPhone").commit()
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(app))
        assertSame(app, AppLocale.wrap(app))
        assertEquals("Test iPhone", prefs.getString("phone_name", null))
        assertThrows(IllegalArgumentException::class.java) { AppLocale.save(app, "ja") }
    }
}
