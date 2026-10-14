package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class G3HotspotConnectionTest {
    @Test fun publishesTheCredentialsActuallyActiveAfterFactoryStartup() {
        var started = false
        val result = G3HotspotConnection.prepare(
            read = { G3HotspotReadResult.Success(G3HotspotSnapshot(
                if (started) "factory-active" else "saved-before-start",
                if (started) "active-test-secret" else "old-test-secret", started)) },
            enable = { started = true; true },
        ) as G3HotspotConnection.Result.Ready
        assertEquals("factory-active", result.snapshot.ssid)
        assertEquals("active-test-secret", result.snapshot.password)
        assertFalse(result.toString().contains(result.snapshot.password))
    }

    @Test fun failedFinalReadNeverReturnsStaleCredentials() {
        var reads = 0
        val result = G3HotspotConnection.prepare(
            read = { if (reads++ == 0) success(false)
                else G3HotspotReadResult.Failure(G3HotspotStatus.READ_FAILED) },
            enable = { true },
        )
        assertEquals(G3HotspotStatus.READ_FAILED,
            (result as G3HotspotConnection.Result.ReadFailed).status)
    }

    @Test fun hotspotThatTurnsOffAfterStartupCannotLaunchProjection() {
        assertSame(G3HotspotConnection.Result.StartFailed,
            G3HotspotConnection.prepare({ success(false) }, { true }))
    }

    @Test fun unreadableInitialConfigurationDoesNotStartHotspot() {
        val result = G3HotspotConnection.prepare(
            { G3HotspotReadResult.Failure(G3HotspotStatus.BUSY) },
            { fail("Must not start an unreadable hotspot"); true },
        )
        assertEquals(G3HotspotStatus.BUSY,
            (result as G3HotspotConnection.Result.ReadFailed).status)
    }

    @Test fun failedStartupDoesNotReuseInitiallyActiveCredentials() {
        assertSame(G3HotspotConnection.Result.StartFailed,
            G3HotspotConnection.prepare({ success(true) }, { false }))
    }

    private fun success(active: Boolean) =
        G3HotspotReadResult.Success(G3HotspotSnapshot("test-hotspot", "test-secret", active))
}
