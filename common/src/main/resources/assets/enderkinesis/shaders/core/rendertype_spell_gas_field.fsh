#version 150

// Poison's gas field: a true volume. Each pixel marches through the box of blocks round the
// camera that hold gas, reading each block's amount (0 to 1) from FieldSampler, smoothly
// between blocks, a little off where it is so no block edge shows, and breaking it up with 3D
// noise. Gas from many casts is one field, so it runs together into one cloud. The march runs
// camera-relative; the noise is sampled in the world, so the gas doesn't slide as the camera
// moves.
//
// FieldSampler holds one layer of blocks (x across, z down) per tile, FieldCols tiles to a
// row, from FieldOrigin (camera-relative, the least corner) over FieldSize blocks: each block's
// gas now in red, and as it was a tick before in green. FieldBlend runs from one to the other
// over the tick, so gas passing from block to block slides across rather than jumping.
//
// Only the box's faces the camera looks out through are drawn, without a depth test, so each
// pixel marches once; the march stops at the scene (DepthSampler), so gas in front of a wall or
// water shows and gas behind it doesn't. Kept cheap: steps with a jittered start, longer the
// farther from the camera (far gas needs less detail), two octaves of noise, and nothing worked
// out where there's no gas.

uniform sampler2D DepthSampler;
uniform sampler2D FieldSampler;

uniform mat4 ProjMat;
uniform vec2 ScreenSize;
// Ticks on the spell visuals' own clock (SpellClock): it doesn't wrap every day as GameTime does.
uniform float SpellTicks;
uniform vec3 CameraPos;
uniform vec3 FieldOrigin;
uniform vec3 FieldSize;
uniform float FieldCols;
uniform float FieldTexSize;
// 0: the gas as it was a tick before; 1: as it is now.
uniform float FieldBlend;
uniform vec3 DarkColor;
uniform vec3 LightColor;

in vec3 viewPos;
in vec3 viewRay;

out vec4 fragColor;

const float NOISE_SCALE = 0.55;

float h(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm2(vec3 p) {
    return (0.5 * n3(p) + 0.25 * n3(p * 2.07 + 5.3)) / 0.75;
}

/** How far along the view ray [dir] (view space, unit) the scene is. */
float sceneDistance(vec3 dir) {
    float depth = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r;
    float ndc = depth * 2.0 - 1.0;
    float z = ProjMat[3][2] / (-ndc - ProjMat[2][2]);
    return z / dir.z;
}

/** Where in the texture one layer's block at [xz] (blocks into the box) is, in texels. */
vec2 texel(vec2 xz, float y) {
    // Not mod(): GPUs work it out through a reciprocal, and on a layer a whole number of rows
    // in it can come out a tile past the row's end, so that whole layer reads as empty.
    float row = floor((y + 0.5) / FieldCols), col = y - row * FieldCols;
    // Kept half a texel inside the tile, so nothing blends in from the next one.
    return clamp(xz, vec2(0.5), FieldSize.xz - 0.5) + vec2(col * FieldSize.x, row * FieldSize.z);
}

/** The gas at [q] (blocks into the box), blended between blocks in all three directions and
 *  from a tick before to now. */
float field(vec3 q) {
    float y = clamp(q.y - 0.5, 0.0, FieldSize.y - 1.0);
    float y0 = floor(y);
    vec2 a = texture(FieldSampler, texel(q.xz, y0) / FieldTexSize).rg;
    vec2 b = texture(FieldSampler, texel(q.xz, min(y0 + 1.0, FieldSize.y - 1.0)) / FieldTexSize).rg;
    vec2 s = mix(a, b, y - y0);
    return mix(s.g, s.r, FieldBlend);
}

void main() {
    float ticks = SpellTicks;
    vec3 rd = normalize(viewPos);
    vec3 inv = 1.0 / (rd + vec3(equal(rd, vec3(0.0))) * 1e-6);
    vec3 ta = FieldOrigin * inv, tb = (FieldOrigin + FieldSize) * inv;
    vec3 lo = min(ta, tb), hi = max(ta, tb);
    float t0 = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float t1 = min(min(hi.x, hi.y), min(hi.z, sceneDistance(normalize(viewRay))));
    if (t1 <= t0) discard;
    const int MAX_STEPS = 56;
    // Half a block a step near, a block and a half far off.
    float stepLen = clamp(0.5 + 0.02 * t0, 0.5, 1.5);
    int steps = int(clamp(ceil((t1 - t0) / stepLen), 4.0, float(MAX_STEPS)));
    float dt = (t1 - t0) / float(steps), trans = 1.0;
    // Each pixel starts at its own point in the first step, so the steps don't show as bands.
    float jitter = h(vec3(gl_FragCoord.xy, fract(ticks * 0.013) * 61.0));
    vec3 drift = vec3(ticks * 0.01, -ticks * 0.006, ticks * 0.008);
    vec3 col = vec3(0.0);
    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= steps) break;
        vec3 p = rd * (t0 + (float(i) + jitter) * dt);
        vec3 q = p - FieldOrigin;
        // Nothing near: no noise worked out (the wisps reach about a block).
        if (field(q) < 0.002) continue;
        vec3 w = (p + CameraPos) * NOISE_SCALE + drift;
        float n0 = fbm2(w);
        float n1 = fbm2(w + 7.3);
        float n = mix(n0, n1, 0.35);
        // Read a little off where it is, so the gas wisps instead of filling blocks.
        float f = field(q + vec3(n0 - 0.5, (n - 0.5) * 0.5, n1 - 0.5) * 1.3);
        if (f < 0.004) continue;
        // Thicker the more gas there is, billowing over a body that's never clear.
        float dens = f * (0.45 + 0.55 * smoothstep(0.2, 0.75, n));
        float a = 1.0 - exp(-dens * dt * 1.4);
        vec3 lit = mix(DarkColor, LightColor, clamp(0.35 + (n - 0.5) * 1.2 + (1.0 - f) * 0.3, 0.0, 1.0));
        col += trans * a * lit;
        trans *= 1.0 - a;
        if (trans < 0.04) break;
    }
    float alpha = 1.0 - trans;
    if (alpha < 0.01) discard;
    fragColor = vec4(col / max(alpha, 0.001), alpha);
}
