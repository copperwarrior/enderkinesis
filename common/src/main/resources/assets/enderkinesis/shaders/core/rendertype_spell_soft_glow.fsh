#version 150

#moj_import <fog.glsl>

// Fades where the scene is just behind the glow, so it does not cut a hard line through it.
// DepthSampler is the depth test: draw with no GL depth test.

uniform sampler2D Sampler0;
uniform sampler2D DepthSampler;

uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;
in float viewDepth;

out vec4 fragColor;

// Distance in front of the scene, in blocks, at which the glow has full strength.
const float SOFTNESS = 0.3;

// In blocks along the view.
float sceneDepth() {
    float depth = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r;
    float ndc = depth * 2.0 - 1.0;
    return -ProjMat[3][2] / (-ndc - ProjMat[2][2]);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    color.a *= clamp((sceneDepth() - viewDepth) / SOFTNESS, 0.0, 1.0);
    if (color.a < 0.004) {
        discard;
    }
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
