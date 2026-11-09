#version 150

// Main depth after the clouds.
uniform sampler2D CloudDepthSampler;
// Main depth with particles, just before the clouds.
uniform sampler2D ParticleDepthSampler;

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float after = texelFetch(CloudDepthSampler, p, 0).r;
    if (after >= texelFetch(ParticleDepthSampler, p, 0).r) discard;
    gl_FragDepth = after;
    fragColor = vec4(0.0);
}
