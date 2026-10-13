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

/**
 * Maps blur region rows from a panel's natural coordinates to its logical (rotated) ones, the
 * space an overlay surface on that display uses. Mirrors RotationUtils.rotateBounds, which
 * DisplayCutout uses for the same mapping. Rotations are Surface.ROTATION_0..270 as 0..3; plain
 * Ints keep this on the JVM.
 */
object DuoRegionRotation {
    private const val LEFT = 2
    private const val TOP = 3
    private const val RIGHT = 4
    private const val BOTTOM = 5

    /** Returns new rows; radius, alpha and corner radii are kept. */
    fun toLogical(
        rows: List<FloatArray>,
        rotation: Int,
        naturalW: Int,
        naturalH: Int,
    ): List<FloatArray> {
        require(rotation in 0..3) { "rotation must be 0..3, was $rotation" }
        if (rotation == 0) return rows.map { it.copyOf() }
        val (w, h) = naturalW.toFloat() to naturalH.toFloat()
        return rows.map { row ->
            val (l, t, r, b) = listOf(row[LEFT], row[TOP], row[RIGHT], row[BOTTOM])
            val rect =
                when (rotation) {
                    1 -> floatArrayOf(t, w - r, b, w - l)
                    2 -> floatArrayOf(w - r, h - b, w - l, h - t)
                    else -> floatArrayOf(h - b, l, h - t, r)
                }
            row.copyOf().also { rect.copyInto(it, destinationOffset = LEFT) }
        }
    }
}
