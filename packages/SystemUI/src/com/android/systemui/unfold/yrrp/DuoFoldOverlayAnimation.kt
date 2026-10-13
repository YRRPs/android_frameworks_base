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
import com.android.systemui.util.concurrency.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * The Duo fold animation's directional blur. Opening: the cover blurs in from its right edge as
 * soon as the hinge leaves 0 degrees, then as a frozen picture on its own display
 * ([DuoCoverOpening], fed the fold, screen and style changes from here). The inner screen turns
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
    private val coverOpening: DuoCoverOpening,
    private val displayManager: DisplayManager,
) : FullscreenLightRevealAnimation {

    private lateinit var inner: FullscreenLightRevealAnimationController
    private lateinit var cover: FullscreenLightRevealAnimationController
    private var panels = DuoPanels.NONE
    private var foldKnown = false
    private var isFolded = false
    private var pendingUnfold = false
    private var pendingCoverReveal = false
    private var lastInnerProgress = 0f
    private var coverBlurredShare = 0f
    private var coverAnimator: ValueAnimator? = null
    private var coverWaitJob: Job? = null

    override fun init() {
        inner = createController(INNER_OVERLAY_TITLE) { maxByOrNull { it.naturalWidth } }
        cover = createController(COVER_OVERLAY_TITLE) { minByOrNull { it.naturalWidth } }
        inner.init()
        cover.init()
        panels = DuoPanels.read(displayManager)
        // The cover opening runs on bg; its init must precede the first fold or screen callback.
        onBg { coverOpening.init(cover, panels) }
        deviceStateManager.registerCallback(
            threadFactory.buildDelayableExecutorOnHandler(bgHandler),
            FoldListener(),
        )
        bgProgressProvider.addCallback(ProgressListener())
        applicationScope.launch {
            powerInteractor.isAsleep.collect { asleep ->
                if (asleep) {
                    onBg {
                        coverOpening.onScreenChanged(false)
                        cancel("ASLEEP")
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

    private fun isDuo() =
        selector.currentStyle() == FoldAnimationStyle.DUO && contentResolver.areAnimationsEnabled()

    /** The style setting changed; the cover hinge listens only for Duo. */
    fun onStyleChanged() = onBg { coverOpening.onStyleChanged() }

    @BinderThread
    override fun onScreenTurningOn(onOverlayReady: Runnable) = onBg {
        coverOpening.onScreenChanged(true)
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
            else -> {
                // A cover opening started under Duo does not carry into another style's unfold.
                if (unfold) coverOpening.cancel("STYLE")
                onOverlayReady.run()
            }
        }
    }

    private fun startUnfold(onOverlayReady: Runnable) {
        coverOpening.onInnerUnfoldStarted()
        lastInnerProgress = 0f
        attach(inner, FoldAnimationPhase.UNFOLDING, onOverlayReady)
        inner.isTouchBlocked = true
    }

    private fun startCoverReveal(onOverlayReady: Runnable) {
        coverBlurredShare = 1f
        // The reveal owns the cover overlay; a cover opening left from before the fold must go.
        coverOpening.cancel("COVER_REVEAL")
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
            runtimeState.innerBlurFrontPx =
                DuoBlurCurve.frontPx(panels.innerW, share, Anchor.LEFT)
        } else {
            runtimeState.coverBlurFrontPx =
                DuoBlurCurve.frontPx(panels.coverW, coverBlurredShare, Anchor.RIGHT)
        }
    }

    /**
     * The inner overlay blurs and blocks touch until the transition finishes; a lost finish must
     * not leave it up. The cover reveal and the cover opening have their own bounds.
     */
    private fun armSafetyTimeout(phase: FoldAnimationPhase) {
        bgHandler.removeCallbacks(safetyTimeout)
        if (phase in INNER_PHASES) bgHandler.postDelayed(safetyTimeout, SAFETY_TIMEOUT_MS)
    }

    private val safetyTimeout = Runnable {
        if (runtimeState.phase in INNER_PHASES) cancel("TIMEOUT")
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
        val (w, h) = panels.coverW to panels.coverH
        val share = coverBlurredShare
        val rows = DuoBlurCurve.rowsFor(w, h, share, DuoBlurCurve.MAX_BLUR_PX, Anchor.RIGHT)
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
        val active = runtimeState.overlayAttached || coverOpening.isActive
        coverOpening.cancel(reason)
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
            if (runtimeState.phase !in INNER_PHASES) return@onBg
            lastInnerProgress = progress
            val (w, h) = panels.innerW to panels.innerH
            val share = DuoBlurCurve.innerRemaining(progress)
            val rows = DuoBlurCurve.rowsFor(w, h, share, DuoBlurCurve.MAX_BLUR_PX, Anchor.LEFT)
            // Retry every callback: the first apply can precede a valid surface.
            runtimeState.blurAvailable = inner.setBlurRegions(rows)
            inner.isTouchBlocked =
                runtimeState.phase == FoldAnimationPhase.FOLDING ||
                    progress < DuoBlurCurve.TOUCH_UNBLOCK_PROGRESS
        }

        override fun onTransitionFinished() = onBg {
            if (runtimeState.phase in INNER_PHASES) detach(inner)
            coverOpening.onUnfoldFinished()
        }
    }

    private inner class FoldListener :
        DeviceStateManager.FoldStateListener(context, Consumer { onFoldChanged(it) })

    private fun onFoldChanged(folded: Boolean) {
        // The first report goes through: the cover opening needs the initial fold state.
        if (foldKnown && folded == isFolded) return
        foldKnown = true
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
        coverOpening.onFoldChanged(folded)
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
        /** The inner overlay's phases; only they have the safety timeout. */
        private val INNER_PHASES = setOf(FoldAnimationPhase.UNFOLDING, FoldAnimationPhase.FOLDING)
        const val COVER_REVEAL_MS = 350L
        const val READY_TIMEOUT_MS = 2000L
        const val SAFETY_TIMEOUT_MS = 5000L
    }
}
