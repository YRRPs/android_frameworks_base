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
 * The cover side of one Duo open: idle, blurring over the live cover while it is still the
 * default display, then showing the frozen picture on the cover's own logical display until
 * system_server turns that display off. Pure state; the overlay animation performs the actions.
 */
class DuoOpenSequence(openAngleDegrees: Float = DuoBlurCurve.OPEN_ANGLE_DEGREES) {
    enum class State { IDLE, COVER_ON_DEFAULT, COVER_ON_OWN_DISPLAY }

    enum class Action {
        NONE,
        CAPTURE_AND_ATTACH,
        UPDATE_COVER,
        MOVE_TO_COVER_DISPLAY,
        RELEASE_COVER,
    }

    var state = State.IDLE
        private set

    var coverDisplayId = NO_DISPLAY
        private set

    private val progress = CoverHingeProgress(openAngleDegrees)

    fun onCoverAngle(angleDegrees: Float): Action =
        when (state) {
            State.IDLE ->
                if (progress.hasLeftClosed(angleDegrees)) {
                    state = State.COVER_ON_DEFAULT
                    Action.CAPTURE_AND_ATTACH
                } else {
                    Action.NONE
                }
            State.COVER_ON_DEFAULT ->
                if (progress.hasLeftClosed(angleDegrees)) Action.UPDATE_COVER else release()
            State.COVER_ON_OWN_DISPLAY -> Action.UPDATE_COVER
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

    fun cancel(): Action = if (state == State.IDLE) Action.NONE else release()

    private fun release(): Action {
        state = State.IDLE
        coverDisplayId = NO_DISPLAY
        return Action.RELEASE_COVER
    }

    private companion object {
        const val NO_DISPLAY = -1
    }
}
