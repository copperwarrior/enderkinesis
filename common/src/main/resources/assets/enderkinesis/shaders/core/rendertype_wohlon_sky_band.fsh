#version 150

// Aurora-like galactic band on a great circle from the NE horizon to the SW horizon.
// Additive: a dust lane only removes band light, it does not darken the sky.

uniform vec4 ColorModulator;
// Seconds; the host keeps it bounded for float precision.
uniform float Time;
uniform float Strength;
// BandAxisA and BandAxisB span the band plane; BandNormal is perpendicular to it.
uniform vec3 BandAxisA;
uniform vec3 BandAxisB;
uniform vec3 BandNormal;

in vec3 vDir;
out vec4 fragColor;

const vec3 DEEP = vec3(0.10, 0.30, 0.36);
const vec3 TEAL = vec3(0.20, 0.52, 0.58);
const vec3 FIREFLY = vec3(0.557, 0.831, 0.784);
const vec3 STAR = vec3(0.92, 0.95, 1.0);

// Angular half-widths, as sine of the angle from the band plane.
const float CORE_WIDTH = 0.13;
const float GLOW_WIDTH = 0.36;
const float LANE_WIDTH = 0.08;
const float INTENSITY = 0.55;
// Stars: one possible star per cell, sized and lit like the vanilla star quads.
const float STAR_GRID = 140.0;
const float STAR_CHANCE = 0.012;
const vec3 UP = vec3(0.0, 1.0, 0.0);

// Gradient noise: value noise showed its lattice as cells across the band.
vec3 hash33(vec3 p) {
    p = fract(p * vec3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx) * 2.0 - 1.0;
}

float gnoise(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    // Quintic fade: continuous second derivative, so no creases along cell edges.
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n000 = dot(hash33(i), f);
    float n100 = dot(hash33(i + vec3(1.0, 0.0, 0.0)), f - vec3(1.0, 0.0, 0.0));
    float n010 = dot(hash33(i + vec3(0.0, 1.0, 0.0)), f - vec3(0.0, 1.0, 0.0));
    float n110 = dot(hash33(i + vec3(1.0, 1.0, 0.0)), f - vec3(1.0, 1.0, 0.0));
    float n001 = dot(hash33(i + vec3(0.0, 0.0, 1.0)), f - vec3(0.0, 0.0, 1.0));
    float n101 = dot(hash33(i + vec3(1.0, 0.0, 1.0)), f - vec3(1.0, 0.0, 1.0));
    float n011 = dot(hash33(i + vec3(0.0, 1.0, 1.0)), f - vec3(0.0, 1.0, 1.0));
    float n111 = dot(hash33(i + vec3(1.0, 1.0, 1.0)), f - vec3(1.0, 1.0, 1.0));
    float x0 = mix(mix(n000, n100, u.x), mix(n010, n110, u.x), u.y);
    float x1 = mix(mix(n001, n101, u.x), mix(n011, n111, u.x), u.y);
    return mix(x0, x1, u.z) * 0.5 + 0.5;
}

// Each octave turns the domain, so no octave's lattice lines up with the next.
const mat3 OCTAVE_TURN = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

float fbm(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += amp * gnoise(p);
        p = OCTAVE_TURN * p * 2.03;
        amp *= 0.5;
    }
    // Gradient fBm sits closer to 0.5 than value fBm; widen it, so the band thresholds keep their look.
    return clamp((sum / 0.9375 - 0.5) * 1.3 + 0.5, 0.0, 1.0);
}

void main() {
    vec3 d = normalize(vDir);
    float horizon = smoothstep(-0.03, 0.18, d.y);
    if (horizon <= 0.0 || Strength <= 0.0) discard;

    float along = atan(dot(d, BandAxisB), dot(d, BandAxisA));
    // The centre line meanders far across the sky. Whole-number frequencies and noise on the circle
    // keep the curve continuous where `along` wraps.
    vec2 ring = vec2(cos(along), sin(along));
    float wander = 0.20 * sin(along + Time * 0.012)
        + 0.12 * sin(2.0 * along - Time * 0.02 + 1.7)
        + 0.07 * sin(3.0 * along + Time * 0.03 + 4.2)
        + 0.04 * sin(5.0 * along - Time * 0.045 + 0.6)
        + 0.02 * sin(9.0 * along + Time * 0.07 + 2.9)
        + 0.18 * (fbm(vec3(ring * 1.4, Time * 0.004 + 3.1)) - 0.5);
    float x = dot(d, BandNormal) - wander;
    float core = exp(-x * x / (CORE_WIDTH * CORE_WIDTH));
    float glow = exp(-x * x / (GLOW_WIDTH * GLOW_WIDTH));

    float n = fbm(d * 2.2 + vec3(Time * 0.004, 0.0, -Time * 0.003));
    float fine = fbm(d * 6.0 + vec3(0.0, Time * 0.006, 0.0));
    float rays = 0.6 + 0.4 * sin(along * 60.0 + fbm(d * 4.0) * 6.0 + Time * 0.3);
    float lane = smoothstep(0.4, 0.85, fbm(d * 4.5 + 11.0)) * exp(-x * x / (LANE_WIDTH * LANE_WIDTH));

    float body = core * (0.75 + 0.35 * n) * mix(1.0, rays, 0.15) + glow * (0.2 + 0.15 * n);
    body *= 1.0 - 0.45 * lane;
    vec3 colour = mix(TEAL, FIREFLY, smoothstep(0.25, 0.9, n * core + fine * 0.2));
    colour = mix(DEEP, colour, smoothstep(0.0, 0.6, core + 0.2));

    // Extra stars inside the band glow; the vanilla star field covers the rest of the sky.
    vec3 g = d * STAR_GRID;
    vec3 cell = floor(g);
    vec3 h = hash33(cell) * 0.5 + 0.5;
    float star = 0.0;
    if (h.x < STAR_CHANCE * glow) {
        // A small square in a tangent frame, like a vanilla star quad.
        vec3 v = g - (cell + 0.5 + (h - 0.5) * 0.5);
        vec3 e1 = normalize(cross(d, abs(d.y) > 0.99 ? vec3(1.0, 0.0, 0.0) : UP));
        vec3 e2 = cross(d, e1);
        float size = mix(0.15, 0.25, h.y);
        float edge = max(abs(dot(v, e1)), abs(dot(v, e2)));
        star = (1.0 - smoothstep(size - 0.04, size, edge)) * mix(0.5, 0.9, h.z);
    }

    vec3 emitted = colour * body * INTENSITY + STAR * star;
    fragColor = vec4(emitted * Strength * horizon, 1.0) * ColorModulator;
}
