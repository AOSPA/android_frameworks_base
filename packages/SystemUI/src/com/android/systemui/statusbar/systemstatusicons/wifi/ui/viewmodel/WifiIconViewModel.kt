/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.systemstatusicons.wifi.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.getValue
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.lifecycle.HydratedActivatable
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel
import com.android.systemui.statusbar.systemstatusicons.SystemStatusIconsInCompose
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconViewModel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * View model for the wifi system status icon. Emits a wifi icon when wifi is enabled and should be
 * shown. This viewModel is active when [SystemStatusIconsInCompose] is enabled and replaces
 * [LocationBasedWifiViewModel].
 */
class WifiIconViewModel
@AssistedInject
constructor(@Assisted private val context: Context, wifiViewModel: WifiViewModel) :
    SystemStatusIconViewModel.Wifi, HydratedActivatable() {

    init {
        SystemStatusIconsInCompose.expectInNewMode()
    }

    override val slotName = context.getString(com.android.internal.R.string.status_bar_wifi)

    private val wifiIcon: WifiIcon by
        wifiViewModel.wifiIcon.hydratedStateOf(
            traceName = "SystemStatus.wifiIcon",
            initialValue = WifiIcon.Hidden,
        )

    override val wifiStandardIcon: Icon? by
        wifiViewModel.wifiStandardIcon.hydratedStateOf(
            traceName = "SystemStatus.wifiIcon.standardIcon",
            initialValue = null,
        )

    override val isActivityContainerVisible: Boolean by
        wifiViewModel.isActivityContainerVisible.hydratedStateOf(
            traceName = "SystemStatus.wifiIcon.activityContainerVisible",
            initialValue = false,
        )

    override val isActivityInVisible: Boolean by
        wifiViewModel.isActivityInViewVisible.hydratedStateOf(
            traceName = "SystemStatus.wifiIcon.activityInVisible",
            initialValue = false,
        )

    override val isActivityOutVisible: Boolean by
        wifiViewModel.isActivityOutViewVisible.hydratedStateOf(
            traceName = "SystemStatus.wifiIcon.activityOutVisible",
            initialValue = false,
        )

    override val visible: Boolean
        get() = wifiIcon is WifiIcon.Visible

    override val icon: Icon?
        get() = (wifiIcon as? WifiIcon.Visible)?.icon

    @AssistedFactory
    interface Factory {
        fun create(context: Context): WifiIconViewModel
    }
}
