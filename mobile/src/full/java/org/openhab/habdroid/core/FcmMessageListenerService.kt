/*
 * Copyright (c) 2010-2018, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.core

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.service.notification.StatusBarNotification

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking

import org.openhab.habdroid.R
import org.openhab.habdroid.core.connection.Connection
import org.openhab.habdroid.core.connection.ConnectionFactory
import org.openhab.habdroid.ui.MainActivity
import org.openhab.habdroid.util.getNotificationTone
import org.openhab.habdroid.util.getNotificationVibrationPattern
import org.openhab.habdroid.util.getPrefs

import java.util.Locale

class FcmMessageListenerService : FirebaseMessagingService() {
    override fun onNewToken(token: String?) {
        super.onNewToken(token)
        FcmRegistrationService.scheduleRegistration(this)
    }

    override fun onMessageReceived(message: RemoteMessage?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val data = message?.data ?: return
        val messageType = data["type"] ?: return
        val notificationId = data[EXTRA_NOTIFICATION_ID]?.toInt() ?: 1

        when (messageType) {
            "notification" -> {
                val messageText = data["message"]
                val severity = data["severity"]
                val icon = data["icon"]
                val persistedId = data["persistedId"]
                // Older versions of openhab-cloud didn't send the notification generation
                // timestamp, so use the (undocumented) google.sent_time as a time reference
                // in that case. If that also isn't present, don't show time at all.
                val timestamp = if (data.containsKey("timestamp"))
                    (data["timestamp"]?.toLong() ?: 0) else message.sentTime
                val channelId = if (severity.isNullOrEmpty())
                    CHANNEL_ID_DEFAULT else String.format(Locale.US, CHANNEL_ID_FORMAT_SEVERITY, severity)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val name = if (severity.isNullOrEmpty())
                        getString(R.string.notification_channel_default)
                    else
                        getString(R.string.notification_channel_severity_value, severity)

                    with (NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_DEFAULT)) {
                        setShowBadge(true)
                        enableVibration(true)
                        nm.createNotificationChannel(this)
                    }
                }

                val n = runBlocking {
                    makeNotification(messageText, channelId, icon, timestamp, persistedId, notificationId)
                }
                nm.notify(notificationId, n)

                if (HAS_GROUPING_SUPPORT) {
                    val count = getGcmNotificationCount(nm.activeNotifications)
                    nm.notify(SUMMARY_NOTIFICATION_ID, makeSummaryNotification(count, timestamp))
                }
            }
            "hideNotification" -> {
                nm.cancel(notificationId)
                if (HAS_GROUPING_SUPPORT) {
                    val active = nm.activeNotifications
                    if (notificationId != SUMMARY_NOTIFICATION_ID && getGcmNotificationCount(active) == 0) {
                        // Cancel summary when removing the last sub-notification
                        nm.cancel(SUMMARY_NOTIFICATION_ID)
                    } else if (notificationId == SUMMARY_NOTIFICATION_ID) {
                        // Cancel all sub-notifications when removing the summary
                        for (n in active) {
                            nm.cancel(n.id)
                        }
                    }
                }
            }
        }
    }

    private fun makeNotificationClickIntent(persistedId: String?, notificationId: Int): PendingIntent {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_NOTIFICATION_SELECTED
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(MainActivity.EXTRA_PERSISTED_NOTIFICATION_ID, persistedId)
        }
        return PendingIntent.getActivity(this, notificationId,
                contentIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private suspend fun makeNotification(msg: String?, channelId: String, icon: String?,
                                 timestamp: Long, persistedId: String?, notificationId: Int): Notification {
        var iconBitmap: Bitmap? = null

        if (icon != null) {
            val connection = ConnectionFactory.getConnection(Connection.TYPE_CLOUD)
            if (connection != null) {
                iconBitmap = connection.httpClient
                        .get("images/$icon.png", timeoutMillis = 1000)
                        .asBitmap(0, false)
                        .response
            }
        }

        val contentIntent = makeNotificationClickIntent(persistedId, notificationId)

        val publicText = resources.getQuantityString(
                R.plurals.summary_notification_text, 1, 1)
        val publicVersion = makeNotificationBuilder(channelId, timestamp)
                .setContentText(publicText)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentIntent)
                .build()

        return makeNotificationBuilder(channelId, timestamp)
                .setLargeIcon(iconBitmap)
                .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
                .setSound(getPrefs().getNotificationTone())
                .setContentText(msg)
                .setContentIntent(contentIntent)
                .setDeleteIntent(FcmRegistrationService.createHideNotificationIntent(this, notificationId))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build()
    }

    @TargetApi(24)
    private fun makeSummaryNotification(subNotificationCount: Int, timestamp: Long): Notification {
        val text = resources.getQuantityString(R.plurals.summary_notification_text,
                subNotificationCount, subNotificationCount)
        val clickIntent = makeNotificationClickIntent(null, SUMMARY_NOTIFICATION_ID)
        val publicVersion = makeNotificationBuilder(CHANNEL_ID_DEFAULT, timestamp)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentText(text)
                .setContentIntent(clickIntent)
                .build()
        return makeNotificationBuilder(CHANNEL_ID_DEFAULT, timestamp)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setGroupSummary(true)
                .setGroup("gcm")
                .setContentText(text)
                .setPublicVersion(publicVersion)
                .setContentIntent(clickIntent)
                .setDeleteIntent(FcmRegistrationService.createHideNotificationIntent(this,
                        SUMMARY_NOTIFICATION_ID))
                .build()
    }

    private fun makeNotificationBuilder(channelId: String, timestamp: Long): NotificationCompat.Builder {
        return NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_openhab_appicon_white_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setWhen(timestamp)
                .setShowWhen(timestamp != 0L)
                .setColor(ContextCompat.getColor(this, R.color.openhab_orange))
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setLights(ContextCompat.getColor(this, R.color.openhab_orange), 3000, 3000)
                .setVibrate(getPrefs().getNotificationVibrationPattern(this))
                .setGroup("gcm")
    }

    @TargetApi(23)
    private fun getGcmNotificationCount(active: Array<StatusBarNotification>): Int {
        return active.count { n -> n.id != 0 && (n.groupKey?.endsWith("gcm") == true) }
    }

    companion object {
        internal const val EXTRA_NOTIFICATION_ID = "notificationId"

        private const val CHANNEL_ID_DEFAULT = "default"
        private const val CHANNEL_ID_FORMAT_SEVERITY = "severity-%s"
        private const val SUMMARY_NOTIFICATION_ID = 0

        // Notification grouping is only available on N or higher, as mentioned in
        // https://developer.android.com/guide/topics/ui/notifiers/notifications#bundle
        private val HAS_GROUPING_SUPPORT = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }
}
