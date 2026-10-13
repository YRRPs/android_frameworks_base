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

import android.hardware.display.DisplayManagerGlobal
import android.os.Handler
import android.view.Display
import android.view.DisplayAdjustments
import android.view.DisplayInfo
import com.android.systemui.log.LogBuffer
import java.util.Optional
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** Test sizes for the two Duo panels and the cover's own logical display. */
object DuoTestPanels {
    const val COVER_W = 1344
    const val COVER_H = 1892
    const val INNER_W = 2688
    const val INNER_H = 1892
    const val INNER_ID = 1
    const val COVER_OWN_ID = 4
    val PANELS = DuoPanels(COVER_W, COVER_H, INNER_W, INNER_H)
}

/** A hinge source that hands angles to the test instead of a sensor. */
class FakeHingeSource(handler: Handler) : CoverHingeAngleSource(mock(), handler) {
    var startResult = true
    var starts = 0
    private var onAngle: ((Float) -> Unit)? = null

    override val isListening: Boolean
        get() = onAngle != null

    override fun start(onAngle: (Float) -> Unit): Boolean {
        starts++
        this.onAngle = if (startResult) onAngle else null
        return startResult
    }

    override fun stop() {
        onAngle = null
    }

    fun report(angle: Float) {
        onAngle?.invoke(angle)
    }
}

/** A cover frame that records calls and shows at once. */
class FakeCoverFrame(handler: Handler, logBuffer: LogBuffer) :
    DuoCoverFrame(mock(), Optional.empty(), handler, logBuffer) {
    val captures = mutableListOf<Int>()
    val shows = mutableListOf<Triple<Int, Int, Int>>()
    val regions = mutableListOf<List<FloatArray>>()
    var releases = 0

    override fun capture(fromDisplayId: Int): Boolean {
        captures += fromDisplayId
        return true
    }

    override fun show(
        targetDisplayId: Int,
        widthPx: Int,
        heightPx: Int,
        onShown: (Boolean) -> Unit,
    ) {
        shows += Triple(targetDisplayId, widthPx, heightPx)
        onShown(true)
    }

    override fun setBlurRegions(regions: List<FloatArray>): Boolean {
        this.regions += regions
        return true
    }

    override fun release() {
        releases++
    }
}

/** A real internal Display over a mocked [global], as FaceScanningProviderFactoryTest does. */
fun fakeInternalDisplay(global: DisplayManagerGlobal, id: Int, width: Int, height: Int): Display {
    val info =
        DisplayInfo().apply {
            type = Display.TYPE_INTERNAL
            logicalWidth = width
            logicalHeight = height
            rotation = 0
        }
    whenever(global.getDisplayInfo(eq(id))).thenReturn(info)
    return Display(global, id, info, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS)
}

/** The rows the cover opening sends for [angle]: coverage-scaled, anchored right. */
fun coverOpeningRows(angle: Float): List<List<Float>> {
    val coverage = DuoBlurCurve.coverCoverage(angle / DuoBlurCurve.OPEN_ANGLE_DEGREES)
    val maxBlurPx = Math.round(DuoBlurCurve.MAX_BLUR_PX * coverage)
    val w = DuoTestPanels.COVER_W
    return DuoBlurCurve.blurStrips(
            w,
            DuoTestPanels.COVER_H,
            w * coverage,
            maxBlurPx,
            DuoBlurCurve.Anchor.RIGHT,
        )
        .map { it.toList() }
}
