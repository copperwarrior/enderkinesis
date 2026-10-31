#version 150

#moj_import <fog.glsl>

// Vanilla's glint, scrolled the same way, but its brightness taken in the vertex colour (the
// spell's) instead of the texture's purple.

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float GlintAlpha;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 tex = texture(Sampler0, texCoord0) * ColorModulator;
    if (tex.a < 0.1) {
        discard;
    }
    float glint = max(tex.r, max(tex.g, tex.b)) * 1.4;
    float fade = linear_fog_fade(vertexDistance, FogStart, FogEnd) * GlintAlpha;
    fragColor = vec4(vertexColor.rgb * glint * fade, tex.a);
}
