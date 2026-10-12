#version 150

uniform sampler2D DepthSampler;
uniform sampler2D HalfSampler;

uniform vec3 FogColor;
uniform vec2 DepthParams;
uniform vec2 HalfSize;

in vec2 texCoord;

out vec4 fragColor;

// Bilinear weights divided by relative depth difference, so fog does not bleed across geometry edges.
const float DEPTH_TOLERANCE = 0.01;

void main() {
    float depth = DepthParams.y / (texture(DepthSampler, texCoord).r * 2.0 - 1.0 + DepthParams.x);
    vec2 halfPos = texCoord * HalfSize - 0.5;
    ivec2 base = ivec2(floor(halfPos));
    vec2 f = halfPos - vec2(base);
    ivec2 maxTexel = ivec2(HalfSize) - 1;

    float alphaSum = 0.0;
    float weightSum = 0.0;
    float bestDiff = 1.0e30;
    float bestAlpha = 0.0;
    for (int i = 0; i < 4; i++) {
        ivec2 o = ivec2(i & 1, i >> 1);
        vec2 s = texelFetch(HalfSampler, clamp(base + o, ivec2(0), maxTexel), 0).rg;
        float bilinear = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        float diff = abs(s.g - depth) / depth;
        float w = bilinear / (diff + DEPTH_TOLERANCE);
        alphaSum += w * s.r;
        weightSum += w;
        if (diff < bestDiff) {
            bestDiff = diff;
            bestAlpha = s.r;
        }
    }

    float alpha = weightSum > 1.0e-4 ? alphaSum / weightSum : bestAlpha;
    if (alpha <= 0.0) discard;
    fragColor = vec4(FogColor, alpha);
}
