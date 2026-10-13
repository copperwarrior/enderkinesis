#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float SessionTime;

in vec2 texCoord;
out vec4 fragColor;

// Mean colour of textures/block/dream_sand.png (#7A7D84).
const vec3  DREAM_SAND      = vec3(0.478, 0.490, 0.518);

const float MASK_INNER      = 0.34;
const float MASK_OUTER      = 0.68;
const float NOISE_FREQ      = 3.2;
const float NOISE_AMP       = 0.30;
const float NOISE_DRIFT     = 0.035;
// Radians for each second. The gradients turn, so the noise changes shape in place.
const float NOISE_FLOW      = 0.9;

const float TINT_ALPHA      = 0.6;
// Fractions of the screen height.
const float BLUR_RADIUS     = 0.005;
const float ABERRATION      = 0.004;
// Whole-image aberration, as a fraction of the distance from the screen centre.
const float BASE_ABERRATION = 0.002;

const int   BLUR_TAPS       = 6;
const float GOLDEN_ANGLE    = 2.39996;

vec2 gradient(vec2 cell) {
    float h = fract(sin(dot(cell, vec2(127.1, 311.7))) * 43758.5453);
    // Each cell turns at its own rate and direction.
    float a = h * 6.2831853 + SessionTime * NOISE_FLOW * (fract(h * 17.31) * 1.5 - 0.75);
    return vec2(cos(a), sin(a));
}

float perlin(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = dot(gradient(i),                  f);
    float b = dot(gradient(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0));
    float c = dot(gradient(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0));
    float d = dot(gradient(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fBm(vec2 p) {
    float sum  = 0.0;
    float amp  = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += perlin(p) * amp;
        p    = p * 2.03 + vec2(17.3, 9.1);
        amp *= 0.5;
    }
    return sum;
}

vec3 blurred(vec2 centre, vec2 radius) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < BLUR_TAPS; i++) {
        float fi = float(i);
        float ang = fi * GOLDEN_ANGLE;
        vec2 tap = vec2(cos(ang), sin(ang)) * sqrt((fi + 0.5) / float(BLUR_TAPS));
        sum += texture(DiffuseSampler, clamp(centre + tap * radius, vec2(0.001), vec2(0.999))).rgb;
    }
    return sum / float(BLUR_TAPS);
}

void main() {
    vec2 uv = texCoord - 0.5;
    float aspect = InSize.x / InSize.y;
    vec2 noiseUV = vec2(uv.x * aspect, uv.y) * NOISE_FREQ;
    float n = fBm(noiseUV + vec2(SessionTime * NOISE_DRIFT, -SessionTime * NOISE_DRIFT * 0.6));

    float mask = smoothstep(MASK_INNER, MASK_OUTER, length(uv) + n * NOISE_AMP);

    vec2 toUV = vec2(1.0 / aspect, 1.0);
    vec2 edgeDir = uv / max(length(uv), 0.0001);
    vec2 shift = uv * BASE_ABERRATION + edgeDir * toUV * ABERRATION * mask;

    // The dream-sand part of the image moves out and the remainder moves in. The sum keeps the brightness.
    vec3 outward;
    vec3 inward;
    if (mask <= 0.0) {
        outward = texture(DiffuseSampler, texCoord + shift).rgb;
        inward  = texture(DiffuseSampler, texCoord - shift).rgb;
    } else {
        vec2 radius = toUV * BLUR_RADIUS * mask;
        outward = blurred(texCoord + shift, radius);
        inward  = blurred(texCoord - shift, radius);
    }
    vec3 scene = outward * DREAM_SAND + inward * (vec3(1.0) - DREAM_SAND);

    fragColor = vec4(scene * mix(vec3(1.0), DREAM_SAND, mask * TINT_ALPHA), 1.0);
}
