#version 150

// The elements' cloaks: a wide, flat ring whirling round its wearer's hips, from their knees to
// their chest and out about a body's length each side, with a thin layer clinging to the body up
// to the shoulders. Drawn on a cylinder round them, each fragment marching its view ray through
// the ring inside, so it has depth from every side (and from inside, in first person), and
// stopping where the ray meets the scene (DepthSampler: a wall, the ground, a mob in the ring) or
// the wearer, who hides what's behind them. The vertex shader works
// out where on the cylinder this is, from the wearer's feet (world axes); UV0 carries how high
// the cylinder reaches and how wide the wearer is; the vertex colour's red carries which cloak
// this is (in SelfCastVisuals.CloakStyle's order), its green how dense (128 for as made, by the
// cast's tier above that), its alpha how strong. Procedural: no texture.
//
// - Flame (0): streaks of flame, white-hot by the body, orange through the streaks, dull red at
//   the ragged edge.
// - Frost (1): thin wisps through a faint haze, white deep in, pale blue at the edge.
// - Lightning (2): Chaos's pink lightning crackling round in threads, striking anew every
//   couple of ticks.
// - Blood (3): dark blood mist spiralling in, close round the body. Drawn over what's behind it
//   (rendertype_spell_cloak_dark).
// - Poison (4): sickly green wisps through a faint haze.
// - Void (5): dark swirls, violet at their edges. Drawn over what's behind it.

// Ticks on the spell visuals' own clock (SpellClock): it doesn't wrap every day as GameTime does.
uniform float SpellTicks;
uniform sampler2D DepthSampler;
uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform mat3 ViewToWorld;

in vec3 viewPos;
in vec3 outward;
in vec2 along;
in vec4 vertexColor;

out vec4 fragColor;

// How far out from the wearer the cylinder reaches (as SelfCastVisuals.CLOAK_REACH).
const float REACH = 0.95;
const int STEPS = 24;
// How much of the wearer's height the cylinder reaches (as SelfCastVisuals.CLOAK_HEIGHT): over
// their eyes, so in first person the eye is plainly inside it, never on its top.
const float TOP = 0.95;
// How high the ring's middle sits, and how far it reaches up and down, as parts of the
// wearer's height (hip height; knees to chest).
const float RING_Y = 0.432;
const float RING_H = 0.27;

// A hash without sin, so the cloaks come out the same on every GPU.
float h(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) { return 0.6 * n3(p) + 0.3 * n3(p * 2.03 + 7.1) + 0.1 * n3(p * 4.1 + 3.3); }

// Flame's colour by how hot it is: dull red, orange, yellow, white.
vec3 fire(float t) {
    vec3 c = mix(vec3(0.6, 0.1, 0.0), vec3(1.0, 0.38, 0.02), smoothstep(0.0, 0.35, t));
    c = mix(c, vec3(1.0, 0.72, 0.2), smoothstep(0.4, 0.7, t));
    return mix(c, vec3(1.0, 0.96, 0.8), smoothstep(0.75, 1.0, t));
}

// How far out past the wearer's sides each cloak's ring reaches: Blood's stays close.
float wrapOf(int style) {
    if (style == 3) return 0.45;
    if (style == 1 || style == 4) return 0.75;
    return 0.8;
}

// How fast each cloak whirls, in radians a tick (about a turn in 2 s).
float spinOf(int style) {
    if (style == 0) return 0.16;
    if (style == 1) return 0.18;
    if (style == 2) return 0.22;
    if (style == 3) return 0.14;
    if (style == 4) return 0.12;
    return 0.1;
}

// How much light (or shade) a block of each cloak gives.
float glowOf(int style) {
    if (style == 0) return 3.0;
    if (style == 1) return 4.5;
    if (style == 2) return 14.0;
    if (style == 4) return 2.5;
    return 5.0;
}

// How much cloak there is at [p] (from the wearer's feet), and its colour there.
float cloak(int style, vec3 p, float height, float body, float ticks, out vec3 col) {
    // Where [p] is in the ring: 0 at its middle, 1 at its edge; up it, -1 below to 1 above.
    float wrap = wrapOf(style);
    float tall = height / TOP;
    vec3 e = (p - vec3(0.0, tall * RING_Y, 0.0)) / vec3(body + wrap, tall * RING_H, body + wrap);
    float rho = length(e);
    float r = length(p.xz);
    // Round the wearer, whirled on.
    float around = atan(p.z, p.x) - ticks * spinOf(style);
    vec2 circle = vec2(cos(around), sin(around));
    // The thin layer clinging to the body up to the shoulders, and the clearing where the body is.
    float y = p.y / tall;
    float cling = (1.0 - smoothstep(body, body + 0.25, r)) * smoothstep(0.0, 0.09, y) * (1.0 - smoothstep(0.585, 0.79, y));
    float clear = smoothstep(body * 0.8, body + 0.3, r);

    if (style == 2) {
        // Threads where two noises both cross their middles; they jump every couple of ticks.
        float strike = floor(ticks * 0.5);
        float tilt = e.y * 3.0 + 0.6 * sin(2.0 * around + strike);
        float a = fbm(vec3(circle * 1.2, tilt) + vec3(strike * 3.7, r * 3.0, 0.0));
        float b = fbm(vec3(circle * 1.2, tilt * 1.3) + vec3(r * 2.5, strike * 5.1, 9.0));
        float bolt = (1.0 - abs(2.0 * a - 1.0)) * (1.0 - abs(2.0 * b - 1.0));
        float zone = 1.0 - smoothstep(0.55, 1.0, rho);
        col = mix(vec3(0.92, 0.42, 0.6), vec3(1.0, 0.92, 0.97), smoothstep(0.86, 0.95, bolt));
        return smoothstep(0.74, 0.88, bolt) * (zone * mix(0.3, 1.0, clear) + 0.3 * cling);
    }

    // The others are streaks or wisps running round the ring, nearly flat, tilting a little.
    float tilt;
    float n;
    if (style == 0) {
        tilt = e.y * 4.0 + 0.7 * sin(around + 1.7) + 0.35 * sin(2.0 * around - 0.4);
        n = fbm(vec3(circle * 0.9, tilt) + vec3(r * 2.5, 0.0, -ticks * 0.03));
    } else if (style == 3) {
        // Spiralling in toward the body as it turns.
        tilt = e.y * 4.0 + 0.6 * sin(around + 2.9) + 0.3 * sin(2.0 * around + 1.2);
        n = fbm(vec3(circle * 0.9, tilt) + vec3(r * 3.0 + ticks * 0.06, 0.0, 0.0));
    } else {
        float phase = style == 1 ? 0.6 : (style == 4 ? 2.2 : 4.1);
        tilt = e.y * 5.0 + 0.8 * sin(around + phase) + 0.3 * sin(2.0 * around + 2.1 + phase);
        n = fbm(vec3(circle * 0.8, tilt) + vec3(r * 2.0, 0.0, -ticks * 0.02));
    }
    // The ring, ragged at its edge.
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
    // Void: near black, violet only at the edges of its swirls.
    float edge = smoothstep(0.52, 0.56, n) * (1.0 - smoothstep(0.58, 0.66, n));
    col = mix(vec3(0.03, 0.01, 0.05), vec3(0.45, 0.24, 0.62), edge);
    return (smoothstep(0.48, 0.58, n) + 0.12 * haze) * shape;
}

// How far along the view ray [d] (world axes, unit) the scene is, from the camera.
float sceneDistance(vec3 d) {
    // Back into view space: the inverse of a turn is its transpose.
    vec3 v = d * ViewToWorld;
    float ndc = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0;
    return ProjMat[3][2] / (-ndc - ProjMat[2][2]) / v.z;
}

// Where a ray from [p] along [d] leaves a cylinder [radius] round the feet, [height] high.
float exitAt(vec3 p, vec3 d, float radius, float height) {
    float a = dot(d.xz, d.xz), b = 2.0 * dot(p.xz, d.xz), c = dot(p.xz, p.xz) - radius * radius;
    float t = a > 1e-6 ? (-b + sqrt(max(0.0, b * b - 4.0 * a * c))) / (2.0 * a) : 1e3;
    if (d.y > 1e-4) t = min(t, (height - p.y) / d.y);
    if (d.y < -1e-4) t = min(t, -p.y / d.y);
    return max(t, 0.0);
}

// Where a ray from [p] along [d] first meets the wearer's body, a column [radius] round the feet,
// [height] high; far off if it misses (or starts inside it, as the eye does in first person).
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

void main() {
    float ticks = SpellTicks;
    int style = int(vertexColor.r * 255.0 + 0.5);
    float density = vertexColor.g * 255.0 / 128.0;
    float height = along.x, body = along.y, radius = body + REACH;
    vec3 p = outward;
    vec3 d = normalize(viewPos);
    vec3 eye = p - viewPos;
    bool inside = length(eye.xz) < radius && eye.y > 0.0 && eye.y < height;
    // The cylinder's own normal here, to tell the faces the ray enters by from those it leaves by.
    vec3 nrm = p.y > height - 1e-3 ? vec3(0.0, 1.0, 0.0) : (p.y < 1e-3 ? vec3(0.0, -1.0, 0.0) : vec3(p.x, 0.0, p.z));
    bool entering = dot(d, nrm) < 0.0;
    // Outside, march in from where the ray enters; inside, from the eye out to where it leaves.
    if (inside == entering) discard;
    vec3 start = inside ? eye : p;
    float len = inside ? length(p - eye) : exitAt(p, d, radius, height);
    // The scene hides whatever's behind it: a wall the ring runs into, the ground, a mob in it.
    float scene = sceneDistance(d) - (inside ? 0.0 : length(viewPos));
    if (scene <= 0.0) discard;
    len = min(len, scene);
    // The wearer hides whatever's behind them.
    len = min(len, bodyAt(start, d, body, height / TOP));
    float stepLen = len / float(STEPS);
    float t = stepLen * h(vec3(gl_FragCoord.xy, ticks));
    float glow = glowOf(style) * density;
    vec3 light = vec3(0.0);
    float amount = 0.0;
    for (int i = 0; i < STEPS; i++) {
        vec3 col;
        vec3 q = start + d * t;
        // Nothing in the middle of the body: the noise, laid round the wearer, bunches into spokes
        // there. The body hides it in third person; in first person it's under your eye.
        float core = smoothstep(body * 0.4, body * 0.9, length(q.xz));
        float k = cloak(style, q, height, body, ticks, col) * core * stepLen * glow;
        light += col * k;
        amount += k;
        t += stepLen;
    }
    float a = (1.0 - exp(-amount)) * vertexColor.a;
    if (a < 0.003) discard;
    fragColor = vec4(light / max(amount, 1e-4), min(a, 1.0));
}
