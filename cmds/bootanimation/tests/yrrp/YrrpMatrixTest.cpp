/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <gtest/gtest.h>

#include <map>

#include "YrrpMatrixConfig.h"
#include "YrrpMatrixStatus.h"
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

TEST(YrrpMatrixConfigTest, xpStyleComesFromTheProperty) {
    const MatrixConfig config = parseMatrixConfig("xp", "");
    EXPECT_EQ(MatrixStyle::Xp, config.style);
    EXPECT_TRUE(config.styleFromProp);
    for (const char* value : {"XP", " xp", "xp ", "windows"}) {
        EXPECT_EQ(MatrixStyle::Matrix, parseMatrixConfig(value, "").style) << value;
    }
}

TEST(YrrpMatrixConfigTest, xpRoutesToXpOnBootAndShutdown) {
    EXPECT_EQ(MatrixRoute::Xp, routeFor(MatrixStyle::Xp, false));
    EXPECT_EQ(MatrixRoute::Xp, routeFor(MatrixStyle::Xp, true));
}

TEST(YrrpMatrixConfigTest, xpNames) {
    EXPECT_STREQ("xp", styleName(MatrixStyle::Xp));
    EXPECT_STREQ("XP", routeName(MatrixRoute::Xp));
}

}  // namespace
}  // namespace android::yrrp

namespace android::yrrp {
namespace {

TEST(YrrpMatrixTimelineTest, beforeExitPlaysIntroThenLoop) {
    const MatrixTimeline timeline(/*fullIntro=*/true, /*shuttingDown=*/false);
    EXPECT_FALSE(timeline.exitDeferred());
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
    EXPECT_FALSE(timeline.exitDeferred());
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

namespace android::yrrp {
namespace {

std::map<std::string, std::string> asMap(const MatrixStatus& status) {
    std::map<std::string, std::string> map;
    for (const auto& [key, value] : statusProperties(status)) {
        map[key] = value;
    }
    return map;
}

TEST(YrrpMatrixStatusTest, defaultMatrixRunPublishesEveryGate) {
    MatrixStatus status;
    status.config = parseMatrixConfig("", "");
    status.route = MatrixRoute::Matrix;
    const auto map = asMap(status);
    EXPECT_EQ("style=matrix/default fullIntro=1/default shutdown=0",
              map.at("sys.yrrp.bootanim.config"));
    EXPECT_EQ("MATRIX", map.at("sys.yrrp.bootanim.route"));
    EXPECT_EQ("none", map.at("sys.yrrp.bootanim.blocked_by"));
    EXPECT_EQ(0u, map.count("sys.yrrp.bootanim.result"));
}

TEST(YrrpMatrixStatusTest, configValueMatchesThePublishedConfig) {
    MatrixStatus status;
    status.config = parseMatrixConfig("lineage", "0");
    status.shuttingDown = true;
    status.route = MatrixRoute::Exit;
    EXPECT_EQ(asMap(status).at("sys.yrrp.bootanim.config"), configValue(status));
}

TEST(YrrpMatrixStatusTest, initFailureNamesTheBlockingStage) {
    MatrixStatus status;
    status.config = parseMatrixConfig("matrix", "false");
    status.route = MatrixRoute::Stock;
    status.blockedBy = "COMPILE";
    const auto map = asMap(status);
    EXPECT_EQ("style=matrix/prop fullIntro=0/prop shutdown=0", map.at("sys.yrrp.bootanim.config"));
    EXPECT_EQ("STOCK", map.at("sys.yrrp.bootanim.route"));
    EXPECT_EQ("COMPILE", map.at("sys.yrrp.bootanim.blocked_by"));
}

TEST(YrrpMatrixStatusTest, finishedRunPublishesTheResult) {
    MatrixStatus status;
    status.route = MatrixRoute::Matrix;
    status.outcome = "COMPLETED";
    status.frames = 312;
    status.averageFrameMs = 16.94;
    status.maxFrameMs = 211.38;
    EXPECT_EQ("outcome=COMPLETED frames=312 avgMs=16.9 maxMs=211.4",
              asMap(status).at("sys.yrrp.bootanim.result"));
}

TEST(YrrpMatrixStatusTest, everyValueFitsAPropertyValue) {
    MatrixStatus status;
    status.config = parseMatrixConfig("lineage", "false");
    status.shuttingDown = true;
    status.route = MatrixRoute::Matrix;
    status.blockedBy = "ASSET_ATLAS";
    status.outcome = "SWAP_FAILED";
    status.frames = 999999999;
    status.averageFrameMs = 99999.9;
    status.maxFrameMs = 99999.9;
    for (const auto& [key, value] : statusProperties(status)) {
        EXPECT_LT(value.size(), 92u) << key;  // PROP_VALUE_MAX - 1
    }
}

TEST(YrrpMatrixStatusTest, resultIsOnlyPublishedForMatrixRuns) {
    for (const MatrixRoute route : {MatrixRoute::Stock, MatrixRoute::Exit}) {
        MatrixStatus status;
        status.route = route;
        status.outcome = "COMPLETED";
        EXPECT_EQ(0u, asMap(status).count("sys.yrrp.bootanim.result"));
    }
}

TEST(YrrpMatrixStatusTest, hugeFrameTimesStillFitAPropertyValue) {
    MatrixStatus status;
    status.route = MatrixRoute::Matrix;
    status.outcome = "SWAP_FAILED";
    status.frames = 999999999;
    status.averageFrameMs = 1e300;
    status.maxFrameMs = 1e300;
    for (const auto& [key, value] : statusProperties(status)) {
        EXPECT_LT(value.size(), 92u) << key;
    }
}

}  // namespace
}  // namespace android::yrrp
