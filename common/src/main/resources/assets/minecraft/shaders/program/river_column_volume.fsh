#version 150

uniform sampler2D DepthSampler;
// The scene copied just before this pass, so the column can refract it.
uniform sampler2D SceneSampler;

uniform mat4 ViewProj;
uniform mat4 LocalToView;
uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Column space: the casting palm at the origin, the stream along +X to the impact at (Range, 0, 0).
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
uniform float Range;
uniform float BaseRadius;
// Cast age in ticks, the clock of the waves.
uniform float Age;
// Seconds since the cast started, the clock of the flow.
uniform float Time;
uniform float Strength;
uniform vec3 Colour;
// 0..1 world light at the column, so the water darkens at night like other blocks.
uniform float Light;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int TRACE_STEPS = 96;
const int VOLUME_STEPS = 12;
const int FRONT_STEPS = 32;
// A thin pencil jet, as from a 0-degree pressure-washer nozzle.
const float PENCIL_RADIUS = 0.09;
// Small waves: the centre line snakes and the radius swells, both travelling with the flow.
const float WAVE_SNAKE = 0.03;
const float WAVE_SWELL = 0.1;
// Water cover over the refracted scene: thin where it faces the eye, thick at the edges.
const float COVER_FACING = 0.8;
const float COVER_EDGE = 0.97;
const float IOR = 1.33;
const float REFRACT_REACH = 0.3;
// Aerated surface: near-white streaks a third of a block long, sixteen across a block, racing along the jet.
const float TEXELS = 16.0;
const float STREAK_FLOW = 40.0;
const float STREAK_LENGTH = 0.35;
const float AERATION = 0.45;
// The mist round the jet: wider than the jet, so it reaches past the surface.
const float MIST_FILL = 3.0;
const float MIST_FLOW = 80.0;
// The density keeps the scale of a volume that just fills a BaseRadius column, so the mist is not thinned out.
const float DENSITY_FILL = 0.85;
const float EMISSION = 2.0;
const float ABSORPTION = 2.6;

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

// The waves grow in from the palm, so the jet leaves the hand straight.
vec2 centreOffset(float x) {
    float grow = smoothstep(0.0, 1.5, x);
    return WAVE_SNAKE * grow * vec2(sin(x * 2.6 - Age * 0.9), cos(x * 2.0 - Age * 0.8));
}

// Jet cross-section offset from its snaking centre line.
vec2 crossOf(vec3 p) {
    return p.yz - centreOffset(p.x);
}

float columnRadius(vec3 p) {
    return PENCIL_RADIUS * (1.0 + WAVE_SWELL * sin(p.x * 4.2 - Age * 1.1)) * (0.6 + 0.4 * smoothstep(0.0, 0.4, p.x));
}

float columnField(vec3 p) {
    return max(length(crossOf(p)) - columnRadius(p), max(-p.x, p.x - Range));
}

float field(vec3 p) {
    return columnField(p);
}

vec3 fieldNormal(vec3 p) {
    const vec2 e = vec2(0.01, 0.0);
    return normalize(vec3(
        field(p + e.xyy) - field(p - e.xyy),
        field(p + e.yxy) - field(p - e.yxy),
        field(p + e.yyx) - field(p - e.yyx)));
}

float jetDensity(vec3 p, out float core) {
    core = 0.0;
    float radius = columnRadius(p) * MIST_FILL;
    vec2 off = crossOf(p);
    float r = length(off) / radius;
    if (p.x <= 0.0 || p.x >= Range || r >= 1.0) return 0.0;
    core = 1.0 - r;
    // A gentle falloff, so the band outside the column surface still glows.
    float envelope = (1.0 - smoothstep(0.0, 1.0, r)) * smoothstep(0.0, 0.3, p.x);
    float a = 0.6 * p.x - 4.0 * Time;
    float c = cos(a);
    float s = sin(a);
    vec2 q = mat2(c, -s, s, c) * (off / radius);
    vec3 n = vec3((p.x - Time * MIST_FLOW) * 0.6, q * 4.0);
    float f = valueNoise(n) * 0.65 + valueNoise(n * 2.3 + 7.1) * 0.35;
    // A thinner column gives a shorter path, so the density scales up to keep the same total glow.
    return envelope * smoothstep(0.25, 0.9, f) * (0.5 / (BaseRadius * DENSITY_FILL));
}

vec2 screenOf(vec3 local) {
    vec4 clip = ViewProj * vec4((LocalToView * vec4(local, 1.0)).xyz, 1.0);
    return clip.xy / clip.w * 0.5 + 0.5;
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

    // Conservative steps: the waves make the field steeper than a true distance.
    float t = t0;
    float best = 1.0e9;
    float bestT = t0;
    bool hit = false;
    for (int i = 0; i < TRACE_STEPS; i++) {
        float d = field(CamLocal + dir * t);
        if (d < best) { best = d; bestT = t; }
        if (d < 0.002) { hit = true; break; }
        t += max(d * 0.6, 0.004);
        if (t > t1) break;
    }
    // A ray that skims the surface can run out of steps just short of it; count a near miss as a hit.
    if (!hit && best < 0.01) { hit = true; t = bestT; }

    // The volume between the eye and the surface, or the scene when the ray misses the column.
    float frontEnd = hit ? t : t1;
    float frontLen = (frontEnd - t0) / float(FRONT_STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 frontEmitted = vec3(0.0);
    float frontThrough = 1.0;
    for (int i = 0; i < FRONT_STEPS; i++) {
        vec3 v = CamLocal + dir * (t0 + (float(i) + jitter) * frontLen);
        float core;
        float d = jetDensity(v, core) * Strength;
        if (d <= 0.0) continue;
        vec3 c = mix(Colour, vec3(1.0), 0.45 + core * 0.4);
        frontEmitted += frontThrough * c * d * EMISSION * frontLen;
        frontThrough *= exp(-d * ABSORPTION * frontLen);
    }
    if (!hit) {
        float a = 1.0 - frontThrough;
        if (a < 0.004 && max(max(frontEmitted.r, frontEmitted.g), frontEmitted.b) < 0.004) discard;
        // Premultiplied output (river_column_volume.json blend).
        fragColor = vec4(frontEmitted, a);
        return;
    }

    vec3 p = CamLocal + dir * t;
    vec3 n = fieldNormal(p);
    float facing = max(dot(n, -dir), 0.0);

    vec3 bent = refract(dir, n, 1.0 / IOR);
    if (dot(bent, bent) < 1.0e-4) bent = dir;
    vec3 behind = texture(SceneSampler, clamp(screenOf(p + bent * REFRACT_REACH), 0.001, 0.999)).rgb;

    // The water volume, seen along the bent ray through the column.
    float stepLen = BaseRadius * 2.4 / float(VOLUME_STEPS);
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < VOLUME_STEPS; i++) {
        vec3 v = p + bent * (float(i) + 0.5) * stepLen;
        float core;
        float d = jetDensity(v, core);
        if (d <= 0.0) continue;
        vec3 c = mix(Colour, vec3(1.0), 0.45 + core * 0.4);
        emitted += through * c * d * EMISSION * stepLen;
        through *= exp(-d * ABSORPTION * stepLen);
    }

    vec3 streak = floor(vec3((p.x - Time * STREAK_FLOW) / STREAK_LENGTH, p.y * TEXELS, p.z * TEXELS));
    float cover = mix(COVER_EDGE, COVER_FACING, facing);
    vec3 water = mix(Colour, vec3(1.0), AERATION + 0.4 * hash(streak)) * Light;
    vec3 colour = mix(behind, water, cover) * through + emitted;

    float alpha = Strength * smoothstep(0.0, 0.25, p.x);
    // Premultiplied output (river_column_volume.json blend): the front volume over the surface.
    fragColor = vec4(frontEmitted + frontThrough * colour * alpha, 1.0 - frontThrough * (1.0 - alpha));
}
