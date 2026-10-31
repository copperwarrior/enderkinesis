#version 150

uniform sampler2D DepthSampler;
uniform mat4 ViewProj;
uniform vec2 DepthParams;
uniform vec2 ScreenSize;
// Camera-relative, world axes.
uniform vec3 GlowCentre;
uniform float Radius;
uniform float Strength;
uniform vec3 GlowColour;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 24;
const float PI = 3.14159265;
// Caps the emission's log peak at the middle, so one sample there does not flash.
const float CORE = 0.02;

// Distance to the scene along the unit view ray [d], world axes. Clip w is view depth.
float sceneDistance(vec3 d) {
    float ndc = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0;
    return DepthParams.y / (ndc + DepthParams.x) / (ViewProj * vec4(d, 0.0)).w;
}

void main() {
    vec3 d = normalize(viewPos);
    vec3 eye = -GlowCentre;
    float b = dot(eye, d);
    float h = b * b - (dot(eye, eye) - Radius * Radius);
    if (h <= 0.0) discard;
    float root = sqrt(h);
    float t0 = max(-b - root, 0.0);
    float t1 = min(-b + root, sceneDistance(d));
    if (t1 <= t0) discard;

    // Emission (Strength / (PI Radius)) acosh(Radius / r) is the Abel inverse of a cone: each full chord sums to Strength (1 - offset / Radius).
    // So only the scene and an eye inside the glow change the result.
    float stepLen = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float sum = 0.0;
    for (int i = 0; i < STEPS; i++) {
        float t = t0 + (float(i) + jitter) * stepLen;
        float x = Radius / max(length(eye + d * t), CORE * Radius);
        sum += log(x + sqrt(max(x * x - 1.0, 0.0)));
    }
    float a = Strength / (PI * Radius) * sum * stepLen;
    if (a < 0.004) discard;
    fragColor = vec4(GlowColour, min(a, 1.0));
}
