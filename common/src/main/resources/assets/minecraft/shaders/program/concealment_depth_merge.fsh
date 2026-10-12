#version 150

// DiffuseSampler is the depth texture of the main framebuffer. The pass draws into the
// cloak ship framebuffer with a LESS depth test: where the world is nearer than the ship
// pixel, the ship colour becomes transparent and the depth becomes the world depth.

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    gl_FragDepth = texture(DiffuseSampler, texCoord).r;
    fragColor = vec4(0.0);
}
