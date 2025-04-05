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
 * Implementation of a pattern HE vibration effect.
 *
 * @hide
 */
public final class PatternHe extends VibrationEffect implements Parcelable {
    public static final @NonNull Parcelable.Creator<PatternHe> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public PatternHe createFromParcel(@NonNull Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new PatternHe(in);
                }

                @Override
                public @NonNull PatternHe[] newArray(int size) {
                    return new PatternHe[size];
                }
            };
    private final int[] mPatternInfo;
    private int mLooper;
    private int mInterval;
    private int mAmplitude;
    private int mFreq;
    private long mDuration = 100;
    private int mEventCount;

    /**
     * Creates a PatternHe effect from a Parcel.
     *
     * @param in The Parcel to read from
     * @hide
     */
    public PatternHe(@NonNull Parcel in) {
        mPatternInfo = in.createIntArray();
        mLooper = in.readInt();
        mInterval = in.readInt();
        mAmplitude = in.readInt();
        mFreq = in.readInt();
    }

    /**
     * Creates a PatternHe effect with the specified parameters.
     *
     * @param patternInfo The pattern information array
     * @param duration    The duration value
     * @param eventCount  The event count
     */
    public PatternHe(@NonNull int[] patternInfo, long duration, int eventCount) {
        mPatternInfo = patternInfo;
        mDuration = duration;
        mEventCount = eventCount;
    }

    /**
     * Creates a PatternHe effect with the specified parameters and loop settings.
     *
     * @param patternInfo The pattern information array
     * @param looper      The looper value
     * @param interval    The interval value
     * @param amplitude   The amplitude value
     * @param freq        The frequency value
     */
    public PatternHe(@NonNull int[] patternInfo, int looper, int interval, int amplitude,
            int freq) {
        mPatternInfo = patternInfo;
        mLooper = looper;
        mInterval = interval;
        mFreq = freq;
        mAmplitude = amplitude;
        mEventCount = 0;
    }

    @Override
    public long getDuration() {
        return mDuration;
    }

    /**
     * Gets the event count.
     *
     * @return The event count
     */
    public int getEventCount() {
        return mEventCount;
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
    public PatternHe resolve(int defaultAmplitude) {
        return this;
    }

    /**
     * Scales the effect by the specified factor.
     *
     * @param scaleFactor The scale factor
     * @return This effect instance
     */
    public PatternHe scale(float scaleFactor) {
        return this;
    }

    /**
     * Gets the pattern information array.
     *
     * @return The pattern information array
     */
    public @NonNull int[] getPatternInfo() {
        return mPatternInfo;
    }

    /**
     * Gets the looper value.
     *
     * @return The looper value
     */
    public int getLooper() {
        return mLooper;
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
    public void validate() {
        if (mDuration <= 0) {
            throw new IllegalArgumentException(
                    "duration must be positive (duration=" + mDuration + ")");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PatternHe other)) {
            return false;
        }
        return other.mDuration == mDuration && other.mPatternInfo == mPatternInfo;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result += 37 * (int) mDuration;
        result += 37 * mEventCount;
        return result;
    }

    @Override
    public String toString() {
        return "PatternHe{mLooper=" + mLooper +
                ", mInterval=" + mInterval +
                ", mAmplitude=" + mAmplitude +
                ", mFreq=" + mFreq + "}";
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
        out.writeInt(RichTapVibrationEffect.PARCEL_TOKEN_PATTERN_HE);
        out.writeIntArray(this.mPatternInfo);
        out.writeInt(mLooper);
        out.writeInt(mInterval);
        out.writeInt(mAmplitude);
        out.writeInt(mFreq);
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
