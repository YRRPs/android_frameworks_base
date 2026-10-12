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
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.YrrpFoldAnimationLog
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * Feeds the per-user setting into [FoldAnimationStyleSelector], logs latch transitions, and dumps
 * every gate. Dump: `dumpsys activity service com.android.systemui/.SystemUIService
 * YrrpFoldAnimation YrrpFoldAnimationLog`.
 */
@SysUISingleton
class YrrpFoldAnimationStartable(
    private val repository: FoldAnimationSettingsRepository,
    private val selector: FoldAnimationStyleSelector,
    private val runtimeState: FoldAnimationRuntimeState,
    private val config: UnfoldTransitionConfig,
    private val dumpManager: DumpManager,
    private val logBuffer: LogBuffer,
    private val backgroundScope: CoroutineScope,
    private val animationsEnabled: () -> Boolean,
) : CoreStartable, Dumpable {

    @Inject
    constructor(
        repository: FoldAnimationSettingsRepository,
        selector: FoldAnimationStyleSelector,
        runtimeState: FoldAnimationRuntimeState,
        config: UnfoldTransitionConfig,
        dumpManager: DumpManager,
        @YrrpFoldAnimationLog logBuffer: LogBuffer,
        @Background backgroundScope: CoroutineScope,
        contentResolver: ContentResolver,
    ) : this(
        repository,
        selector,
        runtimeState,
        config,
        dumpManager,
        logBuffer,
        backgroundScope,
        { contentResolver.areAnimationsEnabled() },
    )

    override fun start() {
        dumpManager.registerNormalDumpable(TAG, this)
        selector.listener =
            object : FoldAnimationStyleSelector.Listener {
                override fun onLatched(style: FoldAnimationStyle) = log("latched style=$style")

                override fun onReleased(style: FoldAnimationStyle) = log("released style=$style")
            }
        backgroundScope.launch {
            repository.setting.collect { raw ->
                val style = FoldAnimationStyle.fromRaw(raw)
                selector.onSettingChanged(style)
                log("setting raw=$raw style=$style")
            }
        }
    }

    private fun log(message: String) {
        logBuffer.log(TAG, LogLevel.DEBUG, { str1 = message }, { str1!! })
    }

    /** The first false gate in evaluation order, or `NONE`. */
    private fun blockedBy(animations: Boolean): String =
        when {
            !config.isEnabled -> "CONFIG_DISABLED"
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
    }
}
