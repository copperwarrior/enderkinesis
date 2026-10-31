#version 150

// Local space: origin at the wearer feet, world axes. Style follows SelfCastVisuals.CloakStyle order.
// Density is 1 as made and goes up with the cast tier.

// Use SpellClock ticks: GameTime wraps each day, and the noise would jump there.
uniform float SpellTicks;
uniform sampler2D DepthSampler;
uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
uniform vec3 CamLocal;
uniform int Style;
uniform float Density;
uniform float Strength;
// Height: top of the cylinder. Body: half the wearer width.
uniform float Height;
uniform float Body;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;
// Must agree with SelfCastVisuals.CLOAK_REACH.
const float REACH = 0.95;
const int STEPS = 24;
// Must agree with SelfCastVisuals.CLOAK_HEIGHT. Above the eyes, so in first person the eye is always inside.
const float TOP = 0.95;
// Ring middle and half height, as fractions of the wearer height (hip; knees to chest).
const float RING_Y = 0.432;
const float RING_H = 0.27;

// Hash without sin, so it comes out the same on every GPU.
float h(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) { return 0.6 * n3(p) + 0.3 * n3(p * 2.03 + 7.1) + 0.1 * n3(p * 4.1 + 3.3); }

vec3 fire(float t) {
    vec3 c = mix(vec3(0.6, 0.1, 0.0), vec3(1.0, 0.38, 0.02), smoothstep(0.0, 0.35, t));
    c = mix(c, vec3(1.0, 0.72, 0.2), smoothstep(0.4, 0.7, t));
    return mix(c, vec3(1.0, 0.96, 0.8), smoothstep(0.75, 1.0, t));
}

float wrapOf(int style) {
    if (style == 3) return 0.45;
    if (style == 1 || style == 4) return 0.75;
    return 0.8;
}

// Radians per tick (about one turn in 2 s).
float spinOf(int style) {
    if (style == 0) return 0.16;
    if (style == 1) return 0.18;
    if (style == 2) return 0.22;
    if (style == 3) return 0.14;
    if (style == 4) return 0.12;
    return 0.1;
}

float glowOf(int style) {
    if (style == 0) return 3.0;
    if (style == 1) return 4.5;
    if (style == 2) return 14.0;
    if (style == 4) return 2.5;
    return 5.0;
}

float cloak(int style, vec3 p, float height, float body, float ticks, out vec3 col) {
    // Ring coordinates: 0 at the middle, 1 at the edge; vertical -1 below to 1 above.
    float wrap = wrapOf(style);
    float tall = height / TOP;
    vec3 e = (p - vec3(0.0, tall * RING_Y, 0.0)) / vec3(body + wrap, tall * RING_H, body + wrap);
    float rho = length(e);
    float r = length(p.xz);
    float around = atan(p.z, p.x) - ticks * spinOf(style);
    vec2 circle = vec2(cos(around), sin(around));
    float y = p.y / tall;
    float cling = (1.0 - smoothstep(body, body + 0.25, r)) * smoothstep(0.0, 0.09, y) * (1.0 - smoothstep(0.585, 0.79, y));
    float clear = smoothstep(body * 0.8, body + 0.3, r);

    if (style == 2) {
        float strike = floor(ticks * 0.5);
        float tilt = e.y * 3.0 + 0.6 * sin(2.0 * around + strike);
        float a = fbm(vec3(circle * 1.2, tilt) + vec3(strike * 3.7, r * 3.0, 0.0));
        float b = fbm(vec3(circle * 1.2, tilt * 1.3) + vec3(r * 2.5, strike * 5.1, 9.0));
        float bolt = (1.0 - abs(2.0 * a - 1.0)) * (1.0 - abs(2.0 * b - 1.0));
        float zone = 1.0 - smoothstep(0.55, 1.0, rho);
        col = mix(vec3(0.92, 0.42, 0.6), vec3(1.0, 0.92, 0.97), smoothstep(0.86, 0.95, bolt));
        return smoothstep(0.74, 0.88, bolt) * (zone * mix(0.3, 1.0, clear) + 0.3 * cling);
    }

    float tilt;
    float n;
    if (style == 0) {
        tilt = e.y * 4.0 + 0.7 * sin(around + 1.7) + 0.35 * sin(2.0 * around - 0.4);
        n = fbm(vec3(circle * 0.9, tilt) + vec3(r * 2.5, 0.0, -ticks * 0.03));
    } else if (style == 3) {
        tilt = e.y * 4.0 + 0.6 * sin(around + 2.9) + 0.3 * sin(2.0 * around + 1.2);
        n = fbm(vec3(circle * 0.9, tilt) + vec3(r * 3.0 + ticks * 0.06, 0.0, 0.0));
    } else {
        float phase = style == 1 ? 0.6 : (style == 4 ? 2.2 : 4.1);
        tilt = e.y * 5.0 + 0.8 * sin(around + phase) + 0.3 * sin(2.0 * around + 2.1 + phase);
        n = fbm(vec3(circle * 0.8, tilt) + vec3(r * 2.0, 0.0, -ticks * 0.02));
    }
    float ring = 1.0 - smoothstep(0.5 + 0.4 * n, 1.0, rho);
    float shape = ring * mix(0.3, 1.0, clear) + 0.35 * cling;

    if (style == 0) {
        col = fire(clamp(0.15 + 1.5 * (n - 0.5) + 0.6 * (1.0 - rho) + 0.2 * cling, 0.0, 1.0));
        return smoothstep(0.5, 0.56, n) * shape;
    }
    if (style == 3) {
        col = mix(vec3(0.22, 0.01, 0.03), vec3(0.72, 0.08, 0.12), smoothstep(0.55, 0.7, n));
        return (smoothstep(0.48, 0.6, n) + 0.1 * smoothstep(0.35, 0.6, n)) * (ring * mix(0.4, 1.0, clear) + 0.5 * cling);
    }
    float wisp = smoothstep(0.52, 0.62, n);
    float haze = smoothstep(0.35, 0.6, n);
    float deep = clamp(1.1 - rho + 0.4 * wisp, 0.0, 1.0);
    if (style == 1) {
        col = mix(vec3(0.45, 0.72, 1.0), vec3(0.9, 0.97, 1.0), deep);
        return (wisp + 0.08 * haze) * shape;
    }
    if (style == 4) {
        col = mix(vec3(0.3, 0.5, 0.08), vec3(0.62, 0.86, 0.3), deep);
        return (smoothstep(0.55, 0.64, n) + 0.05 * haze) * shape;
    }
    float edge = smoothstep(0.52, 0.56, n) * (1.0 - smoothstep(0.58, 0.66, n));
    col = mix(vec3(0.03, 0.01, 0.05), vec3(0.45, 0.24, 0.62), edge);
    return (smoothstep(0.48, 0.58, n) + 0.12 * haze) * shape;
}

// Far off on a miss, or when [p] starts inside, as the eye does in first person.
float bodyAt(vec3 p, vec3 d, float radius, float height) {
    float t = 1e3;
    float a = dot(d.xz, d.xz), b = 2.0 * dot(p.xz, d.xz), c = dot(p.xz, p.xz) - radius * radius;
    float disc = b * b - 4.0 * a * c;
    if (a > 1e-6 && disc > 0.0) {
        float side = (-b - sqrt(disc)) / (2.0 * a);
        float y = p.y + d.y * side;
        if (side > 0.0 && y > 0.0 && y < height) t = side;
    }
    if (d.y < -1e-4) {
        float top = (height - p.y) / d.y;
        vec3 q = p + d * top;
        if (top > 0.0 && dot(q.xz, q.xz) < radius * radius) t = min(t, top);
    }
    return t;
}

vec2 cylinderSpan(vec3 o, vec3 d, float radius, float height) {
    vec2 span = vec2(0.0, 1e6);
    float a = dot(d.xz, d.xz), b = 2.0 * dot(o.xz, d.xz), c = dot(o.xz, o.xz) - radius * radius;
    if (a > 1e-6) {
        float disc = b * b - 4.0 * a * c;
        if (disc <= 0.0) return vec2(1.0, 0.0);
        float root = sqrt(disc);
        span = vec2((-b - root) / (2.0 * a), (-b + root) / (2.0 * a));
    } else if (c > 0.0) {
        return vec2(1.0, 0.0);
    }
    if (abs(d.y) > 1e-4) {
        float y0 = -o.y / d.y, y1 = (height - o.y) / d.y;
        span = vec2(max(span.x, min(y0, y1)), min(span.y, max(y0, y1)));
    } else if (o.y < 0.0 || o.y > height) {
        return vec2(1.0, 0.0);
    }
    return span;
}

void main() {
    float ticks = SpellTicks;
    int style = Style;
    float height = Height, body = Body, radius = body + REACH;
    vec2 texCoord = gl_FragCoord.xy / ScreenSize;
    vec3 toFar = localPos - CamLocal;
    vec3 d = toFar / length(toFar);
    vec2 span = cylinderSpan(CamLocal, d, radius, height);
    float enter = max(span.x, 0.0);
    float leave = span.y;
    float depth = texture(DepthSampler, texCoord).r;
    if (depth < 1.0) {
        vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
        leave = min(leave, length(scene.xyz / scene.w));
    }
    if (leave <= enter) discard;
    vec3 start = CamLocal + d * enter;
    float len = min(leave - enter, bodyAt(start, d, body, height / TOP));
    float stepLen = len / float(STEPS);
    float t = stepLen * h(vec3(gl_FragCoord.xy, ticks));
    float glow = glowOf(style) * Density;
    vec3 light = vec3(0.0);
    float amount = 0.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 col;
        vec3 q = start + d * t;
        // Clear the body middle: the noise around the wearer bunches into spokes there.
        float core = smoothstep(body * 0.4, body * 0.9, length(q.xz));
        float k = cloak(style, q, height, body, ticks, col) * core * stepLen * glow;
        light += col * k;
        amount += k;
        t += stepLen;
    }
    float a = (1.0 - exp(-amount)) * Strength;
    if (a < 0.003) discard;
    fragColor = vec4(light / max(amount, 1e-4), min(a, 1.0));
}
