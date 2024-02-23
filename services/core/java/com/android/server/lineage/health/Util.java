/*
 * SPDX-FileCopyrightText: 2024-2025 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server.lineage.health;

import static java.time.format.FormatStyle.SHORT;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.TimeZone;

public class Util {
    private static final DateTimeFormatter mFormatter = DateTimeFormatter.ofLocalizedTime(SHORT);

    /**
     * Convert milliseconds to a string in the format "hh:mm:ss a".
     *
     * @param ms milliseconds from epoch
     * @return formatted time string in current time zone
     */
    static public String msToString(long ms) {
        final SimpleDateFormat dateFormat = new SimpleDateFormat("hh:mm:ss a");
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(ms);
        return dateFormat.format(calendar.getTime());
    }

    /**
     * Convert seconds of the day to a string in the format "hh:mm:ss".
     * in UTC.
     *
     * @param ms milliseconds from epoch
     * @return formatted time string in UTC time zone
     */
    static public String msToUTCString(long ms) {
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
