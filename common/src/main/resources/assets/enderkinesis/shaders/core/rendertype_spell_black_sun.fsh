#version 150

// Black Hole Sun: a black sphere in a churning disk of fire, marched through per pixel as the
// cloaks' rings are, so it has depth from every side and from inside.
//
// - The disk lies round the core tilted [TILT] from level (which way it tips comes from the
//   vertex colour's red), thickest at the core and tapering to a fine edge, and turns round the
//   core, its inner edge faster: deep red where it meets the black, fire through its middle,
//   gold at its edge.
// - A glow hugs the core as the Sunfire cloak's halo does: a shell swept by soft veils of light,
//   each swinging round its own tilted circle like a comet, brightest at the rim, with a soft
//   light round the black.
// - The world behind it is pulled a little toward the core ([BEND]): what's behind is read from
//   a copy of the screen (SceneSampler) along a ray bent toward the middle, so the horizon and
//   the ground warp round it. The disk's own light isn't bent.
//
// The core hides whatever is behind it; the march stops where the ray meets the scene
// (DepthSampler), so the fire sinks into the ground it touches.
//
// UV0 (radii): the core's radius, the disk's outer radius. The vertex colour's red is which way
// the disk tips (a turn in 256ths), its alpha how much of it shows (it fades in, and caves in at
// the end). Procedural: no texture.

uniform float SpellTicks;
uniform sampler2D DepthSampler;
uniform sampler2D SceneSampler;
uniform mat4 ProjMat;
uniform vec2 ScreenSize;
uniform mat3 ViewToWorld;

in vec3 viewPos;
in vec3 outward;
in vec2 radii;
in vec4 vertexColor;

out vec4 fragColor;

// Steps through the disk's slab: only the stretch where a ray crosses the disk is marched.
const int STEPS = 32;
// How much light a core's radius of the fire gives, and how brightly it's shown.
const float GLOW = 13.0;
const float EXPOSURE = 2.0;
// How fast the disk's outer edge turns, in radians a tick; its inner edge turns faster.
const float SPIN = 0.015;
// The disk's shape: its thickness, how much it churns, how sharply it tapers from the core to
// its edge (-1: thickest at the core, gone at the edge), and its tilt from level.
const float THICK = 0.45;
const float CHURN = 0.35;
const float TAPER = -1.0;
const float TILT = 0.2618;
// How hard the world behind is pulled toward the core.
const float BEND = 0.2;
// How far the disk can reach from its middle plane, as parts of the core's radius: the slab
// marched through.
const float SLAB = 0.75;
// The halo round the core: its radius, as parts of the core's, and its colour.
const float HALO = 1.3;
const vec3 HALO_COLOUR = vec3(1.0, 0.82, 0.45);
const int VEILS = 7;

// A hash without sin, so it comes out the same on every GPU (as the cloaks').
float h(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) { return 0.6 * n3(p) + 0.3 * n3(p * 2.03 + 7.1) + 0.1 * n3(p * 4.1 + 3.3); }

// How far along the view ray [d] (world axes, unit) the scene is, from the camera.
float sceneDistance(vec3 d) {
    vec3 v = d * ViewToWorld;
    float ndc = texture(DepthSampler, gl_FragCoord.xy / ScreenSize).r * 2.0 - 1.0;
    return ProjMat[3][2] / (-ndc - ProjMat[2][2]) / v.z;
}

// Where a ray from [s] along [d] meets a sphere [r] round the middle: near and far, or (-1, -1).
vec2 sphereHits(vec3 s, vec3 d, float r) {
    float b = dot(s, d);
    float c = dot(s, s) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return vec2(-1.0);
    float root = sqrt(disc);
    return vec2(-b - root, -b + root);
}

// [v] turned [angle] about the unit [axis].
vec3 turn(vec3 v, vec3 axis, float angle) {
    float c = cos(angle), s = sin(angle);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// How much fire there is at [q] (from the middle, in the disk's own frame: level), and its colour.
float fire(vec3 q, float core, float outer, float ticks, out vec3 col) {
    float rho = length(q.xz);
    // Across the disk: 0 where it meets the core, 1 at its outer edge.
    float u = clamp((rho - core) / (outer - core), 0.0, 1.0);
    // Round the core, turned on, the inner edge faster.
    float around = atan(q.z, q.x) - ticks * SPIN * (2.2 - 1.2 * u);
    vec2 circle = vec2(cos(around), sin(around));
    float y = q.y / core;
    float n = mix(0.55, fbm(vec3(circle * 1.7, u * 3.2 - ticks * 0.01) + vec3(0.0, 0.0, y * 0.8)), CHURN);
    float m = mix(0.55, fbm(vec3(circle * 4.3, u * 7.0) + vec3(ticks * 0.02, y * 2.0, 5.3)), CHURN);
    // Thickest at the core, tapering to a fine edge.
    float taper = max(0.08, 1.0 + TAPER * 0.9 * (2.0 * u - 1.0));
    float thick = core * THICK * taper * (0.16 + 0.34 * sin(3.14159 * u) + 0.28 * n);
    float level = exp(-(q.y * q.y) / max(thick * thick, 1e-4));
    float band = smoothstep(0.0, 0.06 + 0.12 * n, u) * (1.0 - smoothstep(0.5 + 0.4 * n, 1.0, u));
    float clumps = 0.2 + 1.7 * smoothstep(0.4, 0.75, 0.6 * n + 0.4 * m);
    // Deep red at the core, fire through the middle, gold at the edge.
    col = mix(vec3(0.42, 0.04, 0.02), vec3(0.98, 0.36, 0.06), smoothstep(0.04, 0.35, u + 0.25 * (m - 0.5)));
    col = mix(col, vec3(1.0, 0.78, 0.32), smoothstep(0.42, 0.82, u + 0.3 * (n - 0.5)));
    return band * level * clumps;
}

// Veil i's circle: its axis, tilted its own way and turning slowly about the vertical (as the
// Sunfire cloak's halo).
vec3 axisOf(float i, float ticks) {
    float spin = (0.01 + 0.012 * h(vec3(i, 2.0, 0.0))) * (mod(i, 2.0) < 1.0 ? 1.0 : -1.0);
    float th = h(vec3(i, 1.0, 0.0)) * 6.2832 + ticks * spin;
    float ph = acos(2.0 * h(vec3(i, 3.0, 0.0)) - 1.0);
    return vec3(sin(ph) * cos(th), cos(ph), sin(ph) * sin(th));
}

// Soft veils of light sweeping round the halo, each round its own tilted circle like a comet:
// brightest at its head, fading along its tail, streaked along its length (as the Sunfire
// cloak's halo).
float veils(vec3 n, float ticks) {
    float s = 0.0;
    for (int k = 0; k < VEILS; k++) {
        float i = float(k);
        vec3 axis = axisOf(i, ticks);
        vec3 u = normalize(cross(axis, vec3(0.31, 0.95, 0.17)));
        vec3 v = cross(axis, u);
        float ang = atan(dot(n, v), dot(n, u));
        float w = 0.07 + 0.07 * h(vec3(i, 5.0, 0.0));
        float d = (dot(n, axis) - (h(vec3(i, 4.0, 0.0)) - 0.5) * 1.1) / w;
        float across = exp(-d * d);
        float dir = mod(i, 3.0) < 1.0 ? -1.0 : 1.0;
        float head = ticks * (0.1 + 0.06 * h(vec3(i, 7.0, 0.0))) + 6.2832 * h(vec3(i, 8.0, 0.0));
        float behind = mod(dir * (head - ang), 6.2832);
        float tail = 1.6 + 1.4 * h(vec3(i, 9.0, 0.0));
        float trail = behind < tail ? pow(1.0 - behind / tail, 1.4) : 0.0;
        trail *= smoothstep(0.0, 0.3, behind);
        float streak = n3(vec3(d * 4.0, i * 3.1, 0.0)); streak = 0.15 + 0.85 * streak * streak;
        float edge = exp(-pow((d - 0.55) * 3.0, 2.0));
        s += across * trail * (streak + 1.4 * edge) * (0.6 + 0.4 * h(vec3(i, 6.0, 0.0)));
    }
    return s;
}

// The halo's light where the ray [d] crosses its shell at [hit] (from the middle).
float haloAt(vec3 hit, vec3 d, float ticks) {
    vec3 n = normalize(hit);
    float rim = 1.0 - abs(dot(n, d));
    return 0.01 + veils(n, ticks) * (1.2 + rim) + 0.15 * pow(rim, 3.0);
}

// Where on the screen the world along [d] (world axes, from the camera) is.
vec2 screenOf(vec3 d) {
    vec4 clip = ProjMat * vec4(d * ViewToWorld, 1.0);
    if (clip.w <= 1e-4) return gl_FragCoord.xy / ScreenSize;
    return clamp(clip.xy / clip.w * 0.5 + 0.5, vec2(0.001), vec2(0.999));
}

void main() {
    float ticks = SpellTicks;
    float core = radii.x, outer = radii.y;
    float box = outer;
    vec3 p = outward;
    vec3 d = normalize(viewPos);
    vec3 eye = p - viewPos;
    bool inside = max(abs(eye.x), max(abs(eye.y), abs(eye.z))) < box;
    // The cube's own normal here, to tell the faces the ray enters by from those it leaves by.
    vec3 ap = abs(p);
    vec3 nrm = ap.x >= ap.y && ap.x >= ap.z ? vec3(sign(p.x), 0.0, 0.0) : (ap.y >= ap.z ? vec3(0.0, sign(p.y), 0.0) : vec3(0.0, 0.0, sign(p.z)));
    bool entering = dot(d, nrm) < 0.0;
    // Outside, march in from where the ray enters; inside, from the eye out.
    if (inside == entering) discard;
    vec3 start = inside ? eye : p;
    // The disk's frame: level, tipped TILT about a horizontal axis its own way.
    float yaw = vertexColor.r * 6.2832;
    vec3 tiltAxis = vec3(cos(yaw), 0.0, sin(yaw));

    // The scene hides what's behind it: the ground it sits on, a wall it swells into.
    float scene = sceneDistance(d) - (inside ? 0.0 : length(viewPos));
    if (scene <= 0.0) discard;
    // Where the ray passes nearest the middle, and how near: past the halo's glow and the pull on
    // the world, a ray that misses the disk has nothing to draw.
    float along = -dot(start, d);
    float miss = length(start + d * max(along, 0.0));
    vec2 shellHit = sphereHits(start, d, outer);
    vec2 coreHit = sphereHits(start, d, core);
    bool black = coreHit.y > 0.0 && coreHit.x < scene;
    // The ray in the disk's own frame (level), and where it's within the disk's slab.
    vec3 s0 = turn(start, tiltAxis, -TILT);
    vec3 d0 = turn(d, tiltAxis, -TILT);
    float slab = core * SLAB;
    vec2 inSlab = abs(d0.y) < 1e-5 ? (abs(s0.y) < slab ? vec2(0.0, 1e5) : vec2(1.0, 0.0))
        : vec2(min((-slab - s0.y) / d0.y, (slab - s0.y) / d0.y), max((-slab - s0.y) / d0.y, (slab - s0.y) / d0.y));
    float t0 = max(max(shellHit.x, inSlab.x), 0.0);
    float t1 = min(shellHit.y, inSlab.y);
    if (black) t1 = min(t1, max(coreHit.x, 0.0));
    t1 = min(t1, scene);
    bool disk = shellHit.y > 0.0 && t1 > t0;
    if (!disk && !black && miss > outer * 0.95) discard;

    vec3 light = vec3(0.0);
    float trans = 1.0;
    if (disk) {
        float stepLen = (t1 - t0) / float(STEPS);
        float t = t0 + stepLen * h(vec3(gl_FragCoord.xy, ticks));
        for (int i = 0; i < STEPS; i++) {
            vec3 col;
            float k = fire(s0 + d0 * t, core, outer, ticks, col) * stepLen * GLOW / core;
            light += trans * col * k;
            trans *= exp(-k);
            t += stepLen;
            if (trans < 0.01) break;
        }
    }
    // The halo hugging the core: its shell where the ray crosses it in front of the black (and
    // behind, where the black doesn't hide it), and a soft light round the black.
    vec2 haloHit = sphereHits(start, d, core * HALO);
    if (haloHit.y > 0.0) {
        float far = black ? -1.0 : haloHit.y;
        if (haloHit.x > 0.0 && haloHit.x < scene) light += trans * HALO_COLOUR * haloAt(start + d * haloHit.x, d, ticks) * 0.35;
        if (far > 0.0 && far < scene) light += trans * HALO_COLOUR * haloAt(start + d * far, d, ticks) * 0.25;
    }
    if (!black && along > 0.0) light += trans * HALO_COLOUR * 0.5 * exp(-max(miss - core, 0.0) / (core * 0.35));

    float shown = vertexColor.a;
    // The fire's light, kept from going flat white.
    vec3 lit = 1.0 - exp(-light * EXPOSURE);
    if (black) {
        fragColor = vec4(lit, shown);
        return;
    }
    // What's behind: the screen as it was, along a ray pulled a little toward the core, the pull
    // easing off to nothing well inside the box so its faces never show.
    vec3 bent = d;
    if (along > 0.0) {
        float pull = BEND * 0.35 * core / max(miss, core * 0.8);
        pull *= 1.0 - smoothstep(outer * 0.55, outer * 0.95, miss);
        bent = normalize(d - normalize(start + d * along) * pull);
    }
    vec3 behind = texture(SceneSampler, screenOf(bent)).rgb;
    fragColor = vec4(lit + trans * behind, shown);
}
