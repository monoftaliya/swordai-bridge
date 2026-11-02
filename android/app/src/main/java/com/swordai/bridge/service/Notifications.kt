package com.swordai.bridge.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.swordai.bridge.MainActivity
import com.swordai.bridge.R
import com.swordai.bridge.agent.ConfirmationManager
import com.swordai.bridge.capture.CaptureConsentActivity

/** Каналы и уведомления приложения. */
object Notifications {
    const val CHANNEL_SERVICE = "service"
    const val CHANNEL_CONFIRM = "confirm"
    const val SERVICE_NOTIFICATION_ID = 1001
    private const val CAPTURE_REQUEST_ID = 1002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val service = NotificationChannel(
            CHANNEL_SERVICE,
            context.getString(R.string.channel_service),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_service_desc)
            setShowBadge(false)
        }
        val confirm = NotificationChannel(
            CHANNEL_CONFIRM,
            context.getString(R.string.channel_confirm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_confirm_desc)
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(service, confirm))
    }

    private fun canNotify(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun actionIntent(context: Context, action: String, requestCode: Int, extraId: String? = null): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java).setAction(action)
        if (extraId != null) intent.putExtra(ActionReceiver.EXTRA_ID, extraId)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun serviceNotification(context: Context, title: String, text: String, controlEnabled: Boolean): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF3D7BFF.toInt())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context, 10))
        if (controlEnabled) {
            builder.addAction(R.drawable.ic_stop, "Стоп", actionIntent(context, ActionReceiver.ACTION_EMERGENCY_STOP, 11))
        } else {
            builder.addAction(0, "Открыть", openAppIntent(context, 12))
        }
        builder.addAction(0, "Отключить", actionIntent(context, ActionReceiver.ACTION_DISCONNECT, 13))
        return builder.build()
    }

    private fun confirmationId(id: String): Int = 20_000 + (id.hashCode() and 0xFFFF)

    fun showConfirmation(context: Context, request: ConfirmationManager.Request) {
        if (!canNotify(context)) return
        val notificationId = confirmationId(request.id)
        val approve = NotificationCompat.Action.Builder(
            0,
            "Разрешить",
            actionIntent(context, ActionReceiver.ACTION_APPROVE, notificationId * 2, request.id),
        ).setAuthenticationRequired(true).build() // на Android 12+ требует разблокировки
        val deny = NotificationCompat.Action.Builder(
            0,
            "Отклонить",
            actionIntent(context, ActionReceiver.ACTION_DENY, notificationId * 2 + 1, request.id),
        ).build()
        val text = "${request.client}: ${request.title}"
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Подтвердите действие ИИ")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${request.detail}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setColor(0xFF8A5CFF.toInt())
            .setAutoCancel(false)
            .setOngoing(true)
            .setTimeoutAfter(request.expiresAt - System.currentTimeMillis())
            .setContentIntent(openAppIntent(context, notificationId))
            .addAction(deny)
            .addAction(approve)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // разрешение на уведомления отозвано — подтверждение доступно в приложении
        }
    }

    fun cancelConfirmation(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(confirmationId(id))
    }

    fun showCaptureRequest(context: Context) {
        if (!canNotify(context)) return
        val intent = PendingIntent.getActivity(
            context,
            30,
            Intent(context, CaptureConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ИИ запрашивает скриншот")
            .setContentText("Нажмите, чтобы разрешить захват экрана")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(intent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(CAPTURE_REQUEST_ID, notification)
        } catch (e: SecurityException) {
            // нет разрешения на уведомления
        }
    }

    fun cancelCaptureRequest(context: Context) {
        NotificationManagerCompat.from(context).cancel(CAPTURE_REQUEST_ID)
    }
}
