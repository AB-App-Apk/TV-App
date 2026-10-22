package com.example.tvdash

import com.example.tvlink.TvProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvProtocolTest {
    @Test
    fun wifiUrlNeverContainsPinAndNormalizesLeadingSlash() {
        val url = TvProtocol.buildWifiUrl("192.168.1.20", 8765, "/ping")

        assertEquals("http://192.168.1.20:8765/ping", url)
        assertFalse(url.contains("pin=", ignoreCase = true))
        assertFalse(url.contains("1234"))
    }

    @Test
    fun bluetoothRequestCarriesPinOnlyInHeaderWithHttpLineEndings() {
        val request = TvProtocol.buildBluetoothRequest("/ping", "1234")

        assertEquals("GET /ping HTTP/1.1\r\nX-TV-PIN: 1234\r\n\r\n", request)
        assertFalse(request.substringBefore("\r\n").contains("pin=", ignoreCase = true))
    }

    @Test
    fun pinHeaderIsCaseInsensitiveAndTrimsWhitespace() {
        val lines = listOf("Host: tv", "x-tv-pin:   1234  ", "")
        var index = 0

        val pin = TvProtocol.readPinHeader { lines.getOrNull(index++) }

        assertEquals("1234", pin)
    }

    @Test
    fun missingOrUnrelatedHeadersDoNotProvidePin() {
        val lines = listOf("Host: tv", "Authorization: 1234", "")
        var index = 0

        assertEquals(null, TvProtocol.readPinHeader { lines.getOrNull(index++) })
    }

    @Test
    fun parserStopsAtHeaderTerminatorAndHonorsHeaderLimit() {
        val afterTerminator = listOf("", "X-TV-PIN: 1234")
        var index = 0
        assertEquals(null, TvProtocol.readPinHeader { afterTerminator.getOrNull(index++) })

        val tooManyHeaders = (1..64).map { "X-Other-$it: value" } + "X-TV-PIN: 1234"
        index = 0
        assertEquals(null, TvProtocol.readPinHeader { tooManyHeaders.getOrNull(index++) })
        assertTrue(index <= 65)
    }
}
