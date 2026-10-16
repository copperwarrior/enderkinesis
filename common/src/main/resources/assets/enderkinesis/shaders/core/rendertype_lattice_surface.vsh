#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float vHeight;
out float vAlpha;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // CrepusculiteLatticeOceanVolume.heightMatrix: clip z is the height over the height range, with up as near.
    vHeight = -gl_Position.z;
    vAlpha = Color.a;
}
