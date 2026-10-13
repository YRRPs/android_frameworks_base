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
 * YRRP Duo: one open during which the cover panel stays lit on its own logical display.
 * LIT until the device reaches OPENED, a timeout, or a cancel; then POWERING_OFF until the cover
 * panel reports off, and only then IDLE, so a logical display is never disabled with its panel
 * still on. Folding back ends at once, from LIT or POWERING_OFF alike, with no power-off and no
 * disable: the cover is the target of the new layout anyway. Pure; LogicalDisplayMapper performs
 * each step.
 *
 * <p>The cover display id stays readable through {@link #getCoverDisplayId()} after the session
 * ends, until the next {@link #start}, so the caller can act on a {@link Step#DISABLE}. A
 * {@link #start} while a session is active is ignored.
 *
 * <p>"The panel reports off" ({@link #onPanelState} with {@code off}) means the power controller
 * requested OFF and the display device info was updated to {@code STATE_OFF}. SurfaceFlinger's
 * {@code setDisplayPowerMode} may still be in flight at that point; the stock device-state
 * transition trusts the same signal before it disables a display.
 *
 * <p>{@link #retarget} follows the cover when a forced stock commit moves it to another logical
 * display while the session is active, so the caller keeps that display enabled instead.
 */
final class DuoDualPanelSession {
    enum State { IDLE, LIT, POWERING_OFF }

    enum Step { NONE, POWER_OFF, DISABLE, END_NOW }

    static final long TIMEOUT_MS = 1500;
    static final long POWER_OFF_OVERDUE_MS = 1000;
    static final int NO_DISPLAY = -1;

    private State mState = State.IDLE;
    private int mCoverDisplayId = NO_DISPLAY;
    private String mEndReason = "NONE";

    State getState() {
        return mState;
    }

    int getCoverDisplayId() {
        return mCoverDisplayId;
    }

    String getEndReason() {
        return mEndReason;
    }

    /** Begins a session; returns false, changing nothing, if one is already active. */
    boolean start(int coverDisplayId) {
        if (mState != State.IDLE) {
            return false;
        }
        mState = State.LIT;
        mCoverDisplayId = coverDisplayId;
        mEndReason = "NONE";
        return true;
    }

    /**
     * Moves an active session to the cover's new logical display; returns false, changing
     * nothing, when IDLE.
     */
    boolean retarget(int coverDisplayId) {
        if (mState == State.IDLE) {
            return false;
        }
        mCoverDisplayId = coverDisplayId;
        return true;
    }

    boolean keepsDisplayEnabled(int displayId) {
        return mState != State.IDLE && displayId == mCoverDisplayId;
    }

    Step onDeviceStateCommitted(boolean innerPrimary, boolean opened) {
        if (mState == State.IDLE) {
            return Step.NONE;
        }
        if (!innerPrimary) {
            mState = State.IDLE;
            mEndReason = "CLOSED";
            return Step.END_NOW;
        }
        return opened ? beginPowerOff("OPENED") : Step.NONE;
    }

    Step onTimeout() {
        return beginPowerOff("TIMEOUT");
    }

    Step cancel() {
        return beginPowerOff("CANCEL");
    }

    Step onPanelState(int displayId, boolean off) {
        if (mState != State.POWERING_OFF || displayId != mCoverDisplayId || !off) {
            return Step.NONE;
        }
        mState = State.IDLE;
        return Step.DISABLE;
    }

    private Step beginPowerOff(String reason) {
        if (mState != State.LIT) {
            return Step.NONE;
        }
        mState = State.POWERING_OFF;
        mEndReason = reason;
        return Step.POWER_OFF;
    }
}
