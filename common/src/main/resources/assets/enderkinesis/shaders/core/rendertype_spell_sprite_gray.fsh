#version 150

#moj_import <fog.glsl>

// As rendertype_spell_sprite, but the texture is recoloured by its brightness first, so a
// coloured texture can take the vertex colour instead: Crystallized's amethyst, turned white.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    float lum = min(1.0, dot(tex.rgb, vec3(0.299, 0.587, 0.114)) * 1.3);
    vec4 color = vec4(vec3(lum), tex.a) * vertexColor * ColorModulator;
    if (color.a < 0.02) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
