#version 150

uniform sampler2D DepthSampler;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Portal space: world axes, the portal centre at the origin.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
// Horizontal length of the tentacles.
uniform float Reach;
// Seconds; the host keeps it bounded for float precision.
uniform float Time;
uniform float Seed;
uniform float Strength;
uniform vec3 FogColor;
// 0..1: how far the tips turn toward Target, the local player's chest in portal space.
uniform float Lean;
uniform vec3 Target;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 32;
const int TENTACLES = 6;
const float TAU = 6.28318531;
const float DENSITY = 1.6;
// Most bend, in radians to the side and in blocks up or down for each Reach, near the tip.
const float CURL = 1.4;
const float CURL_UP = 0.7;
// Most tilt up or down where a tentacle leaves the Heart, in radians.
const float TILT = 0.5;
// The slow writhe at the tip: radians to the side, and blocks up or down for each Reach.
const float WRITHE = 0.35;
const float WRITHE_UP = 0.15;
const float BASE_WIDTH = 0.28;
const float TIP_WIDTH = 0.05;
// The share of the lean that a tentacle facing away from the player still takes.
const float LEAN_BACK = 0.35;
// The lean's tip height limit, a share of Reach: past the box edge (Reach) the march never samples the tip.
const float LEAN_Y_LIMIT = 0.85;

// Values in [-1, 1] for each tentacle, so each portal curls its own way.
vec4 tentacleHash(float k) {
    return fract(sin(vec4(12.9898, 78.233, 37.719, 93.989) * (k + 1.0) + Seed * vec4(1.0, 1.7, 2.3, 2.9)) * 43758.5453) * 2.0 - 1.0;
}
const vec3 TINT = vec3(0.30, 0.62, 0.66);

// Equal to atan(sin(x), cos(x)), without the three transcendentals.
float wrapAngle(float x) {
    return x - TAU * floor(x / TAU + 0.5);
}

// Indices of the march samples t0 + (i + jitter) * dt that can lie in [tA, tB], one more on each side for float error.
ivec2 stepRange(float tA, float tB, float t0, float dt, float jitter) {
    vec2 r = vec2(floor((tA - t0) / dt - jitter) - 1.0, floor((tB - t0) / dt - jitter) + 2.0);
    return ivec2(clamp(r, 0.0, float(STEPS)));
}

// The samples with rn <= 1; the step grid stays the box's, so the image does not change.
ivec2 cylinderSteps(vec3 dir, float t0, float dt, float jitter) {
    vec2 o = CamLocal.xz;
    vec2 d = dir.xz;
    float a = dot(d, d);
    // A ray along Y keeps one radius; the per-sample test decides it.
    if (a < 1.0e-8) return ivec2(0, STEPS);
    float b = dot(o, d);
    // A little past the surface, so float error cannot drop an edge sample.
    float disc = b * b - a * (dot(o, o) - Reach * Reach * 1.002);
    if (disc <= 0.0) return ivec2(0);
    float s = sqrt(disc);
    return stepRange((-b - s) / a, (-b + s) / a, t0, dt, jitter);
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
    if (t1 <= t0) discard;

    float dt = (t1 - t0) / float(STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    vec3 colour = vec3(0.0);
    float through = 1.0;
    // Terms that do not change along the ray, out of the step loop.
    vec4 hs[TENTACLES];
    float headings[TENTACLES];
    float tilts[TENTACLES];
    float leans[TENTACLES];
    // (sin, cos) of each wave's time phase; each step adds rn * 5 or rn * 4 with angle addition.
    vec2 wavePhase[TENTACLES];
    vec2 waveUpPhase[TENTACLES];
    float targetR = length(Target.xz);
    float targetHeading = atan(Target.z, Target.x + 1.0e-6);
    float targetSlope = clamp(Target.y / max(targetR, 0.5), -2.0, 2.0);
    // Straight above or below the Heart there is no heading to turn to, and the slope sends the tips out of the box.
    float lean = Lean * smoothstep(0.3, 1.0, targetR) * smoothstep(0.25, 0.6, targetR / max(length(Target), 1.0e-3));
    for (int k = 0; k < TENTACLES; k++) {
        float fk = float(k);
        hs[k] = tentacleHash(fk);
        headings[k] = fk * TAU / float(TENTACLES) + Seed + 0.4 * hs[k].x + 0.3 * sin(Time * 0.04 + fk * 1.3);
        tilts[k] = sin(TILT * hs[k].z);
        leans[k] = lean * mix(LEAN_BACK, 1.0, max(cos(headings[k] - targetHeading), 0.0));
        float phase = fk * 2.1 - Time * 0.35;
        float phaseUp = fk * 3.7 - Time * 0.27;
        wavePhase[k] = vec2(sin(phase), cos(phase));
        waveUpPhase[k] = vec2(sin(phaseUp), cos(phaseUp));
    }
    ivec2 steps = cylinderSteps(dir, t0, dt, jitter);
    for (int i = steps.x; i < steps.y; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        float r = length(p.xz);
        float rn = r / Reach;
        if (rn > 1.0) continue;
        float theta = atan(p.z, p.x + 1.0e-6);
        // Terms that are the same for all tentacles at this sample.
        vec2 along = vec2(sin(rn * 5.0), cos(rn * 5.0));
        vec2 alongUp = vec2(sin(rn * 4.0), cos(rn * 4.0));
        // Each tentacle tilts and curls its own way: left or right, up or down.
        float curl = rn * rn * rn;
        // None at the base, all of it at the tip.
        float pullRamp = smoothstep(0.1, 1.0, rn);
        float leanY = clamp(targetSlope * r, -LEAN_Y_LIMIT * Reach, LEAN_Y_LIMIT * Reach);
        float sideScale = max(r, 0.3);
        // Thick at the Heart, tapering to a point.
        float width = mix(BASE_WIDTH, TIP_WIDTH, pow(rn, 0.8));
        float invWidth2 = 1.0 / (width * width);
        float dens = 0.0;
        for (int k = 0; k < TENTACLES; k++) {
            vec4 h = hs[k];
            vec2 ph = wavePhase[k];
            vec2 phUp = waveUpPhase[k];
            // A wave runs out along the tentacle, larger toward the tip, sideways and up and down.
            float wave = rn * (along.x * ph.y + along.y * ph.x);
            float waveUp = rn * (alongUp.x * phUp.y + alongUp.y * phUp.x);
            float centre = headings[k] + CURL * h.y * curl + WRITHE * wave;
            float centreY = tilts[k] * r + Reach * (CURL_UP * h.w * curl + WRITHE_UP * waveUp);
            if (lean > 0.0) {
                float pull = leans[k] * pullRamp;
                centre += wrapAngle(targetHeading - centre) * pull;
                centreY = mix(centreY, leanY, pull);
            }
            float side = wrapAngle(theta - centre) * sideScale;
            float dy = p.y - centreY;
            dens += exp(-(side * side + dy * dy) * invWidth2);
        }
        dens *= 1.0 - smoothstep(0.85, 1.0, rn);
        float a = 1.0 - exp(-DENSITY * dens * dt);
        colour += through * a * mix(TINT, FogColor, smoothstep(0.1, 0.6, rn));
        through *= 1.0 - a;
        // What is left changes the pixel by less than 1/255.
        if (through < 0.004) break;
    }
    float alpha = (1.0 - through) * Strength;
    if (alpha < 0.003) discard;
    // Premultiplied into VolumeLayer (wohlon_portal_fog.json blend).
    fragColor = vec4(colour * Strength, alpha);
}
