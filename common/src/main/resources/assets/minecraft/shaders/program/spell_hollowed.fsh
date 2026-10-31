#version 150

uniform sampler2D DiffuseSampler;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

const float SATURATION = 0.3;
const float BRIGHTNESS = 0.9;

void main() {
    vec3 c = texture(DiffuseSampler, texCoord).rgb;
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 drained = mix(vec3(lum), c, SATURATION) * BRIGHTNESS;
    fragColor = vec4(mix(c, drained, Strength), 1.0);
}
