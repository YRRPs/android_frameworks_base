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
import com.android.wm.shell.unfold.UnfoldAnimationStyleProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FoldAnimationStyleSelectorTest : SysuiTestCase() {
    private val underTest = FoldAnimationStyleSelector()

    @Test
    fun fromRaw_normalizesUnknownToStock() {
        assertThat(FoldAnimationStyle.fromRaw(0)).isEqualTo(FoldAnimationStyle.STOCK)
        assertThat(FoldAnimationStyle.fromRaw(1)).isEqualTo(FoldAnimationStyle.PIXEL)
        assertThat(FoldAnimationStyle.fromRaw(2)).isEqualTo(FoldAnimationStyle.DUO)
        assertThat(FoldAnimationStyle.fromRaw(3)).isEqualTo(FoldAnimationStyle.STOCK)
        assertThat(FoldAnimationStyle.fromRaw(-1)).isEqualTo(FoldAnimationStyle.STOCK)
    }

    @Test
    fun defaultsToStock_andStockDeniesTransitions() {
        assertThat(underTest.currentStyle()).isEqualTo(FoldAnimationStyle.STOCK)
        assertThat(underTest.onGatedTransitionStarted(Any())).isFalse()
    }

    @Test
    fun pixelAndDuoAllowTransitions() {
        underTest.onSettingChanged(FoldAnimationStyle.PIXEL)
        val a = Any()
        assertThat(underTest.onGatedTransitionStarted(a)).isTrue()
        underTest.onGatedTransitionFinished(a)
        underTest.onSettingChanged(FoldAnimationStyle.DUO)
        assertThat(underTest.onGatedTransitionStarted(Any())).isTrue()
    }

    @Test
    fun latchHoldsThroughSettingChange_andReleasesOnFinish() {
        underTest.onSettingChanged(FoldAnimationStyle.DUO)
        val token = Any()
        underTest.onGatedTransitionStarted(token)
        underTest.onSettingChanged(FoldAnimationStyle.STOCK)

        assertThat(underTest.currentStyle()).isEqualTo(FoldAnimationStyle.DUO)
        assertThat(underTest.getUnfoldAnimationStyle())
            .isEqualTo(UnfoldAnimationStyleProvider.STYLE_DUO)

        underTest.onGatedTransitionFinished(token)
        assertThat(underTest.latchedStyle()).isNull()
        assertThat(underTest.currentStyle()).isEqualTo(FoldAnimationStyle.STOCK)
    }

    @Test
    fun secondTokenJoinsExistingLatch() {
        underTest.onSettingChanged(FoldAnimationStyle.PIXEL)
        val main = Any()
        val bg = Any()
        underTest.onGatedTransitionStarted(main)
        underTest.onSettingChanged(FoldAnimationStyle.STOCK)

        assertThat(underTest.onGatedTransitionStarted(bg)).isTrue()
        underTest.onGatedTransitionFinished(main)
        assertThat(underTest.latchedStyle()).isEqualTo(FoldAnimationStyle.PIXEL)
        underTest.onGatedTransitionFinished(bg)
        assertThat(underTest.latchedStyle()).isNull()
        assertThat(underTest.starts).isEqualTo(1)
        assertThat(underTest.completions).isEqualTo(1)
    }

    @Test
    fun unknownTokenFinish_isIgnored() {
        underTest.onGatedTransitionFinished(Any())
        assertThat(underTest.completions).isEqualTo(0)
    }

    @Test
    fun releaseAll_dropsLatchAndTokens_andReportsForcedRelease() {
        val seen = mutableListOf<String>()
        underTest.listener =
            object : FoldAnimationStyleSelector.Listener {
                override fun onLatched(style: FoldAnimationStyle) {}
                override fun onReleased(style: FoldAnimationStyle) { seen += "released" }
                override fun onForceReleased(style: FoldAnimationStyle, reason: String) {
                    seen += "forced=$style/$reason"
                }
            }
        underTest.onSettingChanged(FoldAnimationStyle.DUO)
        val token = Any()
        underTest.onGatedTransitionStarted(token)

        underTest.releaseAll("SLEEP")

        assertThat(underTest.latchedStyle()).isNull()
        assertThat(underTest.isActive(token)).isFalse()
        assertThat(seen).containsExactly("forced=DUO/SLEEP")
        // The lost finish arriving later changes nothing.
        underTest.onGatedTransitionFinished(token)
        assertThat(underTest.completions).isEqualTo(0)
        // The next start latches afresh.
        underTest.onSettingChanged(FoldAnimationStyle.PIXEL)
        underTest.onGatedTransitionStarted(Any())
        assertThat(underTest.latchedStyle()).isEqualTo(FoldAnimationStyle.PIXEL)
        assertThat(underTest.starts).isEqualTo(2)
    }

    @Test
    fun releaseAll_withoutLatch_isSilent() {
        var forced = 0
        underTest.listener =
            object : FoldAnimationStyleSelector.Listener {
                override fun onLatched(style: FoldAnimationStyle) {}
                override fun onReleased(style: FoldAnimationStyle) {}
                override fun onForceReleased(style: FoldAnimationStyle, reason: String) { forced++ }
            }
        underTest.releaseAll("SLEEP")
        assertThat(forced).isEqualTo(0)
    }

    @Test
    fun latchIfIdle_latchesOnce_andGatedStartJoinsWithoutDoubleCount() {
        underTest.onSettingChanged(FoldAnimationStyle.DUO)
        underTest.latchIfIdle()
        underTest.latchIfIdle()
        underTest.onSettingChanged(FoldAnimationStyle.STOCK)

        assertThat(underTest.currentStyle()).isEqualTo(FoldAnimationStyle.DUO)
        assertThat(underTest.starts).isEqualTo(1)

        val token = Any()
        assertThat(underTest.onGatedTransitionStarted(token)).isTrue()
        assertThat(underTest.starts).isEqualTo(1)

        underTest.onGatedTransitionFinished(token)
        assertThat(underTest.latchedStyle()).isNull()
        assertThat(underTest.completions).isEqualTo(1)
    }

    @Test
    fun latchIfIdle_whileLatched_keepsExistingStyle() {
        underTest.onSettingChanged(FoldAnimationStyle.PIXEL)
        underTest.onGatedTransitionStarted(Any())
        underTest.onSettingChanged(FoldAnimationStyle.DUO)

        assertThat(underTest.latchIfIdle()).isEqualTo(FoldAnimationStyle.PIXEL)
        assertThat(underTest.starts).isEqualTo(1)
    }

    @Test
    fun listenerSeesLatchAndRelease() {
        val seen = mutableListOf<String>()
        underTest.listener =
            object : FoldAnimationStyleSelector.Listener {
                override fun onLatched(style: FoldAnimationStyle) { seen += "latched=$style" }
                override fun onReleased(style: FoldAnimationStyle) { seen += "released=$style" }
            }
        underTest.onSettingChanged(FoldAnimationStyle.DUO)
        val t = Any()
        underTest.onGatedTransitionStarted(t)
        underTest.onGatedTransitionFinished(t)
        assertThat(seen).containsExactly("latched=DUO", "released=DUO").inOrder()
    }
}
