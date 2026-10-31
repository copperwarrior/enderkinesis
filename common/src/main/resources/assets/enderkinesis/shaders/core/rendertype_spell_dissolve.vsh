#version 150

#moj_import <fog.glsl>

// Opaque spell surfaces laid over blocks (snow, ice, the Abyss's mass) that come and go texel
// by texel instead of fading. The vertex colour isn't a tint: red is a seed for which texels go
// first, alpha how much of the surface is there. Lit by the world like particles.

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord0;
out vec4 lightColor;
out float seed;
out float amount;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, Position, FogShape);
    texCoord0 = UV0;
    lightColor = texelFetch(Sampler2, UV2 / 16, 0);
    seed = Color.r;
    amount = Color.a;
}
