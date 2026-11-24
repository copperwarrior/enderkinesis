#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
// 0: lit colour, alpha = fade; 1: view-space normals as strike_sketch reads them, ground flag 0;
// 2: position relative to the plan anchor, scaled to 0..1 over 512 blocks, alpha = (face axis 1 to 3, plus 4 for plain graphite) / 8.
uniform float Mode;

in vec2 texCoord0;
in vec4 vertexColor;
in vec3 viewNormal;
in vec3 framePos;
in vec3 modelNormal;
flat in float plainCell;

out vec4 fragColor;

// Same as strike_sketch.fsh, which divides these lights out again to find the albedo.
const vec3 ITEM_LIGHT0 = vec3(-0.22252, 0.17150, 0.95973);
const vec3 ITEM_LIGHT1 = vec3(-0.21501, 0.97183, 0.09657);

void main() {
  vec4 tex = texture(Sampler0, texCoord0);
  if (tex.a < 0.1) discard;
  vec3 n = normalize(viewNormal);
  if (Mode > 1.5) {
    vec3 a = abs(modelNormal);
    float axis = a.x >= a.y && a.x >= a.z ? 1.0 : (a.y >= a.z ? 2.0 : 3.0);
    fragColor = vec4(clamp((framePos + 256.0) / 512.0, 0.0, 1.0), (axis + 4.0 * plainCell) / 8.0);
    return;
  }
  if (Mode > 0.5) {
    fragColor = vec4(n * 0.5 + 0.5, 0.0);
    return;
  }
  float light = min(1.0, (max(0.0, dot(ITEM_LIGHT0, n)) + max(0.0, dot(ITEM_LIGHT1, n))) * 0.6 + 0.4);
  fragColor = vec4(tex.rgb * vertexColor.rgb * light, ColorModulator.a);
}
