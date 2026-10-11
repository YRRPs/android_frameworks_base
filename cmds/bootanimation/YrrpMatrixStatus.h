/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <string>
#include <utility>
#include <vector>

#include "YrrpMatrixConfig.h"

namespace android::yrrp {

// The decision of one bootanimation run. Published as sys.yrrp.bootanim.*
// properties, which outlive logcat rotation until the next boot.
struct MatrixStatus {
    MatrixConfig config;
    bool shuttingDown = false;
    bool propsReady = false;
    MatrixRoute route = MatrixRoute::Matrix;
    std::string blockedBy = "none";  // STYLE_LINEAGE, ASSET_ATLAS, ASSET_LOGO, COMPILE, LINK, GL
    std::string outcome;             // COMPLETED, EXIT_PENDING, SWAP_FAILED; empty until the end
    int frames = 0;
    double averageFrameMs = 0.0;
    double maxFrameMs = 0.0;
};

std::vector<std::pair<std::string, std::string>> statusProperties(const MatrixStatus& status);

}  // namespace android::yrrp
