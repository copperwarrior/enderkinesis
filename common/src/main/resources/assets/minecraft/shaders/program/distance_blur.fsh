#version 150

// RGB colour, A blur amount (0 to 1) of each half texel.
uniform sampler2D BlurSampler;

uniform vec2 Direction;
// Half texels at blur amount 1; keep it at or below MAX_TAPS.
uniform float MaxRadius;
// 1 on the second pass, which writes the premultiplied haze layer for volume_upsample.
uniform float Final;
uniform float FadeIn;
uniform float FogPull;
uniform vec3 FogColor;
uniform float Strength;

out vec4 fragColor;

const int MAX_TAPS = 8;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 maxTexel = textureSize(BlurSampler, 0) - 1;
    ivec2 dir = ivec2(Direction);
    vec4 centre = texelFetch(BlurSampler, p, 0);
    float radius = centre.a * MaxRadius;
    int reach = int(ceil(radius));
    float sigma = max(radius * 0.5, 0.5);
    float invTwoSigmaSq = 0.5 / (sigma * sigma);

    vec3 sum = centre.rgb;
    float weightSum = 1.0;
    for (int k = 1; k <= MAX_TAPS; k++) {
        if (k > reach) break;
        float gauss = exp(-float(k * k) * invTwoSigmaSq);
        for (int s = -1; s <= 1; s += 2) {
            vec4 tap = texelFetch(BlurSampler, clamp(p + dir * (k * s), ivec2(0), maxTexel), 0);
            // A tap reaches only as far as its own radius, so sharp near texels never spread onto the haze.
            float w = gauss * clamp(tap.a * MaxRadius - float(k) + 1.0, 0.0, 1.0);
            sum += w * tap.rgb;
            weightSum += w;
        }
    }
    vec3 blurred = sum / weightSum;
    if (Final < 0.5) {
        fragColor = vec4(blurred, centre.a);
        return;
    }
    float alpha = clamp(centre.a / FadeIn, 0.0, 1.0) * Strength;
    vec3 haze = mix(blurred, FogColor, centre.a * FogPull);
    fragColor = vec4(haze * alpha, alpha);
}
