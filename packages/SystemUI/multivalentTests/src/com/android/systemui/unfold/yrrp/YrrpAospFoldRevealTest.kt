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
import com.android.systemui.unfold.UnfoldTransitionModule
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class YrrpAospFoldRevealTest : SysuiTestCase() {
    /**
     * Opening the fold-lock gate on xueying would otherwise start AOSP's fold light reveal in
     * Pixel. Pixel had no reveal before, so production keeps it off.
     */
    @Test
    fun production_keepsAospFoldRevealOff() {
        assertThat(UnfoldTransitionModule().provideYrrpAospFoldRevealEnabled()).isFalse()
    }
}
