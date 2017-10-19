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
package com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel

import android.telephony.SubscriptionManager
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.coroutines.newTracingContext
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.dagger.MobileSummaryLog
import com.android.systemui.statusbar.pipeline.mobile.StatusBarMobileIconKairos
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.ui.VerboseMobileViewLogger
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.util.kotlin.mapDirect
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
/**
 * View model for describing the system's current mobile cellular connections. The result is a list
 * of [MobileIconViewModel]s which describe the individual icons and can be bound to
 * [ModernStatusBarMobileView].
 */
@SysUISingleton
class MobileIconsViewModel
@Inject
constructor(
    val logger: MobileViewLogger,
    private val verboseLogger: VerboseMobileViewLogger,
    private val interactor: MobileIconsInteractor,
    private val airplaneModeInteractor: AirplaneModeInteractor,
    private val constants: ConnectivityConstants,
    @MobileSummaryLog private val tableLogger: TableLogBuffer,
    @Background private val scope: CoroutineScope,
) {

    init {
        StatusBarMobileIconKairos.assertInLegacyMode()
    }

    @VisibleForTesting
    val reuseCache = ConcurrentHashMap<Int, Pair<MobileIconViewModel, CoroutineScope>>()
    val activeMobileDataSubscriptionId: StateFlow<Int?> = interactor.activeMobileDataSubscriptionId

    val subscriptionIdsFlow: StateFlow<List<Int>> =
        interactor.filteredSubscriptions
            .mapDirect { subscriptions ->
                subscriptions.map { subscriptionModel -> subscriptionModel.subscriptionId }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), listOf())

    private val ddsIcon: StateFlow<SignalIconModel?> =
        combine(interactor.defaultDataSubId, subscriptionIdsFlow) {
                defaultDataSubId, subscriptionIdsFlow ->
                if (defaultDataSubId ?: -1 > SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    && subscriptionIdsFlow.contains(defaultDataSubId)) {
                    commonViewModelForSub(defaultDataSubId ?: -1)
                } else {
                    null
                }
            }
            .flatMapLatest { viewModel ->
                viewModel?.icon ?: flowOf(null)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    val mobileSubViewModels: StateFlow<List<MobileIconViewModelCommon>> =
        subscriptionIdsFlow
            .map { ids -> ids.map { commonViewModelForSub(it) } }
            .stateIn(scope, SharingStarted.WhileSubscribed(), emptyList())
    /** Whether all of [mobileSubViewModels] are visible or not. */
    private val iconsAreAllVisible =
        mobileSubViewModels
            .flatMapLatest { viewModels ->
                combine(viewModels.map { it.isVisible }) { isVisibleArray ->
                    isVisibleArray.all { it }
                }
            }
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = COL_ICONS_VISIBLE,
                initialValue = false,
            )

    val isStackable: StateFlow<Boolean> =
        combine(iconsAreAllVisible, interactor.isStackable) { isVisible, isStackable ->
                isVisible && isStackable
            }
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = COL_IS_STACKABLE,
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)
    init {
        interactor.setDdsIconFLow(ddsIcon)
        scope.launch { subscriptionIdsFlow.collect { invalidateCaches(it) } }
    }
    fun viewModelForSub(subId: Int, location: StatusBarLocation): LocationBasedMobileViewModel {
        val common = commonViewModelForSub(subId)
        return LocationBasedMobileViewModel.viewModelForLocation(
            common,
            interactor.getMobileConnectionInteractorForSubId(subId),
            verboseLogger,
            location,
            scope,
        )
    }
    private fun commonViewModelForSub(subId: Int): MobileIconViewModelCommon {
        return reuseCache.getOrPut(subId) { createViewModel(subId) }.first
    }
    private fun createViewModel(subId: Int): Pair<MobileIconViewModel, CoroutineScope> {
        // Create a child scope so we can cancel it
        val vmScope = scope.createChildScope(newTracingContext("MobileIconViewModel"))
        val vm =
            MobileIconViewModel(
                subId,
                interactor.getMobileConnectionInteractorForSubId(subId),
                airplaneModeInteractor,
                constants,
                vmScope,
            )
        return Pair(vm, vmScope)
    }
    private fun CoroutineScope.createChildScope(extraContext: CoroutineContext) =
        CoroutineScope(coroutineContext + Job(coroutineContext[Job]) + extraContext)
    private fun invalidateCaches(subIds: List<Int>) {
        reuseCache.keys
            .filter { !subIds.contains(it) }
            .forEach { id ->
                reuseCache
                    .remove(id)
                    // Cancel the view model's scope after removing it
                    ?.second
                    ?.cancel()
            }
    }

    companion object {
        private const val LOGGING_PREFIX = "VM"
        private const val COL_IS_STACKABLE = "isStackable"
        private const val COL_ICONS_VISIBLE = "iconsAreAllVisible"
    }
}
