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

import android.content.ContentResolver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display
import android.view.DisplayInfo
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.YrrpFoldAnimationLog
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.unfold.FullscreenLightRevealAnimationController
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_TRANSPARENT
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import com.android.systemui.unfold.yrrp.DuoBlurCurve.Anchor
import com.android.systemui.unfold.yrrp.DuoOpenSequence.Action
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The cover side of a Duo open. While folded with the cover on, it listens to the hinge
 * ([CoverHingeAngleSource]); once the hinge leaves 0 degrees it captures the cover and blurs it
 * in from the right edge with [DuoBlurCurve.coverCoverage]. When system_server moves the cover
 * panel to its own logical display, the frozen picture ([DuoCoverFrame]) keeps blurring there
 * until that display goes away. Bounded by the hinge returning to 0, the inner unfold and the
 * display removal; no timeout. [DuoFoldOverlayAnimation] feeds it fold, screen and style
 * changes. Everything runs on the @UnfoldBg handler thread.
 */
@SysUIUnfoldScope
class DuoCoverOpening
@Inject
constructor(
    @UnfoldBg private val bgHandler: Handler,
    private val contentResolver: ContentResolver,
    private val selector: FoldAnimationStyleSelector,
    private val runtimeState: FoldAnimationRuntimeState,
    private val powerInteractor: PowerInteractor,
    private val coverHinge: CoverHingeAngleSource,
    private val coverFrame: DuoCoverFrame,
    private val displayManager: DisplayManager,
    @YrrpFoldAnimationLog private val logBuffer: LogBuffer,
) {
    private lateinit var cover: FullscreenLightRevealAnimationController
    private var panels = DuoPanels(0, 0, 0, 0)
    private val hingeProgress = CoverHingeProgress(DuoBlurCurve.OPEN_ANGLE_DEGREES)
    private val sequence = DuoOpenSequence()
    private var isFolded = false
    private var isScreenOn = false
    private var lastCoverProgress = 0f

    /** True from the first angle above 0 until the cover part is released. */
    val isActive: Boolean
        get() = sequence.state != DuoOpenSequence.State.IDLE

    /** Shares the overlay animation's cover controller; call once, on bg. */
    fun init(cover: FullscreenLightRevealAnimationController, panels: DuoPanels) {
        this.cover = cover
        this.panels = panels
        // A SystemUI restart with the cover already on gets no screen-turning-on.
        isScreenOn = powerInteractor.screenPowerState.value == ScreenPowerState.SCREEN_ON
        displayManager.registerDisplayListener(CoverDisplayListener(), bgHandler)
    }

    fun onFoldChanged(folded: Boolean) {
        isFolded = folded
        updateListening()
    }

    fun onScreenChanged(on: Boolean) {
        isScreenOn = on
        updateListening()
    }

    fun onStyleChanged() = updateListening()

    /** The default display is the inner screen now; a cover overlay left on it would blur it. */
    fun onInnerUnfoldStarted() {
        // The frame takes over once the cover's own display appears.
        if (sequence.state == DuoOpenSequence.State.COVER_ON_DEFAULT) cover.ensureOverlayRemoved()
    }

    /** The unfold is over; a cover that never got its own display has nothing left to do. */
    fun onUnfoldFinished() {
        if (sequence.state == DuoOpenSequence.State.COVER_ON_DEFAULT) perform(sequence.cancel())
    }

    fun cancel(reason: String) {
        if (!isActive) return
        log("cover opening cancelled reason=$reason")
        perform(sequence.cancel())
    }

    private fun isDuo() =
        selector.currentStyle() == FoldAnimationStyle.DUO && contentResolver.areAnimationsEnabled()

    /** Listens while folded, screen on and Duo, and while an open runs past the fold change. */
    private fun updateListening() {
        val wanted = hingeProgress.shouldListen(isFolded, isScreenOn, isDuo())
        if (wanted && !coverHinge.isListening) {
            if (!coverHinge.start(::onCoverAngle)) log("cover hinge unavailable")
        } else if (!wanted && coverHinge.isListening && !isActive) {
            coverHinge.stop()
        }
    }

    private fun onCoverAngle(angleDegrees: Float) {
        // The fold's own cover reveal owns the cover overlay until it finishes.
        if (runtimeState.phase == FoldAnimationPhase.COVER_REVEAL) return
        lastCoverProgress = hingeProgress.progressFor(angleDegrees)
        perform(sequence.onCoverAngle(angleDegrees))
    }

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> Unit
            Action.CAPTURE_AND_ATTACH -> start()
            Action.UPDATE_COVER -> applyBlur()
            Action.MOVE_TO_COVER_DISPLAY -> moveToOwnDisplay()
            Action.RELEASE_COVER -> release()
        }
    }

    private fun start() {
        coverFrame.capture(Display.DEFAULT_DISPLAY)
        try {
            // Starts clear; the first rows follow at once and fade the blur in with the hinge.
            cover.addOverlay(ALPHA_TRANSPARENT, null, 0)
            runtimeState.overlayAttached = true
            runtimeState.phase = FoldAnimationPhase.COVER_OPENING
            log("overlay added phase=${FoldAnimationPhase.COVER_OPENING}")
        } catch (e: RuntimeException) {
            cover.ensureOverlayRemoved()
            log("failed stage=attach exception=${e.javaClass.simpleName}")
        }
        applyBlur()
        recordFront()
    }

    private fun moveToOwnDisplay() {
        val id = sequence.coverDisplayId
        runtimeState.dualPanel = true
        coverFrame.show(id, panels.coverW, panels.coverH) { shown ->
            if (shown) applyBlur()
            log("cover frame display=$id shown=$shown")
        }
        cover.ensureOverlayRemoved()
    }

    private fun release() {
        recordFront()
        coverFrame.release()
        cover.ensureOverlayRemoved()
        runtimeState.dualPanel = false
        if (runtimeState.phase == FoldAnimationPhase.COVER_OPENING) {
            runtimeState.overlayAttached = false
            runtimeState.phase = FoldAnimationPhase.IDLE
        }
        log("cover overlay removed")
        updateListening()
    }

    /** Blurs the cover's right-hand share for [lastCoverProgress], on the frame once shown. */
    private fun applyBlur() {
        val coverage = DuoBlurCurve.coverCoverage(lastCoverProgress)
        val maxBlurPx = (DuoBlurCurve.MAX_BLUR_PX * coverage).roundToInt()
        val (w, h) = panels.coverW to panels.coverH
        val rows = DuoBlurCurve.blurStrips(w, h, w * coverage, maxBlurPx, Anchor.RIGHT)
        if (rows.isNotEmpty()) {
            // The frame applies regions whatever the blur setting, so check availability here.
            runtimeState.blurAvailable = cover.isBlurAvailable()
            if (!runtimeState.blurAvailable) return
        }
        if (sequence.state == DuoOpenSequence.State.COVER_ON_OWN_DISPLAY) {
            coverFrame.setBlurRegions(rows)
        } else {
            cover.setBlurRegions(rows)
        }
    }

    /** Only at the start and end of the cover part, never per frame. */
    private fun recordFront() {
        val coverage = DuoBlurCurve.coverCoverage(lastCoverProgress)
        runtimeState.coverBlurFrontPx = DuoBlurCurve.frontPx(panels.coverW, coverage, Anchor.RIGHT)
    }

    /** A narrow internal display other than the default is the cover's own display. */
    private fun onDisplayAppeared(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) return
        val display = displayManager.getDisplay(displayId) ?: return
        val info = DisplayInfo().apply { display.getDisplayInfo(this) }
        if (info.type != Display.TYPE_INTERNAL || info.naturalWidth >= panels.innerW) return
        perform(sequence.onCoverDisplayAdded(displayId))
    }

    /**
     * Enabling and disabling a display also arrive as added and removed (LogicalDisplayMapper
     * turns an enable flip into an ADDED or REMOVED logical display event), so changes are
     * ignored.
     */
    private inner class CoverDisplayListener : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onBg { onDisplayAppeared(displayId) }

        override fun onDisplayRemoved(displayId: Int) = onBg {
            perform(sequence.onCoverDisplayRemoved(displayId))
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private fun onBg(f: () -> Unit) {
        if (bgHandler.looper.isCurrentThread) f() else bgHandler.post(f)
    }

    private fun log(message: String) =
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = message }, { str1!! })

    private companion object {
        const val TAG = "YrrpFoldAnimation"
    }
}
