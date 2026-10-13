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

/**
 * Natural panel 1000 x 2000 with the rect [100, 200, 300, 600]. Expected rects are worked by
 * hand from RotationUtils.rotateBounds, which DisplayCutout uses for the same mapping.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class DuoRegionRotationTest : SysuiTestCase() {
    private val row = floatArrayOf(40f, 0.5f, 100f, 200f, 300f, 600f, 1f, 2f, 3f, 4f)

    @Test
    fun rotation0_keepsTheRect() {
        assertThat(rect(rotated(0))).isEqualTo(listOf(100f, 200f, 300f, 600f))
    }

    @Test
    fun rotation90_mapsTopToLeftAndRightToTop() {
        assertThat(rect(rotated(1))).isEqualTo(listOf(200f, 700f, 600f, 900f))
    }

    @Test
    fun rotation180_mirrorsBothAxes() {
        assertThat(rect(rotated(2))).isEqualTo(listOf(700f, 1400f, 900f, 1800f))
    }

    @Test
    fun rotation270_mapsBottomToLeftAndLeftToTop() {
        assertThat(rect(rotated(3))).isEqualTo(listOf(1400f, 100f, 1800f, 300f))
    }

    @Test
    fun keepsRadiusAlphaAndCorners_andLeavesTheInputAlone() {
        val out = rotated(1)
        assertThat(out.take(2)).isEqualTo(listOf(40f, 0.5f))
        assertThat(out.drop(6)).isEqualTo(listOf(1f, 2f, 3f, 4f))
        assertThat(row.toList())
            .isEqualTo(listOf(40f, 0.5f, 100f, 200f, 300f, 600f, 1f, 2f, 3f, 4f))
    }

    @Test
    fun aRightAnchoredStripOnALandscapeInnerScreen_landsAlongTheTop() {
        // Natural 2000 x 1000: the right half, rotated 90, covers the top half of 1000 x 2000.
        val strip = floatArrayOf(10f, 1f, 1000f, 0f, 2000f, 1000f, 0f, 0f, 0f, 0f)
        val out = DuoRegionRotation.toLogical(listOf(strip), 1, 2000, 1000).single()
        assertThat(rect(out.toList())).isEqualTo(listOf(0f, 0f, 1000f, 1000f))
    }

    @Test
    fun rejectsAnUnknownRotation() {
        assertThrows(IllegalArgumentException::class.java) {
            DuoRegionRotation.toLogical(listOf(row), 4, 1000, 2000)
        }
    }

    private fun rotated(rotation: Int): List<Float> =
        DuoRegionRotation.toLogical(listOf(row), rotation, 1000, 2000).single().toList()

    private fun rect(values: List<Float>) = values.subList(2, 6)
}
