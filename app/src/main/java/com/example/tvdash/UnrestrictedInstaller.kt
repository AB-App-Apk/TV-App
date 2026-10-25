package com.example.tvdash

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Installs an APK using PackageInstaller sessions.
 *
 * PackageInstaller status callbacks are delivered to MainActivity through the explicit
 * com.example.tvdash.INSTALL_COMPLETE intent action. MainActivity must remain registered
 * for that action and handle the standard PackageInstaller status extras.
 */
object UnrestrictedInstaller {

    private const val TAG = "UnrestrictedInstaller"
    private const val SESSION_NAME = "tvdash_unrestricted_install"
    private const val INSTALL_COMPLETE_ACTION = "com.example.tvdash.INSTALL_COMPLETE"
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Starts an install session for [apkFile].
     *
     * This method returns after the install request is committed, not after installation
     * succeeds. The final status is delivered asynchronously to MainActivity.
     */
    @JvmStatic
    fun install(context: Context, apkFile: File) {
        val appContext = context.applicationContext

        if (!apkFile.isFile || !apkFile.canRead()) {
            Log.e(TAG, "APK file does not exist or is not readable: ${apkFile.absolutePath}")
            return
        }

        val expectedLength = apkFile.length()
        if (expectedLength <= 0L) {
            Log.e(TAG, "APK file is empty: ${apkFile.absolutePath}")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            Log.w(TAG, "Allow installation of unknown apps for ${appContext.packageName} first.")
            try {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (exception: SecurityException) {
                Log.e(TAG, "Permission denied opening unknown-app installation settings.", exception)
            } catch (exception: android.content.ActivityNotFoundException) {
                Log.e(TAG, "Unknown-app installation settings are unavailable.", exception)
            }
            return
        }

        val packageInstaller = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setSize(expectedLength)
        }

        var sessionId: Int? = null
        var session: PackageInstaller.Session? = null
        var committed = false

        try {
            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            FileInputStream(apkFile).use { input ->
                session.openWrite(SESSION_NAME, 0L, expectedLength).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesCopied = 0L

                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        if (bytesRead == 0) continue

                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead
                    }

                    if (bytesCopied != expectedLength) {
                        throw IOException(
                            "APK length changed while copying: expected $expectedLength bytes, copied $bytesCopied."
                        )
                    }

                    session.fsync(output)
                }
            }

            val callbackIntent = Intent(appContext, MainActivity::class.java).apply {
                action = INSTALL_COMPLETE_ACTION
                data = Uri.parse("tvdash://installer-status/$sessionId")
                putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
            }

            // PackageInstaller adds status extras to the callback intent. The callback
            // therefore has to be mutable on Android 12+, and the component stays explicit.
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }

            val statusCallback = PendingIntent.getActivity(
                appContext,
                sessionId,
                callbackIntent,
                pendingIntentFlags
            )

            session.commit(statusCallback.intentSender)
            committed = true
            Log.i(TAG, "Committed PackageInstaller session $sessionId.")
        } catch (exception: IOException) {
            Log.e(TAG, "Failed to copy the APK into the installation session.", exception)
        } catch (exception: SecurityException) {
            Log.e(TAG, "Permission denied while preparing the installation session.", exception)
        } catch (exception: IllegalArgumentException) {
            Log.e(TAG, "Invalid PackageInstaller session parameters.", exception)
        } catch (exception: RuntimeException) {
            Log.e(TAG, "PackageInstaller session failed.", exception)
        } finally {
            try {
                session?.close()
            } catch (exception: RuntimeException) {
                Log.w(TAG, "Failed to close PackageInstaller session.", exception)
            }

            if (!committed && sessionId != null) {
                try {
                    packageInstaller.abandonSession(sessionId)
                } catch (exception: SecurityException) {
                    Log.w(TAG, "Permission denied abandoning session $sessionId.", exception)
                } catch (exception: IllegalArgumentException) {
                    Log.w(TAG, "Session $sessionId no longer exists.", exception)
                } catch (exception: RuntimeException) {
                    Log.w(TAG, "Failed to abandon PackageInstaller session $sessionId.", exception)
                }
            }
        }
    }
}
