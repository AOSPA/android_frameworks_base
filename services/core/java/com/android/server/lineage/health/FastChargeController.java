/*
 * SPDX-FileCopyrightText: 2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

import vendor.lineage.health.IFastCharge;

import java.io.PrintWriter;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FastChargeController extends LineageHealthFeature {
    private final int[] mChargingSpeedValues;
    private final ContentResolver mContentResolver;
    private final IFastCharge mFastCharge;

    // Settings uris
    private final Uri MODE_URI = Settings.System.getUriFor(
            Settings.System.FAST_CHARGE_MODE);

    public FastChargeController(Context context, Handler handler) {
        super(context, handler);

        mContentResolver = mContext.getContentResolver();
        mFastCharge = IFastCharge.Stub.asInterface(
                ServiceManager.waitForDeclaredService(
                        IFastCharge.DESCRIPTOR + "/default"));

        Resources res = mContext.getResources();
        mChargingSpeedValues = Stream.of(res.getStringArray(R.array.charging_speed_values))
                .mapToInt(Integer::parseInt)
                .toArray();

        if (mFastCharge == null) {
            Log.i(TAG, "Lineage Health HAL not found");
            return;
        }
    }

    @Override
    public boolean isSupported() {
        try {
            return mFastCharge != null && mFastCharge.getSupportedFastChargeModes() > 0;
        } catch (RemoteException e) {
            return false;
        }
    }

    public int[] getSupportedFastChargeModes() {
        if (mFastCharge == null) {
            return new int[0];
        }

        try {
            long supportedFastChargeModes = mFastCharge.getSupportedFastChargeModes();

            return IntStream.of(mChargingSpeedValues)
                    .filter(mode -> (supportedFastChargeModes & mode) != 0)
                    .toArray();
        } catch (RemoteException e) {
            return new int[0];
        }
    }

    public int getFastChargeMode() {
        int[] supportedFastChargeModes = getSupportedFastChargeModes();
        if (supportedFastChargeModes.length == 0) {
            return 0;
        }
        int defaultMode = supportedFastChargeModes[supportedFastChargeModes.length - 1];

        int mode = Settings.System.getInt(mContentResolver,
                Settings.System.FAST_CHARGE_MODE,
                defaultMode);
        if (mode != defaultMode && !ArrayUtils.contains(supportedFastChargeModes, mode)) {
            return defaultMode;
        }

        return mode;
    }

    public boolean setFastChargeMode(int mode) {
        putInt(Settings.System.FAST_CHARGE_MODE, mode);
        return true;
    }

    @Override
    public void onStart() {
        if (mFastCharge == null) {
            return;
        }

        // Register setting observer
        registerSettings(MODE_URI);

        handleSettingChange();
    }

    private void handleSettingChange() {
        try {
            mFastCharge.setFastChargeMode(getFastChargeMode());
        } catch (Exception e) {
        }
    }

    @Override
    protected void onSettingsChanged(Uri uri) {
        handleSettingChange();
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("FastChargeController Configuration:");
        pw.println("  Mode: " + getFastChargeMode());
        pw.println();
    }
}
