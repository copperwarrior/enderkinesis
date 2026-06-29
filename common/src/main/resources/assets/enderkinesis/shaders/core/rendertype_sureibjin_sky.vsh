#version 150

in vec3 Position;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Inverse of the host-side pose matrix that pre-multiplied Position. Undoing
// it here gives us the WORLD-aligned sphere position to sample noise in —
// without this the camera rotation rides into the noise input and the
// pattern follows the player's view.
uniform mat4 InverseCamera;

out vec3 vWorldPos;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vWorldPos = (InverseCamera * vec4(Position, 1.0)).xyz;
}
