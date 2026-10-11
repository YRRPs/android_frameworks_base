/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "YrrpMatrixTimeline.h"

#include <algorithm>

namespace android::yrrp {

MatrixTimeline::MatrixTimeline(bool fullIntro, bool shuttingDown)
      : mFullIntro(fullIntro), mShuttingDown(shuttingDown) {}

void MatrixTimeline::onExitObserved(double seconds) {
    if (mShuttingDown || mExitObserved) {
        return;
    }
    mExitObserved = true;
    mExitObservedAt = seconds;
    mExitTime = mFullIntro ? std::max(seconds, kIntroSeconds) : seconds;
}

MatrixFrame MatrixTimeline::frame(double seconds) const {
    const float shaderTime = static_cast<float>(seconds);
    const MatrixPhase running = seconds < kIntroSeconds ? MatrixPhase::Intro : MatrixPhase::Loop;
    if (!mExitObserved) {
        return {shaderTime, kNoExit, running, false};
    }
    const float shaderExit = static_cast<float>(mExitTime);
    if (seconds >= mExitTime + kOutroSeconds) {
        return {shaderTime, shaderExit, MatrixPhase::Done, true};
    }
    if (seconds >= mExitTime) {
        return {shaderTime, shaderExit, MatrixPhase::Outro, false};
    }
    return {shaderTime, shaderExit, running, false};
}

const char* phaseName(MatrixPhase phase) {
    switch (phase) {
        case MatrixPhase::Intro:
            return "INTRO";
        case MatrixPhase::Loop:
            return "LOOP";
        case MatrixPhase::Outro:
            return "OUTRO";
        case MatrixPhase::Done:
            return "DONE";
    }
    return "DONE";
}

void FrameStats::add(double frameMs) {
    ++mCount;
    mTotalMs += frameMs;
    mMaxMs = std::max(mMaxMs, frameMs);
}

}  // namespace android::yrrp
