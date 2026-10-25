package com.example.tvdash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Receives PackageInstaller status callbacks using the app-created PendingIntent capability.
 *
 * This receiver is non-exported in AndroidManifest.xml. The PackageInstaller can deliver its
 * callback through the PendingIntent, while other applications cannot directly forge status
 * broadcasts or supply arbitrary nested confirmation intents.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_COMPLETE) {
            Log.w(TAG, "Ignoring unexpected installer callback action.")
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, STATUS_UNKNOWN)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_STATUS_MESSAGE_LENGTH)
            ?: "No status message supplied by PackageInstaller."

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Package installation completed successfully.")
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                launchConfirmation(context, intent)
            }

            PackageInstaller.STATUS_FAILURE -> {
                Log.e(TAG, "Package installation failed (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.w(TAG, "Package installation was aborted (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.e(TAG, "Package installation was blocked (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Log.e(TAG, "Package installation conflicted with an installed package (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Log.e(TAG, "Package is incompatible with this device (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Log.e(TAG, "PackageInstaller rejected the package (status=$status): $message")
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Insufficient storage for package installation (status=$status): $message")
            }

            else -> {
                Log.w(TAG, "PackageInstaller returned status=$status: $message")
            }
        }
    }

    private fun launchConfirmation(context: Context, callback: Intent) {
        val confirmationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callback.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            callback.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

        if (confirmationIntent == null) {
            Log.e(TAG, "PackageInstaller requested user confirmation but supplied no confirmation intent.")
            return
        }

        try {
            context.startActivity(
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "Launched PackageInstaller user-confirmation UI.")
        } catch (exception: SecurityException) {
            Log.e(TAG, "Permission denied launching PackageInstaller confirmation UI.", exception)
        } catch (exception: android.content.ActivityNotFoundException) {
            Log.e(TAG, "PackageInstaller confirmation UI could not be launched.", exception)
        }
    }

    private companion object {
        const val TAG = "InstallStatusReceiver"
        const val ACTION_INSTALL_COMPLETE = "com.example.tvdash.INSTALL_COMPLETE"
        const val STATUS_UNKNOWN = Int.MIN_VALUE
        const val MAX_STATUS_MESSAGE_LENGTH = 512
    }
}
