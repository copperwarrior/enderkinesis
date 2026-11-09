#version 150

// This frame's march, premultiplied. Valid only inside Rect.
uniform sampler2D CurrentSampler;
// The last resolve, linear filter. Valid only inside PrevRect.
uniform sampler2D HistorySampler;
uniform sampler2D HalfDepthSampler;
// R: transmittance-weighted march distance × alpha, G: alpha (premultiplied). Read only when UseVolumeDepth is 1.
uniform sampler2D VolumeDepthSampler;

uniform mat4 InvViewProj;
uniform mat4 PrevViewProj;
// Camera now minus camera then: the view matrices are camera-relative.
uniform vec3 CamDelta;
uniform vec2 HalfSize;
// x0, y0, x1, y1 in half texels, x1 and y1 exclusive.
uniform vec4 Rect;
uniform vec4 PrevRect;
uniform float HistoryWeight;
uniform float UseVolumeDepth;

out vec4 fragColor;

// Variance clip box: mean ± GAMMA σ of the 3x3 neighbourhood, inside its min/max (Salvi 2016).
const float GAMMA = 1.25;
// The history weight falls to MOVING_WEIGHT × HistoryWeight as the reprojection moves 0.5 to 6 half texels.
const float MOVING_WEIGHT = 0.6;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 cur = texelFetch(CurrentSampler, p, 0);
    if (HistoryWeight <= 0.0) {
        fragColor = cur;
        return;
    }

    // The gas sits at its own march depth, not at the surface behind it; reprojecting at the surface smears it on a move.
    vec2 uv = gl_FragCoord.xy / HalfSize;
    vec4 world = InvViewProj * vec4(vec3(uv, texelFetch(HalfDepthSampler, p, 0).r) * 2.0 - 1.0, 1.0);
    vec3 pos = world.xyz / world.w;
    vec2 vd = texelFetch(VolumeDepthSampler, p, 0).rg;
    if (UseVolumeDepth > 0.5 && vd.g > 0.01) pos = normalize(pos) * (vd.r / vd.g);
    vec4 prevClip = PrevViewProj * vec4(pos + CamDelta, 1.0);
    vec2 prevPos = (prevClip.xy / prevClip.w * 0.5 + 0.5) * HalfSize;
    if (prevClip.w <= 0.0 || any(lessThan(prevPos, PrevRect.xy + 0.5)) || any(greaterThan(prevPos, PrevRect.zw - 0.5))) {
        fragColor = cur;
        return;
    }

    ivec2 lo = ivec2(Rect.xy);
    ivec2 hi = ivec2(Rect.zw) - 1;
    vec4 nMin = cur;
    vec4 nMax = cur;
    vec4 m1 = vec4(0.0);
    vec4 m2 = vec4(0.0);
    for (int i = 0; i < 9; i++) {
        vec4 s = texelFetch(CurrentSampler, clamp(p + ivec2(i % 3 - 1, i / 3 - 1), lo, hi), 0);
        nMin = min(nMin, s);
        nMax = max(nMax, s);
        m1 += s;
        m2 += s * s;
    }
    vec4 mean = m1 / 9.0;
    vec4 sigma = sqrt(max(m2 / 9.0 - mean * mean, 0.0));
    vec4 boxMin = max(nMin, mean - GAMMA * sigma);
    vec4 boxMax = min(nMax, mean + GAMMA * sigma);

    // Clip toward the box centre on one line through rgb and alpha together, so premultiplied colour keeps its ratio.
    vec4 hist = texture(HistorySampler, prevPos / HalfSize);
    vec4 centre = 0.5 * (boxMax + boxMin);
    vec4 extent = 0.5 * (boxMax - boxMin) + 1.0e-4;
    vec4 off = hist - centre;
    vec4 u = abs(off / extent);
    float m = max(max(u.r, u.g), max(u.b, u.a));
    if (m > 1.0) hist = centre + off / m;

    float moved = length(prevPos - gl_FragCoord.xy);
    float weight = HistoryWeight * mix(1.0, MOVING_WEIGHT, smoothstep(0.5, 6.0, moved));
    fragColor = mix(cur, hist, weight);
}
