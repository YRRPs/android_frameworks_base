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

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FoldLockSettingsRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val userRepository = kosmos.fakeUserRepository
    private val systemSettings = FakeSettings(kosmos.testDispatcher)
    private val secureSettings = FakeSettings(kosmos.testDispatcher)
    private val underTest =
        FoldLockSettingsRepository(
            systemSettings,
            secureSettings,
            userRepository,
            kosmos.testDispatcher,
        )

    @Before
    fun setup() {
        userRepository.setUserInfos(listOf(USER_1, USER_2))
        secureSettings.putIntForUser(STYLE_KEY, 2, USER_1.id)
        systemSettings.putStringForUser(FOLD_LOCK_KEY, NEVER, USER_1.id)
        secureSettings.putIntForUser(STYLE_KEY, 1, USER_2.id)
        systemSettings.putStringForUser(FOLD_LOCK_KEY, SWIPE, USER_2.id)
    }

    @Test
    fun state_readsStyleAndFoldLockOfTheSelectedUser() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)

            val state by collectLastValue(underTest.state)

            assertThat(state).isEqualTo(FoldLockState(USER_1.id, styleRaw = 2, value = NEVER))
        }

    @Test
    fun state_followsAFoldLockChange() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            val state by collectLastValue(underTest.state)
            runCurrent()

            systemSettings.putStringForUser(FOLD_LOCK_KEY, SWIPE, USER_1.id)
            runCurrent()

            assertThat(state?.value).isEqualTo(SWIPE)
        }

    @Test
    fun state_followsAStyleChange() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            val state by collectLastValue(underTest.state)
            runCurrent()

            secureSettings.putIntForUser(STYLE_KEY, 0, USER_1.id)
            runCurrent()

            assertThat(state?.styleRaw).isEqualTo(0)
        }

    @Test
    fun state_onUserSwitch_readsBothSettingsOfTheNewUser() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(USER_1)
            val state by collectLastValue(underTest.state)
            runCurrent()

            userRepository.setSelectedUserInfo(USER_2)
            runCurrent()

            assertThat(state).isEqualTo(FoldLockState(USER_2.id, styleRaw = 1, value = SWIPE))
        }

    @Test
    fun readStyleRaw_readsThatUsersStoredStyle() {
        assertThat(underTest.readStyleRaw(USER_1.id)).isEqualTo(2)
        assertThat(underTest.readStyleRaw(USER_2.id)).isEqualTo(1)
    }

    @Test
    fun forceAlways_writesOnlyThatUser() {
        assertThat(underTest.forceAlways(USER_2.id)).isTrue()

        assertThat(systemSettings.getStringForUser(FOLD_LOCK_KEY, USER_2.id))
            .isEqualTo(DuoFoldLockEnforcer.ALWAYS)
        assertThat(systemSettings.getStringForUser(FOLD_LOCK_KEY, USER_1.id)).isEqualTo(NEVER)
    }

    @Test
    fun keys_matchTheFrameworkAndSettingsContract() {
        assertThat(FoldLockSettingsRepository.FOLD_LOCK_KEY).isEqualTo(FOLD_LOCK_KEY)
        assertThat(FoldLockSettingsRepository.STYLE_KEY)
            .isEqualTo(FoldAnimationSettingsRepository.SETTING_KEY)
    }

    private companion object {
        const val FOLD_LOCK_KEY = "fold_lock_behavior_setting"
        const val STYLE_KEY = "lineage_fold_animation"
        const val NEVER = "sleep_on_fold_key"
        const val SWIPE = "selective_stay_awake_key"
        val USER_1 = UserInfo(/* id= */ 10, "user1", /* flags= */ 0)
        val USER_2 = UserInfo(/* id= */ 11, "user2", /* flags= */ 0)
    }
}
