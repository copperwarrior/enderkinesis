#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform mat3 IViewRotMat;
uniform int FogShape;
uniform vec3 CameraPos;

out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;
out vec3 worldPos;

void main() {
    vec3 pos = Position + ChunkOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * pos, FogShape);
    vertexColor = Color * minecraft_sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;
    worldPos = pos + CameraPos;
}
