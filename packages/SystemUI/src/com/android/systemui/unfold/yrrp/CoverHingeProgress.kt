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
    fun shouldListen(folded: Boolean, screenOn: Boolean, duo: Boolean): Boolean =
        folded && screenOn && duo

    fun progressFor(angleDegrees: Float): Float =
        (angleDegrees / openAngleDegrees).coerceIn(0f, 1f)

    // 1 degree because android.sensor.hinge_angle reports integers.
    fun hasLeftClosed(angleDegrees: Float): Boolean = angleDegrees >= LEFT_CLOSED_DEGREES

    private companion object {
        const val LEFT_CLOSED_DEGREES = 1f
    }
}
