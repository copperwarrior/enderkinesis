#version 150

// Local space: origin at the flame centre, world axes. The volume fills the BoxMax cube; Radius sizes the flame.

// Use SpellClock ticks: GameTime wraps each day, and the noise would jump there.
uniform float SpellTicks;
uniform sampler2D DepthSampler;
uniform mat4 ViewProj;
uniform vec2 DepthParams;
uniform vec2 ScreenSize;
uniform vec3 CamLocal;
uniform float Radius;
uniform float Seed;
uniform vec3 BoxMax;
// 0..1 spawn/despawn fade, and 0..1 progress of the death burst.
uniform float Fade;
uniform float Burst;
// 1 in a silverpoint sketch, which reads alpha as coverage; the world blend needs alpha 1.
uniform float Coverage;
// xyz is the view direction in local axes; w is 1 for an orthographic sketch, where all rays are parallel.
uniform vec4 OrthoRay;
uniform mat4 LocalToView;

// Ease-out, so the burst throws out fast and then slows. WillfaerFlameVolume grows the box by the same curve.
float spreadOf(float b) { return 1.0 - (1.0 - b) * (1.0 - b); }

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 24;
const int MOTES = 44;
// Fractions of Radius. The flame stands from FLAME_BASE to FLAME_TOP and is at most FLAME_WIDTH from the axis.
const float FLAME_BASE = -0.55;
const float FLAME_TOP = 0.75;
const float FLAME_WIDTH = 0.45;
const float MOTE_SIZE = 0.026;
const vec3 WHITE = vec3(1.0, 0.99, 0.88);
const vec3 YELLOW = vec3(0.97, 0.95, 0.42);
const vec3 GREEN = vec3(0.62, 0.9, 0.3);
const vec3 MOTE = vec3(0.88, 1.0, 0.55);

// Hash without sin, so it comes out the same on every GPU.
float h(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) { return 0.6 * n3(p) + 0.3 * n3(p * 2.03 + 7.1) + 0.1 * n3(p * 4.1 + 3.3); }

// Teardrop width over the normalized height, 1 at the widest point (about 0.3).
float profile(float t) { return 2.8 * sqrt(t) * pow(1.0 - t, 1.2); }

// Per flame and frame: x = height scale, y = brightness, zw = tip sway.
vec4 flicker(float ticks) {
    float a = n3(vec3(ticks * 0.35, Seed, 1.0));
    float b = n3(vec3(ticks * 0.8, Seed, 5.0));
    vec2 sway = vec2(n3(vec3(ticks * 0.12, Seed, 9.0)), n3(vec3(ticks * 0.12, Seed, 13.0))) - 0.5;
    return vec4(0.8 + 0.3 * a, 0.8 + 0.35 * b, sway * 0.6);
}

// Death poof: a ball of thin fire that throws out from the flame centre and thins away. [c] is from that centre.
float puff(vec3 c, float ticks, out vec3 col) {
    float spread = spreadOf(Burst);
    float size = 0.3 + 1.1 * spread;
    float n = fbm(c * 3.0 / size + vec3(0.0, -ticks * 0.3, Seed));
    float d = length(c) / size * (0.6 + 0.8 * n);
    float shell = smoothstep(1.0, 0.55, d) * smoothstep(0.0, 0.5 + 0.3 * spread, d);
    float thin = (1.0 - Burst) * (1.0 - Burst) * 0.3 / size;
    col = mix(mix(WHITE, YELLOW, smoothstep(0.0, 0.3, Burst)), GREEN, smoothstep(0.25, 0.8, Burst) * d);
    return 6.0 * shell * thin;
}

float flameOf(vec3 p, vec3 c, float ticks, vec4 fl, out vec3 col) {
    float t = (p.y - FLAME_BASE) / ((FLAME_TOP - FLAME_BASE) * fl.x);
    float glow = 0.35 * exp(-dot(c, c) / 0.12);
    col = mix(GREEN, YELLOW, 0.4);
    if (t <= 0.0 || t >= 1.0) return glow * fl.y;
    vec2 xz = p.xz / FLAME_WIDTH - fl.zw * t * t;
    // Rising sideways warp bends the flame into tongues that lean and twist.
    float scroll = t * 3.0 - ticks * 0.34;
    vec2 warp = vec2(n3(vec3(scroll, Seed, 2.0)), n3(vec3(scroll, Seed, 6.0))) - 0.5;
    xz -= warp * (0.25 + 0.9 * t);
    // Fast upward scroll, so the tongues lick up and break off.
    float n = fbm(vec3(xz * 2.2, t * 4.0 - ticks * 0.36) + Seed);
    float edge = profile(t) * (0.45 + 1.1 * n);
    float r = length(xz);
    float body = 1.0 - smoothstep(edge * 0.4, edge, r);
    float tongues = smoothstep(0.4, 0.62, n + 0.6 * (1.0 - t));
    float heat = clamp(1.0 - r / max(edge, 1e-3), 0.0, 1.0) * (1.15 - t);
    col = mix(GREEN, YELLOW, smoothstep(0.1, 0.5, heat));
    col = mix(col, WHITE, smoothstep(0.3, 0.75, heat));
    return (4.0 * body * tongues * (0.6 + heat) + glow) * fl.y;
}

// [p] is in units of Radius.
float fire(vec3 p, float ticks, vec4 fl, out vec3 col) {
    vec3 c = p - vec3(0.0, mix(FLAME_BASE, FLAME_TOP, 0.35), 0.0);
    vec3 puffCol = vec3(0.0);
    float puffed = Burst > 0.0 ? puff(c, ticks, puffCol) : 0.0;
    // The flame is gone a few ticks into the burst; only the poof stays.
    float die = 1.0 - smoothstep(0.0, 0.25, Burst);
    float flame = flameOf(p, c, ticks, fl, col) * die;
    float sum = flame + puffed;
    col = sum > 1e-4 ? (col * flame + puffCol * puffed) / sum : col;
    return sum;
}

// Motes drift in a loose cloud around the flame and twinkle. Light only, so they add after the march.
vec3 motes(vec3 o, vec3 d, float far, float ticks) {
    vec3 light = vec3(0.0);
    for (int i = 0; i < MOTES; i++) {
        float fi = float(i) + Seed * 13.0;
        vec3 rnd = vec3(h(vec3(fi, 1.0, 7.0)), h(vec3(fi, 2.0, 3.0)), h(vec3(fi, 5.0, 11.0)));
        float speed = 0.01 + 0.02 * rnd.x;
        float ang = rnd.y * 6.2832 + ticks * speed * (rnd.z > 0.5 ? 1.0 : -1.0);
        float rise = fract(rnd.z + ticks * 0.004 * (0.5 + rnd.x));
        // The burst throws the motes out to about 2.7 Radius; the caller grows the box to hold them.
        float spread = 1.0 + 2.2 * spreadOf(Burst) * (0.6 + 0.4 * rnd.y);
        float dist = (0.3 + 0.6 * rnd.x) * Radius * spread;
        vec3 c = vec3(cos(ang) * dist, (rise * 1.6 - 0.8) * Radius * spread, sin(ang) * dist);
        float t = clamp(dot(c - o, d), 0.0, far);
        vec3 off = o + d * t - c;
        float size = MOTE_SIZE * Radius * (1.0 + Burst);
        float twinkle = 0.5 + 0.5 * sin(ticks * (0.3 + 0.4 * rnd.y) + fi);
        float fade = sin(rise * 3.1416) * (1.0 - Burst * Burst) * (1.0 + 4.0 * sqrt(Burst));
        light += MOTE * exp(-dot(off, off) / (size * size)) * twinkle * fade;
    }
    return light;
}

float sceneNdc() { return texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0; }

// Distance to the scene along the unit view ray [d], world axes. Clip w is view depth.
float sceneDistance(vec3 d) {
    return DepthParams.y / (sceneNdc() + DepthParams.x) / (ViewProj * vec4(d, 0.0)).w;
}

// Orthographic: NDC depth is linear along the ray, so solve for the distance from [o] where it meets the scene.
float sceneDistanceOrtho(vec3 o, vec3 d) {
    vec4 a = ViewProj * LocalToView * vec4(o, 1.0);
    vec4 b = ViewProj * LocalToView * vec4(o + d, 1.0);
    return (sceneNdc() - a.z / a.w) / (b.z / b.w - a.z / a.w);
}

void main() {
    float ticks = SpellTicks;
    bool ortho = OrthoRay.w > 0.5;
    vec3 d = ortho ? OrthoRay.xyz : normalize(localPos - CamLocal);
    // An orthographic ray starts just outside the box, on the side toward the viewer.
    vec3 o = ortho ? localPos - d * (2.0 * length(BoxMax)) : CamLocal;
    float scene = ortho ? sceneDistanceOrtho(o, d) : sceneDistance(d);
    vec3 inv = 1.0 / d;
    vec3 ta = (-BoxMax - o) * inv, tb = (BoxMax - o) * inv;
    vec3 lo = min(ta, tb), hi = max(ta, tb);
    float enter = max(max(max(lo.x, lo.y), lo.z), 0.0);
    float leave = min(min(min(hi.x, hi.y), hi.z), scene);
    if (leave <= enter) discard;

    vec3 light = vec3(0.0);
    float amount = 0.0;
    vec4 fl = flicker(ticks);
    float stepLen = (leave - enter) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float k0 = stepLen / Radius;
    for (int i = 0; i < STEPS; i++) {
        vec3 col;
        float k = fire((o + d * (enter + (float(i) + jitter) * stepLen)) / Radius, ticks, fl, col) * k0;
        light += col * k;
        amount += k;
    }
    vec3 sparks = motes(o, d, scene, ticks);
    float a = 1.0 - exp(-amount);
    vec3 col = amount > 1e-4 ? light / amount * a : vec3(0.0);
    col = (col + sparks) * Fade;
    float total = max(max(col.r, col.g), col.b);
    if (total < 0.004) discard;
    // Additive with alpha 1: the colour already carries the coverage.
    fragColor = vec4(col, Coverage > 0.5 ? min(total, 1.0) : 1.0);
}
