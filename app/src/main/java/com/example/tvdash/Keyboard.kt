package com.example.tvdash

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A keyboard with no keys. Text typed in the TV Remote phone app is committed through Android's normal
 * input-method connection, so it reaches the focused text field of any app that uses the system keyboard.
 */
class RemoteKeyboardService : InputMethodService() {
    companion object { @Volatile var instance: RemoteKeyboardService? = null }

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onEvaluateInputViewShown() = true
    override fun onEvaluateFullscreenMode() = false

    override fun onCreateInputView(): View = TextView(this).apply {
        text = "Type with the TV Remote app on your phone"
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF2C2C2A.toInt())
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(24, 24, 24, 24)
    }

    /** Replaces what the phone has typed so far. Returns false when no text field is waiting for input. */
    fun type(text: String, enter: Boolean, done: Boolean): Boolean = onMain {
        val ic = currentInputConnection
        if (ic == null || !currentInputStarted) return@onMain false
        if (done) ic.finishComposingText()
        else {
            ic.setComposingText(text, 1)
            if (enter) { ic.finishComposingText(); submit(ic) }
        }
        true
    }

    private fun submit(ic: InputConnection) {
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /** The input connection must be used on the main thread; the HTTP server calls from its own threads. */
    private fun onMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        main.post { try { result.set(block()) } finally { latch.countDown() } }
        latch.await(1500, TimeUnit.MILLISECONDS)
        return result.get()
    }
}
