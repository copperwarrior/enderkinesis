#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec3 scene = texture(DiffuseSampler, texCoord).rgb;
    fragColor = vec4(vec3(dot(scene, vec3(0.2126, 0.7152, 0.0722))), 1.0);
}
