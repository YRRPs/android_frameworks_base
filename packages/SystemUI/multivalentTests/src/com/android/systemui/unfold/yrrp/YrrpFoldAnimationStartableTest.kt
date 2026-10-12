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
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class YrrpFoldAnimationStartableTest : SysuiTestCase() {
    private val selector = FoldAnimationStyleSelector()
    private val runtime = FoldAnimationRuntimeState()
    private val raw = MutableStateFlow(0)
    private val repository = mock<FoldAnimationSettingsRepository>()
    private val dumpManager = mock<DumpManager>()
    private val config =
        mock<UnfoldTransitionConfig> {
            on { isEnabled } doReturn true
            on { isHingeAngleEnabled } doReturn true
        }

    @Test
    fun pushesSettingIntoSelector_andRegistersDumpable() = runTest {
        whenever(repository.setting).thenReturn(raw)
        val underTest = create(this)
        underTest.start()
        runCurrent()
        verify(dumpManager).registerNormalDumpable(YrrpFoldAnimationStartable.TAG, underTest)

        raw.value = 2
        runCurrent()
        assertThat(selector.settingStyle()).isEqualTo(FoldAnimationStyle.DUO)
    }

    @Test
    fun dump_listsEveryGate_andBlockedByStockForZero() = runTest {
        whenever(repository.setting).thenReturn(raw)
        val underTest = create(this)
        underTest.start()
        runCurrent()

        val out = StringWriter()
        underTest.dump(PrintWriter(out), emptyArray())
        val text = out.toString()
        listOf(
                "settingValue=0",
                "selectedStyle=STOCK",
                "latchedStyle=none",
                "configEnabled=true",
                "hingeAngleEnabled=true",
                "animationsEnabled=",
                "blurAvailable=",
                "eligible=false",
                "blockedBy=STYLE_STOCK",
                "phase=IDLE",
                "overlayAttached=false",
                "starts=0",
                "completions=0",
                "cancellations=0",
                "lastCancelReason=none",
            )
            .forEach { assertThat(text).contains(it) }
    }

    @Test
    fun dump_duoWithAnimationsOn_isEligible() = runTest {
        whenever(repository.setting).thenReturn(raw)
        raw.value = 2
        val underTest = create(this, animationsEnabled = true)
        underTest.start()
        runCurrent()

        val out = StringWriter()
        underTest.dump(PrintWriter(out), emptyArray())
        assertThat(out.toString()).contains("eligible=true")
        assertThat(out.toString()).contains("blockedBy=NONE")
    }

    private fun create(scope: TestScope, animationsEnabled: Boolean = true) =
        YrrpFoldAnimationStartable(
            repository = repository,
            selector = selector,
            runtimeState = runtime,
            config = config,
            dumpManager = dumpManager,
            // A real buffer: a bare mock returns null from obtain() and the inline log() NPEs.
            logBuffer = logcatLogBuffer("YrrpFoldAnimationLog"),
            backgroundScope = scope.backgroundScope,
            animationsEnabled = { animationsEnabled },
        )
}
