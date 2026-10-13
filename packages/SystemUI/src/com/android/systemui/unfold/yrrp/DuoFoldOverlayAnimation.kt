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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.BinderThread
import android.content.ContentResolver
import android.content.Context
import android.hardware.devicestate.DeviceStateManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display
import android.view.DisplayInfo
import android.view.animation.DecelerateInterpolator
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.YrrpFoldAnimationLog
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.ScreenPowerState
import com.android.systemui.statusbar.LinearLightRevealEffect
import com.android.systemui.unfold.FullscreenLightRevealAnimation
import com.android.systemui.unfold.FullscreenLightRevealAnimationController
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.ALPHA_TRANSPARENT
import com.android.systemui.unfold.FullscreenLightRevealAnimationController.Companion.isVerticalRotation
import com.android.systemui.unfold.SysUIUnfoldScope
import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.dagger.UnfoldBg
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import com.android.systemui.unfold.yrrp.DuoBlurCurve.Anchor
import com.android.systemui.unfold.yrrp.DuoOpenSequence.Action
import com.android.systemui.util.concurrency.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The Duo fold animation's directional blur. Opening: while folded with the cover on, the cover
 * blurs in from its right edge as soon as the hinge leaves 0 degrees ([CoverHingeAngleSource]).
 * When system_server moves the cover panel to its own logical display, the frozen cover picture
 * ([DuoCoverFrame]) keeps blurring there until that display goes away. The inner screen turns
 * on fully blurred and clears right to left with unfold progress, blocking touch until
 * [DuoBlurCurve.TOUCH_UNBLOCK_PROGRESS]. Folding: the inner blur grows back, then the cover
 * screen turns on blurred and sweeps clear left to right over [COVER_REVEAL_MS]. Runs only
 * while the latched style is Duo; every exit removes the overlays and the frame.
 */
@SysUIUnfoldScope
class DuoFoldOverlayAnimation
@Inject
constructor(
    private val context: Context,
    private val contentResolver: ContentResolver,
    @UnfoldBg private val bgHandler: Handler,
    @UnfoldBg private val bgProgressProvider: UnfoldTransitionProgressProvider,
    private val deviceStateManager: DeviceStateManager,
    private val threadFactory: ThreadFactory,
    private val controllerFactory: FullscreenLightRevealAnimationController.Factory,
    private val selector: FoldAnimationStyleSelector,
    private val runtimeState: FoldAnimationRuntimeState,
    private val powerInteractor: PowerInteractor,
    @Background private val applicationScope: CoroutineScope,
    @YrrpFoldAnimationLog private val logBuffer: LogBuffer,
    private val coverHinge: CoverHingeAngleSource,
    private val coverFrame: DuoCoverFrame,
    private val displayManager: DisplayManager,
) : FullscreenLightRevealAnimation {

    private lateinit var inner: FullscreenLightRevealAnimationController
    private lateinit var cover: FullscreenLightRevealAnimationController
    private val hingeProgress = CoverHingeProgress(DuoBlurCurve.OPEN_ANGLE_DEGREES)
    private val openSequence = DuoOpenSequence()
    private var coverW = 0
    private var coverH = 0
    private var innerW = 0
    private var innerH = 0
    private var isFolded = false
    private var isScreenOn = false
    private var pendingUnfold = false
    private var pendingCoverReveal = false
    private var lastCoverProgress = 0f
    private var lastInnerProgress = 0f
    private var coverBlurredShare = 0f
    private var coverAnimator: ValueAnimator? = null
    private var coverWaitJob: Job? = null

    override fun init() {
        inner = createController(INNER_OVERLAY_TITLE) { maxByOrNull { it.naturalWidth } }
        cover = createController(COVER_OVERLAY_TITLE) { minByOrNull { it.naturalWidth } }
        inner.init()
        cover.init()
        readPanelSizes()
        deviceStateManager.registerCallback(
            threadFactory.buildDelayableExecutorOnHandler(bgHandler),
            FoldListener(),
        )
        bgProgressProvider.addCallback(ProgressListener())
        displayManager.registerDisplayListener(CoverDisplayListener(), bgHandler)
        applicationScope.launch {
            powerInteractor.isAsleep.collect { asleep ->
                if (asleep) {
                    onBg {
                        isScreenOn = false
                        cancel("ASLEEP")
                        updateCoverListening()
                    }
                }
            }
        }
    }

    private fun createController(
        title: String,
        selectDisplay: List<DisplayInfo>.() -> DisplayInfo?,
    ): FullscreenLightRevealAnimationController =
        controllerFactory.create(
            displaySelector = selectDisplay,
            effectFactory = { LinearLightRevealEffect(it.isVerticalRotation()) },
            overlayContainerName = title,
        )

    /** The same internal-display selection the controllers use: narrowest cover, widest inner. */
    private fun readPanelSizes() {
        val internal =
            displayManager
                .getDisplays(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)
                .map { DisplayInfo().apply { it.getDisplayInfo(this) } }
                .filter { it.type == Display.TYPE_INTERNAL }
        internal.minByOrNull { it.naturalWidth }?.let {
            coverW = it.naturalWidth
            coverH = it.naturalHeight
        }
        internal.maxByOrNull { it.naturalWidth }?.let {
            innerW = it.naturalWidth
            innerH = it.naturalHeight
        }
    }

    private fun isDuo() =
        selector.currentStyle() == FoldAnimationStyle.DUO && contentResolver.areAnimationsEnabled()

    /** The style setting changed; the cover hinge listens only for Duo. */
    fun onStyleChanged() = onBg { updateCoverListening() }

    @BinderThread
    override fun onScreenTurningOn(onOverlayReady: Runnable) = onBg {
        isScreenOn = true
        updateCoverListening()
        // Consume the pending flags whatever the style, so a stale flag never leaks into a later
        // Duo transition.
        val unfold = !isFolded && pendingUnfold
        val coverReveal = isFolded && pendingCoverReveal
        if (unfold) pendingUnfold = false
        if (coverReveal) pendingCoverReveal = false
        // Latch before reading the style so this overlay and the gated transition that follows
        // agree on it. Only the unfold: the cover reveal has no gated transition to release it.
        if (unfold && contentResolver.areAnimationsEnabled()) selector.latchIfIdle()
        when {
            unfold && isDuo() -> startUnfold(onOverlayReady)
            coverReveal && isDuo() -> startCoverReveal(onOverlayReady)
            else -> onOverlayReady.run()
        }
    }

    private fun startUnfold(onOverlayReady: Runnable) {
        // The default display is the inner screen now; a cover overlay left on it would blur it.
        // The frame takes over once the cover's own display appears.
        if (openSequence.state == DuoOpenSequence.State.COVER_ON_DEFAULT) {
            cover.ensureOverlayRemoved()
        }
        lastInnerProgress = 0f
        attach(inner, FoldAnimationPhase.UNFOLDING, onOverlayReady)
        inner.isTouchBlocked = true
    }

    private fun startCoverReveal(onOverlayReady: Runnable) {
        coverBlurredShare = 1f
        attach(cover, FoldAnimationPhase.COVER_REVEAL, onOverlayReady)
        coverWaitJob?.cancel()
        coverWaitJob =
            applicationScope.launch {
                try {
                    withTimeout(READY_TIMEOUT_MS) {
                        powerInteractor.screenPowerState
                            .filter { it == ScreenPowerState.SCREEN_ON }
                            .first()
                    }
                    onBg { onCoverScreenOn() }
                } catch (e: TimeoutCancellationException) {
                    onBg {
                        if (runtimeState.phase == FoldAnimationPhase.COVER_REVEAL) {
                            cancel("COVER_TIMEOUT")
                        }
                    }
                }
            }
    }

    private fun attach(
        controller: FullscreenLightRevealAnimationController,
        phase: FoldAnimationPhase,
        onOverlayReady: Runnable,
        initialBlurPx: Int = DuoBlurCurve.MAX_BLUR_PX,
    ) {
        // The commit listener and the failure path must not both report ready.
        val reported = AtomicBoolean(false)
        val readyOnce = Runnable {
            if (reported.compareAndSet(false, true)) onOverlayReady.run()
        }
        try {
            // The blur rides in the overlay's first committed frame, before ready is reported.
            controller.addOverlay(ALPHA_TRANSPARENT, readyOnce, initialBlurPx)
            runtimeState.blurAvailable = initialBlurPx == 0 || controller.isBlurAvailable()
            runtimeState.overlayAttached = true
            runtimeState.phase = phase
            armSafetyTimeout(phase)
            recordFront(controller)
            log("overlay added phase=$phase blur=${runtimeState.blurAvailable}")
        } catch (e: RuntimeException) {
            controller.ensureOverlayRemoved()
            readyOnce.run()
            log("failed stage=attach exception=${e.javaClass.simpleName}")
        }
    }

    /** Records the blur front of [controller]'s screen; only at transition boundaries. */
    private fun recordFront(controller: FullscreenLightRevealAnimationController) {
        if (controller === inner) {
            val share = DuoBlurCurve.innerRemaining(lastInnerProgress)
            runtimeState.innerBlurFrontPx = DuoBlurCurve.frontPx(innerW, share, Anchor.LEFT)
        } else {
            runtimeState.coverBlurFrontPx =
                DuoBlurCurve.frontPx(coverW, coverBlurredShare, Anchor.RIGHT)
        }
    }

    /**
     * The inner overlay blurs and blocks touch until the transition finishes, and the cover
     * opening runs until the unfold takes over; a lost finish must not leave either up. The
     * cover reveal has its own bounds.
     */
    private fun armSafetyTimeout(phase: FoldAnimationPhase) {
        bgHandler.removeCallbacks(safetyTimeout)
        if (phase in TIMED_PHASES) bgHandler.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MS)
    }

    private val safetyTimeout = Runnable {
        if (runtimeState.phase in TIMED_PHASES) cancel("TIMEOUT")
    }

    /** Listens to the cover hinge while folded, screen on and Duo, or while an open runs. */
    private fun updateCoverListening() {
        val wanted = hingeProgress.shouldListen(isFolded, isScreenOn, isDuo())
        if (wanted && !coverHinge.isListening) {
            if (!coverHinge.start(::onCoverAngle)) log("cover hinge unavailable")
        } else if (
            !wanted && coverHinge.isListening && openSequence.state == DuoOpenSequence.State.IDLE
        ) {
            coverHinge.stop()
        }
    }

    private fun onCoverAngle(angleDegrees: Float) {
        // The fold's own cover reveal owns the cover overlay until it finishes.
        if (runtimeState.phase == FoldAnimationPhase.COVER_REVEAL) return
        lastCoverProgress = hingeProgress.progressFor(angleDegrees)
        perform(openSequence.onCoverAngle(angleDegrees))
    }

    private fun perform(action: Action) {
        when (action) {
            Action.NONE -> Unit
            Action.CAPTURE_AND_ATTACH -> startCoverOpening()
            Action.UPDATE_COVER -> applyCoverBlur()
            Action.MOVE_TO_COVER_DISPLAY -> moveCoverToOwnDisplay()
            Action.RELEASE_COVER -> releaseCover()
        }
    }

    private fun startCoverOpening() {
        coverFrame.capture(Display.DEFAULT_DISPLAY)
        attach(cover, FoldAnimationPhase.COVER_OPENING, Runnable {}, initialBlurPx = 0)
        applyCoverBlur()
        recordFront(cover)
    }

    private fun moveCoverToOwnDisplay() {
        val id = openSequence.coverDisplayId
        runtimeState.dualPanel = true
        coverFrame.show(id, coverW, coverH) { shown ->
            if (shown) applyCoverBlur()
            log("cover frame display=$id shown=$shown")
        }
        cover.ensureOverlayRemoved()
    }

    private fun releaseCover() {
        recordFront(cover)
        coverFrame.release()
        cover.ensureOverlayRemoved()
        runtimeState.dualPanel = false
        if (runtimeState.phase == FoldAnimationPhase.COVER_OPENING) {
            bgHandler.removeCallbacks(safetyTimeout)
            runtimeState.overlayAttached = false
            runtimeState.phase = FoldAnimationPhase.IDLE
        }
        log("cover overlay removed")
        updateCoverListening()
    }

    /** Blurs the cover's right-hand share for [lastCoverProgress], on the frame once shown. */
    private fun applyCoverBlur() {
        val coverage = DuoBlurCurve.coverCoverage(lastCoverProgress)
        coverBlurredShare = coverage
        val maxBlurPx = (DuoBlurCurve.MAX_BLUR_PX * coverage).roundToInt()
        val rows =
            DuoBlurCurve.blurStrips(coverW, coverH, coverW * coverage, maxBlurPx, Anchor.RIGHT)
        if (rows.isNotEmpty()) {
            // The frame applies regions whatever the blur setting, so check availability here.
            runtimeState.blurAvailable = cover.isBlurAvailable()
            if (!runtimeState.blurAvailable) return
        }
        if (openSequence.state == DuoOpenSequence.State.COVER_ON_OWN_DISPLAY) {
            coverFrame.setBlurRegions(rows)
        } else {
            cover.setBlurRegions(rows)
        }
    }

    /** A narrow internal display other than the default is the cover's own display. */
    private fun onCoverDisplayAppeared(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) return
        val display = displayManager.getDisplay(displayId) ?: return
        val info = DisplayInfo().apply { display.getDisplayInfo(this) }
        if (info.type != Display.TYPE_INTERNAL || info.naturalWidth >= innerW) return
        perform(openSequence.onCoverDisplayAdded(displayId))
    }

    /** Runs the timed cover-screen clear once the cover panel is on. */
    fun onCoverScreenOn() {
        if (runtimeState.phase != FoldAnimationPhase.COVER_REVEAL) return
        coverAnimator?.cancel()
        coverAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = COVER_REVEAL_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener { onCoverRevealFrame(it.animatedFraction) }
                addListener(CoverRevealEndListener())
                start()
            }
    }

    /** Clears the cover left to right: the blurred share shrinks toward the right edge. */
    private fun onCoverRevealFrame(animatedFraction: Float) {
        // animatedFraction is already eased by the decelerate interpolator.
        coverBlurredShare = 1f - animatedFraction
        val rows =
            DuoBlurCurve.blurStrips(
                coverW,
                coverH,
                coverW * coverBlurredShare,
                DuoBlurCurve.MAX_BLUR_PX,
                Anchor.RIGHT,
            )
        runtimeState.blurAvailable = cover.setBlurRegions(rows)
    }

    private inner class CoverRevealEndListener : AnimatorListenerAdapter() {
        private var cancelled = false

        override fun onAnimationCancel(animation: Animator) {
            cancelled = true
        }

        override fun onAnimationEnd(animation: Animator) {
            // A cancel already cleaned up or superseded this run.
            if (!cancelled) {
                onBg {
                    if (runtimeState.phase == FoldAnimationPhase.COVER_REVEAL) detach(cover)
                }
            }
        }
    }

    private fun detach(controller: FullscreenLightRevealAnimationController) {
        bgHandler.removeCallbacks(safetyTimeout)
        controller.ensureOverlayRemoved()
        controller.isTouchBlocked = false
        recordFront(controller)
        runtimeState.overlayAttached = false
        runtimeState.phase = FoldAnimationPhase.IDLE
        log("overlay removed")
    }

    private fun cancel(reason: String) {
        val active =
            runtimeState.overlayAttached || openSequence.state != DuoOpenSequence.State.IDLE
        perform(openSequence.cancel())
        when (runtimeState.phase) {
            FoldAnimationPhase.UNFOLDING, FoldAnimationPhase.FOLDING -> recordFront(inner)
            FoldAnimationPhase.COVER_REVEAL -> recordFront(cover)
            else -> Unit
        }
        bgHandler.removeCallbacks(safetyTimeout)
        // A cover reveal does not survive an interruption such as sleep. pendingUnfold stays: the
        // device is still folded and its unfold screen-on is still due.
        pendingCoverReveal = false
        coverAnimator?.cancel()
        coverWaitJob?.cancel()
        if (active) runtimeState.recordCancel(reason)
        inner.ensureOverlayRemoved()
        inner.isTouchBlocked = false
        cover.ensureOverlayRemoved()
        runtimeState.overlayAttached = false
        runtimeState.phase = FoldAnimationPhase.IDLE
        log("cancelled reason=$reason")
    }

    private inner class ProgressListener :
        UnfoldTransitionProgressProvider.TransitionProgressListener {
        override fun onTransitionStarted() = onBg {
            // Folding on the inner screen: the overlay is not attached yet. A pending unfold means
            // this transition is the unfold whose screen-on has not arrived, not a fold.
            if (isDuo() && !isFolded && !pendingUnfold && !runtimeState.overlayAttached) {
                // Progress is near 1 here, so start clear and let progress callbacks blur.
                lastInnerProgress = 1f
                attach(inner, FoldAnimationPhase.FOLDING, Runnable {}, initialBlurPx = 0)
                inner.isTouchBlocked = true
            }
        }

        override fun onTransitionProgress(progress: Float) = onBg {
            if (
                runtimeState.phase != FoldAnimationPhase.UNFOLDING &&
                    runtimeState.phase != FoldAnimationPhase.FOLDING
            ) {
                return@onBg
            }
            lastInnerProgress = progress
            val blurredPx = innerW * DuoBlurCurve.innerRemaining(progress)
            val rows =
                DuoBlurCurve.blurStrips(
                    innerW,
                    innerH,
                    blurredPx,
                    DuoBlurCurve.MAX_BLUR_PX,
                    Anchor.LEFT,
                )
            // Retry every callback: the first apply can precede a valid surface.
            runtimeState.blurAvailable = inner.setBlurRegions(rows)
            inner.isTouchBlocked =
                runtimeState.phase == FoldAnimationPhase.FOLDING ||
                    progress < DuoBlurCurve.TOUCH_UNBLOCK_PROGRESS
        }

        override fun onTransitionFinished() = onBg {
            if (
                runtimeState.phase == FoldAnimationPhase.UNFOLDING ||
                    runtimeState.phase == FoldAnimationPhase.FOLDING
            ) {
                detach(inner)
            }
            // The unfold is over; a cover that never got its own display has nothing left to do.
            if (openSequence.state == DuoOpenSequence.State.COVER_ON_DEFAULT) {
                perform(openSequence.cancel())
            }
        }
    }

    private inner class FoldListener :
        DeviceStateManager.FoldStateListener(context, Consumer { onFoldChanged(it) })

    private fun onFoldChanged(folded: Boolean) {
        if (folded == isFolded) return
        if (folded && runtimeState.phase == FoldAnimationPhase.UNFOLDING) {
            cancel("FOLDED")
        } else if (!folded && runtimeState.phase == FoldAnimationPhase.COVER_REVEAL) {
            cancel("UNFOLDED")
        } else if (folded) {
            bgHandler.removeCallbacks(safetyTimeout)
            inner.ensureOverlayRemoved()
            runtimeState.overlayAttached = false
            runtimeState.phase = FoldAnimationPhase.IDLE
        }
        isFolded = folded
        if (folded) {
            // The next screen-on is the cover screen's; the one after the unfold is the inner
            // screen's.
            pendingUnfold = true
            pendingCoverReveal = true
        } else {
            pendingCoverReveal = false
        }
        updateCoverListening()
    }

    /** Display add and remove also report enable and disable (LogicalDisplayMapper). */
    private inner class CoverDisplayListener : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onBg { onCoverDisplayAppeared(displayId) }

        override fun onDisplayRemoved(displayId: Int) = onBg {
            perform(openSequence.onCoverDisplayRemoved(displayId))
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private fun onBg(f: () -> Unit) {
        if (bgHandler.looper.isCurrentThread) f() else bgHandler.post(f)
    }

    private fun log(message: String) =
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = message }, { str1!! })

    companion object {
        private const val TAG = "YrrpFoldAnimation"
        private const val INNER_OVERLAY_TITLE = "yrrp-duo-unfold-overlay"
        private const val COVER_OVERLAY_TITLE = "yrrp-duo-cover-overlay"
        private val TIMED_PHASES =
            setOf(
                FoldAnimationPhase.UNFOLDING,
                FoldAnimationPhase.FOLDING,
                FoldAnimationPhase.COVER_OPENING,
            )
        const val COVER_REVEAL_MS = 350L
        const val READY_TIMEOUT_MS = 2000L
        const val SAFETY_TIMEOUT_MS = 5000L
    }
}
