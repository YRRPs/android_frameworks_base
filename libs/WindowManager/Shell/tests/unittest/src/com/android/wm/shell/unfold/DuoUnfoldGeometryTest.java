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

package com.android.wm.shell.unfold;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.wm.shell.unfold.animation.DuoUnfoldGeometry;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DuoUnfoldGeometryTest {
    @Test
    public void scaleX_startsAtCoverWidthAndEndsAtFullWidth() {
        assertThat(DuoUnfoldGeometry.scaleX(0f)).isWithin(1e-4f).of(1116f / 2268f);
        assertThat(DuoUnfoldGeometry.scaleX(1f)).isWithin(1e-4f).of(1f);
    }

    @Test
    public void scaleX_isLinearInProgress() {
        float expected = (1116f / 2268f) + (1f - 1116f / 2268f) * 0.5f;
        assertThat(DuoUnfoldGeometry.scaleX(0.5f)).isWithin(1e-4f).of(expected);
    }

    @Test
    public void scaleX_clampsProgressOutsideZeroToOne() {
        assertThat(DuoUnfoldGeometry.scaleX(-0.2f)).isWithin(1e-4f).of(1116f / 2268f);
        assertThat(DuoUnfoldGeometry.scaleX(1.3f)).isWithin(1e-4f).of(1f);
    }
}
