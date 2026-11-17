package com.example.tvdash

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
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
    private const val MAX_NATIVE_OUTPUT_BYTES = 32 * 1024

    // Native sequence is nominally 1,200 ms ready + 1,200 ms hold + 150 ms settle = 2,550 ms.
    // Leave termination headroom inside TvLink's 4,000 ms response-read timeout.
    private const val PROCESS_TIMEOUT_MS = 2_900L
    private const val TERM_GRACE_MS = 250L
    private const val KILL_GRACE_MS = 250L

    /**
     * A durable marker survives app-process death. It is created before root launch and removed
     * only after a fully audited successful exit, preventing a restarted app from overlapping an
     * orphaned su/native child after a crash or timeout.
     */
    @Volatile
    private var nativeDispatchQuarantined = false

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
        if (nativeDispatchQuarantined) {
            A11yDiagnostics.recordEvent(
                context,
                "uinput-blocked",
                "reason=in-memory-quarantine"
            )
            Log.e(TAG, "native dispatch quarantined after a previous uncertain process outcome")
            return Result.UNCERTAIN
        }

        val appContext = context.applicationContext
        val quarantineFile = File(appContext.noBackupFilesDir, "$BINARY_NAME.quarantine")
        if (nativeDispatchQuarantined || quarantineFile.exists()) {
            A11yDiagnostics.recordEvent(
                appContext,
                "uinput-blocked",
                "reason=durable-quarantine"
            )
            nativeDispatchQuarantined = true
            Log.e(TAG, "durable native dispatch quarantine exists; refusing another root launch")
            return Result.UNCERTAIN
        }

        val executable = installAndVerify(appContext) ?: return Result.UNAVAILABLE
        val outputFile = File(appContext.noBackupFilesDir, "$BINARY_NAME.output")

        // Persist quarantine before starting su. If the app dies between this point and audited
        // completion, a restarted app cannot issue a second potentially overlapping key sequence.
        try {
            FileOutputStream(quarantineFile).use { marker ->
                marker.write("NATIVE_DISPATCH_IN_PROGRESS".toByteArray(Charsets.UTF_8))
                marker.fd.sync()
            }
        } catch (e: Exception) {
            nativeDispatchQuarantined = true
            A11yDiagnostics.recordEvent(
                appContext,
                "uinput-quarantine-failed",
                "exception=${e.javaClass.name} message=${e.message ?: "no-message"}"
            )
            Log.e(TAG, "could not persist native dispatch quarantine; refusing root launch", e)
            return Result.UNCERTAIN
        }

        // A start failure is the only post-staging condition that proves native execution did not
        // begin. Remove the durable marker before allowing accessibility fallback.
        val process = try {
            ProcessBuilder(
                "su", "0", "sh", "-c", "exec \"\$1\"", "tvdash-uinput", executable.absolutePath
            )
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
        } catch (e: Exception) {
            val markerRemoved = runCatching { quarantineFile.delete() }.getOrDefault(false)
            nativeDispatchQuarantined = !markerRemoved
            A11yDiagnostics.recordEvent(
                appContext,
                "uinput-launch-failed",
                "exception=${e.javaClass.name} message=${e.message ?: "no-message"} markerRemoved=$markerRemoved"
            )
            Log.w(TAG, "native process could not be started; quarantineRemoved=$markerRemoved", e)
            return if (markerRemoved) Result.UNAVAILABLE else Result.UNCERTAIN
        }

        A11yDiagnostics.recordEvent(
            appContext,
            "uinput-process-started",
            "result=started"
        )

        try {
            if (!process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                val terminated = terminateProcess(process)
                nativeDispatchQuarantined = true
                A11yDiagnostics.recordEvent(
                    appContext,
                    "uinput-result",
                    "result=UNCERTAIN reason=process-timeout terminationConfirmed=$terminated"
                )
                Log.e(
                    TAG,
                    "native process timed out; result=UNCERTAIN terminationConfirmed=$terminated; fallback prohibited"
                )
                return Result.UNCERTAIN
            }

            val exitCode = process.exitValue()
            val output = readNativeOutput(outputFile)
            if (output == null) {
                nativeDispatchQuarantined = true
                A11yDiagnostics.recordEvent(
                    appContext,
                    "uinput-result",
                    "result=UNCERTAIN reason=native-output-missing"
                )
                Log.e(TAG, "native process exited but telemetry was missing or incomplete; result=UNCERTAIN")
                return Result.UNCERTAIN
            }

            logNativeOutput(output)
            val result = auditResult(exitCode, output)
            A11yDiagnostics.recordEvent(
                appContext,
                "uinput-result",
                "result=$result exitCode=$exitCode"
            )
            if (result == Result.SENT) {
                val markerRemoved = runCatching { quarantineFile.delete() }.getOrDefault(false)
                if (!markerRemoved) {
                    nativeDispatchQuarantined = true
                    A11yDiagnostics.recordEvent(
                        appContext,
                        "uinput-quarantine-remove-failed",
                        "result=UNCERTAIN reason=marker-delete-failed"
                    )
                    Log.e(TAG, "successful native sequence but quarantine marker removal failed; result=UNCERTAIN")
                    return Result.UNCERTAIN
                }
            } else {
                nativeDispatchQuarantined = true
                A11yDiagnostics.recordEvent(
                    appContext,
                    "uinput-quarantined",
                    "reason=native-lifecycle-audit-failed exitCode=$exitCode"
                )
                Log.e(TAG, "native process outcome failed lifecycle audit; durable dispatch quarantine retained")
            }
            Log.i(TAG, "dispatch result=$result exitCode=$exitCode")
            return result
        } catch (e: InterruptedException) {
            val terminated = terminateProcess(process)
            nativeDispatchQuarantined = true
            A11yDiagnostics.recordEvent(
                appContext,
                "uinput-result",
                "result=UNCERTAIN reason=wait-interrupted terminationConfirmed=$terminated"
            )
            Thread.currentThread().interrupt()
            Log.w(
                TAG,
                "native process wait interrupted; result=UNCERTAIN terminationConfirmed=$terminated; fallback prohibited",
                e
            )
            return Result.UNCERTAIN
        } catch (e: Exception) {
            val terminated = terminateProcess(process)
            nativeDispatchQuarantined = true
            A11yDiagnostics.recordEvent(
                appContext,
                "uinput-result",
                "result=UNCERTAIN reason=post-start-exception exception=${e.javaClass.name} message=${e.message ?: "no-message"} terminationConfirmed=$terminated"
            )
            Log.w(
                TAG,
                "native process started but dispatch/telemetry failed; result=UNCERTAIN terminationConfirmed=$terminated",
                e
            )
            return Result.UNCERTAIN
        } finally {
            // Defensive final guard: never let a live Process object escape dispatch silently.
            if (process.isAlive) {
                nativeDispatchQuarantined = true
                val terminated = terminateProcess(process)
                A11yDiagnostics.recordEvent(
                    appContext,
                    "uinput-result",
                    "result=UNCERTAIN reason=final-process-guard terminationConfirmed=$terminated"
                )
                Log.e(TAG, "final process guard terminationConfirmed=$terminated")
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            if (outputFile.exists() && !outputFile.delete()) {
                A11yDiagnostics.recordEvent(
                    appContext,
                    "uinput-output-cleanup",
                    "result=failed reason=temporary-output-delete-failed"
                )
                Log.w(TAG, "could not delete temporary native telemetry file")
            }
        }
    }

    private fun stagingUnavailable(
        context: Context,
        reason: String,
        error: Exception? = null
    ): File? {
        val detail = buildString {
            append("reason=").append(reason)
            if (error != null) {
                append(" exception=").append(error.javaClass.name)
                append(" message=").append(error.message ?: "no-message")
            }
        }

        A11yDiagnostics.recordEvent(
            context,
            "uinput-stage-failed",
            detail
        )

        if (error == null) {
            Log.e(TAG, "native staging unavailable: $detail")
        } else {
            Log.e(TAG, "native staging unavailable: $detail", error)
        }

        return null
    }

    /**
     * Copy the immutable APK asset to an executable app-private file and verify its exact digest.
     * Re-copy after an app update or if the previous extracted file is missing/corrupt.
     */
    private fun installAndVerify(context: Context): File? {
        if (!Build.SUPPORTED_32_BIT_ABIS.contains(ABI)) {
            return stagingUnavailable(
                context,
                "abi-unsupported supported=${Build.SUPPORTED_32_BIT_ABIS.joinToString()}"
            )
        }

        return try {
            val expected = context.assets.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readLine()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            }
            if (expected == null || !expected.matches(Regex("[0-9a-fA-F]{64}"))) {
                return stagingUnavailable(context, "manifest-missing-or-malformed")
            }

            val directory = File(context.noBackupFilesDir, "native")
            if (!directory.isDirectory && !directory.mkdirs()) {
                return stagingUnavailable(context, "native-directory-create-failed")
            }

            val destination = File(directory, BINARY_NAME)
            if (destination.isFile && destination.canExecute() &&
                sha256(destination).equals(expected, ignoreCase = true)
            ) {
                A11yDiagnostics.recordEvent(
                    context,
                    "uinput-stage",
                    "result=verified-cache"
                )
                return destination
            }

            val temporary = File(directory, "$BINARY_NAME.tmp")
            try {
                context.assets.open(BINARY_ASSET).use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                }

                val actual = sha256(temporary)
                if (!actual.equals(expected, ignoreCase = true)) {
                    return stagingUnavailable(
                        context,
                        "asset-hash-mismatch expected=$expected actual=$actual"
                    )
                }
                if (!temporary.setReadable(true, true) ||
                    !temporary.setWritable(true, true) ||
                    !temporary.setExecutable(true, true)
                ) {
                    return stagingUnavailable(context, "owner-executable-permissions-failed")
                }
                if (destination.exists() && !destination.delete()) {
                    return stagingUnavailable(context, "stale-destination-delete-failed")
                }
                if (!temporary.renameTo(destination)) {
                    return stagingUnavailable(context, "atomic-rename-failed")
                }
            } finally {
                if (temporary.exists()) temporary.delete()
            }

            if (!destination.isFile || !destination.canExecute() ||
                !sha256(destination).equals(expected, ignoreCase = true)
            ) {
                return stagingUnavailable(context, "post-install-verification-failed")
            }

            A11yDiagnostics.recordEvent(
                context,
                "uinput-stage",
                "result=installed-verified"
            )
            Log.i(TAG, "native executable installed and SHA-256 verified")
            destination
        } catch (e: Exception) {
            stagingUnavailable(context, "staging-exception", e)
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

    /**
     * After root launch, telemetry cannot prove that a missing KEY_DOWN marker was never emitted:
     * stdout itself may be truncated or lost. Therefore only the complete success sequence is SENT;
     * every other completed native execution is UNCERTAIN and retains durable quarantine.
     */
    private fun auditResult(exitCode: Int, output: String): Result {
        val requiredStates = listOf(
            "STATE=UINPUT_OPEN",
            "STATE=DEVICE_CREATED",
            "STATE=DEVICE_READY",
            "STATE=KEY_DOWN_BEGIN",
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

    /**
     * Best-effort TERM followed by KILL. The return value confirms only that this Process exited;
     * callers still treat a timed-out or interrupted input attempt as UNCERTAIN.
     */
    private fun terminateProcess(process: Process): Boolean {
        if (!process.isAlive) return true

        runCatching { process.destroy() }
            .onFailure { Log.w(TAG, "TERM request failed", it) }
        var terminated = waitForExit(process, TERM_GRACE_MS)

        if (!terminated && process.isAlive) {
            runCatching { process.destroyForcibly() }
                .onFailure { Log.e(TAG, "KILL request failed", it) }
            terminated = waitForExit(process, KILL_GRACE_MS)
        }

        val confirmed = terminated || !process.isAlive
        if (!confirmed) {
            Log.e(TAG, "native Process remains alive after TERM and KILL requests")
        }
        return confirmed
    }

    private fun waitForExit(process: Process, timeoutMs: Long): Boolean {
        return try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            Log.w(TAG, "process termination wait failed", e)
            false
        }
    }

    /** Read redirected telemetry with a hard size cap; never wait on a process-owned output pipe. */
    private fun readNativeOutput(file: File): String? {
        return try {
            if (!file.isFile || file.length() > MAX_NATIVE_OUTPUT_BYTES) return null
            val output = ByteArrayOutputStream()
            FileInputStream(file).use { input ->
                val buffer = ByteArray(4_096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size() + count > MAX_NATIVE_OUTPUT_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            String(output.toByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "could not read native process telemetry", e)
            null
        }
    }

    private fun logNativeOutput(output: String) {
        output.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            Log.i(TAG, "native: $line")
        }
    }
}
