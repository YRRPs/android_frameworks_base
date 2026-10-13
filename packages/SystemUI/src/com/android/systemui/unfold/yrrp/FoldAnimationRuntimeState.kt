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

import com.android.systemui.dagger.SysUISingleton
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

enum class FoldAnimationPhase {
    IDLE,
    UNFOLDING,
    FOLDING,
    COVER_REVEAL,
    /** The cover blurs with the hinge before the unfold transition takes over. */
    COVER_OPENING,
}

/** State the Duo overlay owns and the dump reads; written on the unfold bg thread. */
@SysUISingleton
class FoldAnimationRuntimeState @Inject constructor() {
    @Volatile var phase = FoldAnimationPhase.IDLE
    @Volatile var overlayAttached = false
    @Volatile var blurAvailable = true

    /** True while the frozen cover picture is shown on the cover's own logical display. */
    @Volatile var dualPanel = false

    /**
     * Blur fronts in px (where blurred meets sharp), recorded at transition start, end and
     * cancel, never per frame; -1 when none was recorded.
     */
    @Volatile var coverBlurFrontPx = -1
    @Volatile var innerBlurFrontPx = -1

    @Volatile
    var lastCancelReason = NONE
        private set

    private val cancelCount = AtomicInteger()

    val cancellations: Int
        get() = cancelCount.get()

    fun recordCancel(reason: String) {
        lastCancelReason = reason
        cancelCount.incrementAndGet()
    }

    companion object {
        const val NONE = "none"
    }
}
