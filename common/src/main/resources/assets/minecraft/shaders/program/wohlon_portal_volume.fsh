#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Portal space: world axes, the portal centre at the origin.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
uniform float Radius;
// Slow turn of the aura patches, in radians.
uniform float Spin;
// Distance fade and breathing, multiplied on the host.
uniform float Strength;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 24;
const float CORE_WIDTH = 0.28;
const float CORE_EMISSION = 0.45;
const float AURA_EMISSION = 0.15;
const float OPACITY = 0.25;
const vec3 LIGHT_BLUE = vec3(0.50, 0.80, 1.00);
const vec3 TEAL = vec3(0.10, 0.70, 0.70);
const vec3 CYAN = vec3(0.00, 1.00, 1.00);

void main() {
    vec2 texCoord = gl_FragCoord.xy / ScreenSize;
    vec3 toFar = localPos - CamLocal;
    float tFar = length(toFar);
    vec3 dir = toFar / tFar;
    vec3 inv = 1.0 / dir;
    vec3 ta = (BoxMin - CamLocal) * inv;
    vec3 tb = (BoxMax - CamLocal) * inv;
    vec3 tMin = min(ta, tb);
    float t0 = max(max(max(tMin.x, tMin.y), tMin.z), 0.0);
    float t1 = tFar;
    float depth = texture(DepthSampler, texCoord).r;
    if (depth < 1.0) {
        vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
        t1 = min(t1, length(scene.xyz / scene.w));
    }
    if (t1 <= t0) discard;

    float dt = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 emitted = vec3(0.0);
    float veil = 0.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        float rn = length(p) / Radius;
        if (rn > 1.0) continue;
        // A soft core round the Heart, in a faint aura shell that fades to nothing at the edge.
        float core = exp(-rn * rn / (CORE_WIDTH * CORE_WIDTH));
        float shell = exp(-(rn - 0.55) * (rn - 0.55) / 0.06) * (1.0 - rn);
        // Broad, low-contrast patches: the aura brightens and dims in places as they turn.
        float theta = atan(p.z, p.x + 1.0e-6);
        float patches = 0.8 + 0.2 * cos(2.0 * theta + Spin + p.y * 1.5) * cos(theta - Spin * 0.7);
        vec3 colour = mix(TEAL, LIGHT_BLUE, 0.5 + 0.5 * cos(theta + Spin));
        vec3 light = mix(LIGHT_BLUE, CYAN, 0.3) * core * CORE_EMISSION + colour * shell * patches * AURA_EMISSION;
        emitted += light * dt;
        veil += shell * dt;
    }
    float alpha = (1.0 - exp(-veil)) * OPACITY * Strength;
    vec3 outColour = emitted * Strength;
    if (alpha < 0.003 && max(max(outColour.r, outColour.g), outColour.b) < 0.003) discard;
    // Premultiplied (wohlon_portal_volume.json blend).
    fragColor = vec4(outColour, alpha);
}
