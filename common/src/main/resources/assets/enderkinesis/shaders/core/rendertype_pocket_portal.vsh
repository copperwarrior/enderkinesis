#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 texProj0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    // Inline copy of vanilla projection_from_position, as the end portal block uses.
    vec4 projection = gl_Position * 0.5;
    projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = gl_Position.zw;
    texProj0 = projection;

    // rgb: light, ambient occlusion and face shade. a: the visible part of the portal.
    vertexColor = Color;
}
