#version 150

// The elements' cloaks: a cylinder round their wearer, marched through. Position arrives turned
// to the view; ViewToWorld turns it back to world axes round the camera, and CloakFeet (the
// wearer's feet, from the camera) makes that where on the cylinder this is, from the feet. It
// can't ride in Normal, as the Halo's directions do: normals are bytes, clamped to a block.
// UV0 is the fragment shader's to read.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 ViewToWorld;
uniform vec3 CloakFeet;

out vec3 viewPos;
out vec3 outward;
out vec2 along;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    viewPos = ViewToWorld * Position;
    outward = viewPos - CloakFeet;
    along = UV0;
    vertexColor = Color;
}
