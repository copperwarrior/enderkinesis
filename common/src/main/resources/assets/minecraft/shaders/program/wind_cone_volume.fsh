#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Cone space: the casting hand at the origin, the stream along +X.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
uniform float Range;
uniform float TanHalf;
uniform float ApexRadius;
// Seconds since the cast started.
uniform float Time;
uniform float Strength;
uniform vec3 Colour;
uniform float Emission;
uniform float Absorption;
// Blocks per second along the axis.
uniform float FlowSpeed;
// Radians per block along the axis.
uniform float Twist;
// Radians per second about the axis.
uniform float SpinRate;
// Lateral slope of the lagging tail at the far end. The centre line leaves the hand along +X and
// bends quadratically, so it reaches (Range, Bend * Range).
uniform vec2 Bend;
uniform float FadeIn;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 24;
// Noise cells per block along the axis; low, so the gusts read as long streaks.
const float AXIAL_FREQ = 0.18;
// Noise cells across the cone radius; in cone-relative units, so the gusts widen with the cone.
const float RADIAL_FREQ = 2.2;
const float FADE_OUT_START = 0.4;
// The same flow spreads over a wider cross-section further out.
const float SPREAD_DIM = 0.12;

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

// Returns the density at p; core is 1 on the axis and 0 at the cone wall.
float density(vec3 p, out float core) {
    core = 0.0;
    float radius = p.x * TanHalf + ApexRadius;
    vec2 yz = p.yz - Bend * (p.x * p.x / Range);
    float r = length(yz) / radius;
    if (p.x <= 0.0 || p.x >= Range || r >= 1.0) return 0.0;
    core = 1.0 - r;
    // Reaches zero with zero slope at the wall, so the cone has no visible lip.
    float wall = 1.0 - r * r;
    float envelope = exp(-2.0 * r * r) * wall * wall
        * smoothstep(0.0, FadeIn, p.x)
        * (1.0 - smoothstep(FADE_OUT_START * Range, Range, p.x))
        / (1.0 + p.x * SPREAD_DIM);
    float angle = Twist * p.x - SpinRate * Time;
    float c = cos(angle);
    float s = sin(angle);
    vec2 q = mat2(c, -s, s, c) * (yz / radius);
    vec3 n = vec3((p.x - Time * FlowSpeed) * AXIAL_FREQ, q * RADIAL_FREQ);
    float f = valueNoise(n) * 0.65 + valueNoise(n * 2.3 + 7.1) * 0.35;
    return envelope * smoothstep(0.25, 0.9, f);
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

    float stepLen = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * stepLen);
        float core;
        float d = density(p, core) * Strength;
        if (d <= 0.0) continue;
        vec3 c = mix(Colour, vec3(1.0), core * core * 0.5);
        emitted += through * c * d * Emission * stepLen;
        through *= exp(-d * Absorption * stepLen);
    }
    float alpha = 1.0 - through;
    if (alpha < 0.004 && max(max(emitted.r, emitted.g), emitted.b) < 0.004) discard;
    // Premultiplied output (wind_cone_volume.json blend): the haze covers a little, the glow adds light.
    fragColor = vec4(emitted, alpha);
}
