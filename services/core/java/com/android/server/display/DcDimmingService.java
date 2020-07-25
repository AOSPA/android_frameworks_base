/*
 * Copyright (C) 2020 Paranoid Android
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

package com.android.server.display;

import static android.hardware.display.DcDimmingManager.MODE_AUTO_OFF;
import static android.hardware.display.DcDimmingManager.MODE_AUTO_TIME;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DcDimmingManager;
import android.hardware.display.IDcDimmingManager;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class DcDimmingService extends SystemService {

    private static final String TAG = "DcDimmingService";

    private final Context mContext;
    private final Handler mHandler = new Handler();
    private final Object mLock = new Object();
    private final String mDcNode;
    private final String mDcOnValue;
    private final String mDcOffValue;
    private TwilightManager mTwilightManager;
    private TwilightState mTwilightState;

    private int mAutoMode;
    private boolean mAvailable;
    private boolean mDcOn;
    private boolean mScreenOff;
    private boolean mPendingOnScreenOn;

    private final TwilightListener mTwilightListener = (state) -> {
        Slog.v(TAG, "onTwilightStateChanged state:" + state);
        boolean changed = mTwilightState == null || (state.isNight() != mTwilightState.isNight());
        mPendingOnScreenOn = mScreenOff && changed;
        mTwilightState = state;
        if (mAutoMode == MODE_AUTO_TIME) {
            if (!mScreenOff && changed) {
                synchronized (mLock) {
                    updateLocked(false, false);
                }
            }
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Slog.v(TAG, "mIntentReceiver ACTION_SCREEN_ON"
                        + " mPendingOnScreenOn:" + mPendingOnScreenOn);
                mScreenOff = false;
                mHandler.postDelayed(() -> {
                    if (mPendingOnScreenOn) {
                        synchronized (mLock) {
                            updateLocked(false, false);
                        }
                    }
                    mPendingOnScreenOn = false;
                }, 300);
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                Slog.v(TAG, "mIntentReceiver ACTION_SCREEN_OFF");
                mScreenOff = true;
            }
        }
    };

    public DcDimmingService(Context context) {
        super(context);
        mContext = context;
        mDcNode = context.getResources().getString(
                com.android.internal.R.string.config_deviceDcDimmingSysfsNode);
        mDcOnValue = context.getResources().getString(
                com.android.internal.R.string.config_deviceDcDimmingEnableValue);
        mDcOffValue = context.getResources().getString(
                com.android.internal.R.string.config_deviceDcDimmingDisableValue);
        final IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        mContext.registerReceiver(mIntentReceiver, intentFilter);
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting DcDimmingService");
        publishBinderService(Context.DC_DIM_SERVICE, mService);
        publishLocalService(DcDimmingService.class, this);
        mAvailable = nodeExists();
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Slog.v(TAG, "onBootPhase PHASE_SYSTEM_SERVICES_READY");
            mTwilightManager = getLocalService(TwilightManager.class);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Slog.v(TAG, "onBootPhase PHASE_BOOT_COMPLETED");
            mTwilightManager.registerListener(mTwilightListener, mHandler);
            mTwilightState = mTwilightManager.getLastTwilightState();
        }
    }

    @Override
    public void onUserUnlocking(TargetUser user) {
        mAvailable = nodeExists();
        Slog.v(TAG, "onUnlockUser mAvailable:" + mAvailable);
        if (!mAvailable) {
            return;
        }
        mAutoMode = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.DC_DIMMING_AUTO_MODE, 0, UserHandle.USER_CURRENT);
        mDcOn = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.DC_DIMMING_STATE, 0, UserHandle.USER_CURRENT) == 1;
        synchronized (mLock) {
            updateLocked(false, true);
        }
    }

    private void updateLocked(boolean force, boolean initial) {
        if (!mAvailable) {
            return;
        }
        boolean enable = shouldEnableDc();
        Slog.v(TAG, "updateLocked mDcOn:" + mDcOn + " force:" + force
                + " initial:" + initial + " shouldEnableDc:" + enable);
        if (!force) {
            if ((!initial && mDcOn == enable) || (initial && !enable)) {
                return;
            }
            mDcOn = enable;
        }
        writeSysfsNode(mDcOn ? mDcOnValue : mDcOffValue);
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.DC_DIMMING_STATE, mDcOn ? 1 : 0,
                UserHandle.USER_CURRENT);
    }

    private boolean shouldEnableDc() {
        switch (mAutoMode) {
            case MODE_AUTO_TIME:
                return shouldEnableDcTime();
            default:
                return mDcOn;
        }
    }

    private boolean shouldEnableDcTime() {
        if (mTwilightState == null) {
            mTwilightState = mTwilightManager.getLastTwilightState();
        }
        return mTwilightState != null && mTwilightState.isNight();
    }

    private final IDcDimmingManager.Stub mService = new IDcDimmingManager.Stub() {
        @Override
        public void setAutoMode(int mode) {
            synchronized (mLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (mAutoMode != mode) {
                        Slog.v(TAG, "setAutoMode(" + mode + ")");
                        mAutoMode = mode;
                        Settings.System.putIntForUser(mContext.getContentResolver(),
                                Settings.System.DC_DIMMING_AUTO_MODE, mode,
                                UserHandle.USER_CURRENT);
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public void setDcDimming(boolean enable) {
            synchronized (mLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    Slog.v(TAG, "setDcDimming(" + enable + ")");
                    mDcOn = enable;
                    updateLocked(true, false);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @Override
        public int getAutoMode() {
            return mAutoMode;
        }

        @Override
        public boolean isAvailable() {
            return mAvailable;
        }

        @Override
        public boolean isDcDimmingOn() {
            return mDcOn;
        }
    };

    private void writeSysfsNode(String value) {
        Slog.v(TAG, "writeSysfsNode value:" + value);
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(mDcNode));
            writer.write(value);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "No such file " + mDcNode + " for writing", e);
        } catch (IOException e) {
            Slog.e(TAG, "Could not write to file " + mDcNode, e);
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException e) {
                // Ignored, not much we can do anyway
            }
        }
    }

    private String readSysfsNode() {
        String line = null;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(mDcNode), 512);
            line = reader.readLine();
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "No such file " + mDcNode + " for reading", e);
        } catch (IOException e) {
            Slog.e(TAG, "Could not read from file " + mDcNode, e);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // Ignored, not much we can do anyway
            }
        }
        return line;
    }

    private boolean nodeExists() {
        final File file = new File(mDcNode);
        return file.exists() && file.canRead() && file.canWrite();
    }
}
