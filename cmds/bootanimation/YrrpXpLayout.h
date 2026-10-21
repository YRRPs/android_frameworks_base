/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

namespace android::yrrp {

// Geometry of the Windows XP Professional boot frame (640x480) and its progress
// bar, measured from the Wikimedia captures, and the phone layout that places
// three regions of that frame on a portrait panel.
inline constexpr int kXpFrameWidth = 640;
inline constexpr int kXpBarX = 259;  // first sprite column, frame pixels
inline constexpr int kXpBarY = 354;
inline constexpr int kXpSpriteWidth = 22;
inline constexpr int kXpSpriteHeight = 9;
inline constexpr int kXpBarSteps = 18;

struct XpRect {
    int x;
    int y;
    int w;
    int h;
};

// src in texture pixels, dst in panel pixels; both have a top-left origin.
struct XpBlit {
    XpRect src;
    XpRect dst;
};

// Sprite columns [spriteFrom, spriteFrom + width) at frame x frameX. width 0: no bar.
struct XpBarSpan {
    int frameX;
    int spriteFrom;
    int width;
};

// ReactOS RB_SQUARE_CELLS: steps 0-1 enter clipped from the left, 2-14 move
// the full sprite in 8 px steps, 15-16 leave clipped to the right, 17 is empty.
XpBarSpan xpBarSpan(int step);

struct XpLayout {
    double scale;    // panel width / 640, nearest-neighbour
    double centreX;  // unrounded panel position of the centre region's origin
    double centreY;
    XpBlit centre;     // logo and bar frame
    XpBlit copyright;  // bottom-left
    XpBlit wordmark;   // bottom-right
};

XpLayout xpLayout(int panelWidth, int panelHeight);

// The bar sprite for one step. src is in sprite pixels; dst.w is 0 when no bar shows.
XpBlit xpBarBlit(const XpLayout& layout, int step);

// One blit as GL inputs. dst: clip-space x, bottom y, width, height.
// src: u, v, du, dv, with v = 0 at the image's top row (initTexture uploads top row first).
struct XpQuad {
    float dst[4];
    float src[4];
};

XpQuad xpQuad(const XpBlit& blit, int panelWidth, int panelHeight, int textureWidth,
              int textureHeight);

}  // namespace android::yrrp
