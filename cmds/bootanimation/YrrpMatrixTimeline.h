/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

namespace android::yrrp {

enum class MatrixPhase { Intro, Loop, Outro, Done };

struct MatrixFrame {
    float shaderTime;  // seconds since the renderer started
    float shaderExit;  // outro start in shader time, or kNoExit
    MatrixPhase phase;
    bool done;
};

// Maps real elapsed seconds to shader inputs. Shader time is wall time, so a
// stalled frame jumps forward instead of slowing down.
class MatrixTimeline {
public:
    static constexpr double kIntroSeconds = 3.0;
    static constexpr double kOutroSeconds = 1.2;
    static constexpr float kNoExit = 1.0e6f;

    MatrixTimeline(bool fullIntro, bool shuttingDown);

    // Records the first exit request. Ignored while shutting down, where
    // power-off ends the animation. With full intro, an exit inside the intro
    // is deferred to the intro end.
    void onExitObserved(double seconds);
    MatrixFrame frame(double seconds) const;

    bool exitObserved() const { return mExitObserved; }
    double exitObservedAt() const { return mExitObservedAt; }
    double exitTime() const { return mExitTime; }
    bool exitDeferred() const { return mExitObserved && mExitTime > mExitObservedAt; }

private:
    bool mFullIntro;
    bool mShuttingDown;
    bool mExitObserved = false;
    double mExitObservedAt = 0.0;
    double mExitTime = 0.0;
};

const char* phaseName(MatrixPhase phase);

class FrameStats {
public:
    void add(double frameMs);
    int count() const { return mCount; }
    double averageMs() const { return mCount == 0 ? 0.0 : mTotalMs / mCount; }
    double maxMs() const { return mMaxMs; }

private:
    int mCount = 0;
    double mTotalMs = 0.0;
    double mMaxMs = 0.0;
};

}  // namespace android::yrrp
