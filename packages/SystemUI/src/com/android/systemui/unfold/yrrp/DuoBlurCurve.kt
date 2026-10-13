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

/**
 * Duo blur geometry over hinge progress p (0 closed, 1 open). The cover blur grows in from the
 * right edge as the hinge opens; the inner blur clears from the right and is gone at
 * [BLUR_CLEAR_PROGRESS]. Pure math: no Android types, so it runs on the JVM.
 */
object DuoBlurCurve {
    const val MAX_BLUR_PX = 220
    const val BLUR_CLEAR_PROGRESS = 0.95f
    const val EDGE_WIDTH_FRACTION = 0.6f
    const val TOUCH_UNBLOCK_PROGRESS = 0.8f
    const val OPEN_ANGLE_DEGREES = 165f

    /** Blurred share of the cover's width, anchored at the right edge: 1 - (1 - p)^2. */
    fun coverCoverage(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p)
    }

    /** Blurred share of the inner screen's width, anchored at the left edge: 1 - t^2. */
    fun innerRemaining(progress: Float): Float {
        val t = (progress / BLUR_CLEAR_PROGRESS).coerceIn(0f, 1f)
        return 1f - t * t
    }
}
