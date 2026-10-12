#version 150

uniform sampler2D SceneDepth;
uniform mat4 ProjMat;

in vec4 vColor;
in vec2 vOffset;
in float vRim;

out vec4 fragColor;

float viewDistance(float depth) {
    float ndc = depth * 2.0 - 1.0;
    // ProjMat[2][3] is 0 for the orthographic projection of the inventory entity view.
    return ProjMat[2][3] == 0.0
        ? (ProjMat[3][2] - ndc) / ProjMat[2][2]
        : ProjMat[3][2] / (ndc + ProjMat[2][2]);
}

// Geometry this many rim radii in front of the halo center hides the halo fully.
const float FRONT_RANGE = 3.0;

void main() {
    float falloff = clamp(1.0 - length(vOffset) / vRim, 0.0, 1.0);
    vec2 screenUv = gl_FragCoord.xy / vec2(textureSize(SceneDepth, 0));
    float gap = viewDistance(texture(SceneDepth, screenUv).r) - viewDistance(gl_FragCoord.z);
    // Geometry at the halo center or behind it gets the full glow.
    float visible = smoothstep(0.0, 1.0, gap / (vRim * FRONT_RANGE) + 1.0);
    float alpha = vColor.a * falloff * falloff * visible;
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(vColor.rgb, alpha);
}
