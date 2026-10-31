#version 150

// Sunfire's Halo: a sphere of light round its wearer. Position arrives turned to the view;
// ViewToWorld turns it back to world axes round the camera, the axes Normal is in: out from the
// Halo's sphere. UV0 is the fragment shader's to read.

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 ViewToWorld;

out vec3 viewPos;
out vec3 outward;
out vec2 along;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    viewPos = ViewToWorld * Position;
    outward = Normal;
    along = UV0;
    vertexColor = Color;
}
