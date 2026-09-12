/*
 * Copyright (C) 2023 ArrowOS
 * Copyright (C) 2026 Paranoid Android
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

package com.android.server;

import static android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED;
import static android.provider.Settings.Global.MOBILE_DATA;
import static android.provider.Settings.System.SMART_5G;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;
import static android.telephony.TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED;
import static android.telephony.TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_POWER;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Slog;

import java.util.Arrays;
import java.util.concurrent.Executor;

/* Not smart enough yet, but we're getting there */
public class Smart5gService extends SystemService {

    private static final String TAG = "Smart5gService";
    private static final boolean DEBUG = true;

    private static final int DEFAULT_NETWORK_NONE = 0;
    private static final int DEFAULT_NETWORK_CELLULAR = 1;
    private static final int DEFAULT_NETWORK_NON_CELLULAR = 2;

    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;
    private final Executor mHandlerExecutor;

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubManager;
    private ConnectivityManager mConnectivityManager;
    private PowerManager mPowerManager;

    private boolean mIsInteractive;
    private boolean mIsDeviceIdleMode;
    private boolean mIsPowerSaveMode;
    private int mDefaultNetworkState = DEFAULT_NETWORK_NONE;
    private int[] mActiveSubIds = new int[0];
    private int mActiveDataSubId = INVALID_SUBSCRIPTION_ID;
    private Network mDefaultNetwork;

    private final ContentObserver mSettingObserver;

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            dlog("received intent: " + action);
            switch (action) {
                case Intent.ACTION_SCREEN_ON:
                    mIsInteractive = mPowerManager.isInteractive();
                    update();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    mIsInteractive = mPowerManager.isInteractive();
                    update();
                    break;
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    mIsDeviceIdleMode = mPowerManager.isDeviceIdleMode();
                    update();
                    break;
                case ACTION_POWER_SAVE_MODE_CHANGED:
                    mIsPowerSaveMode = mPowerManager.isPowerSaveMode();
                    update();
                    break;
                case ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                    updateActiveDataSubId(INVALID_SUBSCRIPTION_ID);
                    break;
                default:
                    Slog.e(TAG, "Unhandled intent: " + action);
            }
        }
    };

    private final ConnectivityManager.NetworkCallback mDefaultNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            mDefaultNetwork = network;
            updateDefaultNetworkState(network,
                    mConnectivityManager.getNetworkCapabilities(network));
        }

        @Override
        public void onLost(Network network) {
            if (!network.equals(mDefaultNetwork)) {
                return;
            }
            mDefaultNetwork = null;
            mDefaultNetworkState = DEFAULT_NETWORK_NONE;
            update();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (network.equals(mDefaultNetwork)) {
                updateDefaultNetworkState(network, caps);
            }
        }
    };

    private final ActiveDataSubscriptionCallback mActiveDataSubscriptionCallback =
            new ActiveDataSubscriptionCallback();

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            dlog("onSubscriptionsChanged");
            final int[] subs = mSubManager.getActiveSubscriptionIdList();
            if (!Arrays.equals(subs, mActiveSubIds)) {
                dlog("active subs changed, was: " + Arrays.toString(mActiveSubIds)
                        + ", now: " + Arrays.toString(subs));
                // re-register content observers
                mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
                for (int subId : subs) {
                    dlog("registering content observer for subId " + subId);
                    mContext.getContentResolver().registerContentObserver(
                            Settings.System.getUriFor(SMART_5G + subId), false, mSettingObserver,
                            UserHandle.USER_ALL);
                    mContext.getContentResolver().registerContentObserver(
                            Settings.Global.getUriFor(MOBILE_DATA + subId), false, mSettingObserver,
                            UserHandle.USER_ALL);
                }
                mActiveSubIds = subs;
                update();
            }
        }
    };

    public Smart5gService(Context context) {
        super(context);
        mContext = context;
        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND, false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mHandlerExecutor = new HandlerExecutor(mHandler);
        mSettingObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                dlog("SettingObserver: onChange");
                update();
            }
        };
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting Smart5gService");
        publishLocalService(Smart5gService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mHandler.post(() -> {
                dlog("onBootPhase PHASE_SYSTEM_SERVICES_READY");
                mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
                mSubManager = mContext.getSystemService(SubscriptionManager.class);
                mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
                mPowerManager = mContext.getSystemService(PowerManager.class);
            });
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mHandler.post(() -> {
                dlog("onBootPhase PHASE_BOOT_COMPLETED");
                mIsInteractive = mPowerManager.isInteractive();
                mIsDeviceIdleMode = mPowerManager.isDeviceIdleMode();
                mIsPowerSaveMode = mPowerManager.isPowerSaveMode();
                mActiveDataSubId = SubscriptionManager.getDefaultDataSubscriptionId();
                final IntentFilter filter = new IntentFilter(ACTION_POWER_SAVE_MODE_CHANGED);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
                filter.addAction(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
                mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);
                mConnectivityManager.registerSystemDefaultNetworkCallback(
                        mDefaultNetworkCallback, mHandler);
                mSubManager.addOnSubscriptionsChangedListener(mHandlerExecutor, mSubListener);
                mTelephonyManager.registerTelephonyCallback(
                        mHandlerExecutor, mActiveDataSubscriptionCallback);
            });
        }
    }

    private void updateDefaultNetworkState(Network network, NetworkCapabilities caps) {
        if (caps == null) {
            return;
        }
        mDefaultNetwork = network;
        final int state = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                ? DEFAULT_NETWORK_CELLULAR : DEFAULT_NETWORK_NON_CELLULAR;
        if (state != mDefaultNetworkState) {
            mDefaultNetworkState = state;
            dlog("Default network is "
                    + (state == DEFAULT_NETWORK_CELLULAR ? "cellular" : "non-cellular"));
            update();
        }
    }

    private void updateActiveDataSubId(int callbackSubId) {
        final int fallbackSubId = SubscriptionManager.getDefaultDataSubscriptionId();
        final int activeDataSubId = SubscriptionManager.isValidSubscriptionId(callbackSubId)
                ? callbackSubId : fallbackSubId;
        if (activeDataSubId != mActiveDataSubId) {
            dlog("Active data subId changed from " + mActiveDataSubId + " to " + activeDataSubId);
            mActiveDataSubId = activeDataSubId;
            update();
        }
    }

    private int getActiveDataSubId() {
        if (SubscriptionManager.isValidSubscriptionId(mActiveDataSubId)) {
            return mActiveDataSubId;
        }
        return SubscriptionManager.getDefaultDataSubscriptionId();
    }

    private boolean isEnabled(int subId) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), SMART_5G + subId, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private boolean isDataConnectionAllowed(int subId) {
        try {
            return mTelephonyManager.createForSubscriptionId(subId).isDataConnectionAllowed();
        } catch (SecurityException | IllegalStateException | NullPointerException e) {
            Slog.w(TAG, "Unable to read data state for subId " + subId, e);
            return true;
        }
    }

    private void update() {
        if (mActiveSubIds == null || mActiveSubIds.length == 0) {
            dlog("update: return, no active subs!");
            return;
        }
        for (int subId : mActiveSubIds) {
            try {
                final TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
                if ((tm.getSupportedRadioAccessFamily()
                        & TelephonyManager.NETWORK_TYPE_BITMASK_NR) == 0) {
                    continue;
                }
                long allowedNetworkTypes = tm.getAllowedNetworkTypesForReason(
                        ALLOWED_NETWORK_TYPES_REASON_POWER);
                final boolean is5gAllowed = (allowedNetworkTypes
                        & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0;
                final boolean shouldDisable = shouldDisable5g(subId);
                dlog("update: subId=" + subId + " is5gAllowed=" + is5gAllowed
                        + " shouldDisable=" + shouldDisable);
                if (shouldDisable && is5gAllowed) {
                    allowedNetworkTypes &= ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
                } else if (!shouldDisable && !is5gAllowed) {
                    allowedNetworkTypes |= TelephonyManager.NETWORK_TYPE_BITMASK_NR;
                } else {
                    continue;
                }
                tm.setAllowedNetworkTypesForReason(ALLOWED_NETWORK_TYPES_REASON_POWER,
                        allowedNetworkTypes);
            } catch (SecurityException | IllegalStateException | NullPointerException e) {
                Slog.w(TAG, "Unable to update 5G policy for subId " + subId, e);
            }
        }
    }

    private boolean shouldDisable5g(int subId) {
        if (!isEnabled(subId)) {
            dlog("shouldDisable5g: smart 5g is disabled for subId " + subId);
            return false;
        } else if (!isDataConnectionAllowed(subId)) {
            dlog("shouldDisable5g: mobile data is disabled for subId " + subId);
            return true;
        }
        dlog("shouldDisable5g: subId=" + subId + " mIsPowerSaveMode=" + mIsPowerSaveMode
                + " mDefaultNetworkState=" + mDefaultNetworkState + " mActiveDataSubId="
                + getActiveDataSubId());
        return mIsPowerSaveMode
                || !mIsInteractive
                || mIsDeviceIdleMode
                || mDefaultNetworkState == DEFAULT_NETWORK_NON_CELLULAR
                // this isn't the default data sim
                || (SubscriptionManager.isValidSubscriptionId(getActiveDataSubId())
                        && subId != getActiveDataSubId());
    }

    private final class ActiveDataSubscriptionCallback extends TelephonyCallback implements
            TelephonyCallback.ActiveDataSubscriptionIdListener {
        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            updateActiveDataSubId(subId);
        }
    }

    private static void dlog(String msg) {
        if (DEBUG) Slog.d(TAG, msg);
    }
}
