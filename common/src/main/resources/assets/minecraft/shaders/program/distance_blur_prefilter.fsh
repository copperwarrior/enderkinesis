#version 150

uniform sampler2D MainSampler;
uniform sampler2D DepthSampler;
// volume_half_depth over main depth: the raw depth each half texel stands for.
uniform sampler2D HalfDepthSampler;

uniform mat4 InvViewProj;
// View distance where the blur starts and where it is full.
uniform vec2 BlurRange;
// 1 for FogShape.SPHERE, 0 for FogShape.CYLINDER.
uniform float SphereFog;

out vec4 fragColor;

// RGB: colour of the full-res texel that the half depth picked, so a silhouette does not mix near and far colour.
// A: blur amount, 0 near to 1 far.
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 maxTexel = textureSize(DepthSampler, 0) - 1;
    float depth = texelFetch(HalfDepthSampler, p, 0).r;
    ivec2 pick = min(p * 2, maxTexel);
    for (int i = 0; i < 4; i++) {
        ivec2 t = min(p * 2 + ivec2(i & 1, i >> 1), maxTexel);
        if (texelFetch(DepthSampler, t, 0).r == depth) {
            pick = t;
            break;
        }
    }
    float amount = 1.0;
    if (depth < 1.0) {
        vec2 ndc = (vec2(pick) + 0.5) / vec2(maxTexel + 1) * 2.0 - 1.0;
        vec4 world = InvViewProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
        vec3 rel = world.xyz / world.w;
        float dist = SphereFog > 0.5 ? length(rel) : max(length(rel.xz), abs(rel.y));
        amount = clamp((dist - BlurRange.x) / (BlurRange.y - BlurRange.x), 0.0, 1.0);
    }
    fragColor = vec4(texelFetch(MainSampler, pick, 0).rgb, amount);
}
