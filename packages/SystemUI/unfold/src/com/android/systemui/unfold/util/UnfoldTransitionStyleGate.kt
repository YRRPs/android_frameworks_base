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

package com.android.systemui.unfold.util

/**
 * Decides per transition whether unfold progress reaches any listener. SystemUI binds the YRRP
 * fold animation selector; when unbound, every transition passes (AOSP behaviour).
 */
interface UnfoldTransitionStyleGate {
    /** Called on the progress thread when [token]'s transition starts. True lets it through. */
    fun onGatedTransitionStarted(token: Any): Boolean

    /** Called on the progress thread when [token]'s transition finishes. */
    fun onGatedTransitionFinished(token: Any)
}
