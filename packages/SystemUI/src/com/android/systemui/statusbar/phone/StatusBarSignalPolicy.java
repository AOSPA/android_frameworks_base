/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.common.shared.model.ContentDescription.loadContentDescription;

import android.annotation.Nullable;
import android.content.Context;
import android.os.Handler;
import android.util.ArraySet;

import com.android.systemui.CoreStartable;
import com.android.systemui.common.shared.model.Icon;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.ui.StatusBarIconController;
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor;
import com.android.systemui.statusbar.pipeline.ethernet.domain.EthernetInteractor;
import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.kotlin.JavaAdapter;

import javax.inject.Inject;

/** Controls the signal policies for icons shown in the statusbar. */
@SysUISingleton
public class StatusBarSignalPolicy
        implements SecurityController.SecurityControllerCallback,
                Tunable,
                CoreStartable {
    private final String mSlotAirplane;
    private final String mSlotMobile;
    private final String mSlotEthernet;
    private final String mSlotVpn;

    private final Context mContext;
    private final StatusBarIconController mIconController;
    private final SecurityController mSecurityController;
    private final Handler mHandler = Handler.getMain();
    private final TunerService mTunerService;
    private final JavaAdapter mJavaAdapter;
    private final AirplaneModeInteractor mAirplaneModeInteractor;
    private final EthernetInteractor mEthernetInteractor;

    private boolean mHideAirplane;
    private boolean mHideMobile;
    private boolean mHideEthernet;
    private boolean mHideVpn;


    @Inject
    public StatusBarSignalPolicy(
            Context context,
            StatusBarIconController iconController,
            SecurityController securityController,
            TunerService tunerService,
            JavaAdapter javaAdapter,
            AirplaneModeInteractor airplaneModeInteractor,
            EthernetInteractor ethernetInteractor
    ) {
        mContext = context;

        mIconController = iconController;
        mJavaAdapter = javaAdapter;
        mSecurityController = securityController;
        mTunerService = tunerService;
        mAirplaneModeInteractor = airplaneModeInteractor;
        mEthernetInteractor = ethernetInteractor;

        mSlotAirplane = mContext.getString(com.android.internal.R.string.status_bar_airplane);
        mSlotMobile   = mContext.getString(com.android.internal.R.string.status_bar_mobile);
        mSlotEthernet = mContext.getString(com.android.internal.R.string.status_bar_ethernet);
        mSlotVpn      = mContext.getString(com.android.internal.R.string.status_bar_vpn);
    }

    @Override
    public void start() {
        mTunerService.addTunable(this, StatusBarIconController.ICON_HIDE_LIST);
        mSecurityController.addCallback(this);

        mJavaAdapter.alwaysCollectFlow(
                mAirplaneModeInteractor.isAirplaneMode(), this::updateAirplaneModeIcon);
        mJavaAdapter.alwaysCollectFlow(mEthernetInteractor.getIcon(), this::updateEthernetIcon);
    }

    private void updateVpn() {
        boolean vpnVisible = mSecurityController.isVpnEnabled() && !mHideVpn;
        int vpnIconId = currentVpnIconId(
                mSecurityController.isVpnBranded(),
                mSecurityController.isVpnValidated());

        mIconController.setIcon(mSlotVpn, vpnIconId,
                mContext.getResources().getString(R.string.accessibility_vpn_on));
        mIconController.setIconVisibility(mSlotVpn, vpnVisible);
    }

    private int currentVpnIconId(boolean isBranded, boolean isValidated) {
        if (isBranded) {
            return isValidated
                    ? R.drawable.stat_sys_branded_vpn
                    : R.drawable.stat_sys_no_internet_branded_vpn;
        } else {
            return isValidated
                    ? R.drawable.stat_sys_vpn_ic
                    : R.drawable.stat_sys_no_internet_vpn_ic;
        }
    }

    /**
     * From SecurityController
     */
    @Override
    public void onStateChanged() {
        mHandler.post(this::updateVpn);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!StatusBarIconController.ICON_HIDE_LIST.equals(key)) {
            return;
        }
        ArraySet<String> hideList = StatusBarIconController.getIconHideList(mContext, newValue);
        boolean hideAirplane = hideList.contains(mSlotAirplane);
        boolean hideMobile = hideList.contains(mSlotMobile);
        boolean hideEthernet = hideList.contains(mSlotEthernet);
        boolean hideVpn = hideList.contains(mSlotVpn);

        if (hideAirplane != mHideAirplane || hideMobile != mHideMobile
                || hideEthernet != mHideEthernet || hideVpn != mHideVpn) {
            mHideAirplane = hideAirplane;
            mHideMobile = hideMobile;
            mHideEthernet = hideEthernet;
            mHideVpn = hideVpn;
            mHandler.post(this::updateVpn);
        }
    }

    private void updateEthernetIcon(@Nullable Icon.Resource ethernetIcon) {
        if (ethernetIcon != null) {
            mIconController.setIcon(
                    mSlotEthernet,
                    ethernetIcon.getResId(),
                    loadContentDescription(ethernetIcon.getContentDescription(), mContext));
            mIconController.setIconVisibility(mSlotEthernet, true);
        } else {
            mIconController.setIconVisibility(mSlotEthernet, false);
        }
    }

    private void updateAirplaneModeIcon(boolean isAirplaneModeOn) {
        boolean isAirplaneMode = isAirplaneModeOn && !mHideAirplane;
        mIconController.setIconVisibility(mSlotAirplane, isAirplaneMode);
        if (isAirplaneMode) {
            mIconController.setIcon(
                    mSlotAirplane,
                    R.drawable.stat_sys_airplane_mode,
                    mContext.getString(R.string.accessibility_airplane_mode));
        }
    }
}
