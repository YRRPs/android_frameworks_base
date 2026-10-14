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
 * YRRP Duo: one open or fold during which both panels stay lit.
 *
 * <p>An {@link Kind#OPEN} session holds the cover panel on its own logical display while the
 * inner panel is the default one.
 *
 * <p>A {@link Kind#FOLD} session starts when SystemUI's fold animation starts, while the inner
 * panel is still the default display: it lights the cover on its own logical display. When the
 * device reaches a cover-primary state the cover becomes the default display, and the session
 * is {@link #handOver handed over} to the inner panel on its new logical display. It ends when
 * SystemUI reports the animation finished, on a timeout, or on a cancel. A fold that reaches
 * the cover-primary state with no session started gets one already handed over.
 *
 * <p>An OPEN session is LIT until the device reaches OPENED, a timeout, or a cancel. Either kind
 * is then POWERING_OFF until the held panel reports off, and only then IDLE, so a logical display
 * is never disabled with its panel still on. Folding back ends an OPEN session at once, from LIT
 * or POWERING_OFF alike, with no power-off and no disable: the cover is the target of the new
 * layout anyway. Unfolding again ends a handed-over FOLD session the same way. Pure;
 * LogicalDisplayMapper performs each step.
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

    enum Kind { OPEN, FOLD }

    enum Step { NONE, POWER_OFF, DISABLE, END_NOW, HAND_OVER }

    static final long TIMEOUT_MS = 1500;
    /** FOLD, before the hand-over: a fold that never closes turns the cover back off. */
    static final long FOLD_TIMEOUT_MS = 3000;
    static final long POWER_OFF_OVERDUE_MS = 1000;
    static final int NO_DISPLAY = -1;

    private State mState = State.IDLE;
    private Kind mKind = Kind.OPEN;
    private boolean mHandedOver;
    private int mCoverDisplayId = NO_DISPLAY;
    private String mEndReason = "NONE";

    State getState() {
        return mState;
    }

    Kind getKind() {
        return mKind;
    }

    /** FOLD: whether the session holds the inner panel rather than the cover. */
    boolean isHandedOver() {
        return mHandedOver;
    }

    /** Whether a lit FOLD session still holds the cover. */
    boolean isFoldHoldingCover() {
        return mState == State.LIT && mKind == Kind.FOLD && !mHandedOver;
    }

    /** The logical display of the held panel: the cover, or the inner panel once handed over. */
    int getCoverDisplayId() {
        return mCoverDisplayId;
    }

    String getEndReason() {
        return mEndReason;
    }

    /** Begins an OPEN session; returns false, changing nothing, if one is already active. */
    boolean start(int coverDisplayId) {
        return start(coverDisplayId, Kind.OPEN);
    }

    /** Begins a session; returns false, changing nothing, if one is already active. */
    boolean start(int displayId, Kind kind) {
        if (mState != State.IDLE) {
            return false;
        }
        mState = State.LIT;
        mKind = kind;
        mHandedOver = false;
        mCoverDisplayId = displayId;
        mEndReason = "NONE";
        return true;
    }

    /**
     * FOLD: the cover became the default display; the session now holds the inner panel on
     * {@code innerDisplayId}. Returns false, changing nothing, unless a lit FOLD session still
     * holds the cover.
     */
    boolean handOver(int innerDisplayId) {
        if (mState != State.LIT || mKind != Kind.FOLD || mHandedOver) {
            return false;
        }
        mHandedOver = true;
        mCoverDisplayId = innerDisplayId;
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
        if (mKind == Kind.FOLD) {
            if (!mHandedOver) {
                // Still holding the cover: reaching a cover-primary state hands it over.
                return !innerPrimary && mState == State.LIT ? Step.HAND_OVER : Step.NONE;
            }
            if (innerPrimary) {
                // Unfolded again: the held inner panel is the layout's default display anyway.
                mState = State.IDLE;
                mEndReason = "REOPENED";
                return Step.END_NOW;
            }
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

    /** FOLD: SystemUI's fold animation finished; the held panel can go off. */
    Step onAnimationFinished() {
        if (mKind != Kind.FOLD) {
            return Step.NONE;
        }
        return beginPowerOff("FINISHED");
    }

    /** Ends an active session at once, with no power-off and no disable. */
    Step endNow(String reason) {
        if (mState == State.IDLE) {
            return Step.NONE;
        }
        mState = State.IDLE;
        mEndReason = reason;
        return Step.END_NOW;
    }

    /**
     * FOLD, handed over: the device unfolds again before the animation finished. The held inner
     * panel is the default display of the layout it unfolds to, so the session just ends.
     */
    Step onReopened() {
        if (mState != State.LIT || mKind != Kind.FOLD || !mHandedOver) {
            return Step.NONE;
        }
        mState = State.IDLE;
        mEndReason = "REOPENED";
        return Step.END_NOW;
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
