#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
// Box space: the rite's centre is x = z = 0, and y = 0 is the floor it stands on.
uniform float Time;
// How far the fog has spread from the rite, in blocks; Fade thins all of it at the end.
uniform float Front;
uniform float Fade;
uniform float Radius;
// The light the fog stands in, so it does not glow at night.
uniform float Brightness;
uniform vec3 FogColour;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 32;
// Low: a thin haze the world shows through.
const float DENSITY = 0.11;
const float LAYER_HEIGHT = 3.2;
const float FRONT_FEATHER = 3.0;
const float RAGGED = 3.5;
const float RIM = 3.0;

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
    if (t1 <= t0 || Fade <= 0.0) discard;

    float dt = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 drift = vec3(Time * 0.05, Time * 0.01, Time * 0.03);
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < STEPS; i++) {
        float t = t0 + (float(i) + jitter) * dt;
        vec3 p = CamLocal + dir * t;
        float r = length(p.xz);
        // The front is ragged, so the fog creeps out in tongues and not as a clean ring.
        float angle = atan(p.z, p.x);
        float front = Front + (noise(vec3(cos(angle) * 2.0, sin(angle) * 2.0, Time * 0.1)) - 0.5) * RAGGED;
        float spread = 1.0 - smoothstep(front - FRONT_FEATHER, front, r);
        float rim = 1.0 - smoothstep(Radius - RIM, Radius, r);
        // Thickest just above the floor, thinning upward; the box bottom fades in below the floor.
        float layer = exp(-max(p.y, 0.0) / LAYER_HEIGHT) * smoothstep(BoxMin.y, BoxMin.y + 1.5, p.y)
            * (1.0 - smoothstep(BoxMax.y - 2.0, BoxMax.y, p.y));
        float wisps = smoothstep(0.3, 0.75, fbm(p * 0.2 + drift));
        float d = DENSITY * spread * rim * layer * wisps * Fade;
        float a = 1.0 - exp(-d * dt);
        emitted += through * FogColour * Brightness * a;
        through *= 1.0 - a;
    }
    float alpha = 1.0 - through;
    if (alpha < 0.003) discard;
    fragColor = vec4(emitted, alpha);
}
