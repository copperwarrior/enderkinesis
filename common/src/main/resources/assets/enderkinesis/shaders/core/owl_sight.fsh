#version 150

uniform sampler2D Sampler0;
uniform int Mode;
uniform int Bit;
uniform float AlphaThreshold;

out vec4 fragColor;

void main() {
    int channel = Bit / 8;
    int value = 1 << (Bit % 8);
    if (Mode == 0) {
        vec4 bits = vec4(0.0);
        bits[channel] = float(value) / 255.0;
        fragColor = bits;
        return;
    }
    if (Mode == 2 && texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).a < AlphaThreshold) {
        discard;
    }
    if (Mode == 3) {
        int stored = int(texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0)[channel] * 255.0 + 0.5);
        if ((stored & value) == 0) {
            discard;
        }
    }
    fragColor = vec4(0.0);
}
