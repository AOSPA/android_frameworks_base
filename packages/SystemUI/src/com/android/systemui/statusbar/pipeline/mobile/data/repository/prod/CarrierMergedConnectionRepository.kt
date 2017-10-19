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

// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
// QTI_BEGIN: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
 * Copyright (c) 2023-2024 Qualcomm Innovation Center, Inc. All rights reserved.
// QTI_END: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyManager
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
import com.qti.extphone.NrIconType
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * A repository implementation for a carrier merged (aka VCN) network. A carrier merged network is
 * delivered to SysUI as a wifi network (see [WifiNetworkModel.CarrierMerged], but is visually
 * displayed as a mobile network triangle.
 *
 * See [android.net.wifi.WifiInfo.isCarrierMerged] for more information.
 *
 * See [MobileConnectionRepositoryImpl] for a repository implementation of a typical mobile
 * connection.
 */
class CarrierMergedConnectionRepository(
    override val subId: Int,
    override val tableLogBuffer: TableLogBuffer,
    private val telephonyManager: TelephonyManager,
    private val bgContext: CoroutineContext,
    @Background private val scope: CoroutineScope,
    val wifiRepository: WifiRepository,
) : MobileConnectionRepository {
    init {
        if (telephonyManager.subscriptionId != subId) {
            throw IllegalStateException(
                "CarrierMergedRepo: TelephonyManager should be created with subId($subId). " +
                    "Found ${telephonyManager.subscriptionId} instead."
            )
        }
    }

    /**
     * Outputs the carrier merged network to use, or null if we don't have a valid carrier merged
     * network.
     */
    private val network: Flow<WifiNetworkModel.CarrierMerged?> =
        combine(
            wifiRepository.isWifiEnabled,
            wifiRepository.isWifiDefault,
            wifiRepository.wifiNetwork,
        ) { isEnabled, isDefault, network ->
            when {
                !isEnabled -> null
                !isDefault -> null
                network !is WifiNetworkModel.CarrierMerged -> null
                network.subscriptionId != subId -> {
                    Log.w(
                        TAG,
                        "Connection repo subId=$subId " +
                            "does not equal wifi repo subId=${network.subscriptionId}; " +
                            "not showing carrier merged",
                    )
                    null
                }
                else -> network
            }
        }

    override val cdmaRoaming: StateFlow<Boolean> = MutableStateFlow(ROAMING).asStateFlow()

    override val networkName: StateFlow<NetworkNameModel> =
        network
            // The SIM operator name should be the same throughout the lifetime of a subId, **but**
            // it may not be available when this repo is created because it takes time to load. To
            // be safe, we re-fetch it each time the network has changed.
            .map { NetworkNameModel.SimDerived(telephonyManager.simOperatorName) }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                NetworkNameModel.SimDerived(telephonyManager.simOperatorName),
            )

    override val carrierName: StateFlow<NetworkNameModel> = networkName

    override val numberOfLevels: StateFlow<Int> =
        wifiRepository.wifiNetwork
            .map {
                if (it is WifiNetworkModel.CarrierMerged) {
                    it.numberOfLevels
                } else {
                    DEFAULT_NUM_LEVELS
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DEFAULT_NUM_LEVELS)

    override val primaryLevel =
        network
            .map { it?.level ?: SIGNAL_STRENGTH_NONE_OR_UNKNOWN }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val cdmaLevel =
        network
            .map { it?.level ?: SIGNAL_STRENGTH_NONE_OR_UNKNOWN }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val dataActivityDirection = wifiRepository.wifiActivity

    override val resolvedNetworkType =
        network
            .map {
                if (it != null) {
                    ResolvedNetworkType.CarrierMergedNetworkType
                } else {
                    ResolvedNetworkType.UnknownNetworkType
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                ResolvedNetworkType.UnknownNetworkType,
            )

    override val dataConnectionState =
        network
            .map {
                if (it != null) {
                    DataConnectionState.Connected
                } else {
                    DataConnectionState.Disconnected
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DataConnectionState.Disconnected)

    override val isRoaming = MutableStateFlow(false).asStateFlow()
    override val carrierId = MutableStateFlow(INVALID_SUBSCRIPTION_ID).asStateFlow()
    override val inflateSignalStrength = MutableStateFlow(false).asStateFlow()
    override val allowNetworkSliceIndicator = MutableStateFlow(false).asStateFlow()
    override val isEmergencyOnly = MutableStateFlow(false).asStateFlow()
    override val operatorAlphaShort = MutableStateFlow(null).asStateFlow()
    override val isInService = MutableStateFlow(true).asStateFlow()
    override val isNonTerrestrial = MutableStateFlow(false).asStateFlow()
    override val isGsm = MutableStateFlow(false).asStateFlow()
    override val carrierNetworkChangeActive = MutableStateFlow(false).asStateFlow()
    override val satelliteLevel = MutableStateFlow(0)
    override val imsState = MutableStateFlow(ImsStateModel()).asStateFlow()

    /**
     * Carrier merged connections happen over wifi but are displayed as a mobile triangle. Because
     * they occur over wifi, it's possible to have a valid carrier merged connection even during
     * airplane mode. See b/291993542.
     */
    override val isAllowedDuringAirplaneMode = MutableStateFlow(true).asStateFlow()

    /**
     * It's not currently considered possible that a carrier merged network can have these
     * prioritized capabilities. If we need to track them, we can add the same check as is in
     * [MobileConnectionRepositoryImpl].
     */
    override val hasPrioritizedNetworkCapabilities = MutableStateFlow(false).asStateFlow()

    override val dataEnabled: StateFlow<Boolean> = wifiRepository.isWifiEnabled

// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
    override val lteRsrpLevel = MutableStateFlow(SIGNAL_STRENGTH_NONE_OR_UNKNOWN)
    override val voiceNetworkType = MutableStateFlow(TelephonyManager.NETWORK_TYPE_UNKNOWN)
    override val dataNetworkType = MutableStateFlow(TelephonyManager.NETWORK_TYPE_UNKNOWN)
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
    override val nrIconType = MutableStateFlow(NrIconType.TYPE_NONE)
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
    override val is6Rx = MutableStateFlow(false)
// QTI_END: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
    override val dataRoamingEnabled = MutableStateFlow(true).asStateFlow()
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
    override val originNetworkType = MutableStateFlow(TelephonyManager.NETWORK_TYPE_UNKNOWN)
    override val voiceCapable = MutableStateFlow(false)
    override val videoCapable = MutableStateFlow(false)
    override val imsRegistered = MutableStateFlow(false)
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
    override val imsRegistrationTech = MutableStateFlow(REGISTRATION_TECH_NONE)
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
// QTI_BEGIN: 2023-06-26: Telephony: Separate exclamation mark display for mobile network
    override val isConnectionFailed = MutableStateFlow(false)
// QTI_END: 2023-06-26: Telephony: Separate exclamation mark display for mobile network
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
    override val ciwlanAvailable = MutableStateFlow(false)
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature

    override suspend fun isInEcmMode(): Boolean =
        withContext(bgContext) { telephonyManager.emergencyCallbackMode }

    companion object {
        // Carrier merged is never roaming
        private const val ROAMING = false
    }

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        private val telephonyManager: TelephonyManager,
        @Background private val bgContext: CoroutineContext,
        @Background private val scope: CoroutineScope,
        private val wifiRepository: WifiRepository,
    ) {
        fun build(subId: Int, mobileLogger: TableLogBuffer): MobileConnectionRepository {
            return CarrierMergedConnectionRepository(
                subId,
                mobileLogger,
                telephonyManager.createForSubscriptionId(subId),
                bgContext,
                scope,
                wifiRepository,
            )
        }
    }
}

private const val TAG = "CarrierMergedConnectionRepository"
