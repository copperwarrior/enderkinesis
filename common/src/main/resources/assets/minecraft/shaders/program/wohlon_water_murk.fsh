#version 150

// Scene depth with the water surface, and a copy from before the translucent pass.
uniform sampler2D DepthSampler;
uniform sampler2D OpaqueDepth;
// One byte for each block column: the top water block above BaseY, or 255 (WohlonWaterMurk).
uniform sampler2D ColumnMask;
// Wohlon share of each column over the biome blend radius, 0..1 (WohlonWaterMurk).
uniform sampler2D WeightMask;

uniform mat4 InvViewProj;
uniform ivec2 CamBlockWrap;
uniform vec2 CamFrac;
uniform float CamY;
uniform float BaseY;
uniform vec3 MurkColor;
uniform float Density;
uniform float MaxMurk;
uniform vec2 FogRange;
// Horizontal distance where the murk starts to fade, and the column mask window edge.
uniform vec2 MaskReach;
// Tiling 64³ fBm (WohlonNoiseTexture.id3D), on the unit that WohlonWaterMurk binds by hand.
uniform sampler3D Noise3D;
// Camera X and Z from the world centre, and the same mod the noise period.
uniform vec2 CamFromCentre;
uniform vec2 CamNoise;
// Seconds; the host keeps it bounded for float precision.
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

const int COLUMN_WRAP = 511;
// Past the bottom of the view, so open water down to the sky is fully murky.
const float NO_BOTTOM = 64.0;
// Flowmap: blocks per second, and blocks each phase slides before it fades out.
const float FLOW_SPEED = 0.35;
const float FLOW_LENGTH = 8.0;
// World size of one 64-texel noise tile.
const float FLOW_TILE = 48.0;
// Murk density at the thinnest and the thickest flow streaks, as a fraction of Density.
const float DENSITY_THIN = 0.6;
const float DENSITY_THICK = 1.4;

float flowNoise(vec2 world, vec2 offset) {
    return texture(Noise3D, vec3((world - offset) / FLOW_TILE, 0.37)).r;
}

// Two phases of the noise slide along the flow, half a cycle apart; each fades out before it
// stretches, so the pattern moves with no reset.
float flowStreaks(vec2 world, vec2 flowDir) {
    float cycle = Time * FLOW_SPEED / FLOW_LENGTH;
    float p0 = fract(cycle);
    float p1 = fract(cycle + 0.5);
    float n0 = flowNoise(world, flowDir * p0 * FLOW_LENGTH);
    float n1 = flowNoise(world + 17.3, flowDir * p1 * FLOW_LENGTH);
    float w = abs(p0 * 2.0 - 1.0);
    return mix(n0, n1, w);
}

vec3 relPos(float depth) {
    vec4 rel = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
    return rel.xyz / rel.w;
}

void main() {
    float surface = texture(DepthSampler, texCoord).r;
    float opaque = texture(OpaqueDepth, texCoord).r;
    // Only a translucent surface puts the scene depth in front of the opaque depth.
    if (surface >= opaque - 1.0e-6) discard;
    vec3 s = relPos(surface);
    float horizontal = length(s.xz);
    if (horizontal >= MaskReach.y) discard;
    ivec2 maskPos = (CamBlockWrap + ivec2(floor(CamFrac + s.xz))) & COLUMN_WRAP;
    int code = int(texelFetch(ColumnMask, maskPos, 0).r * 255.0 + 0.5);
    if (code == 255) discard;
    float wohlon = texelFetch(WeightMask, maskPos, 0).r;
    if (wohlon <= 0.0) discard;
    // The surface point must be in the column's top water block; glass and ice are elsewhere.
    float waterY = BaseY + float(code);
    float y = CamY + s.y;
    if (y < waterY - 0.05 || y > waterY + 1.05) discard;

    float tSurface = length(s);
    float tBottom = opaque >= 1.0 ? tSurface + NO_BOTTOM : length(relPos(opaque));
    // The vanilla terrain fog already covers far water: same cylinder distance and smoothstep ramp.
    float fogDistance = max(length(s.xz), abs(s.y));
    float fog = fogDistance <= FogRange.x ? 0.0 : (fogDistance < FogRange.y ? smoothstep(FogRange.x, FogRange.y, fogDistance) : 1.0);
    // The flow changes how thick the murk is, not its colour.
    vec2 fromCentre = CamFromCentre + s.xz;
    vec2 flowDir = fromCentre / max(length(fromCentre), 1.0e-3);
    float streak = smoothstep(0.3, 0.8, flowStreaks(CamNoise + s.xz, flowDir));
    float density = Density * mix(DENSITY_THIN, DENSITY_THICK, streak);
    float murk = 1.0 - exp(-density * max(tBottom - tSurface, 0.0));
    float alpha = wohlon * murk * MaxMurk * (1.0 - fog) * (1.0 - smoothstep(MaskReach.x, MaskReach.y, horizontal));
    if (alpha < 0.003) discard;
    fragColor = vec4(MurkColor, alpha);
}
