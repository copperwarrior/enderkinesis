#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Strength;
uniform float Amplitude;
uniform float Time;

in vec2 texCoord;
out vec4 fragColor;

// One travelling wave of a water surface: d is its direction, k its frequency per screen height, w its speed.
vec2 slope(vec2 p, vec2 d, float k, float w) {
    return d * k * cos(dot(p, d) * k - Time * w);
}

void main() {
    vec2 p = texCoord * vec2(InSize.x / InSize.y, 1.0);
    // The UV offset is the slope of the summed height field, as light bends through a rippled surface.
    vec2 g = slope(p, normalize(vec2(1.0, 0.35)), 19.0, 2.1)
           + slope(p, normalize(vec2(-0.6, 1.0)), 27.0, 2.9) * 0.7
           + slope(p, normalize(vec2(0.2, -1.0)), 37.0, 3.7) * 0.5;
    vec2 c = p - vec2(0.5 * InSize.x / InSize.y, 0.5);
    float r = length(c);
    g += (r > 1e-4 ? c / r : vec2(0.0)) * 31.0 * cos(r * 31.0 - Time * 3.3) * 0.6;
    vec2 uv = clamp(texCoord + g / 60.0 * Amplitude * Strength, vec2(0.0), vec2(1.0));
    fragColor = vec4(texture(DiffuseSampler, uv).rgb, 1.0);
}
