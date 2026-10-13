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
    fun coverCoverage_startsAtZero_reachesFullAtOpen() {
        assertThat(DuoBlurCurve.coverCoverage(0f)).isEqualTo(0f)
        assertThat(DuoBlurCurve.coverCoverage(1f)).isEqualTo(1f)
    }

    @Test
    fun coverCoverage_easesOut() {
        // 1 - (1 - 0.5)^2 = 0.75
        assertThat(DuoBlurCurve.coverCoverage(0.5f)).isWithin(1e-6f).of(0.75f)
    }

    @Test
    fun coverCoverage_clamps() {
        assertThat(DuoBlurCurve.coverCoverage(-1f)).isEqualTo(0f)
        assertThat(DuoBlurCurve.coverCoverage(2f)).isEqualTo(1f)
    }

    @Test
    fun innerRemaining_fullAtStart_clearAtClearPoint() {
        assertThat(DuoBlurCurve.innerRemaining(0f)).isEqualTo(1f)
        assertThat(DuoBlurCurve.innerRemaining(0.95f)).isEqualTo(0f)
        assertThat(DuoBlurCurve.innerRemaining(1f)).isEqualTo(0f)
    }

    @Test
    fun innerRemaining_easesIn_mostlyBlurredAt60Degrees() {
        // 60 degrees of 165 is p = 0.3636; t = p / 0.95; remaining = 1 - t^2, about 0.853
        val p = 60f / 165f
        val t = p / 0.95f
        assertThat(DuoBlurCurve.innerRemaining(p)).isWithin(1e-5f).of(1f - t * t)
        assertThat(DuoBlurCurve.innerRemaining(p)).isGreaterThan(0.85f)
    }

    @Test
    fun constantsMatchTheIssue() {
        assertThat(DuoBlurCurve.MAX_BLUR_PX).isEqualTo(220)
        assertThat(DuoBlurCurve.BLUR_CLEAR_PROGRESS).isEqualTo(0.95f)
        assertThat(DuoBlurCurve.EDGE_WIDTH_FRACTION).isEqualTo(0.6f)
        assertThat(DuoBlurCurve.TOUCH_UNBLOCK_PROGRESS).isEqualTo(0.8f)
        assertThat(DuoBlurCurve.OPEN_ANGLE_DEGREES).isEqualTo(165f)
    }
}
