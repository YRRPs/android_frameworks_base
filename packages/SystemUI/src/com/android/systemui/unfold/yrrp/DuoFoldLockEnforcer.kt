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

import com.android.app.tracing.coroutines.launchTraced as launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** The fold animation style and fold-lock value of one user, read together. */
data class FoldLockState(val userId: Int, val styleRaw: Int, val value: String?)

/** Observes and writes the selected user's fold-lock setting. */
interface FoldLockSettings {
    /** Emits whenever the selected user, their fold animation, or their fold-lock value changes. */
    val state: Flow<FoldLockState>

    /** Writes Always for [userId]; returns false when the write fails. */
    fun forceAlways(userId: Int): Boolean
}

/**
 * Keeps "Continue using apps on fold" at Always while the Duo fold animation is on, so folding an
 * unlocked phone never shows the lock screen. Settings stores and restores the user's own value
 * around Duo; this covers every other writer. Logs rewrites only, never steady state.
 */
class DuoFoldLockEnforcer(
    private val settings: FoldLockSettings,
    private val log: (String) -> Unit,
) {
    /** The selected user's fold-lock value as last read; null when unset or not yet read. */
    @Volatile
    var foldLockValue: String? = null
        private set

    @Volatile private var duoOn = false
    @Volatile private var rewrites = 0

    /** True when Duo is on and SystemUI has rewritten the value at least once since boot. */
    val foldLockForced: Boolean
        get() = duoOn && rewrites > 0

    fun start(scope: CoroutineScope) {
        scope.launch { settings.state.collect(::onState) }
    }

    private fun onState(state: FoldLockState) {
        foldLockValue = state.value
        duoOn = FoldAnimationStyle.fromRaw(state.styleRaw) == FoldAnimationStyle.DUO
        if (!duoOn || state.value == ALWAYS) return
        if (settings.forceAlways(state.userId)) {
            rewrites++
            log("foldLock forced from=${state.value} user=${state.userId}")
        } else {
            log("foldLock force failed from=${state.value} user=${state.userId}")
        }
    }

    companion object {
        /** Settings' "Always" choice, `FoldSettingProvider.SETTING_VALUE_STAY_AWAKE_ON_FOLD`. */
        const val ALWAYS = "stay_awake_on_fold_key"
    }
}
