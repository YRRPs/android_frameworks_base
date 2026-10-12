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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DuoFoldLockEnforcerTest : SysuiTestCase() {
    private val settings = FakeFoldLockSettings()
    private val logs = mutableListOf<String>()

    @Test
    fun duo_withNever_forcesAlwaysForThatUser() = runTest {
        val underTest = start(this)

        settings.state.value = FoldLockState(userId = 10, styleRaw = 2, value = NEVER)
        runCurrent()

        assertThat(settings.writes).containsExactly(10)
        assertThat(settings.state.value.value).isEqualTo(DuoFoldLockEnforcer.ALWAYS)
        assertThat(underTest.foldLockForced).isTrue()
        assertThat(underTest.foldLockValue).isEqualTo(DuoFoldLockEnforcer.ALWAYS)
        assertThat(logs.single()).isEqualTo("foldLock forced from=$NEVER user=10")
    }

    @Test
    fun duo_withUnsetValue_forcesAlways() = runTest {
        start(this)

        settings.state.value = FoldLockState(userId = 0, styleRaw = 2, value = null)
        runCurrent()

        assertThat(settings.writes).containsExactly(0)
    }

    @Test
    fun duo_withAlways_writesNothing() = runTest {
        val underTest = start(this)

        settings.state.value =
            FoldLockState(userId = 0, styleRaw = 2, value = DuoFoldLockEnforcer.ALWAYS)
        runCurrent()

        assertThat(settings.writes).isEmpty()
        assertThat(underTest.foldLockForced).isFalse()
        assertThat(logs).isEmpty()
    }

    @Test
    fun stockAndPixel_leaveAnyValueAlone() = runTest {
        val underTest = start(this)

        settings.state.value = FoldLockState(userId = 0, styleRaw = 0, value = NEVER)
        runCurrent()
        settings.state.value = FoldLockState(userId = 0, styleRaw = 1, value = SWIPE)
        runCurrent()

        assertThat(settings.writes).isEmpty()
        assertThat(underTest.foldLockValue).isEqualTo(SWIPE)
        assertThat(underTest.foldLockForced).isFalse()
    }

    @Test
    fun leavingDuo_afterARewrite_clearsForced() = runTest {
        val underTest = start(this)
        settings.state.value = FoldLockState(userId = 0, styleRaw = 2, value = NEVER)
        runCurrent()

        settings.state.value = FoldLockState(userId = 0, styleRaw = 0, value = NEVER)
        runCurrent()

        assertThat(underTest.foldLockForced).isFalse()
        assertThat(settings.writes).containsExactly(0)
    }

    @Test
    fun failedWrite_isLoggedAndNotCountedAsForced() = runTest {
        settings.failWrites = true
        val underTest = start(this)

        settings.state.value = FoldLockState(userId = 0, styleRaw = 2, value = NEVER)
        runCurrent()

        assertThat(underTest.foldLockForced).isFalse()
        assertThat(logs.single()).isEqualTo("foldLock force failed from=$NEVER user=0")
    }

    @Test
    fun styleLeftDuoBeforeTheForce_writesNothing() = runTest {
        start(this)
        // The emission still says Duo, but Settings has since written Stock and restored Never.
        settings.freshStyleRaw = 0

        settings.state.value = FoldLockState(userId = 0, styleRaw = 2, value = NEVER)
        runCurrent()

        assertThat(settings.writes).isEmpty()
        assertThat(settings.styleReads).containsExactly(0)
    }

    @Test
    fun beforeStart_reportsUnknownValue() {
        val underTest = DuoFoldLockEnforcer(settings) { logs += it }

        assertThat(underTest.foldLockValue).isNull()
        assertThat(underTest.foldLockForced).isFalse()
    }

    private fun start(scope: TestScope): DuoFoldLockEnforcer =
        DuoFoldLockEnforcer(settings) { logs += it }.also { it.start(scope.backgroundScope) }

    /** Holds one user's state; a successful force writes Always back into it, as Settings does. */
    private class FakeFoldLockSettings : FoldLockSettings {
        override val state =
            MutableStateFlow(FoldLockState(userId = 0, styleRaw = 0, value = null))
        val writes = mutableListOf<Int>()
        val styleReads = mutableListOf<Int>()
        var failWrites = false
        /** What a fresh read of the style returns; null reads the emitted style. */
        var freshStyleRaw: Int? = null

        override fun readStyleRaw(userId: Int): Int {
            styleReads += userId
            return freshStyleRaw ?: state.value.styleRaw
        }

        override fun forceAlways(userId: Int): Boolean {
            writes += userId
            if (failWrites) return false
            state.value = state.value.copy(value = DuoFoldLockEnforcer.ALWAYS)
            return true
        }
    }

    private companion object {
        const val NEVER = "sleep_on_fold_key"
        const val SWIPE = "selective_stay_awake_key"
    }
}
