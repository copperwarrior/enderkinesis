#version 150

#moj_import <fog.glsl>

// Rims of the tear, MAX-blended: own depth test, soft where the scene is just behind, and fogged to nothing.

uniform sampler2D DepthSampler;

uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform float FogStart;
uniform float FogEnd;

in vec4 vertexColor;
in float vertexDistance;
in float viewDepth;

out vec4 fragColor;

const float SOFTNESS = 0.5;

float sceneDepth() {
    float ndc = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0;
    return -ProjMat[3][2] / (-ndc - ProjMat[2][2]);
}

void main() {
    float alpha = vertexColor.a * clamp((sceneDepth() - viewDepth) / SOFTNESS, 0.0, 1.0)
        * linear_fog_fade(vertexDistance, FogStart, FogEnd);
    if (alpha < 0.004) discard;
    // MAX ignores the blend factors, so the alpha goes into the colour here.
    fragColor = vec4(vertexColor.rgb * alpha, alpha);
}
