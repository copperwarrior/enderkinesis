#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;
in ivec2 UV2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// The section's world corner, wrapped to 1024 blocks, so Position + WarbleOrigin names the same corner in every section.
uniform vec3 WarbleOrigin;
// Noise-cell time, wrapped to 1024 cells by the caller.
uniform float WarbleTime;
// The section's box minimum relative to the plan anchor.
uniform vec3 FrameOrigin;

out vec2 texCoord0;
out vec4 vertexColor;
out vec3 viewNormal;
out vec3 framePos;
out vec3 modelNormal;
// 1 for a cell drawn as plain graphite: the renderer gives it sky light 14, a coloured cell 15.
flat out float plainCell;

const float WARBLE = 0.03;
const float CYCLE = 1024.0;

vec3 hash3(vec3 p) {
  p = fract(p * vec3(0.1031, 0.1030, 0.0973));
  p += dot(p, p.yxz + 33.33);
  return fract((p.xxy + p.yxx) * p.zyx);
}

void main() {
  // Snapped to 1/16 block, so float noise cannot split a corner that two quads share.
  vec3 corner = mod(floor((Position + WarbleOrigin) * 16.0 + 0.5), 16384.0);
  // Smooth value noise over time; each corner has its own phase, and corners that two quads share agree.
  float t = WarbleTime + hash3(corner * 1.7 + 3.1).x * 64.0;
  float cell = floor(t);
  float f = fract(t);
  f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  vec3 a = hash3(corner + mod(cell, CYCLE) * 7.31);
  vec3 b = hash3(corner + mod(cell + 1.0, CYCLE) * 7.31);
  vec3 boil = (mix(a, b, f) - 0.5) * 2.0 * WARBLE;
  gl_Position = ProjMat * ModelViewMat * vec4(Position + boil, 1.0);
  texCoord0 = UV0;
  vertexColor = Color;
  viewNormal = mat3(ModelViewMat) * Normal;
  framePos = Position + FrameOrigin;
  modelNormal = Normal;
  plainCell = UV2.y < 232 ? 1.0 : 0.0;
}
