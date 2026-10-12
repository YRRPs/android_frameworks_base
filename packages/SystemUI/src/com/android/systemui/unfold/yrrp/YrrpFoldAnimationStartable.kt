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
import androidx.annotation.GuardedBy
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.YrrpFoldAnimationLog
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Feeds the per-user setting into [FoldAnimationStyleSelector], logs latch transitions, releases a
 * stuck latch on sleep or after [LATCH_TIMEOUT_MS], and dumps every gate. Dump:
 * `dumpsys activity service com.android.systemui/.SystemUIService YrrpFoldAnimation
 * YrrpFoldAnimationLog`.
 */
@SysUISingleton
class YrrpFoldAnimationStartable(
    private val repository: FoldAnimationSettingsRepository,
    private val selector: FoldAnimationStyleSelector,
    private val runtimeState: FoldAnimationRuntimeState,
    private val config: UnfoldTransitionConfig,
    private val support: FoldAnimationSupport,
    private val dumpManager: DumpManager,
    private val logBuffer: LogBuffer,
    private val powerInteractor: PowerInteractor,
    private val backgroundScope: CoroutineScope,
    private val animationsEnabled: () -> Boolean,
) : CoreStartable, Dumpable {

    @Inject
    constructor(
        repository: FoldAnimationSettingsRepository,
        selector: FoldAnimationStyleSelector,
        runtimeState: FoldAnimationRuntimeState,
        config: UnfoldTransitionConfig,
        support: FoldAnimationSupport,
        dumpManager: DumpManager,
        @YrrpFoldAnimationLog logBuffer: LogBuffer,
        powerInteractor: PowerInteractor,
        @Background backgroundScope: CoroutineScope,
        contentResolver: ContentResolver,
    ) : this(
        repository,
        selector,
        runtimeState,
        config,
        support,
        dumpManager,
        logBuffer,
        powerInteractor,
        backgroundScope,
        { contentResolver.areAnimationsEnabled() },
    )

    private val timeoutLock = Any()
    @GuardedBy("timeoutLock") private var timeoutJob: Job? = null

    override fun start() {
        dumpManager.registerNormalDumpable(TAG, this)
        // Not a supported foldable: leave the selector on Stock and observe nothing.
        if (!support.isSupported) {
            log("unsupported device blockedBy=${support.blockedBy}")
            return
        }
        selector.listener =
            object : FoldAnimationStyleSelector.Listener {
                override fun onLatched(style: FoldAnimationStyle) {
                    log("latched style=$style")
                    armTimeout()
                }

                override fun onReleased(style: FoldAnimationStyle) {
                    log("released style=$style")
                    disarmTimeout()
                }

                override fun onForceReleased(style: FoldAnimationStyle, reason: String) {
                    runtimeState.recordCancel(reason)
                    log("released style=$style forced reason=$reason")
                    disarmTimeout()
                }
            }
        backgroundScope.launch {
            powerInteractor.isAsleep.collect { asleep ->
                if (asleep) selector.releaseAll("SLEEP")
            }
        }
        backgroundScope.launch {
            repository.setting.collect { raw ->
                val style = FoldAnimationStyle.fromRaw(raw)
                selector.onSettingChanged(style)
                log("setting raw=$raw style=$style")
            }
        }
    }

    /** A lost finish must not pin the style or swallow the next start: release after a delay. */
    private fun armTimeout() {
        // onLatched runs outside the selector lock, so this latch may already be gone. Release
        // only the generation seen here, never a later latch.
        val generation = selector.latchGeneration
        synchronized(timeoutLock) {
            timeoutJob?.cancel()
            timeoutJob =
                backgroundScope.launch {
                    delay(LATCH_TIMEOUT_MS)
                    selector.releaseIfGeneration(generation, "LATCH_TIMEOUT")
                }
        }
    }

    private fun disarmTimeout() {
        synchronized(timeoutLock) {
            timeoutJob?.cancel()
            timeoutJob = null
        }
    }

    private fun log(message: String) {
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = message }, { str1!! })
    }

    /** The first false gate in evaluation order, or `NONE`. */
    private fun blockedBy(animations: Boolean): String =
        support.blockedBy
            ?: when {
            selector.currentStyle() == FoldAnimationStyle.STOCK -> "STYLE_STOCK"
            !animations -> "ANIMATIONS_DISABLED"
            else -> "NONE"
        }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        val animations = animationsEnabled()
        val blocked = blockedBy(animations)
        pw.println("settingValue=${repository.setting.value}")
        pw.println("selectedStyle=${selector.settingStyle()}")
        pw.println("latchedStyle=${selector.latchedStyle() ?: "none"}")
        pw.println("configEnabled=${config.isEnabled}")
        pw.println("hingeAngleEnabled=${config.isHingeAngleEnabled}")
        pw.println("hingeSensorPresent=${support.hingeSensorPresent}")
        pw.println("animationsEnabled=$animations")
        pw.println("blurAvailable=${runtimeState.blurAvailable}")
        pw.println("eligible=${blocked == "NONE"}")
        pw.println("blockedBy=$blocked")
        pw.println("phase=${runtimeState.phase}")
        pw.println("overlayAttached=${runtimeState.overlayAttached}")
        pw.println("starts=${selector.starts}")
        pw.println("completions=${selector.completions}")
        pw.println("cancellations=${runtimeState.cancellations}")
        pw.println("lastCancelReason=${runtimeState.lastCancelReason}")
    }

    companion object {
        const val TAG = "YrrpFoldAnimation"
        const val LATCH_TIMEOUT_MS = 10_000L
    }
}
