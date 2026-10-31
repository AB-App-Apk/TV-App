package com.example.tvdash

import org.junit.Assert.assertEquals
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
    fun longOkPreservesNoFocusResult() {
        val result = DiscretePressDispatch.longOk {
            DiscretePressResult.NO_FOCUS
        }

        assertEquals(DiscretePressResult.NO_FOCUS, result)
    }

    @Test
    fun longOkPreservesUnsupportedResultWithoutFallback() {
        var longClickCalls = 0

        val result = DiscretePressDispatch.longOk {
            longClickCalls++
            DiscretePressResult.LONG_CLICK_UNSUPPORTED
        }

        assertEquals(DiscretePressResult.LONG_CLICK_UNSUPPORTED, result)
        assertEquals(1, longClickCalls)
    }

    @Test
    fun longOkPreservesActionExecutionFailure() {
        val result = DiscretePressDispatch.longOk {
            DiscretePressResult.ACTION_FAILED
        }

        assertEquals(DiscretePressResult.ACTION_FAILED, result)
    }

    @Test
    fun longOkPreservesSuccess() {
        val result = DiscretePressDispatch.longOk {
            DiscretePressResult.SUCCESS
        }

        assertEquals(DiscretePressResult.SUCCESS, result)
    }
}
