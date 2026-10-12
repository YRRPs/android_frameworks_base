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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DuoBlurCurveTest : SysuiTestCase() {
    @Test
    fun fullBlurAtStart_noneFromClearPoint() {
        assertThat(DuoBlurCurve.radiusFor(0f)).isEqualTo(120)
        assertThat(DuoBlurCurve.radiusFor(0.85f)).isEqualTo(0)
        assertThat(DuoBlurCurve.radiusFor(1f)).isEqualTo(0)
    }

    @Test
    fun deceleratesBetween() {
        // t = 0.5 / 0.85; decelerate 1 - (1 - t)^2; radius = 120 * (1 - that)
        val t = 0.5f / 0.85f
        val expected = Math.round(120 * (1 - t) * (1 - t))
        assertThat(DuoBlurCurve.radiusFor(0.5f)).isEqualTo(expected)
    }

    @Test
    fun clampsOutOfRange() {
        assertThat(DuoBlurCurve.radiusFor(-1f)).isEqualTo(120)
        assertThat(DuoBlurCurve.radiusFor(2f)).isEqualTo(0)
    }
}
