/*
 * Copyright (C) 2024-2025 Paranoid Android
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.os.Binder;
import android.os.CombinedVibration;
import android.os.Envelope;
import android.os.ExtPrebaked;
import android.os.HapticParameter;
import android.os.HapticPlayer;
import android.os.IBinder;
import android.os.PatternHe;
import android.os.PatternHeParameter;
import android.os.ServiceManager;
import android.os.VibrationEffect;
import android.util.Slog;

import java.util.Locale;

import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;
import vendor.aac.hardware.richtap.vibrator.IRichtapVibrator;

/**
 * Service class managing RichTap vibrator functionality.
 */
public class RichTapVibratorService {
    public static final String ACTION_CHANGE_MODE = "richtap_change_mode";
    public static final String EXTRA_MODE = "mode";
    private static final String TAG = RichTapVibratorService.class.getSimpleName();
    private static final String VIBRATOR_DESCRIPTOR = IVibrator.DESCRIPTOR + "/default";
    private static final boolean DEBUG = false;
    private final IRichtapCallback mCallback;
    private volatile IRichtapVibrator mRichTapVibratorService = null;

    private interface RichTapOperation {
        void execute(@NonNull IRichtapVibrator service) throws Exception;
    }

    private interface RichTapIntOperation {
        int execute(@NonNull IRichtapVibrator service) throws Exception;
    }

    public RichTapVibratorService() {
        this(null);
    }

    public RichTapVibratorService(@Nullable IRichtapCallback callback) {
        mCallback = callback;
    }

    @Nullable
    private synchronized IRichtapVibrator getRichTapService() {
        if (mRichTapVibratorService == null) {
            if (DEBUG) Slog.d(TAG, "vibratorDescriptor: " + VIBRATOR_DESCRIPTOR);

            IVibrator vibratorHalService = IVibrator.Stub.asInterface(
                    ServiceManager.getService(VIBRATOR_DESCRIPTOR));

            if (vibratorHalService == null) {
                Slog.w(TAG, "Failed to get HAL service");
                return null;
            }

            if (DEBUG) {
                try {
                    Slog.d(TAG, "Capabilities: " + vibratorHalService.getCapabilities());
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to get capabilities", e);
                }
            }

            try {
                IBinder binder = vibratorHalService.asBinder().getExtension();
                if (binder != null) {
                    mRichTapVibratorService = IRichtapVibrator.Stub.asInterface(
                            Binder.allowBlocking(binder));
                    binder.linkToDeath(new RichTapHalDeathRecipient(this), 0);
                } else {
                    Slog.e(TAG, "Extension binder is null");
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get extension", e);
            }
        }
        return mRichTapVibratorService;
    }

    private void runOnRichTapService(String errorMessage, RichTapOperation operation) {
        try {
            IRichtapVibrator service = getRichTapService();
            if (service != null) {
                operation.execute(service);
            }
        } catch (Exception e) {
            Slog.e(TAG, errorMessage, e);
        }
    }

    private int callRichTapService(String errorMessage, RichTapIntOperation operation) {
        try {
            IRichtapVibrator service = getRichTapService();
            if (service != null) {
                return operation.execute(service);
            }
        } catch (Exception e) {
            Slog.e(TAG, errorMessage, e);
        }
        return 0;
    }

    /**
     * Starts the vibrator for specified duration.
     *
     * @param millis The duration in milliseconds
     */
    public void richTapVibratorOn(long millis) {
        runOnRichTapService("Failed to execute vibratorOn", service -> {
            if (DEBUG) Slog.d(TAG, "Executing vibratorOn");
            service.on((int) millis, mCallback);
        });
    }

    /**
     * Sets the vibration amplitude.
     *
     * @param amplitude The amplitude value
     */
    public void richTapVibratorSetAmplitude(int amplitude) {
        runOnRichTapService("Failed to set amplitude", service -> {
            if (DEBUG) Slog.d(TAG, "Setting amplitude: " + amplitude);
            service.setAmplitude(amplitude, mCallback);
        });
    }

    /**
     * Executes a raw pattern HE effect.
     *
     * @param pattern   The pattern array
     * @param amplitude The amplitude value
     * @param freq      The frequency value
     */
    public void richTapVibratorOnRawPattern(@NonNull int[] pattern, int amplitude, int freq) {
        runOnRichTapService("Failed to execute raw pattern", service -> {
            if (DEBUG) Slog.d(TAG, "Executing raw pattern with amplitude: " +
                    amplitude + ", freq: " + freq);
            service.performHe(1, 0, amplitude, freq, pattern, mCallback);
        });
    }

    /**
     * Executes a pattern HE effect.
     *
     * @param effect The PatternHe vibration effect
     */
    public void richTapVibratorOnPatternHe(VibrationEffect effect) {
        PatternHe patternHe = (PatternHe) effect;
        int[] pattern = patternHe.getPatternInfo();
        int looper = patternHe.getLooper();
        int interval = patternHe.getInterval();
        int amplitude = patternHe.getAmplitude();
        int freq = patternHe.getFreq();

        runOnRichTapService("Failed to execute pattern HE effect", service -> {
            if (DEBUG) Slog.d(TAG, "Executing pattern HE effect");
            service.performHe(looper, interval, amplitude, freq, pattern, mCallback);
        });
    }

    /**
     * Executes an envelope vibration effect.
     *
     * @param relativeTime The relative time array
     * @param scaleArr     The scale array
     * @param freqArr      The frequency array
     * @param steepMode    Whether steep mode is enabled
     * @param amplitude    The amplitude value
     */
    public void richTapVibratorOnEnvelope(int[] relativeTime, int[] scaleArr, int[] freqArr,
            boolean steepMode, int amplitude) {
        int[] params = new int[12];
        for (int i = 0; i < relativeTime.length; i++) {
            params[i * 3] = relativeTime[i];
            params[i * 3 + 1] = scaleArr[i];
            params[i * 3 + 2] = freqArr[i];
            if (DEBUG) {
                Slog.d(TAG, String.format(Locale.US, "relativeTime, scale, freq = { %d, %d, %d }",
                        params[i * 3], params[i * 3 + 1], params[i * 3 + 2]));
            }
        }

        richTapVibratorSetAmplitude(amplitude);

        runOnRichTapService("Failed to execute envelope effect", service -> {
            if (DEBUG) Slog.d(TAG, "Executing envelope effect");
            service.performEnvelope(params, steepMode, mCallback);
        });
    }

    /**
     * Stops all vibrations.
     */
    public void richTapVibratorStop() {
        runOnRichTapService("Failed to stop vibrator", service -> {
            if (DEBUG) Slog.d(TAG, "Stopping vibrator");
            service.stop(mCallback);
        });
    }

    /**
     * Sets haptic parameters.
     *
     * @param data   The parameter data
     * @param length The length of the data
     */
    private void setHapticParam(int[] data, int length) {
        runOnRichTapService("Failed to set haptic parameters", service -> {
            if (DEBUG) Slog.d(TAG, "Setting haptic parameters, length: " + length);
            service.setHapticParam(data, length, mCallback);
        });
    }

    /**
     * Sets the vibration mode.
     *
     * @param mode The vibration mode
     */
    public void richTapSetVibrationMode(int mode) {
        if (DEBUG) Slog.i(TAG, "Setting vibration mode: " + mode);

        // Stop all vibrations first
        richTapVibratorStop();

        int[] param = new int[]{
                HapticParamType.HAPTIC_DRC.getValue(),
                mode
        };
        setHapticParam(param, param.length);
    }

    /**
     * Performs a predefined effect with the specified ID and scale.
     *
     * @param id    The effect ID
     * @param scale The scale value
     * @return The command id returned by the HAL
     */
    public int richTapVibratorPerform(int id, byte scale) {
        return callRichTapService("Failed to perform effect", service -> {
            if (DEBUG) Slog.d(TAG, "Performing predefined effect");
            int cmdId = service.perform(id, scale, mCallback);
            if (DEBUG) Slog.d(TAG, "Effect command id: " + cmdId);
            return cmdId;
        });
    }

    /**
     * Processes RichTap effect parameters.
     *
     * @param combEffect The combined vibration effect
     * @return true if the effect was processed, false otherwise
     */
    public boolean playRichTapParameterEffect(CombinedVibration combEffect) {
        if (!(combEffect instanceof CombinedVibration.Mono)) {
            return false;
        }

        VibrationEffect effect = ((CombinedVibration.Mono) combEffect).getEffect();

        if (effect instanceof PatternHeParameter param) {
            int interval = param.getInterval();
            int amplitude = param.getAmplitude();
            int freq = param.getFreq();

            if (DEBUG) {
                Slog.d(TAG, "Processing PatternHeParameter - interval: " + interval +
                        ", amplitude: " + amplitude + ", freq: " + freq);
            }

            runOnRichTapService("Failed to process PatternHeParameter", service ->
                    service.performHeParam(interval, amplitude, freq, mCallback));
            return true;
        } else if (effect instanceof HapticParameter parameter) {
            int[] param = parameter.getParam();
            int length = parameter.getLength();

            if (DEBUG) {
                Slog.d(TAG, "Processing HapticParameter: " + parameter);
            }

            setHapticParam(param, length);
            return true;
        }

        if (DEBUG) Slog.d(TAG, "Not a RichTap effect, no action taken");
        return false;
    }

    /**
     * Checks if the vibration effect is a RichTap effect.
     *
     * @param effect The vibration effect
     * @param reason The vibration reason
     * @return true if it's a RichTap effect, false otherwise
     */
    public boolean checkIfRichTapEffect(VibrationEffect effect, String reason) {
        if (reason != null && reason.equals(HapticPlayer.VIBRATE_REASON)) {
            return false;
        }

        return (effect instanceof PatternHeParameter
                || effect instanceof PatternHe
                || effect instanceof Envelope
                || effect instanceof HapticParameter
                || effect instanceof ExtPrebaked);
    }

    /**
     * Resets the HAL service proxy.
     */
    void resetHalServiceProxy() {
        synchronized (this) {
            mRichTapVibratorService = null;
        }
    }

    /**
     * Haptic parameter types enum.
     */
    public enum HapticParamType {
        HAPTIC_DRC(0x01);

        private final int type;

        HapticParamType(int type) {
            this.type = type;
        }

        public int getValue() {
            return type;
        }
    }

    /**
     * Death recipient for vibrator HAL service.
     */
    private static final class RichTapHalDeathRecipient implements IBinder.DeathRecipient {
        private final RichTapVibratorService mRichTapService;

        RichTapHalDeathRecipient(@NonNull RichTapVibratorService richTapService) {
            mRichTapService = richTapService;
        }

        @Override
        public void binderDied() {
            Slog.w(TAG, "Vibrator HAL died, resetting proxy");
            synchronized (mRichTapService) {
                mRichTapService.resetHalServiceProxy();
            }
        }
    }
}
