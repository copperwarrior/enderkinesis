#version 150

uniform sampler2D SceneDepth;
uniform mat4 ProjMat;

in vec4 vColor;

out vec4 fragColor;

float viewDistance(float depth) {
    float ndc = depth * 2.0 - 1.0;
    return ProjMat[3][2] / (ndc + ProjMat[2][2]);
}

// Half-width of the glow band where the bubble meets the scene, in blocks along the view.
const float CONTACT_WIDTH = 0.6;
const float CONTACT_ALPHA = 0.6;
// Softens the edge where the bubble surface goes behind the scene.
const float OCCLUSION_SOFTNESS = 0.05;

void main() {
    vec2 screenUv = gl_FragCoord.xy / vec2(textureSize(SceneDepth, 0));
    // Positive where the bubble is in front of the scene.
    float gap = viewDistance(texture(SceneDepth, screenUv).r) - viewDistance(gl_FragCoord.z);
    float surface = vColor.a * smoothstep(-OCCLUSION_SOFTNESS, OCCLUSION_SOFTNESS, gap);
    float contact = 1.0 - smoothstep(0.0, CONTACT_WIDTH, abs(gap));
    float alpha = min(surface + CONTACT_ALPHA * contact * contact, 1.0);
    if (alpha <= 0.0) {
        discard;
    }
    fragColor = vec4(vColor.rgb, alpha);
}
