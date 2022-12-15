/*
 * Copyright (C) 2022 Paranoid Android
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
package com.android.systemui.settings.brightness

import android.animation.ArgbEvaluator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.CheckBox
import com.android.settingslib.RestrictedLockUtils
import com.android.settingslib.Utils
import com.android.systemui.Dependency
import com.android.systemui.R
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.tileimpl.QSIconViewImpl

class ToggleIconView constructor(
        context: Context,
        attrs: AttributeSet?
) : CheckBox(context, attrs) {

    companion object {
        private const val ICON_NAME = "icon"
        private const val BACKGROUND_NAME = "background"
    }

    private val colorActive = Utils.getColorAttrDefaultColor(context, android.R.attr.colorAccent)
    private val colorInactive = Utils.getColorAttrDefaultColor(context, R.attr.offStateColor)
    private val colorSecondaryActive = Utils.getColorAttrDefaultColor(context, com.android.internal.R.attr.textColorPrimaryInverse)
    private val colorSecondaryInactive = Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary)

    private var currentBackgroundColor = colorInactive
    private val singleAnimator: ValueAnimator = ValueAnimator().apply {
        duration = QSIconViewImpl.QS_ANIM_LENGTH
        addUpdateListener { animation ->
            setColors(
                    animation.getAnimatedValue(ICON_NAME) as Int,
                    animation.getAnimatedValue(BACKGROUND_NAME) as Int
            )
        }
    }

    private var colorBackgroundDrawable: Drawable? = if (background is LayerDrawable) {
        (background as LayerDrawable).findDrawableByLayerId(R.id.background) ?: background
    } else {
        background
    }

    private var mEnforcedAdmin: RestrictedLockUtils.EnforcedAdmin? = null

    init {
        setColors(getIconColor(isChecked), getBackgroundColor(isChecked))
        jumpDrawablesToCurrentState()
    }

    private fun getIconColor(checked: Boolean): Int = if (checked) {
        colorSecondaryActive
    } else {
        colorSecondaryInactive
    }

    private fun getBackgroundColor(checked: Boolean): Int = if (checked) {
        colorActive
    } else {
        colorInactive
    }

    private fun setColors(iconColor: Int, backgroundColor: Int) {
        foregroundTintList = ColorStateList.valueOf(iconColor)
        colorBackgroundDrawable?.mutate()?.setTint(backgroundColor)
        currentBackgroundColor = backgroundColor
    }

    override fun setChecked(checked: Boolean) {
        if (checked != isChecked) {
            singleAnimator.cancel()
            val backgroundColor = getBackgroundColor(checked)
            val iconColor = getIconColor(checked)
            singleAnimator.setValues(
                    PropertyValuesHolder.ofInt(
                            ICON_NAME,
                            foregroundTintList?.defaultColor
                                    ?: 0,
                            iconColor
                    ).apply { setEvaluator(ArgbEvaluator.getInstance()) },
                    PropertyValuesHolder.ofInt(
                            BACKGROUND_NAME,
                            currentBackgroundColor,
                            backgroundColor
                    ).apply { setEvaluator(ArgbEvaluator.getInstance()) }
            )
            singleAnimator.start()
        }

        super.setChecked(checked)
    }

    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        if (singleAnimator.isStarted) {
            singleAnimator.end()
        }
    }

    override fun onFilterTouchEventForSecurity(event: MotionEvent?): Boolean {
        if (mEnforcedAdmin != null) {
            val intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                    mContext, mEnforcedAdmin)
            Dependency.get(ActivityStarter::class.java).postStartActivityDismissingKeyguard(intent, 0)
            return true
        }
        return super.onFilterTouchEventForSecurity(event)
    }

    fun setEnforcedAdmin(admin: RestrictedLockUtils.EnforcedAdmin?) {
        mEnforcedAdmin = admin
    }
}
