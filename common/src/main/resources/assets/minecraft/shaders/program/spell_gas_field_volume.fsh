#version 150
#extension GL_ARB_explicit_attrib_location : enable

// The march is camera-relative in the field axes, but the noise is in field space, so the gas does not slide as the camera
// or a ship moves.

// R: raw depth where the march stops. G: raw glass depth where the part behind glass starts, or 0.
uniform sampler2D DepthSampler;
// One texel per block from FieldOrigin: the gas now, and the gas one tick before.
uniform sampler3D FieldNow;
uniform sampler3D FieldWas;
// RGB: the flow in blocks a tick, as int8 + 128 in steps of FlowMax / 127, in the field axes. A: 1 for a full solid cell.
uniform sampler3D FieldFlow;
// The flow of the build before (RGB only), blended by FieldBlend like the density.
uniform sampler3D FieldFlowWas;
// The noise offsets in blocks at the last build: the flow summed since phase A (or B) last restarted.
uniform sampler3D FlowOffsetA;
uniform sampler3D FlowOffsetB;
uniform float FlowMax;
uniform float FlowPeriod;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Use SpellClock ticks: GameTime wraps each day, and the noise would jump there.
uniform float SpellTicks;
// World axes to field axes: identity for world gas, the inverse ship rotation for ship gas.
uniform mat4 FieldRotation;
// The field corner in its own frame, for the noise.
uniform vec3 FieldCorner;
uniform vec3 FieldOrigin;
uniform vec3 FieldSize;
uniform vec3 FieldTexSize;
// 0: the gas one tick before. 1: the gas now.
uniform float FieldBlend;
// 1 for world gas; a cloaked ship's gas fades with its cloak.
uniform float Visibility;
uniform vec3 DarkColor;
// The frame number, 0 to 63, shifts the noise that jitters the march start, so the history resolves the grain.
uniform float JitterFrame;
uniform vec3 LightColor;
// The puff jet: flux lines, binned into 1-block tiles. A page table (one cell for each 8 blocks) points to pages of an atlas;
// a tile entry (24 bit offset, 8 bit count) points to runs (16 bit first sample, count in the high bits) of the samples.
uniform float JetOn;
uniform vec3 JetPageDims;
uniform usampler3D JetPages;
uniform usampler3D JetAtlas;
uniform usampler2D JetRuns;
uniform usampler2D JetSamples;
// 1 for each 4x4x4 brick from FieldOrigin that has now or was gas within 2 blocks, the reach of field().
uniform sampler3D FieldBricks;
uniform sampler3D NoiseTex;

in vec3 viewPos;

#ifdef GL_ARB_explicit_attrib_location
layout(location = 0) out vec4 fragColor;
// For the history reprojection (HalfResVolume volumeDepth): R = weighted march distance × alpha, G = alpha.
layout(location = 1) out vec4 volumeDepthOut;
#else
out vec4 fragColor;
vec4 volumeDepthOut;
#endif

const float NOISE_SCALE = 0.55;
// The texture holds 0 to FIELD_MAX in 0 to 1: the most gas in a cell (GasField.MAX_AMOUNT). Above 1 the cell is under strain.
const float FIELD_MAX = 2.0;

float ign(vec2 p) {
    p += 5.588238 * JitterFrame;
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// Value noise on a 1-unit lattice from ValueNoise3D (one texel a lattice point, repeat wrap). The smoothstepped fraction
// steers one linear fetch, so the blend is the exact smoothstep curve.
const float NOISE_TEX_PERIOD = 32.0;

float n3(vec3 p) {
    vec3 i = mod(floor(p), NOISE_TEX_PERIOD), f = fract(p);
    return texture(NoiseTex, (i + f * f * (3.0 - 2.0 * f) + 0.5) * (1.0 / NOISE_TEX_PERIOD)).r;
}

vec3 flowAt(vec3 q) {
    vec3 uvw = clamp(q, vec3(0.5), FieldSize - 0.5) / FieldTexSize;
    vec3 flow = mix(texture(FieldFlowWas, uvw).rgb, texture(FieldFlow, uvw).rgb, FieldBlend);
    return (flow * 255.0 - 128.0) / 127.0 * FlowMax;
}

// Two octaves; the first moves by the summed flow in two phases half a period apart, each restarting at zero weight
// (Vlachos 2010, with summed offsets so a changing flow never jumps). The blend keeps the variance, so no pulse.
float fbmFlow(vec3 w, vec3 offA, vec3 offB, float ticks) {
    float wB = abs(fract(ticks / FlowPeriod) * 2.0 - 1.0);
    float first = mix(n3(w - offA * NOISE_SCALE), n3(w - offB * NOISE_SCALE), wB);
    // Both phases read one pattern, so they only decorrelate as their offsets part (about one lattice unit).
    float apart = clamp(length(offA - offB) * NOISE_SCALE, 0.0, 1.0);
    float gain = mix(1.0, inversesqrt(wB * wB + (1.0 - wB) * (1.0 - wB)), apart);
    first = clamp(0.5 + (first - 0.5) * gain, 0.0, 1.0);
    return (0.5 * first + 0.25 * n3(w * 2.07 + 5.3)) / 0.75;
}

// True inside a full solid cell. Solid cells hold no gas, so the filter fades the gas toward a wall; this test removes the
// gas that the filter leaves between the cell centres.
bool solidAt(vec3 q) {
    ivec3 c = ivec3(floor(q));
    if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(vec3(c), FieldSize))) return false;
    return texelFetch(FieldFlow, c, 0).a > 0.75;
}

float sampleField(sampler3D tex, vec3 q) {
    // Half a texel inside the used part, so the filter reads no texel from past its edge.
    // The fraction is smoothstepped, so the trilinear ramp has no crease at block lines.
    vec3 t = clamp(q, vec3(0.5), FieldSize - 0.5) - 0.5;
    vec3 i = floor(t), f = fract(t);
    vec3 uvw = (i + f * f * (3.0 - 2.0 * f) + 0.5) / FieldTexSize;
    return texture(tex, uvw).r * FIELD_MAX;
}

// Semi-Lagrangian: the gas of the tick before slides forward along the flow, and the gas now is pulled back, so a
// block of gas moves between cells instead of popping. The offset is under one block, and solid cells hold no gas.
float field(vec3 q) {
    vec3 v = clamp(flowAt(q), vec3(-1.0), vec3(1.0));
    if (any(isnan(v))) v = vec3(0.0);
    return mix(sampleField(FieldWas, q - v * FieldBlend), sampleField(FieldNow, q + v * (1.0 - FieldBlend)), FieldBlend);
}

// A sample, all fixed point (GLSL 150 has no bit casts): x, y in word 0, z and the radius in word 1, the concentration in word 2.
// Positions are unsigned 1/256 block with a 64 block bias; the radius is unorm16 over 0 to 2.
void jetSample(int i, out vec3 pos, out float radius, out float conc) {
    uvec4 t = texelFetch(JetSamples, ivec2(i & 255, i >> 8), 0);
    pos = vec3(float(t.x & 0xFFFFu), float(t.x >> 16), float(t.y & 0xFFFFu)) * (1.0 / 256.0) - 64.0;
    radius = float(t.y >> 16) * (2.0 / 65535.0);
    conc = float(t.z & 0xFFFFu) * (1.0 / 65535.0);
}

// The flux concentration of the capsule from sample i to i + 1 at p, with the (1 - rho^2)^2 profile.
float jetCapsule(int i, vec3 p, out vec3 d) {
    vec3 a, b;
    float ra, rb, ca, cb;
    jetSample(i, a, ra, ca);
    jetSample(i + 1, b, rb, cb);
    vec3 ab = b - a;
    float t = clamp(dot(p - a, ab) / max(dot(ab, ab), 1e-6), 0.0, 1.0);
    d = p - (a + ab * t);
    float r = max(mix(ra, rb, t), 1e-3);
    float x = max(0.0, 1.0 - dot(d, d) / (r * r));
    return mix(ca, cb, t) * x * x;
}

// The page table cell of p, or 0 when its page is empty.
uint jetPage(vec3 p, out ivec3 cell) {
    cell = ivec3(floor(p / 8.0));
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(vec3(cell), JetPageDims))) return 0u;
    return texelFetch(JetPages, cell, 0).r;
}

// The tile entry of p: the offset into the run list in x and the run count in y; (0, 0) when the tile is empty.
uvec2 jetTile(vec3 p) {
    ivec3 cell;
    uint pg = jetPage(p, cell);
    if (pg == 0u) return uvec2(0u);
    int page = int(pg) - 1;
    ivec3 base = ivec3(page & 7, (page >> 3) & 7, page >> 6) * 8;
    uint e = texelFetch(JetAtlas, base + ivec3(floor(p)) - cell * 8, 0).r;
    return uvec2(e & 0xFFFFFFu, e >> 24);
}

// The density of the jet flux lines at p, in cell units: the largest of the runs of the tile of p. For each run, every 4th sample is tested, then the 3 on each side of the best.
// [off] is p minus the nearest axis point of the capsule that gave the result.
float jetRuns(vec3 p, uvec2 tile, out vec3 off) {
    float best = 0.0;
    off = vec3(0.0);
    vec3 d3;
    for (uint k = 0u; k < tile.y; k++) {
        int e = int(tile.x + k);
        uint run = texelFetch(JetRuns, ivec2(e & 1023, e >> 10), 0).r;
        int first = int(run & 0xFFFFu), n = int(run >> 16);
        int last = first + max(n - 2, 0);
        int at = first;
        float v = -1.0;
        vec3 vOff = vec3(0.0);
        for (int s = first; s <= last + 3; s += 4) {
            int i = min(s, last);
            float c = jetCapsule(i, p, d3);
            if (c > v) { v = c; at = i; vOff = d3; }
        }
        if (v > best) { best = v; off = vOff; }
        for (int d = -3; d <= 3; d++) {
            if (d == 0) continue;
            int i = at + d;
            if (i < first || i > last) continue;
            float c = jetCapsule(i, p, d3);
            if (c > best) { best = c; off = d3; }
        }
    }
    return best;
}

// The capsules have no walls: a flux line inside a glass room would reach through the glass. The weight falls from 1 at
// the near face of a full solid cell between p and the axis to 0 at JET_WALL_FADE past it, so a moving line fades, not
// switches. Three steps find the first solid; two bisections place its face.
const float JET_WALL_FADE = 0.25;

float jetOpen(vec3 p, vec3 off) {
    if (solidAt(p)) return 0.0;
    float lo = 0.0, hi = -1.0;
    if (solidAt(p - off * 0.25)) hi = 0.25;
    else if (solidAt(p - off * 0.5)) { lo = 0.25; hi = 0.5; }
    else if (solidAt(p - off * 0.75)) { lo = 0.5; hi = 0.75; }
    if (hi < 0.0) return 1.0;
    for (int k = 0; k < 2; k++) {
        float m = 0.5 * (lo + hi);
        if (solidAt(p - off * m)) hi = m; else lo = m;
    }
    return 1.0 - smoothstep(0.0, JET_WALL_FADE, 0.5 * (lo + hi) * length(off));
}

// True when the 4x4x4 brick of q has no gas within reach and its 8-block jet page is empty: no sample in it adds density.
bool brickEmpty(vec3 q, bool jets) {
    ivec3 b = clamp(ivec3(floor(q * 0.25)), ivec3(0), ivec3(ceil(FieldSize * 0.25)) - 1);
    if (texelFetch(FieldBricks, b, 0).r > 0.0) return false;
    ivec3 cell;
    return !jets || jetPage(q, cell) == 0u;
}

void main() {
    float ticks = SpellTicks;
    vec2 texCoord = gl_FragCoord.xy / ScreenSize;
    vec3 rd = mat3(FieldRotation) * normalize(viewPos);
    // A near-zero component would make inf here, and 0 * inf in the box test is NaN, which draws black.
    vec3 inv = 1.0 / mix(rd, vec3(1e-6), lessThan(abs(rd), vec3(1e-6)));
    vec3 ta = FieldOrigin * inv, tb = (FieldOrigin + FieldSize) * inv;
    vec3 lo = min(ta, tb), hi = max(ta, tb);
    float t0 = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float t1 = min(min(hi.x, hi.y), hi.z);
    vec2 sceneDepth = texture(DepthSampler, texCoord).rg;
    float depth = sceneDepth.r;
    if (depth < 1.0) {
        vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
        t1 = min(t1, length(scene.xyz / scene.w));
    }
    // The part behind glass and water starts at the glass (HalfResVolume); with no glass it equals the scene and is empty.
    if (sceneDepth.g > 0.0) {
        if (sceneDepth.g >= 1.0) discard;
        vec4 glass = InvViewProj * vec4(vec3(texCoord, sceneDepth.g) * 2.0 - 1.0, 1.0);
        t0 = max(t0, length(glass.xyz / glass.w));
    }
    if (isnan(t0) || isnan(t1) || isinf(t1) || t1 <= t0) discard;
    const int MAX_FINE = 64;
    const int MAX_ITER = 128;
    bool jets = JetOn > 0.5;
    float stepLen = clamp(0.5 + 0.02 * t0, 0.5, 1.5);
    int coarse = int(clamp(ceil((t1 - t0) / stepLen), 4.0, 56.0));
    float dc = (t1 - t0) / float(coarse), trans = 1.0;
    float jitter = ign(gl_FragCoord.xy);
    vec3 drift = vec3(ticks * 0.01, -ticks * 0.006, ticks * 0.008);
    vec3 col = vec3(0.0);
    float dist = 0.0;
    float tt = t0 + 0.5 * dc;
    int fine = 0;
    for (int iter = 0; iter < MAX_ITER; iter++) {
        if (tt >= t1) break;
        vec3 q0 = rd * tt - FieldOrigin;
        // An empty brick jumps to its far side on the same step grid, so the samples after it do not move.
        if (brickEmpty(q0, jets)) {
            vec3 b0 = floor(q0 * 0.25) * 4.0;
            vec3 tb = (mix(b0, b0 + 4.0, greaterThan(rd, vec3(0.0))) + FieldOrigin) * inv;
            float exitT = min(min(tb.x, tb.y), tb.z);
            tt += dc * max(1.0, ceil((exitT - tt) / dc));
            continue;
        }
        // One tile lookup for the step size and both jet reads of this step.
        uvec2 tile0 = jets ? jetTile(q0) : uvec2(0u);
        // A tile with jet runs marches finer; every other step keeps the normal size.
        float dt = dc;
        if (fine < MAX_FINE && tile0.y > 0u) { dt = min(dc, 0.18); fine++; }
        // The wisps reach about one block, so far from gas the noise is not necessary.
        float f0 = field(q0);
        vec3 jOff = vec3(0.0);
        float j0 = tile0.y > 0u ? jetRuns(q0, tile0, jOff) : 0.0;
        if (j0 > 0.002) j0 *= jetOpen(q0, jOff);
        if (f0 < 0.002 && j0 < 0.002) { tt += dt; continue; }
        // Thin gas gets little jitter, so it shows no grain; dense gas keeps it against banding.
        float amp = smoothstep(0.03, 0.3, max(f0, j0));
        vec3 q = rd * (tt + (jitter - 0.5) * amp * dt) - FieldOrigin;
        // Rays go through the clear texels of cutout glass; a point in the glass cell warped into the room behind it.
        if (solidAt(q)) { tt += dt; continue; }
        vec3 w =(q + FieldCorner) * NOISE_SCALE + drift;
        // The offset grows by the flow-now texture over the tick; the CPU adds the same flow at the next build.
        vec3 uvw = clamp(q, vec3(0.5), FieldSize - 0.5) / FieldTexSize;
        vec3 grow = (texture(FieldFlow, uvw).rgb * 255.0 - 128.0) / 127.0 * FlowMax * FieldBlend;
        vec3 offA = texture(FlowOffsetA, uvw).rgb + grow;
        vec3 offB = texture(FlowOffsetB, uvw).rgb + grow;
        float n0 = fbmFlow(w, offA, offB, ticks);
        float n1 = fbmFlow(w + 7.3, offA, offB, ticks);
        float n = mix(n0, n1, 0.35);
        // Sample a little off the point, so the gas makes wisps and does not fill whole blocks.
        // A warp that ends in or cuts through a solid cell falls back to the unwarped point.
        vec3 qw = q + vec3(n0 - 0.5, (n - 0.5) * 0.5, n1 - 0.5) * 1.3;
        float f = field(solidAt(qw) || solidAt(0.5 * (q + qw)) ? q : qw);
        // The parcels add to the grid concentration, and the sum is capped at a full cell, like the grid's own limit.
        float sd = j0;
        f = min(f + sd, FIELD_MAX);
        // Strain: gas above 1 is denser and darker.
        float strain = clamp(f - 1.0, 0.0, 1.0);
        float dens = f * smoothstep(0.004, 0.04, f) * (0.45 + 0.55 * smoothstep(0.2, 0.75, n)) * (1.0 + 0.8 * strain) * Visibility;
        float a = 1.0 - exp(-dens * dt * 1.4);
        vec3 lit = mix(DarkColor, LightColor, clamp(0.35 + (n - 0.5) * 1.2 + (1.0 - f) * 0.3, 0.0, 1.0));
        lit = mix(lit, LightColor, clamp(sd * 0.25, 0.0, 1.0));
        lit = mix(lit, DarkColor * 0.45, strain * 0.7);
        col += trans * a * lit;
        dist += trans * a * tt;
        trans *= 1.0 - a;
        if (trans < 0.04) break;
        tt += dt;
    }
    float alpha = 1.0 - trans;
    if (isnan(alpha) || any(isnan(col)) || any(isinf(col)) || alpha < 0.01) discard;
    // Premultiplied: the half-resolution target holds it, and the shared upsample blends it over main.
    fragColor = vec4(col, alpha);
    volumeDepthOut = vec4(dist, alpha, 0.0, alpha);
}
