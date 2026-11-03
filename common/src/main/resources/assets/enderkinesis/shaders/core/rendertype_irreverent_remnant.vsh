#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 surfaceNormal;
out vec4 lightColour;
out vec2 faceCoord;
out float noiseSeed;
out float alpha;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // World-space normal, so the light stays put as the camera turns.
    surfaceNormal = Normal;
    // Vanilla's minecraft_sample_lightmap, as entities read it.
    lightColour = texture(Sampler2, clamp(UV2 / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    faceCoord = UV0;
    // Noise seed and opacity ride in the colour attribute.
    noiseSeed = Color.r;
    alpha = Color.a;
}
