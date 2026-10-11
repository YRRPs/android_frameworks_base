/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "YrrpMatrixConfig.h"

namespace android::yrrp {

MatrixConfig parseMatrixConfig(const std::string& style, const std::string& fullIntro) {
    MatrixConfig config;
    if (style == "lineage") {
        config.style = MatrixStyle::Lineage;
        config.styleFromProp = true;
    } else if (style == "matrix") {
        config.styleFromProp = true;
    }
    if (fullIntro == "false" || fullIntro == "0") {
        config.fullIntro = false;
        config.fullIntroFromProp = true;
    } else if (fullIntro == "true" || fullIntro == "1") {
        config.fullIntroFromProp = true;
    }
    return config;
}

MatrixRoute routeFor(MatrixStyle style, bool shuttingDown) {
    if (style == MatrixStyle::Matrix) {
        return MatrixRoute::Matrix;
    }
    return routeAfterInitFailure(shuttingDown);
}

MatrixRoute routeAfterInitFailure(bool shuttingDown) {
    // No shutdown zip exists, so the stock shutdown path would only show the
    // AOSP logo; ending at once keeps the pre-feature shutdown look.
    return shuttingDown ? MatrixRoute::Exit : MatrixRoute::Stock;
}

const char* styleName(MatrixStyle style) {
    return style == MatrixStyle::Lineage ? "lineage" : "matrix";
}

const char* routeName(MatrixRoute route) {
    switch (route) {
        case MatrixRoute::Matrix:
            return "MATRIX";
        case MatrixRoute::Stock:
            return "STOCK";
        case MatrixRoute::Exit:
            return "EXIT";
    }
    return "EXIT";
}

}  // namespace android::yrrp
