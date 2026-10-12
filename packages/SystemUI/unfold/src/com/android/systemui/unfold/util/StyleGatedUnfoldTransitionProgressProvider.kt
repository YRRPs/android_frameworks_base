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

package com.android.systemui.unfold.util

import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Forwards a whole transition or none of it, as decided by [gate] when the transition starts. A
 * finish with no start forwards nothing and leaves the gate untouched. A start that arrives after
 * the gate released the token externally begins a fresh transition. All callbacks arrive on
 * the source's progress thread.
 */
class StyleGatedUnfoldTransitionProgressProvider(
    private val source: UnfoldTransitionProgressProvider,
    private val gate: UnfoldTransitionStyleGate,
) : UnfoldTransitionProgressProvider, TransitionProgressListener {
    private val listeners = CopyOnWriteArrayList<TransitionProgressListener>()
    private var running = false
    private var passing = false

    init {
        source.addCallback(this)
    }

    override fun onTransitionStarted() {
        // A start while running is a duplicate, unless the gate released this token behind our
        // back (lost finish): then the previous run is dead and this start begins a new one.
        if (running && gate.isActive(this)) return
        running = true
        passing = gate.onGatedTransitionStarted(this)
        if (passing) listeners.forEach { it.onTransitionStarted() }
    }

    override fun onTransitionProgress(progress: Float) {
        if (passing) listeners.forEach { it.onTransitionProgress(progress) }
    }

    override fun onTransitionFinishing() {
        if (passing) listeners.forEach { it.onTransitionFinishing() }
    }

    override fun onTransitionFinished() {
        if (!running) return
        running = false
        if (passing) listeners.forEach { it.onTransitionFinished() }
        passing = false
        gate.onGatedTransitionFinished(this)
    }

    override fun addCallback(listener: TransitionProgressListener) {
        listeners += listener
    }

    override fun removeCallback(listener: TransitionProgressListener) {
        listeners -= listener
    }

    override fun destroy() {
        source.removeCallback(this)
        source.destroy()
    }
}
