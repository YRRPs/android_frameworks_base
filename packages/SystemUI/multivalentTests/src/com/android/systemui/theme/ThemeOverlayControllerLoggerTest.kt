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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogBufferFactory
import com.android.systemui.log.LogcatEchoTracker
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class ThemeOverlayControllerLoggerTest : SysuiTestCase() {
    private lateinit var buffer: LogBuffer
    private lateinit var logger: ThemeOverlayControllerLogger

    @Before
    fun setUp() {
        buffer =
            LogBufferFactory(DumpManager(), mock(LogcatEchoTracker::class.java))
                .create("ThemeOverlayControllerLog", 100)
        logger = ThemeOverlayControllerLogger(buffer)
    }

    @Test
    fun terminalDecisions_includeStableStageAndReason() {
        logger.logSourceSelected("custom_theme")
        logger.logRejected("unsupported_version")
        logger.logGenerationResult(success = false, failureClass = "IllegalArgumentException")
        logger.logApplyResult(ThemeOverlayApplier.ApplyResult.COMMIT_FAILED)
        logger.logRetainedCurrent("invalid_contract")
        logger.logFallback("wallpaper")

        val output = dumpBuffer()
        assertThat(output).contains("source=custom_theme")
        assertThat(output).contains("rejected reason=unsupported_version")
        assertThat(output).contains("generation=failed class=IllegalArgumentException")
        assertThat(output).contains("apply=COMMIT_FAILED")
        assertThat(output).contains("retained=invalid_contract")
        assertThat(output).contains("fallback=wallpaper")
    }

    @Test
    fun terminalDecisions_doNotContainThemePayload() {
        logger.logAccepted()
        logger.logRejected("invalid_seeds")

        val output = dumpBuffer()
        assertThat(output).doesNotContain("11111111-1111-4111-8111-111111111111")
        assertThat(output).doesNotContain("B69CFF")
        assertThat(output).doesNotContain("Aurora")
    }

    private fun dumpBuffer(): String {
        val writer = StringWriter()
        buffer.dump(PrintWriter(writer), tailLength = 0)
        return writer.toString()
    }
}
