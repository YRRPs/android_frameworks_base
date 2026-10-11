/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include <gtest/gtest.h>

#include "YrrpMatrixConfig.h"

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
