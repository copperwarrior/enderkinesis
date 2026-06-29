#version 150

// Radial vignette with fBm-noise-perturbed edge — threshold-perturbation variant.
//
// The yellow Voronoi tendrils that used to live here moved into the PostChain
// refract shader (scrying_refract.fsh) so they share a single time uniform with the
// refraction warps and stay frame-perfect synced. This pass is now JUST the brown
// vignette wall: radial gradient + noise-wobbled boundary + subtle colour variation.
//
// Approach references:
//
//   • Domain warping (Inigo Quilez, "Warp": https://iquilezles.org/articles/warp/) is
//     the usual technique for organic noise edges — bend the UV before computing the
//     iso-curves. Too aggressive an amplitude wipes out the radial structure entirely;
//     too low and the silhouette stays visibly round. Domain warping deforms the iso-
//     curves themselves — push it too far and there's no longer a coherent darker-
//     toward-the-edges trend.
//
//   • The cleaner approach for a VIGNETTE specifically (where the radial structure is
//     load-bearing) is **threshold perturbation**: compute the radial distance with
//     the normal aspect-corrected metric, then sample noise at the SAME UV and use it
//     to wobble the inner/outer radii of the gradient ramp. The radial gradient stays
//     intact pixel-for-pixel — closer to centre is always less opaque than farther
//     from centre, monotonically — and the BOUNDARY between transparent and opaque is
//     the part that ripples.
//
//   • Minkowski p-norm (van Walree, "Vignetting and Lens Shading"): with
//     `dist = pow(|x|^p + |y|^p, 1/p)` and per-axis-normalised UV, p=4 gives rounded-
//     rectangle iso-curves that fit a rectangular screen evenly — all four mid-edges
//     sit at the same `0.5` distance from centre. p=2 (Euclidean) leaves left/right
//     at `aspect/2 = 0.89` vs top/bottom at `0.5`, which reads as "more wall on the
//     sides".
//
//   • Cos⁴(θ) natural-vignette falloff approximated by a polynomial `pow(t, n)` with
//     n ≈ 1.8 — soft inner shoulder, faster corner darkening.

uniform vec2  ScreenSize;
uniform float SessionTime;

out vec4 fragColor;

// Base brown #392F2B in 0..1 sRGB.
const vec3 VIGNETTE_COLOR = vec3(0.224, 0.184, 0.169);

// Minkowski distance exponent — rounded-rectangle iso-curves at p=4, fits a
// rectangular screen evenly.
const float DIST_P     = 4.0;
const float DIST_P_INV = 1.0 / DIST_P;

// Base inner/outer radii in normalized [-0.5, 0.5] UV. With p=4 a mid-edge is at 0.5
// and a corner at ~0.595. CLEAR is well inside, OPAQUE just at the mid-edge so corners
// pull deeper past the gradient ramp.
const float CLEAR_RADIUS  = 0.22;
const float OPAQUE_RADIUS = 0.50;

// Polynomial falloff exponent (1.8 ≈ photographic cos⁴ shape).
const float FALLOFF_POW = 1.8;

// Alpha ceiling — corners never reach full opacity, the brown stays translucent.
const float MAX_ALPHA = 0.78;

// EDGE_REMAP * fBm-offset amplifies the typical narrow fBm spread (~±0.15) up to the
// useful ±0.5 range; * _AMP scales that to a usable radial-units displacement.
const float EDGE_FREQ   = 2.4;
const float EDGE_AMP    = 0.085;
const float EDGE_REMAP  = 3.2;

const float DETAIL_FREQ  = 8.5;
const float DETAIL_AMP   = 0.028;
const float DETAIL_REMAP = 2.6;

// Per-octave drift velocities — different signs and magnitudes so the octaves slide
// past each other and the local sum at any pixel morphs rather than translating.
const vec2 OCTAVE_DRIFT[4] = vec2[](
    vec2( 0.07,  0.05),
    vec2(-0.09,  0.11),
    vec2( 0.13, -0.10),
    vec2(-0.15,  0.18)
);

// Subtle colour variation across the vignette wall (±12% luminance).
const float COLOR_VAR_AMP  = 0.12;
const float COLOR_VAR_FREQ = 2.2;

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

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

float fBm(vec2 p) {
    float sum  = 0.0;
    float amp  = 1.0;
    float freq = 1.0;
    float norm = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 phase    = vec2(float(i) * 7.3, float(i) * 11.7);
        vec2 octDrift = OCTAVE_DRIFT[i] * SessionTime;
        sum  += vnoise(p * freq + phase + octDrift) * amp;
        norm += amp;
        freq *= 2.0;
        amp  *= 0.5;
    }
    return sum / norm;
}

void main() {
    // Per-axis normalised UV so both axes span [-0.5, 0.5].
    vec2 uv = (gl_FragCoord.xy / ScreenSize) - 0.5;

    // Pure Minkowski-p4 radial distance — UNTOUCHED by noise.
    float dist = pow(pow(abs(uv.x), DIST_P) + pow(abs(uv.y), DIST_P), DIST_P_INV);

    // Two-scale noise field sampled at the same UV — wobbles the boundary in/out.
    float edgeN   = (fBm(uv * EDGE_FREQ)   - 0.5) * EDGE_REMAP   * EDGE_AMP;
    float detailN = (fBm(uv * DETAIL_FREQ) - 0.5) * DETAIL_REMAP * DETAIL_AMP;
    float boundaryShift = edgeN + detailN;

    // Shift BOTH ramp radii by the same noise — keeps ramp width constant so the
    // falloff steepness doesn't flicker; only the gradient ring's position moves.
    float clearR  = CLEAR_RADIUS  + boundaryShift;
    float opaqueR = OPAQUE_RADIUS + boundaryShift;

    float t = clamp((dist - clearR) / (opaqueR - clearR), 0.0, 1.0);
    float alpha = pow(t, FALLOFF_POW) * MAX_ALPHA;

    // Subtle luminance variation on the base brown so the wall doesn't read as flat.
    float colorMod = mix(1.0 - COLOR_VAR_AMP, 1.0 + COLOR_VAR_AMP, fBm(uv * COLOR_VAR_FREQ));
    vec3 color = VIGNETTE_COLOR * colorMod;

    fragColor = vec4(color, alpha);
}
