/*
 * Copyright (C) 2021 The Android AAC vibration extension
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
import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

/**
 * A DynamicEffect describes a haptic effect to be performed by a {@link Vibrator}.
 * <p>
 * This class allows creating vibration effects from JSON patterns.
 */
public final class DynamicEffect extends VibrationEffect implements Parcelable {
    public static final boolean DEBUG = true;
    public static final @NonNull Parcelable.Creator<DynamicEffect> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public DynamicEffect createFromParcel(@NonNull Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new DynamicEffect(in);
                }

                @Override
                public @NonNull DynamicEffect[] newArray(int size) {
                    return new DynamicEffect[size];
                }
            };
    private static final String TAG = "DynamicEffect";
    private static final int PARCEL_TOKEN_DYNAMIC_EFFECT = 100;
    private final String mPatternJson;

    /**
     * Creates a DynamicEffect from a Parcel.
     *
     * @param in The Parcel to read from
     * @hide
     */
    public DynamicEffect(@NonNull Parcel in) {
        // The current implementation doesn't read anything from the parcel
        // This constructor is kept for compatibility
        mPatternJson = "";
    }

    /**
     * Creates a DynamicEffect with the specified JSON pattern.
     *
     * @param patternJson The JSON pattern string
     */
    public DynamicEffect(@NonNull String patternJson) {
        mPatternJson = patternJson;
    }

    /**
     * Creates a DynamicEffect from a JSON string.
     *
     * @param json The JSON string to create the effect from
     * @return A DynamicEffect instance, or null if the JSON is empty
     */
    @Nullable
    public static DynamicEffect create(@Nullable String json) {
        if (TextUtils.isEmpty(json)) {
            Log.e(TAG, "empty pattern, do nothing");
            return null;
        }

        return new DynamicEffect(json);
    }

    @Override
    public int describeContents() {
        return 0;
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

    /**
     * @hide
     */
    @Override
    public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) {
        return false;
    }

    /**
     * Resolves the effect with the specified default amplitude.
     *
     * @param defaultAmplitude The default amplitude
     * @return This effect instance
     * @hide
     */
    public DynamicEffect resolve(int defaultAmplitude) {
        return this;
    }

    /**
     * Scales the effect by the specified factor.
     *
     * @param scaleFactor The scale factor
     * @return This effect instance
     * @hide
     */
    public DynamicEffect scale(float scaleFactor) {
        return this;
    }

    /**
     * Gets the pattern information as a JSON string.
     *
     * @return The pattern JSON string
     */
    @NonNull
    public String getPatternInfo() {
        return mPatternJson;
    }

    @Override
    public void validate() {
        // No validation implemented in the current version
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DynamicEffect other)) {
            return false;
        }
        return TextUtils.equals(mPatternJson, other.mPatternJson);
    }

    @Override
    public int hashCode() {
        int result = 17;
        if (mPatternJson != null) {
            result += 37 * mPatternJson.hashCode();
        }
        return result;
    }

    @Override
    public long getDuration() {
        return 0;
    }

    @Override
    public String toString() {
        return "DynamicEffect{mPatternJson=" + mPatternJson + "}";
    }

    @Override
    public String toDebugString() {
        return null;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(PARCEL_TOKEN_DYNAMIC_EFFECT);
        out.writeString(mPatternJson);
    }

    /**
     * @hide
     */
    @Override
    public VibrationEffect applyAdaptiveScale(float scaleFactor) {
        return null;
    }

    /**
     * @hide
     */
    @Override
    public VibrationEffect applyEffectStrength(int effectStrength) {
        return null;
    }
}
