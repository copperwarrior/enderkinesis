#version 150

// Sunfire's Halo, in three parts told apart by UV0 (`along`):
// - The shell (along.x < 0): a sphere round its wearer, nearly clear, swept by a few broad,
//   soft veils of light. Each veil swings round its own tilted circle like a comet, bright at its
//   head and fading along its tail, streaked along its length; the circles themselves turn slowly.
// - Rays (along in 0..1): faint shafts of light thrown out from the shell, bright down their
//   middle (along.x across, 0..1) and fading toward their tips (along.y, out from the shell).
// - The glow round the wearer (along.x > 3.5, along - 4 a 0..1 square): soft light that clears
//   over their body so they stay in sight.
// - The pool (along.x in 1.5..3.5, along - 2 a 0..1 square): light pooled on the ground.
// Procedural: no texture.

// Ticks on the spell visuals' own clock (SpellClock): it doesn't wrap every day as GameTime does.
uniform float SpellTicks;

in vec3 viewPos;
in vec3 outward;
in vec2 along;
in vec4 vertexColor;

out vec4 fragColor;

// A hash without sin: sin of large numbers loses its precision on some GPUs (phones), and the
// veils would come out differently there.
float h(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

const int VEILS = 7;

// Veil i's circle: its axis, tilted its own way and turning slowly about the vertical.
vec3 axisOf(float i, float ticks) {
    float spin = (0.01 + 0.012 * h(vec3(i, 2.0, 0.0))) * (mod(i, 2.0) < 1.0 ? 1.0 : -1.0);
    float th = h(vec3(i, 1.0, 0.0)) * 6.2832 + ticks * spin;
    float ph = acos(2.0 * h(vec3(i, 3.0, 0.0)) - 1.0);
    return vec3(sin(ph) * cos(th), cos(ph), sin(ph) * sin(th));
}

// Broad, soft veils of light, each sweeping round its own tilted circle on the sphere like a
// comet: brightest at its head, fading along its tail, streaked along its length, with a
// brighter leading edge on one side.
float veils(vec3 n, float ticks) {
    float s = 0.0;
    for (int k = 0; k < VEILS; k++) {
        float i = float(k);
        vec3 axis = axisOf(i, ticks);
        vec3 u = normalize(cross(axis, vec3(0.31, 0.95, 0.17)));
        vec3 v = cross(axis, u);
        float ang = atan(dot(n, v), dot(n, u));
        // Across the band, in band widths.
        float w = 0.07 + 0.07 * h(vec3(i, 5.0, 0.0));
        float d = (dot(n, axis) - (h(vec3(i, 4.0, 0.0)) - 0.5) * 1.1) / w;
        float across = exp(-d * d);
        // How far behind the head this is, round the way it sweeps (about a turn every 2 to 3 s).
        float dir = mod(i, 3.0) < 1.0 ? -1.0 : 1.0;
        float head = ticks * (0.1 + 0.06 * h(vec3(i, 7.0, 0.0))) + 6.2832 * h(vec3(i, 8.0, 0.0));
        float behind = mod(dir * (head - ang), 6.2832);
        float tail = 1.6 + 1.4 * h(vec3(i, 9.0, 0.0));
        float trail = behind < tail ? pow(1.0 - behind / tail, 1.4) : 0.0;
        // A soft head, brightest just behind it.
        trail *= smoothstep(0.0, 0.3, behind);
        // Fine streaks running along it, and its brighter leading edge.
        float streak = n3(vec3(d * 4.0, i * 3.1, 0.0)); streak = 0.15 + 0.85 * streak * streak;
        float edge = exp(-pow((d - 0.55) * 3.0, 2.0));
        s += across * trail * (streak + 1.4 * edge) * (0.6 + 0.4 * h(vec3(i, 6.0, 0.0)));
    }
    return s;
}

void main() {
    float ticks = SpellTicks;
    float glow;
    if (along.x < -0.5) {
        vec3 n = normalize(outward);
        float facing = abs(dot(n, normalize(-viewPos)));
        float rim = 1.0 - facing;
        glow = 0.01 + veils(n, ticks) * (1.2 + 1.0 * rim) + 0.15 * pow(rim, 3.0);
    } else if (along.x > 3.5) {
        // Clear over the wearer's body (a tall ellipse), so they stay in sight inside their light.
        vec2 q = (along - 4.5) * 2.0;
        float r = length(q);
        float body = smoothstep(0.5, 0.85, length(vec2(q.x * 2.3, q.y)));
        glow = 1.3 * pow(max(0.0, 1.0 - r), 1.6) * mix(0.08, 1.0, body);
    } else if (along.x > 1.5) {
        float r = length(along - 2.5) * 2.0;
        glow = 1.3 * pow(max(0.0, 1.0 - r), 1.6);
    } else {
        float core = 1.0 - abs(along.x * 2.0 - 1.0);
        glow = core * core * smoothstep(0.0, 0.12, along.y) * pow(max(0.0, 1.0 - along.y), 1.6);
    }
    float a = vertexColor.a * glow;
    if (a < 0.003) discard;
    // The brightest light goes white.
    fragColor = vec4(mix(vertexColor.rgb, vec3(1.0), clamp(glow * 0.7, 0.0, 1.0)), min(a, 1.0));
}
