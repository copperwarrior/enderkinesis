#version 150

// Thin at the silhouette, so the two shells add up to one soft column of light.

// Use SpellClock ticks: GameTime wraps each day, and the noise would jump there.
uniform float SpellTicks;

in vec3 viewPos;
in vec3 outward;
in vec2 along;
in vec4 vertexColor;

out vec4 fragColor;

float h(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm(vec3 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { s += a * n3(p); p = p * 2.03 + 7.1; a *= 0.5; }
    return s;
}

void main() {
    float ticks = SpellTicks;
    vec3 n = normalize(outward);
    vec3 q = vec3(n.x * 0.3, along.x, n.z * 0.3 + n.y * 0.3);
    q = (floor(q * 16.0) + 0.5) / 16.0;
    float noise = fbm(q * vec3(2.2, 1.1, 2.2) - vec3(0.0, ticks * 0.07, 0.0));
    float facing = abs(dot(n, normalize(-viewPos)));
    float depth = pow(facing, 0.8);
    float ends = smoothstep(0.0, 0.8, along.x) * smoothstep(0.0, 0.5, along.y - along.x);
    float lit = smoothstep(0.3, 0.75, noise);
    float a = vertexColor.a * ends * depth * (0.55 + 1.5 * lit);
    if (a < 0.003) discard;
    vec3 hot = mix(vertexColor.rgb, vec3(1.0), 0.55);
    fragColor = vec4(mix(vertexColor.rgb, hot, lit), a);
}
