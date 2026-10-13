/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.server.display;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class DuoDualPanelSessionTest {
    private final DuoDualPanelSession mSession = new DuoDualPanelSession();

    @Test
    public void startsIdle() {
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.IDLE);
        assertThat(mSession.keepsDisplayEnabled(4)).isFalse();
    }

    @Test
    public void start_marksTheCoverDisplayLit() {
        assertThat(mSession.start(4)).isTrue();
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.LIT);
        assertThat(mSession.keepsDisplayEnabled(4)).isTrue();
        assertThat(mSession.keepsDisplayEnabled(0)).isFalse();
    }

    @Test
    public void start_whileActive_isIgnored() {
        mSession.start(4);
        mSession.onTimeout();
        assertThat(mSession.start(7)).isFalse();
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.POWERING_OFF);
        assertThat(mSession.getCoverDisplayId()).isEqualTo(4);
        assertThat(mSession.getEndReason()).isEqualTo("TIMEOUT");
    }

    @Test
    public void reachingOpened_beginsPowerOff() {
        mSession.start(4);
        assertThat(mSession.onDeviceStateCommitted(/* innerPrimary= */ true, /* opened= */ true))
                .isEqualTo(DuoDualPanelSession.Step.POWER_OFF);
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.POWERING_OFF);
        assertThat(mSession.getEndReason()).isEqualTo("OPENED");
        assertThat(mSession.keepsDisplayEnabled(4)).isTrue();
    }

    @Test
    public void halfOpened_keepsLit() {
        mSession.start(4);
        assertThat(mSession.onDeviceStateCommitted(true, false))
                .isEqualTo(DuoDualPanelSession.Step.NONE);
    }

    @Test
    public void timeout_beginsPowerOff() {
        mSession.start(4);
        assertThat(mSession.onTimeout()).isEqualTo(DuoDualPanelSession.Step.POWER_OFF);
        assertThat(mSession.getEndReason()).isEqualTo("TIMEOUT");
    }

    @Test
    public void foldingBack_endsWithoutPowerOff() {
        mSession.start(4);
        assertThat(mSession.onDeviceStateCommitted(/* innerPrimary= */ false, false))
                .isEqualTo(DuoDualPanelSession.Step.END_NOW);
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.IDLE);
        assertThat(mSession.getEndReason()).isEqualTo("CLOSED");
    }

    @Test
    public void foldingBack_whilePoweringOff_endsNow() {
        mSession.start(4);
        mSession.onTimeout();
        assertThat(mSession.onDeviceStateCommitted(false, false))
                .isEqualTo(DuoDualPanelSession.Step.END_NOW);
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.IDLE);
        assertThat(mSession.keepsDisplayEnabled(4)).isFalse();
    }

    @Test
    public void panelOff_whilePoweringOff_disables() {
        mSession.start(4);
        mSession.onTimeout();
        assertThat(mSession.onPanelState(4, /* off= */ false))
                .isEqualTo(DuoDualPanelSession.Step.NONE);
        assertThat(mSession.onPanelState(4, /* off= */ true))
                .isEqualTo(DuoDualPanelSession.Step.DISABLE);
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.IDLE);
        assertThat(mSession.keepsDisplayEnabled(4)).isFalse();
    }

    @Test
    public void coverDisplayId_staysReadableAfterDisable() {
        mSession.start(4);
        mSession.onTimeout();
        mSession.onPanelState(4, true);
        assertThat(mSession.getCoverDisplayId()).isEqualTo(4);
    }

    @Test
    public void panelOff_forAnotherDisplay_isIgnored() {
        mSession.start(4);
        mSession.onTimeout();
        assertThat(mSession.onPanelState(7, true)).isEqualTo(DuoDualPanelSession.Step.NONE);
        assertThat(mSession.getState()).isEqualTo(DuoDualPanelSession.State.POWERING_OFF);
    }

    @Test
    public void secondTimeout_whilePoweringOff_isIgnored() {
        mSession.start(4);
        mSession.onTimeout();
        assertThat(mSession.onTimeout()).isEqualTo(DuoDualPanelSession.Step.NONE);
    }

    @Test
    public void cancel_fromLit_powersOff() {
        mSession.start(4);
        assertThat(mSession.cancel()).isEqualTo(DuoDualPanelSession.Step.POWER_OFF);
        assertThat(mSession.getEndReason()).isEqualTo("CANCEL");
    }
}
