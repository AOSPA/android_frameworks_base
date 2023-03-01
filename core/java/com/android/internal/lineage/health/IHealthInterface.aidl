/**
 * Copyright (c) 2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.lineage.health;

/** @hide */
interface IHealthInterface {
    boolean isChargingControlSupported();

    boolean getChargingControlEnabled();
    boolean setChargingControlEnabled(boolean enabled);

    int getChargingControlMode();
    boolean setChargingControlMode(int mode);

    int getChargingControlStartTime();
    boolean setChargingControlStartTime(int time);

    int getChargingControlTargetTime();
    boolean setChargingControlTargetTime(int time);

    int getChargingControlLimit();
    boolean setChargingControlLimit(int limit);

    boolean resetChargingControl();
    boolean allowFineGrainedSettings();
}
