#version 150

uniform sampler2D DepthSampler;
// Top-down height of the wave mesh over the zone ellipse bounds. R = height over HeightRange, G = 1 where the mesh is.
uniform sampler2D HeightMap;
// CrepusculiteLatticeShoreMap, over the zone ellipse bounds: the mesh edge alpha.
uniform sampler2D ShoreMap;
uniform sampler2D HullMask;

uniform mat4 InvViewProj;
// All positions are relative to the camera.
uniform vec3 ZoneCenter;
uniform vec3 ZoneRadii;
uniform vec2 ZoneYaw;
// The flat waterline: the surface where the wave mesh has no fragment, and the top of the colour gradient.
uniform float WaterY;
// Largest height of the wave mesh above the waterline, in blocks.
uniform float HeightRange;
uniform vec3 TopColor;
uniform vec3 DeepColor;
uniform float Density;
uniform float Opacity;
// Cloak and dye fade, as on the wave mesh.
uniform float ZoneFade;
uniform mat4 MaskMat;
// Zero when the ocean has no ship.
uniform ivec3 MaskSize;

in vec2 texCoord;

out vec4 fragColor;

// Bounds each walk through the dry cells of a hull. Past it, the ray counts as wet.
const int MAX_DRY_STEPS = 48;
// Upper limit of the march. A short ray uses fewer steps, of about STEP_BLOCKS each.
const int MARCH_STEPS = 32;
const float MIN_STEPS = 6.0;
const float STEP_BLOCKS = 1.5;
// The march stops when the water in front hides this much of what is behind.
const float OPAQUE_THROUGH = 0.02;
// Fraction of the depth where the density starts to fade to zero.
const float BOTTOM_FADE_START = 0.5;
const float NO_HIT = 1.0e30;

// LatticeHullVolume: one bit for each shipyard cell, 32 Y levels in each RGBA8 texel.
bool wetAt(vec3 pos) {
    ivec3 cell = ivec3(floor(pos));
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, MaskSize))) {
        return true;
    }
    ivec4 bits = ivec4(texelFetch(HullMask, ivec2(cell.x, cell.z + (cell.y >> 5) * MaskSize.z), 0) * 255.0 + 0.5);
    int level = cell.y & 31;
    return ((bits[level >> 3] >> (level & 7)) & 1) != 0;
}

float exitT(float pos, float dir) {
    if (abs(dir) < 1.0e-6) return NO_HIT;
    float edge = dir > 0.0 ? floor(pos) + 1.0 : floor(pos);
    return (edge - pos) / dir;
}

// Length of the dry cells along the ray from t in the direction of tEnd. Mask space keeps world lengths.
float dryRun(vec3 maskOrigin, vec3 maskDir, float t, float tEnd) {
    float span = abs(tEnd - t);
    vec3 dir = maskDir * sign(tEnd - t);
    vec3 start = maskOrigin + maskDir * t;
    float travelled = 1.0e-3;
    for (int i = 0; i < MAX_DRY_STEPS && travelled < span; i++) {
        vec3 p = start + dir * travelled;
        if (wetAt(p)) {
            return i == 0 ? 0.0 : travelled;
        }
        travelled += min(min(exitT(p.x, dir.x), exitT(p.y, dir.y)), exitT(p.z, dir.z)) + 1.0e-3;
    }
    return min(travelled, span);
}

vec3 toZone(vec3 v) {
    return vec3(ZoneYaw.x * v.x - ZoneYaw.y * v.z, v.y, ZoneYaw.y * v.x + ZoneYaw.x * v.z) / ZoneRadii;
}

// Height of a camera-relative point above the wave surface. Negative below it.
float aboveSurface(vec3 p) {
    vec2 mesh = texture(HeightMap, toZone(p - ZoneCenter).xz * 0.5 + 0.5).rg;
    float surface = mesh.g > 1.0e-3 ? mesh.r / mesh.g * HeightRange : 0.0;
    return p.y - WaterY - surface;
}

vec3 viewPos(float depth) {
    vec4 rel = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
    return rel.xyz / rel.w;
}

void main() {
    // The direction does not need the scene depth, so a pixel outside the bounds costs no texture read.
    vec3 dir = normalize(viewPos(1.0));

    // Ray against the bounds of the volume: an upright cylinder on the zone ellipse, from the
    // deepest level to the largest wave height. In zone space the cylinder is a unit circle.
    vec3 o = toZone(-ZoneCenter);
    vec3 d = toZone(dir);
    float t0 = 0.0;
    float tExit = NO_HIT;
    float a = dot(d.xz, d.xz);
    float c = dot(o.xz, o.xz) - 1.0;
    if (a > 1.0e-12) {
        float b = dot(o.xz, d.xz);
        float disc = b * b - a * c;
        if (disc <= 0.0) discard;
        float root = sqrt(disc);
        t0 = max((-b - root) / a, 0.0);
        tExit = (-b + root) / a;
    } else if (c >= 0.0) {
        discard;
    }
    float bottomY = WaterY - ZoneRadii.y;
    float topY = WaterY + HeightRange;
    if (abs(dir.y) > 1.0e-6) {
        float ta = bottomY / dir.y;
        float tb = topY / dir.y;
        t0 = max(t0, min(ta, tb));
        tExit = min(tExit, max(ta, tb));
    } else if (bottomY > 0.0 || topY < 0.0) {
        discard;
    }
    if (tExit <= t0) discard;
    float sceneDepth = texture(DepthSampler, texCoord).r;
    float t1 = sceneDepth >= 1.0 ? tExit : min(tExit, length(viewPos(sceneDepth)));
    if (t1 <= t0) discard;

    // A view ray has dry hull cells only at its start (camera in the hull) or at its end
    // (the ray stops on a surface in the hull), so two bounded walks replace a full march.
    if (MaskSize.x > 0) {
        vec3 maskOrigin = MaskMat[3].xyz;
        vec3 maskDir = mat3(MaskMat) * dir;
        t0 += dryRun(maskOrigin, maskDir, t0, t1);
        if (t1 - t0 > 1.0e-3) t1 -= dryRun(maskOrigin, maskDir, t1, t0);
        if (t1 - t0 <= 1.0e-3) discard;
    }

    // Straight walls: each level of the volume is the mesh shore outline (coast, tendrils, coves).
    // The density fades to zero at the deepest level. The height map test at the two ends of
    // each step gives the part of the step that is below the wave surface, so the ray can go
    // into and out of the water any number of times.
    int steps = int(clamp(ceil((t1 - t0) / STEP_BLOCKS), MIN_STEPS, float(MARCH_STEPS)));
    float stepLen = (t1 - t0) / float(steps);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float depthScale = 1.0 / ZoneRadii.y;
    vec3 colour = vec3(0.0);
    float through = 1.0;
    float above0 = aboveSurface(dir * t0);
    for (int i = 0; i < MARCH_STEPS; i++) {
        if (i >= steps || through < OPAQUE_THROUGH) break;
        float tStep = t0 + float(i) * stepLen;
        float above1 = aboveSurface(dir * (tStep + stepLen));
        float wet = above0 <= 0.0
            ? (above1 <= 0.0 ? 1.0 : above0 / (above0 - above1))
            : (above1 <= 0.0 ? above1 / (above1 - above0) : 0.0);
        above0 = above1;
        if (wet <= 0.0) continue;
        vec3 p = dir * (tStep + jitter * stepLen);
        float depth = max((WaterY - p.y) * depthScale, 0.0);
        if (depth >= 1.0) continue;
        float shore = texture(ShoreMap, toZone(p - ZoneCenter).xz * 0.5 + 0.5).r;
        float density = Density * shore * (1.0 - smoothstep(BOTTOM_FADE_START, 1.0, depth));
        float stepAlpha = 1.0 - exp(-density * stepLen * wet);
        colour += through * stepAlpha * mix(TopColor, DeepColor, depth);
        through *= 1.0 - stepAlpha;
    }
    float alpha = 1.0 - through;
    if (alpha < 0.004) discard;
    fragColor = vec4(colour / alpha, alpha * Opacity * ZoneFade);
}
