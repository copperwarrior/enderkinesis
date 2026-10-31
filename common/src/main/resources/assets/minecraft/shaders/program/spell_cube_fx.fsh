#version 150

// CubeN: x, y and radius in framebuffer pixels, and the element (1 Heat .. 10 Void, Element order; other values draw nothing).

uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float Time;
uniform int Count;
uniform vec4 Cube0;
uniform vec4 Cube1;
uniform vec4 Cube2;
uniform vec4 Cube3;
uniform vec4 Cube4;
uniform vec4 Cube5;
uniform vec4 Cube6;
uniform vec4 Cube7;
// DepthsN: distance from the eye to each cube (0 for none). DepthParams turns the depth buffer back into distance.
uniform sampler2D DepthSampler;
uniform int DepthTest;
uniform vec2 DepthParams;
uniform vec4 Depths0;
uniform vec4 Depths1;

in vec2 texCoord;
out vec4 fragColor;

const float K = 0.75;

float h(vec2 p, float k) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + k * 91.7) * 43758.5453); }
vec2 rot(vec2 v, float a) { float c = cos(a), s = sin(a); return vec2(c * v.x - s * v.y, s * v.x + c * v.y); }

vec2 off = vec2(0.0), split = vec2(0.0);
vec3 glow = vec3(0.0);
float dark = 1.0, desat = 0.0, sick = 0.0;

void cube(vec4 cb, vec2 px) {
    vec2 rel = px - cb.xy;
    float rad = cb.z, dist = length(rel), d = dist / rad;
    if (d >= 1.0) return;
    float f = 1.0 - d, f2 = f * f, an = atan(rel.y, rel.x), T = Time;
    vec2 dir = dist > 0.001 ? rel / dist : vec2(0.0);
    int e = int(cb.w + 0.5);
    if (e == 1) {
        // Heat.
        off += vec2(sin(px.y * 0.09 + T * 0.5) * 3.0, 2.0 + sin(px.x * 0.05 + T * 0.3) * 1.5) * f2;
        glow += vec3(1.0, 0.45, 0.1) * 0.2 * f2;
    } else if (e == 2) {
        // Cold. Its wind is geometry in SpellCore.
        off += (vec2(h(floor(px / 3.0), 1.0), h(floor(px / 3.0), 2.0)) - 0.5) * 7.0 * f2;
        glow += vec3(0.75, 0.9, 1.0) * 0.06 * f2;
    } else if (e == 3) {
        // Growth.
        off += dir * sin(d * 22.0 - T * 0.2) * 2.0 * f;
        glow += vec3(0.35, 0.75, 0.25) * 0.14 * f2;
    } else if (e == 4) {
        // Purity.
        float rays = pow(abs(cos(an * 2.0)), 60.0);
        glow += vec3(1.0) * (rays * f * 0.6 + 0.18 * f2);
    } else if (e == 5) {
        // Gravity.
        off += dir * rad * 0.1 * f2;
        dark *= (1.0 - 0.25 * smoothstep(0.35, 0.0, d)) * (1.0 - 0.35 * f2);
    } else if (e == 6) {
        // Chaos.
        float row = floor(px.y / 6.0), g = h(vec2(row, floor(T * 0.5)), 3.0);
        if (g > 0.75) off.x += (h(vec2(row, floor(T * 0.5)), 4.0) - 0.5) * 6.0 * f;
        split += vec2(5.5, 1.0) * f;
        glow += vec3(0.92, 0.42, 0.6) * 0.1 * f;
    } else if (e == 7) {
        // Blood. Its cube beats with the same heartbeat.
        float k = T * 0.25, beat = pow(max(0.0, sin(k)), 8.0) + 0.6 * pow(max(0.0, sin(k - 0.6)), 8.0);
        glow += vec3(0.75, 0.05, 0.08) * 0.3 * beat * f2;
        dark *= 1.0 - 0.15 * beat * f;
    } else if (e == 8) {
        // Sunfire.
        float front = fract(T * 0.025), w = exp(-pow((d - front) / 0.08, 2.0));
        off -= dir * w * 5.0 * (1.0 - front);
        float rays = pow(abs(cos(an * 3.0 + T * 0.01)), 40.0);
        glow += vec3(1.0, 0.78, 0.35) * (0.3 * f2 + rays * f * 0.5);
    } else if (e == 9) {
        // Poison.
        sick = max(sick, 0.45 * f);
    } else if (e == 10) {
        // Void.
        float tend = 0.0, side = 0.0;
        for (int k = 0; k < 5; k++) {
            float fk = float(k), a0 = fk * 1.2566 + 0.3 * sin(T * 0.013 + fk * 2.0);
            float wave = a0 + 0.55 * sin(d * 7.0 - T * 0.06 + fk * 1.9) * d;
            float da = mod(an - wave + 3.1416, 6.2832) - 3.1416, w = 0.32 * (1.0 - d) + 0.03;
            float t = exp(-pow(da / w, 2.0)) * smoothstep(1.0, 0.55, d) * smoothstep(0.05, 0.25, d);
            tend = max(tend, t);
            side += t * sign(da);
        }
        off += vec2(-dir.y, dir.x) * side * 2.5 * f;
        dark *= (1.0 - 0.55 * smoothstep(0.45, 0.0, d)) * (1.0 - 0.7 * tend);
        desat = max(desat, 0.55 * f);
    }
}

float sceneDistance = 0.0;

// The cube fades over half a block as the scene comes nearer than it.
float visible(float depth) {
    if (DepthTest == 0 || depth <= 0.0) return 1.0;
    return smoothstep(depth - 0.5, depth, sceneDistance);
}

void cubeAt(vec4 cb, vec2 px, float depth) {
    float vis = visible(depth);
    if (vis <= 0.0) return;
    vec2 off0 = off, split0 = split;
    vec3 glow0 = glow;
    float dark0 = dark, desat0 = desat, sick0 = sick;
    cube(cb, px);
    off = mix(off0, off, vis); split = mix(split0, split, vis); glow = mix(glow0, glow, vis);
    dark = mix(dark0, dark, vis); desat = mix(desat0, desat, vis); sick = mix(sick0, sick, vis);
}

void main() {
    vec2 px = texCoord * OutSize;
    if (DepthTest != 0) sceneDistance = DepthParams.y / (texture(DepthSampler, texCoord).r * 2.0 - 1.0 + DepthParams.x);
    if (Count > 0) cubeAt(Cube0, px, Depths0.x);
    if (Count > 1) cubeAt(Cube1, px, Depths0.y);
    if (Count > 2) cubeAt(Cube2, px, Depths0.z);
    if (Count > 3) cubeAt(Cube3, px, Depths0.w);
    if (Count > 4) cubeAt(Cube4, px, Depths1.x);
    if (Count > 5) cubeAt(Cube5, px, Depths1.y);
    if (Count > 6) cubeAt(Cube6, px, Depths1.z);
    if (Count > 7) cubeAt(Cube7, px, Depths1.w);
    off *= K; split *= K; glow *= K; dark = mix(1.0, dark, K); desat *= K; sick *= K;
    vec2 uv = (px + off) / OutSize, sp = split / OutSize;
    vec3 c = vec3(texture(DiffuseSampler, uv + sp).r, texture(DiffuseSampler, uv).g, texture(DiffuseSampler, uv - sp).b);
    float lum = dot(c, vec3(0.299, 0.587, 0.114));
    c = mix(c, vec3(lum), desat);
    c = mix(c, lum * vec3(0.62, 0.9, 0.35), sick);
    fragColor = vec4(c * dark + glow, 1.0);
}
