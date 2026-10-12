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

import com.android.wm.shell.unfold.UnfoldAnimationStyleProvider

/** Fold animation chosen in YRRPs Settings (`lineage_fold_animation`). */
enum class FoldAnimationStyle(val rawValue: Int, val shellValue: Int) {
    STOCK(0, UnfoldAnimationStyleProvider.STYLE_STOCK),
    PIXEL(1, UnfoldAnimationStyleProvider.STYLE_PIXEL),
    DUO(2, UnfoldAnimationStyleProvider.STYLE_DUO);

    companion object {
        /** 1 is Pixel, 2 is Duo; every other value, unset included, is Stock. */
        fun fromRaw(raw: Int): FoldAnimationStyle =
            when (raw) {
                1 -> PIXEL
                2 -> DUO
                else -> STOCK
            }
    }
}
