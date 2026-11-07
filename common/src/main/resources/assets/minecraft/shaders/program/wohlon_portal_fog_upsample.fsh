#version 150

uniform sampler2D DepthSampler;
// Premultiplied RGBA from the half-res tentacle march.
uniform sampler2D HalfSampler;

uniform vec2 DepthParams;
uniform vec2 HalfSize;

in vec2 texCoord;

out vec4 fragColor;

// Bilinear weights divided by relative depth difference, so fog does not bleed across geometry edges.
const float DEPTH_TOLERANCE = 0.01;

float linearDepth(vec2 uv) {
    return DepthParams.y / (texture(DepthSampler, uv).r * 2.0 - 1.0 + DepthParams.x);
}

void main() {
    float depth = linearDepth(texCoord);
    vec2 halfPos = texCoord * HalfSize - 0.5;
    ivec2 base = ivec2(floor(halfPos));
    vec2 f = halfPos - vec2(base);
    ivec2 maxTexel = ivec2(HalfSize) - 1;

    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    float bestDiff = 1.0e30;
    vec4 best = vec4(0.0);
    for (int i = 0; i < 4; i++) {
        ivec2 o = ivec2(i & 1, i >> 1);
        ivec2 texel = clamp(base + o, ivec2(0), maxTexel);
        vec4 s = texelFetch(HalfSampler, texel, 0);
        // The same depth texel that the march of this half texel read, at its centre.
        float diff = abs(linearDepth((vec2(texel) + 0.5) / HalfSize) - depth) / depth;
        float bilinear = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        float w = bilinear / (diff + DEPTH_TOLERANCE);
        sum += w * s;
        weightSum += w;
        if (diff < bestDiff) {
            bestDiff = diff;
            best = s;
        }
    }

    vec4 colour = weightSum > 1.0e-4 ? sum / weightSum : best;
    if (colour.a <= 0.0) discard;
    // Premultiplied (wohlon_portal_fog_upsample.json blend).
    fragColor = colour;
}
