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

import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerGlobal
import android.os.Handler
import android.testing.TestableLooper
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakefulnessModel
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.unfold.FullscreenLightRevealAnimationController
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_TRANSPARENT
import com.android.systemui.unfold.yrrp.DuoBlurCurve.Anchor
import com.android.systemui.unfold.yrrp.DuoTestPanels.COVER_H
import com.android.systemui.unfold.yrrp.DuoTestPanels.COVER_OWN_ID
import com.android.systemui.unfold.yrrp.DuoTestPanels.COVER_W
import com.android.systemui.unfold.yrrp.DuoTestPanels.INNER_W
import com.android.systemui.unfold.yrrp.DuoTestPanels.PANELS
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DuoCoverOpeningTest : SysuiTestCase() {
    private val selector = FoldAnimationStyleSelector()
    private val runtime = FoldAnimationRuntimeState()
    private val cover = mock<FullscreenLightRevealAnimationController>()
    private val wakefulness = MutableStateFlow(WakefulnessModel())
    private val powerInteractor = mock<PowerInteractor>()
    private val displayManager = mock<DisplayManager>()
    private val displayGlobal = mock<DisplayManagerGlobal>()
    private val logBuffer = logcatLogBuffer("YrrpFoldAnimationLog")
    private lateinit var testableLooper: TestableLooper
    private lateinit var hinge: FakeHingeSource
    private lateinit var frame: FakeCoverFrame
    private lateinit var underTest: DuoCoverOpening

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        val handler = Handler(testableLooper.looper)
        hinge = FakeHingeSource(handler)
        frame = FakeCoverFrame(handler, logBuffer)
        whenever(cover.setBlurRegions(any())).thenReturn(true)
        whenever(cover.isBlurAvailable()).thenReturn(true)
        whenever(powerInteractor.detailedWakefulness).thenReturn(wakefulness)
        stubDisplay(Display.DEFAULT_DISPLAY, COVER_W)
        stubDisplay(COVER_OWN_ID, COVER_W)
        selector.onSettingChanged(FoldAnimationStyle.DUO)
        underTest =
            DuoCoverOpening(
                handler,
                mContext.contentResolver,
                selector,
                runtime,
                powerInteractor,
                hinge,
                frame,
                displayManager,
                logBuffer,
            )
    }

    @Test
    fun openFromClosed_attachesCoverWithRightAnchoredRows() {
        startFolded()

        openTo(30f)

        verify(cover).addOverlay(eq(ALPHA_TRANSPARENT), anyOrNull(), eq(0))
        assertThat(frame.captures).containsExactly(Display.DEFAULT_DISPLAY)
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.COVER_OPENING)
        assertThat(runtime.overlayAttached).isTrue()
        assertThat(lastCoverRegions()).isEqualTo(coverOpeningRows(30f))
        val share = DuoBlurCurve.coverCoverage(30f / DuoBlurCurve.OPEN_ANGLE_DEGREES)
        assertThat(runtime.coverBlurFrontPx)
            .isEqualTo(DuoBlurCurve.frontPx(COVER_W, share, Anchor.RIGHT))
    }

    @Test
    fun rotatedCover_getsRowsInItsLogicalCoordinates() {
        whenever(cover.rotation).thenReturn(1)
        startFolded()

        openTo(30f)

        val natural = coverOpeningRows(30f).map { it.toFloatArray() }
        val expected = DuoRegionRotation.toLogical(natural, 1, COVER_W, COVER_H)
        assertThat(lastCoverRegions()).isEqualTo(expected.map { it.toList() })
        assertThat(lastCoverRegions()).isNotEqualTo(coverOpeningRows(30f))
    }

    @Test
    fun closingReadingsAfterAFold_neverStartAnOpen() {
        hinge.currentAngle = 20f
        startFolded()

        listOf(15f, 10f, 5f).forEach { hinge.report(it) }
        processAll()

        verify(cover, never()).addOverlay(any(), anyOrNull(), any())
        assertThat(frame.captures).isEmpty()
        assertThat(underTest.isActive).isFalse()
    }

    @Test
    fun hingeBackToZero_releasesTheCover() {
        startFolded()
        openTo(3f)

        hinge.report(0f)
        processAll()

        assertThat(frame.releases).isEqualTo(1)
        verify(cover, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(dumpedLog()).contains("cover overlay removed reason=HINGE_CLOSED")
    }

    @Test
    fun defaultDisplayNotTheCover_skipsTheOpen() {
        stubDisplay(Display.DEFAULT_DISPLAY, INNER_W)
        startFolded()

        openTo(30f)

        assertThat(frame.captures).isEmpty()
        verify(cover, never()).addOverlay(any(), anyOrNull(), any())
        assertThat(underTest.isActive).isFalse()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(dumpedLog()).contains("cover opening skipped display0 not cover")
    }

    @Test
    fun coverOwnDisplay_movesTheBlurToTheFrameOnceShown() {
        val displays = startFolded()
        openTo(30f)
        clearInvocations(cover)

        displays.onDisplayAdded(COVER_OWN_ID)
        processAll()
        assertThat(frame.shows).containsExactly(Triple(COVER_OWN_ID, COVER_W, COVER_H))
        verify(cover).ensureOverlayRemoved()
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(runtime.dualPanel).isFalse()

        frame.completeShow(true)
        // What the dump reads after the move.
        assertThat(runtime.dualPanel).isTrue()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.COVER_OPENING)
        assertThat(frame.regions.last().map { it.toList() }).isEqualTo(coverOpeningRows(30f))

        hinge.report(60f)
        processAll()
        assertThat(frame.regions.last().map { it.toList() }).isEqualTo(coverOpeningRows(60f))
        verify(cover, never()).setBlurRegions(any())
    }

    @Test
    fun coverOwnDisplayRemoved_releasesTheFrame() {
        val displays = startFolded()
        moveToOwnDisplay(displays)

        displays.onDisplayRemoved(COVER_OWN_ID)
        processAll()

        assertThat(frame.releases).isEqualTo(1)
        assertThat(runtime.dualPanel).isFalse()
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(dumpedLog()).contains("cover overlay removed reason=DISPLAY_REMOVED")
    }

    @Test
    fun unrelatedRemoval_isIgnored() {
        val displays = startFolded()
        moveToOwnDisplay(displays)

        displays.onDisplayRemoved(7)
        processAll()

        assertThat(frame.releases).isEqualTo(0)
        assertThat(runtime.dualPanel).isTrue()
    }

    @Test
    fun nonCoverDisplays_areIgnored() {
        stubDisplay(7, INNER_W)
        stubDisplay(8, COVER_W, Display.TYPE_EXTERNAL)
        val displays = startFolded()
        openTo(30f)

        listOf(Display.DEFAULT_DISPLAY, 7, 8).forEach { displays.onDisplayAdded(it) }
        processAll()

        assertThat(frame.shows).isEmpty()
        assertThat(dumpedLog()).contains("cover display rejected id=7")
        assertThat(dumpedLog()).contains("cover display rejected id=8")
    }

    @Test
    fun displayAfterTheInnerUnfoldStarts_handsTheBlurToTheFrame() {
        val displays = startFolded()
        openTo(30f)
        underTest.onFoldChanged(false)
        underTest.onInnerUnfoldStarted()
        // The inner overlay owns the phase and blurAvailable from here.
        runtime.phase = FoldAnimationPhase.UNFOLDING
        whenever(cover.isBlurAvailable()).thenReturn(false)
        clearInvocations(cover)

        hinge.report(45f)
        processAll()
        verify(cover, never()).setBlurRegions(any())
        assertThat(runtime.blurAvailable).isTrue()

        whenever(cover.isBlurAvailable()).thenReturn(true)
        displays.onDisplayAdded(COVER_OWN_ID)
        frame.completeShow(true)
        processAll()
        assertThat(frame.regions.last().map { it.toList() }).isEqualTo(coverOpeningRows(45f))
        verify(cover, never()).setBlurRegions(any())
    }

    @Test
    fun unfoldWithoutOwnDisplay_releasesAtFinish() {
        startFolded()
        openTo(30f)
        underTest.onFoldChanged(false)
        underTest.onInnerUnfoldStarted()
        assertThat(hinge.isListening).isTrue()

        underTest.onUnfoldFinished()

        assertThat(frame.releases).isEqualTo(1)
        assertThat(underTest.isActive).isFalse()
        assertThat(hinge.isListening).isFalse()
        assertThat(dumpedLog()).contains("cover overlay removed reason=UNFOLD_FINISHED")
    }

    @Test
    fun cancelWhileShowPending_leavesDualPanelFalse_andNoFrameBlur() {
        val displays = startFolded()
        openTo(30f)
        displays.onDisplayAdded(COVER_OWN_ID)
        processAll()

        underTest.cancel("TEST")
        frame.completeShow(true)

        assertThat(runtime.dualPanel).isFalse()
        assertThat(frame.regions).isEmpty()
    }

    @Test
    fun failedShow_cancelsTheOpen() {
        val displays = startFolded()
        openTo(30f)
        displays.onDisplayAdded(COVER_OWN_ID)
        processAll()

        frame.completeShow(false)

        assertThat(underTest.isActive).isFalse()
        assertThat(frame.releases).isEqualTo(1)
        assertThat(runtime.dualPanel).isFalse()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(dumpedLog()).contains("cover frame failed")
    }

    @Test
    fun foldWhileOnOwnDisplay_releases() {
        val displays = startFolded()
        moveToOwnDisplay(displays)
        underTest.onFoldChanged(false)

        underTest.onFoldChanged(true)

        assertThat(frame.releases).isEqualTo(1)
        assertThat(underTest.isActive).isFalse()
        assertThat(runtime.dualPanel).isFalse()
        assertThat(dumpedLog()).contains("cover opening cancelled reason=FOLDED")
    }

    @Test
    fun foldFromTheInnerScreen_closingReadingsNeverCapture() {
        startUnfolded()
        hinge.currentAngle = 20f

        underTest.onFoldChanged(true)
        processAll()
        listOf(15f, 10f, 5f, 4f).forEach { hinge.report(it) }
        processAll()

        assertThat(frame.captures).isEmpty()
        assertThat(underTest.isActive).isFalse()
        openTo(2f)
        assertThat(underTest.isActive).isTrue()
    }

    @Test
    fun restartWithTheHingeClosed_opensOnTheFirstMove() {
        hinge.currentAngle = 0f

        startFolded()
        hinge.report(30f)
        processAll()

        assertThat(underTest.isActive).isTrue()
        assertThat(frame.captures).containsExactly(Display.DEFAULT_DISPLAY)
        assertThat(dumpedLog()).contains("cover hinge first angle=0.0 state=DISARMED")
    }

    @Test
    fun listeningStartsPartlyOpen_doesNotCapture() {
        hinge.currentAngle = 10f

        startFolded()
        hinge.report(30f)
        processAll()

        assertThat(underTest.isActive).isFalse()
        assertThat(frame.captures).isEmpty()
        assertThat(dumpedLog()).contains("cover hinge first angle=10.0 state=DISARMED")
    }

    @Test
    fun missingPicture_cancelsCleanlyWhenTheCoverDisplayAppears() {
        frame.captureResult = false
        val displays = startFolded()
        openTo(30f)

        displays.onDisplayAdded(COVER_OWN_ID)
        processAll()

        assertThat(underTest.isActive).isFalse()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(runtime.dualPanel).isFalse()
        assertThat(dumpedLog()).contains("cover frame failed")
    }

    @Test
    fun styleSwitchMidOpen_cancels() {
        startFolded()
        openTo(30f)

        selector.onSettingChanged(FoldAnimationStyle.PIXEL)
        underTest.onStyleChanged()

        assertThat(underTest.isActive).isFalse()
        assertThat(frame.releases).isEqualTo(1)
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(hinge.isListening).isFalse()
    }

    @Test
    fun hingeSensorUnavailable_isLogged() {
        hinge.startResult = false

        startFolded()

        assertThat(hinge.starts).isEqualTo(1)
        assertThat(dumpedLog()).contains("cover hinge unavailable")
    }

    @Test
    fun awakeAtInit_listensWithoutAWakeUp() {
        // A SystemUI restart with the cover on: the first fold report is folded and no
        // onAwakeChanged ever arrives.
        wakefulness.value = WakefulnessModel(internalWakefulnessState = WakefulnessState.AWAKE)

        startFolded()

        assertThat(hinge.isListening).isTrue()
        openTo(30f)
        assertThat(underTest.isActive).isTrue()
    }

    @Test
    fun asleepAtInit_waitsForAWakeUp() {
        wakefulness.value = WakefulnessModel(internalWakefulnessState = WakefulnessState.ASLEEP)

        startFolded()
        assertThat(hinge.isListening).isFalse()

        underTest.onAwakeChanged(true)
        assertThat(hinge.isListening).isTrue()
    }

    /** Inits and reports folded first; returns the registered display listener. */
    private fun startFolded(): DisplayManager.DisplayListener = start(folded = true)

    private fun startUnfolded(): DisplayManager.DisplayListener = start(folded = false)

    private fun start(folded: Boolean): DisplayManager.DisplayListener {
        underTest.init(cover, PANELS)
        underTest.onFoldChanged(folded)
        processAll()
        val captor = argumentCaptor<DisplayManager.DisplayListener>()
        val events =
            DisplayManager.EVENT_TYPE_DISPLAY_ADDED or DisplayManager.EVENT_TYPE_DISPLAY_REMOVED
        verify(displayManager).registerDisplayListener(captor.capture(), any<Handler>(), eq(events))
        return captor.firstValue
    }

    /** A closed reading arms the sequence; [angle] then opens. */
    private fun openTo(angle: Float) {
        hinge.report(0f)
        hinge.report(angle)
        processAll()
    }

    private fun moveToOwnDisplay(displays: DisplayManager.DisplayListener) {
        openTo(30f)
        displays.onDisplayAdded(COVER_OWN_ID)
        processAll()
        frame.completeShow(true)
    }

    private fun stubDisplay(id: Int, width: Int, type: Int = Display.TYPE_INTERNAL) {
        whenever(displayManager.getDisplay(id))
            .thenReturn(fakeInternalDisplay(displayGlobal, id, width, COVER_H, type))
    }

    private fun lastCoverRegions(): List<List<Float>> {
        val captor = argumentCaptor<List<FloatArray>>()
        verify(cover, atLeastOnce()).setBlurRegions(captor.capture())
        return captor.lastValue.map { it.toList() }
    }

    private fun dumpedLog(): String =
        StringWriter().also { logBuffer.dump(PrintWriter(it), 0) }.toString()

    private fun processAll() = testableLooper.processAllMessages()
}
