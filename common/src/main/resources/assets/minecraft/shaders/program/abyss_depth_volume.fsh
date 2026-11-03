#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Sphere space: the centre at the origin, world axes.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
uniform float Radius;
// Seconds since the cast started.
uniform float Time;
uniform float Strength;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 28;
// Ygann palette: black ink with the violet of the writhing tentacles.
const vec3 DEEP = vec3(0.015, 0.0, 0.035);
const vec3 GLOW = vec3(0.42, 0.17, 0.85);
const float ABSORPTION = 1.1;
const float DEEP_EMISSION = 0.6;
const float GLOW_EMISSION = 1.4;
// Pressure shells per radius, and their inward speed in radii per second.
const float SHELLS = 3.0;
const float SHELL_SPEED = 0.45;
// Radians per second at the rim; the core turns faster.
const float SWIRL = 0.6;
const float NOISE_FREQ = 0.9;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float valueNoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
        mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y),
        f.z);
}

// Returns the ink density at p; glow is the light of the pressure shells there.
float density(vec3 p, out float glow) {
    glow = 0.0;
    float r = length(p) / Radius;
    if (r >= 1.0) return 0.0;
    // Reaches zero with zero slope at the rim, so the sphere has no hard edge.
    float rim = 1.0 - r * r;
    float envelope = rim * rim;
    float angle = Time * SWIRL * (2.0 - r);
    float c = cos(angle);
    float s = sin(angle);
    vec3 q = vec3(c * p.x - s * p.z, p.y, s * p.x + c * p.z);
    vec3 n = q * NOISE_FREQ + vec3(0.0, Time * 0.2, 0.0);
    float f = valueNoise(n) * 0.65 + valueNoise(n * 2.3 + 7.1) * 0.35;
    // The phase falls with time at a fixed r, so the shells close in on the centre.
    float shell = 0.5 + 0.5 * sin(6.2831853 * (r * SHELLS + Time * SHELL_SPEED));
    glow = envelope * pow(shell, 10.0) * smoothstep(0.35, 0.8, f) * smoothstep(0.0, 0.35, r);
    return envelope * (0.45 + 0.55 * f);
}

void main() {
    vec2 texCoord = gl_FragCoord.xy / ScreenSize;
    vec3 toFar = localPos - CamLocal;
    float tFar = length(toFar);
    vec3 dir = toFar / tFar;

    // Ray against the sphere, not the box, so no step falls in the empty corners.
    float b = dot(CamLocal, dir);
    float h = b * b - (dot(CamLocal, CamLocal) - Radius * Radius);
    if (h <= 0.0) discard;
    h = sqrt(h);
    float t0 = max(-b - h, 0.0);
    float t1 = min(-b + h, tFar);
    float depth = texture(DepthSampler, texCoord).r;
    if (depth < 1.0) {
        vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
        t1 = min(t1, length(scene.xyz / scene.w));
    }
    if (t1 <= t0) discard;

    float stepLen = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * stepLen);
        float glow;
        float d = density(p, glow) * Strength;
        if (d <= 0.0) continue;
        emitted += through * (DEEP * d * DEEP_EMISSION + GLOW * glow * Strength * GLOW_EMISSION) * stepLen;
        through *= exp(-d * ABSORPTION * stepLen);
    }
    float alpha = 1.0 - through;
    if (alpha < 0.004 && max(max(emitted.r, emitted.g), emitted.b) < 0.004) discard;
    // Premultiplied output (abyss_depth_volume.json blend): the ink covers the scene, the shells add light.
    fragColor = vec4(emitted, alpha);
}
