/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.mobile.domain.interactor

import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.util.nameTag
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileIconCustomizationMode
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

fun BuildScope.MobileIconInteractorKairosAdapter(
    kairosImpl: MobileIconInteractorKairos
): MobileIconInteractor =
    with(kairosImpl) {
        MobileIconInteractorKairosAdapter(
            subscriptionId = subscriptionId,
            tableLogBuffer = tableLogBuffer,
            activity =
                activity.toStateFlow(
                    name =
                        nameTag {
                            "MobileIconInteractorKairosAdapter(subId=$subscriptionId).activity"
                        }
                ),
            mobileIsDefault =
                mobileIsDefault.toStateFlow(
                    name =
                        nameTag {
                            "MobileIconInteractorKairosAdapter(subId=$subscriptionId).mobileIsDefault"
                        }
                ),
            isDataConnected =
                isDataConnected.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isDataConnected"
                    }
                ),
            isInService =
                isInService.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isInService"
                    }
                ),
            isEmergencyOnly =
                isEmergencyOnly.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isEmergencyOnly"
                    }
                ),
            isDataEnabled =
                isDataEnabled.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isDataEnabled"
                    }
                ),
            alwaysShowDataRatIcon =
                alwaysShowDataRatIcon.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).alwaysShowDataRatIcon"
                    }
                ),
            signalLevelIcon =
                signalLevelIcon.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).signalLevelIcon"
                    }
                ),
            networkTypeIconGroup =
                networkTypeIconGroup.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).networkTypeIconGroup"
                    }
                ),
            showSliceAttribution =
                showSliceAttribution.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).showSliceAttribution"
                    }
                ),
            isNonTerrestrial =
                isNonTerrestrial.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isNonTerrestrial"
                    }
                ),
            networkName =
                networkName.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).networkName"
                    }
                ),
            carrierName =
                carrierName.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).carrierName"
                    }
                ),
            isSingleCarrier =
                isSingleCarrier.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isSingleCarrier"
                    }
                ),
            isRoaming =
                isRoaming.toStateFlow(
                    nameTag { "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isRoaming" }
                ),
            isRoamingForceHidden =
                isRoamingForceHidden.toStateFlow(
                    name =
                        nameTag {
                            "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isRoamingForceHidden"
                        }
                ),
            isForceHidden =
                isForceHidden.toStateFlow(
                    name =
                        nameTag {
                            "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isForceHidden"
                        }
                ),
            isMobileHd =
                isMobileHd.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isMobileHd"
                    }
                ),
            isMobileHdForceHidden =
                isMobileHdForceHidden.toStateFlow(
                    name =
                        nameTag {
                            "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isMobileHdForceHidden"
                        }
                ),
            isVoWifi =
                isVoWifi.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isVoWifi"
                    }
                ),
            isVoWifiForceHidden =
                isVoWifiForceHidden.toStateFlow(
                    name =
                        nameTag {
                            "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isVoWifiForceHidden"
                        }
                ),
            isAllowedDuringAirplaneMode =
                isAllowedDuringAirplaneMode.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).isAllowedDuringAirplaneMode"
                    }
                ),
            carrierNetworkChangeActive =
                carrierNetworkChangeActive.toStateFlow(
                    nameTag {
                        "MobileIconInteractorKairosAdapter(subId=$subscriptionId).carrierNetworkChangeActive"
                    }
                ),
        )
    }

private class MobileIconInteractorKairosAdapter(
    override val subscriptionId: Int,
    override val tableLogBuffer: TableLogBuffer,
    override val activity: StateFlow<DataActivityModel>,
    override val mobileIsDefault: StateFlow<Boolean>,
    override val isDataConnected: StateFlow<Boolean>,
    override val isInService: StateFlow<Boolean>,
    override val isEmergencyOnly: StateFlow<Boolean>,
    override val isDataEnabled: StateFlow<Boolean>,
    override val alwaysShowDataRatIcon: StateFlow<Boolean>,
    override val signalLevelIcon: StateFlow<SignalIconModel>,
    override val networkTypeIconGroup: StateFlow<NetworkTypeIconModel>,
    override val showSliceAttribution: StateFlow<Boolean>,
    override val isNonTerrestrial: StateFlow<Boolean>,
    override val networkName: StateFlow<NetworkNameModel>,
    override val carrierName: StateFlow<String>,
    override val isSingleCarrier: StateFlow<Boolean>,
    override val isRoaming: StateFlow<Boolean>,
    override val isRoamingForceHidden: StateFlow<Boolean>,
    override val isForceHidden: StateFlow<Boolean>,
    override val isMobileHd: StateFlow<Boolean>,
    override val isMobileHdForceHidden: StateFlow<Boolean>,
    override val isVoWifi: StateFlow<Boolean>,
    override val isVoWifiForceHidden: StateFlow<Boolean>,
    override val isAllowedDuringAirplaneMode: StateFlow<Boolean>,
    override val carrierNetworkChangeActive: StateFlow<Boolean>,
) : MobileIconInteractor {
    override val isConnectionFailed = MutableStateFlow(false)
    override val customizedNetworkName = MutableStateFlow(NetworkNameModel.IntentDerived("demo mode"))
    override val customizedCarrierName = MutableStateFlow("demo mode")
    override val customizedIcon = MutableStateFlow(null)
    override val voWifiAvailable = MutableStateFlow(false)
    override val showVowifiIcon = MutableStateFlow(false)
    override val imsInfo = MutableStateFlow(MobileIconCustomizationMode())
    override val showVolteIcon = MutableStateFlow(false)
    override val networkTypeIconCustomization = MutableStateFlow(MobileIconCustomizationMode())
    override val hideNoInternetState = MutableStateFlow(false)
    override val alwaysUseRsrpLevelForLte = MutableStateFlow(false)
}
