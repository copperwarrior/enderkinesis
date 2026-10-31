#version 150

uniform vec4 ColorModulator;
uniform float Time;
uniform sampler2D HullMask;
// Zero when the ocean has no ship.
uniform ivec3 MaskSize;
// The mask before the last hull change. MaskBlend goes from 0 (previous) to 1 (current).
uniform sampler2D PrevHullMask;
uniform ivec3 PrevMaskSize;
uniform float MaskBlend;
uniform sampler2D SceneDepth;
// 1 on the wave layer that gets the depth fade, 0 on the other layer.
uniform float SoftEdge;
uniform mat4 ProjMat;
// The zone shore, from CrepusculiteLatticeMeshRenderer.applyShore. ShoreOffset moves vSurfacePos to the current zone centre.
uniform vec2 ShoreOffset;
uniform vec2 ZoneYaw;
uniform vec2 ZoneRadii;
uniform float ShoreSafe;
// The coastline lobe counts and phases.
uniform vec3 ShoreLobes;
uniform vec3 ShoreDrift;
// The twilight gradient of the zone, and the view-angle falloff power of the layer.
uniform vec3 DeepColor;
uniform vec3 TintColor;
uniform vec3 FoamColor;
uniform float ViewFalloff;

in vec4 vColor;
in vec2 vSurfacePos;
in vec3 vMaskPos;
in vec3 vPrevMaskPos;
in vec3 vCameraRel;

out vec4 fragColor;

const float GRAINS_PER_BLOCK = 16.0;
const float FRAMES_PER_TICK = 2.0;
const float STATIC_STRENGTH = 0.08;
// The surface fades out over this distance in front of the blocks behind it, in blocks.
const float SOFT_EDGE_BLOCKS = 1.0;
// White shift at full grazing: the rim glow without an alpha boost.
const float FRESNEL_WHITE_SHIFT = 0.30;
// Floor of the view-angle factor, so that an edge-on surface does not go fully clear.
const float MIN_VIEW_FACTOR = 0.05;

// View distance of a depth buffer value, for the perspective projection of the level.
float viewDistance(float depth) {
    return ProjMat[3][2] / (depth * 2.0 - 1.0 + ProjMat[2][2]);
}

// CrepusculiteLatticeHullMask: one bit for each shipyard cell, 32 Y levels in each RGBA8 texel.
float wetAt(sampler2D mask, ivec3 size, vec3 pos) {
    ivec3 cell = ivec3(floor(pos));
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, size))) {
        return 1.0;
    }
    ivec4 bits = ivec4(texelFetch(mask, ivec2(cell.x, cell.z + (cell.y >> 5) * size.z), 0) * 255.0 + 0.5);
    int level = cell.y & 31;
    return float((bits[level >> 3] >> (level & 7)) & 1);
}

// Copies of the LatticeShore constants. Change them together.
const float AMP_BLOCKS = 8.0;
const float FALLOFF_BLOCKS = 14.0;

// LatticeShore.rimWarpAt and LatticeShore.shoreEdgeAt, for a point relative to the zone centre.
float shoreEdge(vec2 rel) {
    vec2 n = vec2(ZoneYaw.x * rel.x - ZoneYaw.y * rel.y, ZoneYaw.y * rel.x + ZoneYaw.x * rel.y) / ZoneRadii;
    float rRaw = length(n);
    if (rRaw >= 1.0) return 0.0;
    if (rRaw <= ShoreSafe) return 1.0;
    float theta = atan(n.y * ZoneRadii.y, n.x * ZoneRadii.x);
    vec2 dirOverRadii = vec2(cos(theta), sin(theta)) / ZoneRadii;
    float rDir = inversesqrt(dot(dirOverRadii, dirOverRadii));
    float lobes = sin(theta * ShoreLobes.x + ShoreDrift.x) * 0.5
        + sin(theta * ShoreLobes.y + ShoreDrift.y) * 0.3
        + sin(theta * ShoreLobes.z + ShoreDrift.z) * 0.2;
    float rWarped = rRaw / max(1.0 - AMP_BLOCKS * (lobes * 0.5 + 0.5) / rDir, 0.5);
    return smoothstep(0.0, FALLOFF_BLOCKS, (1.0 - rWarped) * rDir);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

void main() {
    // CrepusculiteLatticeMeshRenderer packs R = swell height, GB = normal x and z, A = base alpha.
    float swell = vColor.r;
    vec3 body = swell < 0.5
        ? mix(DeepColor, TintColor, swell * 2.0)
        : mix(TintColor, FoamColor, swell * 2.0 - 1.0);
    vec2 normalXZ = vColor.gb * 2.0 - 1.0;
    vec3 normal = normalize(vec3(normalXZ.x, sqrt(max(1.0 - dot(normalXZ, normalXZ), 0.0)), normalXZ.y));
    // abs: the surface has no back face, so it looks the same from above and below.
    float cosTheta = clamp(abs(dot(normal, normalize(-vCameraRel))), 0.0, 1.0);
    vec3 rgb = mix(body, vec3(1.0), (1.0 - cosTheta) * FRESNEL_WHITE_SHIFT);
    float alpha = vColor.a * max(MIN_VIEW_FACTOR, pow(cosTheta, ViewFalloff));
    vec4 color = vec4(rgb, alpha) * ColorModulator;
    float wet = wetAt(HullMask, MaskSize, vMaskPos);
    if (MaskBlend < 1.0) {
        wet = mix(wetAt(PrevHullMask, PrevMaskSize, vPrevMaskPos), wet, MaskBlend);
    }
    color.a *= wet * shoreEdge(vSurfacePos + ShoreOffset);
    if (SoftEdge > 0.5) {
        vec2 screenUv = gl_FragCoord.xy / vec2(textureSize(SceneDepth, 0));
        float gap = viewDistance(texture(SceneDepth, screenUv).r) - viewDistance(gl_FragCoord.z);
        color.a *= clamp(gap / SOFT_EDGE_BLOCKS, 0.0, 1.0);
    }
    if (color.a == 0.0) {
        discard;
    }
    vec2 grain = floor(vSurfacePos * GRAINS_PER_BLOCK);
    float frame = floor(Time * FRAMES_PER_TICK);
    float noise = hash13(vec3(grain, frame)) * 2.0 - 1.0;
    fragColor = vec4(color.rgb * (1.0 + STATIC_STRENGTH * noise), color.a);
}
