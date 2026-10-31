#version 150

// Black Hole Sun: a cube round it, marched through (as the cloaks are). Position arrives turned
// to the view; ViewToWorld turns it back to world axes round the camera, and SunCentre (its
// middle, from the camera) makes that where on the cube this is, from its middle. UV0 carries
// its black core's radius and its fire's outer radius.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 ViewToWorld;
uniform vec3 SunCentre;

out vec3 viewPos;
out vec3 outward;
out vec2 radii;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    viewPos = ViewToWorld * Position;
    outward = viewPos - SunCentre;
    radii = UV0;
    vertexColor = Color;
}
