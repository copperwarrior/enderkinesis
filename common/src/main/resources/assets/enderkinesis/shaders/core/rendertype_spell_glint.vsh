#version 150

#moj_import <fog.glsl>

// Vanilla glint vertex shader, plus the vertex colour (the spell colour).

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform mat4 TextureMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = Color;
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
}
