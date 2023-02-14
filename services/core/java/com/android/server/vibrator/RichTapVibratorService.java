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
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;

import vendor.aac.hardware.richtap.vibrator.IRichtapCallback;
import vendor.aac.hardware.richtap.vibrator.IRichtapVibrator;

/**
 * Service class managing RichTap vibrator functionality.
 */
public class RichTapVibratorService {
    private static final String TAG = RichTapVibratorService.class.getSimpleName();
    private static final String VIBRATOR_DESCRIPTOR = IVibrator.DESCRIPTOR + "/default";
    private static final boolean DEBUG = false;

    private final IRichtapCallback mCallback;
    private volatile IRichtapVibrator sRichtapVibratorService = null;

    @Nullable
    private synchronized IRichtapVibrator getRichtapService() {
        if (sRichtapVibratorService == null) {
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
                    sRichtapVibratorService = IRichtapVibrator.Stub.asInterface(
                            Binder.allowBlocking(binder));
                    binder.linkToDeath(new VibHalDeathRecipient(this), 0);
                } else {
                    Slog.e(TAG, "Extension binder is null");
                }
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get extension", e);
            }
        }
        return sRichtapVibratorService;
    }

    public RichTapVibratorService() {
        this(null);
    }

    public RichTapVibratorService(@Nullable IRichtapCallback callback) {
        mCallback = callback;
    }

    public void richTapVibratorOn(long millis) {
        try {
            IRichtapVibrator service = getRichtapService();
            if (service != null) {
                if (DEBUG) Slog.d(TAG, "Executing vibratorOn");
                service.on((int) millis, mCallback);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to execute vibratorOn", e);
        }
    }

    public void richTapVibratorSetAmplitude(int amplitude) {
        try {
            IRichtapVibrator service = getRichtapService();
            if (service != null) {
                if (DEBUG) Slog.d(TAG, "Setting amplitude: " + amplitude);
                service.setAmplitude(amplitude, mCallback);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to set amplitude", e);
        }
    }

    public void richTapVibratorOnRawPattern(@NonNull int[] pattern, int amplitude, int freq) {
        try {
            IRichtapVibrator service = getRichtapService();
            if (service != null) {
                if (DEBUG) Slog.d(TAG, "Executing raw pattern with amplitude: " +
                        amplitude + ", freq: " + freq);
                service.performHe(1, 0, amplitude, freq, pattern, mCallback);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to execute raw pattern", e);
        }
    }

    void resetHalServiceProxy() {
        synchronized (this) {
            sRichtapVibratorService = null;
        }
    }

    private static final class VibHalDeathRecipient implements IBinder.DeathRecipient {
        private final RichTapVibratorService mRichTapService;

        VibHalDeathRecipient(@NonNull RichTapVibratorService richtapService) {
            mRichTapService = richtapService;
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
