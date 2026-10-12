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
import com.android.systemui.shared.settings.data.repository.FakeSecureSettingsRepository
import com.android.systemui.shared.settings.data.repository.SecureSettingsRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class FoldAnimationSettingsRepositoryTest : SysuiTestCase() {
    private val secureSettingsRepository = FakeSecureSettingsRepository()

    @Test
    fun missingSetting_isZero() = runTest {
        val underTest = createRepository()
        runCurrent()
        assertThat(underTest.setting.value).isEqualTo(0)
    }

    @Test
    fun storedValue_isPublishedRaw() = runTest {
        secureSettingsRepository.setInt(FoldAnimationSettingsRepository.SETTING_KEY, 2)
        val underTest = createRepository()
        runCurrent()
        assertThat(underTest.setting.value).isEqualTo(2)
    }

    @Test
    fun unknownValue_isPublishedRawAndNormalizedByStyle() = runTest {
        secureSettingsRepository.setInt(FoldAnimationSettingsRepository.SETTING_KEY, 9)
        val underTest = createRepository()
        runCurrent()
        assertThat(underTest.setting.value).isEqualTo(9)
        assertThat(FoldAnimationStyle.fromRaw(underTest.setting.value))
            .isEqualTo(FoldAnimationStyle.STOCK)
    }

    @Test
    fun readFailure_isZero() = runTest {
        val underTest = createRepository(FailingSecureSettingsRepository(failures = Int.MAX_VALUE))
        runCurrent()
        assertThat(underTest.setting.value).isEqualTo(0)
    }

    private fun TestScope.createRepository(
        repo: SecureSettingsRepository = secureSettingsRepository
    ): FoldAnimationSettingsRepository {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return FoldAnimationSettingsRepository(repo, backgroundScope, dispatcher)
    }

    /** A secure-settings source whose first [failures] reads fail, then delegate. */
    private class FailingSecureSettingsRepository(
        private val failures: Int,
        private val delegate: SecureSettingsRepository = FakeSecureSettingsRepository(),
    ) : SecureSettingsRepository by delegate {
        var reads = 0
            private set

        override fun intSetting(name: String, defaultValue: Int): Flow<Int> = flow {
            reads++
            if (reads <= failures) throw IllegalStateException("unreadable")
            emitAll(delegate.intSetting(name, defaultValue))
        }
    }
}
