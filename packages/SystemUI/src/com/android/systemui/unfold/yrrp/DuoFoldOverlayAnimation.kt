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
import android.os.Handler
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
 * The Duo fold animation's blur. Unfolding: the inner screen turns on fully blurred and clears
 * with hinge progress while Shell stretches the tasks. Folding: the inner screen blurs as the
 * hinge closes, then the cover screen turns on blurred and clears over [COVER_REVEAL_MS]. Runs
 * only while the latched style is Duo; every exit removes the overlay.
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
) : FullscreenLightRevealAnimation {

    private lateinit var inner: FullscreenLightRevealAnimationController
    private lateinit var cover: FullscreenLightRevealAnimationController
    private var isFolded = false
    private var pendingUnfold = false
    private var pendingCoverReveal = false
    private var coverAnimator: ValueAnimator? = null
    private var coverWaitJob: Job? = null

    override fun init() {
        inner =
            controllerFactory.create(
                displaySelector = { maxByOrNull { it.naturalWidth } },
                effectFactory = { LinearLightRevealEffect(it.isVerticalRotation()) },
                overlayContainerName = INNER_OVERLAY_TITLE,
            )
        cover =
            controllerFactory.create(
                displaySelector = { minByOrNull { it.naturalWidth } },
                effectFactory = { LinearLightRevealEffect(it.isVerticalRotation()) },
                overlayContainerName = COVER_OVERLAY_TITLE,
            )
        inner.init()
        cover.init()
        deviceStateManager.registerCallback(
            threadFactory.buildDelayableExecutorOnHandler(bgHandler),
            FoldListener(),
        )
        bgProgressProvider.addCallback(ProgressListener())
        applicationScope.launch {
            powerInteractor.isAsleep.collect { asleep -> if (asleep) onBg { cancel("ASLEEP") } }
        }
    }

    private fun isDuo() =
        selector.currentStyle() == FoldAnimationStyle.DUO && contentResolver.areAnimationsEnabled()

    @BinderThread
    override fun onScreenTurningOn(onOverlayReady: Runnable) = onBg {
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
            unfold && isDuo() -> {
                attach(inner, FoldAnimationPhase.UNFOLDING, onOverlayReady)
                inner.isTouchBlocked = true
            }
            coverReveal && isDuo() -> {
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
            else -> onOverlayReady.run()
        }
    }

    private fun attach(
        controller: FullscreenLightRevealAnimationController,
        phase: FoldAnimationPhase,
        onOverlayReady: Runnable,
    ) {
        // The commit listener and the failure path must not both report ready.
        val reported = AtomicBoolean(false)
        val readyOnce = Runnable {
            if (reported.compareAndSet(false, true)) onOverlayReady.run()
        }
        try {
            controller.addOverlay(ALPHA_TRANSPARENT, readyOnce)
            runtimeState.blurAvailable =
                controller.setBackgroundBlurRadius(DuoBlurCurve.MAX_BLUR_PX)
            runtimeState.overlayAttached = true
            runtimeState.phase = phase
            log("overlay added phase=$phase blur=${runtimeState.blurAvailable}")
        } catch (e: RuntimeException) {
            controller.ensureOverlayRemoved()
            readyOnce.run()
            log("failed stage=attach exception=${e.javaClass.simpleName}")
        }
    }

    /** Runs the timed cover-screen clear once the cover panel is on. */
    fun onCoverScreenOn() {
        if (runtimeState.phase != FoldAnimationPhase.COVER_REVEAL) return
        coverAnimator?.cancel()
        coverAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = COVER_REVEAL_MS
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    // animatedFraction is already eased by the decelerate interpolator.
                    runtimeState.blurAvailable =
                        cover.setBackgroundBlurRadius(
                            (DuoBlurCurve.MAX_BLUR_PX * (1f - it.animatedFraction)).roundToInt()
                        )
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        private var cancelled = false

                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            // A cancel already cleaned up or superseded this run.
                            if (!cancelled) {
                                onBg {
                                    if (runtimeState.phase == FoldAnimationPhase.COVER_REVEAL) {
                                        detach(cover)
                                    }
                                }
                            }
                        }
                    }
                )
                start()
            }
    }

    private fun detach(controller: FullscreenLightRevealAnimationController) {
        controller.ensureOverlayRemoved()
        controller.isTouchBlocked = false
        runtimeState.overlayAttached = false
        runtimeState.phase = FoldAnimationPhase.IDLE
        log("overlay removed")
    }

    private fun cancel(reason: String) {
        coverAnimator?.cancel()
        coverWaitJob?.cancel()
        if (runtimeState.overlayAttached) runtimeState.recordCancel(reason)
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
                attach(inner, FoldAnimationPhase.FOLDING, Runnable {})
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
            // Retry every callback: the first apply can precede a valid surface.
            runtimeState.blurAvailable =
                inner.setBackgroundBlurRadius(DuoBlurCurve.radiusFor(progress))
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
        }
    }

    private inner class FoldListener :
        DeviceStateManager.FoldStateListener(
            context,
            Consumer { folded ->
                if (folded == isFolded) return@Consumer
                if (folded && runtimeState.phase == FoldAnimationPhase.UNFOLDING) {
                    cancel("FOLDED")
                } else if (!folded && runtimeState.phase == FoldAnimationPhase.COVER_REVEAL) {
                    cancel("UNFOLDED")
                } else if (folded) {
                    inner.ensureOverlayRemoved()
                    runtimeState.overlayAttached = false
                    runtimeState.phase = FoldAnimationPhase.IDLE
                }
                isFolded = folded
                if (folded) {
                    // The next screen-on is the cover screen's; the one after the unfold is the
                    // inner screen's.
                    pendingUnfold = true
                    pendingCoverReveal = true
                } else {
                    pendingCoverReveal = false
                }
            },
        )

    private fun onBg(f: () -> Unit) {
        if (bgHandler.looper.isCurrentThread) f() else bgHandler.post(f)
    }

    private fun log(message: String) =
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = message }, { str1!! })

    companion object {
        private const val TAG = "YrrpFoldAnimation"
        private const val INNER_OVERLAY_TITLE = "yrrp-duo-unfold-overlay"
        private const val COVER_OVERLAY_TITLE = "yrrp-duo-cover-overlay"
        const val COVER_REVEAL_MS = 350L
        const val READY_TIMEOUT_MS = 2000L
    }
}
