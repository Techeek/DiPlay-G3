package com.shilapi.xcertplay

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class G3MissingAuthenticationTest {
    @Test fun missingStandaloneAssetsReturnToSetupWithoutRecreationLoop() {
        val ready = DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
        val previous = ready.getBoolean(null)
        ready.setBoolean(null, false)
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java)
        try {
            AirPlayPersistence.saveSmoothVideo(controller.get(), true)
            controller.create().start().resume()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(controller.get().isFinishing)
            assertFalse(CarPlayBackgroundSession.hasSession())
            assertEquals(DiPlayActivity::class.java.name,
                shadowOf(controller.get()).nextStartedActivity.component?.className)
        } finally {
            controller.pause().stop().destroy()
            ready.setBoolean(null, previous)
            CarPlayBackgroundSession.clear()
        }
    }
}
