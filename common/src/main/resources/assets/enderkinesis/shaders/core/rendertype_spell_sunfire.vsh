#version 150

// A Sunfire beam's shell of light. Position arrives turned to the view; ViewToWorld turns it
// back to world axes round the camera, the axes Normal (out from the beam's axis) is in. UV0 is
// (distance along the beam, the beam's length) in blocks.

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
