#version 150

// Overlay-sphere vertex shader. This mesh is emitted by the BER
// **without** a camera-rotation `mulPose` — it's world-aligned, the same
// sphere fixed in the world at the block's centre. The fragment shader
// uses fresnel against the view-space normal for the silhouette
// gradient + glass shell, and `vObjNormal` (mesh-local, untouched by
// the pose's normal matrix) anchors the holy-number highlight to a
// fixed world direction.

in vec3 Position;
in vec4 Color;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec3 vViewNormal;
out vec3 vViewPos;
out vec3 vObjNormal;

// Tiny outward expansion (in world units) applied along each vertex's
// own surface normal. Both spheres are unit spheres at the same world
// centre and same radius; without this offset every fragment maps to
// the same depth value and LEQUAL passes both, causing FP-precision
// Z-fighting. Shifting the overlay 0.005 blocks further out along its
// surface normal puts the overlay's front HEMISPHERE strictly closer
// to the camera than the fractal sphere's front (so it always passes
// LEQUAL cleanly) and its back hemisphere strictly farther away (so the
// fractal sphere's already-drawn depth wins, hiding the back). No
// visible silhouette difference at this magnitude.
const float OVERLAY_NORMAL_EXPANSION = 0.005;

void main() {
    // Position is camera-relative world (the BER baked the pose matrix
    // into it). Shifting along Normal expands around the sphere's centre
    // because Normal is the mesh-local outward direction on a unit-sphere
    // vertex.
    vec3 expandedPos = Position + Normal * OVERLAY_NORMAL_EXPANSION;
    vec4 viewPos = ModelViewMat * vec4(expandedPos, 1.0);
    gl_Position = ProjMat * viewPos;
    vViewPos    = viewPos.xyz;
    vViewNormal = normalize(mat3(ModelViewMat) * Normal);
    vObjNormal  = normalize(Normal);
}
