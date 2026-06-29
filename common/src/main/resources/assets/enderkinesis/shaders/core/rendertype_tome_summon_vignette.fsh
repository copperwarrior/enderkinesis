#version 150

// Radial vignette + fBm-perturbed boundary, copied from
// rendertype_scrying_vignette.fsh as an independent shader so the two
// overlays can evolve separately without one accidentally changing the
// other's look. The only structural difference is the Intensity uniform:
// it scales both the boundary warble amplitude AND the final alpha, which
// lets the host fade the whole vignette in (with the warble "winding up"
// alongside) and back out.
//
// Threshold-perturbation rationale, Minkowski p-norm choice and cos⁴
// polynomial falloff are described in detail in the scrying shader's
// header; not duplicated here.

uniform vec2  ScreenSize;
uniform float SessionTime;
uniform float Intensity;

out vec4 fragColor;

// Base brown #392F2B in 0..1 sRGB.
const vec3 VIGNETTE_COLOR = vec3(0.224, 0.184, 0.169);

// Minkowski distance exponent — rounded-rectangle iso-curves at p=4.
const float DIST_P     = 4.0;
const float DIST_P_INV = 1.0 / DIST_P;

const float CLEAR_RADIUS  = 0.22;
const float OPAQUE_RADIUS = 0.50;

const float FALLOFF_POW = 1.8;

// Alpha ceiling — corners never reach full opacity; the brown stays translucent.
const float MAX_ALPHA = 0.78;

const float EDGE_FREQ   = 2.4;
const float EDGE_AMP    = 0.085;
const float EDGE_REMAP  = 3.2;

const float DETAIL_FREQ  = 8.5;
const float DETAIL_AMP   = 0.028;
const float DETAIL_REMAP = 2.6;

const vec2 OCTAVE_DRIFT[4] = vec2[](
    vec2( 0.07,  0.05),
    vec2(-0.09,  0.11),
    vec2( 0.13, -0.10),
    vec2(-0.15,  0.18)
);

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
    vec2 uv = (gl_FragCoord.xy / ScreenSize) - 0.5;

    float dist = pow(pow(abs(uv.x), DIST_P) + pow(abs(uv.y), DIST_P), DIST_P_INV);

    float edgeN   = (fBm(uv * EDGE_FREQ)   - 0.5) * EDGE_REMAP   * EDGE_AMP;
    float detailN = (fBm(uv * DETAIL_FREQ) - 0.5) * DETAIL_REMAP * DETAIL_AMP;
    // Warble amplitude scales with the envelope: a "winding up" effect during
    // a fade-in, perfectly still boundary at Intensity=0.
    float boundaryShift = (edgeN + detailN) * Intensity;

    float clearR  = CLEAR_RADIUS  + boundaryShift;
    float opaqueR = OPAQUE_RADIUS + boundaryShift;

    float t = clamp((dist - clearR) / (opaqueR - clearR), 0.0, 1.0);
    float alpha = pow(t, FALLOFF_POW) * MAX_ALPHA * Intensity;

    float colorMod = mix(1.0 - COLOR_VAR_AMP, 1.0 + COLOR_VAR_AMP, fBm(uv * COLOR_VAR_FREQ));
    vec3 color = VIGNETTE_COLOR * colorMod;

    fragColor = vec4(color, alpha);
}
