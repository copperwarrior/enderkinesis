#version 150

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec3 normal;
in float flag;

out vec4 fragColor;

// The clear parts of cut-out textures (plants, torches) drop out here as they do in the colour pass.
// Alpha carries the ground flag that view scenes put in the vertex red; other sketches do not read it.
void main() {
    if (texture(Sampler0, texCoord0).a < 0.1) discard;
    fragColor = vec4(normalize(normal) * 0.5 + 0.5, flag);
}
