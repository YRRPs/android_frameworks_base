/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <string>

namespace android::yrrp {

// Written by YRRPs Settings; read once when bootanimation starts. Settings
// (YrrpBootAnimationStore) parses both values with the same rules.
inline constexpr char kStyleProp[] = "persist.yrrp.bootanim.style";
inline constexpr char kFullIntroProp[] = "persist.yrrp.bootanim.full_intro";

enum class MatrixStyle { Matrix, Lineage, Xp };

// Matrix: play the YRRP Matrix renderer. Xp: play the YRRP Windows XP renderer.
// Stock: continue with the zip path. Exit: end bootanimation at once
// (shutdown without a YRRP animation).
enum class MatrixRoute { Matrix, Stock, Exit, Xp };

struct MatrixConfig {
    MatrixStyle style = MatrixStyle::Matrix;
    bool styleFromProp = false;
    bool fullIntro = true;
    bool fullIntroFromProp = false;
};

// Exact "lineage", "xp" or "matrix" select a style; anything else is the Matrix
// default. "false"/"0" and "true"/"1" set full intro; anything else is true.
MatrixConfig parseMatrixConfig(const std::string& style, const std::string& fullIntro);

// Shutdown signals: system_server's ShutdownThread sets sys.shutdown.requested
// before a power-menu shutdown or reboot. Lineage 23.2 init does not store
// sys.powerctl in the property area, so it reads empty in practice; it is
// still honoured.
inline constexpr char kPowerCtlProp[] = "sys.powerctl";
inline constexpr char kShutdownRequestedProp[] = "sys.shutdown.requested";

// True when either signal is non-empty.
bool isShuttingDown(const std::string& powerCtl, const std::string& shutdownRequested);

MatrixRoute routeFor(MatrixStyle style, bool shuttingDown);
MatrixRoute routeAfterInitFailure(bool shuttingDown);

const char* styleName(MatrixStyle style);
const char* routeName(MatrixRoute route);

}  // namespace android::yrrp
