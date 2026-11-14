#version 150

uniform sampler2D DepthSampler;
// Premultiplied RGBA from the half-res march, or from its VolumeHistory.
uniform sampler2D HalfSampler;
// The raw depth each half texel marched to (volume_half_depth).
uniform sampler2D HalfDepthSampler;
// Main before and after the translucent layer (VolumeDepth); read only when BehindOn is 1.
uniform sampler2D OpaqueColourSampler;
// Alpha is 1 - transmittance of the layer: main alpha was 0 before it.
uniform sampler2D GlassColourSampler;

uniform vec2 DepthParams;
uniform vec2 HalfSize;
uniform float DepthTolerance;
// 0 to 63: moves the dither each frame, so its pattern averages out in time.
uniform float DitherFrame;
uniform float BehindOn;

in vec2 texCoord;

out vec4 fragColor;

float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float linearDepth(float raw) {
    return DepthParams.y / (raw * 2.0 - 1.0 + DepthParams.x);
}

float depthAt(ivec2 pixel, ivec2 maxPixel) {
    return linearDepth(texelFetch(DepthSampler, clamp(pixel, ivec2(0), maxPixel), 0).r);
}

// A half texel can be 2.5 full pixels off along each axis: 1.5 from its centre, 1 from the checkerboard min/max pick.
const float SLOPE_SPAN = 3.0;

// Joint bilateral upsampling: bilinear weight x depth similarity over the 4 nearest half texels.
// The similarity scale follows the local depth slope, so grazing ground filters smoothly.
// Where no texel is near in depth, the nearest in depth wins.
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 maxPixel = textureSize(DepthSampler, 0) - 1;
    float depth = depthAt(pixel, maxPixel);
    // The smaller one-sided step on each axis, so a silhouette next to the pixel does not count as slope.
    float slopeX = min(abs(depthAt(pixel + ivec2(1, 0), maxPixel) - depth),
        abs(depth - depthAt(pixel - ivec2(1, 0), maxPixel)));
    float slopeY = min(abs(depthAt(pixel + ivec2(0, 1), maxPixel) - depth),
        abs(depth - depthAt(pixel - ivec2(0, 1), maxPixel)));
    float invSigma = 1.0 / (DepthTolerance * depth + SLOPE_SPAN * (slopeX + slopeY));

    vec2 halfPos = texCoord * HalfSize - 0.5;
    ivec2 base = ivec2(floor(halfPos));
    vec2 f = halfPos - vec2(base);
    ivec2 maxTexel = ivec2(HalfSize) - 1;

    vec4 s[4];
    float diff[4];
    float bestDiff = 1.0e30;
    for (int i = 0; i < 4; i++) {
        ivec2 texel = clamp(base + ivec2(i & 1, i >> 1), ivec2(0), maxTexel);
        s[i] = texelFetch(HalfSampler, texel, 0);
        diff[i] = abs(linearDepth(texelFetch(HalfDepthSampler, texel, 0).r) - depth);
        bestDiff = min(bestDiff, diff[i]);
    }

    vec4 sum = vec4(0.0);
    float weightSum = 0.0;
    for (int i = 0; i < 4; i++) {
        // Relative to the best texel, so it always has full similarity and a lone match is not lost.
        float d = (diff[i] - bestDiff) * invSigma;
        // The floor keeps the best texel when its bilinear weight is 0.
        float w = (((i & 1) == 1 ? f.x : 1.0 - f.x) * ((i >> 1) == 1 ? f.y : 1.0 - f.y) + 1.0e-4) * exp(-d * d);
        sum += w * s[i];
        weightSum += w;
    }

    vec4 colour = sum / weightSum;
    if (colour.a <= 0.0) discard;
    // The layer blends lerp-wise, so main = K + T * opaque; putting the gas under it gives K + T * (gas over opaque).
    if (BehindOn > 0.5) {
        vec4 glass = texelFetch(GlassColourSampler, pixel, 0);
        vec3 opaque = texelFetch(OpaqueColourSampler, pixel, 0).rgb;
        colour = vec4(glass.rgb + (1.0 - glass.a) * (colour.rgb - colour.a * opaque), 1.0);
    }
    // Main is 8-bit and the rounding is of the blended pixel, so the dither is full strength at any alpha:
    // triangular ±1 step (two interleaved gradient noises), moved each frame.
    vec2 p = gl_FragCoord.xy + 5.588238 * DitherFrame;
    float dither = ign(p) + ign(p + vec2(47.0, 17.0)) - 1.0;
    colour.rgb = max(colour.rgb + dither / 255.0, 0.0);
    fragColor = colour;
}
