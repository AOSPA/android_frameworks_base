/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.vibrator;

import static android.os.Trace.TRACE_TAG_VIBRATOR;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.os.Binder;
import android.os.IVibratorStateListener;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.RichTapVibrationEffect;
import android.os.Trace;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.PwlePoint;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import libcore.util.NativeAllocationRegistry;

/** Controls a single vibrator. */
// TODO(b/409002423): remove this class once remove_hidl_support flag removed
final class VibratorController implements HalVibrator {
    private static final String TAG = "VibratorController";
    // RichTap perform returns a command id, so use a short positive duration for framework state.
    private static final long RICHTAP_PREBAKED_DURATION_MS = 30;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final NativeWrapper mNativeWrapper;

    // Vibrator state listeners that support concurrent updates and broadcasts, but should lock
    // while broadcasting to guarantee delivery order.
    private final RemoteCallbackList<IVibratorStateListener> mVibratorStateListeners =
            new RemoteCallbackList<>();

    // Vibrator state variables that are updated from synchronized blocks but can be read anytime
    // for a snippet of the current known vibrator state/info.
    private volatile VibratorInfo mVibratorInfo;
    private volatile boolean mVibratorInfoLoadSuccessful;
    private volatile State mCurrentState;
    private volatile float mCurrentAmplitude;

    @Nullable
    private RichTapVibratorService mRichTapService;

    VibratorController(int vibratorId) {
        this(vibratorId, new NativeWrapper());
    }

    VibratorController(int vibratorId, NativeWrapper nativeWrapper) {
        mNativeWrapper = nativeWrapper;
        mVibratorInfo = new VibratorInfo.Builder(vibratorId).build();
        mCurrentState = State.IDLE;

        if (RichTapVibrationEffect.isSupported()) {
            mRichTapService = new RichTapVibratorService();
        }
    }

    @Override
    public void init(@NonNull Callbacks callbacks) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.init");
        try {
            int vibratorId = mVibratorInfo.getId();
            mNativeWrapper.init(vibratorId, callbacks);
            // Try to load VibratorInfo early.
            VibratorInfo.Builder vibratorInfoBuilder = new VibratorInfo.Builder(vibratorId);
            mVibratorInfoLoadSuccessful = mNativeWrapper.getInfo(vibratorInfoBuilder);
            mVibratorInfo = vibratorInfoBuilder.build();
            if (!mVibratorInfoLoadSuccessful) {
                Slog.e(TAG, "Init failed to load some HAL info for vibrator " + vibratorId);
            }

            // Reset the hardware to a default state.
            // In case this is a runtime restart instead of a fresh boot.
            setExternalControl(false);
            off();
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public void onSystemReady() {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.onSystemReady");
        try {
            // Early check outside lock, for quick return.
            if (mVibratorInfoLoadSuccessful) {
                return;
            }
            synchronized (mLock) {
                if (mVibratorInfoLoadSuccessful) {
                    return;
                }
                int vibratorId = mVibratorInfo.getId();
                VibratorInfo.Builder vibratorInfoBuilder = new VibratorInfo.Builder(vibratorId);
                mVibratorInfoLoadSuccessful = mNativeWrapper.getInfo(vibratorInfoBuilder);
                mVibratorInfo = vibratorInfoBuilder.build();
                if (!mVibratorInfoLoadSuccessful) {
                    Slog.e(TAG, "Failed retry of HAL getInfo for vibrator " + vibratorId);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public boolean registerVibratorStateListener(@NonNull IVibratorStateListener listener) {
        final long token = Binder.clearCallingIdentity();
        try {
            // Register the listener and send the first state atomically, to avoid potentially
            // out of order broadcasts in between.
            synchronized (mLock) {
                if (!mVibratorStateListeners.register(listener)) {
                    return false;
                }
                // Notify its callback after new client registered.
                notifyStateListener(listener, isVibrating(mCurrentState));
            }
            return true;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean unregisterVibratorStateListener(@NonNull IVibratorStateListener listener) {
        final long token = Binder.clearCallingIdentity();
        try {
            return mVibratorStateListeners.unregister(listener);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Checks if the {@link VibratorInfo} was loaded from the vibrator hardware successfully. */
    boolean isVibratorInfoLoadSuccessful() {
        return mVibratorInfoLoadSuccessful;
    }

    @NonNull
    @Override
    public VibratorInfo getInfo() {
        return mVibratorInfo;
    }

    @Override
    public boolean isVibrating() {
        return isVibrating(mCurrentState);
    }

    @Override
    public float getCurrentAmplitude() {
        return mCurrentAmplitude;
    }

    @Override
    public boolean setExternalControl(boolean externalControl) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR,
                externalControl ? "HalVibrator.enableExternalControl"
                : "HalVibrator.disableExternalControl");
        try {
            if (!mVibratorInfo.hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
                return false;
            }
            State newState = externalControl ? State.UNDER_EXTERNAL_CONTROL : State.IDLE;
            synchronized (mLock) {
                mNativeWrapper.setExternalControl(externalControl);
                updateStateAndNotifyListenersLocked(newState);
            }
            return true;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public boolean setAlwaysOn(int id, @Nullable PrebakedSegment prebaked) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR,
                prebaked != null ? "HalVibrator.enableAlwaysOn" : "HalVibrator.disableAlwaysOn");
        try {
            if (!mVibratorInfo.hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
                return false;
            }
            synchronized (mLock) {
                if (prebaked == null) {
                    mNativeWrapper.alwaysOnDisable(id);
                } else {
                    mNativeWrapper.alwaysOnEnable(id, prebaked.getEffectId(),
                            prebaked.getEffectStrength());
                }
            }
            return true;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public boolean setAmplitude(float amplitude) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.setAmplitude");
        try {
            boolean success = false;
            synchronized (mLock) {
                if (mRichTapService != null) {
                    int strength = (int) (255.0f * amplitude);
                    mRichTapService.richTapVibratorSetAmplitude(strength);
                } else if (mVibratorInfo.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
                    mNativeWrapper.setAmplitude(amplitude);
                    success = true;
                }
                if (mCurrentState == State.VIBRATING) {
                    mCurrentAmplitude = amplitude;
                }
            }
            return success;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public long on(long vibrationId, long stepId, long milliseconds) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.onMillis");
        try {
            synchronized (mLock) {
                long duration = 0;
                if (mRichTapService != null) {
                    duration = milliseconds;
                    mRichTapService.richTapVibratorOn(duration);
                } else {
                    duration = mNativeWrapper.on(milliseconds, vibrationId, stepId);
                }
                if (duration > 0) {
                    updateStateAndNotifyListenersLocked(State.VIBRATING);
                }
                return duration;
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public long on(long vibrationId, long stepId, VibrationEffect.VendorEffect vendorEffect) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.onVendor");
        synchronized (mLock) {
            Parcel vendorData = Parcel.obtain();
            try {
                vendorEffect.getVendorData().writeToParcel(vendorData, /* flags= */ 0);
                vendorData.setDataPosition(0);
                long duration = mNativeWrapper.performVendorEffect(vendorData,
                        vendorEffect.getEffectStrength(), vendorEffect.getScale(),
                        vendorEffect.getAdaptiveScale(), vibrationId, stepId);
                if (duration > 0) {
                    updateStateAndNotifyListenersLocked(State.VIBRATING);
                }
                return duration;
            } finally {
                vendorData.recycle();
                Trace.traceEnd(TRACE_TAG_VIBRATOR);
            }
        }
    }

    @Override
    public long on(long vibrationId, long stepId, PrebakedSegment prebaked) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.onPrebaked");
        try {
            synchronized (mLock) {
                long duration = 0;
                boolean useRichTap = mRichTapService != null
                        && RichTapVibrationEffect.isInnerEffectSupported(prebaked.getEffectId());
                int strength = useRichTap
                        ? RichTapVibrationEffect.getInnerEffectStrength(
                                prebaked.getEffectStrength())
                        : 0;
                if (strength > 0) {
                    duration = RICHTAP_PREBAKED_DURATION_MS;
                    int richTapEffectId = RichTapVibrationEffect.getInnerEffectId(
                            prebaked.getEffectId());
                    mRichTapService.richTapVibratorPerform(richTapEffectId, (byte) strength);
                } else {
                    duration = mNativeWrapper.perform(prebaked.getEffectId(),
                            prebaked.getEffectStrength(), vibrationId, stepId);
                }
                if (duration > 0) {
                    updateStateAndNotifyListenersLocked(State.VIBRATING);
                }
                return duration;
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public long on(long vibrationId, long stepId, PrimitiveSegment[] primitives) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.onPrimitives");
        try {
            if (!mVibratorInfo.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
                return 0;
            }
            synchronized (mLock) {
                long duration = mNativeWrapper.compose(primitives, vibrationId, stepId);
                if (duration > 0) {
                    updateStateAndNotifyListenersLocked(State.VIBRATING);
                }
                return duration;
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public long on(long vibrationId, long stepId, PwlePoint[] pwlePoints) {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.onPwleV2");
        try {
            if (!mVibratorInfo.hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2)) {
                return 0;
            }
            synchronized (mLock) {
                long duration = mNativeWrapper.composePwleV2(pwlePoints, vibrationId, stepId);
                if (duration > 0) {
                    updateStateAndNotifyListenersLocked(State.VIBRATING);
                }
                return duration;
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public boolean off() {
        Trace.traceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.off");
        try {
            synchronized (mLock) {
                mNativeWrapper.off();
                updateStateAndNotifyListenersLocked(State.IDLE);
            }
            return true;
        } finally {
            Trace.traceEnd(TRACE_TAG_VIBRATOR);
        }
    }

    @Override
    public String toString() {
        return "VibratorController{"
                + "mVibratorInfo=" + mVibratorInfo
                + ", mVibratorInfoLoadSuccessful=" + mVibratorInfoLoadSuccessful
                + ", mCurrentState=" + mCurrentState.name()
                + ", mCurrentAmplitude=" + mCurrentAmplitude
                + ", mVibratorStateListeners count="
                + mVibratorStateListeners.getRegisteredCallbackCount()
                + '}';
    }

    @Override
    public void dump(IndentingPrintWriter pw) {
        pw.println("Vibrator (id=" + mVibratorInfo.getId() + "):");
        pw.increaseIndent();
        pw.println("currentState = " + mCurrentState.name());
        pw.println("currentAmplitude = " + mCurrentAmplitude);
        pw.println("vibratorInfoLoadSuccessful = " + mVibratorInfoLoadSuccessful);
        pw.println("vibratorStateListener size = "
                + mVibratorStateListeners.getRegisteredCallbackCount());
        mVibratorInfo.dump(pw);
        pw.decreaseIndent();
    }

    /**
     * Updates current vibrator state and notify listeners if {@link #isVibrating()} result changed.
     */
    @GuardedBy("mLock")
    private void updateStateAndNotifyListenersLocked(State state) {
        if (mCurrentState == State.IDLE && state == State.VIBRATING) {
            // First vibrate command.
            Trace.asyncTraceBegin(TRACE_TAG_VIBRATOR, "HalVibrator.vibration", 0);
        } else if (mCurrentState == State.VIBRATING && state == State.IDLE) {
            // First off after a vibrate command.
            Trace.asyncTraceEnd(TRACE_TAG_VIBRATOR, "HalVibrator.vibration", 0);
        }
        boolean previousIsVibrating = isVibrating(mCurrentState);
        final boolean newIsVibrating = isVibrating(state);
        mCurrentState = state;
        mCurrentAmplitude = newIsVibrating ? -1 : 0;
        if (previousIsVibrating != newIsVibrating) {
            // The broadcast method is safe w.r.t. register/unregister listener methods, but lock
            // is required here to guarantee delivery order.
            mVibratorStateListeners.broadcast(
                    listener -> notifyStateListener(listener, newIsVibrating));
        }
    }

    private void notifyStateListener(IVibratorStateListener listener, boolean isVibrating) {
        try {
            listener.onVibrating(isVibrating);
        } catch (RemoteException | RuntimeException e) {
            Slog.e(TAG, "Vibrator state listener failed to call", e);
        }
    }

    /** Returns true only if given state is not {@link State#IDLE}. */
    private static boolean isVibrating(State state) {
        return state != State.IDLE;
    }

    /** Wrapper around the static-native methods of {@link VibratorController} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {
        /**
         * Initializes the native part of this controller, creating a global reference to given
         * {@link Callbacks} and returns a newly allocated native pointer. This
         * wrapper is responsible for deleting this pointer by calling the method pointed
         * by {@link #getNativeFinalizer()}.
         *
         * <p><b>Note:</b> Make sure the given implementation of {@link Callbacks}
         * do not hold any strong reference to the instance responsible for deleting the returned
         * pointer, to avoid creating a cyclic GC root reference.
         */
        private static native long nativeInit(int vibratorId, Callbacks callbacks);

        /**
         * Returns pointer to native function responsible for cleaning up the native pointer
         * allocated and returned by {@link #nativeInit(int, Callbacks)}.
         */
        private static native long getNativeFinalizer();

        private static native long on(long nativePtr, long milliseconds, long vibrationId,
                long stepId);

        private static native void off(long nativePtr);

        private static native void setAmplitude(long nativePtr, float amplitude);

        private static native long performEffect(long nativePtr, long effect, long strength,
                long vibrationId, long stepId);

        private static native long performVendorEffect(long nativePtr, Parcel vendorData,
                long strength, float scale, float adaptiveScale, long vibrationId, long stepId);

        private static native long performComposedEffect(long nativePtr, PrimitiveSegment[] effect,
                long vibrationId, long stepId);

        private static native long performPwleV2Effect(long nativePtr, PwlePoint[] effect,
                long vibrationId, long stepId);

        private static native void setExternalControl(long nativePtr, boolean enabled);

        private static native void alwaysOnEnable(long nativePtr, long id, long effect,
                long strength);

        private static native void alwaysOnDisable(long nativePtr, long id);

        private static native boolean getInfo(long nativePtr, VibratorInfo.Builder infoBuilder);

        private long mNativePtr = 0;

        /** Initializes native controller and allocation registry to destroy native instances. */
        public void init(int vibratorId, Callbacks callbacks) {
            mNativePtr = nativeInit(vibratorId, callbacks);
            long finalizerPtr = getNativeFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorController.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativePtr);
            }
        }

        /** Turns vibrator on for given time. */
        public long on(long milliseconds, long vibrationId, long stepId) {
            return on(mNativePtr, milliseconds, vibrationId, stepId);
        }

        /** Turns vibrator off. */
        public void off() {
            off(mNativePtr);
        }

        /** Sets the amplitude for the vibrator to run. */
        public void setAmplitude(float amplitude) {
            setAmplitude(mNativePtr, amplitude);
        }

        /** Turns vibrator on to perform one of the supported effects. */
        public long perform(long effect, long strength, long vibrationId, long stepId) {
            return performEffect(mNativePtr, effect, strength, vibrationId, stepId);
        }

        /** Turns vibrator on to perform a vendor-specific effect. */
        public long performVendorEffect(Parcel vendorData, long strength, float scale,
                float adaptiveScale, long vibrationId, long stepId) {
            return performVendorEffect(mNativePtr, vendorData, strength, scale, adaptiveScale,
                    vibrationId, stepId);
        }

        /** Turns vibrator on to perform effect composed of give primitives effect. */
        public long compose(PrimitiveSegment[] primitives, long vibrationId, long stepId) {
            return performComposedEffect(mNativePtr, primitives, vibrationId, stepId);
        }

        /** Turns vibrator on to perform PWLE effect composed of given points. */
        public long composePwleV2(PwlePoint[] pwlePoints, long vibrationId, long stepId) {
            return performPwleV2Effect(mNativePtr, pwlePoints, vibrationId, stepId);
        }

        /** Enabled the device vibrator to be controlled by another service. */
        public void setExternalControl(boolean enabled) {
            setExternalControl(mNativePtr, enabled);
        }

        /** Enable always-on vibration with given id and effect. */
        public void alwaysOnEnable(long id, long effect, long strength) {
            alwaysOnEnable(mNativePtr, id, effect, strength);
        }

        /** Disable always-on vibration for given id. */
        public void alwaysOnDisable(long id) {
            alwaysOnDisable(mNativePtr, id);
        }

        /**
         * Loads device vibrator metadata and returns true if all metadata was loaded successfully.
         */
        public boolean getInfo(VibratorInfo.Builder infoBuilder) {
            return getInfo(mNativePtr, infoBuilder);
        }
    }
}
