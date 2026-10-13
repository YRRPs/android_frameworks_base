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
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class YrrpFoldAnimationStartableTest : SysuiTestCase() {
    private val selector = FoldAnimationStyleSelector()
    private val runtime = FoldAnimationRuntimeState()
    private val raw = MutableStateFlow(0)
    private val isAsleep = MutableStateFlow(false)
    private val powerInteractor =
        mock<PowerInteractor> { on { isAsleep } doReturn this@YrrpFoldAnimationStartableTest.isAsleep }
    private val repository = mock<FoldAnimationSettingsRepository>()
    private val dumpManager = mock<DumpManager>()
    private val config =
        mock<UnfoldTransitionConfig> {
            on { isEnabled } doReturn true
            on { isHingeAngleEnabled } doReturn true
        }

    private var hingeSensor = true
    private var styleChanges = 0
    private val foldLockState =
        MutableStateFlow(FoldLockState(userId = 0, styleRaw = 0, value = null))
    private val foldLockWrites = mutableListOf<Int>()
    private val foldLockSettings =
        object : FoldLockSettings {
            override val state = foldLockState

            override fun readStyleRaw(userId: Int) = foldLockState.value.styleRaw

            override fun forceAlways(userId: Int): Boolean {
                foldLockWrites += userId
                foldLockState.value = foldLockState.value.copy(value = DuoFoldLockEnforcer.ALWAYS)
                return true
            }
        }

    @Test
    fun supportedDevice_forcesFoldLockForDuo_andDumpsIt() = runTest {
        whenever(repository.setting).thenReturn(raw)
        foldLockState.value = FoldLockState(userId = 0, styleRaw = 2, value = "sleep_on_fold_key")
        val underTest = create(this)
        underTest.start()
        runCurrent()

        assertThat(foldLockWrites).containsExactly(0)
        val out = StringWriter()
        underTest.dump(PrintWriter(out), emptyArray())
        assertThat(out.toString()).contains("foldLockValue=stay_awake_on_fold_key")
        assertThat(out.toString()).contains("foldLockForced=true")
    }

    @Test
    fun unsupportedDevice_neverTouchesFoldLock() = runTest {
        hingeSensor = false
        whenever(repository.setting).thenReturn(raw)
        foldLockState.value = FoldLockState(userId = 0, styleRaw = 2, value = "sleep_on_fold_key")
        val underTest = create(this)
        underTest.start()
        runCurrent()

        assertThat(foldLockWrites).isEmpty()
        val out = StringWriter()
        underTest.dump(PrintWriter(out), emptyArray())
        assertThat(out.toString()).contains("foldLockValue=null")
        assertThat(out.toString()).contains("foldLockForced=false")
    }

    @Test
    fun unsupportedDevice_registersDumpOnly_andKeepsStock() = runTest {
        hingeSensor = false
        whenever(repository.setting).thenReturn(raw)
        raw.value = 2
        val underTest = create(this)
        underTest.start()
        runCurrent()

        verify(dumpManager).registerNormalDumpable(YrrpFoldAnimationStartable.TAG, underTest)
        verify(repository, never()).setting
        verify(powerInteractor, never()).isAsleep
        assertThat(selector.settingStyle()).isEqualTo(FoldAnimationStyle.STOCK)

        val out = StringWriter()
        underTest.dump(PrintWriter(out), emptyArray())
        assertThat(out.toString()).contains("hingeSensorPresent=false")
        assertThat(out.toString()).contains("eligible=false")
        assertThat(out.toString()).contains("blockedBy=NO_HINGE_SENSOR")
    }

    @Test
    fun unfoldConfigOff_isBlockedByConfigDisabled() = runTest {
        whenever(config.isEnabled).thenReturn(false)
        whenever(repository.setting).thenReturn(raw)
        val underTest = create(this)
        underTest.start()
        runCurrent()

        val out = StringWriter()
        underTest.dump(PrintWriter(out), emptyArray())
        assertThat(out.toString()).contains("blockedBy=CONFIG_DISABLED")
        verify(powerInteractor, never()).isAsleep
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
                "hingeSensorPresent=true",
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
                "foldLockValue=",
                "foldLockForced=",
            )
            .forEach { assertThat(text).contains(it) }
    }

    @Test
    fun dump_includesDualPanelAndBlurFronts() = runTest {
        whenever(repository.setting).thenReturn(raw)
        runtime.dualPanel = true
        runtime.coverBlurFrontPx = 300
        runtime.innerBlurFrontPx = 1200
        val underTest = create(this)
        underTest.start()
        runCurrent()

        val out = StringWriter()
        underTest.dump(PrintWriter(out), emptyArray())
        assertThat(out.toString()).contains("dualPanel=true")
        assertThat(out.toString()).contains("coverBlurFrontPx=300")
        assertThat(out.toString()).contains("innerBlurFrontPx=1200")
    }

    @Test
    fun settingChange_notifiesTheDuoAnimation() = runTest {
        whenever(repository.setting).thenReturn(raw)
        val underTest = create(this)
        underTest.start()
        runCurrent()
        val before = styleChanges

        raw.value = 2
        runCurrent()

        assertThat(styleChanges).isEqualTo(before + 1)
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

    @Test
    fun sleep_releasesLatch_andCountsCancellation() = runTest {
        whenever(repository.setting).thenReturn(raw)
        raw.value = 2
        create(this).start()
        runCurrent()
        selector.onGatedTransitionStarted(Any())
        assertThat(selector.latchedStyle()).isEqualTo(FoldAnimationStyle.DUO)

        isAsleep.value = true
        runCurrent()

        assertThat(selector.latchedStyle()).isNull()
        assertThat(runtime.cancellations).isEqualTo(1)
        assertThat(runtime.lastCancelReason).isEqualTo("SLEEP")
    }

    @Test
    fun sleepWithoutLatch_recordsNothing() = runTest {
        whenever(repository.setting).thenReturn(raw)
        create(this).start()
        runCurrent()

        isAsleep.value = true
        runCurrent()

        assertThat(runtime.cancellations).isEqualTo(0)
    }

    @Test
    fun latchTimeout_releasesLatchAfterTenSeconds() = runTest {
        whenever(repository.setting).thenReturn(raw)
        raw.value = 1
        create(this).start()
        runCurrent()
        selector.onGatedTransitionStarted(Any())

        advanceTimeBy(YrrpFoldAnimationStartable.LATCH_TIMEOUT_MS - 1)
        runCurrent()
        assertThat(selector.latchedStyle()).isEqualTo(FoldAnimationStyle.PIXEL)

        advanceTimeBy(2)
        runCurrent()
        assertThat(selector.latchedStyle()).isNull()
        assertThat(runtime.lastCancelReason).isEqualTo("LATCH_TIMEOUT")
        assertThat(runtime.cancellations).isEqualTo(1)
    }

    @Test
    fun normalFinish_cancelsTimeout_soNoCancellationIsRecorded() = runTest {
        whenever(repository.setting).thenReturn(raw)
        create(this).start()
        runCurrent()
        val token = Any()
        selector.onGatedTransitionStarted(token)
        selector.onGatedTransitionFinished(token)

        advanceTimeBy(YrrpFoldAnimationStartable.LATCH_TIMEOUT_MS + 1)
        runCurrent()

        assertThat(runtime.cancellations).isEqualTo(0)
    }

    @Test
    fun staleTimeout_doesNotReleaseLaterLatch() = runTest {
        whenever(repository.setting).thenReturn(raw)
        create(this).start()
        runCurrent()
        val first = Any()
        selector.onGatedTransitionStarted(first)
        advanceTimeBy(6_000)
        selector.onGatedTransitionFinished(first)
        selector.onGatedTransitionStarted(Any())

        advanceTimeBy(6_000)
        runCurrent()

        assertThat(selector.latchedStyle()).isNotNull()
    }

    private fun create(scope: TestScope, animationsEnabled: Boolean = true) =
        YrrpFoldAnimationStartable(
            repository = repository,
            selector = selector,
            runtimeState = runtime,
            config = config,
            support = FoldAnimationSupport(config) { hingeSensor },
            dumpManager = dumpManager,
            // A real buffer: a bare mock returns null from obtain() and the inline log() NPEs.
            logBuffer = logcatLogBuffer("YrrpFoldAnimationLog"),
            powerInteractor = powerInteractor,
            backgroundScope = scope.backgroundScope,
            animationsEnabled = { animationsEnabled },
            foldLockSettings = foldLockSettings,
            onStyleChanged = { styleChanges++ },
        )
}
