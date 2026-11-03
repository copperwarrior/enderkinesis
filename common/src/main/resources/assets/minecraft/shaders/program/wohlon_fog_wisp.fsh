#version 150

uniform sampler2D DepthSampler;
// Tiling 64³ fBm (WohlonNoiseTexture.id3D), on the unit that WohlonFogWisps binds by hand.
uniform sampler3D Noise3D;

uniform mat4 InvViewProj;
uniform vec2 ScreenSize;
// Wisp space: world axes, the wisp centre at the origin.
uniform vec3 CamLocal;
uniform vec3 BoxMin;
uniform vec3 BoxMax;
// Half extents on X, Y and Z.
uniform vec3 Size;
// World position of the wisp centre, X and Z mod the noise period, so the noise is one field in the world.
uniform vec3 NoiseOrigin;
uniform float Fade;
// Seconds; the host keeps it bounded for float precision.
uniform float Time;
uniform float Density;
uniform vec3 FogColor;

in vec3 localPos;
in vec3 viewPos;

out vec4 fragColor;

const int STEPS = 24;

// Turns the noise grid off the world axes, so no lattice line runs along X, Y or Z.
const mat3 NOISE_TURN = mat3(0.00, 0.80, 0.60, -0.80, 0.36, -0.48, -0.60, -0.48, 0.64);

// blocksPerTile: world size of one 64-texel tile; 8 lattice cells span it.
float noise3(vec3 w, float blocksPerTile, vec3 drift) {
    return texture(Noise3D, NOISE_TURN * (w + drift) / blocksPerTile).r;
}

// Indices of the march samples t0 + (i + jitter) * dt that can lie in [tA, tB], one more on each side for float error.
ivec2 stepRange(float tA, float tB, float t0, float dt, float jitter) {
    vec2 r = vec2(floor((tA - t0) / dt - jitter) - 1.0, floor((tB - t0) / dt - jitter) + 2.0);
    return ivec2(clamp(r, 0.0, float(STEPS)));
}

// The samples in the unit ellipsoid; the step grid stays the box's, so the image does not change.
ivec2 ellipsoidSteps(vec3 dir, float t0, float dt, float jitter) {
    vec3 o = CamLocal / Size;
    vec3 d = dir / Size;
    float a = dot(d, d);
    float b = dot(o, d);
    // A little past the surface, so float error cannot drop an edge sample.
    float disc = b * b - a * (dot(o, o) - 1.002);
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
    ivec2 steps = ellipsoidSteps(dir, t0, dt, jitter);
    for (int i = steps.x; i < steps.y; i++) {
        vec3 p = CamLocal + dir * (t0 + (float(i) + jitter) * dt);
        vec3 e = p / Size;
        float r = length(e);
        if (r > 1.0) continue;
        // A soft floor on the ground, thinning over the full height.
        float vertical = smoothstep(-1.0, -0.3, e.y) * (1.0 - smoothstep(-0.3, 1.0, e.y));
        // A zero factor gives a = 0 exactly, so these skips leave the result the same.
        if (vertical <= 0.0) continue;
        // World-space noise, the same in every direction.
        vec3 w = NoiseOrigin + p;
        // Features of about 12 and 4 blocks.
        float n1 = noise3(w, 96.0, vec3(Time * 0.3, 0.0, 0.0));
        // Thins from the centre all the way to the edge, so no edge shows.
        float body = 1.0 - smoothstep(0.0, 1.0, r + (n1 - 0.5) * 0.6);
        if (body <= 0.0) continue;
        float n2 = noise3(w, 32.0, vec3(0.0, Time * 0.1, -Time * 0.5));
        float strands = smoothstep(0.2, 0.9, n1 * 0.55 + n2 * 0.5);
        float a = 1.0 - exp(-Density * body * strands * vertical * dt);
        colour += through * a * FogColor;
        through *= 1.0 - a;
    }
    float alpha = (1.0 - through) * Fade;
    if (alpha < 0.003) discard;
    // Premultiplied (wohlon_fog_wisp.json blend).
    fragColor = vec4(colour * Fade, alpha);
}
