#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
// Box space: the spring axis is x = z = 0, y = 0 is where the spring wells up.
uniform float Time;
uniform float Surface;
// 0 to 1 while an offered item charges; Burst flashes when it is ready.
uniform float Charge;
uniform float Burst;
// How far the turbulence has risen; accumulated on the CPU so a charge changes its speed without a jump.
uniform float Rise;
uniform vec3 MistColour;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 36;
const float MIST_DENSITY = 1.2;
const float MIST_EMISSION = 0.45;
const float OPACITY = 0.55;
// Scales the whole mist field.
const float DENSITY = 0.3;
// Density reaches 0 this far inside the box, so its faces never cut the volume.
const float EDGE_FADE = 1.2;

float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
        mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y),
        f.z);
}

float fbm(vec3 p) {
    return 0.5 * noise(p) + 0.3 * noise(p * 2.1 + 3.7) + 0.2 * noise(p * 4.3 + 7.1);
}

float sceneDistance(sampler2D sampler, vec2 texCoord) {
    float depth = texture(sampler, texCoord).r;
    if (depth >= 1.0) return 1.0e6;
    vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
    return length(scene.xyz / scene.w);
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
    float t1 = min(tFar, sceneDistance(DepthSampler, texCoord));
    if (t1 <= t0) discard;

    float dt = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float energy = 1.0 + 0.3 * Charge + 0.5 * Burst;
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < STEPS; i++) {
        float t = t0 + (float(i) + jitter) * dt;
        vec3 p = CamLocal + dir * t;
        float r = length(p.xz);
        // The field scrolls up and twists, so the upwelling churns instead of drifting.
        float twist = Time * 1.3 + p.y * 0.8;
        vec3 q = vec3(p.x * cos(twist) - p.z * sin(twist), p.y, p.x * sin(twist) + p.z * cos(twist));
        float turb = fbm(q * vec3(1.8, 1.1, 1.8) - vec3(0.0, Rise, 0.0));

        float under = 1.0 - smoothstep(Surface - 0.15, Surface + 0.05, p.y);
        float above = p.y - Surface;
        float mistR = 0.9 + 0.6 * max(above, 0.0) + 0.2 * Charge + 0.3 * Burst;
        float plume = exp(-max(above, 0.0) * 1.4 / (1.0 + 0.3 * Charge + 0.4 * Burst));
        float mist = (1.0 - under) * exp(-r * r / (mistR * mistR)) * plume * smoothstep(0.3, 0.75, turb);

        // Radial at the sides, so no square corners show; the water surface already ends the mist below.
        float edge = (1.0 - smoothstep(BoxMax.x - EDGE_FADE, BoxMax.x, r)) * (1.0 - smoothstep(BoxMax.y - EDGE_FADE, BoxMax.y, p.y));
        mist *= edge * DENSITY;
        float a = 1.0 - exp(-mist * MIST_DENSITY * dt);
        vec3 light = MistColour * mist * MIST_EMISSION * energy;
        emitted += through * light * dt;
        through *= 1.0 - a * 0.5;
    }
    float alpha = (1.0 - through) * OPACITY;
    if (alpha < 0.003 && max(max(emitted.r, emitted.g), emitted.b) < 0.003) discard;
    fragColor = vec4(emitted, alpha);
}
