/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "YrrpMatrixStatus.h"

#include <cstdio>

namespace android::yrrp {

std::string configValue(const MatrixStatus& status) {
    char buffer[92];
    snprintf(buffer, sizeof(buffer), "style=%s/%s fullIntro=%d/%s shutdown=%d",
             styleName(status.config.style), status.config.styleFromProp ? "prop" : "default",
             status.config.fullIntro ? 1 : 0, status.config.fullIntroFromProp ? "prop" : "default",
             status.shuttingDown ? 1 : 0);
    return buffer;
}

namespace {

std::string resultValue(const MatrixStatus& status) {
    char buffer[92];
    snprintf(buffer, sizeof(buffer), "outcome=%s frames=%d avgMs=%.1f maxMs=%.1f",
             status.outcome.c_str(), status.frames, status.averageFrameMs, status.maxFrameMs);
    return buffer;
}

}  // namespace

std::vector<std::pair<std::string, std::string>> statusProperties(const MatrixStatus& status) {
    std::vector<std::pair<std::string, std::string>> properties = {
            {"sys.yrrp.bootanim.config", configValue(status)},
            {"sys.yrrp.bootanim.route", routeName(status.route)},
            {"sys.yrrp.bootanim.blocked_by", status.blockedBy},
    };
    const bool rendered = status.route == MatrixRoute::Matrix || status.route == MatrixRoute::Xp;
    if (rendered && !status.outcome.empty()) {
        properties.emplace_back("sys.yrrp.bootanim.result", resultValue(status));
    }
    return properties;
}

}  // namespace android::yrrp
