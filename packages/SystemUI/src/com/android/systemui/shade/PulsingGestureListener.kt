/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade

import android.content.Context
import android.database.ContentObserver
import android.graphics.Point
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.PowerManager
import android.os.UserHandle
import android.provider.Settings
import android.provider.Settings.System.GESTURE_DOUBLE_TAP
import android.provider.Settings.System.GESTURE_SINGLE_TAP
import android.provider.Settings.System.GESTURES_ENABLED
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dock.DockManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.DozeInteractor
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.LOW_PENALTY
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.tuner.TunerService
import com.android.systemui.tuner.TunerService.Tunable
import com.android.systemui.util.settings.SystemSettings
import java.io.PrintWriter
import javax.inject.Inject

/**
 * If tap and/or double tap to wake is enabled, this gestureListener will wake the display on
 * tap/double tap when the device is pulsing (AoD2) or transitioning to AoD. Taps are gated by the
 * proximity sensor and falsing manager.
 *
 * Touches go through the [NotificationShadeWindowViewController] when the device is dozing but the
 * screen is still ON and not in the true AoD display state. When the device is in the true AoD
 * display state, wake-ups are handled by [com.android.systemui.doze.DozeSensors].
 */
@SysUISingleton
class PulsingGestureListener
@Inject
constructor(
    @Main private val handler: Handler,
    private val context: Context,
    private val falsingManager: FalsingManager,
    private val dockManager: DockManager,
    private val powerInteractor: PowerInteractor,
    private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
    private val statusBarStateController: StatusBarStateController,
    private val shadeLogger: ShadeLogger,
    private val dozeInteractor: DozeInteractor,
    userTracker: UserTracker,
    systemSettings: SystemSettings,
    tunerService: TunerService,
    dumpManager: DumpManager
) : GestureDetector.SimpleOnGestureListener(), Dumpable {
    private var doubleTapEnabled = false
    private var singleTapEnabled = false
    private var customDoubleTapEnabled = false
    private var customSingleTapEnabled = false

    init {
        val tunable = Tunable { key, _ ->
            when (key) {
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE ->
                    doubleTapEnabled =
                        ambientDisplayConfiguration.doubleTapGestureEnabled(userTracker.userId)
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE ->
                    singleTapEnabled =
                        ambientDisplayConfiguration.tapGestureEnabled(userTracker.userId)
            }
        }
        tunerService.addTunable(
            tunable,
            Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
            Settings.Secure.DOZE_TAP_SCREEN_GESTURE
        )

        val settingsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val doubleTap = systemSettings.getInt(
                    GESTURE_DOUBLE_TAP,
                    context.resources.getInteger(
                            com.android.internal.R.integer.config_doubleTapDefault)
                )
                val singleTap = systemSettings.getInt(
                    GESTURE_SINGLE_TAP,
                    context.resources.getInteger(
                            com.android.internal.R.integer.config_singleTapDefault)
                )
                val gesturesEnabled = systemSettings.getInt(GESTURES_ENABLED, 1) == 1
                // WAKE_UP = 1; PULSE_AMBIENT = 2; from KeyHandler
                customDoubleTapEnabled = gesturesEnabled && (doubleTap == 1 || doubleTap == 2)
                customSingleTapEnabled = gesturesEnabled && (singleTap == 1 || singleTap == 2)
            }
        }
        systemSettings.registerContentObserverForUserSync(
                GESTURE_DOUBLE_TAP, settingsObserver, UserHandle.USER_ALL)
        systemSettings.registerContentObserverForUserSync(
                GESTURE_SINGLE_TAP, settingsObserver, UserHandle.USER_ALL)
        systemSettings.registerContentObserverForUserSync(
                GESTURES_ENABLED, settingsObserver, UserHandle.USER_ALL)
        settingsObserver.onChange(false) // trigger initial update

        dumpManager.registerDumpable(this)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        return onSingleTapUp(e.x, e.y)
    }

    fun onSingleTapUp(x: Float, y: Float): Boolean {
        val isNotDocked = !dockManager.isDocked
        shadeLogger.logSingleTapUp(statusBarStateController.isDozing, singleTapEnabled, isNotDocked)
        if (statusBarStateController.isDozing && singleTapEnabled && isNotDocked) {
            val proximityIsNotNear = !falsingManager.isProximityNear
            val isNotAFalseTap = !falsingManager.isFalseTap(LOW_PENALTY)
            shadeLogger.logSingleTapUpFalsingState(proximityIsNotNear, isNotAFalseTap)
            if (proximityIsNotNear && isNotAFalseTap) {
                shadeLogger.d("Single tap handled, requesting centralSurfaces.wakeUpIfDozing")
                dozeInteractor.setLastTapToWakePosition(Point(x.toInt(), y.toInt()))
                powerInteractor.wakeUpIfDozing("PULSING_SINGLE_TAP", PowerManager.WAKE_REASON_TAP)
            }

            return true
        }

        shadeLogger.d("onSingleTapUp event ignored")
        return false
    }

    /**
     * Receives [MotionEvent.ACTION_DOWN], [MotionEvent.ACTION_MOVE], and [MotionEvent.ACTION_UP]
     * motion events for a double tap.
     */
    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) {
            return false
        }

        return onDoubleTapEvent()
    }

    fun onDoubleTapEvent(): Boolean {
        // React to the [MotionEvent.ACTION_UP] event after double tap is detected. Falsing
        // checks MUST be on the ACTION_UP event.
        if (
            statusBarStateController.isDozing &&
                (doubleTapEnabled || singleTapEnabled ||
                    customDoubleTapEnabled || customSingleTapEnabled) &&
                !falsingManager.isProximityNear &&
                !falsingManager.isFalseDoubleTap
        ) {
            powerInteractor.wakeUpIfDozing("PULSING_DOUBLE_TAP", PowerManager.WAKE_REASON_TAP)
            return true
        }

        return false
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("singleTapEnabled=$singleTapEnabled")
        pw.println("doubleTapEnabled=$doubleTapEnabled")
        pw.println("customSingleTapEnabled=$customSingleTapEnabled")
        pw.println("customDoubleTapEnabled=$customDoubleTapEnabled")
        pw.println("isDocked=${dockManager.isDocked}")
        pw.println("isProxCovered=${falsingManager.isProximityNear}")
    }
}
