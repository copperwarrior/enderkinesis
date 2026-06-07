#version 150

// Direct port of vanilla `rendertype_end_portal.fsh` (same palette, same per-layer
// matrix construction, same Sampler0 / Sampler1 sampling). Modifications:
//
//   - Per-pixel 4-octave fBm noise (via uniforms Progress, NoiseDrift, NoiseSeed)
//     replaces the host-side per-corner alpha computation. Pixel-resolution blob
//     edges; no tile-grid artefacts; multi-octave detail down to ~5 px.
//
//   - Four-band visibility model controlled entirely from this shader (band widths
//     are constants here; the host just passes raw Progress):
//
//       depth in [0, SOFTNESS)                                : fade-in (alpha 0→1, blend=0)
//       depth in [SOFTNESS, SOFTNESS+SOLID)                   : solid black (alpha=1, blend=0)
//       depth in [SOFTNESS+SOLID, SOFTNESS+SOLID+PORTAL_FADE) : crossfade (alpha=1, blend 0→1)
//       depth ≥ SOFTNESS+SOLID+PORTAL_FADE                    : full portal (alpha=1, blend=1)
//
//     Where `depth = adjustedProgress - noise`. SOLID is the "black hold" band added
//     so deep cells don't immediately leak into the portal — gives a visible black
//     ring between the blob outline and the portal core.

uniform sampler2D Sampler0;  // end_sky.png — base nebula texture
uniform sampler2D Sampler1;  // end_portal.png — sparse stars sampled per layer

uniform float GameTime;
uniform vec2  ScreenSize;
uniform float Progress;
uniform float SessionTime;  // seconds since the current fade began
uniform vec2  NoiseSeed;

in vec4 texProj0;

out vec4 fragColor;

const int   EndPortalLayers = 15;

// --- Visibility-band widths (in noise-depth units, all on [0,1]) ---
//
// Tuning note: 4-octave fBm clusters pixels around n ≈ 0.5 (std ≈ 0.17), so most of
// the screen falls in a narrow noise range. Each 0.10 of band width sweeps ~22% of
// screen pixels into that band. Keeping the bands tight prevents the SOLID zone
// from swallowing the entire visible blob — at L5 (adjusted ≈ 0.625) the breakdown
// is roughly 14% edge fade / 12% solid black / 22% crossfade / 31% portal / 22%
// transparent. Visible black ring without "whole screen goes black."
const float SOFTNESS    = 0.10;  // outer fade-in (alpha 0 → 1)
const float SOLID       = 0.05;  // solid-black hold ring (alpha = 1, blend = 0)
const float PORTAL_FADE = 0.10;  // crossfade to portal (alpha = 1, blend 0 → 1)
const float TOTAL_BAND  = SOFTNESS + SOLID + PORTAL_FADE;

// --- Noise sampling scale ---
//
// `gl_FragCoord.xy / ScreenSize.y * BASE_FREQ` puts the largest noise feature at
// `screenHeight / BASE_FREQ` pixels. At 8, that's ~135 px on a 1080p screen for
// octave 1 — a handful of large blob regions across the screen rather than a
// uniformly-noisy field. Octave 4 (8× freq) lands at ~17 px, fine wobble detail.
const float BASE_FREQ = 8.0;

// Per-octave drift speed (noise-units / second). Different rates and opposite signs
// so the octaves slide past each other instead of translating uniformly — the local
// sum at any pixel morphs rather than just panning. Low magnitudes for the largest
// octave (still subtle slow shift of the big blobs) ramping up for finer octaves
// (faster wobble).
const vec2 OCTAVE_DRIFT[4] = vec2[](
    vec2( 0.12,  0.09),  // octave 1 — large blobs, moderate drift
    vec2(-0.15,  0.18),  // octave 2
    vec2( 0.21, -0.15),  // octave 3
    vec2(-0.24,  0.30)   // octave 4 — fine detail, fastest morph
);

const vec3 COLORS[16] = vec3[](
    vec3(0.022087, 0.098399, 0.110818),
    vec3(0.011892, 0.095924, 0.089485),
    vec3(0.027636, 0.101689, 0.100326),
    vec3(0.046564, 0.109883, 0.114838),
    vec3(0.064901, 0.117696, 0.097189),
    vec3(0.063761, 0.086895, 0.123646),
    vec3(0.084817, 0.111994, 0.166380),
    vec3(0.097489, 0.154120, 0.091064),
    vec3(0.106152, 0.131144, 0.195191),
    vec3(0.097721, 0.110188, 0.187229),
    vec3(0.133516, 0.138278, 0.148582),
    vec3(0.070006, 0.243332, 0.235792),
    vec3(0.196766, 0.142899, 0.214696),
    vec3(0.047281, 0.315338, 0.321970),
    vec3(0.204675, 0.390010, 0.302066),
    vec3(0.080955, 0.314821, 0.661491)
);

const mat4 SCALE_TRANSLATE = mat4(
    0.5, 0.0, 0.0, 0.25,
    0.0, 0.5, 0.0, 0.25,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
);

mat2 mat2_rotate_z(float a) {
    return mat2(
        cos(a), -sin(a),
        sin(a),  cos(a)
    );
}

mat4 end_portal_layer(float layer) {
    mat4 translate = mat4(
        1.0, 0.0, 0.0, 17.0 / layer,
        0.0, 1.0, 0.0, (2.0 + layer / 1.5) * (GameTime * 1.5),
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    mat2 rotate = mat2_rotate_z(radians((layer * layer * 4321.0 + layer * 9.0) * 2.0));

    mat2 scale = mat2((4.5 - layer / 4.0) * 2.0);

    return mat4(scale * rotate) * translate * SCALE_TRANSLATE;
}

// Inigo Quilez 2D hash. Cheap, uniform enough for visual noise.
float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// Smoothstep-interpolated value noise on the integer grid. C¹-continuous.
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash2(i),                  hash2(i + vec2(1.0, 0.0)), f.x),
        mix(hash2(i + vec2(0.0, 1.0)), hash2(i + vec2(1.0, 1.0)), f.x),
        f.y
    );
}

// Standard 4-octave fBm: amplitude halves while frequency doubles per octave.
// Output normalized to [0, 1]. Per-octave phase offsets so the same noise field
// isn't trivially aligned across scales. Each octave also gets its OWN time-driven
// drift (different rate per octave) — the octaves slide past each other, making
// the local sum at any fixed pixel morph rather than translate uniformly.
float fBm(vec2 p) {
    float sum = 0.0;
    float amp = 1.0;
    float freq = 1.0;
    float norm = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 phase  = vec2(float(i) * 7.3, float(i) * 11.7);
        vec2 octDrift = OCTAVE_DRIFT[i] * SessionTime;
        sum  += vnoise(p * freq + phase + octDrift) * amp;
        norm += amp;
        freq *= 2.0;
        amp  *= 0.5;
    }
    return sum / norm;
}

void main() {
    // Per-pixel noise sample. Height-normalized so density is aspect-independent.
    // NoiseSeed shifts the base position so each session gets a fresh pattern; the
    // per-frame morph happens inside fBm via SessionTime and OCTAVE_DRIFT.
    vec2 noiseCoord = gl_FragCoord.xy / ScreenSize.y * BASE_FREQ + NoiseSeed;
    float n = fBm(noiseCoord);

    // Overshoot raw progress so that at rawProgress = 1 (e.g. void killplane), even
    // the densest noise cells (n ≈ 1) have completed every band — leaves the screen
    // pure portal everywhere at the deepest moment.
    float adjusted = Progress * (1.0 + TOTAL_BAND);
    float depth = adjusted - n;
    if (depth <= 0.0) discard;

    float alpha = clamp(depth / SOFTNESS, 0.0, 1.0);
    float blend = clamp((depth - SOFTNESS - SOLID) / PORTAL_FADE, 0.0, 1.0);

    // Vanilla end-portal colour — identical math, identical textures, identical palette.
    vec3 color = textureProj(Sampler0, texProj0).rgb * COLORS[0];
    for (int i = 0; i < EndPortalLayers; i++) {
        color += textureProj(Sampler1, texProj0 * end_portal_layer(float(i + 1))).rgb * COLORS[i];
    }

    // `mix(vec3(0), color, blend) == color * blend` — collapses to a multiply since
    // black is the zero vector.
    fragColor = vec4(color * blend, alpha);
}
