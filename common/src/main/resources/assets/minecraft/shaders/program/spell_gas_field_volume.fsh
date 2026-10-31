#version 150

// The march is camera-relative, but the noise is in world space, so the gas does not slide as the camera moves.

uniform sampler2D DepthSampler;
// One texel per block from FieldOrigin: the gas now, and the gas one tick before.
uniform sampler3D FieldNow;
uniform sampler3D FieldWas;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Use SpellClock ticks: GameTime wraps each day, and the noise would jump there.
uniform float SpellTicks;
uniform vec3 CameraPos;
uniform vec3 FieldOrigin;
uniform vec3 FieldSize;
uniform vec3 FieldTexSize;
// 0: the gas one tick before. 1: the gas now.
uniform float FieldBlend;
uniform vec3 DarkColor;
uniform vec3 LightColor;

in vec3 viewPos;

out vec4 fragColor;

const float NOISE_SCALE = 0.55;

float h(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float n3(vec3 p) {
    vec3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(h(i), h(i + vec3(1, 0, 0)), f.x), mix(h(i + vec3(0, 1, 0)), h(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(h(i + vec3(0, 0, 1)), h(i + vec3(1, 0, 1)), f.x), mix(h(i + vec3(0, 1, 1)), h(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}

float fbm2(vec3 p) {
    return (0.5 * n3(p) + 0.25 * n3(p * 2.07 + 5.3)) / 0.75;
}

float field(vec3 q) {
    // Half a texel inside the used part, so the filter reads no texel from past its edge.
    vec3 uvw = clamp(q, vec3(0.5), FieldSize - 0.5) / FieldTexSize;
    return mix(texture(FieldWas, uvw).r, texture(FieldNow, uvw).r, FieldBlend);
}

void main() {
    float ticks = SpellTicks;
    vec2 texCoord = gl_FragCoord.xy / ScreenSize;
    vec3 rd = normalize(viewPos);
    vec3 inv = 1.0 / (rd + vec3(equal(rd, vec3(0.0))) * 1e-6);
    vec3 ta = FieldOrigin * inv, tb = (FieldOrigin + FieldSize) * inv;
    vec3 lo = min(ta, tb), hi = max(ta, tb);
    float t0 = max(max(lo.x, lo.y), max(lo.z, 0.0));
    float t1 = min(min(hi.x, hi.y), hi.z);
    float depth = texture(DepthSampler, texCoord).r;
    if (depth < 1.0) {
        vec4 scene = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
        t1 = min(t1, length(scene.xyz / scene.w));
    }
    if (t1 <= t0) discard;
    const int MAX_STEPS = 56;
    float stepLen = clamp(0.5 + 0.02 * t0, 0.5, 1.5);
    int steps = int(clamp(ceil((t1 - t0) / stepLen), 4.0, float(MAX_STEPS)));
    float dt = (t1 - t0) / float(steps), trans = 1.0;
    float jitter = h(vec3(gl_FragCoord.xy, fract(ticks * 0.013) * 61.0));
    vec3 drift = vec3(ticks * 0.01, -ticks * 0.006, ticks * 0.008);
    vec3 col = vec3(0.0);
    for (int i = 0; i < MAX_STEPS; i++) {
        if (i >= steps) break;
        vec3 p = rd * (t0 + (float(i) + jitter) * dt);
        vec3 q = p - FieldOrigin;
        // The wisps reach about one block, so far from gas the noise is not necessary.
        if (field(q) < 0.002) continue;
        vec3 w = (p + CameraPos) * NOISE_SCALE + drift;
        float n0 = fbm2(w);
        float n1 = fbm2(w + 7.3);
        float n = mix(n0, n1, 0.35);
        // Sample a little off the point, so the gas makes wisps and does not fill whole blocks.
        float f = field(q + vec3(n0 - 0.5, (n - 0.5) * 0.5, n1 - 0.5) * 1.3);
        if (f < 0.004) continue;
        float dens = f * (0.45 + 0.55 * smoothstep(0.2, 0.75, n));
        float a = 1.0 - exp(-dens * dt * 1.4);
        vec3 lit = mix(DarkColor, LightColor, clamp(0.35 + (n - 0.5) * 1.2 + (1.0 - f) * 0.3, 0.0, 1.0));
        col += trans * a * lit;
        trans *= 1.0 - a;
        if (trans < 0.04) break;
    }
    float alpha = 1.0 - trans;
    if (alpha < 0.01) discard;
    fragColor = vec4(col / max(alpha, 0.001), alpha);
}
