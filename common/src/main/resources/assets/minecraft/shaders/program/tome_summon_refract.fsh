#version 150

// Tome-summon screen-space refraction pass. Copied from scrying_refract.fsh so the
// two effects can evolve independently — kept structurally identical (per-cell
// Voronoi refraction + yellow tendril overlay) but adds an `Intensity` uniform
// that scales BOTH the displacement strength AND the tendril alpha, so the host
// (TomeSummonOverlay) can ramp the effect in over the book-opening animation
// and back out after the book is taken / closed.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float SessionTime;
// 0..1 envelope from the host. At 0 the pass is a no-op pass-through.
uniform float Intensity;

in vec2 texCoord;
out vec4 fragColor;

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
    for (int i = 0; i < 3; i++) {
        sum  += vnoise(p * freq) * amp;
        norm += amp;
        freq *= 2.0;
        amp  *= 0.5;
    }
    return sum / norm;
}

vec2 fBm2(vec2 p) {
    return vec2(fBm(p), fBm(p + vec2(43.7, 17.3)));
}

vec2 worleyF1F2(vec2 p, float t, out vec2 outNearestCell) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float f1 = 1e10;
    float f2 = 1e10;
    outNearestCell = vec2(0.0);
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 g = vec2(float(x), float(y));
            vec2 cellId = i + g;
            vec2 baseOff = vec2(hash2(cellId), hash2(cellId + vec2(1.7, 9.4)));
            vec2 anim = 0.5 + 0.5 * sin(t * 0.35 + 6.2831 * baseOff);
            vec2 r = g + anim - f;
            float d = dot(r, r);
            if (d < f1) { f2 = f1; f1 = d; outNearestCell = cellId; }
            else if (d < f2) { f2 = d; }
        }
    }
    return vec2(sqrt(f1), sqrt(f2));
}

const float NOODLE_FREQ        = 5.0;
const float NOODLE_BAND        = 0.18;
const float NOODLE_WARP_FREQ   = 3.5;
const float NOODLE_WARP_AMP    = 0.11;
const float NOODLE_WARP_REMAP  = 3.0;

const float DISPLACEMENT_STRENGTH = 0.07;
// Refraction is zero through the inner reading area and grows over the
// MASK_INNER..MASK_OUTER band so the warp is confined to the periphery —
// same screen region the vignette wall sits on. Corners (r ≈ 0.7 on a 16:9
// screen) sit well past MASK_OUTER and are at full refraction.
const float MASK_INNER            = 0.38;
const float MASK_OUTER            = 0.55;

const float CELL_MAG_MIN     = 0.45;
const float CELL_MAG_MAX     = 1.00;
const float CELL_ROT_SPEED   = 0.12;

const vec3  TENDRIL_COLOR     = vec3(0.831, 0.702, 0.282);
const float TENDRIL_ALPHA_MAX = 0.55;

void main() {
    vec2 uv = texCoord - 0.5;
    float r = length(uv);
    // Refraction lives ONLY in the periphery band [MASK_INNER, MASK_OUTER].
    // Inside MASK_INNER the mask is exactly zero (reading area is clean);
    // past MASK_OUTER it's at full strength (corners are saturated).
    float radialMask = smoothstep(MASK_INNER, MASK_OUTER, r);
    radialMask = pow(radialMask, 1.3);

    float t = SessionTime;

    vec2 warp1 = ((fBm2(uv * NOODLE_WARP_FREQ) - 0.5) * NOODLE_WARP_REMAP) * NOODLE_WARP_AMP;
    vec2 warp2 = ((fBm2((uv + warp1) * NOODLE_WARP_FREQ * 2.3) - 0.5) * NOODLE_WARP_REMAP)
               * NOODLE_WARP_AMP * 0.45;
    vec2 noodleUV = uv + warp1 + warp2;

    vec2 nearestCell;
    vec2 ff = worleyF1F2(noodleUV * NOODLE_FREQ, t, nearestCell);
    float edgeDist = ff.y - ff.x;
    float tendril = 1.0 - smoothstep(0.0, NOODLE_BAND, edgeDist);
    tendril = pow(tendril, 1.4);

    float angHash = hash2(nearestCell + vec2(3.1, 7.9));
    float magHash = hash2(nearestCell + vec2(11.3, 5.7));
    float cellAngle = angHash * 6.2831 + t * CELL_ROT_SPEED * (magHash - 0.5);
    vec2 dispDir = vec2(cos(cellAngle), sin(cellAngle))
                 * mix(CELL_MAG_MIN, CELL_MAG_MAX, magHash);

    // Intensity scales the WORLD displacement so the world ripples harder
    // as the overlay fades in. At Intensity=0 we sample the unrefracted scene.
    float strength = DISPLACEMENT_STRENGTH * radialMask * Intensity;

    vec2 sampleUV = clamp(texCoord + dispDir * strength, vec2(0.001), vec2(0.999));
    vec3 refractedScene = texture(DiffuseSampler, sampleUV).rgb;

    // Intensity also scales the visible tendril overlay so the yellow Voronoi
    // edges come and go with the rest of the effect.
    float tendrilAlpha = tendril * TENDRIL_ALPHA_MAX * radialMask * Intensity;
    vec3 finalColor = mix(refractedScene, TENDRIL_COLOR, tendrilAlpha);

    fragColor = vec4(finalColor, 1.0);
}
