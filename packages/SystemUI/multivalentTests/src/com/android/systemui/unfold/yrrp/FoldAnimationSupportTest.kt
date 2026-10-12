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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class FoldAnimationSupportTest : SysuiTestCase() {

    @Test
    fun supported_whenUnfoldOnHingeAngleOnAndSensorPresent() {
        val support = create(unfold = true, hingeAngle = true, sensor = true)

        assertThat(support.isSupported).isTrue()
        assertThat(support.blockedBy).isNull()
    }

    @Test
    fun configDisabled_isReportedFirst() {
        assertThat(create(unfold = false, hingeAngle = false, sensor = false).blockedBy)
            .isEqualTo("CONFIG_DISABLED")
    }

    @Test
    fun hingeAngleConfigOff_isBlocked() {
        val support = create(unfold = true, hingeAngle = false, sensor = true)

        assertThat(support.isSupported).isFalse()
        assertThat(support.blockedBy).isEqualTo("HINGE_ANGLE_DISABLED")
    }

    @Test
    fun noHingeSensor_isBlocked() {
        val support = create(unfold = true, hingeAngle = true, sensor = false)

        assertThat(support.isSupported).isFalse()
        assertThat(support.blockedBy).isEqualTo("NO_HINGE_SENSOR")
    }

    @Test
    fun sensorIsQueriedOnce() {
        var queries = 0
        val config =
            mock<UnfoldTransitionConfig> {
                on { isEnabled } doReturn true
                on { isHingeAngleEnabled } doReturn true
            }
        val support = FoldAnimationSupport(config) { queries++; true }

        support.isSupported
        support.blockedBy
        support.hingeSensorPresent

        assertThat(queries).isEqualTo(1)
    }

    private fun create(unfold: Boolean, hingeAngle: Boolean, sensor: Boolean): FoldAnimationSupport {
        val config =
            mock<UnfoldTransitionConfig> {
                on { isEnabled } doReturn unfold
                on { isHingeAngleEnabled } doReturn hingeAngle
            }
        return FoldAnimationSupport(config) { sensor }
    }
}
