#version 150

uniform sampler2D DepthSampler;
// Glass and water depth, for the part of a march behind them; read only when NearOn is 1.
uniform sampler2D NearDepthSampler;
uniform float NearOn;

out vec4 fragColor;

// Checkerboard min/max of each 2x2 block keeps both near and far edges at half resolution.
float pick(sampler2D depth, ivec2 base, ivec2 maxTexel, bool near) {
    float d0 = texelFetch(depth, min(base, maxTexel), 0).r;
    float d1 = texelFetch(depth, min(base + ivec2(1, 0), maxTexel), 0).r;
    float d2 = texelFetch(depth, min(base + ivec2(0, 1), maxTexel), 0).r;
    float d3 = texelFetch(depth, min(base + ivec2(1, 1), maxTexel), 0).r;
    return near ? min(min(d0, d1), min(d2, d3)) : max(max(d0, d1), max(d2, d3));
}

// R: the depth the march stops at. G: the depth it starts at behind glass, or 0.
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 maxTexel = textureSize(DepthSampler, 0) - 1;
    ivec2 base = p * 2;
    bool near = ((p.x + p.y) & 1) == 0;
    float d = pick(DepthSampler, base, maxTexel, near);
    // The same texel pick as the march in front, so the two parts meet where it stops.
    float glass = NearOn > 0.5 ? pick(NearDepthSampler, base, maxTexel, near) : 0.0;
    fragColor = vec4(d, glass, 0.0, 1.0);
}
