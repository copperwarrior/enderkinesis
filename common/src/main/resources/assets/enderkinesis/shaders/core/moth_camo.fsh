#version 150

#moj_import <fog.glsl>

// Sampler0 = block atlas (camouflage colour source)
// Sampler1 = moth entity texture (alpha mask only)
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// Up to eight block-atlas sprite rects (u0, v0, u1, v1), one per matrix column.
uniform mat4 SpriteRectsA;
uniform mat4 SpriteRectsB;
// 4-bit sprite slot per face-grid cell, eight cells per int; 15 = not in this draw.
uniform ivec4 CellSlots0;
uniform ivec4 CellSlots1;
uniform ivec4 CellSlots2;
uniform ivec4 CellSlots3;
uniform ivec4 CellSlots4;
uniform ivec4 CellSlots5;
// Fade-in with the landing blend.
uniform float CamoAlpha;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;
// Rest-pose face-plane projection (blocks), origin at the perch block —
// see the vertex shader / SselithMothCamoLayer for how it's baked.
in vec2 projUV;

out vec4 fragColor;

const float PIXELS_PER_BLOCK = 16.0;
const int GRID_W = 13;
const int GRID_H = 13;
const int GRID_OFF_U = 6;
const int GRID_OFF_V = 6;
const int NO_SLOT = 15;

int slotWord(int word) {
    ivec4 v = word < 4 ? CellSlots0
        : (word < 8 ? CellSlots1
        : (word < 12 ? CellSlots2
        : (word < 16 ? CellSlots3
        : (word < 20 ? CellSlots4 : CellSlots5))));
    return v[word & 3];
}

void main() {
    // The moth texture contributes ONLY its alpha — the silhouette.
    float mask = texture(Sampler1, texCoord0).a;
    if (mask < 0.02) discard;

    float du = projUV.x;
    float dv = projUV.y;

    // Face-grid cell → sprite slot for this draw.
    int cu = int(floor(du)) + GRID_OFF_U;
    int cv = int(floor(dv)) + GRID_OFF_V;
    if (cu < 0 || cu >= GRID_W || cv < 0 || cv >= GRID_H) discard;
    int idx = cu * GRID_H + cv;
    int slot = (slotWord(idx >> 3) >> ((idx & 7) * 4)) & 15;
    if (slot == NO_SLOT) discard;
    vec4 rect = slot < 4 ? SpriteRectsA[slot] : SpriteRectsB[slot - 4];

    // In-cell texel, snapped to the 16×16 grid so the camo pixels line up
    // exactly with the block face beneath.
    float pu = (floor(fract(du) * PIXELS_PER_BLOCK) + 0.5) / PIXELS_PER_BLOCK;
    float pv = (floor(fract(dv) * PIXELS_PER_BLOCK) + 0.5) / PIXELS_PER_BLOCK;
    vec2 uv = vec2(mix(rect.x, rect.z, pu), mix(rect.y, rect.w, pv));

    vec4 blockCol = texture(Sampler0, uv);
    vec4 color = vec4(blockCol.rgb, mask * CamoAlpha) * vertexColor * ColorModulator;
    color *= vec4(lightMapColor.rgb, 1.0);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
