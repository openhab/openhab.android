/*
 * Copyright (c) 2010-2018, openHAB.org and others.
 *
 *   All rights reserved. This program and the accompanying materials
 *   are made available under the terms of the Eclipse Public License v1.0
 *   which accompanies this distribution, and is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 */

package org.openhab.habdroid.core;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;

import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;
import org.openhab.habdroid.BuildConfig;
import org.openhab.habdroid.core.connection.CloudConnection;
import org.openhab.habdroid.core.connection.Connection;
import org.openhab.habdroid.core.connection.ConnectionFactory;
import org.openhab.habdroid.util.SyncHttpClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;

public class GcmRegistrationService extends JobIntentService {
    private static final String TAG = GcmRegistrationService.class.getSimpleName();

    private static final int JOB_ID = 1000;

    private static final String ACTION_REGISTER = "org.openhab.habdroid.action.REGISTER_GCM";
    private static final String ACTION_HIDE_NOTIFICATION =
            "org.openhab.habdroid.action.HIDE_NOTIFICATION";
    private static final String EXTRA_NOTIFICATION_ID = "notificationId";

    static void scheduleRegistration(Context context) {
        Intent intent = new Intent(context, GcmRegistrationService.class)
                .setAction(GcmRegistrationService.ACTION_REGISTER);
        JobIntentService.enqueueWork(context, GcmRegistrationService.class, JOB_ID, intent);
    }

    static void scheduleHideNotification(Context context, int notificationId) {
        JobIntentService.enqueueWork(context, GcmRegistrationService.class, JOB_ID,
                makeHideNotificationIntent(context, notificationId));
    }

    static PendingIntent createHideNotificationIntent(Context context, int notificationId) {
        return ProxyReceiver.wrap(context, makeHideNotificationIntent(context, notificationId),
                notificationId);
    }

    private static Intent makeHideNotificationIntent(Context context, int notificationId) {
        return new Intent(context, GcmRegistrationService.class)
                .setAction(GcmRegistrationService.ACTION_HIDE_NOTIFICATION)
                .putExtra(GcmRegistrationService.EXTRA_NOTIFICATION_ID, notificationId);
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        ConnectionFactory.waitForInitialization();
        CloudConnection connection =
                (CloudConnection) ConnectionFactory.getConnection(Connection.TYPE_CLOUD);
        if (connection == null) {
            return;
        }
        String action = intent.getAction();
        if (action == null) {
            return;
        }

        switch (action) {
            case ACTION_REGISTER:
                try {
                    registerGcm(connection);
                } catch (IOException e) {
                    CloudMessagingHelper.sRegistrationFailureReason = e;
                    Log.e(TAG, "GCM registration failed", e);
                }
                CloudMessagingHelper.sRegistrationDone = true;
                break;
            case ACTION_HIDE_NOTIFICATION:
                int id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
                if (id >= 0) {
                    try {
                        sendHideNotificationRequest(id, connection.getMessagingSenderId());
                    } catch (IOException e) {
                        Log.e(TAG, "Failed sending notification hide message", e);
                    }
                }
                break;
            default:
                break;
        }
    }

    private void registerGcm(CloudConnection connection) throws IOException {
        InstanceID instanceId = InstanceID.getInstance(this);
        String token = instanceId.getToken(connection.getMessagingSenderId(),
                GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
        String deviceName = getDeviceName()
                + (BuildConfig.FLAVOR.toLowerCase().contains("beta") ? " (Beta)" : "");
        deviceName = URLEncoder.encode(deviceName, "UTF-8");
        String deviceId = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ANDROID_ID) + BuildConfig.FLAVOR;

        String regUrl = String.format(Locale.US,
                "addAndroidRegistration?deviceId=%s&deviceModel=%s&regId=%s",
                deviceId, deviceName, token);

        Log.d(TAG, "Register device at openHAB-cloud with URL: " + regUrl);
        SyncHttpClient.HttpStatusResult result =
                connection.getSyncHttpClient().get(regUrl).asStatus();
        if (result.isSuccessful()) {
            Log.d(TAG, "GCM reg id success");
        } else {
            Log.e(TAG, "GCM reg id error: " + result.error);
        }
        CloudMessagingHelper.sRegistrationFailureReason = result.error;
    }

    /**
     * @author https://stackoverflow.com/a/12707479
     */
    private String getDeviceName() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return capitalize(model);
        } else {
            return capitalize(manufacturer) + " " + model;
        }
    }

    /**
     * @author https://stackoverflow.com/a/12707479
     */
    private String capitalize(String s) {
        if (TextUtils.isEmpty(s)) {
            return "";
        }
        char first = s.charAt(0);
        if (Character.isUpperCase(first)) {
            return s;
        } else {
            return Character.toUpperCase(first) + s.substring(1);
        }
    }

    private void sendHideNotificationRequest(int notificationId, String senderId)
            throws IOException {
        GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(this);
        Bundle sendBundle = new Bundle();
        sendBundle.putString("type", "hideNotification");
        sendBundle.putString("notificationId", String.valueOf(notificationId));
        gcm.send(senderId + "@gcm.googleapis.com", "1", sendBundle);
    }

    public static class ProxyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Intent actual = intent.getParcelableExtra("intent");
            JobIntentService.enqueueWork(context, GcmRegistrationService.class, JOB_ID, actual);
        }

        private static PendingIntent wrap(Context context, Intent intent, int id) {
            Intent wrapped = new Intent(context, ProxyReceiver.class)
                    .putExtra("intent", intent);
            return PendingIntent.getBroadcast(context, id,
                    wrapped, PendingIntent.FLAG_UPDATE_CURRENT);
        }
    }
}
