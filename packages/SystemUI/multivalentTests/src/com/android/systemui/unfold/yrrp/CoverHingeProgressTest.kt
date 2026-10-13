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
class CoverHingeProgressTest : SysuiTestCase() {
    private val progress = CoverHingeProgress(openAngleDegrees = 165f)

    @Test
    fun listensOnlyWhileFoldedAwakeAndDuo() {
        assertThat(progress.shouldListen(folded = true, awake = true, duo = true)).isTrue()
        assertThat(progress.shouldListen(folded = false, awake = true, duo = true)).isFalse()
        assertThat(progress.shouldListen(folded = true, awake = false, duo = true)).isFalse()
        assertThat(progress.shouldListen(folded = true, awake = true, duo = false)).isFalse()
    }

    @Test
    fun angleMapsLinearlyToProgress_clamped() {
        assertThat(progress.progressFor(0f)).isEqualTo(0f)
        assertThat(progress.progressFor(82.5f)).isEqualTo(0.5f)
        assertThat(progress.progressFor(165f)).isEqualTo(1f)
        assertThat(progress.progressFor(200f)).isEqualTo(1f)
        assertThat(progress.progressFor(-3f)).isEqualTo(0f)
    }

    @Test
    fun notANumberAngleMapsToClosed() {
        assertThat(progress.progressFor(Float.NaN)).isEqualTo(0f)
    }

    @Test
    fun startsOnlyWhenTheHingeLeavesZero() {
        assertThat(progress.hasLeftClosed(0f)).isFalse()
        assertThat(progress.hasLeftClosed(0.4f)).isFalse()
        assertThat(progress.hasLeftClosed(0.99f)).isFalse()
        assertThat(progress.hasLeftClosed(-1f)).isFalse()
        assertThat(progress.hasLeftClosed(1f)).isTrue()
    }

    @Test
    fun rejectsANonPositiveOpenAngle() {
        assertThrows(IllegalArgumentException::class.java) { CoverHingeProgress(0f) }
    }
}
