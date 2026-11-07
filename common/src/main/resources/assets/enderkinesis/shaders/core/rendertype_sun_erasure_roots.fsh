#version 150

// Ritual of Sun Erasure: roots wind across the sky to the sun and coil around it.
// Each root is a curve in polar angles around the sun: a is the angle from the sun, phi the angle around it.
// The curve is drawn as straight segments, each with the vanilla mangrove roots texture laid flat on it,
// so the texels stay square through the bends.

uniform sampler2D Sampler0;
// The root sprite's bounds in the block atlas: u0, v0, u1, v1.
uniform vec4 SpriteUV;
uniform vec3 SunDir;
// 0 to 1: how far the roots grew from the far sky toward the sun.
uniform float Growth;
// 0 to 1: the roots fade out with this after the erasure ends.
uniform float Presence;
uniform float Brightness;
// Seconds; the host keeps it bounded for float precision.
uniform float Time;

in vec3 vDir;
out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.28318531;
const int ROOTS = 12;
const int BRANCHES = 3;
// The coil radius around the sun centre, in radians.
const float RING = 0.12;
// The spiral turns by SPIRAL_K * ln(1 + SPIRAL_C / x), with x the angle past RING.
const float SPIRAL_K = 2.4;
const float SPIRAL_C = 0.45;
const float FAR_X = PI - RING;
const float END_X = 0.012;
const float LN_FAR = log(FAR_X);
const float LN_END = log(END_X);
// Segment ends are even steps in ln x, so the segments get shorter in the coil.
const int SEGMENTS = 64;
const float SEG_STEP = (LN_FAR - LN_END) / float(SEGMENTS);
const float ROOT_WIDTH = 0.020;
const float BRANCH_WIDTH = 0.009;
// One texel of the 16 texel sprite, in radians.
const float TEXEL = 0.006;
const float TILE = 16.0 * TEXEL;
const float BACK_SHADE = 0.55;
// A curve further than this from the fragment, by the quick estimate, cannot cover it.
const float NEAR = 0.06;
// The perpendicular to the sun's path; the sun moves in the XY plane.
const vec3 POLE = vec3(0.0, 0.0, 1.0);
// The hole opens inside the coil as the roots close round the sun. Wohlon water fog and water colours.
const vec3 HOLE_DEEP = vec3(0.141, 0.314, 0.353);
const vec3 HOLE_RIM = vec3(0.059, 0.125, 0.149);
const float HOLE_EDGE = 0.02;
// The sky behind the roots: a dim, low contrast fractal from Wohlon water to Wohlon sky.
const vec3 SKY_LOW = vec3(0.030, 0.065, 0.080);
const vec3 SKY_MID = vec3(0.060, 0.125, 0.145);
const vec3 SKY_HIGH = vec3(0.105, 0.150, 0.170);
const int SKY_OCTAVES = 5;
// How far the noise pushes the hole's edge in and out, in radians.
const float FRINGE = 0.025;

vec3 sunE2;

float hash(float n) {
    return fract(sin(n * 12.9898) * 43758.5453);
}

float valueNoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n = dot(i, vec3(1.0, 57.0, 113.0));
    return mix(mix(mix(hash(n), hash(n + 1.0), f.x), mix(hash(n + 57.0), hash(n + 58.0), f.x), f.y),
               mix(mix(hash(n + 113.0), hash(n + 114.0), f.x), mix(hash(n + 170.0), hash(n + 171.0), f.x), f.y), f.z);
}

// Noise round the hole's edge: sampled on a circle, so it has no seam where phi wraps.
float fringe(float phi) {
    vec3 p = vec3(cos(phi), sin(phi), 0.07 * Time);
    return valueNoise(p * 4.0) * 0.65 + valueNoise(p * 11.0 + 3.7) * 0.35 - 0.5;
}

float fbm(vec3 p) {
    float sum = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < SKY_OCTAVES; i++) {
        sum += amplitude * valueNoise(p);
        p = p * 2.03 + vec3(1.7, 9.2, 3.1);
        amplitude *= 0.5;
    }
    return sum / (1.0 - pow(0.5, float(SKY_OCTAVES)));
}

// Domain warped, so the pattern folds like the fractal and does not look like plain clouds.
vec3 skyColour(vec3 d) {
    vec3 p = d * 3.0 + vec3(0.0, 0.0, 0.01 * Time);
    vec3 warp = vec3(fbm(p + 4.1), fbm(p + 8.3), fbm(p + 1.9));
    float f = fbm(p + 2.0 * warp);
    vec3 colour = mix(SKY_LOW, SKY_MID, smoothstep(0.3, 0.6, f));
    return mix(colour, SKY_HIGH, smoothstep(0.6, 0.8, f) * warp.x);
}

float wrapAngle(float x) {
    return mod(x + PI, TAU) - PI;
}

float spiral(float x) {
    return SPIRAL_K * log(1.0 + SPIRAL_C / x);
}

float spiralSlope(float x) {
    return -SPIRAL_K * SPIRAL_C / (x * (x + SPIRAL_C));
}

// The trunk angle around the sun at x; m holds two meander amplitudes and phases.
float trunkPhi(float x, float base, vec4 m, out float slope) {
    float a = RING + x;
    float e = smoothstep(0.05, 0.6, x);
    // The phases sway slowly, so the meanders travel along the root and the roots writhe.
    float s1 = 4.3 * a + m.z + 1.6 * sin(0.11 * Time + m.w);
    float s2 = 9.7 * a + m.w + 2.0 * sin(0.17 * Time + m.z);
    slope = e * (m.x * 4.3 * cos(s1) + m.y * 9.7 * cos(s2)) + spiralSlope(x);
    return base + e * (m.x * sin(s1) + m.y * sin(s2)) + spiral(x);
}

// b: join x, spread, wobble phase.
float branchPhi(float x, float base, vec4 m, vec3 b, out float slope) {
    float trunk = trunkPhi(x, base, m, slope);
    slope -= b.y / x;
    return trunk + b.y * (log(b.x) - log(x)) + 0.08 * sin(9.0 * (RING + x) + b.z + 1.5 * sin(0.2 * Time + b.z));
}

vec3 skyPoint(float x, float phi) {
    float a = RING + x;
    return cos(a) * SunDir + sin(a) * (cos(phi) * POLE + sin(phi) * sunE2);
}

// Quick angular distance from the fragment to a curve, for the early skip.
float nearDistance(float a, float phi, float curvePhi, float slope) {
    float s = sin(a);
    float across = s * slope;
    return abs(wrapAngle(phi - curvePhi)) * s / sqrt(1.0 + across * across);
}

// One straight segment from p0 to p1 with the sprite laid flat on it; v0 is the texture offset along it.
// rgb is the texel and a is 1 inside the root, else 0.
vec4 segment(vec3 d, vec3 p0, vec3 p1, float width, float v0) {
    vec3 n = cross(p0, p1);
    float nLength = length(n);
    if (nLength < 1e-6 || width <= 0.0) return vec4(0.0);
    n /= nLength;
    float along = atan(dot(d, cross(n, p0)), dot(d, p0));
    float segLength = atan(nLength, dot(p0, p1));
    // The ends run on a little, so the outside of a bend has no gap.
    if (along < -0.5 * width || along > segLength + 0.5 * width) return vec4(0.0);
    float across = dot(d, n);
    if (abs(across) > width) return vec4(0.0);
    vec2 tile = fract(vec2(0.5 + across / TILE, (v0 + along) / TILE));
    vec4 texel = textureLod(Sampler0, mix(SpriteUV.xy, SpriteUV.zw, tile), 0.0);
    if (texel.a >= 0.5) return vec4(texel.rgb, 1.0);
    // Like a mangrove roots block, the gaps show the far side: the sprite mirrored and darker.
    vec4 back = textureLod(Sampler0, mix(SpriteUV.xy, SpriteUV.zw, vec2(1.0 - tile.x, fract(tile.y + 0.5))), 0.0);
    return vec4(back.rgb * BACK_SHADE, step(0.5, back.a));
}

// The texture offset at x: it grows with the spiral, so the texture keeps its scale in the coil.
float alongAt(float x) {
    return RING * spiral(x) - (RING + x);
}

void main() {
    vec3 d = normalize(vDir);
    sunE2 = cross(SunDir, POLE);
    float a = acos(clamp(dot(d, SunDir), -1.0, 1.0));
    float phi = atan(dot(d, sunE2), dot(d, POLE));
    float x = max(a - RING, 1e-4);
    float lnX = log(x);
    int k0 = int(floor((LN_FAR - lnX) / SEG_STEP));

    float holeRadius = RING * smoothstep(0.5, 1.0, Growth);
    float edge = holeRadius + FRINGE * fringe(phi) * smoothstep(0.0, 0.5 * RING, holeRadius);
    float hole = 1.0 - smoothstep(edge - HOLE_EDGE, edge, a);
    vec4 best = vec4(0.0);
    for (int i = 0; i < ROOTS; i++) {
        float fi = float(i);
        // Some roots grow faster; all reach the sun by 80 percent of the growth.
        float g = clamp(Growth * (1.0 + 0.25 * hash(fi + 0.5)), 0.0, 1.0);
        float tipX = exp(mix(LN_FAR, LN_END, g));
        if (x < tipX * 0.9) continue;
        float base = TAU * (fi + 0.35 * (hash(fi + 1.7) - 0.5)) / float(ROOTS);
        vec4 m = vec4(0.30 + 0.25 * hash(fi + 3.1), 0.06 + 0.06 * hash(fi + 4.3),
                      TAU * hash(fi + 5.9), TAU * hash(fi + 7.3));
        float taper = smoothstep(0.0, 1.2, lnX - log(tipX));
        float width = ROOT_WIDTH * (0.75 + 0.5 * hash(fi + 9.1)) * taper * mix(0.55, 1.0, smoothstep(0.0, 0.5, x))
            * clamp(sin(a) * 1.5, 0.15, 1.0);
        float offset = TILE * hash(fi + 21.7);
        float slope;

        if (nearDistance(a, phi, trunkPhi(x, base, m, slope), slope) < NEAR) {
            for (int k = k0 - 1; k <= k0 + 1; k++) {
                float x0 = exp(LN_FAR - float(k) * SEG_STEP);
                float x1 = max(exp(LN_FAR - float(k + 1) * SEG_STEP), tipX);
                if (k < 0 || x0 <= tipX) continue;
                vec3 p0 = skyPoint(x0, trunkPhi(x0, base, m, slope));
                vec3 p1 = skyPoint(x1, trunkPhi(x1, base, m, slope));
                vec4 c = segment(d, p0, p1, width, alongAt(x0) + offset);
                if (c.a > 0.0) best = c;
            }
        }

        for (int j = 0; j < BRANCHES; j++) {
            float fj = fi * 7.0 + float(j);
            // A branch leaves the trunk at joinX and grows sunward for a log length.
            float joinX = exp(mix(log(0.25), log(2.0), hash(fj + 11.3)));
            float branchTipX = max(joinX * exp(-1.0 - 0.6 * hash(fj + 13.7)), tipX);
            if (x > joinX * 1.1 || x < branchTipX * 0.9) continue;
            vec3 b = vec3(joinX, (j % 2 == 0 ? 1.0 : -1.0) * (0.25 + 0.2 * hash(fj + 17.9)), TAU * hash(fj + 19.1));
            if (nearDistance(a, phi, branchPhi(x, base, m, b, slope), slope) >= NEAR) continue;
            float out_ = log(joinX) - lnX;
            float branchTaper = smoothstep(0.0, 0.8, lnX - log(branchTipX)) * smoothstep(0.0, 0.15, out_);
            float branchWidth = BRANCH_WIDTH * branchTaper + 0.4 * width * (1.0 - smoothstep(0.0, 0.3, out_));
            float branchOffset = TILE * hash(fj + 23.3);
            for (int k = k0 - 1; k <= k0 + 1; k++) {
                float x0 = min(exp(LN_FAR - float(k) * SEG_STEP), joinX);
                float x1 = max(exp(LN_FAR - float(k + 1) * SEG_STEP), branchTipX);
                if (k < 0 || x0 <= x1) continue;
                vec3 p0 = skyPoint(x0, branchPhi(x0, base, m, b, slope));
                vec3 p1 = skyPoint(x1, branchPhi(x1, base, m, b, slope));
                vec4 c = segment(d, p0, p1, branchWidth, alongAt(x0) + branchOffset);
                if (c.a > 0.0) best = c;
            }
        }
    }

    // The roots come up out of the horizon haze.
    float horizon = smoothstep(-0.04, 0.10, d.y);
    // The fractal sky comes in with the growth; the hole lies on it and the roots on both.
    // The sky and hole are not lit by the vanilla sky.
    vec3 colour = mix(skyColour(d), mix(HOLE_RIM, HOLE_DEEP, 1.0 - a / RING), hole);
    float cover = max(Growth, hole);
    if (best.a > 0.0) {
        colour = best.rgb * Brightness;
        cover = 1.0;
    }
    fragColor = vec4(colour, cover * Presence * horizon);
}
