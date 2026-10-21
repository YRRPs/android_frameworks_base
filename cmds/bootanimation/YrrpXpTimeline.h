/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cstdint>

namespace android::yrrp {

// When the XP renderer draws, sleeps and how it ends. Time is monotonic
// nanoseconds since the renderer started, so a stall jumps to the current step.
class XpTimeline {
public:
    static constexpr int64_t kStepNs = 80000000;  // ReactOS RB_SQUARE_CELLS delay
    static constexpr int64_t kPollNs = 16666667;  // exit checks stay at 60 Hz

    explicit XpTimeline(bool shuttingDown);

    // Bar step 0-17.
    int stepAt(int64_t elapsedNs) const;
    // Boot: true once per 80 ms tick. Shutdown: true on the first call only.
    bool needsDraw(int64_t elapsedNs);
    // Elapsed time to sleep until.
    int64_t nextWakeNs(int64_t elapsedNs) const;
    // Boot cuts to black and completes; shutdown stops at once.
    const char* exitOutcome() const;

private:
    int64_t tickAt(int64_t elapsedNs) const;

    bool mShuttingDown;
    int64_t mLastDrawnTick = -1;
};

}  // namespace android::yrrp
