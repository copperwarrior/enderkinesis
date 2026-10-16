#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat4 MaskMat;
uniform mat4 PrevMaskMat;

out vec4 vColor;
out vec2 vSurfacePos;
out vec3 vMaskPos;
out vec3 vPrevMaskPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vColor = Color;
    // Position is relative to the zone centre, so the static grain stays fixed to the ocean.
    vSurfacePos = Position.xz;
    vMaskPos = (MaskMat * vec4(Position, 1.0)).xyz;
    vPrevMaskPos = (PrevMaskMat * vec4(Position, 1.0)).xyz;
}
