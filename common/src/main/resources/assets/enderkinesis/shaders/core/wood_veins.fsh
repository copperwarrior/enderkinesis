#version 150

#moj_import <fog.glsl>

// Sampler0 = vein mask (red = grow order / 255, 0 = no vein), Sampler1 = overlay, Sampler3 = block atlas.
uniform sampler2D Sampler0;
uniform sampler2D Sampler3;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// The animated wogor log sprite in the atlas (u0, v0, u1, v1).
uniform vec4 LogRect;
// Wood fraction of this player: a vein pixel shows when its order is at most this.
uniform float Growth;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;

out vec4 fragColor;

const float SKIN_PIXELS = 64.0;
const float LOG_PIXELS = 16.0;

void main() {
    vec2 skinPixel = floor(texCoord0 * SKIN_PIXELS);
    float order = texelFetch(Sampler0, ivec2(skinPixel), 0).r;
    if (order <= 0.0 || order > Growth + 0.5 / 255.0) discard;

    vec2 logUv = mix(LogRect.xy, LogRect.zw, (mod(skinPixel, LOG_PIXELS) + 0.5) / LOG_PIXELS);
    // Level 0 only: the UV jumps at each tile edge, which would pick a far mip level.
    vec4 color = vec4(textureLod(Sampler3, logUv, 0.0).rgb, 1.0) * vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= vec4(lightMapColor.rgb, 1.0);
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
