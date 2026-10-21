/*
 * SPDX-FileCopyrightText: Yim's Riced ROM Project
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

namespace android::yrrp {

// One textured quad. aPosition is the unit square; u_dst places it in clip
// space (x, bottom y, width, height) and u_src picks the texture rect
// (u, v, du, dv) with v = 0 at the image's top row. The stock image shader
// cannot sample a sub-rect, which the XP frame regions and bar sprite need.
inline constexpr char kXpVertexShader[] = R"(
attribute vec2 aPosition;
uniform vec4 u_dst;
uniform vec4 u_src;
varying vec2 vUv;
void main() {
    vUv = vec2(u_src.x + aPosition.x * u_src.z, u_src.y + (1.0 - aPosition.y) * u_src.w);
    gl_Position = vec4(u_dst.xy + aPosition * u_dst.zw, 0.0, 1.0);
})";

// highp: mediump UVs are too coarse for exact texels on a 640 px texture.
inline constexpr char kXpFragmentShader[] = R"(
precision highp float;
uniform sampler2D u_tex;
varying vec2 vUv;
void main() {
    gl_FragColor = texture2D(u_tex, vUv);
})";

}  // namespace android::yrrp
