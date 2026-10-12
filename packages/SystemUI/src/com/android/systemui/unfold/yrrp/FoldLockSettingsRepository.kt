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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.SettingsProxyExt.observerFlow
import com.android.systemui.util.settings.SystemSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Reads the selected user's fold animation style and fold-lock value together, so a user switch
 * never pairs one user's style with another user's fold-lock value. Unlike
 * [com.android.systemui.util.settings.repository.UserAwareSettingsRepository], it reads a string
 * and spans two namespaces in one emission.
 */
@SysUISingleton
class FoldLockSettingsRepository
@Inject
constructor(
    private val systemSettings: SystemSettings,
    private val secureSettings: SecureSettings,
    private val userRepository: UserRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : FoldLockSettings {

    override val state: Flow<FoldLockState> =
        userRepository.selectedUserInfo
            .map { it.id }
            .distinctUntilChanged()
            .flatMapLatest { userId ->
                merge(
                        systemSettings.observerFlow(userId, FOLD_LOCK_KEY),
                        secureSettings.observerFlow(userId, STYLE_KEY),
                    )
                    .onStart { emit(Unit) }
                    .map { read(userId) }
            }
            .distinctUntilChanged()
            .flowOn(backgroundDispatcher)

    override fun forceAlways(userId: Int): Boolean =
        systemSettings.putStringForUser(FOLD_LOCK_KEY, DuoFoldLockEnforcer.ALWAYS, userId)

    private fun read(userId: Int) =
        FoldLockState(
            userId = userId,
            styleRaw = secureSettings.getIntForUser(STYLE_KEY, STOCK_VALUE, userId),
            value = systemSettings.getStringForUser(FOLD_LOCK_KEY, userId),
        )

    companion object {
        /** `Settings.System.FOLD_LOCK_BEHAVIOR`. */
        const val FOLD_LOCK_KEY = "fold_lock_behavior_setting"
        const val STYLE_KEY = FoldAnimationSettingsRepository.SETTING_KEY
        private const val STOCK_VALUE = 0
    }
}
