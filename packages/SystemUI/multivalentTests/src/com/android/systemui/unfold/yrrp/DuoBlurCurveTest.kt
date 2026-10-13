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

    @Test
    fun strips_emptyWhenNothingBlurred() {
        val rows = DuoBlurCurve.blurStrips(1000, 2000, blurredWidthPx = 0f, maxBlurPx = 220,
            anchor = DuoBlurCurve.Anchor.RIGHT, edgeStrips = 4)
        assertThat(rows).isEmpty()
    }

    @Test
    fun strips_rightAnchor_rampFromFrontThenFullToEdge() {
        // Width 1000, blurred 800 (front at x = 200), edge 0.6 * 1000 = 600, 4 strips of 150.
        val rows = DuoBlurCurve.blurStrips(1000, 2000, blurredWidthPx = 800f, maxBlurPx = 200,
            anchor = DuoBlurCurve.Anchor.RIGHT, edgeStrips = 4)
        assertThat(rows).hasSize(5)
        assertThat(rows[0].toList()).containsExactly(50f, 0.25f, 200f, 0f, 350f, 2000f, 0f, 0f, 0f, 0f).inOrder()
        assertThat(rows[3].toList()).containsExactly(200f, 1f, 650f, 0f, 800f, 2000f, 0f, 0f, 0f, 0f).inOrder()
        assertThat(rows[4].toList()).containsExactly(200f, 1f, 800f, 0f, 1000f, 2000f, 0f, 0f, 0f, 0f).inOrder()
    }

    @Test
    fun strips_leftAnchor_mirrorsTheRamp() {
        // Blurred share [0, 800]; the sharp side is to the right of x = 800.
        val rows = DuoBlurCurve.blurStrips(1000, 2000, blurredWidthPx = 800f, maxBlurPx = 200,
            anchor = DuoBlurCurve.Anchor.LEFT, edgeStrips = 4)
        assertThat(rows).hasSize(5)
        assertThat(rows[0].toList()).containsExactly(50f, 0.25f, 650f, 0f, 800f, 2000f, 0f, 0f, 0f, 0f).inOrder()
        assertThat(rows[4].toList()).containsExactly(200f, 1f, 0f, 0f, 200f, 2000f, 0f, 0f, 0f, 0f).inOrder()
    }

    @Test
    fun strips_truncatedWhenBlurredShareIsNarrowerThanTheEdge() {
        // Blurred 200 of 1000; edge strips are 150 wide, so one full strip and one 50 px strip.
        val rows = DuoBlurCurve.blurStrips(1000, 2000, blurredWidthPx = 200f, maxBlurPx = 200,
            anchor = DuoBlurCurve.Anchor.RIGHT, edgeStrips = 4)
        assertThat(rows).hasSize(2)
        assertThat(rows[1][2]).isEqualTo(950f)
        assertThat(rows[1][4]).isEqualTo(1000f)
    }

    @Test
    fun frontPx_rightAnchorIsMeasuredFromTheLeft() {
        assertThat(DuoBlurCurve.frontPx(1000, 0.3f, DuoBlurCurve.Anchor.RIGHT)).isEqualTo(700)
        assertThat(DuoBlurCurve.frontPx(1000, 0.3f, DuoBlurCurve.Anchor.LEFT)).isEqualTo(300)
    }
}
