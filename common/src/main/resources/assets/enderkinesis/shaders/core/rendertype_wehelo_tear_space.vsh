#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out vec4 texProj0;
out vec4 vertexColor;
out float vertexDistance;
out float viewDepth;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    viewDepth = -view.z;
    vertexDistance = fog_distance(ModelViewMat, Position, FogShape);

    // As rendertype_pocket_portal: vanilla projection_from_position, inlined.
    vec4 projection = gl_Position * 0.5;
    projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = gl_Position.zw;
    texProj0 = projection;

    vertexColor = Color;
}
