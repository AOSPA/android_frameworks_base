/*
 * Copyright (C) 2017 The Android AAC vibration extension
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

package android.os;

import android.annotation.NonNull;
import android.util.Log;

/**
 * Implementation of a pattern HE parameter vibration effect.
 *
 * @hide
 */
public final class PatternHeParameter extends VibrationEffect implements Parcelable {
    public static final @NonNull Parcelable.Creator<PatternHeParameter> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public PatternHeParameter createFromParcel(@NonNull Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new PatternHeParameter(in);
                }

                @Override
                public @NonNull PatternHeParameter[] newArray(int size) {
                    return new PatternHeParameter[size];
                }
            };
    private static final String TAG = "PatternHeParameter";
    private final int mInterval;
    private final int mAmplitude;
    private final int mFreq;

    /**
     * Creates a PatternHeParameter effect from a Parcel.
     *
     * @param in The Parcel to read from
     * @hide
     */
    public PatternHeParameter(@NonNull Parcel in) {
        mInterval = in.readInt();
        mAmplitude = in.readInt();
        mFreq = in.readInt();
        Log.d(TAG, "parcel mInterval:" + mInterval + " mAmplitude:" + mAmplitude +
                " mFreq:" + mFreq);
    }

    /**
     * Creates a PatternHeParameter effect with the specified parameters.
     *
     * @param interval  The interval value
     * @param amplitude The amplitude value
     * @param freq      The frequency value
     */
    public PatternHeParameter(int interval, int amplitude, int freq) {
        mInterval = interval;
        mAmplitude = amplitude;
        mFreq = freq;
        Log.d(TAG, "mInterval:" + mInterval + " mAmplitude:" + mAmplitude +
                " mFreq:" + mFreq);
    }

    /**
     * Gets the interval value.
     *
     * @return The interval value
     */
    public int getInterval() {
        return mInterval;
    }

    /**
     * Gets the amplitude value.
     *
     * @return The amplitude value
     */
    public int getAmplitude() {
        return mAmplitude;
    }

    /**
     * Gets the frequency value.
     *
     * @return The frequency value
     */
    public int getFreq() {
        return mFreq;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Resolves the effect with the specified default amplitude.
     *
     * @param defaultAmplitude The default amplitude
     * @return This effect instance
     */
    public PatternHeParameter resolve(int defaultAmplitude) {
        return this;
    }

    /**
     * Scales the effect by the specified factor.
     *
     * @param scaleFactor The scale factor
     * @return This effect instance
     */
    public PatternHeParameter scale(float scaleFactor) {
        return this;
    }

    @Override
    public long getDuration() {
        return -1;
    }

    @Override
    public void validate() {
        if (mAmplitude < -1 || mAmplitude > 255 || mInterval < -1 || mFreq < -1) {
            throw new IllegalArgumentException(
                    "mAmplitude=" + mAmplitude + " mInterval=" + mInterval +
                            " mFreq=" + mFreq);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PatternHeParameter other)) {
            return false;
        }
        int interval = other.getInterval();
        int amplitude = other.getAmplitude();
        int freq = other.getFreq();

        return interval == this.mInterval && amplitude == this.mAmplitude &&
                freq == this.mFreq;
    }

    @Override
    public int hashCode() {
        int result = 14;
        result += 37 * mInterval;
        result += 37 * mAmplitude;
        return result;
    }

    @Override
    public String toString() {
        return "PatternHeParameter: {mAmplitude=" + this.mAmplitude +
                ", mInterval=" + this.mInterval +
                ", mFreq=" + this.mFreq + "}";
    }

    @Override
    public String toDebugString() {
        return null;
    }

    @Override
    public long[] computeCreateWaveformOffOnTimingsOrNull() {
        return new long[0];
    }

    @Override
    public VibrationEffect applyRepeatingIndefinitely(boolean wantRepeating, int loopDelayMs) {
        return null;
    }

    @Override
    public VibrationEffect cropToLengthOrNull(int length) {
        return null;
    }

    @Override
    public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) {
        return false;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(RichTapVibrationEffect.PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER);
        out.writeInt(mInterval);
        out.writeInt(mAmplitude);
        out.writeInt(mFreq);
        Log.d(TAG, "writeToParcel mInterval:" + mInterval +
                " mAmplitude:" + mAmplitude + " mFreq:" + mFreq);
    }

    @Override
    public VibrationEffect applyAdaptiveScale(float scaleFactor) {
        return null;
    }

    @Override
    public VibrationEffect applyEffectStrength(int effectStrength) {
        return null;
    }
}
