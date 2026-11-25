package com.livephotolink.app

import com.livephotolink.app.model.PairingQrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PairingQrPayloadTest {

    @Test
    fun roundTripWithTraditionalChineseName() {
        val original = PairingQrPayload(
            host = "192.168.1.88",
            port = 48910,
            pin = "482910",
            deviceName = "三星 Galaxy Tab S11 平板",
            deviceId = "tab-s11-001"
        )
        val uri = original.toUriString()
        val parsed = PairingQrPayload.parse(uri)
        assertNotNull(parsed)
        assertEquals(original, parsed)
    }

    @Test
    fun rejectsInvalidPayloads() {
        assertNull(PairingQrPayload.parse("https://example.com"))
        assertNull(PairingQrPayload.parse("livephotolink://pair?host=192.168.1.2&port=48910&pin=123"))
        assertNull(PairingQrPayload.parse("livephotolink://pair?host=192.168.1.2&port=99999&pin=123456"))
    }
}
