#version 150

// Halo vertex shader — passes through the view-space position and the
// world-frame surface normal so the fragment shader can compute the
// camera-ray geometry for the bloom-vs-orb-silhouette test.

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vViewPos;
out vec3 vObjNormal;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vViewPos    = Position;
    vObjNormal  = normalize(Normal);
}
