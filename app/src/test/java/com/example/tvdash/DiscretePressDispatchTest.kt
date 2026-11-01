package com.example.tvdash

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscretePressDispatchTest {
    @Test
    fun shortOkPrefersDpadCenterAndDoesNotClickAccessibilityNode() {
        var dpadCalls = 0
        var focusedClickCalls = 0

        val result = DiscretePressDispatch.shortOk(
            dpadCenter = {
                dpadCalls++
                true
            },
            focusedClick = {
                focusedClickCalls++
                true
            }
        )

        assertEquals(DiscretePressResult.SUCCESS, result)
        assertEquals(1, dpadCalls)
        assertEquals(0, focusedClickCalls)
    }

    @Test
    fun shortOkUsesFocusedNodeClickOnlyWhenDpadCenterFails() {
        var dpadCalls = 0
        var focusedClickCalls = 0

        val result = DiscretePressDispatch.shortOk(
            dpadCenter = {
                dpadCalls++
                false
            },
            focusedClick = {
                focusedClickCalls++
                true
            }
        )

        assertEquals(DiscretePressResult.SUCCESS, result)
        assertEquals(1, dpadCalls)
        assertEquals(1, focusedClickCalls)
    }

    @Test
    fun shortOkReportsFailureWhenBothDispatchMethodsFail() {
        val result = DiscretePressDispatch.shortOk(
            dpadCenter = { false },
            focusedClick = { false }
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
