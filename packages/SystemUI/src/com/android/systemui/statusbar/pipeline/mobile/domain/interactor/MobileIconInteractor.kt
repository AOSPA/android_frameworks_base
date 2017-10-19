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
package com.android.systemui.statusbar.pipeline.mobile.domain.interactor
import android.content.Context
import com.android.internal.telephony.flags.Flags
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
import android.telephony.CarrierConfigManager
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support side car 5G icon
import android.telephony.TelephonyDisplayInfo
// QTI_END: 2023-03-02: Android_UI: SystemUI: Support side car 5G icon
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
import android.telephony.TelephonyManager
// QTI_END: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.graph.SignalDrawable
import com.android.settingslib.mobile.MobileIconCarrierIdOverrides
import com.android.settingslib.mobile.MobileIconCarrierIdOverridesImpl
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support side car 5G icon
import com.android.settingslib.mobile.MobileMappings
// QTI_END: 2023-03-02: Android_UI: SystemUI: Support side car 5G icon
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Connected
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileIconCustomizationMode
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support side car 5G icon
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
// QTI_END: 2023-03-02: Android_UI: SystemUI: Support side car 5G icon
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel.DefaultIcon
import com.android.systemui.statusbar.pipeline.mobile.domain.model.NetworkTypeIconModel.OverriddenIcon
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.satellite.ui.model.SatelliteIconModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
import com.android.systemui.statusbar.policy.FiveGServiceClient.FiveGServiceState
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
import com.android.systemui.util.CarrierNameCustomization
// QTI_END: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
// QTI_BEGIN: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
import kotlinx.coroutines.flow.mapLatest
// QTI_END: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
import kotlinx.coroutines.flow.stateIn
interface MobileIconInteractor {
    /** The table log created for this connection */
    val tableLogBuffer: TableLogBuffer
    /** The current mobile data activity */
    val activity: Flow<DataActivityModel>
    /** See [MobileConnectionsRepository.mobileIsDefault]. */
    val mobileIsDefault: Flow<Boolean>
    /**
     * True when telephony tells us that the data state is CONNECTED. See
     * [android.telephony.TelephonyCallback.DataConnectionStateListener] for more details. We
     * consider this connection to be serving data, and thus want to show a network type icon, when
     * data is connected. Other data connection states would typically cause us not to show the icon
     */
    val isDataConnected: Flow<Boolean>

// QTI_BEGIN: 2023-06-26: Telephony: Separate exclamation mark display for mobile network
    /** Only true if mobile is the cellular transport but is not validated, otherwise false */
    val isConnectionFailed: Flow<Boolean>

// QTI_END: 2023-06-26: Telephony: Separate exclamation mark display for mobile network
    /** True if we consider this connection to be in service, i.e. can make calls */
    val isInService: Flow<Boolean>

    /** True if this connection is emergency only */
    val isEmergencyOnly: Flow<Boolean>

    /** Observable for the data enabled state of this connection */
    val isDataEnabled: Flow<Boolean>

    /** True if the RAT icon should always be displayed and false otherwise. */
    val alwaysShowDataRatIcon: Flow<Boolean>

    /** Canonical representation of the current mobile signal strength as a triangle. */
    val signalLevelIcon: Flow<SignalIconModel>

    /** Observable for RAT type (network type) indicator */
    val networkTypeIconGroup: Flow<NetworkTypeIconModel>

    /** Whether or not to show the slice attribution */
    val showSliceAttribution: Flow<Boolean>

    /** True if this connection is satellite-based */
    val isNonTerrestrial: Flow<Boolean>

    /**
     * Provider name for this network connection. The name can be one of 3 values:
     * 1. The default network name, if one is configured
     * 2. A derived name based off of the intent [ACTION_SERVICE_PROVIDERS_UPDATED]
     * 3. Or, in the case where the repository sends us the default network name, we check for an
     *    override in [connectionInfo.operatorAlphaShort], a value that is derived from
     *    [ServiceState]
     */
    val networkName: Flow<NetworkNameModel>

    /**
     * Provider name for this network connection. The name can be one of 3 values:
     * 1. The default network name, if one is configured
     * 2. A name provided by the [SubscriptionModel] of this network connection
     * 3. Or, in the case where the repository sends us the default network name, we check for an
     *    override in [connectionInfo.operatorAlphaShort], a value that is derived from
     *    [ServiceState]
     *
     * TODO(b/296600321): De-duplicate this field with [networkName] after determining the data
     *   provided is identical
     */
    val carrierName: Flow<String>

    /** True if there is only one active subscription. */
    val isSingleCarrier: Flow<Boolean>

    /**
     * True if this connection is considered roaming. The roaming bit can come from [ServiceState],
     * or directly from the telephony manager's CDMA ERI number value. Note that we don't consider a
     * connection to be roaming while carrier network change is active
     */
    val isRoaming: Flow<Boolean>

    /** See [MobileIconsInteractor.isRoamingForceHidden]. */
    val isRoamingForceHidden: Flow<Boolean>

    /** See [MobileIconsInteractor.isForceHidden]. */
    val isForceHidden: Flow<Boolean>
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon

    /** True if the rsrp level should be preferred over the primary level for LTE. */
    val alwaysUseRsrpLevelForLte: Flow<Boolean>

// QTI_END: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
    /** See [MobileConnectionRepository.isAllowedDuringAirplaneMode]. */
    val isAllowedDuringAirplaneMode: Flow<Boolean>

    /** True when in carrier network change mode */
    val carrierNetworkChangeActive: Flow<Boolean>
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
    /** True if the no internet icon should be hidden.  */
    val hideNoInternetState: Flow<Boolean>
// QTI_END: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization

    val networkTypeIconCustomization: Flow<MobileIconCustomizationMode>
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon

    val imsInfo: Flow<MobileIconCustomizationMode>

    val showVolteIcon: Flow<Boolean>
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon

    val showVowifiIcon: Flow<Boolean>

    val voWifiAvailable: Flow<Boolean>
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature

    val customizedIcon: Flow<SignalIconModel?>
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization

    val customizedCarrierName: Flow<String>

    val customizedNetworkName: Flow<NetworkNameModel>
// QTI_END: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization

    /** True when VoLTE/VONR available */
    val isMobileHd: Flow<Boolean>

    /** See [MobileIconsInteractor.isMobileHdForceHidden]. */
    val isMobileHdForceHidden: Flow<Boolean>

    /** True when VoWifi available */
    val isVoWifi: Flow<Boolean>

    /** See [MobileIconsInteractor.isVoWifiForceHidden]. */
    val isVoWifiForceHidden: Flow<Boolean>
}
/** Interactor for a single mobile connection. This connection _should_ have one subscription ID */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
class MobileIconInteractorImpl(
    @Background scope: CoroutineScope,
    defaultSubscriptionHasDataEnabled: StateFlow<Boolean>,
    override val alwaysShowDataRatIcon: StateFlow<Boolean>,
    alwaysUseCdmaLevel: StateFlow<Boolean>,
    override val isSingleCarrier: StateFlow<Boolean>,
    override val mobileIsDefault: StateFlow<Boolean>,
    defaultMobileIconMapping: StateFlow<Map<String, MobileIconGroup>>,
    defaultMobileIconGroup: StateFlow<MobileIconGroup>,
    isDefaultConnectionFailed: StateFlow<Boolean>,
    override val isForceHidden: Flow<Boolean>,
    override val isRoamingForceHidden: Flow<Boolean>,
    override val isMobileHdForceHidden: Flow<Boolean>,
    override val isVoWifiForceHidden: Flow<Boolean>,
    connectionRepository: MobileConnectionRepository,
    private val context: Context,
    val carrierIdOverrides: MobileIconCarrierIdOverrides = MobileIconCarrierIdOverridesImpl(),
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
    override val alwaysUseRsrpLevelForLte: StateFlow<Boolean>,
    override val hideNoInternetState: StateFlow<Boolean>,
// QTI_END: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
    networkTypeIconCustomizationFlow: StateFlow<MobileIconCustomizationMode>,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
    override val showVolteIcon: StateFlow<Boolean>,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
    override val showVowifiIcon: StateFlow<Boolean>,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
    private val defaultDataSubId: StateFlow<Int?>,
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
    ddsIcon: StateFlow<SignalIconModel?>,
    crossSimdisplaySingnalLevel: StateFlow<Boolean>,
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
    carrierNameCustomization: CarrierNameCustomization,
// QTI_END: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
) : MobileIconInteractor {
    override val tableLogBuffer: TableLogBuffer = connectionRepository.tableLogBuffer
    override val activity = connectionRepository.dataActivityDirection
    override val isDataEnabled: StateFlow<Boolean> = connectionRepository.dataEnabled
    override val carrierNetworkChangeActive: StateFlow<Boolean> =
        connectionRepository.carrierNetworkChangeActive
    // True if there exists _any_ icon override for this carrierId. Note that overrides can include
    // any or none of the icon groups defined in MobileMappings, so we still need to check on a
    // per-network-type basis whether or not the given icon group is overridden
    private val carrierIdIconOverrideExists =
        connectionRepository.carrierId
            .map { carrierIdOverrides.carrierIdEntryExists(it) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val networkName =
        combine(connectionRepository.operatorAlphaShort, connectionRepository.networkName) {
                operatorAlphaShort,
                networkName ->
                if (networkName is NetworkNameModel.Default && operatorAlphaShort != null) {
                    NetworkNameModel.IntentDerived(operatorAlphaShort)
                } else {
                    networkName
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                connectionRepository.networkName.value,
            )
    override val carrierName =
        combine(connectionRepository.operatorAlphaShort, connectionRepository.carrierName) {
                operatorAlphaShort,
                networkName ->
                if (networkName is NetworkNameModel.Default && operatorAlphaShort != null) {
                    operatorAlphaShort
                } else {
                    networkName.name
                }
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                connectionRepository.carrierName.value.name,
            )

// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
    private val signalStrengthCustomization: StateFlow<MobileIconCustomizationMode> =
        combine(
            alwaysUseRsrpLevelForLte,
            connectionRepository.lteRsrpLevel,
            connectionRepository.voiceNetworkType,
            connectionRepository.dataNetworkType,
        ) { alwaysUseRsrpLevelForLte, lteRsrpLevel, voiceNetworkType, dataNetworkType ->
            MobileIconCustomizationMode(
                alwaysUseRsrpLevelForLte = alwaysUseRsrpLevelForLte,
                lteRsrpLevel = lteRsrpLevel,
                voiceNetworkType = voiceNetworkType,
                dataNetworkType = dataNetworkType,
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
// QTI_BEGIN: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
    override val customizedCarrierName =
        combine(
            carrierName,
            connectionRepository.nrIconType,
            connectionRepository.dataNetworkType,
            connectionRepository.voiceNetworkType,
            connectionRepository.isInService,
        ) { carrierName, nrIconType, dataNetworkType, voiceNetworkType, isInService ->
            carrierNameCustomization.getCustomizeCarrierNameModern(connectionRepository.subId,
                carrierName, true, nrIconType, dataNetworkType, voiceNetworkType, isInService)
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            connectionRepository.carrierName.value.name
        )

    override val customizedNetworkName =
        networkName
            .map {
                val customizationName = carrierNameCustomization.getCustomizeCarrierNameModern(
                    connectionRepository.subId, it.name, false, 0, 0, 0, false)
                NetworkNameModel.IntentDerived(customizationName)
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                connectionRepository.networkName.value
            )

// QTI_END: 2024-03-10: Android_UI: SystemUI: Readapt the ShadeCarrier SPN display customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
    override val isRoaming: StateFlow<Boolean> =
        combine(
            connectionRepository.carrierNetworkChangeActive,
            connectionRepository.isGsm,
            connectionRepository.isRoaming,
            connectionRepository.cdmaRoaming,
        ) { carrierNetworkChangeActive, isGsm, isRoaming, cdmaRoaming ->
            if (carrierNetworkChangeActive) {
                false
            } else if (isGsm) {
                isRoaming
            } else {
                cdmaRoaming
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
    private val isDefaultDataSub = defaultDataSubId
        .mapLatest { connectionRepository.subId == it }
// QTI_END: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
        .distinctUntilChanged()
        .logDiffsForTable(
            tableLogBuffer = tableLogBuffer,
            columnPrefix = "",
            columnName = "isDefaultDataSub",
            initialValue = connectionRepository.subId == defaultDataSubId.value,
        )
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            connectionRepository.subId == defaultDataSubId.value
        )

// QTI_END: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
    override val networkTypeIconCustomization: StateFlow<MobileIconCustomizationMode> =
        combine(
            networkTypeIconCustomizationFlow,
            isDataEnabled,
            connectionRepository.dataRoamingEnabled,
            isRoaming,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
            isDefaultDataSub,
        ){ state, mobileDataEnabled, dataRoamingEnabled, isRoaming, isDefaultDataSub ->
// QTI_END: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
            MobileIconCustomizationMode(
                isRatCustomization = state.isRatCustomization,
                alwaysShowNetworkTypeIcon = state.alwaysShowNetworkTypeIcon,
                ddsRatIconEnhancementEnabled = state.ddsRatIconEnhancementEnabled,
                nonDdsRatIconEnhancementEnabled = state.nonDdsRatIconEnhancementEnabled,
                mobileDataEnabled = mobileDataEnabled,
                dataRoamingEnabled = dataRoamingEnabled,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
                isDefaultDataSub = isDefaultDataSub,
// QTI_END: 2023-04-24: Android_UI: SystemUI: Add defaultDataSub for DDS RAT customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
                isRoaming = isRoaming
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
    private val mobileIconCustomization: StateFlow<MobileIconCustomizationMode> =
        combine(
            signalStrengthCustomization,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
            connectionRepository.nrIconType,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
            connectionRepository.is6Rx,
// QTI_END: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
            networkTypeIconCustomization,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
            connectionRepository.originNetworkType,
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
        ) { signalStrengthCustomization, nrIconType, is6Rx, networkTypeIconCustomization,
// QTI_END: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
            originNetworkType ->
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
            MobileIconCustomizationMode(
                dataNetworkType = signalStrengthCustomization.dataNetworkType,
                voiceNetworkType = signalStrengthCustomization.voiceNetworkType,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
                fiveGServiceState = FiveGServiceState(nrIconType, is6Rx, context),
// QTI_END: 2024-05-21: Android_UI: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
                isRatCustomization = networkTypeIconCustomization.isRatCustomization,
                alwaysShowNetworkTypeIcon =
                    networkTypeIconCustomization.alwaysShowNetworkTypeIcon,
                ddsRatIconEnhancementEnabled =
                    networkTypeIconCustomization.ddsRatIconEnhancementEnabled,
                nonDdsRatIconEnhancementEnabled =
                    networkTypeIconCustomization.nonDdsRatIconEnhancementEnabled,
                mobileDataEnabled = networkTypeIconCustomization.mobileDataEnabled,
                dataRoamingEnabled = networkTypeIconCustomization.dataRoamingEnabled,
                isDefaultDataSub = networkTypeIconCustomization.isDefaultDataSub,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
                isRoaming = networkTypeIconCustomization.isRoaming,
                originNetworkType = originNetworkType,
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
    override val imsInfo: StateFlow<MobileIconCustomizationMode> =
        combine(
            connectionRepository.voiceNetworkType,
            connectionRepository.originNetworkType,
            connectionRepository.voiceCapable,
            connectionRepository.videoCapable,
            connectionRepository.imsRegistered,
        ) { voiceNetworkType, originNetworkType, voiceCapable, videoCapable, imsRegistered->
            MobileIconCustomizationMode(
                voiceNetworkType = voiceNetworkType,
                originNetworkType = originNetworkType,
                voiceCapable = voiceCapable,
                videoCapable = videoCapable,
                imsRegistered = imsRegistered,
            )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), MobileIconCustomizationMode())

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
    override val customizedIcon: StateFlow<SignalIconModel?> =
        combine(
                isDefaultDataSub,
                connectionRepository.imsRegistrationTech,
                ddsIcon,
                crossSimdisplaySingnalLevel,
                connectionRepository.ciwlanAvailable,
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2024-03-06: Android_UI: SystemUI: Fix MSIM C_IWLAN feature issue
            ) { isDefaultDataSub, imsRegistrationTech, ddsIcon, crossSimdisplaySingnalLevel,
                ciwlanAvailable ->
// QTI_END: 2024-03-06: Android_UI: SystemUI: Fix MSIM C_IWLAN feature issue
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
                if (!isDefaultDataSub
                    && crossSimdisplaySingnalLevel
                    && ciwlanAvailable
                    && (imsRegistrationTech == REGISTRATION_TECH_CROSS_SIM
                            || imsRegistrationTech == REGISTRATION_TECH_IWLAN))
                    ddsIcon
                else
                    null
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
    override val voWifiAvailable: StateFlow<Boolean> =
        combine(
            connectionRepository.imsRegistrationTech,
            connectionRepository.voiceCapable,
            showVowifiIcon,
        ) { imsRegistrationTech, voiceCapable, showVowifiIcon ->
            voiceCapable
                    && imsRegistrationTech == REGISTRATION_TECH_IWLAN
                    && showVowifiIcon
        }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt VoWifi icon
    /** What the mobile icon would be before carrierId overrides */
    private val defaultNetworkType: StateFlow<MobileIconGroup> =
        combine(
                connectionRepository.resolvedNetworkType,
                defaultMobileIconMapping,
                defaultMobileIconGroup,
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
                mobileIconCustomization,
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
            ) { resolvedNetworkType, mapping, defaultGroup, mobileIconCustomization ->
                when (resolvedNetworkType) {
                    is ResolvedNetworkType.CarrierMergedNetworkType ->
                        resolvedNetworkType.iconGroupOverride
                    else -> {
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
                        getMobileIconGroup(resolvedNetworkType, mobileIconCustomization, mapping)
                            ?: defaultGroup
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultMobileIconGroup.value)
    override val networkTypeIconGroup =
        combine(defaultNetworkType, carrierIdIconOverrideExists) { networkType, overrideExists ->
                // DefaultIcon comes out of the icongroup lookup, we check for overrides here
                if (overrideExists) {
                    val iconOverride =
                        carrierIdOverrides.getOverrideFor(
                            connectionRepository.carrierId.value,
                            networkType.name,
                            context.resources,
                        )
                    if (iconOverride > 0) {
                        OverriddenIcon(networkType, iconOverride)
                    } else {
                        DefaultIcon(networkType)
                    }
                } else {
                    DefaultIcon(networkType)
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogBuffer = tableLogBuffer,
                initialValue = DefaultIcon(defaultMobileIconGroup.value),
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                DefaultIcon(defaultMobileIconGroup.value),
            )
    override val showSliceAttribution: StateFlow<Boolean> =
        combine(
                connectionRepository.allowNetworkSliceIndicator,
                connectionRepository.hasPrioritizedNetworkCapabilities,
            ) { allowed, hasPrioritizedNetworkCapabilities ->
                allowed && hasPrioritizedNetworkCapabilities
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val isNonTerrestrial: StateFlow<Boolean> = connectionRepository.isNonTerrestrial

    private val level: StateFlow<Int> =
// QTI_BEGIN: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
        combine(
// QTI_END: 2023-03-02: Android_UI: SystemUI: Support customization signal strength icon
                connectionRepository.isGsm,
                connectionRepository.primaryLevel,
                connectionRepository.cdmaLevel,
                alwaysUseCdmaLevel,
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
                signalStrengthCustomization
            ) { isGsm, primaryLevel, cdmaLevel, alwaysUseCdmaLevel, signalStrengthCustomization ->
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
                when {
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
                    signalStrengthCustomization.alwaysUseRsrpLevelForLte -> {
                        if (isLteCamped(signalStrengthCustomization)) {
                            signalStrengthCustomization.lteRsrpLevel
                        } else {
                            primaryLevel
                        }
                    }
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
                    // GSM connections should never use the CDMA level
                    isGsm -> primaryLevel
                    alwaysUseCdmaLevel -> cdmaLevel
                    else -> primaryLevel
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)
    private val numberOfLevels: StateFlow<Int> = connectionRepository.numberOfLevels
    override val isDataConnected: StateFlow<Boolean> =
        connectionRepository.dataConnectionState
            .map { it == Connected }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    override val isInService = connectionRepository.isInService

// QTI_BEGIN: 2023-06-26: Telephony: Separate exclamation mark display for mobile network
    override val isConnectionFailed: StateFlow<Boolean> = connectionRepository.isConnectionFailed

// QTI_END: 2023-06-26: Telephony: Separate exclamation mark display for mobile network
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
    private fun isLteCamped(mobileIconCustmization: MobileIconCustomizationMode): Boolean {
        return (mobileIconCustmization.dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE
                || mobileIconCustmization.dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE_CA
                || mobileIconCustmization.voiceNetworkType == TelephonyManager.NETWORK_TYPE_LTE
                || mobileIconCustmization.voiceNetworkType == TelephonyManager.NETWORK_TYPE_LTE_CA)
    }
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the customization signal strength icon
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon

    private fun getMobileIconGroup(resolvedNetworkType: ResolvedNetworkType,
                                   customizationInfo: MobileIconCustomizationMode,
                                   mapping: Map<String, MobileIconGroup>): MobileIconGroup ?{
        return if (customizationInfo.fiveGServiceState.isNrIconTypeValid) {
            customizationInfo.fiveGServiceState.iconGroup
        } else {
            when (resolvedNetworkType) {
                is DefaultNetworkType ->
                    mapping[resolvedNetworkType.lookupKey]
                is OverrideNetworkType ->
                    mapping[getLookupKey(resolvedNetworkType, customizationInfo)]
                else ->
                    mapping[MobileMappings.toIconKey(customizationInfo.voiceNetworkType)]
            }
        }
    }

    private fun getLookupKey(resolvedNetworkType: ResolvedNetworkType,
                             customizationInfo: MobileIconCustomizationMode): String {
        return if (isNsa(resolvedNetworkType.networkType)) {
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
            if (customizationInfo.originNetworkType  == TelephonyManager.NETWORK_TYPE_UNKNOWN) {
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
                MobileMappings.toIconKey(customizationInfo.voiceNetworkType)
            }else {
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
                MobileMappings.toIconKey(customizationInfo.originNetworkType)
// QTI_END: 2024-01-30: Android_UI: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon
            }
        }else {
            resolvedNetworkType.lookupKey
        }
    }

    private fun isNsa(networkType: Int): Boolean {
        return networkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
                || networkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
    }
// QTI_END: 2023-04-01: Android_UI: SystemUI: Readapt the side car 5G icon

    override val isEmergencyOnly: StateFlow<Boolean> = connectionRepository.isEmergencyOnly
    override val isAllowedDuringAirplaneMode = connectionRepository.isAllowedDuringAirplaneMode
    /** Whether or not to show the error state of [SignalDrawable] */
    private val showExclamationMark: StateFlow<Boolean> =
        combine(
// QTI_BEGIN: 2023-12-27: Telephony: Fix exclamation mark issue for dual data
                isDataEnabled,
                isDataConnected,
                isConnectionFailed,
// QTI_END: 2023-12-27: Telephony: Fix exclamation mark issue for dual data
                isInService,
// QTI_BEGIN: 2023-12-27: Telephony: Fix exclamation mark issue for dual data
                hideNoInternetState
            ) { isDataEnabled, isDataConnected, isConnectionFailed, isInService,
                        hideNoInternetState ->
                !hideNoInternetState && (!isDataEnabled || (isDataConnected && isConnectionFailed)
                        || !isInService)
// QTI_END: 2023-12-27: Telephony: Fix exclamation mark issue for dual data
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), true)
    private val cellularShownLevel: StateFlow<Int> =
        combine(level, isInService, connectionRepository.inflateSignalStrength) {
                level,
                isInService,
                inflate ->
                if (isInService) {
                    if (inflate) level + 1 else level
                } else 0
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)
    // Satellite level is unaffected by the inflateSignalStrength property
    // See b/346904529 for details
    private val satelliteShownLevel: StateFlow<Int> =
        if (Flags.carrierRoamingNbIotNtn()) {
                connectionRepository.satelliteLevel
            } else {
                combine(level, isInService) { level, isInService -> if (isInService) level else 0 }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)
    private val showRoaming: StateFlow<Boolean> =
        combine(
                isRoaming,
                isRoamingForceHidden
        ) { roaming, roamingForceHidden ->
            roaming && !roamingForceHidden
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    private val cellularIcon: Flow<SignalIconModel.Cellular> =
        combine(
            cellularShownLevel,
            numberOfLevels,
            showExclamationMark,
            carrierNetworkChangeActive,
            showRoaming,
        ) { cellularShownLevel, numberOfLevels, showExclamationMark, carrierNetworkChange, showRoaming ->
            SignalIconModel.Cellular(
                cellularShownLevel,
                numberOfLevels,
                showExclamationMark,
                carrierNetworkChange,
                showRoaming,
            )
        }
    private val satelliteIcon: Flow<SignalIconModel.Satellite> =
        satelliteShownLevel.map {
            SignalIconModel.Satellite(
                level = it,
                icon =
                    SatelliteIconModel.fromSignalStrength(it)
                        ?: SatelliteIconModel.fromSignalStrength(0)!!,
            )
        }

// QTI_BEGIN: 2024-03-06: Android_UI: SystemUI: Fix MSIM C_IWLAN feature issue
    private val customizedCellularIcon : Flow<SignalIconModel.Cellular> =
        combine(
            cellularIcon,
            customizedIcon,
        ) { cellularIcon, customizedIcon ->
            if (customizedIcon != null && customizedIcon is SignalIconModel.Cellular) {
                customizedIcon
            } else {
                cellularIcon
            }
        }

// QTI_END: 2024-03-06: Android_UI: SystemUI: Fix MSIM C_IWLAN feature issue
    override val signalLevelIcon: StateFlow<SignalIconModel> = run {
        val initial =
            SignalIconModel.Cellular(
                cellularShownLevel.value,
                numberOfLevels.value,
                showExclamationMark.value,
                carrierNetworkChangeActive.value,
                showRoaming.value,
            )
        isNonTerrestrial
            .flatMapLatest { ntn ->
                if (ntn) {
                    satelliteIcon
                } else {
// QTI_BEGIN: 2024-03-06: Android_UI: SystemUI: Fix MSIM C_IWLAN feature issue
                    customizedCellularIcon
// QTI_END: 2024-03-06: Android_UI: SystemUI: Fix MSIM C_IWLAN feature issue
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(tableLogBuffer, columnPrefix = "icon", initialValue = initial)
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    override val isMobileHd: StateFlow<Boolean> =
        connectionRepository.imsState
            .map { it.isHdVoiceCapable() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isVoWifi: StateFlow<Boolean> =
        connectionRepository.imsState
            .map { it.isVoWifiAvailable() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
}
