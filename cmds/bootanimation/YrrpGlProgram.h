/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <GLES2/gl2.h>

namespace android::yrrp {

// Compiles and links one program for the YRRP renderers. Returns 0 and sets
// *failure to "COMPILE" or "LINK" on error, after logging the driver's info log
// under tag YrrpGl. Unlike the stock compileShader/linkShader, it frees every
// shader and program it created when it fails.
GLuint linkProgram(const char* vertexSource, const char* fragmentSource, const char** failure);

}  // namespace android::yrrp
