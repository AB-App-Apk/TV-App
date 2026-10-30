package com.example.tvdash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscretePressDispatchTest {
    @Test
    fun shortOkPrefersFocusedNodeClick() {
        var fallbackCalls = 0

        val result = DiscretePressDispatch.shortOk(
            focusedClick = { true },
            fallbackDpadCenter = {
                fallbackCalls++
                true
            }
        )

        assertEquals(DiscretePressResult.SUCCESS, result)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun shortOkUsesDpadCenterWhenFocusedNodeClickFails() {
        var fallbackCalls = 0

        val result = DiscretePressDispatch.shortOk(
            focusedClick = { false },
            fallbackDpadCenter = {
                fallbackCalls++
                true
            }
        )

        assertEquals(DiscretePressResult.SUCCESS, result)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun shortOkReportsFailureWhenBothDispatchMethodsFail() {
        val result = DiscretePressDispatch.shortOk(
            focusedClick = { false },
            fallbackDpadCenter = { false }
        )

        assertEquals(DiscretePressResult.ACTION_FAILED, result)
    }

    @Test
    fun longOkDoesNotFallBackToShortSelect() {
        var longClickCalls = 0

        val result = DiscretePressDispatch.longOk {
            longClickCalls++
            false
        }

        assertEquals(DiscretePressResult.LONG_CLICK_UNSUPPORTED, result)
        assertTrue(longClickCalls == 1)
    }
}
