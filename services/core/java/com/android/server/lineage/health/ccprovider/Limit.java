/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health.ccprovider;

import static com.android.internal.lineage.health.HealthInterface.MODE_AUTO;
import static com.android.internal.lineage.health.HealthInterface.MODE_LIMIT;
import static com.android.internal.lineage.health.HealthInterface.MODE_MANUAL;

import android.content.Context;
import android.util.Log;

import com.android.internal.R;

import vendor.lineage.health.ChargingControlSupportedMode;
import vendor.lineage.health.ChargingLimitInfo;
import vendor.lineage.health.IChargingControl;

import java.io.PrintWriter;

public class Limit extends ChargingControlProvider {
    protected final int mChargingLimitMargin;

    public Limit(IChargingControl chargingControl, Context context) {
        super(context, chargingControl);

        boolean isBypassSupported = isHALModeSupported(ChargingControlSupportedMode.BYPASS);
        if (!isBypassSupported) {
            mChargingLimitMargin = mContext.getResources().getInteger(
                    R.integer.config_chargingControlBatteryRechargeMargin);
        } else {
            mChargingLimitMargin = 1;
        }
        Log.i(TAG, "isBypassSupported: " + isBypassSupported);
    }

    @Override
    protected boolean onBatteryChanged(float currentPct, int targetPct) {
        Log.i(TAG, "Current battery level: " + currentPct + ", target: " + targetPct);
        return setChargingLimit(targetPct);
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
        setChargingLimit(100);
    }

    private boolean setChargingLimit(int targetPct) {
        try {
            if (mChargingControl.getChargingLimit().max != targetPct) {
                ChargingLimitInfo limit = new ChargingLimitInfo();
                if (targetPct == 100) {
                    limit.min = 0;
                } else {
                    limit.min = targetPct - mChargingLimitMargin;
                }
                limit.max = targetPct;
                mChargingControl.setChargingLimit(limit);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set charging limit", e);
            return false;
        }
    }

    @Override
    public boolean isSupported() {
        return isHALModeSupported(ChargingControlSupportedMode.LIMIT);
    }

    @Override
    public boolean requiresBatteryLevelMonitoring() {
        return !isHALModeSupported(ChargingControlSupportedMode.BYPASS);
    }

    @Override
    public boolean isChargingControlModeSupported(int mode) {
        return mode == MODE_AUTO || mode == MODE_MANUAL || mode == MODE_LIMIT;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("Provider: " + getClass().getName());
    }
}
