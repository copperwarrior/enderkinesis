#version 150

// Fractal-sphere vertex shader. `Position` is mesh-local (a baked VBO);
// `ModelViewMat` carries the orb's camera-relative pose.
//
//   - `vObjNormal`: mesh-local surface normal. For a world-aligned
//     unit sphere, the surface normal IS the mesh-local position
//     direction. Equal to the WORLD-space normal because the BER emits
//     normals without applying any matrix.
//   - `vViewPos`: view-space fragment position (camera at origin,
//     looking down -Z). The fragment shader rotates this into world
//     using the `ViewToWorld` uniform when it needs a world-frame ray.
//   - `vBlockSeed`: per-block hash from the Color attribute.

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vObjNormal;
out vec3 vViewPos;
out vec3 vBlockSeed;
// Per-projector fractal selector. BER packs the BlockState's
// `fractal_type` integer into Color.a; MC normalises the byte to
// [0, 1] when uploading. The fragment shader recovers the integer via
// `int(vFractalType * 255 + 0.5)` and dispatches into one of its
// `mapXxx()` functions.
out float vFractalType;

void main() {
    gl_Position  = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vObjNormal   = normalize(Normal);
    vViewPos     = (ModelViewMat * vec4(Position, 1.0)).xyz;
    vBlockSeed   = Color.rgb;
    vFractalType = Color.a;
}
