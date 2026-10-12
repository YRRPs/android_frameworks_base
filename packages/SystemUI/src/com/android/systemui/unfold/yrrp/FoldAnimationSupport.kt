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

package com.android.systemui.unfold.yrrp

import android.hardware.Sensor
import android.hardware.SensorManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import javax.inject.Inject

/**
 * Whether this device can play the YRRP fold animation: the device tree turned on SystemUI's
 * unfold framework (`config_unfoldTransitionEnabled`), drives it by hinge angle
 * (`config_unfoldTransitionHingeAngle`), and the hardware reports a `TYPE_HINGE_ANGLE` sensor.
 * Settings shows the page under the same three conditions. On any other device the feature stays
 * Stock and does nothing.
 */
@SysUISingleton
class FoldAnimationSupport(
    private val config: UnfoldTransitionConfig,
    hingeSensorQuery: () -> Boolean,
) {
    @Inject
    constructor(
        config: UnfoldTransitionConfig,
        sensorManager: SensorManager,
    ) : this(config, { sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null })

    val hingeSensorPresent: Boolean by lazy(hingeSensorQuery)

    /** The first missing condition, or null when supported. */
    val blockedBy: String?
        get() =
            when {
                !config.isEnabled -> "CONFIG_DISABLED"
                !config.isHingeAngleEnabled -> "HINGE_ANGLE_DISABLED"
                !hingeSensorPresent -> "NO_HINGE_SENSOR"
                else -> null
            }

    val isSupported: Boolean
        get() = blockedBy == null
}
