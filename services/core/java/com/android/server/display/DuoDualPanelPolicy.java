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
 * YRRP Duo: whether a device-state change keeps the outgoing panel lit, out of the transition
 * blanking, while the incoming panel turns on. An unfold keeps the cover lit; a fold keeps the
 * inner panel lit until the cover is on. Pure, so it runs without a device.
 */
final class DuoDualPanelPolicy {
    private DuoDualPanelPolicy() {}

    static boolean keepsOutgoingLit(boolean fromInnerPrimary, boolean toInnerPrimary,
            boolean duoActive, boolean interactive) {
        return !fromInnerPrimary && toInnerPrimary && duoActive && interactive;
    }

    /**
     * A fold from an inner-primary to a cover-primary state, with the Duo fold animation active,
     * the device interactive, and the fold not putting the device to sleep. A fold that sleeps
     * keeps the stock blanking, so the sleep and the keyguard see the order they expect.
     */
    static boolean keepsOutgoingLitOnFold(boolean fromInnerPrimary, boolean toInnerPrimary,
            boolean duoActive, boolean interactive, boolean sleepsOnFold) {
        return fromInnerPrimary && !toInnerPrimary && duoActive && interactive && !sleepsOnFold;
    }
}
