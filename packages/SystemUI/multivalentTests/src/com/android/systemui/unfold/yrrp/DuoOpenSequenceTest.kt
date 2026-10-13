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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.yrrp.DuoOpenSequence.Action
import com.android.systemui.unfold.yrrp.DuoOpenSequence.State
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DuoOpenSequenceTest : SysuiTestCase() {
    private val seq = DuoOpenSequence()

    @Test
    fun startsDisarmed_andAClosedReadingArms() {
        assertThat(seq.state).isEqualTo(State.DISARMED)
        assertThat(seq.onCoverAngle(0f)).isEqualTo(Action.NONE)
        assertThat(seq.state).isEqualTo(State.IDLE)
    }

    @Test
    fun closingReadingsAfterAFold_neverCapture() {
        // The fold sensor reports CLOSED at 4-24 degrees on a slow close.
        assertThat(seq.onCoverAngle(15f)).isEqualTo(Action.NONE)
        assertThat(seq.onCoverAngle(10f)).isEqualTo(Action.NONE)
        assertThat(seq.onCoverAngle(5f)).isEqualTo(Action.NONE)
        assertThat(seq.state).isEqualTo(State.DISARMED)
    }

    @Test
    fun closedThenOpening_capturesAndAttachesOnTheCover() {
        seq.onCoverAngle(0f)
        assertThat(seq.onCoverAngle(2f)).isEqualTo(Action.CAPTURE_AND_ATTACH)
        assertThat(seq.state).isEqualTo(State.COVER_ON_DEFAULT)
        assertThat(seq.onCoverAngle(5f)).isEqualTo(Action.UPDATE_COVER)
    }

    @Test
    fun idleUntilTheHingeLeavesZero() {
        seq.onCoverAngle(0f)
        assertThat(seq.onCoverAngle(0f)).isEqualTo(Action.NONE)
        assertThat(seq.state).isEqualTo(State.IDLE)
    }

    @Test
    fun disarm_inIdle_needsAnotherClosedReading() {
        seq.onCoverAngle(0f)
        seq.disarm()
        assertThat(seq.state).isEqualTo(State.DISARMED)
        assertThat(seq.onCoverAngle(2f)).isEqualTo(Action.NONE)
    }

    @Test
    fun disarm_whileActive_keepsTheOpenRunning() {
        armedOpen()
        seq.disarm()
        assertThat(seq.state).isEqualTo(State.COVER_ON_DEFAULT)
    }

    @Test
    fun afterARelease_twoDegreesWaitsForClosed() {
        armedOpen()
        assertThat(seq.cancel()).isEqualTo(Action.RELEASE_COVER)
        assertThat(seq.state).isEqualTo(State.DISARMED)

        assertThat(seq.onCoverAngle(2f)).isEqualTo(Action.NONE)
        assertThat(seq.onCoverAngle(0f)).isEqualTo(Action.NONE)
        assertThat(seq.onCoverAngle(2f)).isEqualTo(Action.CAPTURE_AND_ATTACH)
    }

    @Test
    fun coverDisplayAppearing_movesTheFrame() {
        armedOpen()
        assertThat(seq.onCoverDisplayAdded(4)).isEqualTo(Action.MOVE_TO_COVER_DISPLAY)
        assertThat(seq.state).isEqualTo(State.COVER_ON_OWN_DISPLAY)
        assertThat(seq.coverDisplayId).isEqualTo(4)
    }

    @Test
    fun coverDisplayBeforeAnOpen_isIgnored() {
        assertThat(seq.onCoverDisplayAdded(4)).isEqualTo(Action.NONE)
        seq.onCoverAngle(0f)
        assertThat(seq.onCoverDisplayAdded(4)).isEqualTo(Action.NONE)
    }

    @Test
    fun coverDisplayGone_endsTheCoverPart() {
        armedOpen()
        seq.onCoverDisplayAdded(4)
        assertThat(seq.onCoverDisplayRemoved(4)).isEqualTo(Action.RELEASE_COVER)
        assertThat(seq.state).isEqualTo(State.DISARMED)
    }

    @Test
    fun hingeBackToZeroBeforeTheSwitch_releasesAndStaysArmed() {
        armedOpen()
        assertThat(seq.onCoverAngle(0f)).isEqualTo(Action.RELEASE_COVER)
        // That reading was closed: the next opening must not wait for another 0, because the
        // hinge sensor reports only changes.
        assertThat(seq.state).isEqualTo(State.IDLE)
        assertThat(seq.onCoverAngle(1f)).isEqualTo(Action.CAPTURE_AND_ATTACH)
    }

    @Test
    fun unrelatedDisplayIsIgnored() {
        armedOpen()
        seq.onCoverDisplayAdded(4)
        assertThat(seq.onCoverDisplayRemoved(7)).isEqualTo(Action.NONE)
    }

    @Test
    fun cancelAlwaysReleases() {
        armedOpen()
        assertThat(seq.cancel()).isEqualTo(Action.RELEASE_COVER)
        assertThat(seq.cancel()).isEqualTo(Action.NONE)
        seq.onCoverAngle(0f)
        assertThat(seq.cancel()).isEqualTo(Action.NONE)
    }

    @Test
    fun isActiveOnlyBetweenCaptureAndRelease() {
        assertThat(seq.isActive).isFalse()
        seq.onCoverAngle(0f)
        assertThat(seq.isActive).isFalse()
        seq.onCoverAngle(2f)
        assertThat(seq.isActive).isTrue()
        seq.cancel()
        assertThat(seq.isActive).isFalse()
    }

    @Test
    fun usesTheGivenHingeProgress() {
        val shared = CoverHingeProgress(openAngleDegrees = 90f)
        val withShared = DuoOpenSequence(shared)
        withShared.onCoverAngle(0f)
        assertThat(withShared.onCoverAngle(1f)).isEqualTo(Action.CAPTURE_AND_ATTACH)
        assertThat(withShared.progress).isSameInstanceAs(shared)
    }

    private fun armedOpen() {
        seq.onCoverAngle(0f)
        seq.onCoverAngle(2f)
    }
}
