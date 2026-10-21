/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "YrrpGl"

#include "YrrpGlProgram.h"

#include <log/log.h>

namespace android::yrrp {

namespace {

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok == GL_TRUE) {
        return shader;
    }
    char infoLog[512] = {};
    glGetShaderInfoLog(shader, sizeof(infoLog), nullptr, infoLog);
    ALOGW("shader compile failed type=0x%x log=%s", type, infoLog);
    glDeleteShader(shader);
    return 0;
}

}  // namespace

GLuint linkProgram(const char* vertexSource, const char* fragmentSource, const char** failure) {
    const GLuint vertex = compile(GL_VERTEX_SHADER, vertexSource);
    const GLuint fragment = compile(GL_FRAGMENT_SHADER, fragmentSource);
    if (vertex == 0 || fragment == 0) {
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        *failure = "COMPILE";
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char infoLog[512] = {};
        glGetProgramInfoLog(program, sizeof(infoLog), nullptr, infoLog);
        ALOGW("program link failed log=%s", infoLog);
        glDeleteProgram(program);
        *failure = "LINK";
        return 0;
    }
    return program;
}

}  // namespace android::yrrp
