/*
 * Copyright (C) 2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.lineage.health;

import android.Manifest;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.android.server.ServiceThread;

import com.android.server.SystemService;

import com.android.internal.lineage.app.LineageContextConstants;
import com.android.internal.lineage.health.IHealthInterface;
import vendor.lineage.health.ChargingControlSupportedMode;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

public class HealthInterfaceService extends SystemService {

    private static final String TAG = "LineageHealth";
    private final Context mContext;
    private final Handler mHandler;
    private final ServiceThread mHandlerThread;

    private final List<LineageHealthFeature> mFeatures = new ArrayList<LineageHealthFeature>();

    // Health features
    private ChargingControlController mCCC;

    public HealthInterfaceService(Context context) {
        super(context);
        mContext = context;

        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_DEFAULT, false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }

    @Override
    public void onStart() {
        mCCC = new ChargingControlController(mContext, mHandler);
        if (mCCC.isSupported()) {
            mFeatures.add(mCCC);
        }

        if (!mFeatures.isEmpty()) {
            publishBinderService(LineageContextConstants.LINEAGE_HEALTH_INTERFACE, mService);
        }
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != PHASE_BOOT_COMPLETED) {
            return;
        }

        // start and update all features
        for (LineageHealthFeature feature : mFeatures) {
            feature.start();
        }
    }

    /* Service */
    private final IBinder mService = new IHealthInterface.Stub() {
        @Override
        public boolean isChargingControlSupported() {
            return mCCC.isSupported();
        }

        @Override
        public boolean getChargingControlEnabled() {
            return mCCC.isEnabled();
        }

        @Override
        public boolean setChargingControlEnabled(boolean enabled) {
            return mCCC.setEnabled(enabled);
        }

        @Override
        public int getChargingControlMode() {
            return mCCC.getMode();
        }

        @Override
        public boolean setChargingControlMode(int mode) {
            return mCCC.setMode(mode);
        }

        @Override
        public int getChargingControlStartTime() {
            return mCCC.getStartTime();
        }

        @Override
        public boolean setChargingControlStartTime(int startTime) {
            return mCCC.setStartTime(startTime);
        }

        @Override
        public int getChargingControlTargetTime() {
            return mCCC.getTargetTime();
        }

        @Override
        public boolean setChargingControlTargetTime(int targetTime) {
            return mCCC.setTargetTime(targetTime);
        }

        @Override
        public int getChargingControlLimit() {
            return mCCC.getLimit();
        }

        @Override
        public boolean setChargingControlLimit(int limit) {
            return mCCC.setLimit(limit);
        }

        @Override
        public boolean resetChargingControl() {
            return mCCC.reset();
        }

        @Override
        public boolean allowFineGrainedSettings() {
            // We allow fine-grained settings if allow toggle and bypass
            return mCCC.isChargingModeSupported(ChargingControlSupportedMode.TOGGLE);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.DUMP, TAG);

            pw.println();
            pw.println("LineageHealth Service State:");

            for (LineageHealthFeature feature : mFeatures) {
                feature.dump(pw);
            }
        }
    };
}
