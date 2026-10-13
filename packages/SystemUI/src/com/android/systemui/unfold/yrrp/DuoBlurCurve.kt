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
    const val TOUCH_UNBLOCK_PROGRESS = 0.8f
    const val OPEN_ANGLE_DEGREES = 165f

    /** Blurred share of the cover's width, anchored at the right edge: 1 - (1 - p)^2. */
    fun coverCoverage(progress: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        return 1f - (1f - p) * (1f - p)
    }

    /** Blurred share of the inner screen's width, anchored at the left edge: 1 - t^2. */
    fun innerRemaining(progress: Float): Float {
        val t = (progress / BLUR_CLEAR_PROGRESS).coerceIn(0f, 1f)
        return 1f - t * t
    }

    const val EDGE_STRIPS = 16

    /** Which display edge the blurred share is attached to. */
    enum class Anchor { LEFT, RIGHT }

    /** X of the blur front: where the blurred share meets the sharp share. */
    fun frontPx(widthPx: Int, blurredShare: Float, anchor: Anchor): Int {
        val blurred = (widthPx * blurredShare.coerceIn(0f, 1f)).roundToInt()
        return if (anchor == Anchor.RIGHT) widthPx - blurred else blurred
    }

    /**
     * Blur regions for one surface, as SurfaceControl.Transaction#setBlurRegions rows. The soft
     * edge starts at the front and ramps radius and alpha over [EDGE_WIDTH_FRACTION] of the width
     * in [edgeStrips] adjacent strips; the rest of the blurred share is one full-strength region.
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
        val blurred = blurredWidthPx.coerceIn(0f, widthPx.toFloat())
        if (blurred <= 0f || maxBlurPx <= 0) return emptyList()
        val stripWidth = widthPx * edgeWidthFraction / edgeStrips
        val rows = mutableListOf<FloatArray>()
        var covered = 0f
        for (i in 0 until edgeStrips) {
            if (covered >= blurred) break
            val width = minOf(stripWidth, blurred - covered)
            val f = (i + 1f) / edgeStrips
            rows += row(maxBlurPx * f, f, covered, covered + width, blurred, widthPx, heightPx, anchor)
            covered += width
        }
        if (covered < blurred) {
            rows += row(maxBlurPx.toFloat(), 1f, covered, blurred, blurred, widthPx, heightPx, anchor)
        }
        return rows
    }

    /** One row; [from] and [to] are distances from the front into the blurred share. */
    private fun row(
        radius: Float,
        alpha: Float,
        from: Float,
        to: Float,
        blurred: Float,
        widthPx: Int,
        heightPx: Int,
        anchor: Anchor,
    ): FloatArray {
        val (left, right) =
            if (anchor == Anchor.RIGHT) {
                val front = widthPx - blurred
                front + from to front + to
            } else {
                blurred - to to blurred - from
            }
        return floatArrayOf(radius, alpha, left, 0f, right, heightPx.toFloat(), 0f, 0f, 0f, 0f)
    }
}
