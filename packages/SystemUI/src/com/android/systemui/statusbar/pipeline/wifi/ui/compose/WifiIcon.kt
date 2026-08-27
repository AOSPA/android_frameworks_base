/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.pipeline.shared.ui.composable.ActivityIndicators
import com.android.systemui.statusbar.shared.ui.compose.StatusBarIcon
import com.android.systemui.statusbar.systemstatusicons.ui.viewmodel.SystemStatusIconViewModel

/** Composable for displaying a wifi icon. */
@Composable
fun WifiIcon(viewModel: SystemStatusIconViewModel.Wifi, modifier: Modifier = Modifier) {
    val icon = viewModel.icon ?: return
    val standardIcon = viewModel.wifiStandardIcon
    Box(modifier = modifier) {
        if (viewModel.isActivityContainerVisible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    ActivityIndicators(
                        isActivityInVisible = viewModel.isActivityInVisible,
                        isActivityOutVisible = viewModel.isActivityOutVisible,
                        color = LocalContentColor.current,
                    )
                }
                WifiSignalWithStandard(icon = icon, standardIcon = standardIcon)
            }
        } else {
            WifiSignalWithStandard(icon = icon, standardIcon = standardIcon)
        }
    }
}

@Composable
private fun WifiSignalWithStandard(
    icon: Icon,
    standardIcon: Icon?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        StatusBarIcon(icon = icon)
        standardIcon?.let { StatusBarIcon(icon = it) }
    }
}
