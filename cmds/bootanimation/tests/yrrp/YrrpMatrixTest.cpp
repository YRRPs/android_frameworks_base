/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <gtest/gtest.h>

#include "YrrpMatrixConfig.h"
#include "YrrpMatrixTimeline.h"

namespace android::yrrp {
namespace {

TEST(YrrpMatrixConfigTest, unsetPropertiesUseDefaults) {
    const MatrixConfig config = parseMatrixConfig("", "");
    EXPECT_EQ(MatrixStyle::Matrix, config.style);
    EXPECT_FALSE(config.styleFromProp);
    EXPECT_TRUE(config.fullIntro);
    EXPECT_FALSE(config.fullIntroFromProp);
}

TEST(YrrpMatrixConfigTest, knownStylesComeFromTheProperty) {
    EXPECT_EQ(MatrixStyle::Lineage, parseMatrixConfig("lineage", "").style);
    EXPECT_TRUE(parseMatrixConfig("lineage", "").styleFromProp);
    EXPECT_EQ(MatrixStyle::Matrix, parseMatrixConfig("matrix", "").style);
    EXPECT_TRUE(parseMatrixConfig("matrix", "").styleFromProp);
}

TEST(YrrpMatrixConfigTest, unknownStyleFallsBackToMatrixDefault) {
    for (const char* value : {"LINEAGE", "garbage", " lineage", "lineage "}) {
        const MatrixConfig config = parseMatrixConfig(value, "");
        EXPECT_EQ(MatrixStyle::Matrix, config.style) << value;
        EXPECT_FALSE(config.styleFromProp) << value;
    }
}

TEST(YrrpMatrixConfigTest, fullIntroAcceptsTrueFalseOneZero) {
    EXPECT_FALSE(parseMatrixConfig("", "false").fullIntro);
    EXPECT_FALSE(parseMatrixConfig("", "0").fullIntro);
    EXPECT_TRUE(parseMatrixConfig("", "true").fullIntro);
    EXPECT_TRUE(parseMatrixConfig("", "1").fullIntro);
    EXPECT_TRUE(parseMatrixConfig("", "false").fullIntroFromProp);
    EXPECT_TRUE(parseMatrixConfig("", "1").fullIntroFromProp);
}

TEST(YrrpMatrixConfigTest, unknownFullIntroIsTrueDefault) {
    const MatrixConfig config = parseMatrixConfig("", "maybe");
    EXPECT_TRUE(config.fullIntro);
    EXPECT_FALSE(config.fullIntroFromProp);
}

TEST(YrrpMatrixConfigTest, routes) {
    EXPECT_EQ(MatrixRoute::Matrix, routeFor(MatrixStyle::Matrix, false));
    EXPECT_EQ(MatrixRoute::Matrix, routeFor(MatrixStyle::Matrix, true));
    EXPECT_EQ(MatrixRoute::Stock, routeFor(MatrixStyle::Lineage, false));
    EXPECT_EQ(MatrixRoute::Exit, routeFor(MatrixStyle::Lineage, true));
    EXPECT_EQ(MatrixRoute::Stock, routeAfterInitFailure(false));
    EXPECT_EQ(MatrixRoute::Exit, routeAfterInitFailure(true));
}

TEST(YrrpMatrixConfigTest, names) {
    EXPECT_STREQ("matrix", styleName(MatrixStyle::Matrix));
    EXPECT_STREQ("lineage", styleName(MatrixStyle::Lineage));
    EXPECT_STREQ("MATRIX", routeName(MatrixRoute::Matrix));
    EXPECT_STREQ("STOCK", routeName(MatrixRoute::Stock));
    EXPECT_STREQ("EXIT", routeName(MatrixRoute::Exit));
}

}  // namespace
}  // namespace android::yrrp

namespace android::yrrp {
namespace {

TEST(YrrpMatrixTimelineTest, beforeExitPlaysIntroThenLoop) {
    const MatrixTimeline timeline(/*fullIntro=*/true, /*shuttingDown=*/false);
    const MatrixFrame intro = timeline.frame(1.0);
    EXPECT_EQ(MatrixPhase::Intro, intro.phase);
    EXPECT_FLOAT_EQ(1.0f, intro.shaderTime);
    EXPECT_FLOAT_EQ(MatrixTimeline::kNoExit, intro.shaderExit);
    EXPECT_FALSE(intro.done);
    EXPECT_EQ(MatrixPhase::Loop, timeline.frame(5.0).phase);
}

TEST(YrrpMatrixTimelineTest, fullIntroDefersAnEarlyExitToTheIntroEnd) {
    MatrixTimeline timeline(true, false);
    timeline.onExitObserved(1.0);
    EXPECT_TRUE(timeline.exitObserved());
    EXPECT_DOUBLE_EQ(1.0, timeline.exitObservedAt());
    EXPECT_DOUBLE_EQ(3.0, timeline.exitTime());
    EXPECT_TRUE(timeline.exitDeferred());
    EXPECT_EQ(MatrixPhase::Intro, timeline.frame(2.0).phase);
    EXPECT_FLOAT_EQ(3.0f, timeline.frame(2.0).shaderExit);
    EXPECT_EQ(MatrixPhase::Outro, timeline.frame(3.5).phase);
    EXPECT_FALSE(timeline.frame(4.19).done);
    EXPECT_EQ(MatrixPhase::Done, timeline.frame(4.2).phase);
    EXPECT_TRUE(timeline.frame(4.2).done);
}

TEST(YrrpMatrixTimelineTest, withoutFullIntroAnEarlyExitStartsTheOutroAtOnce) {
    MatrixTimeline timeline(false, false);
    timeline.onExitObserved(1.0);
    EXPECT_DOUBLE_EQ(1.0, timeline.exitTime());
    EXPECT_FALSE(timeline.exitDeferred());
    EXPECT_EQ(MatrixPhase::Outro, timeline.frame(1.5).phase);
    EXPECT_TRUE(timeline.frame(2.2).done);
}

TEST(YrrpMatrixTimelineTest, aLateExitIsNotDeferredAndOnlyTheFirstCounts) {
    MatrixTimeline timeline(true, false);
    timeline.onExitObserved(5.0);
    timeline.onExitObserved(9.0);
    EXPECT_DOUBLE_EQ(5.0, timeline.exitTime());
    EXPECT_FALSE(timeline.exitDeferred());
    EXPECT_EQ(MatrixPhase::Loop, timeline.frame(4.9).phase);
    EXPECT_EQ(MatrixPhase::Outro, timeline.frame(5.0).phase);
}

TEST(YrrpMatrixTimelineTest, shutdownIgnoresExitAndNeverEnds) {
    MatrixTimeline timeline(true, true);
    timeline.onExitObserved(1.0);
    EXPECT_FALSE(timeline.exitObserved());
    const MatrixFrame frame = timeline.frame(100.0);
    EXPECT_EQ(MatrixPhase::Loop, frame.phase);
    EXPECT_FLOAT_EQ(MatrixTimeline::kNoExit, frame.shaderExit);
    EXPECT_FALSE(frame.done);
}

TEST(YrrpMatrixTimelineTest, phaseNames) {
    EXPECT_STREQ("INTRO", phaseName(MatrixPhase::Intro));
    EXPECT_STREQ("LOOP", phaseName(MatrixPhase::Loop));
    EXPECT_STREQ("OUTRO", phaseName(MatrixPhase::Outro));
    EXPECT_STREQ("DONE", phaseName(MatrixPhase::Done));
}

TEST(YrrpFrameStatsTest, emptyThenAverageAndMax) {
    FrameStats stats;
    EXPECT_EQ(0, stats.count());
    EXPECT_DOUBLE_EQ(0.0, stats.averageMs());
    EXPECT_DOUBLE_EQ(0.0, stats.maxMs());
    stats.add(10.0);
    stats.add(20.0);
    EXPECT_EQ(2, stats.count());
    EXPECT_DOUBLE_EQ(15.0, stats.averageMs());
    EXPECT_DOUBLE_EQ(20.0, stats.maxMs());
}

}  // namespace
}  // namespace android::yrrp
