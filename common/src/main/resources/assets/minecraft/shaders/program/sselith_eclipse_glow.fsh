#version 150

uniform sampler2D DepthSampler;
uniform sampler3D LightVolume;

uniform mat4 InvViewProj;
// Camera position in volume texels: the volume wraps, so only the low bits of the block matter.
uniform vec3 CamWrap;
// Texel axes are (x, z, y).
uniform vec3 VolumeSize;
uniform vec2 DepthParams;
uniform float MaxDist;
uniform float FadeStart;
uniform float Strength;
// Luma of lightmap row 0 per block light level, from the CPU: a dynamically indexed local array spills to scratch.
uniform float Curve[16];

in vec2 texCoord;

out vec4 fragColor;

// Bounds the march: one step per block on a MaxDist path.
const int MAX_STEPS = 64;

// Interleaved gradient noise (Jimenez 2014): a start offset per pixel turns step bands into grain the upsample softens.
float startJitter(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 rel = InvViewProj * vec4(vec3(texCoord, depth) * 2.0 - 1.0, 1.0);
    vec3 relPos = rel.xyz / rel.w;
    vec3 dir = normalize(relPos);
    float t1 = depth >= 1.0 ? MaxDist : min(length(relPos), MaxDist);

    float glow = 0.0;
    float t = startJitter(gl_FragCoord.xy);
    for (int i = 0; i < MAX_STEPS && t < t1; i++) {
        vec3 p = CamWrap + dir * t;
        float level = texture(LightVolume, p.xzy / VolumeSize).r * 15.0;
        int lo = min(int(level), 15);
        float lum = mix(Curve[lo], Curve[min(lo + 1, 15)], level - float(lo));
        glow += lum * min(1.0, t1 - t) * (1.0 - smoothstep(FadeStart, MaxDist, t));
        t += 1.0;
    }

    fragColor = vec4(glow * Strength, DepthParams.y / (depth * 2.0 - 1.0 + DepthParams.x), 0.0, 1.0);
}
