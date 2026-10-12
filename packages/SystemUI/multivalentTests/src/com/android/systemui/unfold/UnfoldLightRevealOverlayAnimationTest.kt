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
package com.android.systemui.unfold

import android.hardware.devicestate.DeviceStateManager
import android.os.Handler
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.foldedDeviceStateList
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.unfold.yrrp.FoldAnimationStyle
import com.android.systemui.unfold.yrrp.FoldAnimationStyleSelector
import com.android.systemui.unfoldedDeviceState
import com.android.systemui.util.concurrency.ThreadFactory
import java.util.concurrent.Executor
import javax.inject.Provider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class UnfoldLightRevealOverlayAnimationTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val foldedState = kosmos.foldedDeviceStateList.first()
    private val unfoldedState = kosmos.unfoldedDeviceState

    private val selector = FoldAnimationStyleSelector()
    private val deviceStateManager = mock<DeviceStateManager>()
    private val threadFactory = mock<ThreadFactory>()
    private val factory = mock<FullscreenLightRevealAnimationController.Factory>()
    private val controller = mock<FullscreenLightRevealAnimationController>()
    private val bgProvider = mock<UnfoldTransitionProgressProvider>()
    private val mainProvider = mock<UnfoldTransitionProgressProvider>()
    private val onOverlayReady = mock<Runnable>()
    private lateinit var testableLooper: TestableLooper
    private lateinit var foldStateCallback: DeviceStateManager.DeviceStateCallback
    private lateinit var underTest: UnfoldLightRevealOverlayAnimation

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        mContext.orCreateTestableResources.addOverride(
            R.array.config_foldedDeviceStates,
            intArrayOf(foldedState.identifier),
        )
        whenever(factory.create(any(), any(), any())).thenReturn(controller)
        whenever(threadFactory.buildDelayableExecutorOnHandler(any())).thenReturn(mock())

        underTest =
            UnfoldLightRevealOverlayAnimation(
                mContext,
                mock<FeatureFlagsClassic>(),
                mContext.contentResolver,
                Handler(testableLooper.looper),
                Provider { bgProvider },
                Provider { mainProvider },
                deviceStateManager,
                threadFactory,
                factory,
                selector,
            )
        underTest.init()

        val captor = ArgumentCaptor.forClass(DeviceStateManager.DeviceStateCallback::class.java)
        verify(deviceStateManager).registerCallback(any<Executor>(), captor.capture())
        foldStateCallback = captor.value
    }

    @Test
    fun stock_onScreenTurningOn_reportsReadyWithoutOverlay() {
        selector.onSettingChanged(FoldAnimationStyle.STOCK)
        unfoldAfterFold()

        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        verify(onOverlayReady).run()
        verify(controller, never()).addOverlay(any(), anyOrNull(), any())
    }

    @Test
    fun duo_onScreenTurningOn_reportsReadyWithoutOverlay() {
        selector.onSettingChanged(FoldAnimationStyle.DUO)
        unfoldAfterFold()

        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        verify(onOverlayReady).run()
        verify(controller, never()).addOverlay(any(), anyOrNull(), any())
    }

    @Test
    fun pixel_onScreenTurningOn_addsOverlay() {
        selector.onSettingChanged(FoldAnimationStyle.PIXEL)
        unfoldAfterFold()

        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        verify(controller).addOverlay(any(), eq(onOverlayReady), eq(0))
    }

    @Test
    fun stockUnfold_thenSwitchToPixel_nextScreenOnAddsNoOverlay() {
        selector.onSettingChanged(FoldAnimationStyle.STOCK)
        unfoldAfterFold()
        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        // Lock and wake after the user switched to Pixel: the unfold was already handled.
        selector.onSettingChanged(FoldAnimationStyle.PIXEL)
        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        verify(controller, never()).addOverlay(any(), anyOrNull(), any())
        verify(onOverlayReady, times(2)).run()
    }

    @Test
    fun duoUnfold_thenSwitchToPixel_nextScreenOnAddsNoOverlay() {
        selector.onSettingChanged(FoldAnimationStyle.DUO)
        unfoldAfterFold()
        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        selector.onSettingChanged(FoldAnimationStyle.PIXEL)
        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        verify(controller, never()).addOverlay(any(), anyOrNull(), any())
    }

    @Test
    fun pixel_handshakeLatchesStyle_soGateJoinsInsteadOfRestarting() {
        selector.onSettingChanged(FoldAnimationStyle.PIXEL)
        unfoldAfterFold()

        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()
        selector.onSettingChanged(FoldAnimationStyle.STOCK)

        assertThat(selector.latchedStyle()).isEqualTo(FoldAnimationStyle.PIXEL)
        assertThat(selector.starts).isEqualTo(1)
        assertThat(selector.onGatedTransitionStarted(Any())).isTrue()
        assertThat(selector.starts).isEqualTo(1)
    }

    @Test
    fun noUnfold_handshakeDoesNotLatch() {
        selector.onSettingChanged(FoldAnimationStyle.PIXEL)

        underTest.onScreenTurningOn(onOverlayReady)
        testableLooper.processAllMessages()

        assertThat(selector.latchedStyle()).isNull()
    }

    private fun unfoldAfterFold() {
        foldStateCallback.onDeviceStateChanged(foldedState)
        foldStateCallback.onDeviceStateChanged(unfoldedState)
        testableLooper.processAllMessages()
    }
}
