#version 150

uniform sampler2D DepthSampler;
uniform sampler2D ColumnMask;

uniform mat4 InvViewProj;
// Camera block mod the mask size: the mask wraps, and every block size divides it, so lookups
// and alignment only need the low bits. Absolute coordinates lose block precision past 2^24.
uniform ivec2 CamBlockWrap;
uniform vec2 CamFrac;
uniform float CamY;
uniform vec2 DepthParams;
uniform vec2 FogRange;
uniform vec2 SlabY;
uniform float MaxDist;
uniform vec2 Density;
uniform float EdgeFade;
uniform vec4 BreaksLo;
uniform vec4 BreaksHi;

in vec2 texCoord;

out vec4 fragColor;

// Bounds the march: one step per crossed cell on a MaxDist path.
const int MAX_STEPS = 512;
const int COLUMN_WRAP = 511;
const float NO_HIT = 1.0e30;

// SselithRepertory.fogDensityAt, faded linearly to 0 over EdgeFade past the world top and bottom.
float densityAt(float y) {
    float base = 1.0 - min(abs(y) / Density.x, 1.0) * (1.0 - Density.y);
    float edge = min(y - SlabY.x, SlabY.y - y) / EdgeFade + 1.0;
    return base * clamp(edge, 0.0, 1.0);
}

// densityAt is quadratic between breaks, so 2-point Gauss-Legendre is exact on each piece.
float pieceIntegral(float a, float b) {
    if (b <= a) return 0.0;
    float halfLen = 0.5 * (b - a);
    float mid = a + halfLen;
    float offset = halfLen * 0.57735027;
    return halfLen * (densityAt(mid - offset) + densityAt(mid + offset));
}

float densityIntegral(float a, float b) {
    float sum = 0.0;
    for (int i = 0; i < 7; i++) {
        float lo = i < 4 ? BreaksLo[i] : BreaksHi[i - 4];
        float hi = i < 3 ? BreaksLo[i + 1] : BreaksHi[i - 3];
        sum += pieceIntegral(max(a, lo), min(b, hi));
    }
    return sum;
}

float segmentMass(float y0, float y1, float len) {
    float dy = abs(y1 - y0);
    if (dy < 1.0e-3) return densityAt(0.5 * (y0 + y1)) * len;
    return densityIntegral(min(y0, y1), max(y0, y1)) / dy * len;
}

float exitT(float cellMin, float size, float origin, float dir) {
    if (abs(dir) < 1.0e-6) return NO_HIT;
    float edge = dir > 0.0 ? cellMin + size : cellMin;
    return (edge - origin) / dir;
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 rel = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
    vec3 dir = normalize(rel.xyz / rel.w);
    float t1 = depth >= 1.0 ? MaxDist : min(length(rel.xyz / rel.w), MaxDist);
    float t0 = 0.0;

    if (abs(dir.y) > 1.0e-6) {
        float ta = (SlabY.x - EdgeFade - CamY) / dir.y;
        float tb = (SlabY.y + EdgeFade - CamY) / dir.y;
        t0 = max(t0, min(ta, tb));
        t1 = min(t1, max(ta, tb));
    } else if (CamY < SlabY.x - EdgeFade || CamY > SlabY.y + EdgeFade) {
        t1 = -1.0;
    }

    float voidLen = 0.0;
    float voidMass = 0.0;
    // Adjacent empty cells are one straight run, so each run is integrated once.
    float runStart = -1.0;
    float runEnd = 0.0;
    float t = t0;
    for (int i = 0; i < MAX_STEPS && t < t1; i++) {
        vec2 p = CamFrac + dir.xz * t;
        ivec2 blockRel = ivec2(floor(p));
        ivec2 maskPos = (CamBlockWrap + blockRel) & COLUMN_WRAP;
        // Bit 0: void column. Bit `size`: the aligned size×size block is all one state (SselithVoidFog).
        int bits = int(texelFetch(ColumnMask, maskPos, 0).r * 255.0 + 0.5);
        int size = (bits & 16) != 0 ? 16 : (bits & 8) != 0 ? 8 : (bits & 4) != 0 ? 4 : (bits & 2) != 0 ? 2 : 1;
        vec2 cellMin = vec2(blockRel - (maskPos & (size - 1)));
        bool empty = (bits & 1) != 0;

        float tExit = min(min(exitT(cellMin.x, float(size), CamFrac.x, dir.x),
                              exitT(cellMin.y, float(size), CamFrac.y, dir.z)), t1);
        if (empty) {
            if (runStart < 0.0) runStart = t;
            runEnd = tExit;
        } else if (runStart >= 0.0) {
            voidLen += runEnd - runStart;
            voidMass += segmentMass(CamY + dir.y * runStart, CamY + dir.y * runEnd, runEnd - runStart);
            runStart = -1.0;
        }
        t = tExit + 1.0e-3;
    }
    if (runStart >= 0.0) {
        voidLen += runEnd - runStart;
        voidMass += segmentMass(CamY + dir.y * runStart, CamY + dir.y * runEnd, runEnd - runStart);
    }

    // Vanilla linear_fog ramp over the void path, scaled by the mean fogDensityAt along it.
    float alpha = voidLen > 0.0
        ? smoothstep(FogRange.x, FogRange.y, voidLen) * voidMass / voidLen
        : 0.0;
    fragColor = vec4(alpha, DepthParams.y / (depth * 2.0 - 1.0 + DepthParams.x), 0.0, 1.0);
}
