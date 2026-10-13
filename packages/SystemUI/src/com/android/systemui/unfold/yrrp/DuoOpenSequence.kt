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
 * The cover side of one Duo open. It starts [State.DISARMED] and arms only on a closed reading,
 * because the fold sensor can declare the device closed at 4-24 degrees on a slow close: the
 * cover then turns on while the hinge is still closing, and those readings must not look like
 * an open. Once armed, the first reading above closed captures and blurs the live cover while
 * it is still the default display; the frozen picture then moves to the cover's own logical
 * display until system_server turns that display off. Pure state; DuoCoverOpening performs the
 * actions.
 */
class DuoOpenSequence(
    /** Shared with the caller so the hinge maths is built once. */
    val progress: CoverHingeProgress = CoverHingeProgress(DuoBlurCurve.OPEN_ANGLE_DEGREES),
) {
    enum class State { DISARMED, IDLE, COVER_ON_DEFAULT, COVER_ON_OWN_DISPLAY }

    enum class Action {
        NONE,
        CAPTURE_AND_ATTACH,
        UPDATE_COVER,
        MOVE_TO_COVER_DISPLAY,
        RELEASE_COVER,
    }

    var state = State.DISARMED
        private set

    var coverDisplayId = NO_DISPLAY
        private set

    /** True from the capture until the cover part is released. */
    val isActive: Boolean
        get() = state == State.COVER_ON_DEFAULT || state == State.COVER_ON_OWN_DISPLAY

    fun onCoverAngle(angleDegrees: Float): Action {
        val open = progress.hasLeftClosed(angleDegrees)
        return when (state) {
            State.DISARMED -> {
                if (!open) state = State.IDLE
                Action.NONE
            }
            State.IDLE ->
                if (open) {
                    state = State.COVER_ON_DEFAULT
                    Action.CAPTURE_AND_ATTACH
                } else {
                    Action.NONE
                }
            // This reading is closed, so the sequence stays armed for the next open.
            State.COVER_ON_DEFAULT -> if (open) Action.UPDATE_COVER else release(State.IDLE)
            State.COVER_ON_OWN_DISPLAY -> Action.UPDATE_COVER
        }
    }

    /** Requires a closed reading before the next open; an open already running is kept. */
    fun disarm() {
        if (state == State.IDLE) state = State.DISARMED
    }

    fun onCoverDisplayAdded(displayId: Int): Action {
        if (state != State.COVER_ON_DEFAULT) return Action.NONE
        state = State.COVER_ON_OWN_DISPLAY
        coverDisplayId = displayId
        return Action.MOVE_TO_COVER_DISPLAY
    }

    fun onCoverDisplayRemoved(displayId: Int): Action =
        if (state == State.COVER_ON_OWN_DISPLAY && displayId == coverDisplayId) {
            release()
        } else {
            Action.NONE
        }

    fun cancel(): Action = if (isActive) release() else Action.NONE

    private fun release(next: State = State.DISARMED): Action {
        state = next
        coverDisplayId = NO_DISPLAY
        return Action.RELEASE_COVER
    }

    private companion object {
        const val NO_DISPLAY = -1
    }
}
