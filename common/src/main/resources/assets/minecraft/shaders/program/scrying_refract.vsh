#version 150

// Full-screen quad vertex shader. The post-chain machinery binds a unit quad in Position
// space [(0,0)..(InSize)]; we just project it to clip-space and pass texCoord through.

in vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

out vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);
    texCoord = Position.xy / OutSize;
}
