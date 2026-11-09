package com.example.tvdash

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Runs the validated, KEY_ENTER-only uinput hold as a short-lived root process.
 *
 * The binary is packaged as an APK asset rather than loaded as an Android library. Its SHA-256
 * manifest is checked before execution, and the copied file is kept in this app's no-backup area.
 */
internal object UinputEnterHold {
    private const val TAG = "TvDashUinput"
    private const val ABI = "armeabi-v7a"
    private const val ASSET_DIR = "uinput/$ABI"
    private const val BINARY_NAME = "uinput_enter_hold"
    private const val BINARY_ASSET = "$ASSET_DIR/$BINARY_NAME"
    private const val MANIFEST_ASSET = "$BINARY_ASSET.sha256"
    private const val EXPECTED_HOLD_MS = 1_200L

    // Native sequence: 1,200 ms device-ready delay + 1,200 ms key hold + 150 ms release settle.
    // Keep the process deadline below TvLink's 4,000 ms response-read timeout.
    private const val PROCESS_TIMEOUT_MS = 3_200L
    private const val TERM_GRACE_MS = 350L
    private const val KILL_GRACE_MS = 250L

    internal enum class Result {
        SENT,
        UNAVAILABLE,
        UNCERTAIN
    }

    /**
     * SENT means the native program reported the complete down/hold/up/cleanup sequence.
     * UNCERTAIN means a key-down may have reached Android; callers must not try another input route.
     */
    @Synchronized
    fun dispatch(context: Context): Result {
        val executable = installAndVerify(context.applicationContext) ?: return Result.UNAVAILABLE
        var process: Process? = null

        try {
            // The path is passed as a positional argument, not interpolated into shell source.
            process = ProcessBuilder(
                "su", "0", "sh", "-c", "exec \"\$1\"", "tvdash-uinput", executable.absolutePath
            ).redirectErrorStream(true).start()

            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "native process timeout after ${PROCESS_TIMEOUT_MS}ms")
                val output = terminateAndRead(process)
                logNativeOutput(output)
                return if (output.contains("STATE=KEY_DOWN_BEGIN")) Result.UNCERTAIN else Result.UNAVAILABLE
            }

            val exitCode = process.exitValue()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            logNativeOutput(output)
            val result = auditResult(exitCode, output)
            Log.i(TAG, "dispatch result=$result exitCode=$exitCode")
            return result
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            val output = process?.let { terminateAndRead(it) }.orEmpty()
            logNativeOutput(output)
            Log.w(TAG, "native process wait interrupted", e)
            return if (output.contains("STATE=KEY_DOWN_BEGIN")) Result.UNCERTAIN else Result.UNAVAILABLE
        } catch (e: Exception) {
            val output = process?.let { terminateAndRead(it) }.orEmpty()
            logNativeOutput(output)
            Log.w(TAG, "native uinput dispatch failed", e)
            return if (output.contains("STATE=KEY_DOWN_BEGIN")) Result.UNCERTAIN else Result.UNAVAILABLE
        } finally {
            process?.let {
                if (it.isAlive) {
                    it.destroy()
                    if (!runCatching { it.waitFor(TERM_GRACE_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                        it.destroyForcibly()
                    }
                }
                runCatching { it.inputStream.close() }
                runCatching { it.errorStream.close() }
                runCatching { it.outputStream.close() }
            }
        }
    }

    /**
     * Copy the immutable APK asset to an executable app-private file and verify its exact digest.
     * Re-copy after an app update or if the previous extracted file is missing/corrupt.
     */
    private fun installAndVerify(context: Context): File? {
        if (!Build.SUPPORTED_32_BIT_ABIS.contains(ABI)) {
            Log.w(TAG, "native ABI unavailable; supported32BitAbis=${Build.SUPPORTED_32_BIT_ABIS.joinToString()}")
            return null
        }

        return try {
            val expected = context.assets.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLine()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            }
            if (expected == null || !expected.matches(Regex("[0-9a-fA-F]{64}"))) {
                Log.e(TAG, "native asset manifest is missing or malformed")
                return null
            }

            val directory = File(context.noBackupFilesDir, "native")
            if (!directory.isDirectory && !directory.mkdirs()) {
                Log.e(TAG, "could not create native executable directory")
                return null
            }

            val destination = File(directory, BINARY_NAME)
            if (destination.isFile && destination.canExecute() &&
                sha256(destination).equals(expected, ignoreCase = true)
            ) {
                return destination
            }

            val temporary = File(directory, "$BINARY_NAME.tmp")
            try {
                context.assets.open(BINARY_ASSET).use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                }

                val actual = sha256(temporary)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Log.e(TAG, "native asset SHA-256 mismatch expected=$expected actual=$actual")
                    return null
                }
                if (!temporary.setReadable(true, true) ||
                    !temporary.setWritable(true, true) ||
                    !temporary.setExecutable(true, true)
                ) {
                    Log.e(TAG, "could not set owner-only executable permissions on native asset")
                    return null
                }
                if (destination.exists() && !destination.delete()) {
                    Log.e(TAG, "could not replace stale native executable")
                    return null
                }
                if (!temporary.renameTo(destination)) {
                    Log.e(TAG, "could not atomically install native executable")
                    return null
                }
            } finally {
                if (temporary.exists()) temporary.delete()
            }

            if (!destination.isFile || !destination.canExecute() ||
                !sha256(destination).equals(expected, ignoreCase = true)
            ) {
                Log.e(TAG, "installed native executable failed post-install verification")
                return null
            }
            Log.i(TAG, "native executable installed and SHA-256 verified")
            destination
        } catch (e: Exception) {
            Log.w(TAG, "native executable staging failed", e)
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private fun auditResult(exitCode: Int, output: String): Result {
        // The native program flushes KEY_DOWN_BEGIN before its first event write.
        if (!output.contains("STATE=KEY_DOWN_BEGIN")) return Result.UNAVAILABLE

        val requiredStates = listOf(
            "STATE=DEVICE_CREATED",
            "STATE=KEY_DOWN_ACCEPTED",
            "STATE=KEY_UP_BEGIN",
            "STATE=KEY_UP_ACCEPTED",
            "STATE=DEVICE_DESTROYED",
            "STATE=UINPUT_FD_CLOSED",
            "STATE=UINPUT_ENTER_HOLD_PASS"
        )
        val actualHold = Regex("HOLD_COMPLETE REQUESTED_MS=1200 ACTUAL_MS=(\\d+)")
            .find(output)?.groupValues?.getOrNull(1)?.toLongOrNull()

        return if (
            exitCode == 0 &&
            requiredStates.all(output::contains) &&
            actualHold != null &&
            actualHold >= EXPECTED_HOLD_MS
        ) {
            Result.SENT
        } else {
            Result.UNCERTAIN
        }
    }

    /** TERM lets the native signal handler emit KEY_UP and destroy uinput before forced termination. */
    private fun terminateAndRead(process: Process): String {
        if (process.isAlive) {
            process.destroy()
            val terminated = try {
                process.waitFor(TERM_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!terminated && process.isAlive) {
                process.destroyForcibly()
                try {
                    process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return if (process.isAlive) {
            Log.e(TAG, "native process remained alive after termination request")
            ""
        } else {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrElse {
                Log.w(TAG, "could not read native process telemetry", it)
                ""
            }
        }
    }

    private fun logNativeOutput(output: String) {
        output.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            Log.i(TAG, "native: $line")
        }
    }
}
