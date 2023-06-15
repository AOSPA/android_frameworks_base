/*
 * Copyright (C) 2023 ArrowOS
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
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Slog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/* Not smart enough yet, but we're getting there */
public class Smart5gService extends SystemService {

    private static final String TAG = "Smart5gService";
    private static final boolean DEBUG = true;

    // from org.codeaurora.telephony.utils.EnhancedRadioCapabilityResponse
    private static final int NETWORK_TYPE_NR_NSA = 20; // = TelephonyManager.NETWORK_TYPE_NR
    private static final int NETWORK_TYPE_NR_SA = 21;
    private static final long NETWORK_TYPE_BITMASK_NR_NSA = (1 << (NETWORK_TYPE_NR_NSA -1));
    private static final long NETWORK_TYPE_BITMASK_NR_SA = (1 << (NETWORK_TYPE_NR_SA -1));
    private static final long NETWORK_TYPE_BITMASK_NR =
            (NETWORK_TYPE_BITMASK_NR_NSA | NETWORK_TYPE_BITMASK_NR_SA);

    private static final NetworkRequest INTERNET_NETWORK_REQUEST =
            new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build();

    private final Context mContext;
    private final Object mLock = new Object();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Executor mExecutor = new HandlerExecutor(mHandler);

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubManager;
    private ConnectivityManager mConnectivityManager;
    private PowerManager mPowerManager;

    private boolean mIsOnMobileData, mIsPowerSaveMode;
    private int[] mActiveSubIds = new int[0];
    private int mDefaultDataSubId = INVALID_SUBSCRIPTION_ID;

    private final ContentObserver mSettingObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            dlog("SettingObserver: onChange");
            update();
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            dlog("received intent: " + action);
            switch (action) {
                case ACTION_POWER_SAVE_MODE_CHANGED:
                    final boolean on = mPowerManager.isPowerSaveMode();
                    if (on != mIsPowerSaveMode) {
                        mIsPowerSaveMode = on;
                        dlog("power save mode changed, new: " + on);
                        update();
                    }
                    break;
                case ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                    final int subId = mSubManager.getDefaultDataSubscriptionId();
                    if (subId != mDefaultDataSubId) {
                        mDefaultDataSubId = subId;
                        dlog("dds changed, new: " + subId);
                        update();
                    }
                    break;
                default:
                    Slog.e(TAG, "Unhandled intent: " + action);
            }
        }
    };

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
        Map<Network, NetworkCapabilities> mNetworkCaps = new HashMap<>();

        @Override
        public void onLost(Network network) {
            dlog("NetworkCallback: onLost");
            mNetworkCaps.remove(network);
            refresh();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            dlog("NetworkCallback: onCapabilitiesChanged");
            mNetworkCaps.put(network, caps);
            refresh();
        }

        private void refresh() {
            final boolean isInternetConnected = !mNetworkCaps.isEmpty();
            final boolean isMobileDataActive = mNetworkCaps.values().stream()
                    .anyMatch(nc -> nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            dlog("NetworkCallback: isInternetConnected:" + isInternetConnected
                    + " isMobileDataActive:" + isMobileDataActive);
            final boolean isOnMobileData = isMobileDataActive || !isInternetConnected;
            if (isOnMobileData != mIsOnMobileData) {
                mIsOnMobileData = isOnMobileData;
                update();
            }
        }
    };

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
                            Settings.System.getUriFor(SMART_5G + subId), false, mSettingObserver);
                    mContext.getContentResolver().registerContentObserver(
                            Settings.Global.getUriFor(MOBILE_DATA + subId), false, mSettingObserver);
                }
                mActiveSubIds = subs;
                update();
            }
        }
    };

    public Smart5gService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting Smart5gService");
        publishLocalService(Smart5gService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            dlog("onBootPhase PHASE_SYSTEM_SERVICES_READY");
            mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
            mSubManager = mContext.getSystemService(SubscriptionManager.class);
            mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
            mPowerManager = mContext.getSystemService(PowerManager.class);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            dlog("onBootPhase PHASE_BOOT_COMPLETED");
            mIsPowerSaveMode = mPowerManager.isPowerSaveMode();
            mDefaultDataSubId = mSubManager.getDefaultDataSubscriptionId();
            final IntentFilter filter = new IntentFilter(ACTION_POWER_SAVE_MODE_CHANGED);
            filter.addAction(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
            mContext.registerReceiver(mIntentReceiver, filter);
            mConnectivityManager.registerNetworkCallback(INTERNET_NETWORK_REQUEST, mNetworkCallback);
            mSubManager.addOnSubscriptionsChangedListener(mExecutor, mSubListener);
        }
    }

    private boolean isEnabled(int subId) {
        return Settings.System.getIntForUser(mContext.getContentResolver(), SMART_5G + subId, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private boolean isMobileDataEnabled(int subId) {
        return Settings.Global.getInt(mContext.getContentResolver(), MOBILE_DATA + subId, 1) == 1;
    }

    private static long getSupportedNrBitmask(TelephonyManager tm, int subId) {
        if ((tm.getSupportedRadioAccessFamily() & NETWORK_TYPE_BITMASK_NR) != 0) {
            dlog("subId " + subId + " supports 5g EnhancedRadioCapability");
            return NETWORK_TYPE_BITMASK_NR;
        } else if ((tm.getSupportedRadioAccessFamily() & NETWORK_TYPE_BITMASK_NR_NSA) != 0) {
            dlog("subId " + subId + " supports 5g AOSP");
            return NETWORK_TYPE_BITMASK_NR_NSA;
        } else {
            dlog("subId " + subId + " does not support 5g!");
            return 0;
        }
    }

    private synchronized void update() {
        if (mActiveSubIds == null || mActiveSubIds.length == 0) {
            dlog("update: return, no active subs!");
            return;
        }
        for (int subId : mActiveSubIds) {
            final TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
            final long supportedNrBitmask = getSupportedNrBitmask(tm, subId);
            if (supportedNrBitmask == 0) continue;
            long allowedNetworkTypes = tm.getAllowedNetworkTypesForReason(
                    ALLOWED_NETWORK_TYPES_REASON_POWER);
            final boolean is5gAllowed = (allowedNetworkTypes & supportedNrBitmask) != 0;
            final boolean shouldDisable = shouldDisable5g(subId);
            dlog("update: subId=" + subId + " is5gAllowed=" + is5gAllowed + " shouldDisable="
                    + shouldDisable);
            if (shouldDisable && is5gAllowed) {
                allowedNetworkTypes &= ~supportedNrBitmask;
            } else if (!shouldDisable && !is5gAllowed) {
                allowedNetworkTypes |= supportedNrBitmask;
            } else {
                continue;
            }
            tm.setAllowedNetworkTypesForReason(ALLOWED_NETWORK_TYPES_REASON_POWER,
                    allowedNetworkTypes);
        }
    }

    private boolean shouldDisable5g(int subId) {
        if (!isEnabled(subId)) {
            dlog("shouldDisable5g: smart 5g is disabled for subId " + subId);
            return false;
        } else if (!isMobileDataEnabled(subId)) {
            dlog("shouldDisable5g: mobile data is disabled for subId " + subId);
            return true;
        }
        dlog("shouldDisable5g: subId=" + subId + " mIsPowerSaveMode=" + mIsPowerSaveMode
                + " mIsOnMobileData=" + mIsOnMobileData + " mDefaultDataSubId="
                + mDefaultDataSubId);
        return mIsPowerSaveMode // battery saver mode
                || !mIsOnMobileData // we aren't on mobile data
                // this isn't the default data sim
                || (mDefaultDataSubId != INVALID_SUBSCRIPTION_ID && subId != mDefaultDataSubId);
    }

    private static void dlog(String msg) {
        if (DEBUG) Slog.d(TAG, msg);
    }
}
