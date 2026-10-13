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
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.unfold.FullscreenLightRevealAnimationController
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_TRANSPARENT
import com.android.systemui.unfold.yrrp.DuoBlurCurve.Anchor
import com.android.systemui.unfold.yrrp.DuoTestPanels.COVER_H
import com.android.systemui.unfold.yrrp.DuoTestPanels.COVER_OWN_ID
import com.android.systemui.unfold.yrrp.DuoTestPanels.COVER_W
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
    private val screenPowerState = MutableStateFlow(ScreenPowerState.SCREEN_ON)
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
        whenever(powerInteractor.screenPowerState).thenReturn(screenPowerState)
        whenever(displayManager.getDisplay(COVER_OWN_ID))
            .thenReturn(fakeInternalDisplay(displayGlobal, COVER_OWN_ID, COVER_W, COVER_H))
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
    fun coverAngle_whileFoldedScreenOnDuo_attachesCoverWithRightAnchoredRows() {
        startFolded()

        hinge.report(30f)
        processAll()

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
    fun hingeBackToZero_releasesTheCover() {
        startFolded()
        hinge.report(3f)
        processAll()

        hinge.report(0f)
        processAll()

        assertThat(frame.releases).isEqualTo(1)
        verify(cover, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
        assertThat(runtime.overlayAttached).isFalse()
    }

    @Test
    fun coverOwnDisplayAppearing_movesTheBlurToTheFrame() {
        val displays = startFolded()
        hinge.report(30f)
        processAll()
        clearInvocations(cover)

        displays.onDisplayAdded(COVER_OWN_ID)
        processAll()

        assertThat(frame.shows).containsExactly(Triple(COVER_OWN_ID, COVER_W, COVER_H))
        verify(cover, atLeastOnce()).ensureOverlayRemoved()
        assertThat(runtime.dualPanel).isTrue()
        assertThat(frame.regions.last().map { it.toList() }).isEqualTo(coverOpeningRows(30f))

        hinge.report(60f)
        processAll()
        assertThat(frame.regions.last().map { it.toList() }).isEqualTo(coverOpeningRows(60f))
        verify(cover, never()).setBlurRegions(any())
    }

    @Test
    fun coverOwnDisplayRemoved_releasesTheFrame() {
        val displays = startFolded()
        hinge.report(30f)
        processAll()
        displays.onDisplayAdded(COVER_OWN_ID)
        processAll()

        displays.onDisplayRemoved(COVER_OWN_ID)
        processAll()

        assertThat(frame.releases).isEqualTo(1)
        assertThat(runtime.dualPanel).isFalse()
        assertThat(runtime.overlayAttached).isFalse()
        assertThat(runtime.phase).isEqualTo(FoldAnimationPhase.IDLE)
    }

    @Test
    fun unfoldWithoutOwnDisplay_dropsCoverOverlayAtInnerStart_andReleasesAtFinish() {
        startFolded()
        hinge.report(30f)
        processAll()
        clearInvocations(cover)
        underTest.onFoldChanged(false)

        underTest.onInnerUnfoldStarted()
        verify(cover).ensureOverlayRemoved()
        assertThat(hinge.isListening).isTrue()

        underTest.onUnfoldFinished()
        assertThat(frame.releases).isEqualTo(1)
        assertThat(underTest.isActive).isFalse()
        assertThat(hinge.isListening).isFalse()
    }

    @Test
    fun hingeSensorUnavailable_isLogged() {
        hinge.startResult = false

        startFolded()

        assertThat(hinge.starts).isEqualTo(1)
        val out = StringWriter()
        logBuffer.dump(PrintWriter(out), 0)
        assertThat(out.toString()).contains("cover hinge unavailable")
    }

    @Test
    fun screenAlreadyOnAtInit_listensOnceFolded() {
        screenPowerState.value = ScreenPowerState.SCREEN_ON

        startFolded()

        assertThat(hinge.isListening).isTrue()
    }

    @Test
    fun screenOffAtInit_waitsForScreenOn() {
        screenPowerState.value = ScreenPowerState.SCREEN_OFF

        startFolded()
        assertThat(hinge.isListening).isFalse()

        underTest.onScreenChanged(true)
        assertThat(hinge.isListening).isTrue()
    }

    /** Inits with the cover controller and folds; returns the registered display listener. */
    private fun startFolded(): DisplayManager.DisplayListener {
        underTest.init(cover, PANELS)
        underTest.onFoldChanged(true)
        processAll()
        val captor = argumentCaptor<DisplayManager.DisplayListener>()
        verify(displayManager).registerDisplayListener(captor.capture(), any<Handler>())
        return captor.firstValue
    }

    private fun lastCoverRegions(): List<List<Float>> {
        val captor = argumentCaptor<List<FloatArray>>()
        verify(cover, atLeastOnce()).setBlurRegions(captor.capture())
        return captor.lastValue.map { it.toList() }
    }

    private fun processAll() = testableLooper.processAllMessages()
}
