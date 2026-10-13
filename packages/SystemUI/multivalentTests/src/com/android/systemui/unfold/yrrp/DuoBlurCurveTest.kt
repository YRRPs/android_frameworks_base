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
import org.junit.Assert.assertThrows
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

    // A change detector on purpose: these values are user decisions recorded on the issue.
    @Test
    fun constantsMatchTheIssue() {
        assertThat(DuoBlurCurve.MAX_BLUR_PX).isEqualTo(220)
        assertThat(DuoBlurCurve.BLUR_CLEAR_PROGRESS).isEqualTo(0.95f)
        assertThat(DuoBlurCurve.EDGE_WIDTH_FRACTION).isEqualTo(0.6f)
        assertThat(DuoBlurCurve.TOUCH_UNBLOCK_PROGRESS).isEqualTo(0.8f)
        assertThat(DuoBlurCurve.OPEN_ANGLE_DEGREES).isEqualTo(165f)
    }

    private fun strips(
        blurredWidthPx: Float,
        anchor: DuoBlurCurve.Anchor,
        edgeStrips: Int,
        widthPx: Int = 1000,
        maxBlurPx: Int = 200,
    ) = DuoBlurCurve.blurStrips(
        widthPx, 2000, blurredWidthPx, maxBlurPx, anchor, edgeStrips = edgeStrips,
    )

    @Test
    fun strips_emptyWhenNothingBlurred() {
        assertThat(strips(0f, DuoBlurCurve.Anchor.RIGHT, 4)).isEmpty()
    }

    @Test
    fun strips_rightAnchor_rampFromFrontThenFullToEdge() {
        // Width 1000, blurred 800 (front at x = 200), edge 0.6 * 1000 = 600, 4 strips of 150.
        val rows = strips(800f, DuoBlurCurve.Anchor.RIGHT, 4)
        assertThat(rows).hasSize(5)
        val first = rows[0].toList()
        val last = rows[3].toList()
        val rest = rows[4].toList()
        assertThat(first)
            .containsExactly(50f, 0.25f, 200f, 0f, 350f, 2000f, 0f, 0f, 0f, 0f).inOrder()
        assertThat(last)
            .containsExactly(200f, 1f, 650f, 0f, 800f, 2000f, 0f, 0f, 0f, 0f).inOrder()
        assertThat(rest)
            .containsExactly(200f, 1f, 800f, 0f, 1000f, 2000f, 0f, 0f, 0f, 0f).inOrder()
    }

    @Test
    fun strips_leftAnchor_mirrorsTheRamp() {
        // Blurred share [0, 800]; the sharp side is to the right of x = 800.
        val rows = strips(800f, DuoBlurCurve.Anchor.LEFT, 4)
        assertThat(rows).hasSize(5)
        val first = rows[0].toList()
        val rest = rows[4].toList()
        assertThat(first)
            .containsExactly(50f, 0.25f, 650f, 0f, 800f, 2000f, 0f, 0f, 0f, 0f).inOrder()
        assertThat(rest)
            .containsExactly(200f, 1f, 0f, 0f, 200f, 2000f, 0f, 0f, 0f, 0f).inOrder()
    }

    @Test
    fun strips_truncatedWhenBlurredShareIsNarrowerThanTheEdge() {
        // Blurred 200 of 1000; edge strips are 150 wide, so one full strip and one 50 px strip.
        val rows = strips(200f, DuoBlurCurve.Anchor.RIGHT, 4)
        assertThat(rows).hasSize(2)
        assertThat(rows[1][2]).isEqualTo(950f)
        assertThat(rows[1][4]).isEqualTo(1000f)
        // The cut strip keeps its ramp value (2 of 4 strips).
        assertThat(rows[1][0]).isEqualTo(100f)
        assertThat(rows[1][1]).isEqualTo(0.5f)
    }

    @Test
    fun strips_noZeroWidthRemainderFromFloatBuildUp() {
        val exactEdge = strips(1080 * 0.6f, DuoBlurCurve.Anchor.RIGHT, 7, widthPx = 1080)
        assertThat(exactEdge).hasSize(7)
        val sliver = strips(600.0001f, DuoBlurCurve.Anchor.RIGHT, 4)
        assertThat(sliver).hasSize(4)
    }

    @Test
    fun strips_trailingSliverExtendsTheLastRowToTheEdge() {
        val right = strips(150.3f, DuoBlurCurve.Anchor.RIGHT, 4)
        assertThat(right).hasSize(1)
        assertThat(right[0][4]).isEqualTo(1000f)
        val left = strips(150.3f, DuoBlurCurve.Anchor.LEFT, 4)
        assertThat(left).hasSize(1)
        assertThat(left[0][2]).isEqualTo(0f)
    }

    @Test
    fun strips_tinyStripsAreMergedIntoThePreviousRow() {
        // Strips of 0.25 px: the first four merge into one row of 1 px, then the full region.
        val rows = DuoBlurCurve.blurStrips(
            1000, 2000, 100f, 200, DuoBlurCurve.Anchor.RIGHT,
            edgeStrips = 4, edgeWidthFraction = 0.001f,
        )
        assertThat(rows).hasSize(2)
        assertThat(rows[0][4]).isWithin(1e-3f).of(rows[1][2])
        assertThat(rows[1][4]).isEqualTo(1000f)
    }

    @Test
    fun strips_rejectBadTuning() {
        assertThrows(IllegalArgumentException::class.java) {
            strips(500f, DuoBlurCurve.Anchor.RIGHT, 0)
        }
        for (fraction in listOf(0f, -0.1f, 1.1f)) {
            assertThrows(IllegalArgumentException::class.java) {
                DuoBlurCurve.blurStrips(
                    1000, 2000, 500f, 200, DuoBlurCurve.Anchor.RIGHT, edgeWidthFraction = fraction,
                )
            }
        }
    }

    @Test
    fun strips_degenerateInputsAreEmpty() {
        assertThat(strips(Float.NaN, DuoBlurCurve.Anchor.RIGHT, 4)).isEmpty()
        assertThat(strips(-5f, DuoBlurCurve.Anchor.RIGHT, 4)).isEmpty()
        assertThat(strips(500f, DuoBlurCurve.Anchor.RIGHT, 4, widthPx = -1)).isEmpty()
        assertThat(strips(500f, DuoBlurCurve.Anchor.RIGHT, 4, maxBlurPx = 0)).isEmpty()
        val noHeight = DuoBlurCurve.blurStrips(1000, 0, 500f, 200, DuoBlurCurve.Anchor.RIGHT)
        assertThat(noHeight).isEmpty()
    }

    @Test
    fun strips_blurredWiderThanSurfaceIsClamped() {
        val rows = strips(1500f, DuoBlurCurve.Anchor.RIGHT, 4)
        assertThat(rows.last()[4]).isEqualTo(1000f)
        assertThat(rows.last()[2]).isAtLeast(0f)
    }

    @Test
    fun strips_defaultEdge_contiguousAndNeverWeakerAwayFromFront() {
        for (anchor in DuoBlurCurve.Anchor.values()) {
            for (share in listOf(0.1f, 0.5f, 1.0f)) {
                val rows = DuoBlurCurve.blurStrips(2268, 2076, 2268 * share, 220, anchor)
                assertThat(rows).isNotEmpty()
                for (i in 0 until rows.size - 1) {
                    val gap = if (anchor == DuoBlurCurve.Anchor.RIGHT) {
                        rows[i][4] - rows[i + 1][2]
                    } else {
                        rows[i][2] - rows[i + 1][4]
                    }
                    assertThat(gap).isWithin(1e-3f).of(0f)
                    assertThat(rows[i + 1][0]).isAtLeast(rows[i][0])
                    assertThat(rows[i + 1][1]).isAtLeast(rows[i][1])
                }
            }
        }
    }

    @Test
    fun frontPx_rightAnchorIsMeasuredFromTheLeft() {
        assertThat(DuoBlurCurve.frontPx(1000, 0.3f, DuoBlurCurve.Anchor.RIGHT)).isEqualTo(700)
        assertThat(DuoBlurCurve.frontPx(1000, 0.3f, DuoBlurCurve.Anchor.LEFT)).isEqualTo(300)
    }
}
