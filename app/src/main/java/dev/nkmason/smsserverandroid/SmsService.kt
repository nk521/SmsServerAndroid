/*
 * SMS Server — HTTP API on the phone for sending SMS from a computer.
 * Copyright (C) 2026 Nikhil Kumar Singh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package dev.nkmason.smsserverandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the HTTP API server alive while the app is
 * in the background. Restarting the server means restarting this service
 * with the new port; ServerManager swaps the underlying socket.
 */
class SmsService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, ServerManager.savedPort(this))
            ?: ServerManager.savedPort(this)
        val token = ServerManager.savedToken(this)
        startForeground(NOTIF_ID, buildNotification(port))
        ServerManager.start(this, port, token)
        return START_STICKY
    }

    override fun onDestroy() {
        ServerManager.stop()
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        val ip = ServerManager.localIpAddress() ?: "localhost"
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = "http://$ip:$port\nPOST /send for sending SMS"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("SMS Server running on port $port")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SMS Server", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while the HTTP server is running" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "sms_server"
        private const val NOTIF_ID = 1
        const val EXTRA_PORT = "extra_port"

        /** Starts the service, or restarts it with a new port if already running. */
        fun startServer(context: Context, port: Int) {
            val intent = Intent(context, SmsService::class.java).putExtra(EXTRA_PORT, port)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopServer(context: Context) {
            context.stopService(Intent(context, SmsService::class.java))
        }
    }
}
