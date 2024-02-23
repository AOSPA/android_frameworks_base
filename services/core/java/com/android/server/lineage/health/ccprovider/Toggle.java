/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health.ccprovider;

import static com.android.internal.lineage.health.HealthInterface.MODE_AUTO;
import static com.android.internal.lineage.health.HealthInterface.MODE_LIMIT;
import static com.android.internal.lineage.health.HealthInterface.MODE_MANUAL;

import static com.android.server.lineage.health.Util.msToString;
import static com.android.server.lineage.health.Util.msToUTCString;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.R;

import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;
import java.util.Objects;

public class Toggle extends ChargingControlProvider {
    protected final int mChargingLimitMargin;
    private final int mChargingTimeMargin;

    private final boolean mToggleSetAlways = mContext.getResources().getBoolean(
            R.bool.config_chargingControlToggleSetAlways);
    private boolean mIsLimitSet;
    private long mSavedTargetTime;
    private long mEstimatedFullTime;
    private chgCtrlStage mStage = chgCtrlStage.STAGE_NONE;

    private enum chgCtrlStage {
        /**
         * It has no effect
         */
        STAGE_NONE,

        /**
         * The battery level is less than 80%
         */
        STAGE_INITIAL,

        /**
         * The battery level reached 80% and is now waiting
         */
        STAGE_WAITING,

        /**
         * The battery is now charging towards 100%
         */
        STAGE_CONTINUE,
    }

    // Only when the battery level is above this limit will the charging control be activated.
    private final static int CHARGE_CTRL_MIN_LEVEL = 80;

    public Toggle(IChargingControl chargingControl,
            Context context) {
        super(context, chargingControl);

        boolean isBypassSupported = isHALModeSupported(
                ChargingControlSupportedMode.BYPASS | ChargingControlSupportedMode.TOGGLE);
        if (!isBypassSupported) {
            mChargingLimitMargin = mContext.getResources().getInteger(
                    R.integer.config_chargingControlBatteryRechargeMargin);
        } else {
            mChargingLimitMargin = 1;
        }
        Log.i(TAG, "isBypassSupported: " + isBypassSupported);

        mChargingTimeMargin = mContext.getResources().getInteger(
                R.integer.config_chargingControlTimeMargin) * 60 * 1000;
    }

    @Override
    public boolean isSupported() {
        return isHALModeSupported(ChargingControlSupportedMode.TOGGLE);
    }

    @Override
    public boolean requiresBatteryLevelMonitoring() {
        return !isHALModeSupported(ChargingControlSupportedMode.BYPASS);
    }

    @Override
    protected boolean onBatteryChanged(float currentPct, int targetPct) {
        mIsLimitSet = shouldStopCharging(currentPct, targetPct);
        Log.i(TAG, "Current battery level: " + currentPct + ", target: " + targetPct +
                ", limit set: " + mIsLimitSet);
        return setChargingEnabled(!mIsLimitSet);
    }

    private boolean onStage(chgCtrlStage stage) {
        switch (stage) {
            case STAGE_NONE -> {
                setChargingEnabled(true);
                return false;
            }
            case STAGE_INITIAL, STAGE_CONTINUE -> {
                return setChargingEnabled(true);
            }
            case STAGE_WAITING -> {
                return setChargingEnabled(false);
            }
        }

        return false;
    }

    private chgCtrlStage getNextStage(float batteryPct, long startTime, long targetTime) {
        final long currentTime = System.currentTimeMillis();
        chgCtrlStage stage = mStage;

        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = mContext.registerReceiver(null, ifilter);
        boolean plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) != 0;

        if (startTime > currentTime && stage != chgCtrlStage.STAGE_CONTINUE) {
            // Not yet entering user configured time frame
            return chgCtrlStage.STAGE_NONE;
        }

        if (mSavedTargetTime != targetTime && (mSavedTargetTime == 0
                || mSavedTargetTime >= currentTime)) {
            Log.i(TAG, "User changed target time, reassign it");
            mSavedTargetTime = targetTime;
            stage = chgCtrlStage.STAGE_INITIAL;
        }

        final BatteryUsageStats batteryUsageStats = Objects.requireNonNull(
                mContext.getSystemService(
                        BatteryStatsManager.class)).getBatteryUsageStats();
        long remaining = batteryUsageStats.getChargeTimeRemainingMs();
        remaining += mChargingTimeMargin;
        Log.i(TAG, "Current estimated time to full: " + msToUTCString(remaining));

        long deltaTime = targetTime - currentTime;
        Log.i(TAG, "Current time to target: " + msToUTCString(deltaTime));

        switch (stage) {
            case STAGE_NONE, STAGE_INITIAL -> {
                if (!plugged || batteryPct < CHARGE_CTRL_MIN_LEVEL || remaining == -1) {
                    // NONE/INITIAL -> INITIAL: If battery level < 80%
                    return chgCtrlStage.STAGE_INITIAL;
                } else if (deltaTime > remaining) {
                    // NONE/INITIAL -> WAITING: battery level >= 80% && Have enough time waiting
                    mEstimatedFullTime = remaining;
                    return chgCtrlStage.STAGE_WAITING;
                } else {
                    // NONE/INITIAL -> CONTINUE: battery level >= 80% && Not enough time waiting
                    return chgCtrlStage.STAGE_CONTINUE;
                }
            }
            case STAGE_WAITING -> {
                if (deltaTime <= mEstimatedFullTime) {
                    return chgCtrlStage.STAGE_CONTINUE;
                } else {
                    return chgCtrlStage.STAGE_WAITING;
                }
            }
            case STAGE_CONTINUE -> {
                if (!plugged) {
                    return chgCtrlStage.STAGE_INITIAL;
                }
                return chgCtrlStage.STAGE_CONTINUE;
            }
        }

        Log.e(TAG, "Possible bug: code reaches out of switch case");
        return chgCtrlStage.STAGE_NONE;
    }

    @Override
    protected boolean onBatteryChanged(float batteryPct, long startTime, long targetTime,
            int configMode) {
        if (configMode != MODE_AUTO && configMode != MODE_MANUAL) {
            Log.e(TAG,
                    "Possible bug: onBatteryChanged called with unsupported mode: " + configMode);
            return false;
        }

        chgCtrlStage prevStage = mStage;
        mStage = getNextStage(batteryPct, startTime, targetTime);
        Log.i(TAG, "State change: " + prevStage + " -> " + mStage);

        return onStage(mStage);
    }

    private boolean setChargingEnabled(boolean enabled) {
        try {
            if (mToggleSetAlways) {
                mChargingControl.setChargingEnabled(enabled);
            } else if (mChargingControl.getChargingEnabled() != enabled) {
                mChargingControl.setChargingEnabled(enabled);
            }
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set charging enabled", e);
            return false;
        }
    }

    @Override
    protected void onEnabled() {
        onReset();
    }

    @Override
    protected void onDisable() {
        onReset();
    }

    @Override
    protected void onReset() {
        try {
            mChargingControl.setChargingEnabled(true);
            mIsLimitSet = false;
            mSavedTargetTime = 0;
            mEstimatedFullTime = 0;
            mStage = chgCtrlStage.STAGE_NONE;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set charging enabled", e);
        }
    }

    @Override
    public boolean isChargingControlModeSupported(int mode) {
        return mode == MODE_AUTO || mode == MODE_MANUAL || mode == MODE_LIMIT;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("Provider: " + getClass().getName());
        pw.println("  mIsLimitSet: " + mIsLimitSet);
        pw.println("  mSavedTargetTime: " + msToString(mSavedTargetTime));
        pw.println("  mEstimatedFullTime: " + msToUTCString(mEstimatedFullTime));
        pw.println("  mStage: " + mStage);
        pw.println("  mChargeLimitMargin: " + mChargingLimitMargin);
    }

    private boolean shouldStopCharging(float currentPct, int targetPct) {
        if (mIsLimitSet) {
            return currentPct >= targetPct - mChargingLimitMargin;
        }
        return currentPct >= targetPct;
    }
}
