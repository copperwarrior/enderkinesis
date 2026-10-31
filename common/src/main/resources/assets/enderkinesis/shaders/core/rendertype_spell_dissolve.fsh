#version 150

#moj_import <fog.glsl>

// Each texel shows once the amount passes its own threshold, as vanilla block-break cracks do.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 lightColor;
in float seed;
in float amount;

out vec4 fragColor;

float hash(vec2 p, float k) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + k * 91.7) * 43758.5453); }

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    vec2 texel = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));
    if (color.a < 0.1 || hash(texel, floor(seed * 255.0 + 0.5)) >= amount) {
        discard;
    }
    color = vec4(color.rgb, 1.0) * lightColor * ColorModulator;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
