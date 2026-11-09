#version 150

// Main colour and alpha before the translucent layer (VolumeDepth.opaque).
uniform sampler2D OpaqueColourSampler;

out vec4 fragColor;

void main() {
    fragColor = vec4(0.0, 0.0, 0.0, texelFetch(OpaqueColourSampler, ivec2(gl_FragCoord.xy), 0).a);
}
