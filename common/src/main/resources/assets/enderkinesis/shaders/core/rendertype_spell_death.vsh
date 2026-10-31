#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

// Vanilla's translucent entity shader, for a mob dying a spell's way. Its vertex colour isn't a
// tint: red is the death's style, green how far into it (half-ticks), blue the height on the body
// (0 a quarter of its height below the feet, 1 a quarter above its top), alpha its opacity. The directional light is worked out on its
// own, from white.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 shade;
out vec4 death;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape);
    shade = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, vec4(1.0));
    death = Color;
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
}
