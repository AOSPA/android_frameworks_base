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

import java.util.Arrays;

/**
 * Implementation of an envelope vibration effect.
 *
 * @hide
 */
public final class Envelope extends VibrationEffect implements Parcelable {
    public static final @NonNull Parcelable.Creator<Envelope> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public Envelope createFromParcel(@NonNull Parcel in) {
                    // Skip the type token
                    in.readInt();
                    return new Envelope(in);
                }

                @Override
                public @NonNull Envelope[] newArray(int size) {
                    return new Envelope[size];
                }
            };
    private final int[] relativeTimeArr;
    private final int[] scaleArr;
    private final int[] freqArr;
    private final boolean steepMode;
    private final int amplitude;

    /**
     * Creates an Envelope effect from a Parcel.
     *
     * @param in The Parcel to read from
     * @hide
     */
    public Envelope(@NonNull Parcel in) {
        this(in.createIntArray(), in.createIntArray(), in.createIntArray(),
                in.readInt() == 1, in.readInt());
    }

    /**
     * Creates an Envelope effect with the specified parameters.
     *
     * @param relativeTimeArr The relative time array (length 4)
     * @param scaleArr        The scale array (length 4)
     * @param freqArr         The frequency array (length 4)
     * @param steepMode       Whether steep mode is enabled
     * @param amplitude       The amplitude value
     */
    public Envelope(@NonNull int[] relativeTimeArr, @NonNull int[] scaleArr,
            @NonNull int[] freqArr, boolean steepMode, int amplitude) {
        this.relativeTimeArr = Arrays.copyOf(relativeTimeArr, 4);
        this.scaleArr = Arrays.copyOf(scaleArr, 4);
        this.freqArr = Arrays.copyOf(freqArr, 4);
        this.steepMode = steepMode;
        this.amplitude = amplitude;
    }

    /**
     * Gets the relative time array.
     *
     * @return The relative time array
     */
    public @NonNull int[] getRelativeTimeArr() {
        return this.relativeTimeArr;
    }

    /**
     * Gets the scale array.
     *
     * @return The scale array
     */
    public @NonNull int[] getScaleArr() {
        return this.scaleArr;
    }

    /**
     * Gets the frequency array.
     *
     * @return The frequency array
     */
    public @NonNull int[] getFreqArr() {
        return this.freqArr;
    }

    /**
     * Checks if steep mode is enabled.
     *
     * @return true if steep mode is enabled, false otherwise
     */
    public boolean isSteepMode() {
        return this.steepMode;
    }

    /**
     * Gets the amplitude value.
     *
     * @return The amplitude value
     */
    public int getAmplitude() {
        return this.amplitude;
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
    public Envelope resolve(int defaultAmplitude) {
        return this;
    }

    /**
     * Scales the effect by the specified factor.
     *
     * @param scaleFactor The scale factor
     * @return This effect instance
     */
    public Envelope scale(float scaleFactor) {
        return this;
    }

    @Override
    public long getDuration() {
        return -1;
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
    public long[] computeCreateWaveformOffOnTimingsOrNull() {
        return new long[0];
    }

    @Override
    public boolean areVibrationFeaturesSupported(@NonNull VibratorInfo vibratorInfo) {
        return false;
    }

    @Override
    public void validate() {
        for (int i = 0; i < 4; i++) {
            if (relativeTimeArr[i] < 0) {
                throw new IllegalArgumentException("relative time cannot be negative");
            }

            if (scaleArr[i] < 0) {
                throw new IllegalArgumentException("scale cannot be negative");
            }

            if (freqArr[i] < 0) {
                throw new IllegalArgumentException("frequency must be positive");
            }
        }

        if (amplitude < -1 || amplitude == 0 || amplitude > 255) {
            throw new IllegalArgumentException(
                    "amplitude must either be DEFAULT_AMPLITUDE, " +
                            "or between 1 and 255 inclusive (amplitude=" + amplitude + ")");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Envelope other)) {
            return false;
        }
        int[] timeArr = other.getRelativeTimeArr();
        int[] scArr = other.getScaleArr();
        int[] frArr = other.getFreqArr();

        if (this.amplitude != other.getAmplitude()) {
            return false;
        }

        if (!Arrays.equals(timeArr, this.relativeTimeArr)) {
            return false;
        }

        if (!Arrays.equals(scArr, this.scaleArr)) {
            return false;
        }

        if (!Arrays.equals(frArr, this.freqArr)) {
            return false;
        }

        return other.isSteepMode() == this.steepMode;
    }

    @Override
    public int hashCode() {
        return relativeTimeArr[2] + scaleArr[2] + freqArr[2];
    }

    @Override
    public String toString() {
        return "Envelope: {relativeTimeArr=" + Arrays.toString(relativeTimeArr) +
                ", scaleArr=" + Arrays.toString(scaleArr) +
                ", freqArr=" + Arrays.toString(freqArr) +
                ", steepMode=" + this.steepMode +
                ", amplitude=" + this.amplitude + "}";
    }

    @Override
    public String toDebugString() {
        return null;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(RichTapVibrationEffect.PARCEL_TOKEN_ENVELOPE);
        out.writeIntArray(this.relativeTimeArr);
        out.writeIntArray(this.scaleArr);
        out.writeIntArray(this.freqArr);
        out.writeInt(this.steepMode ? 1 : 0);
        out.writeInt(this.amplitude);
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
