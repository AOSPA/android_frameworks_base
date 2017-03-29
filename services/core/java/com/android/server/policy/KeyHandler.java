/*
 * Copyright (C) 2021, Paranoid Android
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

package com.android.server.policy;

import static android.hardware.display.AmbientDisplayConfiguration.DOZE_NO_PROXIMITY_CHECK;

import android.app.KeyguardManager;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraManager.TorchCallback;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.power.Boost;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.Looper;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.WindowManagerGlobal;

import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;

import com.android.internal.R;

import java.lang.IllegalArgumentException;

/**
 * Key handler for screen off gestures.
 * @author Carlo Savignnao
 */
public class KeyHandler {

    private static final String TAG = KeyHandler.class.getSimpleName();

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int GESTURE_REQUEST = 1;
    private static final int GESTURE_WAKE_LOCK_DURATION = 250; // ms

    private static final int MAX_SUPPORTED_GESTURES = 15;

    // Default value for gesture enabled state.
    // 1 = enabled, 0 = disabled.
    private static final int GESTURES_DEFAULT = 1; // 0 = disabled, 1 = enabled

    private static final String DOZE_INTENT = "com.android.systemui.doze.pulse";

    // Supported actions.
    private static final int DISABLED = 0;
    private static final int WAKE_UP = 1;
    private static final int PULSE_AMBIENT = 2;
    private static final int TORCH = 3;
    private static final int AIRPLANE = 4;
    private static final int MUSIC_PLAY_PAUSE = 5;
    private static final int MUSIC_NEXT = 6;
    private static final int MUSIC_PREVIOUS = 7;
    private static final int CAMERA = 8;
    private static final int DIALER = 9;

    private final Context mContext;
    private PowerManager mPowerManager;
    private PowerManagerInternal mPowerManagerInternal;
    private String mCameraId;
    private EventHandler mHandler;
    private SensorManager mSensorManager;
    private CameraManager mCameraManager;
    private AudioManager mAudioManager;
    private TelecomManager mTelecomManager;
    private StatusBarManagerInternal mStatusBarManagerInternal;
    private KeyguardManager mKeyguardManager;
    private Sensor mProximitySensor;
    private Vibrator mVibrator;
    private WakeLock mProximityWakeLock;
    private WakeLock mGestureWakeLock;
    private boolean mTorchEnabled;
    private boolean mSystemReady = false;

    private int mDoubleTapKeyCode;
    private int mSingleTapKeyCode;
    private int mDrawOKeyCode;
    private int mTwoFingerSwipeKeyCode;
    private int mDrawVKeyCode;
    private int mDrawInverseVKeyCode;
    private int mDrawArrowLeftKeyCode;
    private int mDrawArrowRightKeyCode;
    private int mOneFingerSwipeUpKeyCode;
    private int mOneFingerSwipeRightKeyCode;
    private int mOneFingerSwipeDowKeyCode;
    private int mOneFingerSwipeLeftKeyCode;
    private int mDrawMKeyCode;
    private int mDrawWKeyCode;
    private int mDrawSKeyCode;

    private int mDoubleTapGesture;
    private int mSingleTapGesture;
    private int mDrawOGesture;
    private int mTwoFingerSwipeGesture;
    private int mDrawVGesture;
    private int mDrawInverseVGesture;
    private int mDrawArrowLeftGesture;
    private int mDrawArrowRightGesture;
    private int mOneFingerSwipeUpGesture;
    private int mOneFingerSwipeRightGesture;
    private int mOneFingerSwipeDownGesture;
    private int mOneFingerSwipeLeftGesture;
    private int mDrawMGesture;
    private int mDrawWGesture;
    private int mDrawSGesture;

    private boolean mGesturesEnabled;
    private boolean mSingleDoubleSpecialCase;

    private final SparseIntArray mGestures = new SparseIntArray(MAX_SUPPORTED_GESTURES);

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            onConfigurationChanged();
        }
    };

    private class EventHandler extends Handler {

        public EventHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.obj instanceof KeyEvent) {
                final KeyEvent event = (KeyEvent) msg.obj;

                if (DEBUG) {
                    Log.w(TAG, "EventHandler.handleMessage(): event.toString(): "
                            + event.toString());
                }

                final int scanCode = event.getScanCode();
                handleGesture(mGestures.get(scanCode));
            }
        }
    }

    public KeyHandler(Context context) {
        mContext = context;
        HandlerThread handlerThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        mHandler = new EventHandler(handlerThread.getLooper());
    }

    public void systemReady() {
        mSystemReady = true;

        // Init configurations.
        getConfiguration();

        // Set up managers.
        ensureAudioManager();
        ensureTelecomManager();
        ensureVibrator();
        ensurePowerManager();
        ensureSensors();
        ensureStatusBarService();
        ensureCameraManager();
        ensureKeyguardManager();
        ensureWakeLocks();

        // Get camera id.
        ensureCameraId();

        // Register callbacks
        registerTorchCallback();

        // Register observers
        registerObservers();
    }

    private void getConfiguration() {
        final Resources resources = mContext.getResources();

        // Gestures device key codes.
        mDoubleTapKeyCode = resources.getInteger(R.integer.config_doubleTapKeyCode);
        mSingleTapKeyCode = resources.getInteger(R.integer.config_singleTapKeyCode);
        mDrawOKeyCode = resources.getInteger(R.integer.config_drawOKeyCode);
        mTwoFingerSwipeKeyCode = resources.getInteger(R.integer.config_twoFingerSwipeKeyCode);
        mDrawVKeyCode = resources.getInteger(R.integer.config_drawVKeyCode);
        mDrawInverseVKeyCode = resources.getInteger(R.integer.config_drawInverseVKeyCode);
        mDrawArrowLeftKeyCode = resources.getInteger(R.integer.config_drawArrowLeftKeyCode);
        mDrawArrowRightKeyCode = resources.getInteger(R.integer.config_drawArrowRightKeyCode);
        mOneFingerSwipeUpKeyCode = resources.getInteger(R.integer.config_oneFingerSwipeUpKeyCode);
        mOneFingerSwipeRightKeyCode = resources.getInteger(R.integer.config_oneFingerSwipeRightKeyCode);
        mOneFingerSwipeDowKeyCode = resources.getInteger(R.integer.config_oneFingerSwipeDownKeyCode);
        mOneFingerSwipeLeftKeyCode = resources.getInteger(R.integer.config_oneFingerSwipeLeftKeyCode);
        mDrawMKeyCode = resources.getInteger(R.integer.config_drawMKeyCode);
        mDrawWKeyCode = resources.getInteger(R.integer.config_drawWKeyCode);
        mDrawSKeyCode = resources.getInteger(R.integer.config_drawSKeyCode);

        mGestures.clear();
        mGestures.put(mDoubleTapKeyCode, mDoubleTapGesture);
        mGestures.put(mSingleTapKeyCode, mSingleTapGesture);
        mGestures.put(mDrawOKeyCode, mDrawOGesture);
        mGestures.put(mTwoFingerSwipeKeyCode, mTwoFingerSwipeGesture);
        mGestures.put(mDrawVKeyCode, mDrawVGesture);
        mGestures.put(mDrawInverseVKeyCode, mDrawInverseVGesture);
        mGestures.put(mDrawArrowLeftKeyCode, mDrawArrowLeftGesture);
        mGestures.put(mDrawArrowRightKeyCode, mDrawArrowRightGesture);
        mGestures.put(mOneFingerSwipeUpKeyCode, mOneFingerSwipeUpGesture);
        mGestures.put(mOneFingerSwipeRightKeyCode, mOneFingerSwipeRightGesture);
        mGestures.put(mOneFingerSwipeDowKeyCode, mOneFingerSwipeDownGesture);
        mGestures.put(mOneFingerSwipeLeftKeyCode, mOneFingerSwipeLeftGesture);
        mGestures.put(mDrawMKeyCode, mDrawMGesture);
        mGestures.put(mDrawWKeyCode, mDrawWGesture);
        mGestures.put(mDrawSKeyCode, mDrawSGesture);

        // Trigger configuration changed.
        onConfigurationChanged();
    }

    private void onConfigurationChanged() {
        boolean gesturesEnabled = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURES_ENABLED, GESTURES_DEFAULT) != 0;
        if (gesturesEnabled != mGesturesEnabled) {
            mGesturesEnabled = gesturesEnabled;
        }

        int doubleTapGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DOUBLE_TAP, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_doubleTapDefault));
        if (doubleTapGesture != mDoubleTapGesture) {
            mDoubleTapGesture = doubleTapGesture;
            mGestures.put(mDoubleTapKeyCode, mDoubleTapGesture);
        }

        int singleTapGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_SINGLE_TAP, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_singleTapDefault));
        if (singleTapGesture != mSingleTapGesture) {
            mSingleTapGesture = singleTapGesture;
            mGestures.put(mSingleTapKeyCode, mSingleTapGesture);
        }

        int drawOGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_O, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawODefault));
        if (drawOGesture != mDrawOGesture) {
            mDrawOGesture = drawOGesture;
            mGestures.put(mDrawOKeyCode, mDrawOGesture);
        }

        int twoFingerSwipeGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_TWO_FINGER_SWIPE, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_twoFingerSwipeDefault));
        if (twoFingerSwipeGesture != mTwoFingerSwipeGesture) {
            mTwoFingerSwipeGesture = twoFingerSwipeGesture;
            mGestures.put(mTwoFingerSwipeKeyCode, mTwoFingerSwipeGesture);
        }

        int drawVGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_V, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawVDefault));
        if (drawVGesture != mDrawVGesture) {
            mDrawVGesture = drawVGesture;
            mGestures.put(mDrawVKeyCode, mDrawVGesture);
        }

        int drawInverseVGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_INVERSE_V, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawInverseVDefault));
        if (drawInverseVGesture != mDrawInverseVGesture) {
            mDrawInverseVGesture = drawInverseVGesture;
            mGestures.put(mDrawInverseVKeyCode, mDrawInverseVGesture);
        }

        int drawArrowLeftGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_ARROW_LEFT, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawArrowLeftDefault));
        if (drawArrowLeftGesture != mDrawArrowLeftGesture) {
            mDrawArrowLeftGesture = drawArrowLeftGesture;
            mGestures.put(mDrawArrowLeftKeyCode, mDrawArrowLeftGesture);
        }

        int drawArrowRightGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_ARROW_RIGHT, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawArrowRightDefault));
        if (drawArrowRightGesture != mDrawArrowRightGesture) {
            mDrawArrowRightGesture = drawArrowRightGesture;
            mGestures.put(mDrawArrowRightKeyCode, mDrawArrowRightGesture);
        }

        int oneFingerSwipeUpGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_ONE_FINGER_SWIPE_UP, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_oneFingerSwipeUpDefault));
        if (oneFingerSwipeUpGesture != mOneFingerSwipeUpGesture) {
            mOneFingerSwipeUpGesture = oneFingerSwipeUpGesture;
            mGestures.put(mOneFingerSwipeUpKeyCode, mOneFingerSwipeUpGesture);
        }

        int oneFingerSwipeRightGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_ONE_FINGER_SWIPE_RIGHT, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_oneFingerSwipeRightDefault));
        if (oneFingerSwipeRightGesture != mOneFingerSwipeRightGesture) {
            mOneFingerSwipeRightGesture = oneFingerSwipeRightGesture;
            mGestures.put(mOneFingerSwipeRightKeyCode, mOneFingerSwipeRightGesture);
        }

        int oneFingerSwipeDownGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_ONE_FINGER_SWIPE_DOWN, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_oneFingerSwipeDownDefault));
        if (oneFingerSwipeDownGesture != mOneFingerSwipeDownGesture) {
            mOneFingerSwipeDownGesture = oneFingerSwipeDownGesture;
            mGestures.put(mOneFingerSwipeDowKeyCode, mOneFingerSwipeDownGesture);
        }

        int oneFingerSwipeLeftGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_ONE_FINGER_SWIPE_LEFT, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_oneFingerSwipeLeftDefault));
        if (oneFingerSwipeLeftGesture != mOneFingerSwipeLeftGesture) {
            mOneFingerSwipeLeftGesture = oneFingerSwipeLeftGesture;
            mGestures.put(mOneFingerSwipeLeftKeyCode, mOneFingerSwipeLeftGesture);
        }

        int drawMGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_M, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawMDefault));
        if (drawMGesture != mDrawMGesture) {
            mDrawMGesture = drawMGesture;
            mGestures.put(mDrawMKeyCode, mDrawMGesture);
        }

        int drawWGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_W, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawWDefault));
        if (drawWGesture != mDrawWGesture) {
            mDrawWGesture = drawWGesture;
            mGestures.put(mDrawWKeyCode, mDrawWGesture);
        }

        int drawSGesture = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.GESTURE_DRAW_S, mContext.getResources()
                        .getInteger(com.android.internal.R.integer.config_drawSDefault));
        if (drawSGesture != mDrawSGesture) {
            mDrawSGesture = drawSGesture;
            mGestures.put(mDrawSKeyCode, mDrawSGesture);
        }

        mSingleDoubleSpecialCase = mGestures.get(mDoubleTapKeyCode) > 0 &&
                mGestures.get(mSingleTapKeyCode) > 0;
    }

    private void ensureAudioManager() {
        if (mAudioManager == null) {
            mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        }
    }

    private void ensureTelecomManager() {
        if (mTelecomManager == null) {
            mTelecomManager = TelecomManager.from(mContext);
        }
    }

    private void ensureVibrator() {
        if (mVibrator == null) {
            mVibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
            if (!mVibrator.hasVibrator()) {
                mVibrator = null;
            }
        }
    }

    private void ensurePowerManager() {
        if (mPowerManager == null) {
            mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        }
        if (mPowerManagerInternal == null) {
            mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);

        }
    }

    private void ensureSensors() {
        if (mSensorManager == null) {
            mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        }
        if (mProximitySensor == null) {
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        }
    }

    private void ensureStatusBarService() {
        if (mStatusBarManagerInternal == null) {
            mStatusBarManagerInternal = LocalServices.getService(StatusBarManagerInternal.class);
        }
    }

    private void ensureKeyguardManager() {
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        }
    }

    private void ensureWakeLocks() {
        if (mGestureWakeLock == null) {
            mGestureWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "GestureWakeLock");
        }
        if (mProximityWakeLock == null) {
            mProximityWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "ProximityWakeLock");
        }
    }

    private void ensureCameraManager() {
        if (mCameraManager == null) {
            mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        }
    }

    private void ensureCameraId() {
        try {
            mCameraId = getCameraId();
        } catch (Throwable e) {
            Log.e(TAG, "Couldn't initialize.", e);
            return;
        }
    }

    private void registerTorchCallback() {
        if (mCameraManager != null) {
            mCameraManager.registerTorchCallback(mTorchCallback, mHandler);
        }
    }

    private void registerObservers() {
        final ContentResolver resolver = mContext.getContentResolver();
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURES_ENABLED),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DOUBLE_TAP),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_SINGLE_TAP),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_V),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_INVERSE_V),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_O),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_M),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_W),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_S),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_ARROW_LEFT),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_DRAW_ARROW_RIGHT),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_ONE_FINGER_SWIPE_UP),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_ONE_FINGER_SWIPE_RIGHT),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_ONE_FINGER_SWIPE_UP),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_ONE_FINGER_SWIPE_RIGHT),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_ONE_FINGER_SWIPE_DOWN),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_ONE_FINGER_SWIPE_LEFT),
                false, mObserver, UserHandle.USER_ALL);
        resolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.GESTURE_TWO_FINGER_SWIPE),
                false, mObserver, UserHandle.USER_ALL);
    }

    private void handleGesture(int gesture) {
        if (DEBUG) {
            Log.w(TAG, "handleCodeBehavior: gesture = " + gesture);
        }

        acquireGestureWakeLock();

        mPowerManagerInternal.setPowerBoost(Boost.INTERACTION, 0);

        boolean handled;
        boolean vibrate;

        switch(gesture) {
            case DISABLED:
                // Don't do anything if the gesture was disabled by the user
                releaseGestureWakeLock();
                return;
            case CAMERA:
                mStatusBarManagerInternal.onCameraLaunchGestureDetected(
                        StatusBarManager.CAMERA_LAUNCH_SOURCE_SCREEN_GESTURE);
                handled = true;
                vibrate = true;
                break;
            case MUSIC_PLAY_PAUSE:
                handled = dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                vibrate = true;
                break;
            case TORCH:
                handled = setTorchMode(!mTorchEnabled);
                vibrate = true;
                break;
            case MUSIC_PREVIOUS:
                handled = isMusicActive() && dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                vibrate = true;
                break;
            case MUSIC_NEXT:
                handled = isMusicActive() && dispatchMediaKeyWithWakeLockToMediaSession(KeyEvent.KEYCODE_MEDIA_NEXT);
                vibrate = true;
                break;
            case WAKE_UP:
                if (mPowerManager.isInteractive()) {
                    return;
                }
                mPowerManager.wakeUp(SystemClock.uptimeMillis());
                handled = true;
                vibrate = false;
                break;
            case DIALER:
                mPowerManager.wakeUp(SystemClock.uptimeMillis());
                if (isKeyguardShowing()) {
                    dismissKeyguard();
                }
                Intent dialIntent = new Intent(Intent.ACTION_DIAL);
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(dialIntent, null);
                handled = true;
                vibrate = true;
                break;
            case AIRPLANE:
                // Change the system setting
                boolean enabled = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
                Settings.Global.putInt(mContext.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, !enabled ? 1 : 0);
                // Post the intent
                Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra("state", enabled);
                mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                handled = true;
                vibrate = true;
                break;
            case PULSE_AMBIENT:
                Intent pulseIntent = new Intent(DOZE_INTENT);
                pulseIntent.putExtra(DOZE_NO_PROXIMITY_CHECK, 1);
                mContext.sendBroadcastAsUser(pulseIntent,
                    new UserHandle(UserHandle.USER_CURRENT));
                handled = true;
                vibrate = false;
                break;
            default:
                handled = false;
                vibrate = false;
                releaseGestureWakeLock();
                break;
        }

        doHapticFeedback(handled, vibrate);
    }

    private void acquireGestureWakeLock() {
        if (mGestureWakeLock.isHeld()) {
            mGestureWakeLock.release();
        }
        mGestureWakeLock.acquire(KeyHandler.GESTURE_WAKE_LOCK_DURATION);
    }

    private void releaseGestureWakeLock() {
        if (mGestureWakeLock.isHeld()) {
            mGestureWakeLock.release();
        }
    }

    public boolean handleKeyEvent(KeyEvent event) {
        if (DEBUG) {
            Log.w(TAG, "handleKeyEvent(): event.toString(): " + event.toString());
        }

        if (!mSystemReady || !mGesturesEnabled || isDisabledByPhoneState()) {
            return false;
        }

        int action = event.getAction();
        int scanCode = event.getScanCode();
        int repeatCount = event.getRepeatCount();

        if (scanCode <= 0) {
            if (DEBUG) {
                Log.w(TAG, "handleKeyEvent(): scanCode is invalid, returning." );
            }
            return false;
        }

        if (action != KeyEvent.ACTION_UP || repeatCount != 0) {
            if (DEBUG) {
                Log.w(TAG, "handleKeyEvent(): action != ACTION_UP || repeatCount != 0, returning.");
            }
            return false;
        }

        boolean isKeySupportedAndEnabled = mGestures.get(scanCode) > 0;

        if (DEBUG) {
            Log.w(TAG, "handleKeyEvent(): isKeySupportedAndEnabled = " + isKeySupportedAndEnabled);
        }

        // If it's held, means we just processed a gesture or we are in the middle of one
        if (isKeySupportedAndEnabled && !mGestureWakeLock.isHeld()) {
            Message msg = getMessageForKeyEvent(event);
            if (mProximitySensor != null) {
                mHandler.sendMessageDelayed(msg, 250 /* proximity timeout */);
                processEvent(event);
            } else {
                if (scanCode == mSingleTapKeyCode && mSingleDoubleSpecialCase) {
                    mHandler.sendMessageDelayed(msg, ViewConfiguration.getDoubleTapTimeout());
                } else {
                    mHandler.removeMessages(GESTURE_REQUEST);
                    mHandler.sendMessage(msg);
                }
            }
        }

        return isKeySupportedAndEnabled;
    }

    private Message getMessageForKeyEvent(KeyEvent keyEvent) {
        Message msg = mHandler.obtainMessage(GESTURE_REQUEST);
        msg.obj = keyEvent;
        return msg;
    }

    private void processEvent(final KeyEvent keyEvent) {
        mProximityWakeLock.acquire();
        mSensorManager.registerListener(new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                mProximityWakeLock.release();
                mSensorManager.unregisterListener(this);
                if (!mHandler.hasMessages(GESTURE_REQUEST)) {
                    // The sensor took to long, ignoring.
                    return;
                }
                mHandler.removeMessages(GESTURE_REQUEST);
                if (event.values[0] == mProximitySensor.getMaximumRange()) {
                    Message msg = getMessageForKeyEvent(keyEvent);
                    mHandler.sendMessage(msg);
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) { }

        }, mProximitySensor, SensorManager.SENSOR_DELAY_FASTEST);
    }

    private boolean dispatchMediaKeyWithWakeLockToMediaSession(int keycode) {
        MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(mContext);
        if (helper != null) {
            KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
            helper.sendMediaButtonEvent(event, true);
            event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
            helper.sendMediaButtonEvent(event, true);
            return true;
        } else {
            if (DEBUG) {
                Log.w(TAG, "Unable to send media key event");
            }
            return false;
        }
    }

    private void doHapticFeedback(boolean success, boolean vibrate) {
        final boolean hapticsEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 0, UserHandle.USER_CURRENT) != 0;
        if (vibrate && hapticsEnabled && mVibrator != null) {
            if (success) {
                mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
            } else {
                mVibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK));
            }
        }
    }

    private String getCameraId() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();
        if (ids != null && ids.length > 0) {
            for (String id : ids) {
                CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
                Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
                if (flashAvailable != null && flashAvailable
                        && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        }
        return null;
    }

    private boolean setTorchMode(boolean enabled) {
        if (mCameraId == null) ensureCameraId();
        try {
            mCameraManager.setTorchMode(mCameraId, enabled);
        } catch (CameraAccessException | IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    private final TorchCallback mTorchCallback = new TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (!TextUtils.isEmpty(mCameraId)) {
                if (mCameraId.equals(cameraId)) {
                    mTorchEnabled = enabled;
                }
            } else {
                mTorchEnabled = enabled;
            }
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            if (!TextUtils.isEmpty(mCameraId)) {
                if (mCameraId.equals(cameraId)) {
                    mTorchEnabled = false;
                }
            } else {
                mTorchEnabled = false;
            }
        }
    };

    private boolean isMusicActive() {
        if (mAudioManager != null) {
            return mAudioManager.isMusicActive();
        }
        return false;
    }

    private void dismissKeyguard() {
        try {
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard(null, null);
        } catch (RemoteException e) {
            if (DEBUG) {
                Log.w(TAG, "WindowManagerGlobal.getWindowManagerService() instance not alive");
            }
        }
    }

    private boolean isKeyguardShowing() {
        return mKeyguardManager.isKeyguardLocked();
    }

    private long[] getLongIntArray(Resources r, int resid) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return null;
        }
        long[] out = new long[ar.length];
        for (int i = 0; i < ar.length; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    private boolean isDisabledByPhoneState() {
        if (mTelecomManager != null) {
            return mTelecomManager.isInCall() || mTelecomManager.isRinging();
        }
        return false;
    }
}
