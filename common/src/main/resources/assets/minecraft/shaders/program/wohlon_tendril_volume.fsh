#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
// Quadratic Bézier control points, in box space.
uniform vec3 P0;
uniform vec3 P1;
uniform vec3 P2;
// Grown part of the curve, as a curve parameter in [0, 1].
uniform float Reach;
uniform float Radius;
uniform float Strength;
// Phase of the pulses that run from P0 toward P2, in radians.
uniform float Flow;
uniform vec3 ColourStart;
uniform vec3 ColourEnd;
// Writhe: blocks of bend at the middle of the tendril (0 is a calm tendril), its own phase seed,
// and the shared wave phase in radians. The ends stay where they are.
uniform float Wild;
uniform float Seed;
uniform float Writhe;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 28;
// Dark core, faint glowing rim: the tube absorbs, and only the halo outside it gives light.
const float HALO_SCALE = 2.2;
const float CORE_SHADE = 0.06;
const float RIM_EMISSION = 0.35;
const float TIP_EMISSION = 0.5;
const float VEIL_DENSITY = 3.0;
const float OPACITY = 0.85;

// WohlonRitualTendrils.writhe matches this, for the motes.
vec3 writhe(vec3 p) {
    return vec3(
        sin(p.y * 0.55 + p.z * 0.31 + Writhe + Seed) + 0.5 * sin(p.z * 1.3 - 2.0 * Writhe + Seed * 1.7),
        sin(p.x * 0.47 - p.z * 0.29 - Writhe + Seed * 2.3) + 0.5 * sin(p.x * 1.1 + 2.0 * Writhe + Seed * 0.6),
        sin(p.x * 0.39 + p.y * 0.53 + Writhe + Seed * 3.1) + 0.5 * sin(p.y * 1.2 - 2.0 * Writhe + Seed * 1.3)) / 1.5;
}

vec3 bezier(float t) {
    float s = 1.0 - t;
    return s * s * P0 + 2.0 * s * t * P1 + t * t * P2;
}

// Distance and curve parameter of the closest point on the Bézier (Inigo Quilez, sdBezier).
vec2 closest(vec3 pos) {
    vec3 a = P1 - P0;
    vec3 b = P0 - 2.0 * P1 + P2;
    vec3 c = a * 2.0;
    vec3 d = P0 - pos;
    float bb = dot(b, b);
    if (bb < 1.0e-6) {
        // Straight curve: closest point on the segment.
        vec3 ab = P2 - P0;
        float t = clamp(dot(pos - P0, ab) / max(dot(ab, ab), 1.0e-6), 0.0, 1.0);
        return vec2(length(pos - P0 - ab * t), t);
    }
    float kk = 1.0 / bb;
    float kx = kk * dot(a, b);
    float ky = kk * (2.0 * dot(a, a) + dot(d, b)) / 3.0;
    float kz = kk * dot(d, a);
    float p = ky - kx * kx;
    float q = kx * (2.0 * kx * kx - 3.0 * ky) + kz;
    float h = q * q + 4.0 * p * p * p;
    vec2 res;
    if (h >= 0.0) {
        h = sqrt(h);
        vec2 x = (vec2(h, -h) - q) / 2.0;
        vec2 uv = sign(x) * pow(abs(x), vec2(1.0 / 3.0));
        float t = clamp(uv.x + uv.y - kx, 0.0, 1.0);
        vec3 r = d + (c + b * t) * t;
        res = vec2(dot(r, r), t);
    } else {
        float z = sqrt(-p);
        float v = acos(q / (p * z * 2.0)) / 3.0;
        float m = cos(v);
        float n = sin(v) * 1.732050808;
        vec3 t = clamp(vec3(m + m, -n - m, n - m) * z - kx, 0.0, 1.0);
        vec3 rx = d + (c + b * t.x) * t.x;
        vec3 ry = d + (c + b * t.y) * t.y;
        float dx = dot(rx, rx);
        float dy = dot(ry, ry);
        res = dx < dy ? vec2(dx, t.x) : vec2(dy, t.y);
    }
    return vec2(sqrt(res.x), res.y);
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
    if (t1 <= t0 || Reach <= 0.0) discard;

    float dt = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 tip = bezier(Reach);
    vec3 emitted = vec3(0.0);
    float through = 1.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        if (Wild > 0.0) {
            // The unwarped closest point says where along the tendril this is; the bend is 0 at both ends.
            float along = clamp(closest(p).y, 0.0, 1.0);
            p += writhe(p) * Wild * sin(3.14159265 * along);
        }
        vec2 hit = closest(p);
        float d = hit.x;
        float s = hit.y;
        // Past the grown part, the nearest drawn point is the tip.
        if (s > Reach) {
            d = length(p - tip);
            s = Reach;
        }
        // Thick at the root of the tendril, thin toward its end.
        float r = Radius * (1.0 - 0.45 * s);
        float tube = exp(-d * d / (r * r));
        float halo = exp(-d * d / (r * r * HALO_SCALE * HALO_SCALE));
        float pulse = 0.55 + 0.45 * sin(s * 22.0 - Flow);
        float tipGlow = exp(-dot(p - tip, p - tip) / (r * r * 4.0)) * (Reach < 1.0 ? 1.0 : 0.3);
        vec3 colour = mix(ColourStart, ColourEnd, s);
        float rim = max(halo - tube, 0.0);
        float a = 1.0 - exp(-VEIL_DENSITY * tube * dt);
        vec3 light = colour * rim * RIM_EMISSION * pulse * dt + colour * tipGlow * TIP_EMISSION * dt;
        emitted += through * (light + a * colour * CORE_SHADE);
        through *= 1.0 - a;
    }
    float alpha = (1.0 - through) * OPACITY * Strength;
    vec3 outColour = emitted * Strength;
    if (alpha < 0.003 && max(max(outColour.r, outColour.g), outColour.b) < 0.003) discard;
    fragColor = vec4(outColour, alpha);
}
