/* Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.lights;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Color;
import android.hardware.light.HwLight;
import android.hardware.light.HwLightEffect;
import android.hardware.light.HwLightState;
import android.hardware.light.ILights;
import android.hardware.light.InterpolationType;
import android.hardware.light.LightType;
import android.hardware.lights.ColorSequence;
import android.hardware.lights.ColorSequence.InterpolationMode;
import android.hardware.lights.ILightsManager;
import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.MultiLightEffect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;
import com.android.server.lights.feature.flags.Flags;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class LightsService extends SystemService {
    static final String TAG = "LightsService";
    static final boolean DEBUG = false;

    // Priority for the system override session, higher than typical app priorities.
    static final int DEFAULT_SYSTEM_SESSION_PRIORITY = 1_000;

    @VisibleForTesting
    final SparseArray<LightImpl> mLightsByType = new SparseArray<>();
    private final SparseArray<LightImpl> mLightsById = new SparseArray<>();

    // A special session used to mute all user-controllable lights.
    private LightsManagerBinderService.Session mSystemOverrideSession;

    @Nullable
    private final Supplier<ILights> mVintfLights;

    @VisibleForTesting
    final LightsManagerBinderService mManagerService;

    private Handler mH;
    private boolean mSupportsEffects = false;

    private final class LightsManagerBinderService extends ILightsManager.Stub
          implements IBinder.DeathRecipient {

        private final LightsManagerBinderService.Session.EventListener mSessionListener =
                new Session.EventListener() {
                    @Override
                    public void onEffectPlaybackComplete(Session session, int playbackGeneration) {
                        synchronized (LightsService.this) {
                            if (!mSessions.contains(session)) {
                                return;
                            }
                            if (session.transitionToNextEffect(playbackGeneration)) {
                                computeAndApplyLightConfigurationsLocked();
                            }
                        }
                    }
                };

        @GuardedBy("LightsService.this")
        private final List<Session> mSessions = new ArrayList<>();

        LightsManagerBinderService() {
            super(PermissionEnforcer.fromContext(getContext()));
        }

        /** @see #binderDied(IBinder) */
        @Override
        public void binderDied() {}

        /**
         * Callback for sessions which died without explicitly closing.
         */
        @Override
        public void binderDied(IBinder token) {
            closeSessionInternal(token);
        }

        /**
         * Returns the lights available for apps to control on the device. Only lights that aren't
         * reserved for system use are available to apps.
         */
        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public List<Light> getLights() {
            getLights_enforcePermission();

            synchronized (LightsService.this) {
                final List<Light> lights = new ArrayList<Light>();
                for (int i = 0; i < mLightsById.size(); i++) {
                    if (!mLightsById.valueAt(i).isSystemLight()) {
                        HwLight hwLight = mLightsById.valueAt(i).mHwLight;
                        Light.Builder lightBuilder =
                                new Light.Builder(hwLight.id, hwLight.ordinal, hwLight.type);

                        if (mSupportsEffects && hwLight.minUpdatePeriodMillis > 0) {
                            lightBuilder.setMinUpdatePeriodMillis(hwLight.minUpdatePeriodMillis);
                            lightBuilder.setCapabilities(
                                    Light.LIGHT_CAPABILITY_ANIMATION
                                            | Light.LIGHT_CAPABILITY_COLOR_RGB);
                        }

                        lights.add(lightBuilder.build());
                    }
                }
                return lights;
            }
        }

        /**
         * Updates the set of light requests for {@code token} with additions and removals from
         * {@code lightIds} and {@code lightStates}.
         *
         * <p>Null values mean that the request should be removed, and the light turned off if it
         * is not being used by anything else.
         */
        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void setLightStates(IBinder token, int[] lightIds, LightState[] lightStates) {
            setLightStates_enforcePermission();
            Preconditions.checkState(lightIds.length == lightStates.length);

            synchronized (LightsService.this) {
                Session session = getSessionLocked(Preconditions.checkNotNull(token));
                Preconditions.checkState(session != null, "not registered");

                checkRequestIsValid(lightIds);

                for (int i = 0; i < lightIds.length; i++) {
                    session.setRequest(lightIds[i], lightStates[i]);
                }
                computeAndApplyLightConfigurationsLocked();
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public @Nullable LightState getLightState(int lightId) {
            getLightState_enforcePermission();

            synchronized (LightsService.this) {
                final LightImpl light = mLightsById.get(lightId);
                if (light == null || light.isSystemLight()) {
                    throw new IllegalArgumentException("Invalid light: " + lightId);
                }
                return new LightState(light.getColor());
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void setLightEffect(IBinder token, MultiLightEffect effect) {
            setLightEffect_enforcePermission();

            if (!mSupportsEffects) {
                throw new UnsupportedOperationException("This device does not support effects.");
            }
            Preconditions.checkArgument(
                    effect.getLights().length == effect.getSequences().size(), "uneven effect");

            synchronized (LightsService.this) {
                Session session = getSessionLocked(Preconditions.checkNotNull(token));
                Preconditions.checkState(session != null, "not registered");

                checkRequestIsValid(effect.getLights());

                session.addEffect(effect);
                computeAndApplyLightConfigurationsLocked();
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public ColorSequence getLightSequence(int lightId)
                throws RemoteException {
            getLightSequence_enforcePermission();

            if (!mSupportsEffects) {
                Slog.w(TAG, "Device does not support animations.");
                return null;
            }

            synchronized (LightsService.this) {
                final LightImpl light = mLightsById.get(lightId);

                Preconditions.checkArgument(light != null && !light.isSystemLight());
                if (light.mConfiguration == null || !light.mConfiguration.isDynamic()) {
                    return null;
                }
                return light.mConfiguration.getEffect().getSequences().get(lightId);
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void openSession(IBinder token, int priority) {
            openSession_enforcePermission();
            Preconditions.checkNotNull(token);

            synchronized (LightsService.this) {
                Preconditions.checkState(getSessionLocked(token) == null, "already registered");
                try {
                    token.linkToDeath(LightsManagerBinderService.this, 0);
                    mSessions.add(new Session(token, priority, mSessionListener, mH));
                    Collections.sort(mSessions);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Couldn't open session, client already died" , e);
                    throw new IllegalArgumentException("Client is already dead.");
                }
            }
        }

        @android.annotation.EnforcePermission(android.Manifest.permission.CONTROL_DEVICE_LIGHTS)
        @Override
        public void closeSession(IBinder token) {
            closeSession_enforcePermission();
            Preconditions.checkNotNull(token);
            closeSessionInternal(token);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            synchronized (LightsService.this) {
                pw.println("Service: " + mVintfLights != null ? mVintfLights.get() : null);

                pw.println("Lights:");
                for (int i = 0; i < mLightsById.size(); i++) {
                    final LightImpl light = mLightsById.valueAt(i);
                    pw.println(TextUtils.formatSimple("  Light id=%d ordinal=%d color=%08x",
                            light.mHwLight.id, light.mHwLight.ordinal, light.getColor()));
                }

                pw.println("Session clients:");
                for (Session session : mSessions) {
                    pw.println("  Session token=" + session.mToken);
                    for (int i = 0; i < session.mConfigurations.size(); i++) {
                        if (Flags.enableLightAnimations()) {
                            pw.println(TextUtils.formatSimple("    Configuration lightId=%d %s",
                                    session.mConfigurations.keyAt(i),
                                    session.mConfigurations.valueAt(i)));
                        } else {
                            // With the flag off, state should never be null, since effects are not
                            // allowed, but is worth checking to avoid the NullPointerException.
                            LightState state = session.mConfigurations.valueAt(i).getState();
                            pw.println(TextUtils.formatSimple("    Request id=%d color=%08x",
                                    session.mConfigurations.keyAt(i),
                                    state != null ? state.getColor() : 0));
                        }
                    }
                }
            }
        }

        /**
         * Mutes or unmutes all non-critical lights on the device.
         * <p>
         * When muted, user-controllable lights are overridden by a high-priority system session
         * that turns them off. Non-critical system lights are muted directly. Critical lights
         * such as the screen backlight are unaffected.
         *
         * @param muted true to mute lights or false to unmute them.
         */
        public void setMutedState(boolean muted) {
            synchronized (LightsService.this) {
                boolean currentlyMuted = mSystemOverrideSession != null;
                if (muted == currentlyMuted) {
                    // We are already in the right state. Just return.
                    Slog.i(TAG, "Lights already " + (muted ? "unmuted." : "muted."));
                    return;
                }

                LightState state = null;
                if (muted) {
                    Slog.i(TAG, "Muting lights.");
                    state = new LightState.Builder().setColor(0).build();
                    mSystemOverrideSession =
                            new Session(
                                    null, DEFAULT_SYSTEM_SESSION_PRIORITY, mSessionListener, mH);
                } else {
                    Slog.i(TAG, "Unmuting lights.");
                }

                for (int i = 0; i < mLightsById.size(); i++) {
                    LightImpl light = mLightsById.valueAt(i);
                    if (!light.isSystemLight()) {
                        // All user lights mute/unmute through session priority.
                        mSystemOverrideSession.setRequest(light.mHwLight.id, state);
                    } else if (!light.isCriticalLight()) {
                        // Non critical system lights are muted/unmuted directly.
                        light.setMuteState(muted);
                    }
                }

                if (muted) {
                    mSessions.add(mSystemOverrideSession);
                } else {
                    mSessions.remove(mSystemOverrideSession);
                    mSystemOverrideSession = null;
                }
                Collections.sort(mSessions);
                computeAndApplyLightConfigurationsLocked();
            }
        }

        private void closeSessionInternal(IBinder token) {
            synchronized (LightsService.this) {
                final Session session = getSessionLocked(token);
                if (session != null) {
                    session.clearEffectQueue();
                    mSessions.remove(session);
                    computeAndApplyLightConfigurationsLocked();
                    token.unlinkToDeath(LightsManagerBinderService.this, 0);
                }
            }
        }

        private void checkRequestIsValid(int[] lightIds) {
            for (int lightId : lightIds) {
                final LightImpl light = mLightsById.get(lightId);
                Preconditions.checkState(light != null && !light.isSystemLight(),
                        "Invalid lightId " + lightId);
            }
        }

        /**
         * Computes and applies the light configurations based on session requests and the current
         * light state for each light.
         * <p>
         * Sessions are evaluated based on priority and in case of conflict, the session that
         * started earliest wins.
         */
        private void computeAndApplyLightConfigurationsLocked() {
            if (Flags.enableLightAnimations()) {
                invalidateLightConfigurationsLocked();
            } else {
                invalidateLightStatesLocked();
            }
        }

        /**
         * Apply light state requests for all light IDs.
         * <p>
         * In case of conflict, the session that started earliest wins.
         */
        private void invalidateLightStatesLocked() {
            final Map<Integer, LightState> states = new HashMap<>();
            for (int i = 0; i < mSessions.size(); i++) {
                Session session = mSessions.get(i);
                for (int j = 0; j < session.mConfigurations.size(); j++) {
                    // Add the light state if a higher priority session is not using the light.
                    states.putIfAbsent(
                            session.mConfigurations.keyAt(j),
                            session.mConfigurations.valueAt(j).getState());
                }
            }
            for (int i = 0; i < mLightsById.size(); i++) {
                LightImpl light = mLightsById.valueAt(i);
                if (!light.isSystemLight()) {
                    LightState state = states.get(light.mHwLight.id);
                    if (state != null) {
                        light.setColor(state.getColor());
                    } else {
                        light.turnOff();
                    }
                }
            }
        }

        @SuppressWarnings("AndroidFrameworkEfficientCollections")
        private void invalidateLightConfigurationsLocked() {
            // Traverse the sessions in priority order to find the used lights.
            final Map<Integer, LightConfiguration> configs = new HashMap<>();
            for (Session session : mSessions) {
                Map<Integer, LightConfiguration> effectConfigs = new HashMap<>();
                boolean isEffectPlayable = true;
                for (int i = 0; i < session.mConfigurations.size(); i++) {
                    int lightId = session.mConfigurations.keyAt(i);
                    LightConfiguration nextConfig = session.mConfigurations.valueAt(i);

                    if (nextConfig.isDynamic()) {
                        // Skip the effect if the HAL doesn't have support or a light needed by the
                        // effect is already in use by a higher priority session.
                        if (!mSupportsEffects || configs.containsKey(lightId)) {
                            isEffectPlayable = false;
                            break;
                        }
                        effectConfigs.put(lightId, nextConfig);
                    } else {
                        configs.putIfAbsent(lightId, nextConfig);
                    }
                }

                if (isEffectPlayable) {
                    configs.putAll(effectConfigs);
                }
            }

            // Traverse the list of lights, finding the effects that need to be applied in bulk.
            ArrayList<HwLightEffect> effectsList = new ArrayList<>(mLightsById.size());
            for (int i = 0; i < mLightsById.size(); i++) {
                LightImpl light = mLightsById.valueAt(i);
                if (!light.isSystemLight()) {
                    LightConfiguration config = configs.get(light.mHwLight.id);

                    boolean shouldUpdate = light.setConfiguration(config);
                    if (shouldUpdate) {
                        effectsList.add(
                                vintfEffectFromSequence(light.mHwLight, config.getEffect()));
                    }
                }
            }

            // Finally, send the effects for playback.
            if (!effectsList.isEmpty()) {
                try {
                    mVintfLights.get().setLightEffects(
                            effectsList.toArray(new HwLightEffect[0]));
                } catch (RemoteException re) {
                    Slog.e(TAG, "Exception trying to set the light effect.", re);
                }
            }
        }

        private HwLightEffect vintfEffectFromSequence(HwLight light, MultiLightEffect effect) {
            ColorSequence sequence = null;
            for (int i = 0; i < effect.getLights().length; i++) {
                if (effect.getLights()[i] == light.id) {
                    sequence = effect.getColorSequences()[i];
                    break;
                }
            }

            IntArray frames = new IntArray();
            for (long delay : sequence.getDelaysMillis()) {
                frames.add((int) (delay / light.minUpdatePeriodMillis));
            }

            HwLightEffect hwEffect = new HwLightEffect();
            hwEffect.interpolationType = toHalInterpolationType(sequence.getInterpolationMode());
            hwEffect.framePeriodMillis = light.minUpdatePeriodMillis;
            hwEffect.lightId = light.id;
            hwEffect.frames = frames.toArray();
            hwEffect.colors = sequence.getColors();
            hwEffect.iterations = effect.getIterations();
            hwEffect.preemptive = effect.isPreemptive();

            return hwEffect;
        }

        private @Nullable Session getSessionLocked(IBinder token) {
            for (int i = 0; i < mSessions.size(); i++) {
                if (token.equals(mSessions.get(i).mToken)) {
                    return mSessions.get(i);
                }
            }
            return null;
        }

        private static byte toHalInterpolationType(@InterpolationMode int interpolationMode) {
            return switch (interpolationMode) {
                case ColorSequence.INTERPOLATION_MODE_LINEAR -> InterpolationType.LINEAR;
                case ColorSequence.INTERPOLATION_MODE_NONE -> InterpolationType.NONE;
                default -> InterpolationType.NONE;
            };
        }

        final class Session implements Comparable<Session> {
            public interface EventListener {
                void onEffectPlaybackComplete(Session session, int playbackGeneration);
            }

            private static final int MAX_EFFECT_QUEUE_SIZE = 10;

            final IBinder mToken;
            final SparseArray<LightConfiguration> mConfigurations = new SparseArray<>();
            final Deque<MultiLightEffect> mEffects = new ArrayDeque<>(MAX_EFFECT_QUEUE_SIZE);
            final EventListener mListener;
            final Handler mHandler;

            final int mPriority;
            // Reject completions that were dequeued before playback was canceled or replaced.
            private int mPlaybackGeneration = 0;

            Session(IBinder token, int priority, EventListener listener, Handler handler) {
                mToken = token;
                mPriority = priority;
                mListener = listener;
                mHandler = handler;
            }

            void setRequest(int lightId, LightState state) {
                LightConfiguration previousConfig = mConfigurations.get(lightId);
                // If the light was part of an effect, clear the effect first.
                if (previousConfig != null && previousConfig.isDynamic()) {
                    clearEffectConfiguration(previousConfig.getEffect());
                    clearEffectQueue();
                }

                if (state != null) {
                    mConfigurations.put(lightId, new LightConfiguration(state));
                } else {
                    mConfigurations.remove(lightId);
                }
            }

            void addEffect(MultiLightEffect effect) {
                if (!effect.isPreemptive() && mEffects.size() == MAX_EFFECT_QUEUE_SIZE) {
                    throw new IllegalStateException("Too many effects queued.");
                }

                if (effect.isPreemptive() && !mEffects.isEmpty()) {
                    clearEffectConfiguration(mEffects.getFirst());
                    clearEffectQueue();
                }
                mEffects.add(effect);

                // If the effect should start playback immediately, update the internal state.
                if (mEffects.size() == 1) {
                    applyEffectConfiguration(mEffects.getFirst());
                }
            }

            boolean transitionToNextEffect(int playbackGeneration) {
                if (mEffects.isEmpty() || playbackGeneration != mPlaybackGeneration) {
                    return false;
                }
                // Remove the effect that just ended playback and clear the state.
                clearEffectConfiguration(mEffects.pop());

                MultiLightEffect nextEffect = mEffects.peek();
                if (nextEffect != null) {
                    applyEffectConfiguration(nextEffect);
                }
                return true;
            }

            private void applyEffectConfiguration(MultiLightEffect effect) {
                LightConfiguration newConfig = new LightConfiguration(effect);
                for (int lightId : effect.getLights()) {
                    mConfigurations.put(lightId, newConfig);
                }

                final int generation = ++mPlaybackGeneration;
                // If the effect is not infinite, schedule the transition.
                if (effect.getIterations() > 0) {
                    mHandler.postDelayed(
                            () -> {
                                mListener.onEffectPlaybackComplete(this, generation);
                            },
                            /* token= */this,
                            effect.getTotalDurationMillis());
                }
            }

            private void clearEffectQueue() {
                mPlaybackGeneration++;
                mHandler.removeCallbacksAndMessages(Session.this);
                mEffects.clear();
            }

            private void clearEffectConfiguration(MultiLightEffect effect) {
                for (int lightId : effect.getLights()) {
                    mConfigurations.remove(lightId);
                }
            }

            @Override
            public int compareTo(Session otherSession) {
                // Sort descending by priority
                return Integer.compare(otherSession.mPriority, mPriority);
            }
        }
    }

    private final class LightImpl extends LogicalLight {

        private LightImpl(Context context, HwLight hwLight) {
            mHwLight = hwLight;
        }

        /**
         * Set the light configuration for this particular logical light.
         * <p>
         * A configuration update usually requires changes to the hardware light but some of the
         * updates are handled automatically by the configuration change to maintain pre-existing
         * behavior.
         * <p>
         * If the configuration update is handled automatically this method returns false to
         * indicate that the configuration doesn't require the service to do further work. When the
         * update just changes the internal state, this method returns true to require the service
         * to issue the update.
         *
         * @param configuration A configuration object with the desired state for this light.
         * @return true if the light state needs to be updated by the service. False otherwise.
         */
        boolean setConfiguration(LightConfiguration configuration) {
            LightConfiguration previousConfig = mConfiguration;
            mConfiguration = configuration;

            if (mConfiguration == null) {
                turnOff();
                return false;
            }

            if (!mConfiguration.isDynamic()) {
                setColor(mConfiguration.getState().getColor());
                return false;
            }

            // Reset the static color to Black to clear state when we have an effect. This has to be
            // different than '0' to allow the end of the session to send a "turnOff" request.
            mColor = Color.BLACK;

            // If the configuration is different from the last configuration the effect needs to be
            // sent down to the HAL, so return true to let the service know it needs to do it.
            return !mConfiguration.equals(previousConfig);
        }

        @Override
        public void setBrightness(float brightness) {
            setBrightness(brightness, BRIGHTNESS_MODE_USER);
        }

        @Override
        public void setBrightness(float brightness, int brightnessMode) {
            if (Float.isNaN(brightness)) {
                Slog.w(TAG, "Brightness is not valid: " + brightness);
                return;
            }
            synchronized (this) {
                // LOW_PERSISTENCE cannot be manually set
                if (brightnessMode == BRIGHTNESS_MODE_LOW_PERSISTENCE) {
                    Slog.w(TAG, "setBrightness with LOW_PERSISTENCE unexpected #" + mHwLight.id
                            + ": brightness=" + brightness);
                    return;
                }
                int brightnessInt = BrightnessSynchronizer.brightnessFloatToInt(brightness);
                int color = brightnessInt & 0x000000ff;
                color = 0xff000000 | (color << 16) | (color << 8) | color;
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, brightnessMode);
            }
        }

        @Override
        public void setColor(int color) {
            synchronized (this) {
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        @Override
        public void setFlashing(int color, int mode, int onMS, int offMS) {
            synchronized (this) {
                setLightLocked(color, mode, onMS, offMS, BRIGHTNESS_MODE_USER);
            }
        }

        @Override
        public void pulse() {
            pulse(0x00ffffff, 7);
        }

        @Override
        public void pulse(int color, int onMS) {
            synchronized (this) {
                if (mColor == 0 && !mFlashing) {
                    setLightLocked(color, LIGHT_FLASH_HARDWARE, onMS, 1000,
                            BRIGHTNESS_MODE_USER);
                    mColor = 0;
                    mH.postDelayed(this::stopFlashing, onMS);
                }
            }
        }

        @Override
        public void turnOff() {
            synchronized (this) {
                setLightLocked(0, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        @Override
        public void setVrMode(boolean enabled) {
            synchronized (this) {
                if (mVrModeEnabled != enabled) {
                    mVrModeEnabled = enabled;

                    mUseLowPersistenceForVR =
                            (getVrDisplayMode() == Settings.Secure.VR_DISPLAY_MODE_LOW_PERSISTENCE);
                    if (shouldBeInLowPersistenceMode()) {
                        mLastBrightnessMode = mBrightnessMode;
                    }

                    // NOTE: We do not trigger a call to setLightLocked here.  We do not know the
                    // current brightness or other values when leaving VR so we avoid any incorrect
                    // jumps. The code that calls this method will immediately issue a brightness
                    // update which is when the change will occur.
                }
            }
        }

        private void stopFlashing() {
            synchronized (this) {
                setLightLocked(mColor, LIGHT_FLASH_NONE, 0, 0, BRIGHTNESS_MODE_USER);
            }
        }

        private void setLightLocked(int color, int mode, int onMS, int offMS, int brightnessMode) {
            if (shouldBeInLowPersistenceMode()) {
                brightnessMode = BRIGHTNESS_MODE_LOW_PERSISTENCE;
            } else if (brightnessMode == BRIGHTNESS_MODE_LOW_PERSISTENCE) {
                brightnessMode = mLastBrightnessMode;
            }

            if (!mInitialized || color != mColor || mode != mMode || onMS != mOnMS ||
                    offMS != mOffMS || mBrightnessMode != brightnessMode) {
                if (DEBUG) {
                    Slog.v(TAG, "setLight #" + mHwLight.id + ": color=#"
                            + Integer.toHexString(color) + ": brightnessMode=" + brightnessMode);
                }
                mInitialized = true;
                mLastColor = mColor;
                mColor = color;
                mMode = mode;
                mOnMS = onMS;
                mOffMS = offMS;
                mBrightnessMode = brightnessMode;

                // Only send the new configuration if the light is not muted.
                if (!mMuted) {
                    setLightUnchecked(color, mode, onMS, offMS, brightnessMode);
                }
            }
        }

        private void setLightUnchecked(int color, int mode, int onMS, int offMS,
                int brightnessMode) {
            Trace.traceBegin(Trace.TRACE_TAG_POWER, "setLightState(" + mHwLight.id + ", 0x"
                    + Integer.toHexString(color) + ")");
            try {
                if (mVintfLights != null) {
                    HwLightState lightState = new HwLightState();
                    lightState.color = color;
                    lightState.flashMode = (byte) mode;
                    lightState.flashOnMs = onMS;
                    lightState.flashOffMs = offMS;
                    lightState.brightnessMode = (byte) brightnessMode;
                    mVintfLights.get().setLightState(mHwLight.id, lightState);
                } else {
                    Slog.e(TAG, "Failed issuing setLightState, no ILights HAL service available");
                }
            } catch (RemoteException | UnsupportedOperationException ex) {
                Slog.e(TAG, "Failed issuing setLightState", ex);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_POWER);
            }
        }

        private boolean shouldBeInLowPersistenceMode() {
            return mVrModeEnabled && mUseLowPersistenceForVR;
        }

        /**
         * Returns whether a light is system-use-only or should be accessible to
         * applications using the {@link android.hardware.lights.LightsManager} API.
         */
        private boolean isSystemLight() {
            // This list of lights is based on the list of lights from the HAL, which only
            // contains system lights.
            //
            // Newly-added public lights are made available via the public LightsManager API and
            // new system-only lights are filtered here, through constants from the latest HAL API.
            switch(mHwLight.type) {
                case  LightType.BACKLIGHT:
                case  LightType.KEYBOARD:
                case  LightType.BUTTONS:
                case  LightType.BATTERY:
                case  LightType.NOTIFICATIONS:
                case  LightType.ATTENTION:
                case  LightType.BLUETOOTH:
                case  LightType.WIFI:
                case LightType.PRIORITY_NOTIFICATIONS:
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Returns whether the light provides a critical functionality and should not be muted.
         * <p>
         * Most lights are non-critical and can be muted if the system requires them to temporarily
         * turn off during light-sensitive use cases (i.e.: camera taking photos / video), but some
         * lights such as backlight and keyboard provide critical functionality and should not be
         * turned off.
         */
        private boolean isCriticalLight() {
            switch(mHwLight.type) {
                case  LightType.BACKLIGHT:
                case  LightType.KEYBOARD:
                case  LightType.BUTTONS:
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Mutes or unmutes this light.
         * <p>
         * When a light is muted, it is turned off immediatelly and further changes, while
         * reflected in the light state, won't be propagated to the HAL.
         * <p>
         * When unmuted, its last state before muting is restored.
         *
         * @param muted true to mute the light, false to unmute.
         */
        private void setMuteState(boolean muted) {
            synchronized (this) {
                mMuted = muted;
                if (mMuted) {
                    setLightUnchecked(0, LIGHT_FLASH_NONE, 0, 0, 0);
                } else {
                    // The light had a permanent state that needs to be restored.
                    setLightUnchecked(mColor, mMode, mOnMS, mOffMS, mBrightnessMode);
                }
            }
        }

        private int getColor() {
            return mColor;
        }

        private HwLight mHwLight;
        private LightConfiguration mConfiguration;
        private int mColor;
        private int mMode;
        private int mOnMS;
        private int mOffMS;
        private boolean mFlashing;
        private int mBrightnessMode;
        private int mLastBrightnessMode;
        private int mLastColor;
        private boolean mVrModeEnabled;
        private boolean mUseLowPersistenceForVR;
        private boolean mInitialized;
        private boolean mMuted;
    }

    public LightsService(Context context) {
        this(context, new VintfHalCache(), Looper.myLooper());
    }

    @VisibleForTesting
    LightsService(Context context, Supplier<ILights> service, Looper looper) {
        super(context);
        mH = new Handler(looper);
        mVintfLights = service.get() != null ? service : null;

        populateAvailableLights(context);
        mManagerService = new LightsManagerBinderService();
    }

    private void populateAvailableLights(Context context) {
        if (mVintfLights != null) {
            populateAvailableLightsFromHal(context);
        } else {
            Slog.e(TAG, "Failed to populate available lights, no ILights HAL service available");
        }

        for (int i = mLightsById.size() - 1; i >= 0; i--) {
            LightImpl light = mLightsById.valueAt(i);
            if (light.isSystemLight()) {
                mLightsByType.append(light.mHwLight.type, light);
            }
        }
    }

    private void populateAvailableLightsFromHal(Context context) {
        try {
            mSupportsEffects =
                Flags.enableLightAnimations() && mVintfLights.get().getInterfaceVersion() >= 3;

            for (HwLight hwLight : mVintfLights.get().getLights()) {
                mLightsById.put(hwLight.id, new LightImpl(context, hwLight));
            }
        } catch (RemoteException ex) {
            Slog.e(TAG, "Unable to get lights from HAL", ex);
        }
    }

    @Override
    public void onStart() {
        publishLocalService(LightsManager.class, mService);
        publishBinderService(Context.LIGHTS_SERVICE, mManagerService);
    }

    @Override
    public void onBootPhase(int phase) {
    }

    private int getVrDisplayMode() {
        int currentUser = ActivityManager.getCurrentUser();
        return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                Settings.Secure.VR_DISPLAY_MODE,
                /*default*/Settings.Secure.VR_DISPLAY_MODE_LOW_PERSISTENCE,
                currentUser);
    }

    private final LightsManager mService = new LightsManager() {
        @Override
        public LogicalLight getLight(int lightType) {
            return mLightsByType.get(lightType);
        }

        @Override
        public void setMutedState(boolean muted) {
            LightsService.this.setMutedState(muted);
        }
    };

    @VisibleForTesting
    void setMutedState(boolean muted) {
        mManagerService.setMutedState(muted);
    }

    private static class VintfHalCache implements Supplier<ILights>, IBinder.DeathRecipient {
        @GuardedBy("this")
        private ILights mInstance = null;

        @Override
        public synchronized ILights get() {
            if (mInstance == null) {
                IBinder binder = Binder.allowBlocking(
                        ServiceManager.waitForDeclaredService(ILights.DESCRIPTOR + "/default"));
                if (binder != null) {
                    mInstance = ILights.Stub.asInterface(binder);
                    try {
                        binder.linkToDeath(this, 0);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to register DeathRecipient for " + mInstance);
                    }
                }
            }
            return mInstance;
        }

        @Override
        public synchronized void binderDied() {
            mInstance = null;
        }
    }
}
