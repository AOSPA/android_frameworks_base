/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health.ccprovider;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;

public abstract class ChargingControlProvider {
    protected final IChargingControl mChargingControl;
    protected final Context mContext;

    protected static final String TAG = "LineageHealth";

    protected boolean isEnabled = false;

    ChargingControlProvider(Context context, IChargingControl chargingControl) {
        mContext = context;
        mChargingControl = chargingControl;
    }

    public final boolean update(float batteryPct, int targetPct) {
        if (!isEnabled) {
            return false;
        }
        return onBatteryChanged(batteryPct, targetPct);
    }

    public final boolean update(float batteryPct, long startTime, long targetTime, int configMode) {
        if (!isEnabled) {
            return false;
        }
        return onBatteryChanged(batteryPct, startTime, targetTime, configMode);
    }

    public final void reset() {
        onReset();
    }

    /**
     * Enables the provider
     */
    public final void enable() {
        // Don't enable a provider twice
        if (isEnabled) {
            return;
        }
        isEnabled = true;
        onEnabled();
        Log.i(TAG, getClass() + " is enabled");
    }

    /**
     * Disable any effect of this provider.
     */
    public final void disable() {
        // Don't disable a provider twice
        if (!isEnabled) {
            return;
        }
        isEnabled = false;
        Log.i(TAG, getClass() + " is disabled");
        onDisable();
    }

    /**
     * Return whether the provider is enabled
     */
    public final boolean isEnabled() {
        return isEnabled;
    }

    /**
     * Called when the mode is {@link com.android.internal.lineage.health.HealthInterface#MODE_LIMIT} and
     * the {@link android.content.Intent#ACTION_BATTERY_CHANGED} is received.
     *
     * @param currentPct Current battery percentage
     * @param targetPct  The user-configured target charging limit
     * @return Whether a notification should be posted
     */
    protected boolean onBatteryChanged(float currentPct, int targetPct) {
        throw new RuntimeException("Unsupported operation");
    }

    /**
     * Called when the mode is {@link com.android.internal.lineage.health.HealthInterface#MODE_AUTO} or
     * {@link com.android.internal.lineage.health.HealthInterface#MODE_MANUAL} and the
     * {@link android.content.Intent#ACTION_BATTERY_CHANGED} is received.
     *
     * @param batteryPct Current battery percentage
     * @param startTime  The time when the charging control should start
     * @param targetTime The expected time when the battery should be full
     * @param configMode The current charging control mode, either
     *                   {@link com.android.internal.lineage.health.HealthInterface#MODE_AUTO} or
     *                   {@link com.android.internal.lineage.health.HealthInterface#MODE_MANUAL}
     * @return Whether a notification should be posted
     */
    protected boolean onBatteryChanged(float batteryPct, long startTime, long targetTime,
            int configMode) {
        throw new RuntimeException("Unsupported operation");
    }

    /**
     * Called when the provider is enabled
     */
    protected abstract void onEnabled();

    /**
     * Called when the provider is disabled
     */
    protected abstract void onDisable();

    /**
     * Reset internal states
     */
    protected abstract void onReset();

    /**
     * Dump internal states
     */
    public abstract void dump(PrintWriter pw);

    /**
     * Given current device setup, whether the charging control provider is supported.
     *
     * @return Whether this charging control provider is supported.
     */
    public abstract boolean isSupported();

    /**
     * Whether this provider requires always monitoring the battery level
     */
    public abstract boolean requiresBatteryLevelMonitoring();

    /**
     * Whether this provider supports the mode.
     * Available modes:
     *     - ${@link com.android.internal.lineage.health.HealthInterface#MODE_AUTO}
     *     - ${@link com.android.internal.lineage.health.HealthInterface#MODE_MANUAL}
     *     - ${@link com.android.internal.lineage.health.HealthInterface#MODE_LIMIT}
     */
    public abstract boolean isChargingControlModeSupported(int mode);

    /**
     * Whether the HAL supports the mode or modes
     *
     * @param mode One or more {@link vendor.lineage.health.ChargingControlSupportedMode}
     * @return Whether the provider supports the modes
     */
    public final boolean isHALModeSupported(int mode) {
        try {
            Log.i(TAG, "isSupported mode called, param: " + mode + ", supported: "
                    + mChargingControl.getSupportedMode());
            return (mChargingControl.getSupportedMode() & mode) == mode;
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to get supported mode from HAL!", e);
            return false;
        }
    }
}
