/*
 * Copyright 2026 The Android Open Source Project
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

package com.android.server.display;

/**
 * YRRP Duo: whether a device-state change keeps the outgoing (cover) panel lit while the inner
 * panel turns on. Only a fold-to-unfold change qualifies, with the Duo fold animation active and
 * the device interactive. Pure, so it runs without a device.
 */
final class DuoDualPanelPolicy {
    private DuoDualPanelPolicy() {}

    static boolean keepsOutgoingLit(boolean fromInnerPrimary, boolean toInnerPrimary,
            boolean duoActive, boolean interactive) {
        return !fromInnerPrimary && toInnerPrimary && duoActive && interactive;
    }
}
