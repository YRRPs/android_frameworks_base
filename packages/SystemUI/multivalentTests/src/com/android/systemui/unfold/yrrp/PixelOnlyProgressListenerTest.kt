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
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PixelOnlyProgressListenerTest : SysuiTestCase() {
    private val selector = FoldAnimationStyleSelector()
    private val events = mutableListOf<String>()
    private val delegate =
        object : TransitionProgressListener {
            override fun onTransitionStarted() {
                events += "start"
            }

            override fun onTransitionProgress(progress: Float) {
                events += "p"
            }

            override fun onTransitionFinished() {
                events += "finish"
            }
        }
    private val underTest = PixelOnlyProgressListener(delegate, selector)

    @Test
    fun pixel_forwards() {
        selector.onSettingChanged(FoldAnimationStyle.PIXEL)
        val t = Any()
        selector.onGatedTransitionStarted(t)
        underTest.onTransitionStarted()
        underTest.onTransitionProgress(0.5f)
        underTest.onTransitionFinished()
        assertThat(events).containsExactly("start", "p", "finish").inOrder()
    }

    @Test
    fun duo_forwardsNothing() {
        selector.onSettingChanged(FoldAnimationStyle.DUO)
        selector.onGatedTransitionStarted(Any())
        underTest.onTransitionStarted()
        underTest.onTransitionProgress(0.5f)
        underTest.onTransitionFinished()
        assertThat(events).isEmpty()
    }
}
