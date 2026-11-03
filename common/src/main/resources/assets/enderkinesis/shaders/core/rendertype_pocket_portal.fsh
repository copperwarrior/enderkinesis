#version 150

// Vanilla rendertype_end_portal.fsh. Vertex rgb is the block light; vertex alpha fades it on the Abyssal Pocket walls.

uniform sampler2D Sampler0;  // end_sky.png
uniform sampler2D Sampler1;  // end_portal.png

uniform float GameTime;

in vec4 texProj0;
in vec4 vertexColor;

out vec4 fragColor;

#moj_import <end_portal_layers.glsl>

void main() {
    if (vertexColor.a < 0.004) discard;
    vec3 color = endPortalColor(texProj0);
    fragColor = vec4(color * vertexColor.rgb, vertexColor.a);
}
