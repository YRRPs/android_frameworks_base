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
import com.android.systemui.unfold.yrrp.DuoOpenSequence.State
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * The cover side of a Duo open. While folded with the cover on, it listens to the hinge
 * ([CoverHingeAngleSource]). [DuoOpenSequence] arms on a closed reading; the first reading above
 * closed then captures the cover and blurs it in from the right edge with
 * [DuoBlurCurve.coverCoverage]. When system_server moves the cover panel to its own logical
 * display, the frozen picture ([DuoCoverFrame]) keeps blurring there until that display goes
 * away. Bounded by the hinge returning to 0, the inner unfold, the display removal, a fold, a
 * style change and a failed frame; no timeout. [DuoFoldOverlayAnimation] feeds it fold, screen
 * and style changes. Everything runs on the @UnfoldBg handler thread.
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
    private var panels = DuoPanels.NONE
    private val hingeProgress = CoverHingeProgress(DuoBlurCurve.OPEN_ANGLE_DEGREES)
    private val sequence = DuoOpenSequence()
    private var isFolded: Boolean? = null
    private var isScreenOn = false
    private var lastCoverProgress = 0f
    // The cover overlay was dropped for the inner unfold; the cover blur waits for the frame.
    private var overlayHandedOff = false

    /** True from the capture until the cover part is released. */
    val isActive: Boolean
        get() = sequence.isActive

    /** Shares the overlay animation's cover controller; call once, on bg. */
    fun init(cover: FullscreenLightRevealAnimationController, panels: DuoPanels) {
        this.cover = cover
        this.panels = panels
        // A SystemUI restart with the cover already on gets no screen-turning-on.
        isScreenOn = powerInteractor.screenPowerState.value == ScreenPowerState.SCREEN_ON
        val events =
            DisplayManager.EVENT_TYPE_DISPLAY_ADDED or DisplayManager.EVENT_TYPE_DISPLAY_REMOVED
        displayManager.registerDisplayListener(CoverDisplayListener(), bgHandler, events)
    }

    /** Every fold report, the first one included. */
    fun onFoldChanged(folded: Boolean) {
        if (folded && isFolded == false) {
            // The screen that was on is the inner one; the cover's own screen-on sets this again.
            isScreenOn = false
        }
        isFolded = folded
        if (folded) {
            if (isActive) perform(sequence.cancel())
            sequence.disarm()
        }
        updateListening()
    }

    fun onScreenChanged(on: Boolean) {
        isScreenOn = on
        updateListening()
    }

    fun onStyleChanged() {
        if (isActive && !isDuo()) cancel("STYLE")
        updateListening()
    }

    /** The default display is the inner screen now; a cover overlay left on it would blur it. */
    fun onInnerUnfoldStarted() {
        // The frame takes over once the cover's own display appears.
        if (sequence.state != State.COVER_ON_DEFAULT) return
        cover.ensureOverlayRemoved()
        overlayHandedOff = true
    }

    /** The unfold is over; a cover that never got its own display has nothing left to do. */
    fun onUnfoldFinished() {
        if (sequence.state == State.COVER_ON_DEFAULT) perform(sequence.cancel())
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
        val wanted = hingeProgress.shouldListen(isFolded == true, isScreenOn, isDuo())
        if (wanted && !coverHinge.isListening) {
            // The first reading after a restart may come from a hinge that is still closing.
            sequence.disarm()
            if (!coverHinge.start(::onCoverAngle)) log("cover hinge unavailable")
        } else if (!wanted && coverHinge.isListening && !isActive) {
            coverHinge.stop()
        }
    }

    private fun onCoverAngle(angleDegrees: Float) {
        // The fold's own cover reveal owns the cover overlay; only a closed reading may arm.
        val revealing = runtimeState.phase == FoldAnimationPhase.COVER_REVEAL
        if (revealing && sequence.state != State.DISARMED) return
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
        // A synchronous screenshot on bg, once per open, taken before the overlay is attached so
        // the picture holds no blur.
        coverFrame.capture(Display.DEFAULT_DISPLAY)
        overlayHandedOff = false
        try {
            // Starts clear; the first rows follow at once and fade the blur in with the hinge.
            cover.addOverlay(ALPHA_TRANSPARENT, null, 0)
        } catch (e: RuntimeException) {
            cover.ensureOverlayRemoved()
            log("failed stage=attach exception=${e.javaClass.simpleName}")
            return
        }
        runtimeState.overlayAttached = true
        runtimeState.phase = FoldAnimationPhase.COVER_OPENING
        log("overlay added phase=${FoldAnimationPhase.COVER_OPENING}")
        applyBlur()
        recordFront()
    }

    private fun moveToOwnDisplay() {
        val id = sequence.coverDisplayId
        coverFrame.show(id, panels.coverW, panels.coverH) { shown ->
            runtimeState.dualPanel = shown
            if (shown) {
                applyBlur()
                log("cover frame display=$id shown=true")
            } else {
                log("cover frame failed display=$id")
                perform(sequence.cancel())
            }
        }
        cover.ensureOverlayRemoved()
        if (runtimeState.phase == FoldAnimationPhase.COVER_OPENING) {
            runtimeState.overlayAttached = false
        }
    }

    private fun release() {
        recordFront()
        coverFrame.release()
        cover.ensureOverlayRemoved()
        overlayHandedOff = false
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
        val rows = DuoBlurCurve.rowsFor(w, h, coverage, maxBlurPx, Anchor.RIGHT)
        // The frame applies regions whatever the blur setting, so check availability here.
        val available = rows.isEmpty() || cover.isBlurAvailable()
        if (rows.isNotEmpty() && runtimeState.phase == FoldAnimationPhase.COVER_OPENING) {
            runtimeState.blurAvailable = available
        }
        if (!available) return
        when {
            sequence.state == State.COVER_ON_OWN_DISPLAY -> coverFrame.setBlurRegions(rows)
            !overlayHandedOff -> cover.setBlurRegions(rows)
        }
    }

    /** Only at the start and end of the cover part, never per frame. */
    private fun recordFront() {
        val coverage = DuoBlurCurve.coverCoverage(lastCoverProgress)
        runtimeState.coverBlurFrontPx = DuoBlurCurve.frontPx(panels.coverW, coverage, Anchor.RIGHT)
    }

    /** A narrow internal display other than the default is the cover's own display. */
    private fun onDisplayAdded(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY || sequence.state != State.COVER_ON_DEFAULT) return
        val display = displayManager.getDisplay(displayId) ?: return
        val info = DisplayInfo().apply { display.getDisplayInfo(this) }
        if (info.type != Display.TYPE_INTERNAL || info.naturalWidth >= panels.innerW) {
            log("cover display rejected id=$displayId type=${info.type} w=${info.naturalWidth}")
            return
        }
        perform(sequence.onCoverDisplayAdded(displayId))
    }

    /**
     * Registered on bg for added and removed only: enabling and disabling a display arrive as
     * added and removed (LogicalDisplayMapper turns an enable flip into an ADDED or REMOVED
     * logical display event).
     */
    private inner class CoverDisplayListener : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) =
            this@DuoCoverOpening.onDisplayAdded(displayId)

        override fun onDisplayRemoved(displayId: Int) =
            perform(sequence.onCoverDisplayRemoved(displayId))

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private fun log(message: String) =
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = message }, { str1!! })

    private companion object {
        const val TAG = "YrrpFoldAnimation"
    }
}
