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

// Values in [-1, 1] for each tentacle, so each portal curls its own way.
vec4 tentacleHash(float k) {
    return fract(sin(vec4(12.9898, 78.233, 37.719, 93.989) * (k + 1.0) + Seed * vec4(1.0, 1.7, 2.3, 2.9)) * 43758.5453) * 2.0 - 1.0;
}
const vec3 TINT = vec3(0.30, 0.62, 0.66);

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
    for (int i = 0; i < STEPS; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        float r = length(p.xz);
        float rn = r / Reach;
        if (rn > 1.0) continue;
        float theta = atan(p.z, p.x + 1.0e-6);
        float dens = 0.0;
        for (int k = 0; k < TENTACLES; k++) {
            float fk = float(k);
            vec4 h = tentacleHash(fk);
            float heading = fk * TAU / float(TENTACLES) + Seed + 0.4 * h.x + 0.3 * sin(Time * 0.04 + fk * 1.3);
            // A wave runs out along the tentacle, larger toward the tip, sideways and up and down.
            float wave = rn * sin(rn * 5.0 - Time * 0.35 + fk * 2.1);
            float waveUp = rn * sin(rn * 4.0 - Time * 0.27 + fk * 3.7);
            // Each tentacle tilts and curls its own way: left or right, up or down.
            float curl = rn * rn * rn;
            float centre = heading + CURL * h.y * curl + WRITHE * wave;
            float side = atan(sin(theta - centre), cos(theta - centre)) * max(r, 0.3);
            float centreY = sin(TILT * h.z) * r + Reach * (CURL_UP * h.w * curl + WRITHE_UP * waveUp);
            float dy = p.y - centreY;
            // Thick at the Heart, tapering to a point.
            float width = mix(BASE_WIDTH, TIP_WIDTH, pow(rn, 0.8));
            dens += exp(-(side * side + dy * dy) / (width * width));
        }
        dens *= 1.0 - smoothstep(0.85, 1.0, rn);
        float a = 1.0 - exp(-DENSITY * dens * dt);
        colour += through * a * mix(TINT, FogColor, smoothstep(0.1, 0.6, rn));
        through *= 1.0 - a;
    }
    float alpha = (1.0 - through) * Strength;
    if (alpha < 0.003) discard;
    // Premultiplied (wohlon_portal_fog.json blend).
    fragColor = vec4(colour * Strength, alpha);
}
