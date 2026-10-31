#version 150

uniform sampler2D DepthSampler;
// Signed margin to the staff surface in grid voxels: r = wood, g = gem. 128 is the surface, 16 per voxel.
uniform sampler3D Margin;
uniform sampler3D Warp;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;

uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;

uniform vec3 GridSize;
// Grid voxels per block: LocalToView scales by its inverse, so scene distances convert by it.
uniform float LocalScale;
// Wood below this grid height is filled by an offering; above it is still a ghost.
uniform float FillY;
uniform float Appear;
uniform vec3 GemColour;
// The colour of what the recipe fills the wood with; BodyTint is 0 when the recipe gives none.
uniform vec3 BodyColour;
uniform float BodyTint;
uniform float Strength;
// 1 when the viewer lacks the recipe's advancements: the shape warbles and blurs.
uniform float Warble;
uniform float Time;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 64;
// Densities and widths are per grid voxel.
const float GHOST_DENSITY = 0.05;
const float SOLID_DENSITY = 0.45;
const float GEM_DENSITY = 0.35;
const float SURFACE_SOFT = 0.8;
const float RIM_WIDTH = 1.2;
const float FILL_SOFT = 2.0;
const float CORE_SHADE = 0.05;
const float RIM_EMISSION = 0.05;
const float GEM_EMISSION = 0.12;
const float BODY_INFLUENCE = 0.75;
const float BODY_EMISSION = 0.05;
const float APPEAR_SOFT = 0.25;
const float NOISE_TILE = 48.0;
const float WARBLE_TILE = 40.0;
const float WARBLE_RATE = 0.08;
const float WARBLE_SHIFT = 10.0;
const float FUZZ_SOFT = 2.5;
const float FUZZ_NOISE = 3.0;
// The ritual tendril colours: the ghost in cyan, filled wood in firefly green.
const vec3 GHOST_COLOUR = vec3(0.0, 1.0, 1.0);
const vec3 SOLID_COLOUR = vec3(0.557, 0.831, 0.784);

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
        t1 = min(t1, length(scene.xyz / scene.w) * LocalScale);
    }
    if (t1 <= t0) discard;

    float dt = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        vec3 q = p;
        float soft = SURFACE_SOFT;
        if (Warble > 0.0) {
            vec3 w = p / WARBLE_TILE + vec3(0.0, Time * WARBLE_RATE, 0.0);
            q += (vec3(texture(Warp, w).r, texture(Warp, w + vec3(0.37, 0.11, 0.71)).r, texture(Warp, w + vec3(0.53, 0.79, 0.23)).r) - 0.5)
                * WARBLE_SHIFT * Warble;
            soft += FUZZ_SOFT * Warble;
        }
        vec2 m = (texture(Margin, q / GridSize).rg * 255.0 - 128.0) / 16.0;
        float n = texture(Warp, p / NOISE_TILE).r;
        if (Warble > 0.0) m += (texture(Warp, p / 6.0 + Time * 0.5).r - 0.5) * FUZZ_NOISE * Warble;
        if (max(m.r, m.g) < -RIM_WIDTH * 2.5 - soft) continue;
        float shown = smoothstep(n - APPEAR_SOFT, n, Appear * (1.0 + APPEAR_SOFT));
        if (shown <= 0.0) continue;

        float filled = 1.0 - smoothstep(FillY - FILL_SOFT, FillY + FILL_SOFT, p.y);
        float wood = smoothstep(-soft, soft, m.r);
        float gem = smoothstep(-soft, soft, m.g);
        float woodRim = exp(-min(m.r, 0.0) * min(m.r, 0.0) / (RIM_WIDTH * RIM_WIDTH)) * (1.0 - wood);
        float gemRim = exp(-min(m.g, 0.0) * min(m.g, 0.0) / (RIM_WIDTH * RIM_WIDTH)) * (1.0 - gem);

        float tint = BodyTint * BODY_INFLUENCE;
        vec3 woodColour = mix(mix(GHOST_COLOUR, BodyColour, tint), mix(SOLID_COLOUR, BodyColour, tint), filled);
        float pulse = 0.6 + 0.4 * sin(p.y * 0.15 - Time * 2.0);
        float density = (wood * mix(GHOST_DENSITY, SOLID_DENSITY, filled) + gem * GEM_DENSITY) * shown;
        float a = 1.0 - exp(-density * dt);
        vec3 shade = (wood * woodColour + gem * GemColour) / max(wood + gem, 1.0e-3);
        emitted += through * (a * shade * CORE_SHADE
            + (woodColour * woodRim * RIM_EMISSION * pulse + BodyColour * wood * filled * BODY_EMISSION * BodyTint
                + GemColour * (gem + gemRim) * GEM_EMISSION) * shown * dt);
        through *= 1.0 - a;
        if (through < 0.01) break;
    }
    float alpha = (1.0 - through) * Strength;
    vec3 outColour = emitted * Strength;
    if (alpha < 0.003 && max(max(outColour.r, outColour.g), outColour.b) < 0.003) discard;
    // Premultiplied (wohlon_staff_ghost.json blend).
    fragColor = vec4(outColour, alpha);
}
