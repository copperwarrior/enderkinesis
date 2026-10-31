#version 150

// VolumeProxy unit cube, [0, 1] on each axis.
in vec3 Position;

uniform mat4 ViewProj;
// Volume-local space to camera-relative world space. Rigid, so lengths stay in blocks.
uniform mat4 LocalToView;
uniform vec3 BoxMin;
uniform vec3 BoxMax;

out vec3 localPos;
out vec3 viewPos;

void main() {
    localPos = mix(BoxMin, BoxMax, Position);
    viewPos = (LocalToView * vec4(localPos, 1.0)).xyz;
    gl_Position = ViewProj * vec4(viewPos, 1.0);
}
