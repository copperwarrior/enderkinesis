#version 150

// Scrying-orb screen-space refraction pass — refraction AND visible Voronoi tendrils
// both rendered here in a single shader so they share the same per-pixel noise
// computation. Previously the tendrils lived in the vignette shader (GUI pass) and only
// the refraction lived here (PostChain pass), which meant:
//
//   - the two were driven by different time uniforms (PostChain shaders don't auto-set
//     GameTime; the vignette had its own SessionTime via direct shader access),
//   - and even after we equalised the units, the per-frame timing landed off by one
//     frame because Kotlin set the PostChain uniform from RENDER_HUD AFTER the world
//     pass had already run.
//
// Co-locating them eliminates both issues: the Worley cells, the warps, and the tendril
// alpha are all computed from one `t` so the visible tendrils and the underlying world
// displacement are pixel-perfect synced.
//
// Pipeline order is unchanged: PostChain runs between world render and GUI render. This
// shader reads `DiffuseSampler` (the rendered world), computes Voronoi-modulated
// displacement, samples the world at the displaced UV, then OVER-composites the yellow
// tendrils on top. The GUI vignette + SGA glyphs draw on top of THAT in the GUI pass.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
// Set explicitly each frame from ScryingClient (vanilla's PostPass.process only auto-
// pushes Time and ScreenSize, no GameTime equivalent). Seconds since beginScrying().
uniform float SessionTime;

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

// Worley/Voronoi: distance to nearest (F1) and second-nearest (F2) feature point in the
// 3x3 cell neighbourhood, plus the cell ID of the nearest feature point. The cell ID is
// used downstream as a stable per-cell seed so each Voronoi cell carries its own
// refraction character (direction + magnitude). Feature points orbit at ~0.35 rad/s —
// previously 0.55, dialled down because the per-cell refraction now makes the cell
// motion much more visually obvious and the old speed read as too busy.
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

// -- Constants ----------------------------------------------------------------------
const float NOODLE_FREQ        = 5.0;
const float NOODLE_BAND        = 0.18;
const float NOODLE_WARP_FREQ   = 3.5;
const float NOODLE_WARP_AMP    = 0.11;
const float NOODLE_WARP_REMAP  = 3.0;

const float DISPLACEMENT_STRENGTH = 0.07;
const float MASK_OUTER            = 0.48;

// Per-cell magnitude varies between MIN and MAX so some cells refract harder than
// others. Each cell's direction also drifts at a slow per-cell rate (different sign
// and magnitude per cell), giving a "lensing" motion that's hidden by the bright
// tendrils sitting on the cell boundaries.
const float CELL_MAG_MIN     = 0.45;
const float CELL_MAG_MAX     = 1.00;
const float CELL_ROT_SPEED   = 0.12;

// Warm yellow #D4B348 — the tendril colour. Same value the vignette shader used before
// the consolidation, so the visual hue is unchanged.
const vec3  TENDRIL_COLOR     = vec3(0.831, 0.702, 0.282);
// Peak tendril opacity on top of the refracted scene. Lower than the previous vignette
// 0.32 because here the tendrils sit over a moving scene rather than a flat brown wall;
// 0.55 reads as a punchy glow without washing the world out.
const float TENDRIL_ALPHA_MAX = 0.55;

void main() {
    vec2 uv = texCoord - 0.5;
    float r = length(uv);
    float radialMask = 1.0 - smoothstep(0.0, MASK_OUTER, r);
    radialMask = pow(radialMask, 1.3);

    float t = SessionTime;

    // --- Iterated domain warp (Quilez) -----------------------------------------------
    vec2 warp1 = ((fBm2(uv * NOODLE_WARP_FREQ) - 0.5) * NOODLE_WARP_REMAP) * NOODLE_WARP_AMP;
    vec2 warp2 = ((fBm2((uv + warp1) * NOODLE_WARP_FREQ * 2.3) - 0.5) * NOODLE_WARP_REMAP)
               * NOODLE_WARP_AMP * 0.45;
    vec2 noodleUV = uv + warp1 + warp2;

    // --- Single Worley sample used for BOTH the tendril overlay AND the per-cell
    // refraction below. nearestCell is the (integer) cell ID of the cell THIS PIXEL
    // belongs to; every pixel inside the same cell therefore reads the same hashed
    // direction below, which is the whole point — refraction lives IN the cell, not
    // along the borders.
    vec2 nearestCell;
    vec2 ff = worleyF1F2(noodleUV * NOODLE_FREQ, t, nearestCell);
    float edgeDist = ff.y - ff.x;
    float tendril = 1.0 - smoothstep(0.0, NOODLE_BAND, edgeDist);
    tendril = pow(tendril, 1.4);

    // --- Per-cell refraction: hash the cell ID into a stable angle and magnitude.
    // The cell's direction slowly rotates at a per-cell rate so the lenses drift
    // rather than snap. Cell magnitude varies so some cells lens harder than others.
    // The hard step at cell boundaries (where adjacent cells push in different
    // directions) sits exactly under the bright yellow tendril, so the visible
    // discontinuity is hidden by the overlay.
    float angHash = hash2(nearestCell + vec2(3.1, 7.9));
    float magHash = hash2(nearestCell + vec2(11.3, 5.7));
    float cellAngle = angHash * 6.2831 + t * CELL_ROT_SPEED * (magHash - 0.5);
    vec2 dispDir = vec2(cos(cellAngle), sin(cellAngle))
                 * mix(CELL_MAG_MIN, CELL_MAG_MAX, magHash);

    float strength = DISPLACEMENT_STRENGTH * radialMask;

    // Sample the refracted scene.
    vec2 sampleUV = clamp(texCoord + dispDir * strength, vec2(0.001), vec2(0.999));
    vec3 refractedScene = texture(DiffuseSampler, sampleUV).rgb;

    // OVER-composite the yellow tendril on top. The tendril value modulates alpha so we
    // only colour the actual Voronoi edges; cell interiors pass the refracted scene
    // through untouched. Radial mask kills the overlay near the vignette wall so the
    // brown layer the GUI vignette draws doesn't sit on top of half-glowing tendrils.
    float tendrilAlpha = tendril * TENDRIL_ALPHA_MAX * radialMask;
    vec3 finalColor = mix(refractedScene, TENDRIL_COLOR, tendrilAlpha);

    fragColor = vec4(finalColor, 1.0);
}
