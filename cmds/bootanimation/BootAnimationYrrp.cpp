/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "YrrpMatrix"

#include <GLES2/gl2.h>
#include <android-base/properties.h>
#include <errno.h>
#include <log/log.h>
#include <time.h>
#include <utils/Timers.h>

#include "BootAnimation.h"
#include "YrrpMatrixConfig.h"
#include "YrrpMatrixShader.h"
#include "YrrpMatrixStatus.h"
#include "YrrpMatrixTimeline.h"

namespace android {

struct BootAnimation::YrrpMatrixGl {
    GLuint program = 0;
    GLint aPosition = -1;
    GLint uAtlas = -1;
    GLint uLogo = -1;
    GLint uRes = -1;
    GLint uTime = -1;
    GLint uExit = -1;
    GLint uLogoAspect = -1;
    Texture atlas{0, 0, 0};
    Texture logo{0, 0, 0};
};

namespace {

constexpr nsecs_t kFrameNs = 16666667; // 60 fps cap; stalls are kept, never caught up
constexpr GLfloat kFullScreen[] = {-1.f, -1.f, 1.f, -1.f, -1.f, 1.f, 1.f, 1.f};
constexpr char kAtlasAsset[] = "images/yrrp-matrix-atlas.png";
constexpr char kLogoAsset[] = "images/yrrp-matrix-logo.png";

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

void useLinearFiltering(GLuint texture) {
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
}

void publish(const yrrp::MatrixStatus& status) {
    for (const auto& [key, value] : yrrp::statusProperties(status)) {
        android::base::SetProperty(key, value);
    }
}

void sleepUntil(nsecs_t target) {
    timespec spec{static_cast<time_t>(target / 1000000000), static_cast<long>(target % 1000000000)};
    while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &spec, nullptr) == EINTR) {
    }
}

} // namespace

bool BootAnimation::playYrrpBootAnimation() {
    yrrp::MatrixStatus status;
    status.config = yrrp::parseMatrixConfig(android::base::GetProperty(yrrp::kStyleProp, ""),
                                            android::base::GetProperty(yrrp::kFullIntroProp, ""));
    status.shuttingDown = mShuttingDown;
    status.propsReady = android::base::GetBoolProperty("ro.persistent_properties.ready", false);
    status.route = yrrp::routeFor(status.config.style, mShuttingDown);
    for (const auto& [key, value] : yrrp::statusProperties(status)) {
        if (key == "sys.yrrp.bootanim.config") {
            ALOGI("config %s", value.c_str());
        }
    }
    if (status.route != yrrp::MatrixRoute::Matrix) {
        status.blockedBy = "STYLE_LINEAGE";
        publish(status);
        ALOGI("route=%s blockedBy=STYLE_LINEAGE", yrrp::routeName(status.route));
        return status.route == yrrp::MatrixRoute::Exit;
    }

    YrrpMatrixGl gl;
    if (const char* failure = initYrrpMatrix(&gl)) {
        releaseYrrpMatrix(&gl);
        status.route = yrrp::routeAfterInitFailure(mShuttingDown);
        status.blockedBy = failure;
        publish(status);
        ALOGW("init failed reason=%s fallback=%s", failure, yrrp::routeName(status.route));
        return status.route == yrrp::MatrixRoute::Exit;
    }
    if (mDisplays.empty()) {
        ALOGI("init ok panel=none displays=0");
    } else {
        ALOGI("init ok panel=%dx%d displays=%zu", mDisplays.front().width, mDisplays.front().height,
              mDisplays.size());
    }
    publish(status);
    runYrrpMatrix(gl, &status);
    releaseYrrpMatrix(&gl);
    publish(status);
    return true;
}

const char* BootAnimation::initYrrpMatrix(YrrpMatrixGl* gl) {
    if (initTexture(&gl->atlas, mAssets, kAtlasAsset) != NO_ERROR) {
        return "ASSET_ATLAS";
    }
    if (initTexture(&gl->logo, mAssets, kLogoAsset) != NO_ERROR || gl->logo.h <= 0) {
        return "ASSET_LOGO";
    }
    useLinearFiltering(gl->atlas.name);
    useLinearFiltering(gl->logo.name);

    const GLuint vertex = compile(GL_VERTEX_SHADER, yrrp::kMatrixVertexShader);
    const GLuint fragment = compile(GL_FRAGMENT_SHADER, yrrp::kMatrixFragmentShader);
    if (vertex == 0 || fragment == 0) {
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return "COMPILE";
    }
    gl->program = glCreateProgram();
    glAttachShader(gl->program, vertex);
    glAttachShader(gl->program, fragment);
    glLinkProgram(gl->program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    GLint linked = GL_FALSE;
    glGetProgramiv(gl->program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        return "LINK";
    }
    gl->aPosition = glGetAttribLocation(gl->program, "aPosition");
    gl->uAtlas = glGetUniformLocation(gl->program, "u_atlas");
    gl->uLogo = glGetUniformLocation(gl->program, "u_logo");
    gl->uRes = glGetUniformLocation(gl->program, "u_res");
    gl->uTime = glGetUniformLocation(gl->program, "u_time");
    gl->uExit = glGetUniformLocation(gl->program, "u_exit");
    gl->uLogoAspect = glGetUniformLocation(gl->program, "u_logoAspect");
    if (gl->aPosition < 0 || glGetError() != GL_NO_ERROR) {
        return "GL";
    }
    return nullptr;
}

void BootAnimation::releaseYrrpMatrix(YrrpMatrixGl* gl) {
    if (gl->program != 0) {
        glDeleteProgram(gl->program);
        gl->program = 0;
    }
    glDeleteTextures(1, &gl->atlas.name); // name 0 is ignored
    glDeleteTextures(1, &gl->logo.name);
    gl->atlas.name = 0;
    gl->logo.name = 0;
}

void BootAnimation::runYrrpMatrix(const YrrpMatrixGl& gl, yrrp::MatrixStatus* status) {
    yrrp::MatrixTimeline timeline(status->config.fullIntro, mShuttingDown);
    yrrp::FrameStats stats;

    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_DITHER); // limits banding on the RGB565 surface
    glUseProgram(gl.program);
    glUniform1i(gl.uAtlas, 0);
    glUniform1i(gl.uLogo, 1);
    glUniform1f(gl.uLogoAspect, static_cast<float>(gl.logo.w) / gl.logo.h);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, gl.logo.name);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gl.atlas.name);

    const nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);
    nsecs_t lastFrameEnd = start;
    for (;;) {
        processDisplayEvents();
        checkExit();
        const nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
        const double seconds = static_cast<double>(now - start) / 1e9;
        if (exitPending() && mShuttingDown) {
            status->outcome = "EXIT_PENDING";
            break;
        }
        if (exitPending() && !timeline.exitObserved()) {
            timeline.onExitObserved(seconds);
            ALOGI("exit observed t=%.2f exitT=%.2f deferred=%d", seconds, timeline.exitTime(),
                  timeline.exitDeferred());
        }
        const yrrp::MatrixFrame frame = timeline.frame(seconds);
        if (frame.done) {
            status->outcome = "COMPLETED";
            break;
        }

        bool swapped = true;
        for (const auto& display : mDisplays) {
            eglMakeCurrent(mEgl, display.eglSurface, display.eglSurface, mEglContext);
            projectSceneToWindow(display);
            glVertexAttribPointer(gl.aPosition, 2, GL_FLOAT, GL_FALSE, 0, kFullScreen);
            glEnableVertexAttribArray(gl.aPosition);
            glUniform2f(gl.uRes, static_cast<GLfloat>(display.width),
                        static_cast<GLfloat>(display.height));
            glUniform1f(gl.uTime, frame.shaderTime);
            glUniform1f(gl.uExit, frame.shaderExit);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            swapped = eglSwapBuffers(mEgl, display.eglSurface) == EGL_TRUE && swapped;
        }
        if (!swapped) {
            status->outcome = "SWAP_FAILED";
            break;
        }
        const nsecs_t frameEnd = systemTime(SYSTEM_TIME_MONOTONIC);
        stats.add(static_cast<double>(frameEnd - lastFrameEnd) / 1e6);
        lastFrameEnd = frameEnd;
        sleepUntil(now + kFrameNs);
    }

    status->frames = stats.count();
    status->averageFrameMs = stats.averageMs();
    status->maxFrameMs = stats.maxMs();
    ALOGI("end outcome=%s frames=%d avgFrameMs=%.1f maxFrameMs=%.1f durationS=%.2f",
          status->outcome.c_str(), status->frames, status->averageFrameMs, status->maxFrameMs,
          static_cast<double>(systemTime(SYSTEM_TIME_MONOTONIC) - start) / 1e9);
}

} // namespace android
