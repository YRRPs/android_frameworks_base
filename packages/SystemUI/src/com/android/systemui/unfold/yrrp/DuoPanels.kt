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

import android.hardware.display.DisplayManager
import android.view.Display
import android.view.DisplayInfo

/**
 * Natural sizes of the two Duo panels, chosen the way the overlay controllers choose their
 * displays: among internal displays, including disabled ones, the narrowest is the cover and the
 * widest is the inner screen. All zero when no internal display is known.
 */
data class DuoPanels(val coverW: Int, val coverH: Int, val innerW: Int, val innerH: Int) {
    companion object {
        fun read(displayManager: DisplayManager): DuoPanels {
            val internal =
                displayManager
                    .getDisplays(DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED)
                    .map { DisplayInfo().apply { it.getDisplayInfo(this) } }
                    .filter { it.type == Display.TYPE_INTERNAL }
            val cover = internal.minByOrNull { it.naturalWidth }
            val inner = internal.maxByOrNull { it.naturalWidth }
            return DuoPanels(
                cover?.naturalWidth ?: 0,
                cover?.naturalHeight ?: 0,
                inner?.naturalWidth ?: 0,
                inner?.naturalHeight ?: 0,
            )
        }
    }
}
