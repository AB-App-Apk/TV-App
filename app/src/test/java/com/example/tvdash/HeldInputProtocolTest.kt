package com.example.tvdash

import com.example.tvlink.HeldAction
import com.example.tvlink.HeldInputProtocol
import com.example.tvlink.HeldRemoteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldInputProtocolTest {
    @Test
    fun acceptsUuidLikeHoldIdsOnly() {
        assertTrue(HeldInputProtocol.isValidHoldId("c0ffee00-0000-4000-8000-000000000001"))
        assertFalse(HeldInputProtocol.isValidHoldId(""))
        assertFalse(HeldInputProtocol.isValidHoldId("not-a-hold-id"))
        assertFalse(HeldInputProtocol.isValidHoldId("c0ffee00-0000-4000-8000-00000000000g"))
    }

    @Test
    fun buildsMatchingDownAndUpPaths() {
        val id = "c0ffee00-0000-4000-8000-000000000001"

        assertEquals(
            "hold?id=$id&key=ff&action=down",
            HeldInputProtocol.path(id, HeldRemoteKey.FORWARD, HeldAction.DOWN)
        )
        assertEquals(
            "hold?id=$id&key=ff&action=renew",
            HeldInputProtocol.path(id, HeldRemoteKey.FORWARD, HeldAction.RENEW)
        )
        assertEquals(
            "hold?id=$id&key=ff&action=up",
            HeldInputProtocol.path(id, HeldRemoteKey.FORWARD, HeldAction.UP)
        )
        assertEquals(HeldAction.RENEW, HeldAction.fromWire("renew"))
    }
}
