#version 150

// Halo fragment shader. Renders an additive bloom AROUND the orb's
// visible silhouette — fragments where the camera ray would actually
// hit the inner orb are discarded (the opaque FRACTAL_SPHERE render
// type handles those pixels).
//
// The halo mesh is a sphere of radius 2.2 (world coords) centred on
// the same block as the orb mesh (radius 1.5). For each halo
// fragment, we compute the perpendicular distance from the camera
// ray to the orb centre; that distance tells us where this fragment
// sits relative to the orb's projected silhouette:
//   - perpDist < orb radius        → ray hits the orb → discard
//   - perpDist == orb radius       → fragment is right at silhouette → max bloom
//   - perpDist == halo radius      → fragment is at the outer mesh edge → no bloom
//
// Color gradient is WHITE at the thin-edge layer (closest to the
// orb's silhouette) and fades to yellow further out — the user wanted
// the brightest bloom layer to read as white-hot.

in vec3 vViewPos;
in vec3 vObjNormal;

uniform mat3 ViewToWorld;
uniform mat3 MeshToWorld;

out vec4 fragColor;

const float ORB_RADIUS  = 1.5;
const float HALO_RADIUS = 2.0;

void main() {
    // Rotate view-space fragment position back into world frame
    // (camera-relative). Same `ViewToWorld` uniform the fractal shader
    // uses — set by the BER from camera pitch/yaw.
    vec3 worldCamRel = ViewToWorld * vViewPos;

    // For rotated VS-Body orbs the mesh axes don't match world axes,
    // so combine `vObjNormal` (mesh frame) with `worldCamRel` (world
    // frame) is wrong as-is. Rotate world->mesh and do everything that
    // touches `vObjNormal` in the mesh frame. The sphere-vs-ray
    // distance is rotationally invariant, so the silhouette test still
    // produces identical results.
    mat3 WorldToMesh = transpose(MeshToWorld);
    vec3 meshCamRel  = WorldToMesh * worldCamRel;
    vec3 rdMesh      = normalize(meshCamRel);

    // NOTE: no `dot(rdWorld, vObjNormal) >= 0.0` back-face discard.
    // When the camera enters the halo sphere, EVERY visible halo
    // fragment has vObjNormal and rdWorld both pointing outward (dot
    // > 0), so that discard killed the whole bloom whenever the
    // player walked into the orb's vicinity. The perpDist test below
    // is the real silhouette gate — the back-face discard was just a
    // duplicate of GL's cull and didn't survive going inside.

    // Camera position in MESH-LOCAL block-centred coords. Halo mesh
    // fragment lives at `vObjNormal * HALO_RADIUS` (mesh frame), the
    // block centre sits at the mesh origin, so the camera in mesh
    // frame is `vObjNormal * R - meshCamRel`.
    vec3 cameraMesh = vObjNormal * HALO_RADIUS - meshCamRel;

    // Perpendicular distance from the orb centre (origin in mesh
    // frame) to the camera ray. Computed in mesh frame so the math
    // stays consistent with `vObjNormal`; the sphere is rotationally
    // symmetric so the value matches the world-frame equivalent.
    float dotCR = dot(cameraMesh, rdMesh);
    float perpSq = dot(cameraMesh, cameraMesh) - dotCR * dotCR;
    float perpDist = sqrt(max(perpSq, 0.0));

    // If the ray would intersect the inner orb, this pixel is covered
    // by the orb's opaque render — don't double-render a halo on top.
    if (perpDist < ORB_RADIUS) discard;

    // Bloom intensity: 1 at the orb silhouette (perpDist = ORB_RADIUS),
    // 0 at the outer halo extent.
    float intensity = 1.0 - smoothstep(ORB_RADIUS, HALO_RADIUS, perpDist);

    // Color gradient: a VERY thin white layer right at the orb
    // silhouette, then yellow filling the rest of the bloom band.
    // `pow(intensity, 16.0)` keeps the white-mix near zero until the
    // last few percent before the silhouette, so the white reads as
    // a sharp rim that fuses with the orb's own white fresnel.
    float whiteness = pow(intensity, 16.0);
    vec3 yellow = vec3(1.0, 0.85, 0.35);
    vec3 white  = vec3(1.0);
    vec3 bloomColor = mix(yellow, white, whiteness);

    // Additive blend: output goes directly added to the framebuffer.
    // ×0.15 keeps the bloom subtle — even modest values saturate to
    // white under additive blending against bright backgrounds.
    fragColor = vec4(bloomColor * intensity * 0.15, intensity);
}
