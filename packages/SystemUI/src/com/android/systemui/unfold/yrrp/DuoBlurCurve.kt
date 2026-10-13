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

import kotlin.math.roundToInt

/**
 * Duo blur geometry over hinge progress p (0 closed, 1 open). The cover blur grows in from the
 * right edge as the hinge opens; the inner blur clears from the right and is gone at
 * [BLUR_CLEAR_PROGRESS]. Pure math: no Android types, so it runs on the JVM.
 */
object DuoBlurCurve {
    const val MAX_BLUR_PX = 220
    const val BLUR_CLEAR_PROGRESS = 0.95f
    const val EDGE_WIDTH_FRACTION = 0.6f
    const val EDGE_STRIPS = 16

    /** The inner overlay blocks touch below this progress. */
    const val TOUCH_UNBLOCK_PROGRESS = 0.8f

    /** Hinge angle treated as fully open (AOSP's 165 degrees). */
    const val OPEN_ANGLE_DEGREES = 165f

    /** Regions narrower than this are dropped: they are float noise, not visible blur. */
    private const val MIN_REGION_PX = 0.5f

    /** Which display edge the blurred share is attached to. */
    enum class Anchor { LEFT, RIGHT }

    /** Blurred share of the cover's width, anchored at the right edge: 1 - (1 - p)^2. */
    fun coverCoverage(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p)
    }

    /**
     * Blurred share of the inner screen's width, anchored at the left edge: 1 - t^2, where
     * t = p / [BLUR_CLEAR_PROGRESS] clamped to 0..1.
     */
    fun innerRemaining(progress: Float): Float {
        val t = (progress / BLUR_CLEAR_PROGRESS).coerceIn(0f, 1f)
        return 1f - t * t
    }

    /**
     * X of the blur front: where the blurred share meets the sharp share. Rounded to whole
     * pixels for placement and logging; [blurStrips] uses the unrounded front.
     */
    fun frontPx(widthPx: Int, blurredShare: Float, anchor: Anchor): Int {
        val blurred = (widthPx * blurredShare.coerceIn(0f, 1f)).roundToInt()
        return if (anchor == Anchor.RIGHT) widthPx - blurred else blurred
    }

    /**
     * Blur regions for one surface, as SurfaceControl.Transaction#setBlurRegions rows
     * `[radius, alpha, left, top, right, bottom, 4 corner radii]`, ordered from the front inward.
     * The soft edge starts at the front and ramps radius and alpha, from 1/[edgeStrips] of
     * [maxBlurPx] up to the full value, over [edgeWidthFraction] of the width in [edgeStrips]
     * adjacent strips; the ramp is cut short when the blurred share is narrower than the edge.
     * The rest of the blurred share is one full-strength region. Regions under [MIN_REGION_PX]
     * wide are dropped.
     *
     * [edgeStrips] and [edgeWidthFraction] are tuning hooks (tests now, Duo tuning settings
     * later), so this keeps seven parameters and they stay explicit.
     */
    fun blurStrips(
        widthPx: Int,
        heightPx: Int,
        blurredWidthPx: Float,
        maxBlurPx: Int,
        anchor: Anchor,
        edgeStrips: Int = EDGE_STRIPS,
        edgeWidthFraction: Float = EDGE_WIDTH_FRACTION,
    ): List<FloatArray> {
        require(edgeStrips > 0) { "edgeStrips must be positive: $edgeStrips" }
        require(edgeWidthFraction > 0f && edgeWidthFraction <= 1f) {
            "edgeWidthFraction must be in (0, 1]: $edgeWidthFraction"
        }
        if (widthPx <= 0 || !(blurredWidthPx > 0f) || maxBlurPx <= 0) return emptyList()
        val blurred = blurredWidthPx.coerceAtMost(widthPx.toFloat())
        val front = if (anchor == Anchor.RIGHT) widthPx - blurred else blurred
        val stripWidth = widthPx * edgeWidthFraction / edgeStrips
        val height = heightPx.toFloat()

        val rows = mutableListOf<FloatArray>()

        // Distances [from, to] are measured from the front into the blurred share.
        fun add(radius: Float, alpha: Float, from: Float, to: Float) {
            val left = if (anchor == Anchor.RIGHT) front + from else front - to
            val right = if (anchor == Anchor.RIGHT) front + to else front - from
            rows += row(radius, alpha, left, right, height)
        }

        var edgeEnd = 0f
        for (i in 0 until edgeStrips) {
            val from = i * stripWidth
            if (blurred - from < MIN_REGION_PX) break
            val to = minOf((i + 1) * stripWidth, blurred)
            val f = (i + 1f) / edgeStrips
            add(maxBlurPx * f, f, from, to)
            edgeEnd = to
        }
        if (blurred - edgeEnd >= MIN_REGION_PX) {
            add(maxBlurPx.toFloat(), 1f, edgeEnd, blurred)
        }
        return rows
    }

    private fun row(
        radius: Float,
        alpha: Float,
        left: Float,
        right: Float,
        heightPx: Float,
    ): FloatArray = floatArrayOf(radius, alpha, left, 0f, right, heightPx, 0f, 0f, 0f, 0f)
}
