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
import android.annotation.Nullable;
import android.content.res.Resources;
import android.os.VibrationEffect;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A RichTapVibrationEffect describes a haptic effect to be performed by a {@link Vibrator}.
 *
 * These effects may be any number of things, from single shot vibrations to complex
 * waveforms, and to AAC extended effects.
 */
public final class RichTapVibrationEffect {
    private static final String TAG = RichTapVibrationEffect.class.getSimpleName();

    // Parcel tokens for different effect types
    static final int PARCEL_TOKEN_EXT_PREBAKED = 501;
    static final int PARCEL_TOKEN_ENVELOPE = 502;
    static final int PARCEL_TOKEN_PATTERN_HE = 503;
    static final int PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER = 504;
    static final int PARCEL_TOKEN_HAPTIC_PARAMETER = 505;

    // Client identification constants
    private static final int AAC_CLIENT = 0x00FF << 16;

    // Version constants
    private static final int MAJOR_RICHTAP_VERSION = 0x0020 << 8;
    private static final int MINOR_RICHTAP_VERSION = 0x0010 << 0;

    // Effect ID constants
    private static final int EFFECT_ID_START = 0x1000;
    private static final int INNER_EFFECT_STRENGTH_LIGHT = 150;
    private static final int INNER_EFFECT_STRENGTH_MEDIUM = 200;
    private static final int INNER_EFFECT_STRENGTH_STRONG = 250;
    private static final int PREBAKED_HE_MAX_EVENTS = 16;
    private static final int PREBAKED_HE_EVENT_SIZE = 17;
    private static final int PREBAKED_HE_CONTINUOUS_EVENT = 0x1000;
    private static final int PREBAKED_HE_TRANSIENT_EVENT = 0x1001;
    private static final int PREBAKED_HE_DEFAULT_RELATIVE_TIME_STEP_MS = 400;
    private static final String PREBAKED_HE_RESOURCE_ROOT = "/vendor/etc/richtapresources";

    private static final Object sPrebakedHeCacheLock = new Object();
    private static final Map<String, int[]> sPrebakedHeCache = new HashMap<>();

    // Prevent instantiation
    private RichTapVibrationEffect() {
        // not called
    }

    /**
     * Checks if RichTap vibration is supported on this device.
     *
     * @return A boolean indicating support status
     * @hide
     */
    public static boolean isSupported() {
        return Resources.getSystem().getBoolean(R.bool.config_usesRichtapVibration) &&
               checkIfRichTapSupport() != Vibrator.VIBRATION_EFFECT_SUPPORT_NO;
    }

    /**
     * Checks if RichTap vibration is supported on this device.
     *
     * @return A value indicating support status and version information
     */
    public static int checkIfRichTapSupport() {
        return (AAC_CLIENT | MAJOR_RICHTAP_VERSION | MINOR_RICHTAP_VERSION);
    }

    /**
     * Checks whether the given prebaked effect ID is handled by RichTap.
     * @param id The vibration effect ID
     * @return {@code true} if the effect can be routed through RichTap
     * @hide
     */
    public static boolean isInnerEffectSupported(int id) {
        switch (id) {
            case VibrationEffect.EFFECT_CLICK:
            case VibrationEffect.EFFECT_DOUBLE_CLICK:
            case VibrationEffect.EFFECT_TICK:
            case VibrationEffect.EFFECT_THUD:
            case VibrationEffect.EFFECT_POP:
            case VibrationEffect.EFFECT_HEAVY_CLICK:
            case VibrationEffect.EFFECT_TEXTURE_TICK:
                return true;
            default:
                return false;
        }
    }

    /**
     * Gets the RichTap prebaked effect ID for a standard Android prebaked effect ID.
     * @param id The Android vibration effect ID
     * @return The RichTap effect ID
     * @hide
     */
    public static int getInnerEffectId(int id) {
        return EFFECT_ID_START + id;
    }

    /**
     * Gets the inner effect strength value for a given strength level.
     * @param strength The desired effect strength
     * @return Strength value, or 0 if invalid
     * @hide
     */
    public static int getInnerEffectStrength(int strength) {
        switch (strength) {
            case VibrationEffect.EFFECT_STRENGTH_LIGHT:
                return INNER_EFFECT_STRENGTH_LIGHT;
            case VibrationEffect.EFFECT_STRENGTH_MEDIUM:
                return INNER_EFFECT_STRENGTH_MEDIUM;
            case VibrationEffect.EFFECT_STRENGTH_STRONG:
                return INNER_EFFECT_STRENGTH_STRONG;
            default:
                Slog.e(TAG, "Invalid effect strength: " + strength);
                return 0;
        }
    }

    /**
     * Loads a tuned HE resource for a standard Android prebaked effect.
     * @param id The Android vibration effect ID
     * @param strength The desired effect strength
     * @return Parsed HE pattern, or {@code null} when disabled, unsupported, or unavailable
     * @hide
     */
    @Nullable
    public static int[] getPrebakedHeEffect(int id, int strength) {
        if (!new File(PREBAKED_HE_RESOURCE_ROOT).isDirectory()) {
            return null;
        }

        String fileName = getPrebakedHeFileName(id);
        String strengthDir = getPrebakedHeStrengthDir(strength);
        if (fileName == null || strengthDir == null) {
            return null;
        }

        String cacheKey = strengthDir + '/' + fileName;
        synchronized (sPrebakedHeCacheLock) {
            int[] cached = sPrebakedHeCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        int[] pattern = loadPrebakedHeEffect(PREBAKED_HE_RESOURCE_ROOT, strengthDir, fileName);
        if (pattern != null) {
            synchronized (sPrebakedHeCacheLock) {
                sPrebakedHeCache.put(cacheKey, pattern);
            }
        }
        return pattern;
    }

    @Nullable
    private static String getPrebakedHeFileName(int id) {
        switch (id) {
            case VibrationEffect.EFFECT_CLICK:
                return "click.he";
            case VibrationEffect.EFFECT_DOUBLE_CLICK:
                return "double_click.he";
            case VibrationEffect.EFFECT_TICK:
                return "tick.he";
            case VibrationEffect.EFFECT_THUD:
                return "thud.he";
            case VibrationEffect.EFFECT_POP:
                return "pop.he";
            case VibrationEffect.EFFECT_HEAVY_CLICK:
                return "heavy_click.he";
            case VibrationEffect.EFFECT_TEXTURE_TICK:
                return "texture_tick.he";
            default:
                return null;
        }
    }

    @Nullable
    private static String getPrebakedHeStrengthDir(int strength) {
        switch (strength) {
            case VibrationEffect.EFFECT_STRENGTH_LIGHT:
                return "weak";
            case VibrationEffect.EFFECT_STRENGTH_MEDIUM:
                return "default";
            case VibrationEffect.EFFECT_STRENGTH_STRONG:
                return "strong";
            default:
                Slog.e(TAG, "Invalid effect strength: " + strength);
                return null;
        }
    }

    @Nullable
    private static int[] loadPrebakedHeEffect(String root, String strengthDir, String fileName) {
        File rootDir = new File(root);
        File heFile = new File(new File(rootDir, strengthDir), fileName);
        if (!heFile.exists()) {
            heFile = new File(rootDir, fileName);
        }
        if (!heFile.exists()) {
            Slog.w(TAG, "Missing RichTap HE resource: " + heFile);
            return null;
        }

        try {
            return parsePrebakedHeEffect(readHeFile(heFile));
        } catch (Exception e) {
            Slog.e(TAG, "Failed to load RichTap HE resource: " + heFile, e);
            return null;
        }
    }

    private static String readHeFile(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }

    @Nullable
    private static int[] parsePrebakedHeEffect(String content) throws Exception {
        JSONObject root = new JSONObject(content);
        if (root.getJSONObject("Metadata").getInt("Version") != 1) {
            return null;
        }

        JSONArray pattern = root.getJSONArray("Pattern");
        int eventCount = Math.min(pattern.length(), PREBAKED_HE_MAX_EVENTS);
        int[] he = new int[eventCount * PREBAKED_HE_EVENT_SIZE + 1];
        he[0] = 1;

        for (int i = 0; i < eventCount; i++) {
            JSONObject event = pattern.getJSONObject(i).getJSONObject("Event");
            int type = getPrebakedHeEventType(event.getString("Type"));
            if (type == 0) {
                Slog.e(TAG, "Unsupported RichTap HE event type");
                return null;
            }

            int relativeTime = event.has("RelativeTime")
                    ? event.getInt("RelativeTime")
                    : i * PREBAKED_HE_DEFAULT_RELATIVE_TIME_STEP_MS;
            if (!isInRange(relativeTime, 0, 50000)) {
                Slog.e(TAG, "RelativeTime must be between 0 and 50000");
                return null;
            }

            JSONObject params = event.getJSONObject("Parameters");
            int intensity = params.getInt("Intensity");
            int frequency = params.getInt("Frequency");
            if (!isInRange(intensity, 0, 100) || !isInRange(frequency, 0, 100)) {
                Slog.e(TAG, "Intensity or Frequency must be between 0 and 100");
                return null;
            }

            int base = i * PREBAKED_HE_EVENT_SIZE;
            he[base + 1] = type;
            he[base + 2] = relativeTime;
            he[base + 3] = intensity;
            he[base + 4] = frequency;
        }
        return he;
    }

    private static int getPrebakedHeEventType(String type) {
        if ("continuous".equals(type)) {
            return PREBAKED_HE_CONTINUOUS_EVENT;
        }
        if ("transient".equals(type)) {
            return PREBAKED_HE_TRANSIENT_EVENT;
        }
        return 0;
    }

    private static boolean isInRange(int value, int min, int max) {
        return value >= min && value <= max;
    }

    /**
     * Creates an extended pre-baked effect with the specified ID and strength.
     *
     * @param effectId The effect ID
     * @param strength The strength value (1-100)
     * @return A VibrationEffect instance
     */
    @NonNull
    public static VibrationEffect createExtPreBaked(int effectId, int strength) {
        VibrationEffect effect = new ExtPrebaked(EFFECT_ID_START + effectId, strength);
        effect.validate();
        return effect;
    }

    /**
     * Creates an envelope vibration effect with specified parameters.
     *
     * @param relativeTimeArr The relative time array (length 4)
     * @param scaleArr The scale array (length 4)
     * @param freqArr The frequency array (length 4)
     * @param steepMode Whether steep mode is enabled
     * @param amplitude The amplitude value (1-255 or -1 for default)
     * @return A VibrationEffect instance
     */
    @NonNull
    public static VibrationEffect createEnvelope(
            @NonNull int[] relativeTimeArr,
            @NonNull int[] scaleArr,
            @NonNull int[] freqArr,
            boolean steepMode,
            int amplitude) {
        VibrationEffect effect = new Envelope(relativeTimeArr, scaleArr, freqArr, steepMode, amplitude);
        effect.validate();
        return effect;
    }

    /**
     * Creates a pattern HE parameter effect with specified parameters.
     *
     * @param interval The interval value
     * @param amplitude The amplitude value (1-255 or -1 for default)
     * @param freq The frequency value
     * @return A VibrationEffect instance
     */
    @NonNull
    public static VibrationEffect createPatternHeParameter(int interval, int amplitude, int freq) {
        VibrationEffect effect = new PatternHeParameter(interval, amplitude, freq);
        effect.validate();
        return effect;
    }

    /**
     * Creates a haptic parameter effect with specified parameters.
     *
     * @param param The parameter array
     * @param length The length value (must match param.length)
     * @return A VibrationEffect instance
     */
    @NonNull
    public static VibrationEffect createHapticParameter(@NonNull int[] param, int length) {
        VibrationEffect effect = new HapticParameter(param, length);
        effect.validate();
        return effect;
    }

    /**
     * Creates a pattern HE effect with specified parameters and loop settings.
     *
     * @param patternInfo The pattern information array
     * @param looper The looper value
     * @param interval The interval value
     * @param amplitude The amplitude value
     * @param freq The frequency value
     * @return A VibrationEffect instance
     */
    @NonNull
    public static VibrationEffect createPatternHeWithParam(
            @NonNull int[] patternInfo,
            int looper,
            int interval,
            int amplitude,
            int freq) {
        VibrationEffect effect = new PatternHe(patternInfo, looper, interval, amplitude, freq);
        effect.validate();
        return effect;
    }

    /**
     * Checks if the given token represents an extended effect.
     *
     * @param token The token to check
     * @return true if it is an extended effect, false otherwise
     * @hide
     */
    public static boolean isExtendedEffect(int token) {
        switch (token) {
            case PARCEL_TOKEN_EXT_PREBAKED:
            case PARCEL_TOKEN_ENVELOPE:
            case PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER:
            case PARCEL_TOKEN_PATTERN_HE:
            case PARCEL_TOKEN_HAPTIC_PARAMETER:
                return true;
            default:
                return false;
        }
    }

    /**
     * Creates an extended effect from a Parcel.
     *
     * @param in The Parcel to read from
     * @return A VibrationEffect instance
     * @hide
     */
    @NonNull
    public static VibrationEffect createExtendedEffect(@NonNull Parcel in) {
        int offset = in.dataPosition() - Integer.BYTES;
        in.setDataPosition(offset);
        return RichTapVibrationEffect.CREATOR.createFromParcel(in);
    }

    /**
     * Creator for RichTapVibrationEffect instances.
     * @hide
     */
    public static final @NonNull Parcelable.Creator<VibrationEffect> CREATOR =
            new Parcelable.Creator<VibrationEffect>() {
                @Override
                public VibrationEffect createFromParcel(Parcel in) {
                    int token = in.readInt();
                    Log.d(TAG, "read token: " + token + "!");

                    switch (token) {
                        case PARCEL_TOKEN_EXT_PREBAKED:
                            return new ExtPrebaked(in);
                        case PARCEL_TOKEN_ENVELOPE:
                            return new Envelope(in);
                        case PARCEL_TOKEN_PATTERN_HE_LOOP_PARAMETER:
                            return new PatternHeParameter(in);
                        case PARCEL_TOKEN_PATTERN_HE:
                            return new PatternHe(in);
                        case PARCEL_TOKEN_HAPTIC_PARAMETER:
                            return new HapticParameter(in);
                        default:
                            throw new IllegalStateException(
                                    "Unexpected vibration event type token in parcel.");
                    }
                }

                @Override
                public VibrationEffect[] newArray(int size) {
                    return new VibrationEffect[size];
                }
            };
}
