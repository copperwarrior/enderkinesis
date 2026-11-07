#version 150

// DepthSampler is the depth test: draw with no GL depth test.

uniform sampler2D DepthSampler;

uniform mat4 ProjMat;
uniform vec4 ColorModulator;
uniform vec2 ScreenSize;
uniform float FadeDistance;

in vec4 vertexColor;
in float viewZ;

out vec4 fragColor;

// The GUI projection is orthographic, so depth is linear in view z.
float sceneViewZ() {
    float ndc = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0;
    return (ndc - ProjMat[3][2]) / ProjMat[2][2];
}

void main() {
    vec4 color = vertexColor * ColorModulator;
    color.a *= clamp((viewZ - sceneViewZ()) / FadeDistance, 0.0, 1.0);
    if (color.a < 0.004) {
        discard;
    }
    fragColor = color;
}
