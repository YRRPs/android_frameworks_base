/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#include "YrrpXpLayout.h"

#include <cmath>

namespace android::yrrp {

namespace {

constexpr XpRect kCentre{200, 116, 262, 254};
constexpr XpRect kCopyright{18, 428, 145, 38};
constexpr XpRect kWordmark{540, 440, 84, 24};
constexpr double kCentreYFraction = 0.47;  // centre region's vertical centre
constexpr double kBottomFraction = 0.045;  // gap below the corner regions
constexpr double kSideMargin = 18.0;       // frame pixels, as in the original corners
constexpr double kWordmarkLift = 4.0;      // frame pixels

// Maps frame rect src, whose frame point (originX, originY) lands at panel
// (anchorX, anchorY). Rounding each edge keeps adjacent rects seamless.
XpRect mapRect(const XpRect& src, int originX, int originY, double anchorX, double anchorY,
               double scale) {
    const long x0 = std::lround(anchorX + (src.x - originX) * scale);
    const long x1 = std::lround(anchorX + (src.x + src.w - originX) * scale);
    const long y0 = std::lround(anchorY + (src.y - originY) * scale);
    const long y1 = std::lround(anchorY + (src.y + src.h - originY) * scale);
    return {static_cast<int>(x0), static_cast<int>(y0), static_cast<int>(x1 - x0),
            static_cast<int>(y1 - y0)};
}

XpBlit anchored(const XpRect& src, double anchorX, double anchorY, double scale) {
    return {src, mapRect(src, src.x, src.y, anchorX, anchorY, scale)};
}

}  // namespace

XpBarSpan xpBarSpan(int step) {
    if (step < 0 || step >= kXpBarSteps - 1) {
        return {kXpBarX, 0, 0};
    }
    if (step < 2) {
        const int skip = 8 * (2 - step);
        return {kXpBarX, skip, kXpSpriteWidth - skip};
    }
    const int width = step >= kXpBarSteps - 3 ? kXpSpriteWidth - 8 * (4 - (kXpBarSteps - step))
                                              : kXpSpriteWidth;
    return {kXpBarX + (step - 2) * 8, 0, width};
}

XpLayout xpLayout(int panelWidth, int panelHeight) {
    const double s = static_cast<double>(panelWidth) / kXpFrameWidth;
    const double bottom = panelHeight - kBottomFraction * panelHeight;
    XpLayout layout{};
    layout.scale = s;
    layout.centreX = (panelWidth - kCentre.w * s) / 2;
    layout.centreY = kCentreYFraction * panelHeight - kCentre.h * s / 2;
    layout.centre = anchored(kCentre, layout.centreX, layout.centreY, s);
    layout.copyright = anchored(kCopyright, kSideMargin * s, bottom - kCopyright.h * s, s);
    layout.wordmark = anchored(kWordmark, panelWidth - kSideMargin * s - kWordmark.w * s,
                               bottom - kWordmark.h * s - kWordmarkLift * s, s);
    return layout;
}

XpBlit xpBarBlit(const XpLayout& layout, int step) {
    const XpBarSpan span = xpBarSpan(step);
    const XpRect frame{span.frameX, kXpBarY, span.width, kXpSpriteHeight};
    return {{span.spriteFrom, 0, span.width, kXpSpriteHeight},
            mapRect(frame, kCentre.x, kCentre.y, layout.centreX, layout.centreY, layout.scale)};
}

XpQuad xpQuad(const XpBlit& blit, int panelWidth, int panelHeight, int textureWidth,
              int textureHeight) {
    const float w = static_cast<float>(panelWidth);
    const float h = static_cast<float>(panelHeight);
    const float tw = static_cast<float>(textureWidth);
    const float th = static_cast<float>(textureHeight);
    XpQuad quad{};
    quad.dst[0] = 2.0f * blit.dst.x / w - 1.0f;
    quad.dst[1] = 1.0f - 2.0f * (blit.dst.y + blit.dst.h) / h;
    quad.dst[2] = 2.0f * blit.dst.w / w;
    quad.dst[3] = 2.0f * blit.dst.h / h;
    quad.src[0] = blit.src.x / tw;
    quad.src[1] = blit.src.y / th;
    quad.src[2] = blit.src.w / tw;
    quad.src[3] = blit.src.h / th;
    return quad;
}

}  // namespace android::yrrp
