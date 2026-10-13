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
import android.os.Looper
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

/**
 * A hinge source that hands angles to the test instead of a sensor; bg-only like the real one.
 * Like the sensor on activation, [start] posts [currentAngle] to the new callback.
 */
class FakeHingeSource(private val handler: Handler) : CoverHingeAngleSource(mock(), handler) {
    var startResult = true
    var currentAngle = 0f
    var starts = 0
    private var onAngle: ((Float) -> Unit)? = null

    override val isListening: Boolean
        get() = onAngle != null

    override fun start(onAngle: (Float) -> Unit): Boolean {
        checkOnLooper(handler)
        starts++
        this.onAngle = if (startResult) onAngle else null
        if (startResult) {
            handler.post { if (this.onAngle === onAngle) onAngle(currentAngle) }
        }
        return startResult
    }

    override fun stop() {
        checkOnLooper(handler)
        onAngle = null
    }

    fun report(angle: Float) {
        currentAngle = angle
        onAngle?.invoke(angle)
    }
}

/**
 * A cover frame with the real contract: capture, show and release each start a new generation;
 * [show] keeps its callback until [completeShow], which reports only for the current request.
 * Regions apply only once a show has succeeded. With [captureResult] false there is no picture,
 * and [show] reports false inline, as the real class does.
 */
class FakeCoverFrame(private val handler: Handler, logBuffer: LogBuffer) :
    DuoCoverFrame(mock(), Optional.empty(), handler, logBuffer) {
    val captures = mutableListOf<Int>()
    val captureRotations = mutableListOf<Int>()
    val shows = mutableListOf<Triple<Int, Int, Int>>()
    val regions = mutableListOf<List<FloatArray>>()
    var releases = 0
    var captureResult = true
    private var holdsPicture = false
    private var generation = 0
    private var pending: Pair<Int, (Boolean) -> Unit>? = null
    private var shownGeneration = -1

    override fun capture(fromDisplayId: Int, rotation: Int): Boolean {
        checkOnLooper(handler)
        generation++
        captures += fromDisplayId
        captureRotations += rotation
        // Like the real frame, a picture captured at any rotation is never shown.
        holdsPicture = captureResult && rotation == 0
        return captureResult
    }

    override fun show(
        targetDisplayId: Int,
        widthPx: Int,
        heightPx: Int,
        onShown: (Boolean) -> Unit,
    ) {
        checkOnLooper(handler)
        generation++
        shows += Triple(targetDisplayId, widthPx, heightPx)
        if (!holdsPicture) {
            onShown(false)
            return
        }
        pending = generation to onShown
    }

    /** Finishes the latest show; a superseded one never reports. */
    fun completeShow(ok: Boolean) {
        val (requested, onShown) = pending ?: return
        pending = null
        if (requested != generation) return
        if (ok) shownGeneration = requested
        onShown(ok)
    }

    override fun setBlurRegions(regions: List<FloatArray>): Boolean {
        checkOnLooper(handler)
        if (shownGeneration != generation) return false
        this.regions += regions
        return true
    }

    override fun release() {
        checkOnLooper(handler)
        generation++
        holdsPicture = false
        releases++
    }
}

private fun checkOnLooper(handler: Handler) =
    check(Looper.myLooper() == handler.looper) { "must run on the @UnfoldBg handler" }

/** A real Display over a mocked [global], as FaceScanningProviderFactoryTest does. */
fun fakeInternalDisplay(
    global: DisplayManagerGlobal,
    id: Int,
    width: Int,
    height: Int,
    displayType: Int = Display.TYPE_INTERNAL,
): Display {
    val info =
        DisplayInfo().apply {
            type = displayType
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
    val (w, h) = DuoTestPanels.COVER_W to DuoTestPanels.COVER_H
    return DuoBlurCurve.rowsFor(w, h, coverage, maxBlurPx, DuoBlurCurve.Anchor.RIGHT)
        .map { it.toList() }
}
