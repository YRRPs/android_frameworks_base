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
package com.android.systemui.theme

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.ThemeOverlayControllerLog
import javax.inject.Inject

private const val TAG = "ThemeOverlayController"

/** Records bounded theme decisions without user-created theme data. */
@SysUISingleton
class ThemeOverlayControllerLogger
@Inject
constructor(@ThemeOverlayControllerLog private val buffer: LogBuffer) {
    fun logSourceSelected(source: String) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = source }, { "source=$str1" })
    }

    fun logAccepted() {
        buffer.log(TAG, LogLevel.DEBUG, "custom contract accepted")
    }

    fun logRejected(reason: String) {
        buffer.log(TAG, LogLevel.WARNING, { str1 = reason }, { "rejected reason=$str1" })
    }

    fun logGenerationResult(success: Boolean, failureClass: String?) {
        buffer.log(
            TAG,
            if (success) LogLevel.DEBUG else LogLevel.WARNING,
            {
                bool1 = success
                str1 = failureClass ?: "none"
            },
            { if (bool1) "generation=success" else "generation=failed class=$str1" },
        )
    }

    fun logApplyResult(result: ThemeOverlayApplier.ApplyResult) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = result.name }, { "apply=$str1" })
    }

    fun logRetainedCurrent(reason: String) {
        buffer.log(TAG, LogLevel.WARNING, { str1 = reason }, { "retained=$str1" })
    }

    fun logFallback(source: String) {
        buffer.log(TAG, LogLevel.WARNING, { str1 = source }, { "fallback=$str1" })
    }
}
