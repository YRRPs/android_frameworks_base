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

import androidx.annotation.GuardedBy
import com.android.systemui.unfold.util.UnfoldTransitionStyleGate
import com.android.wm.shell.unfold.UnfoldAnimationStyleProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Latches the user's fold animation style when the first gated progress provider starts a
 * transition and releases it when the last one finishes. Every unfold consumer, Shell included,
 * reads [currentStyle], so one transition never mixes styles. Lives in the global graph because
 * Shell and the unfold library are both resolved there.
 */
@Singleton
class FoldAnimationStyleSelector @Inject constructor() :
    UnfoldTransitionStyleGate, UnfoldAnimationStyleProvider {

    interface Listener {
        fun onLatched(style: FoldAnimationStyle)

        fun onReleased(style: FoldAnimationStyle)
    }

    @Volatile var listener: Listener? = null

    private val lock = Any()
    @GuardedBy("lock") private var setting = FoldAnimationStyle.STOCK
    @GuardedBy("lock") private var latched: FoldAnimationStyle? = null
    @GuardedBy("lock") private val activeTokens = mutableSetOf<Any>()
    @GuardedBy("lock") private var startCount = 0
    @GuardedBy("lock") private var completionCount = 0

    val starts: Int
        get() = synchronized(lock) { startCount }

    val completions: Int
        get() = synchronized(lock) { completionCount }

    fun onSettingChanged(style: FoldAnimationStyle) = synchronized(lock) { setting = style }

    fun settingStyle(): FoldAnimationStyle = synchronized(lock) { setting }

    fun latchedStyle(): FoldAnimationStyle? = synchronized(lock) { latched }

    fun currentStyle(): FoldAnimationStyle = synchronized(lock) { latched ?: setting }

    override fun getUnfoldAnimationStyle(): Int = currentStyle().shellValue

    override fun onGatedTransitionStarted(token: Any): Boolean {
        val (style, newlyLatched) =
            synchronized(lock) {
                val fresh = activeTokens.isEmpty()
                if (fresh) {
                    latched = setting
                    startCount++
                }
                activeTokens += token
                latched!! to fresh
            }
        if (newlyLatched) listener?.onLatched(style)
        return style != FoldAnimationStyle.STOCK
    }

    override fun onGatedTransitionFinished(token: Any) {
        val released =
            synchronized(lock) {
                if (!activeTokens.remove(token) || activeTokens.isNotEmpty()) return
                completionCount++
                latched.also { latched = null }
            }
        released?.let { listener?.onReleased(it) }
    }
}
