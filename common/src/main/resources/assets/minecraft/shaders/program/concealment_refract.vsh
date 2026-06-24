#version 150

// Full-screen quad. Same layout as scrying_refract.vsh — the post-chain machinery
// binds a unit quad in [(0,0)..(InSize)] Position space.

in vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

out vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);
    texCoord = Position.xy / OutSize;
}
