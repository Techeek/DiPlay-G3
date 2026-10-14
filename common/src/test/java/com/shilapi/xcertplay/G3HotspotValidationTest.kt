package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class G3HotspotValidationTest {
    @Test fun utf8ByteLimitAllowsMultilingualNamesWithin32Bytes() {
        assertNull(G3HotspotValidation.error("车".repeat(10) + "12", "test-pass"))
        assertEquals(G3HotspotStatus.INVALID_SSID, G3HotspotValidation.error("车".repeat(11), "test-pass"))
        assertNull(G3HotspotValidation.error("🚘".repeat(8), "test-pass"))
        assertEquals(G3HotspotStatus.INVALID_SSID, G3HotspotValidation.error("🚘".repeat(8) + "a", "test-pass"))
    }

    @Test fun oemDelimiterAndControlCharactersAreRejectedInNameAndPassword() {
        for (name in listOf("", "   ", "a|b", "name\n", "name\r", "name\u0000", "name\t", "name\u2028", "name\u2029", "bad\uD800")) {
            assertEquals(G3HotspotStatus.INVALID_SSID, G3HotspotValidation.error(name, "test-pass"))
        }
        for (password in listOf("abc|defg", "abc\ndefg", "abc\rdefg", "abc\u0000defg", "密码testpass", "abc\u007Fdefg")) {
            assertEquals(G3HotspotStatus.INVALID_PASSWORD, G3HotspotValidation.error("hotspot", password))
        }
    }

    @Test fun wpa2PassphraseAcceptsExactly8Through63PrintableAsciiCharacters() {
        assertEquals(G3HotspotStatus.INVALID_PASSWORD, G3HotspotValidation.error("hotspot", "a".repeat(7)))
        assertNull(G3HotspotValidation.error("hotspot", "a".repeat(8)))
        assertNull(G3HotspotValidation.error("hotspot", "a".repeat(63)))
        assertEquals(G3HotspotStatus.INVALID_PASSWORD, G3HotspotValidation.error("hotspot", "a".repeat(64)))
        assertNull(G3HotspotValidation.error("hotspot", " spaces "))
    }
}
