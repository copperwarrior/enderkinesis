#version 150

// The tear rims, merged with MAX in their own target, added onto the scene.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(DiffuseSampler, texCoord).rgb, 0.0);
}
