#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
in vec4 TrypLocal;
in vec4 TrypSize;
in vec4 TrypNormal;
in vec4 TrypAxisX;
in vec4 TrypAxisY;
in vec4 TrypAxisZ;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec3 worldNormal;
out vec3 partLocal;
out vec3 camLocal;
flat out vec3 partSize;
flat out vec3 partNormal;
flat out float partId;
flat out mat3 partAxes;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    vertexColor = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    worldNormal = Normal;
    partLocal = TrypLocal.xyz;
    partId = TrypLocal.w;
    partSize = TrypSize.xyz;
    partNormal = TrypNormal.xyz;
    mat3 axes = mat3(TrypAxisX.xyz, TrypAxisY.xyz, TrypAxisZ.xyz);
    partAxes = axes;
    camLocal = vec3(0.0);
    if (partId >= 0.0) {
        // The eye in this part's own block-unit frame, so the fragment shader can march the view ray through it.
        vec3 eye = (inverse(ModelViewMat) * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
        camLocal = inverse(axes) * (eye - (Position - axes * TrypLocal.xyz));
    }
}
