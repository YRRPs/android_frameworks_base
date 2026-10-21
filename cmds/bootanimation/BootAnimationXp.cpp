/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "YrrpXp"

#include <GLES2/gl2.h>
#include <log/log.h>
#include <utils/Timers.h>

#include <vector>

#include "BootAnimation.h"
#include "YrrpGlProgram.h"
#include "YrrpMatrixConfig.h"
#include "YrrpMatrixStatus.h"
#include "YrrpMatrixTimeline.h"
#include "YrrpXpLayout.h"
#include "YrrpXpShader.h"
#include "YrrpXpTimeline.h"

namespace android {

struct BootAnimation::YrrpXpGl {
    GLuint program = 0;
    GLint aPosition = -1;
    GLint uTex = -1;
    GLint uDst = -1;
    GLint uSrc = -1;
    Texture boot{0, 0, 0};
    Texture bar{0, 0, 0};
    Texture shutdown{0, 0, 0};
};

namespace {

constexpr GLfloat kUnitQuad[] = {0.f, 0.f, 1.f, 0.f, 0.f, 1.f, 1.f, 1.f};
constexpr char kBootAsset[] = "images/yrrp-xp-boot.png";
constexpr char kBarAsset[] = "images/yrrp-xp-bar.png";
constexpr char kShutdownAsset[] = "images/yrrp-xp-shutdown.png";
constexpr int kBlackFrame = -1;  // drawYrrpXp step for an all-black frame

template <typename Displays>
std::vector<EGLSurface> surfacesOf(const Displays& displays) {
    std::vector<EGLSurface> surfaces;
    for (const auto& display : displays) {
        surfaces.push_back(display.eglSurface);
    }
    return surfaces;
}

bool loaded(status_t result, const BootAnimation::Texture& texture) {
    return result == NO_ERROR && texture.w > 0 && texture.h > 0;
}

void logLayout(int width, int height) {
    const yrrp::XpLayout l = yrrp::xpLayout(width, height);
    ALOGI("layout centre=%d,%d,%d,%d copy=%d,%d,%d,%d ms=%d,%d,%d,%d", l.centre.dst.x,
          l.centre.dst.y, l.centre.dst.w, l.centre.dst.h, l.copyright.dst.x, l.copyright.dst.y,
          l.copyright.dst.w, l.copyright.dst.h, l.wordmark.dst.x, l.wordmark.dst.y,
          l.wordmark.dst.w, l.wordmark.dst.h);
}

} // namespace

bool BootAnimation::playYrrpXp(yrrp::MatrixStatus* status) {
    YrrpXpGl gl;
    if (const char* failure = initYrrpXp(&gl)) {
        releaseYrrpXp(&gl);
        status->route = yrrp::routeAfterInitFailure(mShuttingDown);
        status->blockedBy = failure;
        publishYrrpStatus(*status);
        ALOGW("init failed reason=%s fallback=%s", failure, yrrp::routeName(status->route));
        return status->route == yrrp::MatrixRoute::Exit;
    }
    if (mDisplays.empty()) {
        ALOGI("init ok panel=none displays=0");
    } else {
        const Display& first = mDisplays.front();
        ALOGI("init ok panel=%dx%d displays=%zu scale=%.4f", first.width, first.height,
              mDisplays.size(), static_cast<double>(first.width) / yrrp::kXpFrameWidth);
        logLayout(first.width, first.height);
    }
    publishYrrpStatus(*status);
    runYrrpXp(gl, status);
    releaseYrrpXp(&gl);
    publishYrrpStatus(*status);
    return true;
}

const char* BootAnimation::initYrrpXp(YrrpXpGl* gl) {
    // Drop errors left by earlier GL work so the final check reports only ours.
    // Bounded, so a context-loss error that keeps reporting cannot spin here.
    for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; ++i) {
    }
    if (mShuttingDown) {
        if (!loaded(initTexture(&gl->shutdown, mAssets, kShutdownAsset), gl->shutdown)) {
            return "ASSET_XP_SHUTDOWN";
        }
        glBindTexture(GL_TEXTURE_2D, gl->shutdown.name);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    } else {
        // initTexture leaves GL_NEAREST and GL_CLAMP_TO_EDGE: exact palette texels.
        if (!loaded(initTexture(&gl->boot, mAssets, kBootAsset), gl->boot)) {
            return "ASSET_XP_BOOT";
        }
        if (!loaded(initTexture(&gl->bar, mAssets, kBarAsset), gl->bar)) {
            return "ASSET_XP_BAR";
        }
    }
    const char* failure = nullptr;
    gl->program = yrrp::linkProgram(yrrp::kXpVertexShader, yrrp::kXpFragmentShader, &failure);
    if (gl->program == 0) {
        return failure;
    }
    gl->aPosition = glGetAttribLocation(gl->program, "aPosition");
    gl->uTex = glGetUniformLocation(gl->program, "u_tex");
    gl->uDst = glGetUniformLocation(gl->program, "u_dst");
    gl->uSrc = glGetUniformLocation(gl->program, "u_src");
    if (gl->aPosition < 0 || glGetError() != GL_NO_ERROR) {
        return "GL";
    }
    return nullptr;
}

void BootAnimation::releaseYrrpXp(YrrpXpGl* gl) {
    if (gl->program != 0) {
        glDeleteProgram(gl->program);
        gl->program = 0;
    }
    for (Texture* texture : {&gl->boot, &gl->bar, &gl->shutdown}) {
        glDeleteTextures(1, &texture->name); // name 0 is ignored
        texture->name = 0;
    }
}

// Draws one frame on every display and swaps. step is the bar step on boot,
// any step on shutdown, or kBlackFrame for the exit cut.
bool BootAnimation::drawYrrpXp(const YrrpXpGl& gl, int step) {
    bool swapped = true;
    for (const auto& display : mDisplays) {
        eglMakeCurrent(mEgl, display.eglSurface, display.eglSurface, mEglContext);
        projectSceneToWindow(display);
        glClearColor(0.f, 0.f, 0.f, 1.f);
        glClear(GL_COLOR_BUFFER_BIT);
        glVertexAttribPointer(gl.aPosition, 2, GL_FLOAT, GL_FALSE, 0, kUnitQuad);
        glEnableVertexAttribArray(gl.aPosition);
        const auto blit = [&](const Texture& texture, const yrrp::XpBlit& b) {
            if (b.dst.w <= 0 || b.dst.h <= 0) {
                return;
            }
            const yrrp::XpQuad q =
                    yrrp::xpQuad(b, display.width, display.height, texture.w, texture.h);
            glBindTexture(GL_TEXTURE_2D, texture.name);
            glUniform4fv(gl.uDst, 1, q.dst);
            glUniform4fv(gl.uSrc, 1, q.src);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        };
        if (step != kBlackFrame && mShuttingDown) {
            const yrrp::XpRect full{0, 0, gl.shutdown.w, gl.shutdown.h};
            blit(gl.shutdown, {full, {0, 0, display.width, display.height}});
        } else if (step != kBlackFrame) {
            const yrrp::XpLayout layout = yrrp::xpLayout(display.width, display.height);
            blit(gl.boot, layout.centre);
            blit(gl.boot, layout.copyright);
            blit(gl.boot, layout.wordmark);
            blit(gl.bar, yrrp::xpBarBlit(layout, step));
        }
        swapped = eglSwapBuffers(mEgl, display.eglSurface) == EGL_TRUE && swapped;
    }
    return swapped;
}

void BootAnimation::runYrrpXp(const YrrpXpGl& gl, yrrp::MatrixStatus* status) {
    yrrp::XpTimeline timeline(mShuttingDown);
    yrrp::FrameStats stats;

    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);
    // Boot: exact palette pixels. Shutdown: dither the gradient lines on RGB565.
    if (mShuttingDown) {
        glEnable(GL_DITHER);
    } else {
        glDisable(GL_DITHER);
    }
    glUseProgram(gl.program);
    glUniform1i(gl.uTex, 0);
    glActiveTexture(GL_TEXTURE0);

    const nsecs_t start = systemTime(SYSTEM_TIME_MONOTONIC);
    nsecs_t lastSwap = start;
    std::vector<EGLSurface> drawnSurfaces;
    for (;;) {
        processDisplayEvents();
        checkExit();
        // A resize recreates a display's surface; the static shutdown frame must be redrawn.
        if (surfacesOf(mDisplays) != drawnSurfaces) {
            timeline.invalidate();
        }
        const nsecs_t elapsed = systemTime(SYSTEM_TIME_MONOTONIC) - start;
        if (exitPending()) {
            status->outcome = timeline.exitOutcome();
            ALOGI("exit observed t=%.2f step=%d", static_cast<double>(elapsed) / 1e9,
                  timeline.stepAt(elapsed));
            if (!mShuttingDown && !drawYrrpXp(gl, kBlackFrame)) {
                status->outcome = "SWAP_FAILED";
            }
            break;
        }
        if (timeline.needsDraw(elapsed)) {
            if (!drawYrrpXp(gl, timeline.stepAt(elapsed))) {
                status->outcome = "SWAP_FAILED";
                break;
            }
            drawnSurfaces = surfacesOf(mDisplays);
            const nsecs_t swappedAt = systemTime(SYSTEM_TIME_MONOTONIC);
            stats.add(static_cast<double>(swappedAt - lastSwap) / 1e6);
            lastSwap = swappedAt;
        }
        sleepUntilYrrp(start + timeline.nextWakeNs(elapsed));
    }

    status->frames = stats.count();
    status->averageFrameMs = stats.averageMs();
    status->maxFrameMs = stats.maxMs();
    ALOGI("end outcome=%s frames=%d avgSwapGapMs=%.1f maxSwapGapMs=%.1f durationS=%.2f",
          status->outcome.c_str(), status->frames, status->averageFrameMs, status->maxFrameMs,
          static_cast<double>(systemTime(SYSTEM_TIME_MONOTONIC) - start) / 1e9);
}

} // namespace android
