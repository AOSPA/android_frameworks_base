/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health.ccprovider;

import static com.android.internal.lineage.health.HealthInterface.MODE_AUTO;
import static com.android.internal.lineage.health.HealthInterface.MODE_MANUAL;

import static com.android.server.lineage.health.Util.msToString;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;

public class Deadline extends ChargingControlProvider {
    private long mSavedTargetTime;

    public Deadline(IChargingControl chargingControl, Context context) {
        super(context, chargingControl);
    }

    @Override
    protected boolean onBatteryChanged(float batteryPct, long startTime, long targetTime,
            int configMode) {
        if (targetTime == mSavedTargetTime) {
            return true;
        }

        final long currentTime = System.currentTimeMillis();
        final long deadline = (targetTime - currentTime) / 1000;

        Log.i(TAG, "Setting charge deadline: Deadline (seconds): " + deadline);

        try {
            mChargingControl.setChargingDeadline(deadline);
            mSavedTargetTime = targetTime;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set charging deadline", e);
            return false;
        } catch (IllegalStateException e) {
            // This is possible when the device is just plugged in and the sysfs node is not ready
            // to be written to
            Log.e(TAG, "Failed to set charging deadline, will retry on next battery change");
            return false;
        }

        return true;
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
        mSavedTargetTime = 0;

        try {
            mChargingControl.setChargingDeadline(-1);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to reset charging deadline", e);
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("Provider: " + getClass().getName());
        pw.println("  mSavedTargetTime: " + mSavedTargetTime);
    }

    @Override
    public boolean isSupported() {
        return isHALModeSupported(ChargingControlSupportedMode.DEADLINE);
    }

    @Override
    public boolean isChargingControlModeSupported(int mode) {
        return mode == MODE_AUTO || mode == MODE_MANUAL;
    }

    @Override
    public boolean requiresBatteryLevelMonitoring() {
        return false;
    }
}
