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

/**
 * Implementation of an extended pre-baked vibration effect.
 *
 * @hide
 */
public final class ExtPrebaked extends VibrationEffect implements Parcelable {
    public static final @NonNull Parcelable.Creator<ExtPrebaked> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public ExtPrebaked createFromParcel(@NonNull Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new ExtPrebaked(in);
                }

                @Override
                public ExtPrebaked[] newArray(int size) {
                    return new ExtPrebaked[size];
                }
            };
    private final int mEffectId;
    private final int mStrength;

    /**
     * Creates an ExtPrebaked effect from a Parcel.
     *
     * @param in The Parcel to read from
     * @hide
     */
    public ExtPrebaked(@NonNull Parcel in) {
        this(in.readInt(), in.readInt());
    }

    /**
     * Creates an ExtPrebaked effect with the specified ID and strength.
     *
     * @param effectId The effect ID
     * @param strength The strength value (1-100)
     */
    public ExtPrebaked(int effectId, int strength) {
        mEffectId = effectId;
        mStrength = strength;
    }

    /**
     * Gets the effect ID.
     *
     * @return The effect ID
     */
    public int getId() {
        return mEffectId;
    }

    /**
     * Gets the strength scale.
     *
     * @return The strength value
     */
    public int getScale() {
        return mStrength;
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
    public ExtPrebaked resolve(int defaultAmplitude) {
        return this;
    }

    /**
     * Scales the effect by the specified factor.
     *
     * @param scaleFactor The scale factor
     * @return This effect instance
     */
    public ExtPrebaked scale(float scaleFactor) {
        return this;
    }

    @Override
    public long getDuration() {
        return -1;
    }

    @Override
    public void validate() {
        if (mEffectId < 0) {
            throw new IllegalArgumentException(
                    "Unknown ExtPrebaked effect type (value=" + mEffectId + ")");
        }

        if (mStrength < 1 || mStrength > 100) {
            throw new IllegalArgumentException(
                    "mStrength must be between 1 and 100 inclusive (mStrength=" + mStrength + ")");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ExtPrebaked other)) {
            return false;
        }
        return mEffectId == other.mEffectId;
    }

    @Override
    public int hashCode() {
        return mEffectId;
    }

    @Override
    public String toString() {
        return "ExtPrebaked{mEffectId=" + mEffectId + ", mStrength=" + mStrength + "}";
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
        out.writeInt(RichTapVibrationEffect.PARCEL_TOKEN_EXT_PREBAKED);
        out.writeInt(mEffectId);
        out.writeInt(mStrength);
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
