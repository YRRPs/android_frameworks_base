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

package com.android.wm.shell.unfold;

/**
 * The fold animation style chosen by the user. SystemUI binds the implementation; when it is
 * absent, Shell behaves as {@link #STYLE_PIXEL}, the AOSP animation.
 */
public interface UnfoldAnimationStyleProvider {
    int STYLE_STOCK = 0;
    int STYLE_PIXEL = 1;
    int STYLE_DUO = 2;

    /** The style of the running transition, or the selected style when idle. */
    int getUnfoldAnimationStyle();
}
