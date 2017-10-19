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

// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
/*
 * Changes from Qualcomm Innovation Center are provided under the following license:
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
// QTI_BEGIN: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
 * Copyright (c) 2023-2024 Qualcomm Innovation Center, Inc. All rights reserved.
// QTI_END: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */


// QTI_END: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt network type icon customization
import android.database.ContentObserver
// QTI_END: 2023-04-01: Data: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-06-26: Data: Separate exclamation mark display for mobile network
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
// QTI_END: 2023-06-26: Data: Separate exclamation mark display for mobile network
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt network type icon customization
import android.provider.Settings.Global
// QTI_END: 2023-04-01: Data: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_GREAT
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_GOOD
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_MODERATE
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_POOR
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
import android.telephony.CellSignalStrengthCdma
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support customization signal strength icon
import android.telephony.CellSignalStrengthLte
// QTI_END: 2023-03-02: Data: SystemUI: Support customization signal strength icon
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
import android.telephony.ims.ImsException
import android.telephony.ims.ImsMmTelManager
import android.telephony.ims.ImsReasonInfo
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.ImsStateCallback
import android.telephony.ims.feature.MmTelFeature.MmTelCapabilities
import android.telephony.ims.RegistrationManager
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt VoWifi icon
import android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_NONE
// QTI_END: 2023-04-01: Data: SystemUI: Readapt VoWifi icon
import android.telephony.ServiceState
import android.telephony.SignalStrength
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
import android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.ERI_FLASH
import android.telephony.TelephonyManager.ERI_ON
import android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID
import android.telephony.satellite.NtnSignalStrength
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
import android.util.Log
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
import com.android.settingslib.Utils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags.ROAMING_INDICATOR_VIA_DISPLAY_INFO
import com.android.systemui.log.table.TableLogBuffer
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt network type icon customization
import com.android.systemui.log.table.logDiffsForTable
// QTI_END: 2023-04-01: Data: SystemUI: Readapt network type icon customization
import com.android.systemui.statusbar.pipeline.ims.data.model.ImsStateModel
import com.android.systemui.statusbar.pipeline.ims.data.repository.ImsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Disconnected
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.model.toDataConnectionType
import com.android.systemui.statusbar.pipeline.mobile.data.model.toNetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
import com.android.systemui.statusbar.policy.FiveGServiceClient
import com.android.systemui.statusbar.policy.FiveGServiceClient.FiveGServiceState
import com.android.systemui.statusbar.policy.FiveGServiceClient.IFiveGStateListener
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
import com.qti.extphone.NrIconType
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt network type icon customization
import kotlinx.coroutines.flow.distinctUntilChanged
// QTI_END: 2023-04-01: Data: SystemUI: Readapt network type icon customization
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
// QTI_BEGIN: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
// QTI_END: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
import kotlinx.coroutines.launch
// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue

/**
 * A repository implementation for a typical mobile connection (as opposed to a carrier merged
 * connection -- see [CarrierMergedConnectionRepository]).
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
class MobileConnectionRepositoryImpl(
    override val subId: Int,
    private val context: Context,
    subscriptionModel: Flow<SubscriptionModel?>,
    defaultNetworkName: NetworkNameModel,
    networkNameSeparator: String,
    connectivityManager: ConnectivityManager,
    private val telephonyManager: TelephonyManager,
    systemUiCarrierConfig: SystemUiCarrierConfig,
    broadcastDispatcher: BroadcastDispatcher,
    private val mobileMappingsProxy: MobileMappingsProxy,
    private val bgDispatcher: CoroutineDispatcher,
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    private val logger: MobileInputLogger,
// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    override val tableLogBuffer: TableLogBuffer,
    flags: FeatureFlagsClassic,
// QTI_BEGIN: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
    private val scope: CoroutineScope,
// QTI_END: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
    private val fiveGServiceClient: FiveGServiceClient,
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    slotIndexForSubId:  Flow<Int>? = null,
// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    private val imsRepository: ImsRepository,
) : MobileConnectionRepository {
    init {
        if (telephonyManager.subscriptionId != subId) {
            throw IllegalStateException(
                "MobileRepo: TelephonyManager should be created with subId($subId). " +
                    "Found ${telephonyManager.subscriptionId} instead."
            )
        }
    }
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
    private val tag: String = MobileConnectionRepositoryImpl::class.java.simpleName
    private val imsMmTelManager: ImsMmTelManager = ImsMmTelManager.createForSubscriptionId(subId)
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    private var imsStateCallback: ImsStateCallback? = null
// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
    private var registrationCallback: RegistrationManager.RegistrationCallback? = null
    private var capabilityCallback: ImsMmTelManager.CapabilityCallback? = null
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    private var imsStateCallBackRegistered = false
// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    /**
     * This flow defines the single shared connection to system_server via TelephonyCallback. Any
     * new callback should be added to this listener and funneled through callbackEvents via a data
     * class. See [CallbackEvent] for defining new callbacks.
     *
     * The reason we need to do this is because TelephonyManager limits the number of registered
     * listeners per-process, so we don't want to create a new listener for every callback.
     *
     * A note on the design for back pressure here: We don't control _which_ telephony callback
     * comes in first, since we register every relevant bit of information as a batch. E.g., if a
     * downstream starts collecting on a field which is backed by
     * [TelephonyCallback.ServiceStateListener], it's not possible for us to guarantee that _that_
     * callback comes in -- the first callback could very well be
     * [TelephonyCallback.DataActivityListener], which would promptly be dropped if we didn't keep
     * it tracked. We use the [scan] operator here to track the most recent callback of _each type_
     * here. See [TelephonyCallbackState] to see how the callbacks are stored.
     */
    private val callbackEvents: StateFlow<TelephonyCallbackState> = run {
        val initial = TelephonyCallbackState()
        callbackFlow {
                val callback =
                    object :
                        TelephonyCallback(),
                        TelephonyCallback.CarrierNetworkListener,
                        TelephonyCallback.CarrierRoamingNtnListener,
                        TelephonyCallback.DataActivityListener,
                        TelephonyCallback.DataConnectionStateListener,
                        TelephonyCallback.DataEnabledListener,
                        TelephonyCallback.DisplayInfoListener,
                        TelephonyCallback.ServiceStateListener,
                        TelephonyCallback.SignalStrengthsListener {

                        override fun onCarrierNetworkChange(active: Boolean) {
                            logger.logOnCarrierNetworkChange(active, subId)
                            trySend(CallbackEvent.OnCarrierNetworkChange(active))
                        }

                        override fun onCarrierRoamingNtnModeChanged(active: Boolean) {
                            logger.logOnCarrierRoamingNtnModeChanged(active)
                            trySend(CallbackEvent.OnCarrierRoamingNtnModeChanged(active))
                        }

                        override fun onDataActivity(direction: Int) {
                            logger.logOnDataActivity(direction, subId)
                            trySend(CallbackEvent.OnDataActivity(direction))
                        }

                        override fun onDataEnabledChanged(enabled: Boolean, reason: Int) {
                            logger.logOnDataEnabledChanged(enabled, subId)
                            trySend(CallbackEvent.OnDataEnabledChanged(enabled))
                        }

                        override fun onDataConnectionStateChanged(
                            dataState: Int,
                            networkType: Int,
                        ) {
                            logger.logOnDataConnectionStateChanged(dataState, networkType, subId)
                            trySend(CallbackEvent.OnDataConnectionStateChanged(dataState))
                        }

                        override fun onDisplayInfoChanged(
                            telephonyDisplayInfo: TelephonyDisplayInfo
                        ) {
                            logger.logOnDisplayInfoChanged(telephonyDisplayInfo, subId)
                            trySend(CallbackEvent.OnDisplayInfoChanged(telephonyDisplayInfo))
                        }

                        override fun onServiceStateChanged(serviceState: ServiceState) {
                            logger.logOnServiceStateChanged(serviceState, subId)
                            trySend(CallbackEvent.OnServiceStateChanged(serviceState))
                        }

                        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                            logger.logOnSignalStrengthsChanged(signalStrength, subId)
                            trySend(CallbackEvent.OnSignalStrengthChanged(signalStrength))
                        }

                        override fun onCarrierRoamingNtnSignalStrengthChanged(
                            signalStrength: NtnSignalStrength
                        ) {
                            logger.logNtnSignalStrengthChanged(signalStrength)
                            trySend(
                                CallbackEvent.OnCarrierRoamingNtnSignalStrengthChanged(
                                    signalStrength
                                )
                            )
                        }
                    }

                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
                awaitClose {
                    telephonyManager.unregisterTelephonyCallback(callback)
                }
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
            }
            .flowOn(bgDispatcher)
            .scan(initial = initial) { state, event -> state.applyEvent(event) }
            .stateIn(scope = scope, started = SharingStarted.WhileSubscribed(), initial)
    }

// QTI_BEGIN: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
    private fun getFiveGStateFlow(slotIndex: Int): Flow<TelephonyCallbackState> {
        return callbackFlow {
            val listener =
                object : IFiveGStateListener {
                    override fun onStateChanged(serviceState: FiveGServiceState) {
// QTI_END: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
// QTI_BEGIN: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
                        logger.logOnNrIconTypeChanged(serviceState.nrIconType,
                            serviceState.is6Rx, subId)
                        trySend(CallbackEvent.OnNrIconTypeChanged(serviceState.nrIconType,
                            serviceState.is6Rx))
// QTI_END: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
                    }

                    override fun onCiwlanAvailableChanged(available: Boolean) {
                        logger.logOnCiwlanAvailableChanged(available, subId)
                        trySend(CallbackEvent.OnCiwlanAvailableChanged(available))
                    }
                }
            fiveGServiceClient.registerListener(slotIndex, listener)
            awaitClose {
                fiveGServiceClient.unregisterListener(slotIndex, listener) }
        }
            .scan(TelephonyCallbackState()) { state, event -> state.applyEvent(event) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), TelephonyCallbackState())
    }

    private val fiveGState: Flow<TelephonyCallbackState> = run {
        val initial = flowOf(TelephonyCallbackState()
// QTI_END: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
// QTI_BEGIN: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
            .applyEvent(CallbackEvent.OnNrIconTypeChanged(NrIconType.TYPE_NONE, false))
// QTI_END: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
            .applyEvent(CallbackEvent.OnCiwlanAvailableChanged(false)))
        if (slotIndexForSubId == null) {
            initial
        } else {
            slotIndexForSubId
                .flatMapLatest { it ->
                    if (SubscriptionManager.isValidSlotIndex(it)) {
                        getFiveGStateFlow(it)
                    } else {
                        initial
                    }
                }
        }
    }

// QTI_END: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    private fun unRegisterImsCallbackIfNeeded() {
        if (!imsStateCallBackRegistered) {
            return
        }
        try {
            imsStateCallback?.let {
                imsMmTelManager.unregisterImsStateCallback(it)
            }
        } catch (exception: Exception) {
            logger.logException(exception, "UnregisterImsStateCallback failed sub: $subId")
        }
        unregisterCapabilityAndRegistrationCallback()
        imsStateCallBackRegistered = false
        logger.logImsStateCallbackRegistered(false, subId)
    }

    private fun registerImsCallbackIfNeeded() {
        if (imsStateCallBackRegistered) {
            return
        }
        if (imsStateCallback == null) {
            imsStateCallback =
                object : ImsStateCallback() {
                    override fun onAvailable() {
                        registerCapabilityAndRegistrationCallback()
                    }

                    override fun onUnavailable(reason: Int) {
                        unregisterCapabilityAndRegistrationCallback()
                        if (reason == 5) {
                            unRegisterImsCallbackIfNeeded()
                        }
                    }

                    override fun onError() {
                        unregisterCapabilityAndRegistrationCallback()
                    }
                }
        }
        try {
            imsStateCallback?.let {
                imsMmTelManager.registerImsStateCallback(context.mainExecutor,it) }
            imsStateCallBackRegistered = true
            logger.logImsStateCallbackRegistered(true, subId)
        } catch (exception: ImsException) {
            logger.logException(exception, "RegisterImsStateCallback failed sub: $subId")
            imsStateCallBackRegistered = false
        }
    }

// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
    override val isEmergencyOnly =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.isEmergencyOnly }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isRoaming =
        if (flags.isEnabled(ROAMING_INDICATOR_VIA_DISPLAY_INFO)) {
                callbackEvents
                    .mapNotNull { it.onDisplayInfoChanged }
                    .map { it.telephonyDisplayInfo.isRoaming }
            } else {
                callbackEvents
                    .mapNotNull { it.onServiceStateChanged }
                    .map { it.serviceState.roaming }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val operatorAlphaShort =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.operatorAlphaShort }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val isInService =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { Utils.isInService(it.serviceState) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isNonTerrestrial =
        callbackEvents
            .mapNotNull { it.onCarrierRoamingNtnModeChanged }
            .map { it.active }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isGsm =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map { it.signalStrength.isGsm }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val cdmaLevel =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map {
                it.signalStrength.getCellSignalStrengths(CellSignalStrengthCdma::class.java).let {
                    strengths ->
                    if (strengths.isNotEmpty()) {
                        strengths[0].level
                    } else {
                        SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                    }
                }
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
            }
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val primaryLevel =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map { it.signalStrength.level }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val satelliteLevel: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onCarrierRoamingNtnSignalStrengthChanged }
            .map { it.signalStrength.level }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    override val dataConnectionState =
        callbackEvents
            .mapNotNull { it.onDataConnectionStateChanged }
            .map { it.dataState.toDataConnectionType() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), Disconnected)

    override val dataActivityDirection =
        callbackEvents
            .mapNotNull { it.onDataActivity }
            .map { it.direction.toMobileDataActivityModel() }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                DataActivityModel(hasActivityIn = false, hasActivityOut = false),
            )

    override val carrierNetworkChangeActive =
        callbackEvents
            .mapNotNull { it.onCarrierNetworkChange }
            .map { it.active }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val resolvedNetworkType =
        callbackEvents
            .mapNotNull { it.onDisplayInfoChanged }
            .map {
                if (it.telephonyDisplayInfo.overrideNetworkType != OVERRIDE_NETWORK_TYPE_NONE) {
                    OverrideNetworkType(
                        mobileMappingsProxy.toIconKeyOverride(
                            it.telephonyDisplayInfo.overrideNetworkType
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
                        ),
                        it.telephonyDisplayInfo.overrideNetworkType
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
                    )
                } else if (it.telephonyDisplayInfo.networkType != NETWORK_TYPE_UNKNOWN) {
                    DefaultNetworkType(
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
                        mobileMappingsProxy.toIconKey(it.telephonyDisplayInfo.networkType),
                        it.telephonyDisplayInfo.networkType
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
                    )
                } else {
                    UnknownNetworkType
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), UnknownNetworkType)

    override val inflateSignalStrength = systemUiCarrierConfig.shouldInflateSignalStrength
    override val allowNetworkSliceIndicator = systemUiCarrierConfig.allowNetworkSliceIndicator

    override val numberOfLevels =
        inflateSignalStrength
            .map { shouldInflate ->
                if (shouldInflate) {
                    DEFAULT_NUM_LEVELS + 1
                } else {
                    DEFAULT_NUM_LEVELS
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DEFAULT_NUM_LEVELS)

    override val carrierName =
        subscriptionModel
            .map {
                it?.let { model -> NetworkNameModel.SubscriptionDerived(model.carrierName) }
                    ?: defaultNetworkName
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultNetworkName)

    /**
     * There are a few cases where we will need to poll [TelephonyManager] so we can update some
     * internal state where callbacks aren't provided. Any of those events should be merged into
     * this flow, which can be used to trigger the polling.
     */
    private val telephonyPollingEvent: Flow<Unit> = callbackEvents.map { Unit }

    override val cdmaRoaming: StateFlow<Boolean> =
        telephonyPollingEvent
            .mapLatest {
                try {
                    val cdmaEri = telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber
                    cdmaEri == ERI_ON || cdmaEri == ERI_FLASH
                } catch (e: UnsupportedOperationException) {
                    // Handles the same as a function call failure
                    false
                }
            }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val carrierId =
        broadcastDispatcher
            .broadcastFlow(
                filter =
                    IntentFilter(TelephonyManager.ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED),
                map = { intent, _ -> intent },
            )
            .filter { intent ->
                intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, INVALID_SUBSCRIPTION_ID) == subId
            }
            .map { it.carrierId() }
            .onStart {
                // Make sure we get the initial carrierId
                emit(telephonyManager.simCarrierId)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), telephonyManager.simCarrierId)

    /**
     * BroadcastDispatcher does not handle sticky broadcasts, so we can't use it here. Note that we
     * now use the [SharingStarted.Eagerly] strategy, because there have been cases where the sticky
     * broadcast does not represent the correct state.
     *
     * See b/322432056 for context.
     */
    @SuppressLint("RegisterReceiverViaContext")
// QTI_BEGIN: 2024-07-15: Data: SystemUI: Fix "No service" in Internet tile after ANR/ crash.
    override val networkName: StateFlow<NetworkNameModel> = run {
        var subscriptionManager: SubscriptionManager? =
            context.getSystemService(SubscriptionManager::class.java)
        val initial = subscriptionManager?.getActiveSubscriptionInfo(subId)?.let {
             NetworkNameModel.IntentDerived(it.carrierName.toString())
        } ?: defaultNetworkName
// QTI_END: 2024-07-15: Data: SystemUI: Fix "No service" in Internet tile after ANR/ crash.
        conflatedCallbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (
                                intent.getIntExtra(
                                    EXTRA_SUBSCRIPTION_INDEX,
                                    INVALID_SUBSCRIPTION_ID,
                                ) == subId
                            ) {
                                logger.logServiceProvidersUpdatedBroadcast(intent)
                                trySend(
                                    intent.toNetworkNameModel(networkNameSeparator)
                                        ?: defaultNetworkName
                                )
                            }
                        }
                    }

                context.registerReceiver(
                    receiver,
                    IntentFilter(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED),
                )

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .flowOn(bgDispatcher)
// QTI_BEGIN: 2024-07-15: Data: SystemUI: Fix "No service" in Internet tile after ANR/ crash.
            .stateIn(scope, SharingStarted.Eagerly, initial)
    }

// QTI_END: 2024-07-15: Data: SystemUI: Fix "No service" in Internet tile after ANR/ crash.

    override val dataEnabled = run {
        val initial = telephonyManager.isDataConnectionAllowed
        callbackEvents
            .mapNotNull { it.onDataEnabledChanged }
            .map { it.enabled }
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
    override val lteRsrpLevel: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map {
                it.signalStrength.getCellSignalStrengths(CellSignalStrengthLte::class.java).let {
                    strengths ->
                        if (strengths.isNotEmpty()) {
                            when (strengths[0].rsrp) {
                                SignalStrength.INVALID -> it.signalStrength.level
                                in -120 until -113 -> SIGNAL_STRENGTH_POOR
                                in -113 until -105 -> SIGNAL_STRENGTH_MODERATE
                                in -105 until -97 -> SIGNAL_STRENGTH_GOOD
                                in -97 until -43 -> SIGNAL_STRENGTH_GREAT
                                else -> SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                            }
                        } else {
                            it.signalStrength.level
                        }
                    }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val voiceNetworkType: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.voiceNetworkType }
            .stateIn(scope, SharingStarted.WhileSubscribed(), NETWORK_TYPE_UNKNOWN)

    override val dataNetworkType: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.dataNetworkType }
            .stateIn(scope, SharingStarted.WhileSubscribed(), NETWORK_TYPE_UNKNOWN)

// QTI_END: 2023-04-01: Data: SystemUI: Readapt the customization signal strength icon
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
    override val nrIconType: StateFlow<Int> =
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
        fiveGState
// QTI_END: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
            .mapNotNull {it.onNrIconTypeChanged }
            .map { it.nrIconType}
            .stateIn(scope, SharingStarted.WhileSubscribed(), NrIconType.TYPE_NONE)

// QTI_END: 2023-04-01: Data: SystemUI: Readapt the side car 5G icon
// QTI_BEGIN: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
    override val is6Rx: StateFlow<Boolean> =
        fiveGState
            .mapNotNull {it.onNrIconTypeChanged }
            .map { it.is6Rx}
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

// QTI_END: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt network type icon customization
    private val dataRoamingSettingChangedEvent: Flow<Unit> = conflatedCallbackFlow {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        context.contentResolver.registerContentObserver(
            Global.getUriFor("${Global.DATA_ROAMING}$subId"),
            true,
            observer)

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    override val dataRoamingEnabled: StateFlow<Boolean> = run {
        val initial = telephonyManager.isDataRoamingEnabled
        dataRoamingSettingChangedEvent
            .mapLatest { telephonyManager.isDataRoamingEnabled }
            .distinctUntilChanged()
            .logDiffsForTable(
                    tableLogBuffer,
                    columnPrefix = "",
                    columnName = "dataRoamingEnabled",
                    initialValue = initial,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

// QTI_END: 2023-04-01: Data: SystemUI: Readapt network type icon customization
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
    override val originNetworkType: StateFlow<Int> =
        callbackEvents
            .mapNotNull { it.onDisplayInfoChanged }
            .map { it.telephonyDisplayInfo.networkType }
            .stateIn(scope, SharingStarted.WhileSubscribed(), NETWORK_TYPE_UNKNOWN)

// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
    private fun registerCapabilityAndRegistrationCallback() {
        if (registrationCallback == null) {
            registrationCallback =
                object : RegistrationManager.RegistrationCallback() {
                    override fun onRegistered(attributes: ImsRegistrationAttributes) {
                        imsRegistered.value = true
                        imsRegistrationTech.value = attributes.getRegistrationTechnology()
                    }
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.

// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
                    override fun onUnregistered(info: ImsReasonInfo) {
                        imsRegistered.value = false
                        imsRegistrationTech.value = REGISTRATION_TECH_NONE
                    }
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
                }
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
        }
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.

// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
        if (capabilityCallback == null) {
            capabilityCallback =
                object : ImsMmTelManager.CapabilityCallback() {
                    override fun onCapabilitiesStatusChanged(config: MmTelCapabilities) {
                        voiceCapable.value = config.isCapable(
                            MmTelCapabilities.CAPABILITY_TYPE_VOICE)
                        videoCapable.value = config.isCapable(
                            MmTelCapabilities.CAPABILITY_TYPE_VIDEO)
                    }
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
                }
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
        }

        try {
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
            registrationCallback?.let {
                imsMmTelManager.registerImsRegistrationCallback(
                    context.mainExecutor,it)
            }
            capabilityCallback?.let {
                imsMmTelManager.registerMmTelCapabilityCallback(
                    context.mainExecutor, it)
            }
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
        } catch (e: ImsException) {
            Log.e(tag, "failed to call register ims callback ", e)
        }
    }

    private fun unregisterCapabilityAndRegistrationCallback() {
        try {
            capabilityCallback?.let {
                imsMmTelManager.unregisterMmTelCapabilityCallback(it)
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
            }
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
            registrationCallback?.let {
                imsMmTelManager.unregisterImsRegistrationCallback(it)
            }
        } catch (exception: Exception) {
            Log.e(tag, " failed to call unregister ims callback ", exception)

// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
        }
// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
        capabilityCallback = null
        registrationCallback = null
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
// QTI_BEGIN: 2025-01-19: Data: SystemUI: Fix NPE in MobileConnectionRepositoryImpl
        imsRegistered?.value = false
        imsRegistrationTech?.value = REGISTRATION_TECH_NONE
        voiceCapable?.value = false
        videoCapable?.value = false
// QTI_END: 2025-01-19: Data: SystemUI: Fix NPE in MobileConnectionRepositoryImpl
// QTI_BEGIN: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
    }
// QTI_END: 2023-07-17: Data: SystemUI: Fix ImsStateCallback registration failure issue.
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon

    override val voiceCapable: MutableStateFlow<Boolean> =
        MutableStateFlow<Boolean>(false)

    override val videoCapable: MutableStateFlow<Boolean> =
        MutableStateFlow<Boolean>(false)

    override val imsRegistered: MutableStateFlow<Boolean> =
        MutableStateFlow<Boolean>(false)

// QTI_END: 2023-04-01: Data: SystemUI: Readapt the Volte HD icon
// QTI_BEGIN: 2023-04-01: Data: SystemUI: Readapt VoWifi icon
    override val imsRegistrationTech: MutableStateFlow<Int> =
        MutableStateFlow<Int>(REGISTRATION_TECH_NONE)

// QTI_END: 2023-04-01: Data: SystemUI: Readapt VoWifi icon
// QTI_BEGIN: 2025-01-19: Data: SystemUI: Fix NPE in MobileConnectionRepositoryImpl
    init {
        slotIndexForSubId?.let { slotIndex ->
            scope.launch { slotIndex.collect {
                logger.logSlotIndex(it, subId)
                if (SubscriptionManager.isValidSlotIndex(it)) {
                    registerImsCallbackIfNeeded()
                } else {
                    unRegisterImsCallbackIfNeeded()
                }
            }}
        }
    }

// QTI_END: 2025-01-19: Data: SystemUI: Fix NPE in MobileConnectionRepositoryImpl
// QTI_BEGIN: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
    override val ciwlanAvailable: StateFlow<Boolean> =
// QTI_END: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
        fiveGState
// QTI_END: 2024-04-19: Data: SystemUI: Fix FiveGStateListener registration failure issue
// QTI_BEGIN: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
            .mapNotNull {it.onCiwlanAvailableChanged }
            .map { it.available}
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

// QTI_END: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
// QTI_BEGIN: 2023-06-26: Data: Separate exclamation mark display for mobile network
    override val isConnectionFailed: StateFlow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities
                 ) {
                     trySend(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                 }
            }
            connectivityManager.registerNetworkCallback(createNetworkRequest(subId), callback)

            awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private fun createNetworkRequest(specfier: Int): NetworkRequest {
        return NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(TelephonyNetworkSpecifier.Builder()
                        .setSubscriptionId(specfier).build())
                .build()
    }

// QTI_END: 2023-06-26: Data: Separate exclamation mark display for mobile network
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
    private fun getSlotIndex(subId: Int): Int {
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
        var subscriptionManager: SubscriptionManager? =
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
                context.getSystemService(SubscriptionManager::class.java)
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
        var list: List<SubscriptionInfo>? = subscriptionManager?.completeActiveSubscriptionInfoList
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
        var slotIndex: Int = 0
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
        list?.let{
            for (subscriptionInfo in list.iterator()) {
                if (subscriptionInfo.subscriptionId == subId) {
                    slotIndex = subscriptionInfo.simSlotIndex
                    break
                }
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
            }
        }
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
        Log.d(tag, "getSlotIndex subId: $subId slotIndex: $slotIndex list.size: ${list?.size}")
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
        return slotIndex
    }

// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
    override suspend fun isInEcmMode(): Boolean =
        withContext(bgDispatcher) { telephonyManager.emergencyCallbackMode }

    /** Typical mobile connections aren't available during airplane mode. */
    override val isAllowedDuringAirplaneMode = MutableStateFlow(false).asStateFlow()

    override val imsState: StateFlow<ImsStateModel> = imsRepository.imsState

    /**
     * Currently, a network with NET_CAPABILITY_PRIORITIZE_LATENCY is the only type of network that
     * we consider to be a "network slice". _PRIORITIZE_BANDWIDTH may be added in the future. Any of
     * these capabilities that are used here must also be represented in the
     * self_certified_network_capabilities.xml config file
     */
    @SuppressLint("WrongConstant")
    private val networkSliceRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY)
            .setSubscriptionIds(setOf(subId))
            .build()

    @SuppressLint("MissingPermission")
    override val hasPrioritizedNetworkCapabilities: StateFlow<Boolean> =
        conflatedCallbackFlow {
                // Our network callback listens only for this.subId && net_cap_prioritize_latency
                // therefore our state is a simple mapping of whether or not that network exists
                val callback =
                    object : NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            logger.logPrioritizedNetworkAvailable(network.netId)
                            trySend(true)
                        }

                        override fun onLost(network: Network) {
                            logger.logPrioritizedNetworkLost(network.netId)
                            trySend(false)
                        }
                    }

                connectivityManager.registerNetworkCallback(networkSliceRequest, callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    class Factory
    @Inject
    constructor(
        private val context: Context,
        private val broadcastDispatcher: BroadcastDispatcher,
        private val connectivityManager: ConnectivityManager,
        private val telephonyManager: TelephonyManager,
        private val logger: MobileInputLogger,
        private val carrierConfigRepository: CarrierConfigRepository,
        private val mobileMappingsProxy: MobileMappingsProxy,
        private val flags: FeatureFlagsClassic,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Background private val scope: CoroutineScope,
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
        private val fiveGServiceClient: FiveGServiceClient,
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
    ) {
        fun build(
            subId: Int,
            mobileLogger: TableLogBuffer,
            subscriptionModel: Flow<SubscriptionModel?>,
            defaultNetworkName: NetworkNameModel,
            networkNameSeparator: String,
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
            slotIndexForSubId:  Flow<Int>? = null,
// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
            imsRepository: ImsRepository,
        ): MobileConnectionRepository {
            return MobileConnectionRepositoryImpl(
                subId,
                context,
                subscriptionModel,
                defaultNetworkName,
                networkNameSeparator,
                connectivityManager,
                telephonyManager.createForSubscriptionId(subId),
                carrierConfigRepository.getOrCreateConfigForSubId(subId),
                broadcastDispatcher,
                mobileMappingsProxy,
                bgDispatcher,
                logger,
                mobileLogger,
                flags,
                scope,
// QTI_BEGIN: 2023-03-02: Data: SystemUI: Support side car 5G icon
                fiveGServiceClient,
// QTI_END: 2023-03-02: Data: SystemUI: Support side car 5G icon
// QTI_BEGIN: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
                slotIndexForSubId,
// QTI_END: 2024-04-17: Data: SystemUI: Fix ImsStateCallback registration failure issue
                imsRepository,
            )
        }
    }
}

private fun Intent.carrierId(): Int =
    getIntExtra(TelephonyManager.EXTRA_CARRIER_ID, UNKNOWN_CARRIER_ID)

/**
 * Wrap every [TelephonyCallback] we care about in a data class so we can accept them in a single
 * shared flow and then split them back out into other flows.
 */
sealed interface CallbackEvent {
    data class OnCarrierNetworkChange(val active: Boolean) : CallbackEvent

    data class OnCarrierRoamingNtnModeChanged(val active: Boolean) : CallbackEvent

    data class OnDataActivity(val direction: Int) : CallbackEvent

    data class OnDataConnectionStateChanged(val dataState: Int) : CallbackEvent

    data class OnDataEnabledChanged(val enabled: Boolean) : CallbackEvent

    data class OnDisplayInfoChanged(val telephonyDisplayInfo: TelephonyDisplayInfo) : CallbackEvent

    data class OnServiceStateChanged(val serviceState: ServiceState) : CallbackEvent

    data class OnSignalStrengthChanged(val signalStrength: SignalStrength) : CallbackEvent

// QTI_BEGIN: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
    data class OnNrIconTypeChanged(val nrIconType: Int, val is6Rx: Boolean) : CallbackEvent
// QTI_END: 2024-05-21: Data: SystemUI: Add 6Rx icons support for NrIcons
// QTI_BEGIN: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature

    data class OnCiwlanAvailableChanged(val available: Boolean): CallbackEvent
// QTI_END: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature

    data class OnCarrierRoamingNtnSignalStrengthChanged(val signalStrength: NtnSignalStrength) :
        CallbackEvent

    data class OnCallBackModeStarted(val type: Int) : CallbackEvent

    data class OnCallBackModeStopped(val type: Int) : CallbackEvent
}

/**
 * A simple box type for 1-to-1 mapping of [CallbackEvent] to the batched event. Used in conjunction
 * with [scan] to make sure we don't drop important callbacks due to late subscribers
 */
data class TelephonyCallbackState(
    val onDataActivity: CallbackEvent.OnDataActivity? = null,
    val onCarrierNetworkChange: CallbackEvent.OnCarrierNetworkChange? = null,
    val onCarrierRoamingNtnModeChanged: CallbackEvent.OnCarrierRoamingNtnModeChanged? = null,
    val onDataConnectionStateChanged: CallbackEvent.OnDataConnectionStateChanged? = null,
    val onDataEnabledChanged: CallbackEvent.OnDataEnabledChanged? = null,
    val onDisplayInfoChanged: CallbackEvent.OnDisplayInfoChanged? = null,
    val onServiceStateChanged: CallbackEvent.OnServiceStateChanged? = null,
    val onSignalStrengthChanged: CallbackEvent.OnSignalStrengthChanged? = null,
    val onNrIconTypeChanged: CallbackEvent.OnNrIconTypeChanged? = null,
// QTI_BEGIN: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
    val onCiwlanAvailableChanged: CallbackEvent.OnCiwlanAvailableChanged? = null,
// QTI_END: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
    val onCarrierRoamingNtnSignalStrengthChanged:
        CallbackEvent.OnCarrierRoamingNtnSignalStrengthChanged? =
        null,
    val addedCallbackModes: Set<Int> = emptySet(),
    val removedCallbackModes: Set<Int> = emptySet(),
) {
    fun applyEvent(event: CallbackEvent): TelephonyCallbackState {
        return when (event) {
            is CallbackEvent.OnCarrierNetworkChange -> copy(onCarrierNetworkChange = event)
            is CallbackEvent.OnCarrierRoamingNtnModeChanged -> {
                copy(onCarrierRoamingNtnModeChanged = event)
            }
            is CallbackEvent.OnDataActivity -> copy(onDataActivity = event)
            is CallbackEvent.OnDataConnectionStateChanged ->
                copy(onDataConnectionStateChanged = event)
            is CallbackEvent.OnDataEnabledChanged -> copy(onDataEnabledChanged = event)
            is CallbackEvent.OnDisplayInfoChanged -> copy(onDisplayInfoChanged = event)
            is CallbackEvent.OnServiceStateChanged -> {
                copy(onServiceStateChanged = event)
            }
            is CallbackEvent.OnSignalStrengthChanged -> copy(onSignalStrengthChanged = event)
            is CallbackEvent.OnNrIconTypeChanged -> copy(onNrIconTypeChanged = event)
// QTI_BEGIN: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
            is CallbackEvent.OnCiwlanAvailableChanged -> copy(onCiwlanAvailableChanged = event)
// QTI_END: 2024-01-30: Data: SystemUI: Implementation for MSIM C_IWLAN feature
            is CallbackEvent.OnCarrierRoamingNtnSignalStrengthChanged ->
                copy(onCarrierRoamingNtnSignalStrengthChanged = event)
            is CallbackEvent.OnCallBackModeStarted -> {
                copy(
                    addedCallbackModes =
                        if (event.type !in removedCallbackModes) {
                            addedCallbackModes + event.type
                        } else {
                            addedCallbackModes
                        },
                    removedCallbackModes =
                        if (event.type !in addedCallbackModes) {
                            removedCallbackModes - event.type
                        } else {
                            removedCallbackModes
                        },
                )
            }
            is CallbackEvent.OnCallBackModeStopped ->
                copy(
                    addedCallbackModes =
                        if (event.type !in removedCallbackModes) {
                            addedCallbackModes - event.type
                        } else {
                            addedCallbackModes
                        },
                    removedCallbackModes =
                        if (event.type !in addedCallbackModes) {
                            removedCallbackModes + event.type
                        } else {
                            removedCallbackModes
                        },
                )
        }
    }
}
