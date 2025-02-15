/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health;

import android.content.Context;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.TimeZone;

public class Util {
    /**
     * Convert milliseconds to a string in the current locale's format.
     *
     * @param ms milliseconds from epoch
     * @return formatted time string in current time zone
     */
    static public String msToString(Context context, long ms) {
        return DateFormat.getTimeFormat(context).format(msToLocalTime(ms).getTime());
    }

    static private Calendar msToLocalTime(long ms) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(ms);
        return calendar;
    }

    /**
     * Convert milliseconds to a string in the format "HH:mm:ss".
     *
     * @param ms time in milliseconds, should be less than
     *           ${@link android.text.format.DateUtils#DAY_IN_MILLIS}
     * @return formatted time string in the format "HH:mm:ss"
     */
    static public String msToHMSString(long ms) {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
        Calendar calendar = Calendar.getInstance();
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(ms);
        return dateFormat.format(calendar.getTime());
    }

    /**
     * Convert the seconds of the day to UTC milliseconds from epoch.
     *
     * @param time seconds of the day
     * @return UTC milliseconds from epoch
     */
    static public long getTimeMillisFromSecondOfDay(int time) {
        ZoneId utcZone = ZoneOffset.UTC;
        LocalDate currentDate = LocalDate.now();
        LocalTime timeOfDay = LocalTime.ofSecondOfDay(time);

        ZonedDateTime zonedDateTime = ZonedDateTime.of(currentDate, timeOfDay,
                        ZoneId.systemDefault())
                .withZoneSameInstant(utcZone);
        return zonedDateTime.toInstant().toEpochMilli();
    }
}
