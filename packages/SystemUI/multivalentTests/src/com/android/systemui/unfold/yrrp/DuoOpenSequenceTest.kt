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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DuoOpenSequenceTest : SysuiTestCase() {
    private val seq = DuoOpenSequence()

    @Test
    fun idleUntilTheHingeLeavesZero() {
        assertThat(seq.onCoverAngle(0f)).isEqualTo(DuoOpenSequence.Action.NONE)
        assertThat(seq.state).isEqualTo(DuoOpenSequence.State.IDLE)
    }

    @Test
    fun firstAngleAboveZero_capturesAndAttachesOnTheCover() {
        assertThat(seq.onCoverAngle(2f)).isEqualTo(DuoOpenSequence.Action.CAPTURE_AND_ATTACH)
        assertThat(seq.state).isEqualTo(DuoOpenSequence.State.COVER_ON_DEFAULT)
        assertThat(seq.onCoverAngle(5f)).isEqualTo(DuoOpenSequence.Action.UPDATE_COVER)
    }

    @Test
    fun coverDisplayAppearing_movesTheFrame() {
        seq.onCoverAngle(2f)
        assertThat(seq.onCoverDisplayAdded(4))
            .isEqualTo(DuoOpenSequence.Action.MOVE_TO_COVER_DISPLAY)
        assertThat(seq.state).isEqualTo(DuoOpenSequence.State.COVER_ON_OWN_DISPLAY)
        assertThat(seq.coverDisplayId).isEqualTo(4)
    }

    @Test
    fun coverDisplayGone_endsTheCoverPart() {
        seq.onCoverAngle(2f)
        seq.onCoverDisplayAdded(4)
        assertThat(seq.onCoverDisplayRemoved(4)).isEqualTo(DuoOpenSequence.Action.RELEASE_COVER)
        assertThat(seq.state).isEqualTo(DuoOpenSequence.State.IDLE)
    }

    @Test
    fun hingeBackToZeroBeforeTheSwitch_releasesTheCover() {
        seq.onCoverAngle(3f)
        assertThat(seq.onCoverAngle(0f)).isEqualTo(DuoOpenSequence.Action.RELEASE_COVER)
        assertThat(seq.state).isEqualTo(DuoOpenSequence.State.IDLE)
    }

    @Test
    fun unrelatedDisplayIsIgnored() {
        seq.onCoverAngle(2f)
        seq.onCoverDisplayAdded(4)
        assertThat(seq.onCoverDisplayRemoved(7)).isEqualTo(DuoOpenSequence.Action.NONE)
    }

    @Test
    fun cancelAlwaysReleases() {
        seq.onCoverAngle(2f)
        assertThat(seq.cancel()).isEqualTo(DuoOpenSequence.Action.RELEASE_COVER)
        assertThat(seq.cancel()).isEqualTo(DuoOpenSequence.Action.NONE)
    }
}
