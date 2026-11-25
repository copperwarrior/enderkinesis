#version 150

#moj_import <fog.glsl>

// Sampler0 = player skin, Sampler1 = block atlas (animated wogor log sprite at LogRect).
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// Sprite rect in the atlas (u0, v0, u1, v1). The atlas holds the current animation frame.
uniform vec4 LogRect;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec2 texCoord0;

out vec4 fragColor;

const float LOG_PIXELS = 16.0;
const vec3 LUMA = vec3(0.299, 0.587, 0.114);

void main() {
    vec4 skin = texture(Sampler0, texCoord0);
    if (skin.a < 0.1) discard;

    // The same pixel of the log as of the skin, so the bark tiles every 16 skin pixels.
    vec2 skinPixel = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));
    vec2 logPixel = mod(skinPixel, LOG_PIXELS) + 0.5;
    vec2 logUv = mix(LogRect.xy, LogRect.zw, logPixel / LOG_PIXELS);
    // Level 0 only: the computed UV jumps at each tile edge, which would pick a far mip level.
    vec3 bark = textureLod(Sampler1, logUv, 0.0).rgb;

    float lum = dot(skin.rgb, LUMA);
    vec4 color = vec4(clamp(bark * (0.9 + 1.3 * lum), 0.0, 1.0), 1.0) * vertexColor * ColorModulator;
    color *= vec4(lightMapColor.rgb, 1.0);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
