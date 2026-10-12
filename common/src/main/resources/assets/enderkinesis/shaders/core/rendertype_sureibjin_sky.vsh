#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Position is world-aligned and the camera pose is in ModelViewMat, so the host sends identity here.
uniform mat4 InverseCamera;

out vec3 vWorldPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vWorldPos = (InverseCamera * vec4(Position, 1.0)).xyz;
}
