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

package com.android.internal.lineage.health;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.lineage.app.LineageContextConstants;

public class HealthInterface {
    /**
     * No config set. This value is invalid and does not have any effects
     */
    public static final int MODE_NONE = 0;

    /**
     * Automatic config
     */
    public static final int MODE_AUTO = 1;

    /**
     * Manual config mode
     */
    public static final int MODE_MANUAL = 2;

    /**
     * Limit config mode
     */
    public static final int MODE_LIMIT = 3;

    private static final String TAG = "HealthInterface";
    private static IHealthInterface sService;
    private static HealthInterface sInstance;
    private Context mContext;
    private HealthInterface(Context context) {
        Context appContext = context.getApplicationContext();
        mContext = appContext == null ? context : appContext;
        sService = getService();
    }
    /**
     * Get or create an instance of the {@link lineageos.health.HealthInterface}
     *
     * @param context Used to get the service
     * @return {@link HealthInterface}
     */
    public static synchronized HealthInterface getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new HealthInterface(context);
        }
        return sInstance;
    }
    /** @hide **/
    public static IHealthInterface getService() {
        if (sService != null) {
            return sService;
        }
        IBinder b = ServiceManager.getService(LineageContextConstants.LINEAGE_HEALTH_INTERFACE);
        sService = IHealthInterface.Stub.asInterface(b);
        if (sService == null) {
            Log.e(TAG, "null health service, SAD!");
            return null;
        }
        return sService;
    }

    /**
     * @return true if service is valid
     */
    private boolean checkService() {
        if (sService == null) {
            Log.w(TAG, "not connected to LineageHardwareManagerService");
            return false;
        }
        return true;
    }

    /**
     * Returns whether charging control is supported
     *
     * @return true if charging control is supported
     */
    public boolean isChargingControlSupported() {
        try {
            return checkService() && sService.isChargingControlSupported();
        } catch (RemoteException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }

        return false;
    }

    /**
     * Returns whether charging control is supported
     *
     * @return true if charging control is supported
     */
    public static boolean isChargingControlSupported(Context context) {
        try {
            return getInstance(context).isChargingControlSupported();
        } catch (RuntimeException e) {
            Log.e(TAG, e.getLocalizedMessage(), e);
        }
        return false;
    }

    /**
     * Returns the charging control enabled status
     *
     * @return whether charging control has been enabled
     */
    public boolean getEnabled() {
        try {
            return checkService() && sService.getChargingControlEnabled();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Set charging control enable status
     *
     * @param enabled whether charging control should be enabled
     * @return true if the enabled status was successfully set
     */
    public boolean setEnabled(boolean enabled) {
        try {
            return checkService() && sService.setChargingControlEnabled(enabled);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns the current charging control mode
     *
     * @return id of the charging control mode
     */
    public int getMode() {
        try {
            return checkService() ? sService.getChargingControlMode() : MODE_NONE;
        } catch (RemoteException e) {
            return MODE_NONE;
        }
    }

    /**
     * Selects the new charging control mode
     *
     * @param mode the new charging control mode
     * @return true if the mode was successfully set
     */
    public boolean setMode(int mode) {
        try {
            return checkService() && sService.setChargingControlMode(mode);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the charging control start time
     *
     * @return the seconds of the day of the start time
     */
    public int getStartTime() {
        try {
            return checkService() ? sService.getChargingControlStartTime() : 0;
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
     * Sets the charging control start time
     *
     * @param time the seconds of the day of the start time
     * @return true if the start time was successfully set
     */
    public boolean setStartTime(int time) {
        try {
            return checkService() && sService.setChargingControlStartTime(time);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the charging control target time
     *
     * @return the seconds of the day of the target time
     */
    public int getTargetTime() {
        try {
            return checkService() ? sService.getChargingControlTargetTime() : 0;
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
     * Sets the charging control target time
     *
     * @param time the seconds of the day of the target time
     * @return true if the target time was successfully set
     */
    public boolean setTargetTime(int time) {
        try {
            return checkService() && sService.setChargingControlTargetTime(time);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Gets the charging control limit
     *
     * @return the charging control limit
     */
    public int getLimit() {
        try {
            return checkService() ? sService.getChargingControlLimit() : 100;
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
     * Sets the charging control limit
     *
     * @param limit the charging control limit
     * @return true if the limit was successfully set
     */
    public boolean setLimit(int limit) {
        try {
            return checkService() && sService.setChargingControlLimit(limit);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Resets the charging control setting to default
     *
     * @return true if the setting was successfully reset
     */
    public boolean reset() {
        try {
            return checkService() && sService.resetChargingControl();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns whether the device's battery control bypasses battery
     *
     * @return true if the charging control bypasses battery
     */
    public boolean allowFineGrainedSettings() {
        try {
            return checkService() && sService.allowFineGrainedSettings();
        } catch (RemoteException e) {
            return false;
        }
    }
}
