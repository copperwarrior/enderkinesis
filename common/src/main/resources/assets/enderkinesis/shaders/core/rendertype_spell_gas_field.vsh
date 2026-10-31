#version 150

// Poison gas field: its box. Position arrives turned to the view; ViewToWorld turns it
// back to world axes round the camera, where the fragment shader marches through the cloud.
// viewRay keeps the view-space direction, to find how far the scene behind it is.

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 ViewToWorld;

out vec3 viewPos;
out vec3 viewRay;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    viewPos = ViewToWorld * Position;
    viewRay = (ModelViewMat * vec4(Position, 1.0)).xyz;
}
