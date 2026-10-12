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

package com.android.systemui.unfold.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.FakeUnfoldTransitionProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class StyleGatedUnfoldTransitionProgressProviderTest : SysuiTestCase() {
    private val source = FakeUnfoldTransitionProvider()
    private val gate = FakeGate()
    private val events = mutableListOf<String>()
    private val underTest = StyleGatedUnfoldTransitionProgressProvider(source, gate)

    init {
        underTest.addCallback(
            object : TransitionProgressListener {
                override fun onTransitionStarted() {
                    events += "start"
                }

                override fun onTransitionProgress(progress: Float) {
                    events += "p$progress"
                }

                override fun onTransitionFinishing() {
                    events += "finishing"
                }

                override fun onTransitionFinished() {
                    events += "finish"
                }
            }
        )
    }

    @Test
    fun allowed_forwardsEverything() {
        gate.allow = true
        source.onTransitionStarted()
        source.onTransitionProgress(0.5f)
        source.onTransitionFinishing()
        source.onTransitionFinished()

        assertThat(events).containsExactly("start", "p0.5", "finishing", "finish").inOrder()
        assertThat(gate.started).isEqualTo(1)
        assertThat(gate.finished).isEqualTo(1)
    }

    @Test
    fun denied_swallowsEverythingButStillReleasesGate() {
        gate.allow = false
        source.onTransitionStarted()
        source.onTransitionProgress(0.5f)
        source.onTransitionFinished()

        assertThat(events).isEmpty()
        assertThat(gate.finished).isEqualTo(1)
    }

    @Test
    fun finishWithoutStart_forwardsNothingAndDoesNotReleaseGate() {
        gate.allow = true
        source.onTransitionFinished()

        assertThat(events).isEmpty()
        assertThat(gate.finished).isEqualTo(0)
    }

    @Test
    fun decisionHoldsForWholeTransitionEvenIfGateFlips() {
        gate.allow = true
        source.onTransitionStarted()
        gate.allow = false
        source.onTransitionProgress(0.3f)
        source.onTransitionFinished()

        assertThat(events).containsExactly("start", "p0.3", "finish").inOrder()
    }

    private class FakeGate : UnfoldTransitionStyleGate {
        var allow = true
        var started = 0
        var finished = 0

        override fun onGatedTransitionStarted(token: Any): Boolean {
            started++
            return allow
        }

        override fun onGatedTransitionFinished(token: Any) {
            finished++
        }
    }
}
