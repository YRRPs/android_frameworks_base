/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Prints one YRRP Matrix shader with a GLSL ES 1.00 version line, for
// glslangValidator. Usage: dump_matrix_shader vert|frag
#include <cstdio>
#include <cstring>

#include "YrrpMatrixShader.h"

int main(int argc, char** argv) {
    if (argc != 2) {
        return 2;
    }
    const bool vertex = strcmp(argv[1], "vert") == 0;
    if (!vertex && strcmp(argv[1], "frag") != 0) {
        return 2;
    }
    printf("#version 100\n%s\n",
           vertex ? android::yrrp::kMatrixVertexShader : android::yrrp::kMatrixFragmentShader);
    return 0;
}
