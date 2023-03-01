/*
 * Copyright (C) 2023 The LineageOS Project
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

package com.android.server.lineage;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;

import com.android.server.lineage.common.UserContentObserver;

import java.io.PrintWriter;

import android.provider.Settings;

public abstract class LineageBaseFeature {
    protected final Context mContext;
    protected final Handler mHandler;
    protected SettingsObserver mSettingsObserver;

    public LineageBaseFeature(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    public abstract void onStart();

    protected abstract void onSettingsChanged(Uri uri);

    public abstract void dump(PrintWriter pw);

    public void start() {
        if (mSettingsObserver == null) {
            mSettingsObserver = new SettingsObserver(mHandler);
            onStart();
        }
    }

    protected final void registerSettings(Uri... settings) {
        mSettingsObserver.register(settings);
    }

    protected final boolean getBoolean(String setting, boolean defaultValue) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                setting, (defaultValue ? 1 : 0), UserHandle.USER_CURRENT) == 1;
    }

    protected final void putBoolean(String setting, boolean value) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                setting, (value ? 1 : 0), UserHandle.USER_CURRENT);
    }

    protected final int getInt(String setting, int defaultValue) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                setting, defaultValue, UserHandle.USER_CURRENT);
    }

    protected final void putInt(String setting, int value) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                setting, value, UserHandle.USER_CURRENT);
    }

    protected final String getString(String setting) {
        return Settings.System.getStringForUser(mContext.getContentResolver(),
                setting, UserHandle.USER_CURRENT);
    }

    protected final void putString(String setting, String value) {
        Settings.System.putStringForUser(mContext.getContentResolver(),
                setting, value, UserHandle.USER_CURRENT);
    }

    public void onDestroy() {
        mSettingsObserver.unregister();
    }

    final class SettingsObserver extends UserContentObserver {

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void register(Uri... uris) {
            final ContentResolver cr = mContext.getContentResolver();
            for (Uri uri : uris) {
                cr.registerContentObserver(uri, false, this, UserHandle.USER_ALL);
            }

            observe();
        }

        public void unregister() {
            mContext.getContentResolver().unregisterContentObserver(this);
            unobserve();
        }

        @Override
        protected void update() {
            onSettingsChanged(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            onSettingsChanged(uri);
        }
    }
}
