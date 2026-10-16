#version 150

in float vHeight;
in float vAlpha;

out vec4 fragColor;

void main() {
    if (vAlpha == 0.0) {
        discard;
    }
    // G = 1 marks a texel with mesh. Alpha 1 keeps the values exact if blending is on.
    fragColor = vec4(vHeight, 1.0, 0.0, 1.0);
}
