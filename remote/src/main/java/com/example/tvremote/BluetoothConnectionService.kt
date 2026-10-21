package com.example.tvremote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Keeps the phone process important while the selected TV's Bluetooth data connection is in use.
 * Started only from a visible activity after Bluetooth is configured and its runtime permission is granted.
 */
class BluetoothConnectionService : Service() {
    companion object {
        private const val TAG = "BluetoothConnection"
        private const val CHANNEL_ID = "tv_bluetooth_connection"
        private const val NOTIFICATION_ID = 4102

        /**
         * Call only while the app is visible. A connected-device foreground service is not a general-purpose
         * background-start loophole; Android restricts starting it from the background on modern versions.
         */
        fun startIfConfigured(context: Context) {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("remote", Context.MODE_PRIVATE)
            if (prefs.getString("bt_target", null).isNullOrBlank()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return

            val enabled = try {
                app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
            } catch (e: SecurityException) {
                false
            }
            if (!enabled) return

            try {
                ContextCompat.startForegroundService(app, Intent(app, BluetoothConnectionService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start Bluetooth foreground service", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            BluetoothDataClientProvider.serviceStarted(applicationContext)
        } catch (e: SecurityException) {
            Log.e(TAG, "Android rejected the connected-device foreground service", e)
            stopSelf()
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not start Bluetooth foreground service", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BluetoothDataClientProvider.reconnectNow()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        BluetoothDataClientProvider.serviceStopped()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "TV Bluetooth connection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the TV's Bluetooth control connection ready in the background."
                }
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TV Remote connection active")
            .setContentText("Keeping the selected TV's Bluetooth connection ready.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}

/**
 * One RFCOMM client shared by the foreground service and the UI graph. This prevents the service from opening a
 * second socket to the TV, whose RFCOMM listener accepts only one phone at a time.
 */
internal object BluetoothDataClientProvider {
    private var client: BluetoothDataClient? = null
    private var graphUsers = 0
    private var serviceActive = false

    @Synchronized
    fun acquireGraphClient(context: Context, pin: () -> String): BluetoothDataClient {
        graphUsers += 1
        return getOrCreate(context, pin)
    }

    @Synchronized
    fun releaseGraphClient(expected: BluetoothDataClient) {
        if (client !== expected) return
        graphUsers = (graphUsers - 1).coerceAtLeast(0)
        releaseIfUnused()
    }

    @Synchronized
    fun serviceStarted(context: Context) {
        val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
        getOrCreate(context) { prefs.getString("pin", "")?.trim().orEmpty() }
        serviceActive = true
    }

    @Synchronized
    fun serviceStopped() {
        serviceActive = false
        releaseIfUnused()
    }

    @Synchronized
    fun reconnectNow() {
        client?.reconnectNow()
    }

    private fun getOrCreate(context: Context, pin: () -> String): BluetoothDataClient {
        client?.let { return it }
        return BluetoothDataClient(context.applicationContext, pin).also { client = it }
    }

    private fun releaseIfUnused() {
        if (graphUsers == 0 && !serviceActive) {
            client?.close()
            client = null
        }
    }
}
