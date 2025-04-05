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
import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class for playing dynamic haptic effects using RichTap vibration technology.
 */
@SuppressLint("NotCloseable")
public class HapticPlayer {
    public static final int FORMAT_VERSION = 2;
    public static final String VIBRATE_REASON = "DynamicEffect";
    public static final String PATTERN_KEY_PATTERN = "Pattern";
    public static final String EVENT_TYPE_HE_CONTINUOUS_NAME = "continuous";
    public static final String EVENT_TYPE_HE_TRANSIENT_NAME = "transient";
    public static final String EVENT_KEY_EVENT = "Event";
    public static final String EVENT_KEY_RELATIVE_TIME = "RelativeTime";
    public static final String EVENT_KEY_DURATION = "Duration";
    public static final String EVENT_KEY_HE_TYPE = "Type";
    public static final String EVENT_KEY_HE_PARAMETERS = "Parameters";
    public static final String EVENT_KEY_HE_INTENSITY = "Intensity";
    public static final String EVENT_KEY_HE_FREQUENCY = "Frequency";
    public static final String EVENT_KEY_HE_CURVE = "Curve";
    public static final String EVENT_KEY_HE_CURVE_POINT_TIME = "Time";
    public static final String PATTERN_KEY_PATTERN_LIST = "PatternList";
    public static final String HE_META_DATA_KEY = "Metadata";
    public static final String HE_VERSION_KEY = "Version";
    public static final String PATTERN_KEY_PATTERN_ABS_TIME = "AbsoluteTime";
    public static final String PATTERN_KEY_EVENT_VIB_ID = "Index";
    public static final int CONTINUOUS_EVENT = 0x1000;
    public static final int TRANSIENT_EVENT = CONTINUOUS_EVENT + 1;
    public static final int HE_DEFAULT_RELATIVE_TIME = 400;
    public static final int HE_DEFAULT_DURATION = 0;
    public static final int HE_TYPE = 0;
    public static final int HE_RELATIVE_TIME = 1;
    public static final int HE_INTENSITY = 2;
    public static final int HE_FREQUENCY = 3;
    public static final int HE_DURATION = 4;
    public static final int HE_VIB_INDEX = 5;
    public static final int HE_POINT_COUNT = 6;
    public static final int HE_CURVE_POINT_0_TIME = 7;
    public static final int HE_CURVE_POINT_0_INTENSITY = 8;
    public static final int HE_CURVE_POINT_0_FREQUENCY = 9;
    public static final int HE_VALUE_LENGTH = 7 + 3 * 16;
    public static final int HE2_0_PATTERN_WRAP_NUM = 10;
    private static final String TAG = HapticPlayer.class.getSimpleName();
    private static final int MAX_EVENT_COUNT = 16;
    private static final int MAX_POINT_COUNT = 16;
    private static final boolean mAvailable = isSupportRichtap();
    private static final ExecutorService mExcutor = Executors.newSingleThreadExecutor();
    private static final AtomicInteger mSeq = new AtomicInteger();
    private final VibratorManager mVibratorManager;
    private final String mPackageName;
    private final boolean DEBUG = true;
    private boolean mStarted;
    private DynamicEffect mEffect;

    /**
     * Default constructor.
     */
    private HapticPlayer() {
        this.mStarted = false;
        this.mPackageName = ActivityThread.currentPackageName();
        Context ctx = ActivityThread.currentActivityThread().getSystemContext();
        this.mVibratorManager = ctx.getSystemService(VibratorManager.class);
    }

    /**
     * Constructor with a dynamic effect.
     *
     * @param effect The dynamic effect to play
     */
    public HapticPlayer(@NonNull final DynamicEffect effect) {
        this();
        this.mEffect = effect;
    }

    /**
     * Checks if RichTap vibration is supported on this device.
     *
     * @return true if supported, false otherwise
     */
    private static boolean isSupportRichtap() {
        int support = RichTapVibrationEffect.checkIfRichTapSupport();
        return support != Vibrator.VIBRATION_EFFECT_SUPPORT_NO;
    }

    /**
     * Checks if RichTap vibration is available.
     *
     * @return true if available, false otherwise
     */
    public static boolean isAvailable() {
        return mAvailable;
    }

    /**
     * Gets the major RichTap core version exposed by the framework.
     *
     * @return the RichTap major version, or 0 when unsupported
     */
    public static int getMajorVersion() {
        int support = RichTapVibrationEffect.checkIfRichTapSupport();
        if (support == Vibrator.VIBRATION_EFFECT_SUPPORT_NO) {
            return 0;
        }
        int clientCode = (support & (0x00FF << 16)) >> 16;
        int majorVersion = (support & (0x00FF << 8)) >> 8;
        int minorVersion = support & 0x00FF;

        Log.d(TAG, "clientCode:" + clientCode + " majorVersion:" + majorVersion
                + " minorVersion:" + minorVersion);
        return majorVersion;
    }

    /**
     * Gets the minor RichTap core version exposed by the framework.
     *
     * @return the RichTap minor version, or 0 when unsupported
     */
    public static int getMinorVersion() {
        int support = RichTapVibrationEffect.checkIfRichTapSupport();
        if (support == Vibrator.VIBRATION_EFFECT_SUPPORT_NO) {
            return 0;
        }
        int clientCode = (support & (0x00FF << 16)) >> 16;
        int majorVersion = (support & (0x00FF << 8)) >> 8;
        int minorVersion = support & 0x00FF;

        Log.d(TAG, "clientCode:" + clientCode + " majorVersion:" + majorVersion
                + " minorVersion:" + minorVersion);
        return minorVersion;
    }

    /**
     * Checks if the given data is within the specified interval.
     *
     * @param data The data to check
     * @param a    The lower bound (inclusive)
     * @param b    The upper bound (inclusive)
     * @return true if the data is within the interval, false otherwise
     */
    private boolean isInTheInterval(int data, int a, int b) {
        return data >= a && data <= b;
    }

    /**
     * Parses and serializes HE 1.0 pattern data.
     *
     * @param patternString The pattern JSON string
     * @return The serialized pattern data, or null if parsing failed
     */
    private int[] getSerializationDataHe_1_0(String patternString) {
        int totalDuration;
        int relativeTimeLast = 0;
        int durationLast = 0;
        int[] patternHeInfo;

        try {
            JSONObject hapticObject = new JSONObject(patternString);
            JSONArray pattern = hapticObject.getJSONArray(PATTERN_KEY_PATTERN);
            int eventNumberTmp = Math.min(pattern.length(), MAX_EVENT_COUNT);
            int len = eventNumberTmp * HE_VALUE_LENGTH;
            patternHeInfo = new int[len];

            boolean isCompliance = true;

            for (int ind = 0; ind < eventNumberTmp; ind++) {
                JSONObject patternObject = pattern.getJSONObject(ind);
                JSONObject eventObject = patternObject.getJSONObject(EVENT_KEY_EVENT);

                // Get type
                String name = eventObject.getString(EVENT_KEY_HE_TYPE);
                int type;
                if (TextUtils.equals(EVENT_TYPE_HE_CONTINUOUS_NAME, name)) {
                    type = CONTINUOUS_EVENT;
                } else if (TextUtils.equals(EVENT_TYPE_HE_TRANSIENT_NAME, name)) {
                    type = TRANSIENT_EVENT;
                } else {
                    // Error: unknown type
                    Log.e(TAG, "Event " + ind + " has unknown type: " + name);
                    isCompliance = false;
                    break;
                }

                // Get RelativeTime
                if (!eventObject.has(EVENT_KEY_RELATIVE_TIME)) {
                    Log.e(TAG, "Event " + ind + " missing relativeTime parameter, " +
                            "using default: " + (ind * HE_DEFAULT_RELATIVE_TIME));
                    relativeTimeLast = ind * HE_DEFAULT_RELATIVE_TIME;
                } else {
                    relativeTimeLast = eventObject.getInt(EVENT_KEY_RELATIVE_TIME);
                }

                if (!isInTheInterval(relativeTimeLast, 0, 50000)) {
                    Log.e(TAG, "Event " + ind + " relativeTime must be between 0 and 50000 " +
                            "(value: " + relativeTimeLast + ")");
                    isCompliance = false;
                    break;
                }

                // Get Parameters
                JSONObject parametersObject = eventObject.getJSONObject(EVENT_KEY_HE_PARAMETERS);
                int intensity = parametersObject.getInt(EVENT_KEY_HE_INTENSITY);
                int frequency = parametersObject.getInt(EVENT_KEY_HE_FREQUENCY);

                if (!isInTheInterval(intensity, 0, 100) || !isInTheInterval(frequency, 0, 100)) {
                    Log.e(TAG,
                            "Event " + ind + " parameters out of range - intensity: " + intensity +
                                    ", frequency: " + frequency
                                    + " (both must be between 0 and 100)");
                    isCompliance = false;
                    break;
                }

                patternHeInfo[ind * HE_VALUE_LENGTH + HE_TYPE] = type;
                patternHeInfo[ind * HE_VALUE_LENGTH + HE_RELATIVE_TIME] = relativeTimeLast;
                patternHeInfo[ind * HE_VALUE_LENGTH + HE_INTENSITY] = intensity;
                patternHeInfo[ind * HE_VALUE_LENGTH + HE_FREQUENCY] = frequency;

                if (CONTINUOUS_EVENT == type) {
                    // Get Duration
                    if (!eventObject.has(EVENT_KEY_DURATION)) {
                        Log.e(TAG, "Event " + ind + " missing duration parameter, " +
                                "using default: " + HE_DEFAULT_DURATION);
                        durationLast = HE_DEFAULT_DURATION;
                    } else {
                        durationLast = eventObject.getInt(EVENT_KEY_DURATION);
                    }

                    if (!isInTheInterval(durationLast, 0, 5000)) {
                        Log.e(TAG, "Event " + ind + " duration must be less than 5000 " +
                                "(value: " + durationLast + ")");
                        isCompliance = false;
                        break;
                    }

                    patternHeInfo[ind * HE_VALUE_LENGTH + HE_DURATION] = durationLast;
                    patternHeInfo[ind * HE_VALUE_LENGTH + HE_VIB_INDEX] = 0;

                    JSONArray curve = parametersObject.getJSONArray(EVENT_KEY_HE_CURVE);
                    int pointCount = Math.min(curve.length(), MAX_POINT_COUNT);
                    patternHeInfo[ind * HE_VALUE_LENGTH + HE_POINT_COUNT] = pointCount;

                    // Process points data
                    for (int i = 0; i < pointCount; i++) {
                        JSONObject curveObject = curve.getJSONObject(i);
                        int pointTime = curveObject.getInt(EVENT_KEY_HE_CURVE_POINT_TIME);
                        // Multiply by 100 to facilitate data transfer
                        int pointIntensity = (int) (curveObject.getDouble(EVENT_KEY_HE_INTENSITY)
                                * 100);
                        int pointFrequency = curveObject.getInt(EVENT_KEY_HE_FREQUENCY);

                        if (0 == i && (pointTime != 0 || pointIntensity != 0 ||
                                !isInTheInterval(pointFrequency, -100, 100))) {
                            Log.e(TAG, "Event " + ind + " first curve point invalid - time: "
                                    + pointTime +
                                    ", intensity: " + pointIntensity + ", frequency: "
                                    + pointFrequency +
                                    " (first point must have time=0, intensity=0, freq in [-100,"
                                    + "100])");
                            isCompliance = false;
                            break;
                        } else if (0 < i && i < pointCount - 1 &&
                                (!isInTheInterval(pointTime, 0, 5000) ||
                                        !isInTheInterval(pointIntensity, 0, 100) ||
                                        !isInTheInterval(pointFrequency, -100, 100))) {
                            // Intensity value has multi 100, so interval is 0~100
                            Log.e(TAG, "Event " + ind + " curve point " + i + " invalid - time: "
                                    + pointTime +
                                    ", intensity: " + pointIntensity + ", frequency: "
                                    + pointFrequency +
                                    " (must have time in [0,5000], intensity in [0,100], freq in "
                                    + "[-100,100])");
                            isCompliance = false;
                            break;
                        } else if (pointCount - 1 == i && (pointTime != durationLast ||
                                pointIntensity != 0 ||
                                !isInTheInterval(pointFrequency, -100, 100))) {
                            Log.e(TAG, "Event " + ind + " last curve point invalid - time: "
                                    + pointTime +
                                    ", intensity: " + pointIntensity + ", frequency: "
                                    + pointFrequency +
                                    " (last point must have time=" + durationLast +
                                    ", intensity=0, freq in [-100,100])");
                            isCompliance = false;
                            break;
                        }

                        patternHeInfo[ind * HE_VALUE_LENGTH + (HE_CURVE_POINT_0_TIME + i * 3)] =
                                pointTime;
                        patternHeInfo[ind * HE_VALUE_LENGTH + (HE_CURVE_POINT_0_INTENSITY
                                + i * 3)] =
                                pointIntensity;
                        patternHeInfo[ind * HE_VALUE_LENGTH + (HE_CURVE_POINT_0_FREQUENCY
                                + i * 3)] =
                                pointFrequency;
                    }
                }

                if (!isCompliance) break;

                if (DEBUG) {
                    Log.d(TAG, "Parsed event " + ind + " - type: " +
                            (type == CONTINUOUS_EVENT ? "continuous" : "transient") +
                            ", relativeTime: " + relativeTimeLast +
                            ", intensity: " + intensity +
                            ", frequency: " + frequency);
                }
            }

            // Compliance check
            if (!isCompliance) {
                Log.e(TAG, "HE 1.0 pattern failed compliance check");
                return null;
            }

            int lastEventIndex = (eventNumberTmp - 1) * HE_VALUE_LENGTH + HE_TYPE;
            if (CONTINUOUS_EVENT == patternHeInfo[lastEventIndex]) {
                totalDuration = relativeTimeLast + durationLast;
                Log.d(TAG, "Last event is continuous, total duration: " + totalDuration + "ms");
            } else {
                totalDuration = relativeTimeLast + 80;
                Log.d(TAG, "Last event is transient, total duration: " + totalDuration + "ms");
            }

            return patternHeInfo;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing HE 1.0 pattern: " + e.getMessage());
            return null;
        }
    }

    /**
     * Generates serialized data for HE 2.0 patterns.
     *
     * @param formatVersion The format version
     * @param heVersion     The HE version
     * @param totalPattern  The total number of patterns
     * @param pid           The process ID
     * @param seq           The sequence number
     * @param indexBase     The base index for patterns
     * @param pattern       The pattern array
     * @return The serialized pattern data
     */
    int[] generateSerializationDataHe_2_0(int formatVersion, int heVersion, int totalPattern,
            int pid, int seq, int indexBase, Pattern[] pattern) {
        int totalPatternLen = 0;
        int patternOffset = 5;

        for (Pattern patternTmp : pattern) {
            totalPatternLen += patternTmp.getPatternDataLen();
        }

        int[] data = new int[patternOffset + totalPatternLen];
        Arrays.fill(data, 0);

        data[0] = formatVersion;
        data[1] = heVersion;
        data[2] = pid;
        data[3] = seq;
        data[4] |= totalPattern & 0x0000FFFF;

        int patternNum = pattern.length;
        data[4] |= ((patternNum << 16) & 0xFFFF0000);

        int[] patternData;

        for (Pattern patternTmp : pattern) {
            patternData = patternTmp.generateSerializationPatternData(indexBase);
            System.arraycopy(patternData, 0, data, patternOffset, patternData.length);
            patternOffset += patternData.length;
            indexBase++;
        }

        if (DEBUG) {
            Log.d(TAG, "Generated HE 2.0 data - formatVersion: " + formatVersion +
                    ", heVersion: " + heVersion +
                    ", totalPattern: " + totalPattern +
                    ", patternNum: " + patternNum);
        }

        return data;
    }

    /**
     * Sends a wrapped pattern to the vibrator.
     *
     * @param seq                The sequence number
     * @param pid                The process ID
     * @param heVersion          The HE version
     * @param loop               The loop count
     * @param interval           The interval between loops
     * @param amplitude          The amplitude value
     * @param freq               The frequency value
     * @param totalPatternNum    The total number of patterns
     * @param patternIndexOffset The pattern index offset
     * @param list               The pattern list
     */
    void sendPatternWrapper(int seq, int pid, int heVersion, int loop, int interval,
            int amplitude, int freq, int totalPatternNum, int patternIndexOffset, Pattern[] list) {
        int[] patternHe = generateSerializationDataHe_2_0(FORMAT_VERSION, heVersion,
                totalPatternNum,
                pid, seq, patternIndexOffset, list);

        try {
            VibrationEffect createPatternHe = RichTapVibrationEffect.createPatternHeWithParam(
                    patternHe, loop, interval, amplitude, freq);
            VibrationAttributes atr = new VibrationAttributes.Builder().build();
            CombinedVibration combinedEffect = CombinedVibration.createParallel(createPatternHe);
            mVibratorManager.vibrate(Process.myUid(), mPackageName,
                    combinedEffect, VIBRATE_REASON, atr);

            if (DEBUG) {
                Log.d(TAG, "Sent pattern wrapper - seq: " + seq +
                        ", part " + (patternIndexOffset / HE2_0_PATTERN_WRAP_NUM + 1) +
                        " of " + (totalPatternNum / HE2_0_PATTERN_WRAP_NUM +
                        (totalPatternNum % HE2_0_PATTERN_WRAP_NUM > 0 ? 1 : 0)));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send pattern wrapper: " + e.getMessage());
        }
    }

    /**
     * Parses and sends HE 2.0 pattern data.
     *
     * @param seq           The sequence number
     * @param pid           The process ID
     * @param heVersion     The HE version
     * @param loop          The loop count
     * @param interval      The interval between loops
     * @param amplitude     The amplitude value
     * @param freq          The frequency value
     * @param patternString The pattern JSON string
     */
    private void parseAndSendDataHe_2_0(int seq, int pid, int heVersion, int loop, int interval,
            int amplitude, int freq, String patternString) {
        int relativeTimeLast = 0;
        int durationLast = 0;
        Pattern[] patternList;

        try {
            JSONObject hapticObject = new JSONObject(patternString);
            JSONArray patternArray = hapticObject.getJSONArray(PATTERN_KEY_PATTERN_LIST);
            int patternNum = patternArray.length();

            boolean isCompliance = true;
            patternList = new Pattern[patternNum];
            int wrapperOffset = 0, wrapperIndex = 0;

            Log.d(TAG, "Parsing HE 2.0 pattern - patternCount: " + patternNum);

            for (int ind = 0; ind < patternNum; ) {
                Pattern pattern = new Pattern();
                JSONObject patternObject = patternArray.getJSONObject(ind);
                int patternRelativeTime = patternObject.getInt(PATTERN_KEY_PATTERN_ABS_TIME);
                pattern.mRelativeTime = patternRelativeTime;

                int patternDurationTime = relativeTimeLast + durationLast;
                if (ind > 0 && patternRelativeTime < patternDurationTime) {
                    Log.e(TAG,
                            "Pattern " + ind + " has invalid relative time: " + patternRelativeTime
                                    +
                                    " (must be >= " + patternDurationTime + ")");
                    return;
                }

                JSONArray eventArray = patternObject.getJSONArray(PATTERN_KEY_PATTERN);
                pattern.mEvent = new Event[eventArray.length()];

                if (DEBUG) {
                    Log.d(TAG, "Pattern " + ind + " - relativeTime: " + patternRelativeTime +
                            ", eventCount: " + eventArray.length());
                }

                int eventRelativeTime = -1;
                for (int event = 0; event < eventArray.length(); event++) {
                    JSONObject eventObject = eventArray.getJSONObject(event);
                    JSONObject eventTemp = eventObject.getJSONObject(EVENT_KEY_EVENT);

                    // Get type
                    String name = eventTemp.getString(EVENT_KEY_HE_TYPE);
                    int type;
                    if (TextUtils.equals(EVENT_TYPE_HE_CONTINUOUS_NAME, name)) {
                        type = CONTINUOUS_EVENT;
                        pattern.mEvent[event] = new ContinuousEvent();
                    } else if (TextUtils.equals(EVENT_TYPE_HE_TRANSIENT_NAME, name)) {
                        type = TRANSIENT_EVENT;
                        pattern.mEvent[event] = new TransientEvent();
                    } else {
                        // Error: unknown type
                        Log.e(TAG, "Pattern " + ind + ", event " + event +
                                " has unknown type: " + name);
                        isCompliance = false;
                        break;
                    }

                    int vibId = eventTemp.getInt(PATTERN_KEY_EVENT_VIB_ID);
                    pattern.mEvent[event].mVibId = (byte) vibId;

                    // Get RelativeTime
                    if (!eventTemp.has(EVENT_KEY_RELATIVE_TIME)) {
                        Log.e(TAG, "Pattern " + ind + ", event " + event +
                                " missing relativeTime parameter");
                        return;
                    } else {
                        relativeTimeLast = eventTemp.getInt(EVENT_KEY_RELATIVE_TIME);

                        if (event > 0 && relativeTimeLast < eventRelativeTime) {
                            Log.e(TAG, "Pattern " + ind + ", event " + event +
                                    " has invalid relative time: " + relativeTimeLast +
                                    " (must be >= " + eventRelativeTime + ")");
                            return;
                        }
                        eventRelativeTime = relativeTimeLast;
                    }

                    if (!isInTheInterval(relativeTimeLast, 0, 50000)) {
                        Log.e(TAG, "Pattern " + ind + ", event " + event +
                                " relativeTime must be between 0 and 50000 " +
                                "(value: " + relativeTimeLast + ")");
                        isCompliance = false;
                        break;
                    }

                    // Get Parameters
                    JSONObject parametersObject = eventTemp.getJSONObject(EVENT_KEY_HE_PARAMETERS);
                    int intensity = parametersObject.getInt(EVENT_KEY_HE_INTENSITY);
                    int frequency = parametersObject.getInt(EVENT_KEY_HE_FREQUENCY);

                    if (!isInTheInterval(intensity, 0, 100) || !isInTheInterval(frequency, 0,
                            100)) {
                        Log.e(TAG, "Pattern " + ind + ", event " + event +
                                " parameters out of range - intensity: " + intensity +
                                ", frequency: " + frequency + " (both must be between 0 and 100)");
                        isCompliance = false;
                        break;
                    }

                    pattern.mEvent[event].mType = type;
                    pattern.mEvent[event].mRelativeTime = relativeTimeLast;
                    pattern.mEvent[event].mIntensity = intensity;
                    pattern.mEvent[event].mFreq = frequency;

                    if (CONTINUOUS_EVENT == type) {
                        // Get Duration
                        if (!eventTemp.has(EVENT_KEY_DURATION)) {
                            Log.e(TAG, "Pattern " + ind + ", event " + event +
                                    " missing duration parameter");
                            return;
                        } else {
                            durationLast = eventTemp.getInt(EVENT_KEY_DURATION);
                        }

                        if (!isInTheInterval(durationLast, 0, 5000)) {
                            Log.e(TAG, "Pattern " + ind + ", event " + event +
                                    " duration must be less than 5000 " +
                                    "(value: " + durationLast + ")");
                            isCompliance = false;
                            break;
                        }

                        pattern.mEvent[event].mDuration = durationLast;

                        JSONArray curve = parametersObject.getJSONArray(EVENT_KEY_HE_CURVE);
                        ((ContinuousEvent) pattern.mEvent[event]).mPointNum = (byte) curve.length();
                        Point[] pointArray = new Point[curve.length()];

                        // Process points data
                        int prevPointTime = -1;
                        int i = 0;
                        int pointLastTime = 0;

                        for (; i < curve.length(); i++) {
                            JSONObject curveObject = curve.getJSONObject(i);
                            pointArray[i] = new Point();

                            int pointTime = curveObject.getInt(EVENT_KEY_HE_CURVE_POINT_TIME);
                            // Multiply by 100 to facilitate data transfer
                            int pointIntensity = (int) (curveObject.getDouble(
                                    EVENT_KEY_HE_INTENSITY) * 100);
                            int pointFrequency = curveObject.getInt(EVENT_KEY_HE_FREQUENCY);

                            if (i == 0 && pointTime != 0) {
                                Log.e(TAG, "Pattern " + ind + ", event " + event +
                                        " first curve point must have time=0 (value: " + pointTime
                                        + ")");
                                return;
                            }

                            if ((i > 0) && (pointTime < prevPointTime)) {
                                Log.e(TAG, "Pattern " + ind + ", event " + event +
                                        " curve point " + i + " time is not in order: " + pointTime
                                        +
                                        " (must be >= " + prevPointTime + ")");
                                return;
                            }
                            prevPointTime = pointTime;

                            pointArray[i].mTime = pointTime;
                            pointArray[i].mIntensity = pointIntensity;
                            pointArray[i].mFreq = pointFrequency;
                            pointLastTime = pointTime;
                        }

                        if (pointLastTime != durationLast) {
                            Log.e(TAG, "Pattern " + ind + ", event " + event +
                                    " last curve point time (" + pointLastTime +
                                    ") does not match duration (" + durationLast + ")");
                            return;
                        }

                        if (pointArray.length > 0) {
                            ((ContinuousEvent) pattern.mEvent[event]).mPoint = pointArray;
                        } else {
                            Log.e(TAG, "Pattern " + ind + ", event " + event +
                                    " continuous event has no curve points");
                            isCompliance = false;
                        }
                    }

                    if (!isCompliance) break;

                    if (DEBUG) {
                        Log.d(TAG, "Pattern " + ind + ", event " + event + " - type: " +
                                (type == CONTINUOUS_EVENT ? "continuous" : "transient") +
                                ", relativeTime: " + relativeTimeLast +
                                ", vibId: " + vibId);
                    }
                }

                // Compliance check
                if (!isCompliance) {
                    Log.e(TAG, "HE 2.0 pattern failed compliance check");
                    return;
                }

                patternList[ind] = pattern;
                ind++;

                if (ind >= HE2_0_PATTERN_WRAP_NUM * (wrapperIndex + 1)) {
                    Pattern[] patternWrapper = new Pattern[HE2_0_PATTERN_WRAP_NUM];
                    System.arraycopy(patternList, wrapperOffset, patternWrapper, 0,
                            HE2_0_PATTERN_WRAP_NUM);
                    sendPatternWrapper(seq, pid, heVersion, loop, interval,
                            amplitude, freq, patternNum, wrapperOffset, patternWrapper);
                    wrapperIndex++;
                    wrapperOffset = HE2_0_PATTERN_WRAP_NUM * wrapperIndex;
                }
            }

            if (wrapperOffset < patternList.length) {
                int endWrapperNum = patternList.length - wrapperOffset;
                Pattern[] patternWrapper = new Pattern[endWrapperNum];
                System.arraycopy(patternList, wrapperOffset, patternWrapper, 0,
                        patternWrapper.length);
                sendPatternWrapper(seq, pid, heVersion, loop, interval,
                        amplitude, freq, patternNum, wrapperOffset, patternWrapper);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing HE 2.0 pattern: " + e.getMessage(), e);
        }
    }

    /**
     * Applies a pattern HE effect with the specified parameters.
     *
     * @param patternString The pattern JSON string
     * @param loop          The loop count
     * @param interval      The interval between loops
     * @param amplitude     The amplitude value
     * @param freq          The frequency value
     */
    public void applyPatternHeWithString(@Nullable String patternString, int loop,
            int interval, int amplitude, int freq) {
        Log.d(TAG, "Applying pattern HE effect - loop: " + loop +
                ", interval: " + interval + ", amplitude: " + amplitude +
                ", freq: " + freq);

        if (loop < 1) {
            Log.e(TAG, "Loop count must be at least 1 (value: " + loop + ")");
            return;
        }

        try {
            JSONObject hapticObject = new JSONObject(patternString);

            int heVersion = 0;
            if (mAvailable) {
                JSONObject metaData = hapticObject.getJSONObject(HE_META_DATA_KEY);
                heVersion = metaData.getInt(HE_VERSION_KEY);
            }

            if (heVersion == 1) {
                int[] patternHeInfo = getSerializationDataHe_1_0(patternString);
                if (patternHeInfo == null) {
                    Log.e(TAG, "Failed to serialize HE 1.0 pattern");
                    return;
                }

                int len = patternHeInfo.length;
                try {
                    int[] realPatternHeInfo = new int[len + 1];
                    realPatternHeInfo[0] = 0x3; // 0x3: HE1.0 which support 16 curve points
                    System.arraycopy(patternHeInfo, 0, realPatternHeInfo, 1, patternHeInfo.length);

                    VibrationEffect createPatternHe =
                            RichTapVibrationEffect.createPatternHeWithParam(
                                    realPatternHeInfo, loop, interval, amplitude, freq);
                    VibrationAttributes atr = new VibrationAttributes.Builder().build();
                    CombinedVibration combinedEffect = CombinedVibration.createParallel(
                            createPatternHe);
                    mVibratorManager.vibrate(Process.myUid(), mPackageName,
                            combinedEffect, VIBRATE_REASON, atr);

                    Log.d(TAG, "Successfully applied HE 1.0 pattern");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply HE 1.0 pattern: " + e.getMessage(), e);
                }
            } else if (heVersion == 2) {
                int seq = mSeq.getAndIncrement();
                int pid = Process.myPid();
                parseAndSendDataHe_2_0(seq, pid, heVersion, loop, interval, amplitude, freq,
                        patternString);
                Log.d(TAG, "Successfully processed HE 2.0 pattern");
            } else {
                Log.e(TAG, "Unsupported HE version: " + heVersion);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying pattern HE effect: " + e.getMessage(), e);
        }
    }

    /**
     * Gets the real looper value.
     *
     * @param looper The input looper value
     * @return The real looper value
     */
    public int getRealLooper(int looper) {
        if (looper < 0) {
            if (looper == -1) {
                return Integer.MAX_VALUE;
            } else {
                return 0;
            }
        } else if (looper == 0) {
            return 1;
        } else {
            return looper;
        }
    }

    /**
     * Starts playing the effect with the specified loop count.
     *
     * @param loop The loop count
     */
    public void start(final int loop) {
        Log.d(TAG, "Starting playback - loop: " + loop);

        if (mEffect == null) {
            Log.e(TAG, "Effect is null, cannot start playback");
            return;
        }

        final int realLooper = getRealLooper(loop);
        if (realLooper < 0) {
            Log.e(TAG, "Invalid loop value: " + loop + " (resolved to " + realLooper + ")");
            return;
        }

        mExcutor.execute(() -> {
            Log.d(TAG, "Starting haptic playback");
            long startRunTime = System.currentTimeMillis();
            try {
                mStarted = true;
                String patternJson = mEffect.getPatternInfo();
                if (patternJson == null) {
                    Log.e(TAG, "Pattern is null, cannot play effect");
                    return;
                }
                applyPatternHeWithString(patternJson, realLooper, 0, 255, 0);
            } catch (Exception e) {
                Log.e(TAG, "Error during haptic playback: " + e.getMessage(), e);
            }
            long useTime = System.currentTimeMillis() - startRunTime;
            Log.d(TAG, "Haptic playback processing took " + useTime + "ms");
        });
    }

    /**
     * Starts playing the effect with the specified parameters.
     *
     * @param loop      The loop count (1 means no loop, > 1 means loop count, -1 means infinite
     *                  loop)
     * @param interval  The interval between loops (0-1000 ms)
     * @param amplitude The amplitude value (1-255)
     */
    public void start(final int loop, final int interval, final int amplitude) {
        Log.d(TAG, "Starting playback - loop: " + loop +
                ", interval: " + interval + ", amplitude: " + amplitude);

        boolean checkResult = checkParam(interval, amplitude, -1);

        if (!checkResult) {
            Log.e(TAG, "Invalid parameters for playback");
            return;
        }

        if (mEffect == null) {
            Log.e(TAG, "Effect is null, cannot start playback");
            return;
        }

        final int realLooper = getRealLooper(loop);
        if (realLooper < 0) {
            Log.e(TAG, "Invalid loop value: " + loop + " (resolved to " + realLooper + ")");
            return;
        }

        mExcutor.execute(() -> {
            Log.d(TAG, "Starting haptic playback");
            long startRunTime = System.currentTimeMillis();
            try {
                mStarted = true;
                String patternJson = mEffect.getPatternInfo();
                if (patternJson == null) {
                    Log.e(TAG, "Pattern is null, cannot play effect");
                    return;
                }
                applyPatternHeWithString(patternJson, realLooper, interval, amplitude, 0);
            } catch (Exception e) {
                Log.e(TAG, "Error during haptic playback: " + e.getMessage(), e);
            }
            long useTime = System.currentTimeMillis() - startRunTime;
            Log.d(TAG, "Haptic playback processing took " + useTime + "ms");
        });
    }

    /**
     * Starts playing the effect with the specified parameters.
     *
     * @param loop      The loop count (1 means no loop, > 1 means loop count, -1 means infinite
     *                  loop)
     * @param interval  The interval between loops (0-1000 ms)
     * @param amplitude The amplitude value (1-255)
     * @param freq      The frequency value
     */
    public void start(final int loop, final int interval, final int amplitude, final int freq) {
        Log.d(TAG, "Starting playback - loop: " + loop +
                ", interval: " + interval + ", amplitude: " + amplitude +
                ", freq: " + freq);

        boolean checkResult = checkParam(interval, amplitude, freq);

        if (!checkResult) {
            Log.e(TAG, "Invalid parameters for playback");
            return;
        }

        if (mEffect == null) {
            Log.e(TAG, "Effect is null, cannot start playback");
            return;
        }

        final int realLooper = getRealLooper(loop);
        if (realLooper < 0) {
            Log.e(TAG, "Invalid loop value: " + loop + " (resolved to " + realLooper + ")");
            return;
        }

        mExcutor.execute(() -> {
            Log.d(TAG, "Starting haptic playback");
            long startRunTime = System.currentTimeMillis();
            try {
                mStarted = true;
                String patternJson = mEffect.getPatternInfo();
                if (patternJson == null) {
                    Log.e(TAG, "Pattern is null, cannot play effect");
                    return;
                }
                applyPatternHeWithString(patternJson, realLooper, interval, amplitude, freq);
            } catch (Exception e) {
                Log.e(TAG, "Error during haptic playback: " + e.getMessage(), e);
            }
            long useTime = System.currentTimeMillis() - startRunTime;
            Log.d(TAG, "Haptic playback processing took " + useTime + "ms");
        });
    }

    /**
     * Checks if the parameters are valid.
     *
     * @param interval  The interval value
     * @param amplitude The amplitude value
     * @param freq      The frequency value
     * @return true if valid, false otherwise
     */
    private boolean checkParam(int interval, int amplitude, int freq) {
        if (interval < 0 && interval != -1) {
            Log.e(TAG, "Invalid interval: " + interval + " (must be >= 0 or -1)");
            return false;
        }

        if (freq < 0 && freq != -1) {
            Log.e(TAG, "Invalid frequency: " + freq + " (must be >= 0 or -1)");
            return false;
        }

        if ((amplitude < 0 && amplitude != -1) || (amplitude > 255)) {
            Log.e(TAG, "Invalid amplitude: " + amplitude + " (must be between 1-255 or -1)");
            return false;
        }

        return true;
    }

    /**
     * Applies runtime parameters to the current RichTap HE playback.
     *
     * @param interval The loop interval, or -1 to keep the current value
     * @param amplitude The amplitude, or -1 to keep the current value
     * @param freq The frequency, or -1 to keep the current value
     */
    public void applyPatternHeParam(final int interval, final int amplitude, final int freq) {
        Log.d(TAG, "Applying playback params - interval: " + interval
                + ", amplitude: " + amplitude + ", freq: " + freq);

        if (!checkParam(interval, amplitude, freq)) {
            Log.e(TAG, "Invalid parameters for playback update");
            return;
        }

        if (!mStarted) {
            Log.d(TAG, "Haptic player has not started");
            return;
        }

        mExcutor.execute(() -> {
            try {
                VibrationEffect createPatternHe = RichTapVibrationEffect.createPatternHeParameter(
                        interval, amplitude, freq);
                CombinedVibration combinedEffect = CombinedVibration.createParallel(createPatternHe);
                mVibratorManager.vibrate(Process.myUid(), mPackageName,
                        combinedEffect, VIBRATE_REASON, null);
                Log.d(TAG, "Applied haptic playback params");
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply haptic playback params: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Updates the current loop interval.
     *
     * @param interval The loop interval in milliseconds
     */
    public void updateInterval(int interval) {
        applyPatternHeParam(interval, -1, -1);
    }

    /**
     * Updates the current amplitude.
     *
     * @param amplitude The amplitude value
     */
    public void updateAmplitude(int amplitude) {
        applyPatternHeParam(-1, amplitude, -1);
    }

    /**
     * Updates the current frequency.
     *
     * @param freq The frequency value
     */
    public void updateFrequency(int freq) {
        applyPatternHeParam(-1, -1, freq);
    }

    /**
     * Updates runtime playback parameters.
     *
     * @param interval The loop interval, or -1 to keep the current value
     * @param amplitude The amplitude, or -1 to keep the current value
     * @param freq The frequency, or -1 to keep the current value
     */
    public void updateParameter(int interval, int amplitude, int freq) {
        applyPatternHeParam(interval, amplitude, freq);
    }

    /**
     * Stops playing the effect.
     */
    public void stop() {
        Log.d(TAG, "Stopping playback");

        if (mStarted) {
            mExcutor.execute(() -> {
                try {
                    VibrationEffect createPatternHe =
                            RichTapVibrationEffect.createPatternHeParameter(0, 0, 0);
                    CombinedVibration combinedEffect =
                            CombinedVibration.createParallel(createPatternHe);
                    mVibratorManager.vibrate(Process.myUid(), mPackageName,
                            combinedEffect, VIBRATE_REASON, null);
                    Log.d(TAG, "Playback stopped successfully");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to stop playback: " + e.getMessage(), e);
                }
            });
        } else {
            Log.w(TAG, "Nothing to stop - playback not started");
        }
    }

    /**
     * Abstract base class for vibration events.
     */
    abstract static class Event {
        int mType;
        int mLen;
        int mVibId;
        int mRelativeTime;
        int mIntensity;
        int mFreq;
        int mDuration;

        /**
         * Generates serialized data for this event.
         *
         * @return The serialized data
         */
        abstract int[] generateData();

        @Override
        public String toString() {
            return "Event{" +
                    "mType=" + mType +
                    ", mVibId=" + mVibId +
                    ", mRelativeTime=" + mRelativeTime +
                    ", mIntensity=" + mIntensity +
                    ", mFreq=" + mFreq +
                    ", mDuration=" + mDuration +
                    '}';
        }
    }

    /**
     * Class representing a transient vibration event.
     */
    static class TransientEvent extends Event {
        /**
         * Constructor.
         */
        TransientEvent() {
            mLen = 7;
        }

        @Override
        int[] generateData() {
            int[] data = new int[mLen];
            Arrays.fill(data, 0);
            data[0] = mType;
            data[1] = mLen - 2;
            data[2] = mVibId;
            data[3] = mRelativeTime;
            data[4] = mIntensity;
            data[5] = mFreq;
            data[6] = mDuration;
            return data;
        }
    }

    /**
     * Class representing a point in a continuous vibration event.
     */
    static class Point {
        int mTime;
        int mIntensity;
        int mFreq;
    }

    /**
     * Class representing a continuous vibration event.
     */
    static class ContinuousEvent extends Event {
        int mPointNum; // max 16
        Point[] mPoint;

        /**
         * Constructor.
         */
        ContinuousEvent() {
        }

        @Override
        int[] generateData() {
            int pointOffset = 8;
            int[] data = new int[pointOffset + mPointNum * 3];
            Arrays.fill(data, 0);

            data[0] = mType;
            data[1] = pointOffset + mPointNum * 3 - 2;
            data[2] = mVibId;
            data[3] = mRelativeTime;
            data[4] = mIntensity;
            data[5] = mFreq;
            data[6] = mDuration;
            data[7] = mPointNum;

            int offset = pointOffset;
            for (int i = 0; i < mPointNum; i++) {
                data[offset] = mPoint[i].mTime;
                offset += 1;
                data[offset] = mPoint[i].mIntensity;
                offset += 1;
                data[offset] = mPoint[i].mFreq;
                offset += 1;
            }

            return data;
        }

        @Override
        public String toString() {
            return "ContinuousEvent{" +
                    "mPointNum=" + mPointNum +
                    ", mPoint=" + Arrays.toString(mPoint) +
                    "} " + super.toString();
        }
    }

    /**
     * Class representing a vibration pattern.
     */
    static class Pattern {
        int mRelativeTime;
        Event[] mEvent;

        /**
         * Gets the length of all events in this pattern.
         *
         * @return The total event length
         */
        int getPatternEventLen() {
            int len = 0;

            for (Event event : mEvent) {
                if (event.mType == CONTINUOUS_EVENT) {
                    len += 8 + ((ContinuousEvent) event).mPointNum * 3;
                } else if (event.mType == TRANSIENT_EVENT) {
                    len += 7;
                }
            }

            return len;
        }

        /**
         * Gets the total data length for this pattern.
         *
         * @return The total data length
         */
        int getPatternDataLen() {
            int eventLen = getPatternEventLen();
            return 3 + eventLen;
        }

        /**
         * Generates serialized data for this pattern.
         *
         * @param index The pattern index
         * @return The serialized data
         */
        int[] generateSerializationPatternData(int index) {
            int dataLen = getPatternDataLen();
            int[] data = new int[dataLen];
            Arrays.fill(data, 0);

            data[0] = index;
            data[1] = mRelativeTime;
            data[2] = mEvent.length;

            int[] eventData;
            int offset = 3;

            for (Event event : mEvent) {
                eventData = event.generateData();
                System.arraycopy(eventData, 0, data, offset, eventData.length);
                offset += eventData.length;
            }

            return data;
        }
    }
}
