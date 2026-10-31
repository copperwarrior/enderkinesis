#version 150

// UV0 (along) selects the part. Shell: x < 0. Rays: x and y in 0..1.
// Ground pool: x in 1.5..3.5, read as along - 2. Wearer glow: x > 3.5, read as along - 4.

// Use SpellClock ticks: GameTime wraps each day, and the noise would jump there.
uniform float SpellTicks;

in vec3 viewPos;
in vec3 outward;
in vec2 along;
in vec4 vertexColor;

out vec4 fragColor;

// Hash without sin: sin of large values loses precision on some GPUs (phones), and the veils change there.
float h(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

const int VEILS = 7;

vec3 axisOf(float i, float ticks) {
    float spin = (0.01 + 0.012 * h(vec3(i, 2.0, 0.0))) * (mod(i, 2.0) < 1.0 ? 1.0 : -1.0);
    float th = h(vec3(i, 1.0, 0.0)) * 6.2832 + ticks * spin;
    float ph = acos(2.0 * h(vec3(i, 3.0, 0.0)) - 1.0);
    return vec3(sin(ph) * cos(th), cos(ph), sin(ph) * sin(th));
}

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

void main() {
    float ticks = SpellTicks;
    float glow;
    if (along.x < -0.5) {
        vec3 n = normalize(outward);
        float facing = abs(dot(n, normalize(-viewPos)));
        float rim = 1.0 - facing;
        glow = 0.01 + veils(n, ticks) * (1.2 + 1.0 * rim) + 0.15 * pow(rim, 3.0);
    } else if (along.x > 3.5) {
        // Keep the body clear (a tall ellipse), so the wearer stays visible inside the light.
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
    fragColor = vec4(mix(vertexColor.rgb, vec3(1.0), clamp(glow * 0.7, 0.0, 1.0)), min(a, 1.0));
}
