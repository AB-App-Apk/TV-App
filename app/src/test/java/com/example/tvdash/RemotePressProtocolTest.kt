package com.example.tvdash

import com.example.tvlink.HeldRemoteKey
import com.example.tvlink.RemotePressProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemotePressProtocolTest {
    @Test
    fun buildsOneExplicitActionForBackAndOk() {
        val id = "c0ffee00-0000-4000-8000-000000000001"

        assertEquals(
            "press?id=$id&key=back&action=tap",
            RemotePressProtocol.path(id, HeldRemoteKey.BACK, longPress = false)
        )
        assertEquals(
            "press?id=$id&key=ok&action=long",
            RemotePressProtocol.path(id, HeldRemoteKey.OK, longPress = true)
        )
    }

    @Test
    fun rejectsKeysOutsideTheDiscretePressProtocol() {
        val id = "c0ffee00-0000-4000-8000-000000000001"

        assertThrows(IllegalArgumentException::class.java) {
            RemotePressProtocol.path(id, HeldRemoteKey.UP, longPress = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemotePressProtocol.path("invalid", HeldRemoteKey.BACK, longPress = false)
        }
    }
}
