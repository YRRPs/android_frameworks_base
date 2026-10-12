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
 * Dagger name of the Boolean that lets AOSP's `FoldLightRevealOverlayAnimation` run. YRRP provides
 * false: opening the fold-lock gate for "Continue using apps on fold" would otherwise start that
 * reveal in Pixel, which had none on xueying before.
 */
const val YRRP_AOSP_FOLD_REVEAL = "yrrp_aosp_fold_reveal"
