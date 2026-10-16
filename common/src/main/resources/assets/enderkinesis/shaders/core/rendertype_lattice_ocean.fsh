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

in vec4 vColor;
in vec2 vSurfacePos;
in vec3 vMaskPos;
in vec3 vPrevMaskPos;

out vec4 fragColor;

const float GRAINS_PER_BLOCK = 16.0;
const float FRAMES_PER_TICK = 2.0;
const float STATIC_STRENGTH = 0.08;
// The surface fades out over this distance in front of the blocks behind it, in blocks.
const float SOFT_EDGE_BLOCKS = 1.0;

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

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

void main() {
    vec4 color = vColor * ColorModulator;
    float wet = wetAt(HullMask, MaskSize, vMaskPos);
    if (MaskBlend < 1.0) {
        wet = mix(wetAt(PrevHullMask, PrevMaskSize, vPrevMaskPos), wet, MaskBlend);
    }
    color.a *= wet;
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
