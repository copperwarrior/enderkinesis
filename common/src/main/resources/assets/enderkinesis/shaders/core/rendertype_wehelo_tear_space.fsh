#version 150

#moj_import <fog.glsl>

// rendertype_pocket_portal, with its own depth test and a soft fade where the scene is just behind.
// Vertex rgb tints the space; vertex alpha is how much space shows.

uniform sampler2D Sampler0;  // end_sky.png
uniform sampler2D Sampler1;  // end_portal.png
uniform sampler2D DepthSampler;

uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform float GameTime;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec4 texProj0;
in vec4 vertexColor;
in float vertexDistance;
in float viewDepth;

out vec4 fragColor;

// Blocks in front of the scene at which the tear is fully shown.
const float SOFTNESS = 0.5;

#moj_import <end_portal_layers.glsl>

// In blocks along the view.
float sceneDepth() {
    float ndc = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0;
    return -ProjMat[3][2] / (-ndc - ProjMat[2][2]);
}

void main() {
    float alpha = vertexColor.a * clamp((sceneDepth() - viewDepth) / SOFTNESS, 0.0, 1.0);
    if (alpha < 0.004) discard;
    vec3 color = endPortalColor(texProj0) * vertexColor.rgb;
    fragColor = linear_fog(vec4(color, alpha), vertexDistance, FogStart, FogEnd, FogColor);
}
