#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vColor;
out vec2 vOffset;
out float vRim;

void main() {
    // Position is the halo center for all 4 vertices. UV0 is the corner offset in view units.
    gl_Position = ProjMat * ModelViewMat * vec4(Position + vec3(UV0, 0.0), 1.0);
    vColor = Color;
    vOffset = UV0;
    vRim = abs(UV0.x);
}
