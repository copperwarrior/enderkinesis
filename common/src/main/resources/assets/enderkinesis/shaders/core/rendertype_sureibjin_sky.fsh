#version 150

// Dream-sky background.
//
// Two layers, blended into the same fragment:
//
//   1. **Cloudlike base**: 4-octave fBm noise over the unit sphere drives a
//      three-stop palette (MID / DARK / LIGHT). Soft smoothsteps so patches
//      read as billowing clouds rather than hard noise blobs.
//
//   2. **Sun nebula**: depth-stacked fBm centred on the sun direction —
//      port of the user's Shadertoy effect. Radial + vertical falloff, with
//      colours pulled from this dim's palette so the glow tonally matches
//      the surrounding clouds instead of slamming in a foreign blue.
//
// The nebula is computed only when the fragment direction faces the sun
// (dot(dir, SunDir) > 0 and the projection onto the sun-perpendicular plane
// is within 0.6) so most of the sky pays no cost for it.

uniform vec4  ColorModulator;
uniform float Time;
uniform vec3  SunDir;

in  vec3 vWorldPos;
out vec4 fragColor;

// Palette — #1f272f / #0f1317 / #344450, sRGB pre-converted. Matches
// SureibjinSky's Kotlin constants and the FogRenderer mixin override.
const vec3 MID   = vec3(0.122, 0.153, 0.184);
const vec3 DARK  = vec3(0.059, 0.075, 0.090);
const vec3 LIGHT = vec3(0.204, 0.267, 0.314);

// Nebula highlight tint — a single notch above LIGHT, staying clearly in
// the palette's cool dark-grey family. (Anything brighter started looking
// like a foreign cyan-blue plate against the rest of the sky.)
const vec3 NEBULA_HIGHLIGHT = vec3(0.34, 0.42, 0.50);

// Per-component drift speeds for the cloud base.
const vec3 DRIFT_DARK  = vec3(0.00425, 0.00210, 0.00250);
const vec3 DRIFT_LIGHT = vec3(0.00150, 0.00310, 0.00375);

const float NOISE_SCALE_DARK  = 2.4;
const float NOISE_SCALE_LIGHT = 1.7;

// ---------------------------------------------------------------- noise

float hash3(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a000 = hash3(i);
    float a100 = hash3(i + vec3(1.0, 0.0, 0.0));
    float a010 = hash3(i + vec3(0.0, 1.0, 0.0));
    float a110 = hash3(i + vec3(1.0, 1.0, 0.0));
    float a001 = hash3(i + vec3(0.0, 0.0, 1.0));
    float a101 = hash3(i + vec3(1.0, 0.0, 1.0));
    float a011 = hash3(i + vec3(0.0, 1.0, 1.0));
    float a111 = hash3(i + vec3(1.0, 1.0, 1.0));
    return mix(
        mix(mix(a000, a100, f.x), mix(a010, a110, f.x), f.y),
        mix(mix(a001, a101, f.x), mix(a011, a111, f.x), f.y),
        f.z
    );
}

float fbm3(vec3 p) {
    float n = 0.0;
    float amp = 1.0;
    float freq = 1.0;
    for (int i = 0; i < 4; i++) {
        n += vnoise3(p * freq) * amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    return n / 1.875;
}

// ------------------------------------------------------ nebula helpers
// Direct ports of the user's Shadertoy primitives.

float n21(vec3 uvw) {
    return fract(sin(uvw.x * 23.35661 + uvw.y * 6560.65 + uvw.z * 4624.165) * 2459.452);
}

float smoothNoise(vec3 uvw) {
    vec3 fl = floor(uvw);
    float fbl = n21(fl);
    float fbr = n21(vec3(1.0, 0.0, 0.0) + fl);
    float ful = n21(vec3(0.0, 1.0, 0.0) + fl);
    float fur = n21(vec3(1.0, 1.0, 0.0) + fl);
    float bbl = n21(vec3(0.0, 0.0, 1.0) + fl);
    float bbr = n21(vec3(1.0, 0.0, 1.0) + fl);
    float bul = n21(vec3(0.0, 1.0, 1.0) + fl);
    float bur = n21(vec3(1.0, 1.0, 1.0) + fl);

    vec3 f = fract(uvw);
    vec3 blend = f * f * (3.0 - 2.0 * f);

    return mix(
        mix(mix(fbl, fbr, blend.x), mix(ful, fur, blend.x), blend.y),
        mix(mix(bbl, bbr, blend.x), mix(bul, bur, blend.x), blend.y),
        blend.z
    );
}

float perlinNoise(vec3 uvw) {
    float blended = smoothNoise(uvw * 4.0);
    blended += smoothNoise(uvw * 8.0)  * 0.5;
    blended += smoothNoise(uvw * 16.0) * 0.25;
    blended += smoothNoise(uvw * 32.0) * 0.125;
    blended += smoothNoise(uvw * 64.0) * 0.0625;
    blended /= 2.0;
    blended *= pow(max(0.0, 0.8 - abs(uvw.y)), 2.0);
    return blended;
}

// --------------------------------------------------------------- main

void main() {
    vec3 dir = normalize(vWorldPos);

    // 1. Base cloud noise — same MID/DARK/LIGHT mix patterns as the rest of
    //    the sky. These are the cloud SHAPES; the nebula modulates how much
    //    LIGHT bleeds through them in the sun region rather than overlaying
    //    its own coloured shape.
    float nDark  = fbm3(dir * NOISE_SCALE_DARK  + Time * DRIFT_DARK);
    float nLight = fbm3(dir * NOISE_SCALE_LIGHT + Time * DRIFT_LIGHT);
    float dMix = smoothstep(0.35, 0.60, nDark)  * 0.9;
    float lMix = smoothstep(0.38, 0.62, nLight) * 0.85;

    // 2. EXPERIMENT: same depth-stacked perlin field, but evaluated across
    //    the ENTIRE skybox instead of being gated to the sun-facing
    //    hemisphere. The 2D projection onto the sun-perpendicular plane
    //    folds at the antipodal point (length(sunUv) collapses back to 0
    //    when dotSun → −1), so you'll see the noise pattern roughly mirror
    //    around the anti-sun direction — that's a natural side-effect of
    //    the projection, useful for evaluating whether the field reads
    //    well as global cloud structure.
    vec3 sunRight = normalize(cross(vec3(0.0, 1.0, 0.0), SunDir));
    vec3 sunUp = cross(SunDir, sunRight);
    vec2 sunUv = vec2(dot(dir, sunRight), dot(dir, sunUp));

    vec3 nebulaUvw = vec3(sunUv, Time * 0.007);
    float result = 0.0;
    float moreDepth = 0.0;
    for (int i = 0; i < 20; i++) {
        moreDepth += 0.008;
        result += perlinNoise(
            nebulaUvw * vec3(vec2(moreDepth * 12.0 + 1.0), 1.0) +
            vec3(0.0, 0.0, moreDepth)
        );
    }
    result /= 14.0;
    float nebulaIntensity = clamp(result, 0.0, 1.0);

    // The nebula does two things, both inside the existing palette:
    //  - It pushes lMix higher in nebula-bright fragments → the LIGHT
    //    portion of the cloud pattern bleeds in.
    //  - It nudges the final colour toward NEBULA_HIGHLIGHT, which is one
    //    step brighter than LIGHT, capped well short of white.
    float boostedLMix = clamp(lMix + nebulaIntensity * 5.0, 0.0, 1.0);

    vec3 col = mix(MID, DARK, dMix);
    col = mix(col, LIGHT, boostedLMix);
    col = mix(col, NEBULA_HIGHLIGHT, nebulaIntensity * 1.5);

    fragColor = vec4(col, 1.0) * ColorModulator;
}
