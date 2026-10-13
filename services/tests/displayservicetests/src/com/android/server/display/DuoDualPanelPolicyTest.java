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
public class DuoDualPanelPolicyTest {
    private static boolean decide(boolean fromInnerPrimary, boolean toInnerPrimary,
            boolean duo, boolean interactive) {
        return DuoDualPanelPolicy.keepsOutgoingLit(
                fromInnerPrimary, toInnerPrimary, duo, interactive);
    }

    @Test
    public void openingWithDuoWhileInteractive_keepsTheCoverLit() {
        assertThat(decide(false, true, true, true)).isTrue();
    }

    @Test
    public void closing_doesNot() {
        assertThat(decide(true, false, true, true)).isFalse();
    }

    @Test
    public void halfOpenedToOpened_doesNot() {
        assertThat(decide(true, true, true, true)).isFalse();
    }

    @Test
    public void notDuo_doesNot() {
        assertThat(decide(false, true, false, true)).isFalse();
    }

    @Test
    public void asleep_doesNot() {
        assertThat(decide(false, true, true, false)).isFalse();
    }
}
