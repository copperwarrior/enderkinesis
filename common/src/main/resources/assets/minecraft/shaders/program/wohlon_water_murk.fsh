#version 150

// Scene depth with the water surface, and a copy from before the translucent pass.
uniform sampler2D DepthSampler;
uniform sampler2D OpaqueDepth;
// Wohlon share of each column over the biome blend radius, 0..1 (WohlonWaterMurk).
uniform sampler2D WeightMask;
// Two water runs for each block column (WohlonWaterMurk): top Y - BaseY + 1 (0 = none), then blocks below the top + 128 if falling.
uniform sampler2D RunMask;

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
const float INSIDE_STEP = 0.02;
// Falling water is a thin sheet: murk only about the water a ray crosses, not the air to the wall behind.
const float FALL_THICKNESS = 1.0;
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

bool inRun(float y, float top, float info) {
    if (top < 0.5) return false;
    float topY = BaseY + top - 1.0;
    return y >= topY - mod(info, 128.0) - 0.05 && y <= topY + 1.05;
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
    float tSurface = length(s);
    // A side face lies on the column edge; a point just behind it is in the water block's own column.
    vec3 inside = s + s * (INSIDE_STEP / max(tSurface, 1.0e-3));
    ivec2 maskPos = (CamBlockWrap + ivec2(floor(CamFrac + inside.xz))) & COLUMN_WRAP;
    vec4 runs = floor(texelFetch(RunMask, maskPos, 0) * 255.0 + 0.5);
    // The surface point must be in one of the column's water runs; glass and ice are elsewhere.
    float y = CamY + inside.y;
    float info = -1.0;
    if (inRun(y, runs.x, runs.y)) info = runs.y;
    else if (inRun(y, runs.z, runs.w)) info = runs.w;
    if (info < 0.0) discard;
    float wohlon = texelFetch(WeightMask, maskPos, 0).r;
    if (wohlon <= 0.0) discard;

    float tBottom = opaque >= 1.0 ? tSurface + NO_BOTTOM : length(relPos(opaque));
    // The vanilla terrain fog already covers far water: same cylinder distance and smoothstep ramp.
    float fogDistance = max(length(s.xz), abs(s.y));
    float fog = fogDistance <= FogRange.x ? 0.0 : (fogDistance < FogRange.y ? smoothstep(FogRange.x, FogRange.y, fogDistance) : 1.0);
    // The flow changes how thick the murk is, not its colour.
    vec2 fromCentre = CamFromCentre + s.xz;
    vec2 flowDir = fromCentre / max(length(fromCentre), 1.0e-3);
    float streak = smoothstep(0.3, 0.8, flowStreaks(CamNoise + s.xz, flowDir));
    float density = Density * mix(DENSITY_THIN, DENSITY_THICK, streak);
    float thickness = max(tBottom - tSurface, 0.0);
    if (info >= 128.0) thickness = min(thickness, FALL_THICKNESS);
    float murk = 1.0 - exp(-density * thickness);
    float alpha = wohlon * murk * MaxMurk * (1.0 - fog) * (1.0 - smoothstep(MaskReach.x, MaskReach.y, horizontal));
    if (alpha < 0.003) discard;
    fragColor = vec4(MurkColor, alpha);
}
