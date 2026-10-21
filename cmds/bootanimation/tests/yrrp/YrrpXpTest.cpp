/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <gtest/gtest.h>

#include <utility>

#include "YrrpXpLayout.h"
#include "YrrpXpTimeline.h"

namespace android::yrrp {
namespace {

void expectRect(const XpRect& expected, const XpRect& actual) {
    EXPECT_EQ(expected.x, actual.x);
    EXPECT_EQ(expected.y, actual.y);
    EXPECT_EQ(expected.w, actual.w);
    EXPECT_EQ(expected.h, actual.h);
}

TEST(YrrpXpBarTest, everyStepMatchesTheReactOsCellModel) {
    const XpBarSpan expected[kXpBarSteps] = {
            {259, 16, 6}, {259, 8, 14}, {259, 0, 22}, {267, 0, 22}, {275, 0, 22}, {283, 0, 22},
            {291, 0, 22}, {299, 0, 22}, {307, 0, 22}, {315, 0, 22}, {323, 0, 22}, {331, 0, 22},
            {339, 0, 22}, {347, 0, 22}, {355, 0, 22}, {363, 0, 14}, {371, 0, 6},  {259, 0, 0},
    };
    for (int step = 0; step < kXpBarSteps; ++step) {
        const XpBarSpan span = xpBarSpan(step);
        EXPECT_EQ(expected[step].frameX, span.frameX) << step;
        EXPECT_EQ(expected[step].spriteFrom, span.spriteFrom) << step;
        EXPECT_EQ(expected[step].width, span.width) << step;
    }
}

TEST(YrrpXpBarTest, capturesSitOnTheModel) {
    // Home Edition capture: x 259-272. Professional capture: x 291-312.
    EXPECT_EQ(259, xpBarSpan(1).frameX);
    EXPECT_EQ(272, xpBarSpan(1).frameX + xpBarSpan(1).width - 1);
    EXPECT_EQ(291, xpBarSpan(6).frameX);
    EXPECT_EQ(312, xpBarSpan(6).frameX + xpBarSpan(6).width - 1);
}

TEST(YrrpXpBarTest, barNeverLeavesTheFrameInterior) {
    for (int step = 0; step < kXpBarSteps; ++step) {
        const XpBarSpan span = xpBarSpan(step);
        if (span.width == 0) {
            continue;
        }
        EXPECT_GE(span.frameX, 257) << step;
        EXPECT_LE(span.frameX + span.width - 1, 377) << step;
    }
}

TEST(YrrpXpBarTest, outOfRangeStepsDrawNothing) {
    EXPECT_EQ(0, xpBarSpan(-1).width);
    EXPECT_EQ(0, xpBarSpan(18).width);
}

TEST(YrrpXpLayoutTest, salami) {
    const XpLayout layout = xpLayout(1440, 3216);
    EXPECT_DOUBLE_EQ(2.25, layout.scale);
    expectRect({200, 116, 262, 254}, layout.centre.src);
    expectRect({425, 1226, 590, 571}, layout.centre.dst);
    expectRect({18, 428, 145, 38}, layout.copyright.src);
    expectRect({41, 2986, 326, 85}, layout.copyright.dst);
    expectRect({540, 440, 84, 24}, layout.wordmark.src);
    expectRect({1211, 3008, 189, 54}, layout.wordmark.dst);
}

TEST(YrrpXpLayoutTest, xueying) {
    const XpLayout layout = xpLayout(1080, 2412);
    EXPECT_DOUBLE_EQ(1.6875, layout.scale);
    expectRect({319, 919, 442, 429}, layout.centre.dst);
    expectRect({30, 2239, 245, 64}, layout.copyright.dst);
    expectRect({908, 2256, 142, 41}, layout.wordmark.dst);
}

TEST(YrrpXpLayoutTest, barBlitFollowsTheCentreRegion) {
    const XpLayout salami = xpLayout(1440, 3216);
    expectRect({16, 0, 6, 9}, xpBarBlit(salami, 0).src);
    expectRect({558, 1761, 14, 21}, xpBarBlit(salami, 0).dst);
    expectRect({0, 0, 22, 9}, xpBarBlit(salami, 6).src);
    expectRect({630, 1761, 50, 21}, xpBarBlit(salami, 6).dst);
    expectRect({810, 1761, 14, 21}, xpBarBlit(salami, 16).dst);
    EXPECT_EQ(0, xpBarBlit(salami, 17).dst.w);
    const XpLayout xueying = xpLayout(1080, 2412);
    expectRect({473, 1321, 37, 15}, xpBarBlit(xueying, 6).dst);
}

TEST(YrrpXpLayoutTest, everyRegionStaysOnThePanel) {
    for (const auto& [w, h] : {std::pair{1440, 3216}, std::pair{1080, 2412}, std::pair{720, 1600}}) {
        const XpLayout layout = xpLayout(w, h);
        for (const XpBlit& blit : {layout.centre, layout.copyright, layout.wordmark}) {
            EXPECT_GE(blit.dst.x, 0);
            EXPECT_GE(blit.dst.y, 0);
            EXPECT_LE(blit.dst.x + blit.dst.w, w);
            EXPECT_LE(blit.dst.y + blit.dst.h, h);
        }
    }
}

TEST(YrrpXpQuadTest, fullScreenBlitCoversClipSpace) {
    const XpQuad quad = xpQuad({{0, 0, 720, 1608}, {0, 0, 1440, 3216}}, 1440, 3216, 720, 1608);
    EXPECT_FLOAT_EQ(-1.0f, quad.dst[0]);
    EXPECT_FLOAT_EQ(-1.0f, quad.dst[1]);
    EXPECT_FLOAT_EQ(2.0f, quad.dst[2]);
    EXPECT_FLOAT_EQ(2.0f, quad.dst[3]);
    EXPECT_FLOAT_EQ(0.0f, quad.src[0]);
    EXPECT_FLOAT_EQ(0.0f, quad.src[1]);
    EXPECT_FLOAT_EQ(1.0f, quad.src[2]);
    EXPECT_FLOAT_EQ(1.0f, quad.src[3]);
}

TEST(YrrpXpQuadTest, topLeftPixelRectMapsToClipAndTexture) {
    // 100x50 at the panel's top-left, sampling the frame's top-left 64x48.
    const XpQuad quad = xpQuad({{0, 0, 64, 48}, {0, 0, 100, 50}}, 1000, 500, 640, 480);
    EXPECT_FLOAT_EQ(-1.0f, quad.dst[0]);
    EXPECT_FLOAT_EQ(0.8f, quad.dst[1]);  // bottom edge: 1 - 2 * 50 / 500
    EXPECT_FLOAT_EQ(0.2f, quad.dst[2]);
    EXPECT_FLOAT_EQ(0.2f, quad.dst[3]);
    EXPECT_FLOAT_EQ(0.1f, quad.src[2]);
    EXPECT_FLOAT_EQ(0.1f, quad.src[3]);
}

constexpr int64_t kMs = 1000000;

TEST(YrrpXpTimelineTest, stepIsEightyMillisecondTicksModuloEighteen) {
    const XpTimeline timeline(/*shuttingDown=*/false);
    EXPECT_EQ(0, timeline.stepAt(0));
    EXPECT_EQ(0, timeline.stepAt(79 * kMs));
    EXPECT_EQ(1, timeline.stepAt(80 * kMs));
    EXPECT_EQ(17, timeline.stepAt(1439 * kMs));
    EXPECT_EQ(0, timeline.stepAt(1440 * kMs));
    EXPECT_EQ(0, timeline.stepAt(-5 * kMs));
}

TEST(YrrpXpTimelineTest, bootDrawsOncePerTickAndAfterAStall) {
    XpTimeline timeline(false);
    EXPECT_TRUE(timeline.needsDraw(0));
    EXPECT_FALSE(timeline.needsDraw(16 * kMs));
    EXPECT_FALSE(timeline.needsDraw(79 * kMs));
    EXPECT_TRUE(timeline.needsDraw(80 * kMs));
    EXPECT_TRUE(timeline.needsDraw(900 * kMs));  // stall: jump straight to the current step
    EXPECT_FALSE(timeline.needsDraw(950 * kMs));
}

TEST(YrrpXpTimelineTest, shutdownDrawsOnlyOnce) {
    XpTimeline timeline(true);
    EXPECT_TRUE(timeline.needsDraw(0));
    EXPECT_FALSE(timeline.needsDraw(80 * kMs));
    EXPECT_FALSE(timeline.needsDraw(60000 * kMs));
}

TEST(YrrpXpTimelineTest, invalidateForcesARedrawOfTheCurrentFrame) {
    XpTimeline shutdown(true);
    EXPECT_TRUE(shutdown.needsDraw(0));
    shutdown.invalidate();  // a display surface was recreated
    EXPECT_TRUE(shutdown.needsDraw(5000 * kMs));
    EXPECT_FALSE(shutdown.needsDraw(6000 * kMs));
    XpTimeline boot(false);
    EXPECT_TRUE(boot.needsDraw(10 * kMs));
    boot.invalidate();
    EXPECT_TRUE(boot.needsDraw(20 * kMs));
    EXPECT_FALSE(boot.needsDraw(30 * kMs));
}

TEST(YrrpXpTimelineTest, bootWakesAtTheNextTickOrPollWhicheverIsFirst) {
    const XpTimeline timeline(false);
    EXPECT_EQ(XpTimeline::kPollNs, timeline.nextWakeNs(0));
    EXPECT_EQ(80 * kMs, timeline.nextWakeNs(70 * kMs));
    EXPECT_EQ(160 * kMs, timeline.nextWakeNs(150 * kMs));
}

TEST(YrrpXpTimelineTest, shutdownOnlyPolls) {
    const XpTimeline timeline(true);
    EXPECT_EQ(70 * kMs + XpTimeline::kPollNs, timeline.nextWakeNs(70 * kMs));
}

TEST(YrrpXpTimelineTest, exitOutcome) {
    EXPECT_STREQ("COMPLETED", XpTimeline(false).exitOutcome());
    EXPECT_STREQ("EXIT_PENDING", XpTimeline(true).exitOutcome());
}

}  // namespace
}  // namespace android::yrrp
