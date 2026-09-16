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

import android.app.ActivityManager;
import android.app.UidObserver;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TetheringManager;
import android.net.TrafficStats;
import android.os.DeadSystemRuntimeException;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Slog;
import android.util.SparseArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/* Not smart enough yet, but we're getting there */
public class Smart5gService extends SystemService {

    private static final String TAG = "Smart5gService";
    private static final boolean DEBUG = true;

    private static final long DISABLE_DELAY_MS = 5_000;
    private static final long POST_CALL_GUARD_MS = 4_000;
    private static final long TRAFFIC_POLL_INTERVAL_MS = 1_000;
    private static final long TRAFFIC_THRESHOLD_BYTES_PER_SECOND = 1024 * 1024L;
    private static final long TRAFFIC_MARGIN_BYTES_PER_SECOND = 200 * 1024L;
    private static final long TRAFFIC_HIGH_BYTES_PER_SECOND =
            TRAFFIC_THRESHOLD_BYTES_PER_SECOND + TRAFFIC_MARGIN_BYTES_PER_SECOND;
    private static final long TRAFFIC_LOW_BYTES_PER_SECOND =
            TRAFFIC_THRESHOLD_BYTES_PER_SECOND - TRAFFIC_MARGIN_BYTES_PER_SECOND;

    private static final int DEFAULT_NETWORK_NONE = 0;
    private static final int DEFAULT_NETWORK_CELLULAR = 1;
    private static final int DEFAULT_NETWORK_NON_CELLULAR = 2;
    private static final int CALL_STATE_UNINITIALIZED = -1;

    private final Context mContext;
    private final ServiceThread mHandlerThread;
    private final Handler mHandler;
    private final Executor mHandlerExecutor;
    private final SparseArray<Runnable> mPendingDisableRunnables = new SparseArray<>();
    private final Map<Integer, CallStateCallback> mCallStateCallbacks = new HashMap<>();
    private final Map<String, Boolean> mGamePackageCache = new HashMap<>();
    private final Set<Integer> mForegroundGameUids = new HashSet<>();

    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubManager;
    private ConnectivityManager mConnectivityManager;
    private TetheringManager mTetheringManager;
    private PowerManager mPowerManager;

    private boolean mIsInteractive;
    private boolean mIsDeviceIdleMode;
    private boolean mIsPowerSaveMode;
    private boolean mIsTetheringActive;
    private boolean mIsGameActive;
    private boolean mIsHighTraffic = true;
    private boolean mIsTrafficMonitoring;
    private int mDefaultNetworkState = DEFAULT_NETWORK_NONE;
    private int[] mActiveSubIds = new int[0];
    private int mActiveDataSubId = INVALID_SUBSCRIPTION_ID;
    private boolean mHasActiveDataSubIdCallback;
    private Network mDefaultNetwork;
    private List<String> mCellularInterfaces = List.of();
    private long mLastMobileBytes;
    private long mLastTrafficSampleElapsed;
    private boolean mIsPostCallGuardActive;

    private final Runnable mPostCallGuardRunnable = () -> {
        mIsPostCallGuardActive = false;
        dlog("Post-call guard ended");
        reevaluate();
    };

    private final ContentObserver mSettingObserver;

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            dlog("received intent: " + action);
            switch (action) {
                case Intent.ACTION_SCREEN_ON:
                    mIsInteractive = mPowerManager.isInteractive();
                    mIsHighTraffic = true;
                    cancelAllPendingDisables();
                    reevaluate();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    mIsInteractive = mPowerManager.isInteractive();
                    reevaluate();
                    break;
                case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED:
                    mIsDeviceIdleMode = mPowerManager.isDeviceIdleMode();
                    reevaluate();
                    break;
                case ACTION_POWER_SAVE_MODE_CHANGED:
                    mIsPowerSaveMode = mPowerManager.isPowerSaveMode();
                    reevaluate();
                    break;
                case ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED:
                    refreshActiveDataSubIdFromFallback();
                    break;
                case Intent.ACTION_USER_SWITCHED:
                    reevaluate();
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
            updateCellularInterface(network, mConnectivityManager.getLinkProperties(network));
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (network.equals(mDefaultNetwork)) {
                updateDefaultNetworkState(network, caps);
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            updateCellularInterface(network, linkProperties);
        }

        @Override
        public void onLost(Network network) {
            if (!network.equals(mDefaultNetwork)) {
                return;
            }
            dlog("Default network lost");
            mDefaultNetwork = null;
            mDefaultNetworkState = DEFAULT_NETWORK_NONE;
            setCellularInterfaces(List.of());
            reevaluate();
        }
    };

    private final SubscriptionManager.OnSubscriptionsChangedListener mSubListener =
            new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
            updateSubscriptions();
        }
    };

    private final ActiveDataSubscriptionCallback mActiveDataSubscriptionCallback =
            new ActiveDataSubscriptionCallback();

    private final UidObserver mUidObserver = new UidObserver() {
        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mHandler.post(() -> handleUidStateChanged(uid, procState));
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mHandler.post(() -> handleUidGone(uid));
        }
    };

    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() != null) {
                mGamePackageCache.remove(intent.getData().getSchemeSpecificPart());
            }
        }
    };

    private final TetheringManager.TetheringEventCallback mTetheringEventCallback =
            new TetheringManager.TetheringEventCallback() {
        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            final boolean active = !interfaces.isEmpty();
            if (active == mIsTetheringActive) {
                return;
            }
            mIsTetheringActive = active;
            dlog("Tethering active: " + active);
            if (active) {
                cancelAllPendingDisables();
            }
            reevaluate();
        }
    };

    private final Runnable mTrafficPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!shouldMonitorTraffic()) {
                stopTrafficMonitoring();
                return;
            }

            final long now = SystemClock.elapsedRealtime();
            final long mobileBytes = getMobileBytes();
            if (mobileBytes >= 0 && mLastMobileBytes >= 0
                    && mobileBytes >= mLastMobileBytes && now > mLastTrafficSampleElapsed) {
                final long byteDelta = mobileBytes - mLastMobileBytes;
                final long timeDelta = now - mLastTrafficSampleElapsed;
                final long bytesPerSecond = (long) (byteDelta * 1000.0 / timeDelta);
                final boolean wasHighTraffic = mIsHighTraffic;
                if (bytesPerSecond >= TRAFFIC_HIGH_BYTES_PER_SECOND) {
                    mIsHighTraffic = true;
                } else if (bytesPerSecond <= TRAFFIC_LOW_BYTES_PER_SECOND) {
                    mIsHighTraffic = false;
                }
                if (wasHighTraffic != mIsHighTraffic) {
                    dlog("Mobile traffic rate=" + bytesPerSecond + " high=" + mIsHighTraffic);
                    reevaluate();
                }
            }
            mLastMobileBytes = mobileBytes;
            mLastTrafficSampleElapsed = now;
            mHandler.postDelayed(this, TRAFFIC_POLL_INTERVAL_MS);
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
                reevaluate();
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
            mHandler.post(this::initializeSystemServices);
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mHandler.post(this::startMonitoring);
        }
    }

    private void initializeSystemServices() {
        mTelephonyManager = mContext.getSystemService(TelephonyManager.class);
        mSubManager = mContext.getSystemService(SubscriptionManager.class);
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mTetheringManager = mContext.getSystemService(TetheringManager.class);
        mPowerManager = mContext.getSystemService(PowerManager.class);
    }

    private void startMonitoring() {
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
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);

        mConnectivityManager.registerSystemDefaultNetworkCallback(
                mDefaultNetworkCallback, mHandler);
        mSubManager.addOnSubscriptionsChangedListener(mHandlerExecutor, mSubListener);
        try {
            mTelephonyManager.registerTelephonyCallback(
                    mHandlerExecutor, mActiveDataSubscriptionCallback);
        } catch (SecurityException | IllegalStateException | NullPointerException
                | DeadSystemRuntimeException e) {
            Slog.w(TAG, "Unable to monitor active data subscription", e);
        }
        if (mTetheringManager != null) {
            mTetheringManager.registerTetheringEventCallback(
                    mHandlerExecutor, mTetheringEventCallback);
        }
        try {
            ActivityManager.getService().registerUidObserver(mUidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE | ActivityManager.UID_OBSERVER_GONE,
                    ActivityManager.PROCESS_STATE_TOP, null);
            seedForegroundGameUids();
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to monitor foreground games", e);
        }
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageFilter.addDataScheme("package");
        mContext.registerReceiverForAllUsers(mPackageReceiver, packageFilter, null, mHandler);
        updateSubscriptions();
    }

    private void handleUidStateChanged(int uid, int procState) {
        if (procState == ActivityManager.PROCESS_STATE_TOP && isGameUid(uid)) {
            mForegroundGameUids.add(uid);
        } else {
            mForegroundGameUids.remove(uid);
        }
        updateGameActiveState();
    }

    private void handleUidGone(int uid) {
        mForegroundGameUids.remove(uid);
        updateGameActiveState();
    }

    private void seedForegroundGameUids() throws RemoteException {
        final List<ActivityManager.RunningAppProcessInfo> runningProcesses =
                ActivityManager.getService().getRunningAppProcesses();
        if (runningProcesses == null) {
            return;
        }
        for (ActivityManager.RunningAppProcessInfo process : runningProcesses) {
            if (process.processState == ActivityManager.PROCESS_STATE_TOP
                    && isGameUid(process.uid)) {
                mForegroundGameUids.add(process.uid);
            }
        }
        updateGameActiveState();
    }

    private boolean isGameUid(int uid) {
        final PackageManager packageManager = mContext.getPackageManager();
        final String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null) {
            return false;
        }
        for (String packageName : packages) {
            final Boolean cached = mGamePackageCache.get(packageName);
            if (cached != null) {
                if (cached) {
                    return true;
                }
                continue;
            }

            boolean isGame = false;
            try {
                final ApplicationInfo appInfo = packageManager.getApplicationInfoAsUser(
                        packageName, 0, UserHandle.getUserId(uid));
                isGame = appInfo.category == ApplicationInfo.CATEGORY_GAME
                        || (appInfo.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
                mGamePackageCache.put(packageName, isGame);
            } catch (PackageManager.NameNotFoundException e) {
                dlog("Unable to classify package " + packageName);
            }
            if (isGame) {
                return true;
            }
        }
        return false;
    }

    private void updateGameActiveState() {
        final boolean isGameActive = !mForegroundGameUids.isEmpty();
        if (isGameActive == mIsGameActive) {
            return;
        }
        mIsGameActive = isGameActive;
        dlog("Foreground game active: " + mIsGameActive);
        reevaluate();
    }

    private void updateDefaultNetworkState(Network network, NetworkCapabilities caps) {
        if (caps == null) {
            return;
        }
        mDefaultNetwork = network;
        final int state = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                ? DEFAULT_NETWORK_CELLULAR : DEFAULT_NETWORK_NON_CELLULAR;
        if (state != DEFAULT_NETWORK_CELLULAR) {
            setCellularInterfaces(List.of());
        }
        if (state == mDefaultNetworkState) {
            return;
        }
        mDefaultNetworkState = state;
        dlog("Default network is "
                + (state == DEFAULT_NETWORK_CELLULAR ? "cellular" : "non-cellular"));
        reevaluate();
    }

    private void updateCellularInterface(Network network, LinkProperties linkProperties) {
        if (!network.equals(mDefaultNetwork)
                || mDefaultNetworkState != DEFAULT_NETWORK_CELLULAR) {
            return;
        }
        if (setCellularInterfaces(
                linkProperties != null ? linkProperties.getAllInterfaceNames() : List.of())) {
            reevaluate();
        }
    }

    private boolean setCellularInterfaces(List<String> interfaceNames) {
        if (Objects.equals(interfaceNames, mCellularInterfaces)) {
            return false;
        }
        mCellularInterfaces = List.copyOf(interfaceNames);
        mIsHighTraffic = true;
        stopTrafficMonitoring();
        dlog("Cellular interfaces: " + mCellularInterfaces);
        return true;
    }

    private void updateSubscriptions() {
        final int[] subscriptions = mSubManager.getActiveSubscriptionIdList();
        final int[] newSubIds = subscriptions != null ? subscriptions : new int[0];
        if (Arrays.equals(newSubIds, mActiveSubIds)) {
            refreshActiveDataSubIdFromFallback();
            return;
        }

        dlog("Active subs changed, was: " + Arrays.toString(mActiveSubIds)
                + ", now: " + Arrays.toString(newSubIds));
        unregisterCallStateCallbacks();
        cancelAllPendingDisables();
        mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
        mActiveSubIds = newSubIds;

        final ContentResolver resolver = mContext.getContentResolver();
        for (int subId : mActiveSubIds) {
            resolver.registerContentObserver(Settings.System.getUriFor(SMART_5G + subId), false,
                    mSettingObserver, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.Global.getUriFor(MOBILE_DATA + subId), false,
                    mSettingObserver, UserHandle.USER_ALL);
            registerCallStateCallback(subId);
        }
        refreshActiveDataSubIdFromFallback();
    }

    private void registerCallStateCallback(int subId) {
        try {
            final TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
            final CallStateCallback callback = new CallStateCallback(subId, tm);
            mCallStateCallbacks.put(subId, callback);
            tm.registerTelephonyCallback(mHandlerExecutor, callback);
        } catch (SecurityException | IllegalStateException | NullPointerException
                | DeadSystemRuntimeException e) {
            Slog.w(TAG, "Unable to monitor call state for subId " + subId, e);
        }
    }

    private void unregisterCallStateCallbacks() {
        for (CallStateCallback callback : mCallStateCallbacks.values()) {
            try {
                callback.mTelephonyManager.unregisterTelephonyCallback(callback);
            } catch (SecurityException | IllegalStateException | NullPointerException
                    | DeadSystemRuntimeException e) {
                Slog.w(TAG, "Unable to unregister call state callback", e);
            }
        }
        mCallStateCallbacks.clear();
    }

    private void handleActiveDataSubIdChanged(int callbackSubId) {
        mHasActiveDataSubIdCallback = SubscriptionManager.isValidSubscriptionId(callbackSubId);
        setActiveDataSubId(mHasActiveDataSubIdCallback
                ? callbackSubId : SubscriptionManager.getDefaultDataSubscriptionId());
    }

    private void refreshActiveDataSubIdFromFallback() {
        if (!mHasActiveDataSubIdCallback) {
            setActiveDataSubId(SubscriptionManager.getDefaultDataSubscriptionId());
        } else {
            reevaluate();
        }
    }

    private void setActiveDataSubId(int activeDataSubId) {
        if (activeDataSubId != mActiveDataSubId) {
            dlog("Active data subId changed from " + mActiveDataSubId + " to " + activeDataSubId);
            cancelPendingDisable(mActiveDataSubId);
            mActiveDataSubId = activeDataSubId;
        }
        reevaluate();
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

    private void reevaluate() {
        updateTrafficMonitoring();
        updateRadioPolicy();
    }

    private void updateRadioPolicy() {
        if (hasActiveOrUninitializedCall() || mIsPostCallGuardActive) {
            dlog("Holding radio policy for call state");
            cancelAllPendingDisables();
            return;
        }

        for (int subId : mActiveSubIds) {
            if (shouldDisable5g(subId)) {
                scheduleDisable(subId);
            } else {
                cancelPendingDisable(subId);
                set5gAllowed(subId, true);
            }
        }
    }

    private boolean shouldDisable5g(int subId) {
        if (!isEnabled(subId)) {
            return false;
        }
        final int activeDataSubId = getActiveDataSubId();
        if (SubscriptionManager.isValidSubscriptionId(activeDataSubId)
                && subId != activeDataSubId) {
            return true;
        }
        if (mIsTetheringActive) {
            return false;
        }
        if (!isDataConnectionAllowed(subId)) {
            return true;
        }
        if (mIsPowerSaveMode) {
            return true;
        }
        if (!mIsInteractive || mIsDeviceIdleMode) {
            return true;
        }
        if (mDefaultNetworkState == DEFAULT_NETWORK_NON_CELLULAR) {
            return true;
        }
        if (mDefaultNetworkState == DEFAULT_NETWORK_CELLULAR) {
            return !mIsGameActive && !mIsHighTraffic;
        }
        return false;
    }

    private boolean hasActiveOrUninitializedCall() {
        for (int subId : mActiveSubIds) {
            final CallStateCallback callback = mCallStateCallbacks.get(subId);
            if (callback == null || callback.mCallState != TelephonyManager.CALL_STATE_IDLE) {
                return true;
            }
        }
        return false;
    }

    private boolean hasActiveCall() {
        for (CallStateCallback callback : mCallStateCallbacks.values()) {
            if (callback.mCallState != CALL_STATE_UNINITIALIZED
                    && callback.mCallState != TelephonyManager.CALL_STATE_IDLE) {
                return true;
            }
        }
        return false;
    }

    private void startPostCallGuard() {
        mHandler.removeCallbacks(mPostCallGuardRunnable);
        mIsPostCallGuardActive = true;
        cancelAllPendingDisables();
        mHandler.postDelayed(mPostCallGuardRunnable, POST_CALL_GUARD_MS);
        dlog("Post-call guard started");
    }

    private void cancelPostCallGuard() {
        mHandler.removeCallbacks(mPostCallGuardRunnable);
        mIsPostCallGuardActive = false;
    }

    private void scheduleDisable(int subId) {
        if (mPendingDisableRunnables.get(subId) != null) {
            return;
        }
        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (mPendingDisableRunnables.get(subId) != this) {
                    return;
                }
                mPendingDisableRunnables.remove(subId);
                if (!hasActiveOrUninitializedCall() && shouldDisable5g(subId)) {
                    set5gAllowed(subId, false);
                }
            }
        };
        mPendingDisableRunnables.put(subId, runnable);
        mHandler.postDelayed(runnable, DISABLE_DELAY_MS);
        dlog("Scheduled 5G disable for subId " + subId);
    }

    private void cancelPendingDisable(int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            return;
        }
        final Runnable runnable = mPendingDisableRunnables.get(subId);
        if (runnable != null) {
            mPendingDisableRunnables.remove(subId);
            mHandler.removeCallbacks(runnable);
            dlog("Cancelled 5G disable for subId " + subId);
        }
    }

    private void cancelAllPendingDisables() {
        for (int i = 0; i < mPendingDisableRunnables.size(); i++) {
            mHandler.removeCallbacks(mPendingDisableRunnables.valueAt(i));
        }
        mPendingDisableRunnables.clear();
    }

    private void set5gAllowed(int subId, boolean allowed) {
        try {
            final TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
            if ((tm.getSupportedRadioAccessFamily()
                    & TelephonyManager.NETWORK_TYPE_BITMASK_NR) == 0) {
                return;
            }
            final long currentTypes = tm.getAllowedNetworkTypesForReason(
                    ALLOWED_NETWORK_TYPES_REASON_POWER);
            final long updatedTypes = allowed
                    ? currentTypes | TelephonyManager.NETWORK_TYPE_BITMASK_NR
                    : currentTypes & ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
            if (updatedTypes == currentTypes) {
                return;
            }
            tm.setAllowedNetworkTypesForReason(ALLOWED_NETWORK_TYPES_REASON_POWER, updatedTypes);
            dlog((allowed ? "Enabled" : "Disabled") + " 5G for subId " + subId);
        } catch (SecurityException | IllegalStateException | NullPointerException
                | DeadSystemRuntimeException e) {
            Slog.w(TAG, "Unable to update 5G policy for subId " + subId, e);
        }
    }

    private void updateTrafficMonitoring() {
        if (shouldMonitorTraffic()) {
            if (!mIsTrafficMonitoring) {
                mIsTrafficMonitoring = true;
                mIsHighTraffic = true;
                mLastMobileBytes = getMobileBytes();
                mLastTrafficSampleElapsed = SystemClock.elapsedRealtime();
                mHandler.postDelayed(mTrafficPollRunnable, TRAFFIC_POLL_INTERVAL_MS);
            }
        } else {
            stopTrafficMonitoring();
        }
    }

    private boolean shouldMonitorTraffic() {
        final int subId = getActiveDataSubId();
        return SubscriptionManager.isValidSubscriptionId(subId)
                && isEnabled(subId)
                && isDataConnectionAllowed(subId)
                && !mIsPowerSaveMode
                && mDefaultNetworkState == DEFAULT_NETWORK_CELLULAR
                && !mCellularInterfaces.isEmpty()
                && mIsInteractive
                && !mIsDeviceIdleMode;
    }

    private void stopTrafficMonitoring() {
        if (!mIsTrafficMonitoring) {
            return;
        }
        mIsTrafficMonitoring = false;
        mHandler.removeCallbacks(mTrafficPollRunnable);
        mLastMobileBytes = -1;
        mLastTrafficSampleElapsed = 0;
    }

    private long getMobileBytes() {
        if (mCellularInterfaces.isEmpty()) {
            return -1;
        }
        long totalBytes = 0;
        for (String interfaceName : mCellularInterfaces) {
            final long rxBytes = TrafficStats.getRxBytes(interfaceName);
            final long txBytes = TrafficStats.getTxBytes(interfaceName);
            if (rxBytes < 0 || txBytes < 0) {
                return -1;
            }
            totalBytes += rxBytes + txBytes;
        }
        return totalBytes;
    }

    private final class ActiveDataSubscriptionCallback extends TelephonyCallback implements
            TelephonyCallback.ActiveDataSubscriptionIdListener {
        @Override
        public void onActiveDataSubscriptionIdChanged(int subId) {
            handleActiveDataSubIdChanged(subId);
        }
    }

    private final class CallStateCallback extends TelephonyCallback implements
            TelephonyCallback.CallStateListener, TelephonyCallback.DataEnabledListener {
        private final int mSubId;
        private final TelephonyManager mTelephonyManager;
        private int mCallState = CALL_STATE_UNINITIALIZED;

        CallStateCallback(int subId, TelephonyManager telephonyManager) {
            mSubId = subId;
            mTelephonyManager = telephonyManager;
        }

        @Override
        public void onCallStateChanged(int state) {
            if (state == mCallState) {
                return;
            }
            final boolean wasActiveCall = hasActiveCall();
            mCallState = state;
            dlog("Call state changed for subId " + mSubId + ": " + state);
            final boolean isActiveCall = hasActiveCall();
            if (wasActiveCall && !isActiveCall) {
                startPostCallGuard();
            } else if (isActiveCall) {
                cancelPostCallGuard();
            }
            reevaluate();
        }

        @Override
        public void onDataEnabledChanged(boolean enabled, int reason) {
            dlog("Data enabled changed for subId " + mSubId + ": " + enabled);
            reevaluate();
        }
    }

    private static void dlog(String msg) {
        if (DEBUG) Slog.d(TAG, msg);
    }
}
