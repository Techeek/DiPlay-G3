package com.shilapi.xcertplay

import android.net.wifi.WifiConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class G3HotspotControllerTest {
    @Test fun deniedSecureSettingsCannotStopApOrMutateEitherCredentialStore() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED; secureSettings = false }
        assertEquals(G3HotspotStatus.NEEDS_WRITE_SECURE_SETTINGS, controller(backend).apply("New name", NEW_PASSWORD, true).status)
        assertTrue(backend.events.isEmpty())
        assertOriginal(backend, active = true)
    }

    @Test fun deniedWriteSettingsCannotMutateEitherCredentialStore() {
        val backend = FakeBackend().apply { writeSettings = false }
        assertEquals(G3HotspotStatus.NEEDS_WRITE_SETTINGS, controller(backend).apply("New name", NEW_PASSWORD).status)
        assertTrue(backend.events.isEmpty())
        assertOriginal(backend)
    }

    @Test fun invalidInputCannotMutateEitherCredentialStoreOrStopRunningAp() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED }
        assertEquals(G3HotspotStatus.INVALID_SSID, controller(backend).apply("invalid|name", NEW_PASSWORD, true).status)
        assertEquals(G3HotspotStatus.INVALID_PASSWORD, controller(backend).apply("New name", "short", true).status)
        assertTrue(backend.events.isEmpty())
        assertOriginal(backend, active = true)
    }

    @Test fun activeHotspotRequiresExplicitRestartConfirmation() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED }
        assertEquals(G3HotspotStatus.RESTART_CONFIRMATION_REQUIRED, controller(backend).apply("New name", NEW_PASSWORD).status)
        assertTrue(backend.events.isEmpty())
        assertOriginal(backend, active = true)
    }

    @Test fun disabledHotspotSavesBothStoresWithoutStartingRadioAndKeepsOemFields() {
        val backend = FakeBackend()
        val originalBand = band(backend.config)
        val originalChannel = channel(backend.config)
        val originalProtocols = backend.config.allowedProtocols.clone()
        val result = controller(backend).apply("新热点", NEW_PASSWORD)
        assertEquals(G3HotspotStatus.APPLIED, result.status)
        assertEquals(listOf("cache", "config"), backend.events)
        assertEquals("新热点", backend.config.SSID)
        assertEquals(NEW_PASSWORD, backend.config.preSharedKey)
        assertEquals("新热点|$NEW_PASSWORD", backend.cached)
        assertEquals(G3HotspotState.DISABLED, backend.radio)
        assertEquals(originalBand, band(backend.config))
        assertEquals(originalChannel, channel(backend.config))
        assertTrue(backend.config.hiddenSSID)
        assertEquals(originalProtocols, backend.config.allowedProtocols)
        assertEquals(1, backend.config.allowedKeyManagement.cardinality())
        assertTrue(backend.config.allowedKeyManagement[4])
    }

    @Test fun confirmedApplyRestoresRunningApOnlyAfterSynchronizingBothStores() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED }
        assertEquals(G3HotspotStatus.APPLIED, controller(backend).apply("New name", NEW_PASSWORD, true).status)
        assertEquals(listOf("enabled:false", "await:DISABLED", "cache", "config", "enabled:true", "await:ENABLED"), backend.events)
        assertEquals(G3HotspotState.ENABLED, backend.radio)
        assertEquals("New name|$NEW_PASSWORD", backend.cached)
    }

    @Test fun unchangedRunningConfigurationCanBeVerifiedWithoutRestartOrWrites() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED }
        assertEquals(G3HotspotStatus.APPLIED, controller(backend).apply("Old hotspot", OLD_PASSWORD).status)
        assertTrue(backend.events.isEmpty())
        assertOriginal(backend, active = true)
    }

    @Test fun unchangedRunningConfigurationRepairsOnlyFactoryCacheWithoutRestart() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED; cached = "Stale|cache-pass" }
        assertEquals(G3HotspotStatus.APPLIED, controller(backend).apply("Old hotspot", OLD_PASSWORD).status)
        assertEquals(listOf("cache"), backend.events)
        assertOriginal(backend, active = true)
    }

    @Test fun cacheRejectionRestoresCoherentOldCacheEvenWhenOriginalWasMissing() {
        val backend = FakeBackend().apply { cached = null; rejectFirstCache = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(true, result.rollbackSucceeded)
        assertOriginal(backend)
    }

    @Test fun malformedOriginalCacheIsRepairedDuringFailedApplyRollback() {
        val backend = FakeBackend().apply { cached = "malformed"; rejectFirstConfiguration = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(true, result.rollbackSucceeded)
        assertOriginal(backend)
    }

    @Test fun invalidOriginalCredentialsRejectApplyBeforeAnyMutation() {
        val backend = FakeBackend().apply { config.preSharedKey = "short"; cached = null }
        assertEquals(G3HotspotStatus.READ_FAILED, controller(backend).apply("New name", NEW_PASSWORD).status)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun missingRestartApiStillAllowsReadingAndSavingDisabledConfiguration() {
        val backend = FakeBackend().apply { restartSupported = false }
        assertTrue(controller(backend).load() is G3HotspotReadResult.Success)
        assertEquals(G3HotspotStatus.APPLIED, controller(backend).apply("New name", NEW_PASSWORD).status)
        assertEquals(listOf("cache", "config"), backend.events)
    }

    @Test fun missingRestartApiRejectsActiveConfigChangeBeforeAnyMutation() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED; restartSupported = false }
        assertTrue(controller(backend).load() is G3HotspotReadResult.Success)
        assertEquals(G3HotspotStatus.UNSUPPORTED, controller(backend).apply("New name", NEW_PASSWORD, true).status)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun rejectedConfigurationRestoresBothStoresAndRunningAp() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED; rejectFirstConfiguration = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD, true)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(true, result.rollbackSucceeded)
        assertOriginal(backend, active = true)
    }

    @Test fun asynchronouslyAppliedConfigurationIsAwaitedBeforeReportingSuccess() {
        val backend = FakeBackend().apply { delayConfigurationUntilReadback = true }
        assertEquals(G3HotspotStatus.APPLIED, controller(backend).apply("New name", NEW_PASSWORD).status)
        assertEquals("New name", backend.config.SSID)
        assertEquals(NEW_PASSWORD, backend.config.preSharedKey)
        assertTrue(backend.readbackSamples >= 2)
    }

    @Test fun configurationReadbackMismatchRollsBackInsteadOfReportingSuccess() {
        val backend = FakeBackend().apply { corruptFirstConfiguration = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(true, result.rollbackSucceeded)
        assertOriginal(backend)
    }

    @Test fun unexpectedChannelChangeIsDetectedAndRolledBack() {
        val backend = FakeBackend().apply { corruptFirstChannel = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(true, result.rollbackSucceeded)
        assertOriginal(backend)
    }

    @Test fun factoryOverwriteOnRestartIsDetectedAndOldCredentialsRestored() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED; overwriteOnFirstEnable = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD, true)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(true, result.rollbackSucceeded)
        assertOriginal(backend, active = true)
    }

    @Test fun restartTimeoutStillAttemptsRestorationOfOriginalRunningAp() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED; failFirstEnabledWait = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD, true)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(true, result.rollbackSucceeded)
        assertOriginal(backend, active = true)
    }

    @Test fun failedRollbackIsReportedAndRemainingRestorationsAreStillAttempted() {
        val backend = FakeBackend().apply { radio = G3HotspotState.ENABLED; rejectEveryConfiguration = true }
        val result = controller(backend).apply("New name", NEW_PASSWORD, true)
        assertEquals(G3HotspotStatus.APPLY_FAILED, result.status)
        assertEquals(false, result.rollbackSucceeded)
        assertEquals(OLD_CACHE, backend.cached)
        assertEquals(G3HotspotState.ENABLED, backend.radio)
        assertTrue(backend.events.contains("enabled:true"))
    }

    @Test fun transientRadioStateIsReadOnlyAndDoesNotEnterTransaction() {
        val backend = FakeBackend().apply { radio = G3HotspotState.BUSY }
        val controller = controller(backend)
        assertEquals(G3HotspotStatus.BUSY, (controller.load() as G3HotspotReadResult.Failure).status)
        assertEquals(G3HotspotStatus.BUSY, controller.apply("New name", NEW_PASSWORD, true).status)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun unsupportedBackendAndMissingConfigurationCannotWrite() {
        val backend = FakeBackend().apply { supported = false }
        assertEquals(G3HotspotStatus.UNSUPPORTED, controller(backend).apply("New name", NEW_PASSWORD).status)
        assertEquals(G3HotspotStatus.UNSUPPORTED, (controller(backend).load() as G3HotspotReadResult.Failure).status)
        backend.supported = true
        backend.missingConfiguration = true
        assertEquals(G3HotspotStatus.READ_FAILED, controller(backend).apply("New name", NEW_PASSWORD).status)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun loadedCredentialsRemainLocalAndAllResultStringsAreRedacted() {
        val backend = FakeBackend()
        val result = controller(backend).load() as G3HotspotReadResult.Success
        assertEquals(OLD_PASSWORD, result.snapshot.password)
        assertEquals("Old hotspot", result.snapshot.ssid)
        assertFalse(result.snapshot.active)
        assertFalse(result.toString().contains(OLD_PASSWORD))
        assertFalse(result.snapshot.toString().contains(OLD_PASSWORD))
        assertTrue(backend.events.isEmpty())
    }

    private fun controller(backend: FakeBackend) = G3HotspotController(backend)

    private fun assertOriginal(backend: FakeBackend, active: Boolean = false, cache: String? = OLD_CACHE) {
        assertEquals("Old hotspot", backend.config.SSID)
        assertEquals(OLD_PASSWORD, backend.config.preSharedKey)
        assertEquals(cache, backend.cached)
        assertEquals(if (active) G3HotspotState.ENABLED else G3HotspotState.DISABLED, backend.radio)
        assertEquals(1, band(backend.config))
        assertEquals(149, channel(backend.config))
        assertTrue(backend.config.hiddenSSID)
    }

    private fun band(config: WifiConfiguration) = WifiConfiguration::class.java.getField("apBand").getInt(config)
    private fun channel(config: WifiConfiguration) = WifiConfiguration::class.java.getField("apChannel").getInt(config)

    private class FakeBackend : G3HotspotBackend {
        override var supported = true
        override var restartSupported = true
        var writeSettings = true
        var secureSettings = true
        var radio = G3HotspotState.DISABLED
        var cached: String? = OLD_CACHE
        var config = WifiConfiguration().apply {
            SSID = "Old hotspot"
            preSharedKey = OLD_PASSWORD
            hiddenSSID = true
            allowedKeyManagement.set(4)
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            WifiConfiguration::class.java.getField("apBand").setInt(this, 1)
            WifiConfiguration::class.java.getField("apChannel").setInt(this, 149)
        }
        var rejectFirstCache = false
        var rejectFirstConfiguration = false
        var rejectEveryConfiguration = false
        var corruptFirstConfiguration = false
        var corruptFirstChannel = false
        var overwriteOnFirstEnable = false
        var failFirstEnabledWait = false
        var missingConfiguration = false
        var delayConfigurationUntilReadback = false
        var pendingConfiguration: WifiConfiguration? = null
        var readbackSamples = 0
        val events = mutableListOf<String>()
        override fun canWriteSettings() = writeSettings
        override fun canWriteSecureSettings() = secureSettings
        override fun configuration(): WifiConfiguration? = if (missingConfiguration) null else config
        override fun setConfiguration(config: WifiConfiguration): Boolean {
            events.add("config")
            // Even a rejected vendor call might have changed state; rollback must handle that.
            if (delayConfigurationUntilReadback) {
                delayConfigurationUntilReadback = false
                pendingConfiguration = WifiConfiguration(config)
                return true
            }
            this.config = WifiConfiguration(config)
            if (rejectEveryConfiguration) return false
            if (rejectFirstConfiguration) { rejectFirstConfiguration = false; return false }
            if (corruptFirstConfiguration) { corruptFirstConfiguration = false; this.config.preSharedKey = "Firmware changed" }
            if (corruptFirstChannel) {
                corruptFirstChannel = false
                WifiConfiguration::class.java.getField("apChannel").setInt(this.config, 6)
            }
            return true
        }
        override fun cache() = cached
        override fun writeCache(value: String?): Boolean {
            events.add("cache")
            cached = value
            if (rejectFirstCache) { rejectFirstCache = false; return false }
            return true
        }
        override fun state() = radio
        override fun setEnabled(enabled: Boolean): Boolean {
            events.add("enabled:$enabled")
            radio = if (enabled) G3HotspotState.ENABLED else G3HotspotState.DISABLED
            if (enabled && overwriteOnFirstEnable) {
                overwriteOnFirstEnable = false
                config.preSharedKey = "Factory changed"
            }
            return true
        }
        override fun awaitState(expected: G3HotspotState): Boolean {
            events.add("await:$expected")
            if (expected == G3HotspotState.ENABLED && failFirstEnabledWait) {
                failFirstEnabledWait = false
                radio = G3HotspotState.BUSY
                return false
            }
            return radio == expected
        }
        override fun awaitConfiguration(matches: () -> Boolean): Boolean {
            readbackSamples++
            if (matches()) return true
            pendingConfiguration?.let { config = it; pendingConfiguration = null }
            readbackSamples++
            return matches()
        }
    }

    companion object {
        private const val OLD_PASSWORD = "old-test-pass"
        private const val NEW_PASSWORD = "new-test-pass"
        private const val OLD_CACHE = "Old hotspot|$OLD_PASSWORD"
    }
}
