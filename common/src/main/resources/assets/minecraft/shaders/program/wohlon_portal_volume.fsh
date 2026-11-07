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

// Indices of the march samples t0 + (i + jitter) * dt that can lie in [tA, tB], one more on each side for float error.
ivec2 stepRange(float tA, float tB, float t0, float dt, float jitter) {
    vec2 r = vec2(floor((tA - t0) / dt - jitter) - 1.0, floor((tB - t0) / dt - jitter) + 2.0);
    return ivec2(clamp(r, 0.0, float(STEPS)));
}

// The samples with rn <= 1; the step grid stays the box's, so the image does not change.
ivec2 sphereSteps(vec3 dir, float t0, float dt, float jitter) {
    float b = dot(CamLocal, dir);
    // A little past the surface, so float error cannot drop an edge sample.
    float disc = b * b - (dot(CamLocal, CamLocal) - Radius * Radius * 1.002);
    if (disc <= 0.0) return ivec2(0);
    float s = sqrt(disc);
    return stepRange(-b - s, -b + s, t0, dt, jitter);
}

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
    // (cos, sin) of Spin and 0.7 Spin; each step turns them by theta with angle addition.
    vec2 spinA = vec2(cos(Spin), sin(Spin));
    vec2 spinB = vec2(cos(Spin * 0.7), sin(Spin * 0.7));
    ivec2 steps = sphereSteps(dir, t0, dt, jitter);
    for (int i = steps.x; i < steps.y; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        float rn = length(p) / Radius;
        if (rn > 1.0) continue;
        // A soft core round the Heart, in a faint aura shell that fades to nothing at the edge.
        float core = exp(-rn * rn / (CORE_WIDTH * CORE_WIDTH));
        float shell = exp(-(rn - 0.55) * (rn - 0.55) / 0.06) * (1.0 - rn);
        // Broad, low-contrast patches: the aura brightens and dims in places as they turn.
        // (cos, sin) of theta = atan(p.z, p.x + 1e-6), and of 2 theta.
        vec2 q = vec2(p.x + 1.0e-6, p.z);
        vec2 th = q * inversesqrt(max(dot(q, q), 1.0e-24));
        vec2 th2 = vec2(th.x * th.x - th.y * th.y, 2.0 * th.x * th.y);
        float psi = Spin + p.y * 1.5;
        float patches = 0.8 + 0.2 * (th2.x * cos(psi) - th2.y * sin(psi)) * (th.x * spinB.x + th.y * spinB.y);
        vec3 colour = mix(TEAL, LIGHT_BLUE, 0.5 + 0.5 * (th.x * spinA.x - th.y * spinA.y));
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
