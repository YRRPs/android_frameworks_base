/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "YrrpXpTimeline.h"

#include <algorithm>

#include "YrrpXpLayout.h"

namespace android::yrrp {

XpTimeline::XpTimeline(bool shuttingDown) : mShuttingDown(shuttingDown) {}

int64_t XpTimeline::tickAt(int64_t elapsedNs) const {
    return std::max<int64_t>(elapsedNs, 0) / kStepNs;
}

int XpTimeline::stepAt(int64_t elapsedNs) const {
    return static_cast<int>(tickAt(elapsedNs) % kXpBarSteps);
}

bool XpTimeline::needsDraw(int64_t elapsedNs) {
    const int64_t tick = mShuttingDown ? 0 : tickAt(elapsedNs);
    if (tick == mLastDrawnTick) {
        return false;
    }
    mLastDrawnTick = tick;
    return true;
}

int64_t XpTimeline::nextWakeNs(int64_t elapsedNs) const {
    const int64_t poll = elapsedNs + kPollNs;
    if (mShuttingDown) {
        return poll;
    }
    return std::min(poll, (tickAt(elapsedNs) + 1) * kStepNs);
}

const char* XpTimeline::exitOutcome() const {
    return mShuttingDown ? "EXIT_PENDING" : "COMPLETED";
}

}  // namespace android::yrrp
