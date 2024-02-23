/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health;

import static com.android.server.lineage.health.Util.msToString;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.android.internal.R;

public class ChargingControlNotification {
    private final NotificationManager mNotificationManager;
    private final Context mContext;

    private static final String INTENT_PARTS =
            "com.android.settings.lineage.health.CHARGING_CONTROL_SETTINGS";
    private static final int CHARGING_CONTROL_NOTIFICATION_ID = 1000;
    private static final String ACTION_CHARGING_CONTROL_CANCEL_ONCE =
            "lineageos.platform.intent.action.CHARGING_CONTROL_CANCEL_ONCE";
    private static final String CHARGING_CONTROL_CHANNEL_ID = "LineageHealthChargingControl";

    private final ChargingControlController mChargingControlController;

    private boolean mIsDoneNotification = false;
    private boolean mIsNotificationPosted = false;

    ChargingControlNotification(Context context, ChargingControlController controller) {
        mContext = context;
        mChargingControlController = controller;

        // Get notification manager
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        // Register notification monitor
        IntentFilter notificationFilter = new IntentFilter(ACTION_CHARGING_CONTROL_CANCEL_ONCE);
        mContext.registerReceiver(new LineageHealthNotificationBroadcastReceiver(),
                notificationFilter);
    }

    public void post(int limit, boolean done) {
        if (mIsNotificationPosted && mIsDoneNotification == done) {
            return;
        }

        if (mIsNotificationPosted) {
            cancel();
        }

        if (done) {
            postChargingDoneNotification(null, limit);
        } else {
            postChargingControlNotification(null, limit);
        }

        mIsNotificationPosted = true;
        mIsDoneNotification = done;
    }

    public void post(Long targetTime, boolean done) {
        if (mIsNotificationPosted && mIsDoneNotification == done) {
            return;
        }

        if (mIsNotificationPosted) {
            cancel();
        }

        if (done) {
            postChargingDoneNotification(targetTime, 0);
        } else {
            postChargingControlNotification(targetTime, 0);
        }

        mIsNotificationPosted = true;
        mIsDoneNotification = done;
    }

    public void cancel() {
        cancelChargingControlNotification();
        mIsNotificationPosted = false;
    }

    public boolean isPosted() {
        return mIsNotificationPosted;
    }

    public boolean isDoneNotification() {
        return mIsDoneNotification;
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent.getAction().equals(ACTION_CHARGING_CONTROL_CANCEL_ONCE)) {
            mChargingControlController.setChargingCancelledOnce();
        }
    }

    private void postChargingControlNotification(Long targetTime, int limit) {
        String title = mContext.getString(R.string.charging_control_notification_title);
        String message;
        if (targetTime != null) {
            message = String.format(
                    mContext.getString(R.string.charging_control_notification_content_target),
                    msToString(targetTime));
        } else {
            message = String.format(
                    mContext.getString(R.string.charging_control_notification_content_limit),
                    limit);
        }

        Intent mainIntent = new Intent(INTENT_PARTS);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(mContext, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent cancelOnceIntent = new Intent(ACTION_CHARGING_CONTROL_CANCEL_ONCE);
        PendingIntent cancelPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                cancelOnceIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder notification =
                new Notification.Builder(mContext, CHARGING_CONTROL_CHANNEL_ID)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setContentIntent(mainPendingIntent)
                        .setSmallIcon(R.drawable.ic_charging_control)
                        .setOngoing(true)
                        .addAction(R.drawable.ic_charging_control,
                                mContext.getString(
                                        R.string.charging_control_notification_cancel_once),
                                cancelPendingIntent);

        createNotificationChannelIfNeeded();
        mNotificationManager.notify(CHARGING_CONTROL_NOTIFICATION_ID, notification.build());
    }

    private void postChargingDoneNotification(Long targetTime, int limit) {
        cancelChargingControlNotification();

        String title = mContext.getString(R.string.charging_control_notification_title);
        String message;
        if (targetTime != null) {
            message = mContext.getString(
                    R.string.charging_control_notification_content_target_reached);
        } else {
            message = String.format(
                    mContext.getString(
                            R.string.charging_control_notification_content_limit_reached),
                    limit);
        }

        Intent mainIntent = new Intent(INTENT_PARTS);
        mainIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(mContext, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder notification = new Notification.Builder(mContext,
                CHARGING_CONTROL_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(mainPendingIntent)
                .setSmallIcon(R.drawable.ic_charging_control)
                .setOngoing(false);

        if (targetTime == null) {
            Intent cancelOnceIntent = new Intent(ACTION_CHARGING_CONTROL_CANCEL_ONCE);
            PendingIntent cancelPendingIntent = PendingIntent.getBroadcast(mContext, 0,
                    cancelOnceIntent, PendingIntent.FLAG_IMMUTABLE);
            notification.addAction(R.drawable.ic_charging_control,
                    mContext.getString(R.string.charging_control_notification_cancel_once),
                    cancelPendingIntent);
        }

        createNotificationChannelIfNeeded();
        mNotificationManager.notify(CHARGING_CONTROL_NOTIFICATION_ID, notification.build());
    }

    private void createNotificationChannelIfNeeded() {
        String id = CHARGING_CONTROL_CHANNEL_ID;
        NotificationChannel channel = mNotificationManager.getNotificationChannel(id);
        if (channel != null) {
            return;
        }

        String name = mContext.getString(R.string.charging_control_notification_channel);
        int importance = NotificationManager.IMPORTANCE_LOW;
        NotificationChannel batteryHealthChannel = new NotificationChannel(id, name,
                importance);
        batteryHealthChannel.setBlockable(true);
        mNotificationManager.createNotificationChannel(batteryHealthChannel);
    }

    private void cancelChargingControlNotification() {
        mNotificationManager.cancel(CHARGING_CONTROL_NOTIFICATION_ID);
    }

    /* Notification Broadcast Receiver */
    private class LineageHealthNotificationBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleNotificationIntent(intent);
        }
    }
}
