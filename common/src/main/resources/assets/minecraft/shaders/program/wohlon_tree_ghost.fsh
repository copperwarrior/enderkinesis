#version 150

uniform sampler2D DepthSampler;
// Roots, wood and leaves: signed margin to the surface, 128 at the surface, 16 for each block, inside above (WohlonTreeGhost).
uniform sampler3D Margin;
// Tiling 64³ fBm (WohlonNoiseTexture.id3D): the dispersal pattern, and the leaf warp.
uniform sampler3D Warp;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Grid space: one unit is one block and one voxel, the grid corner at the origin.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
// The voxel grid's size; the box reaches past it so the fog can come in from the air around.
uniform vec3 GridSize;
uniform float Strength;
// The Heart in grid space, and the distance from it to the farthest grid corner.
uniform vec3 Heart;
uniform float Reach;
// 0 to 1: how far the ghost has dispersed in, and then out.
uniform vec2 Dispersal;
// Out-dispersal shape: share of the order from the distance to the Heart (the rest is noise), and the edge width.
uniform vec2 OutShape;
// Seconds; the host keeps it bounded for float precision.
uniform float Time;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 72;
// Shaded like the ritual root tendrils: a dark, lightly absorbing core and a faint glowing rim.
const float DENSITY = 0.3;
const float OPACITY = 0.5;
const float CORE_SHADE = 0.03;
const float RIM_EMISSION = 0.15;
const float RIM_WIDTH = 0.6;
const float SURFACE_SOFT = 0.6;
// Width of the dispersal edge, as a share of the noise range: larger is a softer, wispier edge.
const float DISPERSE_SOFT = 0.25;
const float DISPERSE_TILE = 40.0;
// Blocks of leaf warp, and the world size of one noise tile.
const float LEAF_WARP = 1.5;
const float WARP_TILE = 24.0;
// Dispersing in, the fog is drawn in from the air: blocks it starts out from the shape, blocks of curl
// on the way, and extra edge softness while it is still far out.
const float PULL_DIST = 8.0;
const float PULL_SWIRL = 3.0;
const float PULL_SOFT = 2.5;
// The tendril colours: cyan at the Heart, firefly green out at the roots.
const vec3 HEART_COLOUR = vec3(0.0, 1.0, 1.0);
const vec3 ROOT_COLOUR = vec3(0.557, 0.831, 0.784);

void main() {
    vec2 texCoord = gl_FragCoord.xy / ScreenSize;
    vec3 toFar = localPos - CamLocal;
    float tFar = length(toFar);
    vec3 dir = toFar / tFar;
    vec3 inv = 1.0 / dir;
    vec3 ta = (BoxMin - CamLocal) * inv;
    vec3 tb = (BoxMax - CamLocal) * inv;
    vec3 tMin = min(ta, tb);
    float t0 = max(max(max(tMin.x, tMin.y), tMin.z), 0.0);
    float t1 = tFar;
    float depth = texture(DepthSampler, texCoord).r;
    if (depth < 1.0) {
        vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
        t1 = min(t1, length(scene.xyz / scene.w));
    }
    if (t1 <= t0) discard;

    float dt = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        float r = length(p - Heart);
        // Dispersal: each point comes in when Dispersal.x passes its noise value, and goes out when
        // Dispersal.y passes a key of noise and distance from the Heart, so the Heart clears first.
        float n = clamp((texture(Warp, p / DISPERSE_TILE).r * 0.65 + texture(Warp, p / (DISPERSE_TILE * 0.3) + 0.53).r * 0.35 - 0.5) * 1.8 + 0.5, 0.0, 1.0);
        float shownIn = smoothstep(n - DISPERSE_SOFT, n, Dispersal.x * (1.0 + DISPERSE_SOFT));
        float outKey = mix(n, clamp(r / Reach, 0.0, 1.0), OutShape.x);
        float kept = 1.0 - smoothstep(outKey - OutShape.y, outKey, Dispersal.y * (1.0 + OutShape.y));
        float live = shownIn * kept;
        if (live <= 0.0) continue;
        float pull = 1.0 - Dispersal.x;
        // Settled fog with no tree here: skip the warp and noise reads, most of the box is empty. The field
        // stops at -2 blocks, so this tests for that floor; the leaf warp may lose only a sliver of faint rim.
        if (pull <= 0.0) {
            vec3 m0 = texture(Margin, p / GridSize).rgb * 255.0;
            if (max(max(m0.r, m0.g), m0.b) < 128.0 - 1.9 * 16.0) continue;
        }
        vec3 w = p / WARP_TILE;
        vec3 warp = vec3(texture(Warp, w).r, texture(Warp, w + 0.37).r, texture(Warp, w + 0.71).r) * 2.0 - 1.0;
        // Fog here now is fog that will settle further in: sample the shape back toward the Heart, by
        // less as it arrives, so the mist moves in from the air around the tree and curls on the way.
        vec3 outward = (p - Heart) / max(r, 1.0e-3);
        vec3 q = p - (outward * PULL_DIST + warp * PULL_SWIRL) * pull;
        vec3 m = (texture(Margin, q / GridSize).rgb * 255.0 - 128.0) / 16.0;
        m.b = (texture(Margin, (q + warp * LEAF_WARP) / GridSize).b * 255.0 - 128.0) / 16.0;
        // Past the reach of the field (margin -2) there is no tree; mask after the edge test, as m = 0 is half inside.
        vec3 valid = step(-1.9, m);
        float soft = SURFACE_SOFT + PULL_SOFT * pull;
        vec3 core = smoothstep(-soft, soft, m) * valid;
        vec3 inside = core * live;
        vec3 outside = min(m, 0.0);
        vec3 rim = exp(-outside * outside / (RIM_WIDTH * RIM_WIDTH)) * (1.0 - core) * valid * live;
        float body = max(max(inside.r, inside.g), inside.b);
        float glow = max(max(rim.r, rim.g), rim.b);
        if (body <= 0.0 && glow <= 0.0) continue;
        vec3 colour = mix(HEART_COLOUR, ROOT_COLOUR, smoothstep(0.0, 0.5, r / Reach));
        float pulse = 0.55 + 0.45 * sin(r * 1.2 - Time * 3.0);
        float a = 1.0 - exp(-DENSITY * body * dt);
        emitted += through * (a * colour * CORE_SHADE + colour * glow * RIM_EMISSION * pulse * dt);
        through *= 1.0 - a;
    }
    float alpha = (1.0 - through) * OPACITY * Strength;
    vec3 outColour = emitted * Strength;
    if (alpha < 0.003 && max(max(outColour.r, outColour.g), outColour.b) < 0.003) discard;
    // Premultiplied (wohlon_tree_ghost.json blend).
    fragColor = vec4(outColour, alpha);
}
