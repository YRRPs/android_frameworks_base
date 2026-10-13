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
 * When the cover blur listens to the hinge, and what progress an angle means. The unfold
 * library listens only while unfolded, so the cover needs its own source between 0 degrees and
 * the screen switch.
 */
class CoverHingeProgress(private val openAngleDegrees: Float) {
    init {
        require(openAngleDegrees > 0f) { "openAngleDegrees must be > 0, was $openAngleDegrees" }
    }

    /**
     * Only folded, awake, Duo: there is no point listening while asleep (screen off or AOD) or
     * while another fold style runs.
     */
    fun shouldListen(folded: Boolean, awake: Boolean, duo: Boolean): Boolean =
        folded && awake && duo

    /** Maps a hinge angle linearly onto 0..1, reaching 1 at the open angle; NaN reads as 0. */
    fun progressFor(angleDegrees: Float): Float =
        if (angleDegrees.isNaN()) 0f else (angleDegrees / openAngleDegrees).coerceIn(0f, 1f)

    /** Whether the hinge has opened at least 1 degree from closed. */
    fun hasLeftClosed(angleDegrees: Float): Boolean = angleDegrees >= LEFT_CLOSED_DEGREES

    private companion object {
        // xueying's hinge HAL reports whole degrees, so 1 is the first reading above closed.
        const val LEFT_CLOSED_DEGREES = 1f
    }
}
