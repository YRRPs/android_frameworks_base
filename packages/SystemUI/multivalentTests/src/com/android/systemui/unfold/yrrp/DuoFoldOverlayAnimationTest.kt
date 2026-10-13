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

import android.hardware.SensorManager
import android.hardware.devicestate.DeviceStateManager
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerGlobal
import android.os.Handler
import android.testing.TestableLooper
import android.view.Display
import android.view.DisplayAdjustments
import android.view.DisplayInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.foldedDeviceStateList
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.unfold.FakeUnfoldTransitionProvider
import com.android.systemui.unfold.FullscreenLightRevealAnimationController
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_TRANSPARENT
import com.android.systemui.unfold.yrrp.DuoBlurCurve.Anchor
import com.android.systemui.unfoldedDeviceState
import com.android.systemui.util.concurrency.ThreadFactory
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DuoFoldOverlayAnimationTest : SysuiTestCase() {
    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    private val kosmos = Kosmos()
    private val foldedState = kosmos.foldedDeviceStateList.first()
    private val unfoldedState = kosmos.unfoldedDeviceState

    private val selector = FoldAnimationStyleSelector()
    private val runtime = FoldAnimationRuntimeState()
    private val innerController = mock<FullscreenLightRevealAnimationController>()
    private val coverController = mock<FullscreenLightRevealAnimationController>()
    private val factory = mock<FullscreenLightRevealAnimationController.Factory>()
    private val deviceStateManager = mock<DeviceStateManager>()
    private val threadFactory = mock<ThreadFactory>()
    private val powerInteractor = mock<PowerInteractor>()
    private val screenPowerState = MutableStateFlow(ScreenPowerState.SCREEN_OFF)
    private val isAsleep = MutableStateFlow(false)
    private val progressProvider = FakeUnfoldTransitionProvider()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val logBuffer = logcatLogBuffer("YrrpFoldAnimationLog")
    private val displayManager = mock<DisplayManager>()
    private val displayGlobal = mock<DisplayManagerGlobal>()
    private lateinit var hinge: FakeHingeSource
    private lateinit var frame: FakeFrame
    private lateinit var displayListener: DisplayManager.DisplayListener
    private lateinit var testableLooper: TestableLooper
    private lateinit var underTest: DuoFoldOverlayAnimation
    private lateinit var foldListener: DeviceStateManager.DeviceStateCallback

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        mContext.orCreateTestableResources.addOverride(
            R.array.config_foldedDeviceStates,
            intArrayOf(foldedState.identifier),
        )
        whenever(innerController.setBlurRegions(any())).thenReturn(true)
        whenever(coverController.setBlurRegions(any())).thenReturn(true)
        whenever(innerController.isBlurAvailable()).thenReturn(true)
        whenever(coverController.isBlurAvailable()).thenReturn(true)
        whenever(factory.create(any(), any(), any()))
            .thenReturn(innerController)
            .thenReturn(coverController)
        whenever(threadFactory.buildDelayableExecutorOnHandler(any())).thenReturn(mock())
        whenever(powerInteractor.screenPowerState).thenReturn(screenPowerState)
        whenever(powerInteractor.isAsleep).thenReturn(isAsleep)
        val handler = Handler(testableLooper.looper)
        hinge = FakeHingeSource(handler)
        frame = FakeFrame(handler)
        val coverDisplay = display(Display.DEFAULT_DISPLAY, COVER_W, COVER_H)
        val innerDisplay = display(INNER_ID, INNER_W, INNER_H)
        val allDisplays = DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED
        whenever(displayManager.getDisplays(allDisplays))
            .thenReturn(arrayOf(coverDisplay, innerDisplay))
        whenever(displayManager.getDisplay(COVER_OWN_ID))
            .thenReturn(display(COVER_OWN_ID, COVER_W, COVER_H))

        underTest = createUnderTest(handler)
        underTest.init()
        foldListener = captureDeviceStateCallback()
        displayListener = captureDisplayListener()
        selector.onSettingChanged(FoldAnimationStyle.DUO)
    }

    @Test
    fun duoUnfold_attachesBlurredBeforeReady_thenClearsWithProgress() {
        reportFolded(true)
        reportFolded(false)
        selector.onGatedTransitionStarted(progressProvider)
        val ready = CountDownLatch(1)

        underTest.onScreenTurningOn { ready.countDown() }
        processAll()

        verify(innerController)
            .addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), eq(DuoBlurCurve.MAX_BLUR_PX))
        verify(innerController, never()).setBlurRegions(any())
        assertThat(runtime.blurAvailable).isTrue()
        assertThat(runtime.innerBlurFrontPx).isEqualTo(INNER_W)
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.9f)
        processAll()
        assertThat(lastRegions(innerController)).isEqualTo(innerRows(0.9f))
        verify(innerController).isTouchBlocked = false
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.UNFOLDING)

        progressProvider.onTransitionFinished()
        processAll()
        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        val share = DuoBlurCurve.innerRemaining(0.9f)
        assertThat(runtime.innerBlurFrontPx)
            .isEqualTo(DuoBlurCurve.frontPx(INNER_W, share, Anchor.LEFT))
    }

    @Test
    fun duoUnfold_handshakeLatchesStyleOnce_andGatedStartJoins() {
        reportFolded(true)
        reportFolded(false)

        underTest.onScreenTurningOn {}
        processAll()
        selector.onSettingChanged(FoldAnimationStyle.STOCK)

        assertThat(selector.latchedStyle()).isEqualTo(FoldAnimationStyle.DUO)
        assertThat(selector.starts).isEqualTo(1)
        selector.onGatedTransitionStarted(progressProvider)
        assertThat(selector.starts).isEqualTo(1)
        verify(innerController).addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), any())
    }

    @Test
    fun pixel_neverAttaches_andReportsReady() {
        selector.onSettingChanged(FoldAnimationStyle.PIXEL)
        reportFolded(true)
        reportFolded(false)
        val ready = CountDownLatch(1)

        underTest.onScreenTurningOn { ready.countDown() }
        processAll()

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue()
        verify(innerController, never()).addOverlay(any(), anyOrNull(), any())
        verify(coverController, never()).addOverlay(any(), anyOrNull(), any())
    }

    @Test
    fun foldMidUnfold_removesOverlay_andRecordsCancel() {
        reportFolded(true)
        reportFolded(false)
        underTest.onScreenTurningOn {}
        processAll()

        reportFolded(true)
        processAll()

        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.cancellations).isEqualTo(1)
        assertThat(runtime.lastCancelReason).isEqualTo("FOLDED")
    }

    @Test
    fun blurRefused_marksBlurUnavailable_thenRetriesOnProgress() {
        whenever(innerController.isBlurAvailable()).thenReturn(false)
        whenever(innerController.setBlurRegions(any())).thenReturn(false)
        reportFolded(true)
        reportFolded(false)

        underTest.onScreenTurningOn {}
        processAll()

        assertThat(runtime.blurAvailable).isFalse()
        verify(innerController).addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), any())

        whenever(innerController.setBlurRegions(any())).thenReturn(true)
        progressProvider.onTransitionProgress(0.5f)
        processAll()

        assertThat(lastRegions(innerController)).isEqualTo(innerRows(0.5f))
        assertThat(runtime.blurAvailable).isTrue()
    }

    @Test
    fun attachFailure_reportsReadyExactlyOnce() {
        var readyCount = 0
        whenever(innerController.addOverlay(any(), anyOrNull(), any())).thenAnswer {
            (it.arguments[1] as Runnable).run()
        }
        whenever(innerController.isBlurAvailable()).thenThrow(RuntimeException())
        reportFolded(true)
        reportFolded(false)

        underTest.onScreenTurningOn { readyCount++ }
        processAll()

        assertThat(readyCount).isEqualTo(1)
        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
    }

    @Test
    fun coverReveal_timesOutWithoutScreenOn_removesOverlay() {
        reportFolded(false)
        reportFolded(true)
        underTest.onScreenTurningOn {}
        processAll()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.COVER_REVEAL)

        scope.testScheduler.advanceTimeBy(DuoFoldOverlayAnimation.READY_TIMEOUT_MS + 1)
        scope.testScheduler.runCurrent()
        processAll()

        verify(coverController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.lastCancelReason).isEqualTo("COVER_TIMEOUT")
    }

    @Test
    fun unfoldDuringCoverReveal_cancelsCover_andUnfoldCompletesCleanly() {
        reportFolded(false)
        reportFolded(true)
        underTest.onScreenTurningOn {}
        processAll()
        underTest.onCoverScreenOn()
        animatorTestRule.advanceTimeBy(100)
        processAll()

        reportFolded(false)
        assertThat(runtime.lastCancelReason).isEqualTo("UNFOLDED")
        verify(coverController, atLeastOnce()).ensureOverlayRemoved()
        selector.onGatedTransitionStarted(progressProvider)
        underTest.onScreenTurningOn {}
        processAll()
        animatorTestRule.advanceTimeBy(DuoFoldOverlayAnimation.COVER_REVEAL_MS)
        processAll()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.UNFOLDING)

        progressProvider.onTransitionFinished()
        processAll()

        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.overlayAttached).isFalse()
    }

    @Test
    fun coverReveal_afterFold_attachesOnCover_thenRemovesAfterReveal() {
        reportFolded(false)
        reportFolded(true)
        val ready = CountDownLatch(1)

        underTest.onScreenTurningOn { ready.countDown() }
        processAll()

        verify(coverController)
            .addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), eq(DuoBlurCurve.MAX_BLUR_PX))
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.COVER_REVEAL)

        underTest.onCoverScreenOn() // test hook = what the SCREEN_ON collector calls
        animatorTestRule.advanceTimeBy(DuoFoldOverlayAnimation.COVER_REVEAL_MS)
        processAll()

        // Fully blurred from the right edge at the start, swept clear left to right at the end.
        val regions = allRegions(coverController)
        assertThat(regions.first()).isEqualTo(coverRevealRows(1f))
        assertThat(regions.last()).isEmpty()
        verify(coverController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.coverBlurFrontPx).isEqualTo(COVER_W)
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
    }

    @Test
    fun coverReveal_startsWhenCoverScreenReportsOn() {
        reportFolded(false)
        reportFolded(true)
        underTest.onScreenTurningOn {}
        processAll()

        screenPowerState.value = ScreenPowerState.SCREEN_ON
        animatorTestRule.advanceTimeBy(DuoFoldOverlayAnimation.COVER_REVEAL_MS)
        processAll()

        assertThat(lastRegions(coverController)).isEmpty()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
    }

    @Test
    fun asleepMidUnfold_removesOverlay_andRecordsCancel() {
        reportFolded(true)
        reportFolded(false)
        underTest.onScreenTurningOn {}
        processAll()

        isAsleep.value = true
        processAll()

        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.lastCancelReason).isEqualTo("ASLEEP")
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
    }

    @Test
    fun foldOnInner_attachesOnTransitionStarted_andBlursWithoutUnblockingTouch() {
        selector.onGatedTransitionStarted(progressProvider)

        progressProvider.onTransitionStarted()
        processAll()

        verify(innerController).addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), any())
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.FOLDING)
        progressProvider.onTransitionProgress(0.9f)
        processAll()
        verify(innerController, never()).isTouchBlocked = false
    }

    @Test
    fun foldSleepThenUnfold_stillPlaysUnfold_andIsNotMistakenForFold() {
        reportFolded(true)
        isAsleep.value = true
        processAll()
        isAsleep.value = false
        processAll()
        reportFolded(false)

        underTest.onScreenTurningOn {}
        processAll()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.UNFOLDING)
        verify(innerController).addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), any())

        progressProvider.onTransitionStarted()
        processAll()

        // No second, FOLDING attach for the real unfold.
        verify(innerController, times(1)).addOverlay(any(), anyOrNull(), any())
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.UNFOLDING)
    }

    @Test
    fun foldOnInner_startsUnblurred_soNoFlashBeforeProgress() {
        selector.onGatedTransitionStarted(progressProvider)

        progressProvider.onTransitionStarted()
        processAll()

        verify(innerController).addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), eq(0))
        verify(innerController, never()).setBackgroundBlurRadius(DuoBlurCurve.MAX_BLUR_PX)
    }

    @Test
    fun unfoldNeverFinishing_safetyTimeoutRemovesOverlay() {
        reportFolded(true)
        reportFolded(false)
        underTest.onScreenTurningOn {}
        processAll()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.UNFOLDING)

        testableLooper.moveTimeForward(DuoFoldOverlayAnimation.SAFETY_TIMEOUT_MS + 1)
        processAll()

        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
        verify(innerController, atLeastOnce()).isTouchBlocked = false
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.lastCancelReason).isEqualTo("TIMEOUT")
    }

    @Test
    fun foldNeverFinishing_safetyTimeoutRemovesOverlay() {
        selector.onGatedTransitionStarted(progressProvider)
        progressProvider.onTransitionStarted()
        processAll()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.FOLDING)

        testableLooper.moveTimeForward(DuoFoldOverlayAnimation.SAFETY_TIMEOUT_MS + 1)
        processAll()

        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.lastCancelReason).isEqualTo("TIMEOUT")
    }

    @Test
    fun finishBeforeTimeout_recordsNoCancel() {
        selector.onGatedTransitionStarted(progressProvider)
        progressProvider.onTransitionStarted()
        processAll()
        progressProvider.onTransitionFinished()
        processAll()

        testableLooper.moveTimeForward(DuoFoldOverlayAnimation.SAFETY_TIMEOUT_MS + 1)
        processAll()

        assertThat(runtime.cancellations).isEqualTo(0)
    }

    @Test
    fun foldThenSleepBeforeScreenOn_wakeDoesNotRevealCover() {
        reportFolded(false)
        reportFolded(true)
        isAsleep.value = true
        processAll()
        isAsleep.value = false
        processAll()
        var ready = false

        underTest.onScreenTurningOn { ready = true }
        processAll()

        assertThat(ready).isTrue()
        verify(coverController, never()).addOverlay(any(), anyOrNull(), any())
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
    }

    @Test
    fun foldCompletes_removesInnerOverlay() {
        selector.onGatedTransitionStarted(progressProvider)
        progressProvider.onTransitionStarted()
        processAll()

        reportFolded(true)
        processAll()

        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.overlayAttached).isFalse()
    }

    @Test
    fun coverAngle_whileFoldedScreenOnDuo_attachesCoverWithRightAnchoredRows() {
        foldWithCoverOn()

        hinge.report(30f)
        processAll()

        verify(coverController).addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), eq(0))
        assertThat(frame.captures).containsExactly(Display.DEFAULT_DISPLAY)
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.COVER_OPENING)
        assertThat(runtime.overlayAttached).isTrue()
        assertThat(lastRegions(coverController)).isEqualTo(coverOpeningRows(30f))
        val share = DuoBlurCurve.coverCoverage(30f / DuoBlurCurve.OPEN_ANGLE_DEGREES)
        assertThat(runtime.coverBlurFrontPx)
            .isEqualTo(DuoBlurCurve.frontPx(COVER_W, share, Anchor.RIGHT))
    }

    @Test
    fun coverOwnDisplayAppearing_movesTheBlurToTheFrame() {
        foldWithCoverOn()
        hinge.report(30f)
        processAll()
        clearInvocations(coverController)

        displayListener.onDisplayAdded(COVER_OWN_ID)
        processAll()

        assertThat(frame.shows).containsExactly(Triple(COVER_OWN_ID, COVER_W, COVER_H))
        verify(coverController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.dualPanel).isTrue()
        assertThat(frame.regions.last().map { it.toList() }).isEqualTo(coverOpeningRows(30f))

        hinge.report(60f)
        processAll()
        assertThat(frame.regions.last().map { it.toList() }).isEqualTo(coverOpeningRows(60f))
        verify(coverController, never()).setBlurRegions(any())
    }

    @Test
    fun coverOwnDisplayRemoved_releasesTheFrame() {
        foldWithCoverOn()
        hinge.report(30f)
        processAll()
        displayListener.onDisplayAdded(COVER_OWN_ID)
        processAll()

        displayListener.onDisplayRemoved(COVER_OWN_ID)
        processAll()

        assertThat(frame.releases).isEqualTo(1)
        assertThat(runtime.dualPanel).isFalse()
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
    }

    @Test
    fun sleepDuringOpen_releasesFrameAndBothOverlays() {
        foldWithCoverOn()
        hinge.report(30f)
        processAll()
        displayListener.onDisplayAdded(COVER_OWN_ID)
        processAll()
        reportFolded(false)
        selector.onGatedTransitionStarted(progressProvider)
        underTest.onScreenTurningOn {}
        processAll()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.UNFOLDING)

        isAsleep.value = true
        processAll()

        assertThat(frame.releases).isEqualTo(1)
        verify(innerController, atLeastOnce()).ensureOverlayRemoved()
        verify(innerController, atLeastOnce()).isTouchBlocked = false
        verify(coverController, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(runtime.dualPanel).isFalse()
        assertThat(runtime.lastCancelReason).isEqualTo("ASLEEP")
        assertThat(hinge.isListening).isFalse()
    }

    @Test
    fun hingeSensorUnavailable_isLogged() {
        hinge.startResult = false

        foldWithCoverOn()

        assertThat(hinge.starts).isAtLeast(1)
        val out = StringWriter()
        logBuffer.dump(PrintWriter(out), 0)
        assertThat(out.toString()).contains("cover hinge unavailable")
    }

    /** Folds, lets the cover reveal finish, and leaves the cover on with the hinge listening. */
    private fun foldWithCoverOn() {
        reportFolded(true)
        underTest.onScreenTurningOn {}
        processAll()
        underTest.onCoverScreenOn()
        animatorTestRule.advanceTimeBy(DuoFoldOverlayAnimation.COVER_REVEAL_MS)
        processAll()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        clearInvocations(coverController)
    }

    private fun innerRows(progress: Float): List<List<Float>> =
        DuoBlurCurve.blurStrips(
                INNER_W,
                INNER_H,
                INNER_W * DuoBlurCurve.innerRemaining(progress),
                DuoBlurCurve.MAX_BLUR_PX,
                Anchor.LEFT,
            )
            .map { it.toList() }

    private fun coverOpeningRows(angle: Float): List<List<Float>> {
        val coverage = DuoBlurCurve.coverCoverage(angle / DuoBlurCurve.OPEN_ANGLE_DEGREES)
        val maxBlurPx = Math.round(DuoBlurCurve.MAX_BLUR_PX * coverage)
        return DuoBlurCurve.blurStrips(
                COVER_W,
                COVER_H,
                COVER_W * coverage,
                maxBlurPx,
                Anchor.RIGHT,
            )
            .map { it.toList() }
    }

    private fun coverRevealRows(blurredShare: Float): List<List<Float>> =
        DuoBlurCurve.blurStrips(
                COVER_W,
                COVER_H,
                COVER_W * blurredShare,
                DuoBlurCurve.MAX_BLUR_PX,
                Anchor.RIGHT,
            )
            .map { it.toList() }

    private fun allRegions(
        controller: FullscreenLightRevealAnimationController
    ): List<List<List<Float>>> {
        val captor = argumentCaptor<List<FloatArray>>()
        verify(controller, atLeastOnce()).setBlurRegions(captor.capture())
        return captor.allValues.map { regions -> regions.map { it.toList() } }
    }

    private fun lastRegions(controller: FullscreenLightRevealAnimationController) =
        allRegions(controller).last()

    /** A real Display over a mocked DisplayManagerGlobal, as FaceScanningProviderFactoryTest. */
    private fun display(id: Int, width: Int, height: Int): Display {
        val info =
            DisplayInfo().apply {
                type = Display.TYPE_INTERNAL
                logicalWidth = width
                logicalHeight = height
                rotation = 0
            }
        whenever(displayGlobal.getDisplayInfo(eq(id))).thenReturn(info)
        return Display(displayGlobal, id, info, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS)
    }

    private class FakeHingeSource(handler: Handler) : CoverHingeAngleSource(mock(), handler) {
        var startResult = true
        var starts = 0
        private var onAngle: ((Float) -> Unit)? = null

        override val isListening: Boolean
            get() = onAngle != null

        override fun start(onAngle: (Float) -> Unit): Boolean {
            starts++
            this.onAngle = if (startResult) onAngle else null
            return startResult
        }

        override fun stop() {
            onAngle = null
        }

        fun report(angle: Float) {
            onAngle?.invoke(angle)
        }
    }

    private inner class FakeFrame(handler: Handler) :
        DuoCoverFrame(mock(), Optional.empty(), handler, logBuffer) {
        val captures = mutableListOf<Int>()
        val shows = mutableListOf<Triple<Int, Int, Int>>()
        val regions = mutableListOf<List<FloatArray>>()
        var releases = 0

        override fun capture(fromDisplayId: Int): Boolean {
            captures += fromDisplayId
            return true
        }

        override fun show(
            targetDisplayId: Int,
            widthPx: Int,
            heightPx: Int,
            onShown: (Boolean) -> Unit,
        ) {
            shows += Triple(targetDisplayId, widthPx, heightPx)
            onShown(true)
        }

        override fun setBlurRegions(regions: List<FloatArray>): Boolean {
            this.regions += regions
            return true
        }

        override fun release() {
            releases++
        }
    }

    private fun createUnderTest(handler: Handler) =
        DuoFoldOverlayAnimation(
            mContext,
            mContext.contentResolver,
            handler,
            progressProvider,
            deviceStateManager,
            threadFactory,
            factory,
            selector,
            runtime,
            powerInteractor,
            scope,
            logBuffer,
            hinge,
            frame,
            displayManager,
        )

    private fun captureDisplayListener(): DisplayManager.DisplayListener {
        val captor = argumentCaptor<DisplayManager.DisplayListener>()
        verify(displayManager).registerDisplayListener(captor.capture(), any<Handler>())
        return captor.firstValue
    }

    private fun captureDeviceStateCallback(): DeviceStateManager.DeviceStateCallback {
        val captor = ArgumentCaptor.forClass(DeviceStateManager.DeviceStateCallback::class.java)
        verify(deviceStateManager).registerCallback(any<Executor>(), captor.capture())
        return captor.value
    }

    private fun reportFolded(folded: Boolean) {
        foldListener.onDeviceStateChanged(if (folded) foldedState else unfoldedState)
        processAll()
    }

    private fun processAll() = testableLooper.processAllMessages()

    private companion object {
        const val COVER_W = 1344
        const val COVER_H = 1892
        const val INNER_W = 2688
        const val INNER_H = 1892
        const val INNER_ID = 1
        const val COVER_OWN_ID = 4
    }
}
