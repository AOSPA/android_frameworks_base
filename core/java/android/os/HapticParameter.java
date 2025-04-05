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

import java.util.Arrays;
import java.util.Objects;

/**
 * Implementation of a haptic parameter vibration effect.
 *
 * @hide
 */
public final class HapticParameter extends VibrationEffect implements Parcelable {
    public static final @NonNull Parcelable.Creator<HapticParameter> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public HapticParameter createFromParcel(@NonNull Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new HapticParameter(in);
                }

                @Override
                public @NonNull HapticParameter[] newArray(int size) {
                    return new HapticParameter[size];
                }
            };
    private static final String TAG = "HapticParameter";
    private final int[] mParam;
    private final int mLength;

    /**
     * Creates a HapticParameter effect from a Parcel.
     *
     * @param in The Parcel to read from
     * @hide
     */
    public HapticParameter(@NonNull Parcel in) {
        mParam = in.createIntArray();
        mLength = in.readInt();
        Log.d(TAG, "parcel mLength:" + mLength);
    }

    /**
     * Creates a HapticParameter effect with the specified parameters.
     *
     * @param param  The parameter array
     * @param length The length value
     */
    public HapticParameter(int[] param, int length) {
        mParam = param;
        mLength = length;
        Log.d(TAG, "created with mLength:" + mLength);
    }

    /**
     * Gets the parameter array.
     *
     * @return The parameter array
     */
    public int[] getParam() {
        return mParam;
    }

    /**
     * Gets the length value.
     *
     * @return The length value
     */
    public int getLength() {
        return mLength;
    }

    /**
     * Resolves the effect with the specified default amplitude.
     *
     * @param defaultAmplitude The default amplitude
     * @return This effect instance
     */
    public HapticParameter resolve(int defaultAmplitude) {
        return this;
    }

    /**
     * Scales the effect by the specified factor.
     *
     * @param scaleFactor The scale factor
     * @return This effect instance
     */
    public HapticParameter scale(float scaleFactor) {
        return this;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public long getDuration() {
        return -1;
    }

    @Override
    public void validate() {
        if (null == mParam || 0 == mParam.length || mLength != mParam.length) {
            throw new IllegalArgumentException("empty param or length mismatch");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HapticParameter other)) {
            return false;
        }
        if (this.getLength() != other.getLength()) {
            return false;
        }
        return Arrays.equals(this.getParam(), other.getParam());
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(mParam), mLength);
    }

    @Override
    public String toString() {
        return "HapticParameter: {mLength=" + this.mLength +
                ", mParam=" + Arrays.toString(mParam) + "}";
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
        out.writeInt(RichTapVibrationEffect.PARCEL_TOKEN_HAPTIC_PARAMETER);
        out.writeIntArray(mParam);
        out.writeInt(mLength);
        Log.d(TAG, "writeToParcel, mLength:" + mLength);
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
