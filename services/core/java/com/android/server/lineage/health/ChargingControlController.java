/*
 * SPDX-FileCopyrightText: 2023-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health;

import static com.android.server.lineage.health.Util.getTimeMillisFromSecondOfDay;
import static com.android.server.lineage.health.Util.msToString;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.internal.R;
import com.android.server.lineage.health.ccprovider.ChargingControlProvider;
import com.android.server.lineage.health.ccprovider.Deadline;
import com.android.server.lineage.health.ccprovider.Toggle;

import java.io.PrintWriter;

import android.provider.Settings;

import vendor.lineage.health.IChargingControl;

import static com.android.internal.lineage.health.HealthInterface.MODE_NONE;
import static com.android.internal.lineage.health.HealthInterface.MODE_AUTO;
import static com.android.internal.lineage.health.HealthInterface.MODE_MANUAL;
import static com.android.internal.lineage.health.HealthInterface.MODE_LIMIT;

public class ChargingControlController extends LineageHealthFeature {
    private final IChargingControl mChargingControl;
    private final ContentResolver mContentResolver;
    private ChargingControlNotification mChargingNotification;
    private LineageHealthBatteryBroadcastReceiver mBattReceiver;

    // Defaults
    private boolean mDefaultEnabled = false;
    private int mDefaultMode;
    private int mDefaultLimit;
    private int mDefaultStartTime;
    private int mDefaultTargetTime;

    // Settings uris
    private final Uri MODE_URI = Settings.System.getUriFor(
            Settings.System.CHARGING_CONTROL_MODE);
    private final Uri LIMIT_URI = Settings.System.getUriFor(
            Settings.System.CHARGING_CONTROL_LIMIT);
    private final Uri ENABLED_URI = Settings.System.getUriFor(
            Settings.System.CHARGING_CONTROL_ENABLED);
    private final Uri START_TIME_URI = Settings.System.getUriFor(
            Settings.System.CHARGING_CONTROL_START_TIME);
    private final Uri TARGET_TIME_URI = Settings.System.getUriFor(
            Settings.System.CHARGING_CONTROL_TARGET_TIME);

    // Internal state
    private float mBatteryPct;
    private boolean mIsPowerConnected;
    private boolean mIsControlCancelledOnce;

    // Current selected provider
    private ChargingControlProvider mCurrentProvider;

    public ChargingControlController(Context context, Handler handler) {
        super(context, handler);

        mContentResolver = mContext.getContentResolver();
        mChargingControl = IChargingControl.Stub.asInterface(
                ServiceManager.waitForDeclaredService(
                        IChargingControl.DESCRIPTOR + "/default"));

        if (mChargingControl == null) {
            Log.i(TAG, "Lineage Health HAL not found");
            return;
        }

        mChargingNotification = new ChargingControlNotification(context, this);

        mDefaultEnabled = mContext.getResources().getBoolean(
                R.bool.config_chargingControlEnabled);
        mDefaultMode = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlMode);
        mDefaultStartTime = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlStartTime);
        mDefaultTargetTime = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlTargetTime);
        mDefaultLimit = mContext.getResources().getInteger(
                R.integer.config_defaultChargingControlLimit);

        // Set up charging control providers
        mCurrentProvider = new Toggle(mChargingControl, mContext);
        if (!mCurrentProvider.isSupported()) {
            mCurrentProvider = null;
        }

        if (mCurrentProvider == null) {
            mCurrentProvider = new Deadline(mChargingControl, mContext);
            if (!mCurrentProvider.isSupported()) {
                mCurrentProvider = null;
            }
        }

        if (mCurrentProvider == null) {
            Log.wtf(TAG, "No charging control provider is supported");
        }
    }

    @Override
    public boolean isSupported() {
        return mChargingControl != null;
    }

    public boolean isEnabled() {
        return Settings.System.getInt(mContentResolver,
                Settings.System.CHARGING_CONTROL_ENABLED, 0) != 0;
    }

    public boolean setEnabled(boolean enabled) {
        putBoolean(Settings.System.CHARGING_CONTROL_ENABLED, enabled);
        return true;
    }

    public int getMode() {
        return Settings.System.getInt(mContentResolver,
                Settings.System.CHARGING_CONTROL_MODE,
                mDefaultMode);
    }

    public boolean setMode(int mode) {
        if (mode < MODE_NONE || mode > MODE_LIMIT) {
            return false;
        }

        putInt(Settings.System.CHARGING_CONTROL_MODE, mode);
        return true;
    }

    public int getStartTime() {
        return Settings.System.getInt(mContentResolver,
                Settings.System.CHARGING_CONTROL_START_TIME,
                mDefaultStartTime);
    }

    public boolean setStartTime(int time) {
        if (time < 0 || time > 24 * 60 * 60) {
            return false;
        }

        putInt(Settings.System.CHARGING_CONTROL_START_TIME, time);
        return true;
    }

    public int getTargetTime() {
        return Settings.System.getInt(mContentResolver,
                Settings.System.CHARGING_CONTROL_TARGET_TIME,
                mDefaultTargetTime);
    }

    public boolean setTargetTime(int time) {
        if (time < 0 || time > 24 * 60 * 60) {
            return false;
        }

        putInt(Settings.System.CHARGING_CONTROL_TARGET_TIME, time);
        return true;
    }

    public int getLimit() {
        return Settings.System.getInt(mContentResolver,
                Settings.System.CHARGING_CONTROL_LIMIT,
                mDefaultLimit);
    }

    public boolean setLimit(int limit) {
        if (limit < 0 || limit > 100) {
            return false;
        }

        putInt(Settings.System.CHARGING_CONTROL_LIMIT, limit);
        return true;
    }

    public boolean reset() {
        return setEnabled(mDefaultEnabled) && setMode(mDefaultMode) && setLimit(mDefaultLimit)
                && setStartTime(mDefaultStartTime) && setTargetTime(mDefaultTargetTime);
    }

    @Override
    public void onStart() {
        if (mChargingControl == null) {
            return;
        }

        // Register setting observer
        registerSettings(MODE_URI, LIMIT_URI, ENABLED_URI, START_TIME_URI, TARGET_TIME_URI);

        // For devices that do not support bypass, we can only always listen to battery change
        // because we can't distinguish between "unplugged" and "plugged in but not charging".
        if (mCurrentProvider.requiresBatteryLevelMonitoring()) {
            mIsPowerConnected = true;
            onPowerStatus(true);
            handleSettingChange();
            return;
        }

        // Start monitor battery status when power connected
        IntentFilter connectedFilter = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Power connected, start monitoring battery");
                mIsPowerConnected = true;
                onPowerStatus(true);
            }
        }, connectedFilter);

        // Stop monitor battery status when power disconnected
        IntentFilter disconnectedFilter = new IntentFilter(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Power disconnected, stop monitoring battery");
                mIsPowerConnected = false;
                onPowerStatus(false);
            }
        }, disconnectedFilter);

        // Initial monitor
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);
        mIsPowerConnected = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;
        if (mIsPowerConnected) {
            onPowerConnected();
        }

        // Restore settings
        handleSettingChange();
    }

    public boolean isChargingModeSupported(int mode) {
        try {
            return isSupported() && (mChargingControl.getSupportedMode() & mode) != 0;
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    protected void resetInternalState() {
        mIsControlCancelledOnce = false;
        mChargingNotification.cancel();

        mCurrentProvider.reset();
    }

    protected void setChargingCancelledOnce() {
        mIsControlCancelledOnce = true;

        if (mCurrentProvider.requiresBatteryLevelMonitoring()) {
            IntentFilter disconnectFilter = new IntentFilter(
                    Intent.ACTION_POWER_DISCONNECTED);

            // Register a one-time receiver that resets internal state on power
            // disconnection
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.i(TAG, "Power disconnected, reset internal states");
                    resetInternalState();
                    mContext.unregisterReceiver(this);
                }
            }, disconnectFilter);
        }

        mCurrentProvider.disable();
        mChargingNotification.cancel();
    }

    private void onPowerConnected() {
        if (mBattReceiver == null) {
            mBattReceiver = new LineageHealthBatteryBroadcastReceiver();
        }
        IntentFilter battFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mBattReceiver, battFilter);
    }

    private void onPowerDisconnected() {
        if (mBattReceiver != null) {
            mContext.unregisterReceiver(mBattReceiver);
        }

        // On disconnected, reset internal state
        resetInternalState();
    }

    private void onPowerStatus(boolean enable) {
        // Don't do anything if it is not enabled
        if (!isEnabled()) {
            return;
        }

        if (enable) {
            onPowerConnected();
            updateChargeControl();
        } else {
            onPowerDisconnected();
        }
    }

    private ChargeTime getChargeTime() {
        // Get duration to target full time
        final long currentTime = System.currentTimeMillis();
        Log.i(TAG, "Current time is " + msToString(currentTime));
        long targetTime = 0, startTime = currentTime;
        int mode = getMode();

        if (mode == MODE_AUTO) {
            // Use alarm as the target time. Maybe someday we can use a model.
            AlarmManager m = mContext.getSystemService(AlarmManager.class);
            if (m == null) {
                Log.e(TAG, "Failed to get alarm service!");
                mChargingNotification.cancel();
                return null;
            }
            AlarmManager.AlarmClockInfo alarmClockInfo = m.getNextAlarmClock();
            if (alarmClockInfo == null) {
                // We didn't find an alarm. Clear waiting flags because we can't predict anyway
                Log.w(TAG, "No alarm found, auto charging control has no effect");
                mChargingNotification.cancel();
                return null;
            }
            targetTime = alarmClockInfo.getTriggerTime();

            // Start time is 9 hours before the alarm
            startTime = targetTime - DateUtils.HOUR_IN_MILLIS * 9;
        } else if (mode == MODE_MANUAL) {
            // User manually controlled time
            startTime = getTimeMillisFromSecondOfDay(getStartTime());
            targetTime = getTimeMillisFromSecondOfDay(getTargetTime());

            if (startTime > targetTime) {
                if (currentTime > targetTime) {
                    targetTime += DateUtils.DAY_IN_MILLIS;
                } else {
                    startTime -= DateUtils.DAY_IN_MILLIS;
                }
            } else if (currentTime >= targetTime) {
                startTime += DateUtils.DAY_IN_MILLIS;
                targetTime += DateUtils.DAY_IN_MILLIS;
            }
        } else {
            Log.e(TAG, "invalid charging control mode " + mode);
            return null;
        }

        Log.i(TAG, "Got target time " + msToString(targetTime) + ", start time " +
                msToString(startTime) + ", current time " + msToString(currentTime));
        Log.i(TAG, "Raw: " + targetTime + ", " + startTime + ", " + currentTime);

        return new ChargeTime(startTime, targetTime);
    }

    protected void updateChargeControl() {
        if (!isEnabled() || mIsControlCancelledOnce) {
            mCurrentProvider.disable();
            return;
        }

        int mode = getMode();
        int limit = getLimit();

        mCurrentProvider.enable();

        if (mode == MODE_LIMIT) {
            if (mCurrentProvider.update(mBatteryPct, limit)) {
                mChargingNotification.post(limit, mBatteryPct == limit);
            }
        } else {
            ChargeTime chargeTime = getChargeTime();
            if (chargeTime != null) {
                if (mCurrentProvider.update(mBatteryPct, chargeTime.getStartTime(),
                        chargeTime.getTargetTime(), mode)) {
                    mChargingNotification.post(chargeTime.getTargetTime(),
                            mBatteryPct == 100);
                } else {
                    mChargingNotification.cancel();
                }
            }
        }
    }

    /**
     * Whether the current charging control mode supports supports the mode.
     * Available modes:
     *     - ${@link lineageos.health.HealthInterface#MODE_AUTO}
     *     - ${@link lineageos.health.HealthInterface#MODE_MANUAL}
     *     - ${@link lineageos.health.HealthInterface#MODE_LIMIT}
     */
    private boolean isProvideSupportCCMode(int mode) {
        return mCurrentProvider.isChargingControlModeSupported(mode);
    }

    private void handleSettingChange() {
        int mode = getMode();

        if (!isProvideSupportCCMode(mode)) {
            Log.e(TAG, "Current provider does not support mode: " + mode
                    + ", setting to default mode");
            setMode(mDefaultMode);
        }

        // Reset internal states
        resetInternalState();

        // Update based on those values
        updateChargeControl();
    }

    @Override
    protected void onSettingsChanged(Uri uri) {
        handleSettingChange();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("ChargingControlController Configuration:");
        pw.println("  Enabled: " + isEnabled());
        pw.println("  Mode: " + getMode());
        pw.println("  Limit: " + getLimit());
        pw.println("  StartTime: " + getStartTime());
        pw.println("  TargetTime: " + getTargetTime());
        pw.println();
        pw.println("ChargingControlController State:");
        pw.println("  mBatteryPct: " + mBatteryPct);
        pw.println("  mIsPowerConnected: " + mIsPowerConnected);
        pw.println("  mIsNotificationPosted: " + mChargingNotification.isPosted());
        pw.println("  mIsDoneNotification: " + mChargingNotification.isDoneNotification());
        pw.println("  mIsControlCancelledOnce: " + mIsControlCancelledOnce);
        pw.println();
        mCurrentProvider.dump(pw);
    }

    /* Battery Broadcast Receiver */
    private class LineageHealthBatteryBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level == -1 || scale == -1) {
                return;
            }

            mBatteryPct = level * 100 / (float) scale;
            updateChargeControl();
        }
    }

    /* A representation of start and target time */
    static final class ChargeTime {
        private final long mStartTime;
        private final long mTargetTime;

        ChargeTime(long startTime, long targetTime) {
            mStartTime = startTime;
            mTargetTime = targetTime;
        }

        public long getStartTime() {
            return mStartTime;
        }

        public long getTargetTime() {
            return mTargetTime;
        }
    }
}
