#version 150

// =====================================================================
// Adapted from Shadertoy "Fractal Land" — https://www.shadertoy.com/view/MtX3Ws
// Original by S. Guillitte, 2015.
// Licensed CC-BY-NC-SA 3.0 Unported (Creative Commons Attribution-
// NonCommercial-ShareAlike). This file is the same license; the
// adaptation only substitutes Shadertoy globals for the MC core-shader
// equivalents (iTime → GlobalTime, iResolution → ScreenSize, iMouse
// dropped, iChannel0 replaced with a FogColor-based procedural sky).
// =====================================================================

in vec3 vObjNormal;
in vec3 vViewPos;
in vec3 vBlockSeed;
// Per-projector fractal selector — vertex shader packs it from
// Color.a (BER → BlockState's fractal_type). Same value at every
// fragment of one orb, so the dispatcher branch below is uniform
// control flow.
in float vFractalType;

uniform float GlobalTime;
uniform vec2  ScreenSize;
uniform vec4  FogColor;
uniform mat4  ModelViewMat;
// view→world rotation. BER pushes `camera.rotation()` per frame.
// `Position` (and hence `vViewPos`) comes out of MC's BE pose-stack
// bake in view space — we use this to rotate it back into world space
// so the discard and the raymarch can work in a single coherent frame
// alongside the world-space `vObjNormal`.
uniform mat3  ViewToWorld;
// mesh->world rotation. Identity when the orb is rendered for an
// axis-aligned block (the legacy BER path); the body renderer pushes
// the body's rotation matrix here so the cubic fractal structure
// rotates with the orb while the cubemap reflection + sun specular
// stay anchored to the real world. WorldToMesh is the transpose.
uniform mat3  MeshToWorld;

// 6-face Panoramica cubemap bound by the render type. Indices map to
// world directions: 0 = south (+Z), 1 = west (-X), 2 = north (-Z),
// 3 = east (+X), 4 = up (+Y), 5 = down (-Y). The `sampleCubemap(dir)`
// helper below picks the right face from a direction and computes the
// face-local UV.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
// Sampler6 = vanilla minecraft:textures/font/ascii_sga.png - same
// SGA glyph map MC binds for the minecraft:alt font. Sampled per
// letter in proceduralGlyphs so the words rendered into the cubemap
// match Sselith spellings exactly.
uniform sampler2D Sampler6;

out vec4 fragColor;

// File-scope mesh-frame view direction from the orb origin TOWARD
// the camera. Set in `main()` before the raymarch; read by map
// functions that need camera-relative orientation (the eye map's
// "face the camera" projection).
vec3 g_meshCamDir = vec3(0.0, 0.0, 1.0);

// File-scope un-yawed sample position. The raymarch applies the
// per-projector yaw rotation to each sample before calling `map()`
// — that's what makes the fractal patterns precess inside a
// stationary orb. For maps that need a STABLE coordinate frame
// (the eye's iris pattern shouldn't precess), the raymarch stashes
// the pre-yaw sample here and the map reads from this instead of
// its `p` argument. */
vec3 g_meshSamplePos = vec3(0.0);

// Current frame's yaw value (the one passed to `raymarch()`). Set
// in `main()` so map functions can analytically undo the yaw
// rotation on their `p` argument when they need a yaw-stable frame.
// Backstop in case `g_meshSamplePos` writes aren't being captured
// by the compiler for some optimizer reason.
float g_yaw = 0.0;

// ---------------------------------------------------------------------
//   Helpers from the original.
// ---------------------------------------------------------------------

const float zoom = 1.0;

vec2 cmul(vec2 a, vec2 b) { return vec2(a.x*b.x - a.y*b.y, a.x*b.y + a.y*b.x); }
vec2 csqr(vec2 a)         { return vec2(a.x*a.x - a.y*a.y, 2.0*a.x*a.y); }

// Quaternion squaring. q = a + bi + cj + dk represented as (a, b, c, d).
//   q² = (a² - b² - c² - d²) + 2a·(bi + cj + dk)
// Used by the 3D Julia fractal — iterating `z = z² + J` with z and J
// as quaternions, projected to 3D via the w=0 slice for rendering.
vec4 qsqr(vec4 q) {
    return vec4(
        q.x*q.x - q.y*q.y - q.z*q.z - q.w*q.w,
        2.0 * q.x * q.y,
        2.0 * q.x * q.z,
        2.0 * q.x * q.w
    );
}

mat2 rot(float a) {
    return mat2(cos(a), sin(a), -sin(a), cos(a));
}

// Hash-based pseudo-random value at a 3D grid point. Used by the
// value-noise function below; sin() trick is the classic GLSL hash
// (precision warnings notwithstanding — output range fits comfortably
// inside float resolution at the scales we sample).
float h3(vec3 p) {
    return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
}

// 3D value noise — hashed grid-point values, smoothly interpolated
// with smoothstep weighting. Output in [0, 1].
float noise3(in vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = h3(i + vec3(0.0, 0.0, 0.0));
    float n100 = h3(i + vec3(1.0, 0.0, 0.0));
    float n010 = h3(i + vec3(0.0, 1.0, 0.0));
    float n110 = h3(i + vec3(1.0, 1.0, 0.0));
    float n001 = h3(i + vec3(0.0, 0.0, 1.0));
    float n101 = h3(i + vec3(1.0, 0.0, 1.0));
    float n011 = h3(i + vec3(0.0, 1.0, 1.0));
    float n111 = h3(i + vec3(1.0, 1.0, 1.0));
    return mix(
        mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
        mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y),
        f.z
    );
}

// Fractal Brownian motion — sum of `noise3` at increasing frequencies
// with halving amplitudes. Standard turbulence for cloudy / wispy
// textures. Output in [0, ~1].
float fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; ++i) {
        v += a * noise3(p);
        p *= 2.03;  // slightly off-2 to avoid grid alignment
        a *= 0.5;
    }
    return v;
}

// Houdini-style "lattice warp" FBM: each octave's noise output
// accumulates into a running warp vector that displaces the sample
// position of EVERY subsequent octave. Per the SideFX docs the warp
// "accumulates for each iteration of added fractal noise."
//
// CRITICAL DETAIL: the warp source is PER-LATTICE-CELL DISCRETE —
// `h3(floor(s))` instead of `noise3(s)`. This is what produces the
// visible sharp edges that distinguish lattice warp from regular
// smooth-warped noise. The displacement is piecewise-constant
// within each integer cell, so at cell boundaries the noise sample
// "teleports" to a different point in the field — creating C0
// discontinuities (sharp edges) aligned with the lattice grid.
// Accumulating across octaves spreads those discontinuities across
// scales for the streaky / smudgy / wiry multi-scale look.
float latticeWarpFBM(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    vec3 warp = vec3(0.0);
    vec3 q = p;
    for (int i = 0; i < 5; ++i) {
        vec3 s = q + warp;
        float n = noise3(s);
        v += a * n;
        // Per-cell discrete warp via `h3(floor(...))` — piecewise-
        // constant within each integer cell, jumps at cell
        // boundaries. The lattice-aligned discontinuities are the
        // sharp edges the docs describe.
        vec3 cell = floor(s);
        vec3 warpInc = vec3(
            h3(cell + vec3(7.7,  0.0,  0.0)),
            h3(cell + vec3(0.0,  13.3, 0.0)),
            h3(cell + vec3(0.0,  0.0,  19.7))
        ) - 0.5;
        warp += warpInc * a * 2.5;
        q *= 2.03;
        a *= 0.5;
    }
    return v;
}

// Forward declaration — `worleyF1F2` is defined further down (after
// the Worley feature-point helper) but needed here by the lattice-
// warp variant. GLSL requires declaration before use; the body
// resolves to the definition at link time.
vec2 worleyF1F2(vec3 p);

// Lattice-warp variant where each octave samples Worley F1 instead
// of value noise. The accumulating warp produces a stringy network
// of cell distortion that's distinct from both plain Worley and
// from the value-noise lattice warp.
float latticeWarpWorleyFBM(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    vec3 warp = vec3(0.0);
    vec3 q = p;
    for (int i = 0; i < 4; ++i) {
        vec3 s = q + warp;
        vec2 ff = worleyF1F2(s);
        float n = pow(max(0.0, 1.0 - ff.x), 1.6);
        v += a * n;
        // Per-cell discrete warp — same sharp-edge trick as the
        // value-noise variant. h3 hashed at integer cell coords.
        vec3 cell = floor(s);
        vec3 warpInc = vec3(
            h3(cell + vec3(7.7,  0.0,  0.0)),
            h3(cell + vec3(0.0,  13.3, 0.0)),
            h3(cell + vec3(0.0,  0.0,  19.7))
        ) - 0.5;
        warp += warpInc * a * 2.5;
        q *= 2.03;
        a *= 0.5;
    }
    return v;
}

// Ridged fractal noise — same FBM structure but the per-octave
// contribution is `1 - |noise - 0.5| · 2`, which peaks at the noise's
// half-value and valleys at extremes. Stacked octaves produce sharp
// branching ridge-and-valley structures characteristic of erosion
// patterns, lightning, and (with domain warping) tendrils.
float ridgedFBM(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; ++i) {
        v += a * (1.0 - abs(noise3(p) - 0.5) * 2.0);
        p *= 2.03;
        a *= 0.5;
    }
    return v;
}

// Tendril noise. Domain warping (Iñigo Quilez's "warped noise") sends
// the input position through an FBM offset before sampling the actual
// ridged FBM — this bends the otherwise-straight ridges into curving,
// branching tendril shapes. Output in [0, ~1]; the highest values
// trace thin filamentary structures through 3-space.
float tendrilNoise(vec3 p) {
    vec3 warp = vec3(
        fbm(p + vec3(0.0, 1.7, 9.2)),
        fbm(p + vec3(8.3, 2.8, 2.4)),
        fbm(p + vec3(5.1, 1.3, 4.6))
    );
    return ridgedFBM(p + 0.8 * warp);
}

// Worley (cellular / Voronoi) noise feature point in a given cell.
// Hashes the integer cell coords to a random offset within the cell.
vec3 worleyFeature(vec3 cell) {
    return cell + vec3(
        h3(cell + vec3(1.7, 9.2, 3.1)),
        h3(cell + vec3(8.3, 2.8, 5.4)),
        h3(cell + vec3(5.1, 1.3, 7.8))
    );
}

// Worley noise: returns the two nearest feature-point distances (F1,
// F2) in a 3×3×3 neighbourhood of cells around `p`. F1 is the classic
// Voronoi distance; F2 - F1 is small near cell boundaries (where two
// feature points are equidistant), so subtracting them gives sharp
// thin LINES along the Voronoi cell edges — that's the tendril shape.
vec2 worleyF1F2(vec3 p) {
    vec3 cell = floor(p);
    float f1 = 1e10;
    float f2 = 1e10;
    for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                vec3 ncell = cell + vec3(dx, dy, dz);
                vec3 feat = worleyFeature(ncell);
                float d = length(feat - p);
                if (d < f1) { f2 = f1; f1 = d; }
                else if (d < f2) { f2 = d; }
            }
        }
    }
    return vec2(f1, f2);
}

// Flow-noise variant of [worleyFeature]: each cell's feature point
// orbits its cell centre at a fixed per-cell angular velocity. Uses
// the same `h3` hash as the base Worley, so the per-cell base offset
// and rotation rate are derived from the same primitive — no
// separate noise system. The orbit axis is world-Y; per-cell axes
// would produce angular discontinuities at cell boundaries that
// pop visibly.
vec3 worleyFeatureFlow(vec3 cell, float time) {
    vec3 baseFeat = worleyFeature(cell);
    // Rotate `baseFeat - cellCentre` around world-Y by an angle that
    // increases linearly with time at a per-cell rate.
    vec3 cellCenter = cell + vec3(0.5);
    vec3 r = baseFeat - cellCenter;
    float omega = (h3(cell + vec3(17.0, 31.0, 91.0)) - 0.5) * 2.0; // [-1, 1] rad/s
    float ang = time * omega;
    float c = cos(ang); float s = sin(ang);
    vec3 rotated = vec3(c * r.x - s * r.z, r.y, s * r.x + c * r.z);
    return cellCenter + rotated;
}

vec2 worleyF1F2Flow(vec3 p, float time) {
    vec3 cell = floor(p);
    float f1 = 1e10;
    float f2 = 1e10;
    for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                vec3 ncell = cell + vec3(dx, dy, dz);
                vec3 feat = worleyFeatureFlow(ncell, time);
                float d = length(feat - p);
                if (d < f1) { f2 = f1; f1 = d; }
                else if (d < f2) { f2 = d; }
            }
        }
    }
    return vec2(f1, f2);
}

vec2 iSphere(in vec3 ro, in vec3 rd, in vec4 sph) {
    vec3 oc = ro - sph.xyz;
    float b = dot(oc, rd);
    float c = dot(oc, oc) - sph.w * sph.w;
    float h = b*b - c;
    if (h < 0.0) return vec2(-1.0);
    h = sqrt(h);
    return vec2(-b - h, -b + h);
}

// ---------------------------------------------------------------------
//   Fractal density functions.
//
// Each `mapXxx(p)` returns a per-point scalar density. The raymarch
// (and the DDA) accumulates `exp(-k·|orbit_trap|)` per iteration —
// the visible structure is wherever orbits linger near the trap
// surface. To add a new fractal: write `mapNew(p)` here, add a branch
// to the dispatcher at the bottom, and bump the modulus in the
// dispatcher so the per-block selector covers it.
// ---------------------------------------------------------------------

// Precession in this codebase = parameter morphing. Each fractal has
// characteristic constants in its iteration (Guillitte's 0.7 fold
// scalar, Menger's scale factor S, Worley's cell scale, etc.) whose
// values control the fractal's visible physical shape. Slowly
// drifting those constants over time produces a "breathing" or
// "morphing" effect, materially changing the structure rather than
// just rotating an unchanged structure through space. Each `mapXxx()`
// applies its own morphing inline since the parameter being modulated
// is fractal-specific.

// 1) Guillitte's hybrid IFS — fold + sphere-invert + complex-square +
// cycled axes. CC-BY-NC-SA 3.0 (see the file header for attribution).
// Cosmic-filament look with 3-fold symmetry from the axis cycle.
// Brightness tuned via the orbit-trap coefficient (15 vs the original
// 19 → wider density peaks per orbit, more total light through) and
// the final divisor (1.4 vs 2.0).
float mapGuillitte(in vec3 p) {
    // Time-varying pitch/roll/yaw rotation to dissolve the OCTANT
    // ALIGNMENT. Guillitte's `abs(p)` folds all 8 octants identically,
    // so the canonical fractal has perfect axis-plane mirror symmetry
    // — visible as octant-aligned discontinuities. A static rotation
    // just *moves* the discontinuities to different angles; what
    // actually hides them is continuous motion. Three rotations at
    // different rates keep the octant planes constantly sweeping,
    // so they never settle into a visible position.
    {
        float pitch = 0.25 * sin(GlobalTime * 0.07);
        float roll  = 0.25 * cos(GlobalTime * 0.05);
        float yaw   = 0.20 * sin(GlobalTime * 0.06);
        float cp = cos(pitch), sp = sin(pitch);
        float cr = cos(roll),  sr = sin(roll);
        float cy = cos(yaw),   sy = sin(yaw);
        vec2 yz = mat2(cp, sp, -sp, cp) * p.yz; p.y = yz.x; p.z = yz.y;
        vec2 xy = mat2(cr, sr, -sr, cr) * p.xy; p.x = xy.x; p.y = xy.y;
        vec2 xz = mat2(cy, sy, -sy, cy) * p.xz; p.x = xz.x; p.z = xz.y;
    }
    // Parameter-morphing precession: drift the fold scalar `k` over
    // time. At k=0.7 (the default) we get the canonical Shadertoy
    // shape; the ±0.08 drift visibly reshapes the basin / filament
    // structure. Rate 0.20 rad/s → ~31s per full morph cycle, fast
    // enough to actually notice the change.
    float k = 0.7 + 0.08 * sin(GlobalTime * 0.20);
    float res = 0.0;
    vec3 c = p;
    for (int i = 0; i < 10; ++i) {
        p = k * abs(p) / dot(p, p) - k;
        p.yz = csqr(p.yz);
        p = p.zxy;
        res += exp(-24.0 * abs(dot(p, c)));
    }
    return res / 1.8;
}

// 2) Menger-sponge fold (Karl Menger, 1926). Cubic axis-aligned
// crystalline look. Each iteration:
//   - mirror everything into the +X+Y+Z octant via abs()
//   - sort the axes so |x| >= |y| >= |z| (3-comparison sorting network)
//   - scale by 3 and offset to repeat the void cross
//
// Orbit trap is `length(p)` (rotation-invariant), NOT `|dot(p, c)|`.
// The dot-product trap that works so well for Guillitte produces
// stretching artefacts here because the sort step permutes p's axes
// while c stays in the original orientation — `dot(p, c)` reads
// different values for inputs aligned with different world axes
// even when the underlying geometry is rotationally equivalent.
// `length(p)` measures orbit magnitude only and is invariant under
// the sort, so the visible structure inherits the Menger's natural
// cubic symmetry rather than fighting it.
// Toggle: 1 = SDF (uniform bright cube body, clean boundary,
// but tends to produce blue concentric rings from the c³ blue
// channel × high-density SDF), 0 = orbit-trap raymarch
// (corner-bright + gradient interior, lower c values, stays
// yellow). Change this one digit to flip between styles.
#define MENGER_USE_SDF 0

float mapMenger(in vec3 p) {
    // Preceding rotation layer: pitch (around X) + roll (around Z),
    // both slowly oscillating. The cube tilts and tips over time;
    // because both rotations are small-amplitude and out of phase,
    // the orb's visible cube faces are continuously sweeping
    // through orientations rather than staying locked to world axes.
    float pitch = 0.30 * sin(GlobalTime * 0.07);
    float roll  = 0.30 * cos(GlobalTime * 0.05);
    float cp = cos(pitch), sp = sin(pitch);
    float cr = cos(roll), sr = sin(roll);
    // Pitch: rotate Y-Z plane
    vec2 yz = mat2(cp, sp, -sp, cp) * p.yz;
    p.y = yz.x; p.z = yz.y;
    // Roll: rotate X-Y plane
    vec2 xy = mat2(cr, sr, -sr, cr) * p.xy;
    p.x = xy.x; p.y = xy.y;

    vec3 pInput = p;  // preserve for background-noise sampling
#if MENGER_USE_SDF
    // SDF: track cumulative scale `s` through the fold, then return
    // signed distance from the final iterate to the unit cube,
    // divided by `s` to undo the zoom. Inside the structure: sdf < 0.
    // Outside: sdf > 0.
    //
    // The fold is FULLY SYMMETRIC — all three axes get the same
    // scale/translate AND the same conditional `+= 2.0` fold. The
    // classical Menger sponge fold catches only Z (smallest axis
    // after sort), producing the cross-shaped voids that are
    // characteristic of Menger — but the Z-only treatment means the
    // structure has different behaviour on the Z axis vs the X/Y
    // axes. Under precession (which rotates the input through all
    // orientations), this asymmetry mapped onto changing world
    // directions and produced the "half-dark" cube. Folding all
    // three axes gives a related but rotationally symmetric Cantor-
    // cube fractal that handles rotation cleanly.
    // Parameter-morphing precession: drift the scale factor `S` over
    // time. At S=3.0 we get the canonical Menger; small drift varies
    // the relative sizes of the cube and its sub-voids, slowly
    // reshaping the visible structure.
    float S = 3.0 + 0.15 * sin(GlobalTime * 0.04);
    // Iteration count reduced from 6 → 3. The deeper recursion was
    // producing visible "concentric ring" projections of the
    // multi-level cross-void structure; 3 levels give a cleaner
    // cube with one or two layers of sub-detail.
    float s = 1.0;
    for (int i = 0; i < 3; ++i) {
        p = abs(p);
        if (p.x < p.y) p.xy = p.yx;
        if (p.x < p.z) p.xz = p.zx;
        if (p.y < p.z) p.yz = p.zy;
        p = p * S - (S - 1.0);
        if (p.x < -1.0) p.x += S - 1.0;
        if (p.y < -1.0) p.y += S - 1.0;
        if (p.z < -1.0) p.z += S - 1.0;
        s *= S;
    }
    vec3 d = abs(p) - 1.0;
    float boxDist = length(max(d, 0.0)) + min(max(d.x, max(d.y, d.z)), 0.0);
    float sdf = boxDist / s;
    // TIGHT smoothstep transition (-0.005 → 0.02) for a crisp cube
    // boundary — previously the boundary was a wide gradient, which
    // read as the whole thing being blurry.
    float cubeDensity = 3.0 * (1.0 - smoothstep(-0.005, 0.02, sdf));
    // BACKGROUND FILL: where the cube is empty, show the same FBM
    // noise the hypertexture uses — turns the "distant black" regions
    // into a soft cloudy halo around the cube. Same noise machinery
    // as the hypertexture, just less density (smaller multiplier).
    vec3 noisePos = pInput * 1.5 + vec3(0.0, GlobalTime * 0.08, 0.0);
    float n = fbm(noisePos);
    float shape = max(0.0, 1.0 - length(pInput) / 2.0);
    float bgNoise = pow(max(0.0, n - 0.45), 2.5) * shape * 6.0;
    return max(cubeDensity, bgNoise);
#else
    // ORBIT-TRAP RAYMARCH using ISOTROPIC `length(p)` trap.
    //
    // The previous `abs(dot(p, c))` trap measured alignment between
    // the iterate and the ORIGINAL sample position — a c-dependent
    // quantity that's NOT rotationally symmetric. Along world axes,
    // c is dominantly axis-aligned, so the trap accumulates strongly
    // for entire screen-space slabs near the cube's faces and corners.
    // Those slabs are what stretched out to the orb silhouette — they
    // are intrinsic to the trap, not to the envelope, which is why
    // envelope adjustments could only hide the symptom.
    //
    // `length(p)` measures how far the iterate has wandered from the
    // origin of the FOLDED space. The cube fold pulls points inside
    // the Menger set toward origin and pushes points in the voids
    // outward. So `exp(-length(p))` is large for points IN the Menger
    // structure and small in the voids — a true set-membership density
    // that's symmetric under the full cube symmetry group.
    float S = 3.0 + 0.15 * sin(GlobalTime * 0.04);
    float res = 0.0;
    for (int i = 0; i < 4; ++i) {
        p = abs(p);
        if (p.x < p.y) p.xy = p.yx;
        if (p.x < p.z) p.xz = p.zx;
        if (p.y < p.z) p.yz = p.zy;
        p = p * S - (S - 1.0);
        if (p.x < -1.0) p.x += S - 1.0;
        if (p.y < -1.0) p.y += S - 1.0;
        if (p.z < -1.0) p.z += S - 1.0;
        res += exp(-1.5 * length(p));
    }
    // Sphere envelope at the orb boundary. Cubic STRUCTURE comes from
    // the isotropic trap above; this just blends the structure off at
    // the orb's surface so the lattice fades to cubemap reflection.
    float r = length(pInput);
    float shapeMask = 1.0 - smoothstep(1.2, 1.45, r);
    float cubeDensity = (res / 2.0) * shapeMask;
    // Background noise fill (same as SDF branch — turns distant
    // empty regions into soft cloudy halo).
    vec3 noisePos = pInput * 1.5 + vec3(0.0, GlobalTime * 0.08, 0.0);
    float n = fbm(noisePos);
    float shape = max(0.0, 1.0 - length(pInput) / 2.0);
    float bgNoise = pow(max(0.0, n - 0.45), 2.5) * shape * 6.0;
    return max(cubeDensity, bgNoise);
#endif
}

// 3) Low-density hypertexture. Ken Perlin / Eric Hoffert 1989 style —
// a soft volumetric medium built from procedural 3D noise rather than
// an iterated fractal. Composition:
//
//   shape(p)     — soft spherical envelope, full at the centre,
//                  fading to 0 by the orb's silhouette
//   turbulence(p) — FBM (fractal Brownian motion) of value noise,
//                  the wispy / cloudy detail layer
//   density      — `pow(max(0, turbulence - threshold), exponent)` so
//                  most positions return 0 (empty space) and only
//                  noise PEAKS contribute (the "low density" part —
//                  sparse wisps, not uniform haze)
//
// Time-driven drift of the noise field gives slow morphing without
// requiring the fractal iteration's precession trick. The drift
// vector is along (0, 1, 0) so the wisps rise upward over time.
float mapHypertexture(in vec3 p) {
    // Parameter-morphing precession: drift the Worley cell density.
    // At cellScale=1.5 we get the default tendril spacing; the ±0.2
    // drift varies cell sizes over time so the visible tendril
    // network slowly stretches and contracts.
    float cellScale = 1.5 + 0.20 * sin(GlobalTime * 0.04);
    // Smoothstep envelope (smoother than max() linear) — full density
    // at orb centre, falls to 0 by the silhouette.
    float r = length(p) / 2.0;
    float shape = 1.0 - smoothstep(0.0, 1.0, r);
    // Worley cellular noise. F2 - F1 → 0 exactly on Voronoi cell
    // boundaries (where two features are equidistant). The boundary
    // network is a 3D web of thin filaments.
    vec3 noisePos = p * cellScale + vec3(0.0, GlobalTime * 0.08, 0.0);
    vec2 ff = worleyF1F2(noisePos);
    float edge = ff.y - ff.x;
    // Tendril THICKNESS scales with shape — at the orb centre the
    // smoothstep accepts thicker edges (up to 0.30), at the silhouette
    // only the thinnest boundaries register. This gives a true
    // density gradient (more tendrils visible at centre, fewer at
    // edge) rather than just dimming the same tendrils uniformly.
    float thickness = max(0.001, 0.30 * shape);
    float tendrils = 1.0 - smoothstep(0.0, thickness, edge);
    return tendrils * shape * 6.0;
}

// =========================================================================
// HOUDINI-STYLE NOISE PATTERNS
//
// The five maps below mirror the named noise types from Houdini's
// UnifiedNoise VOP docs (https://www.sidefx.com/docs/houdini/nodes/vop/unifiednoise.html).
// They share the same orb-fitting structure as the three above:
//   1. cosmetic precession (rotation that drifts over time).
//   2. a per-noise-type density formula.
//   3. a sphere envelope so the noise fades into the orb silhouette.
// =========================================================================

// 3) F1 Worley — distance to nearest feature point. Each cell is
//    filled with a power-curve gradient from its feature centre out
//    to the edges. Envelope is the same gradual fade Hypertexture
//    uses so the pattern stays partially visible all the way to the
//    silhouette instead of fading to black at r=1.45.
float mapF1Worley(in vec3 p) {
    vec3 pInput = p;
    float cellScale = 1.6 + 0.15 * sin(GlobalTime * 0.05);
    vec3 noisePos = p * cellScale + vec3(0.0, GlobalTime * 0.06, 0.0);
    vec2 ff = worleyF1F2(noisePos);
    float t = clamp(ff.x, 0.0, 1.0);
    float cells = pow(1.0 - t, 1.4);
    float shape = max(0.0, 1.0 - length(pInput) / 2.0);
    return cells * shape * 4.5;
}

// 4) DNA Helix — two double-helix backbones at pseudo-random axis
//    directions. Each backbone is TWO independent strands wound
//    around its axis with a π phase offset, connected by thin rungs
//    at regular intervals along the axis. Tuning follows the
//    Shadertoy example's 10:1 radius-to-tube ratio so the strands
//    are visually distinct ropes rather than smeared blobs:
//      - Frequency is low (2.5) so the helix pitch is steep enough
//        that the closest-point-at-this-s approximation has minimal
//        error (closest-s pitch error ≈ sin(arctan(1/(freq*radius))) ).
//      - Two helixes only — three+ tends to mush visually as their
//        ropes cross each other.
const int DNA_HELIX_COUNT = 6;
const float DNA_HELIX_FREQ   = 2.5;
const float DNA_STRAND_THICK = 0.035;
const float DNA_RUNG_THICK   = 0.018;
const float DNA_RUNG_EVERY   = 1.0;
// Per-helix radius via hash. Range [0.3, 0.85] gives a clear "nested
// sizes" look — some helixes small and packed near the centre, others
// wide and skimming the orb's silhouette.
float dnaRadius(int i) {
    return mix(0.3, 0.85, h3(vec3(float(i) * 23.7, 5.5, 19.1)));
}

vec3 dnaAxis(int i) {
    float fi = float(i) * 13.37;
    float a = h3(vec3(fi, 0.0, 0.0)) * 6.2831853;
    float z = h3(vec3(fi, 7.7, 0.0)) * 2.0 - 1.0;
    float r = sqrt(max(0.0, 1.0 - z * z));
    return vec3(r * cos(a), z, r * sin(a));
}

void dnaBasis(vec3 axis, out vec3 right, out vec3 up) {
    vec3 worldUp = abs(axis.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    right = normalize(cross(axis, worldUp));
    up    = normalize(cross(right, axis));
}

// Newton-refined closest-point distance from `p` to a helix strand
// curve `axis * s + (r1·cos(s·freq + phase) + u1·sin(s·freq + phase)) * radius`.
// Starts from the simple `s = dot(p, axis)` projection and runs 3
// Newton iterations that converge on the true closest s. Without
// this refinement the strand "tube" rendered is much wider than the
// nominal thickness (closest-point error ≈ sin(arctan(1/(freq·radius)))·radius),
// which is why the two strands of a single helix were visually
// merging into one fat blob in the previous version.
float dnaStrandDist(vec3 p, vec3 axis, vec3 r1, vec3 u1, float phase, float radius, float freq) {
    float s = dot(p, axis);
    for (int iter = 0; iter < 3; ++iter) {
        float ang = s * freq + phase;
        float ca = cos(ang); float sa = sin(ang);
        vec3 pos = axis * s + (r1 * ca + u1 * sa) * radius;
        vec3 tan = axis + (-r1 * sa + u1 * ca) * radius * freq;
        s += dot(p - pos, tan) / dot(tan, tan);
    }
    float ang = s * freq + phase;
    vec3 pos = axis * s + (r1 * cos(ang) + u1 * sin(ang)) * radius;
    return length(p - pos);
}

float mapDNAHelix(in vec3 p) {
    vec3 pInput = p;
    float t = GlobalTime * 0.3;
    float minStrand = 1e10;
    float minRung   = 1e10;
    for (int i = 0; i < DNA_HELIX_COUNT; ++i) {
        vec3 axis = dnaAxis(i);
        vec3 r1; vec3 u1;
        dnaBasis(axis, r1, u1);
        float phase  = float(i) * 1.7 + t;
        float radius = dnaRadius(i);
        // Two strands at the same s but π apart in helix phase —
        // each gets a proper Newton-refined SDF so the rendered
        // tubes match the actual strand thickness.
        float dA = dnaStrandDist(p, axis, r1, u1, phase,           radius, DNA_HELIX_FREQ);
        float dB = dnaStrandDist(p, axis, r1, u1, phase + 3.14159, radius, DNA_HELIX_FREQ);
        minStrand = min(minStrand, min(dA, dB));
        // Rungs connect strand A to strand B at integer multiples of
        // DNA_RUNG_EVERY along the axis. Their distance is from p to
        // the line segment between the two strands' positions at the
        // rung's axis parameter.
        float s = dot(p, axis);
        float rungParam = floor(s / DNA_RUNG_EVERY + 0.5) * DNA_RUNG_EVERY;
        float rungAngA = rungParam * DNA_HELIX_FREQ + phase;
        vec3 rA = axis * rungParam + (r1 * cos(rungAngA)           + u1 * sin(rungAngA))           * radius;
        vec3 rB = axis * rungParam + (r1 * cos(rungAngA + 3.14159) + u1 * sin(rungAngA + 3.14159)) * radius;
        vec3 rab = rB - rA;
        float rt = clamp(dot(p - rA, rab) / dot(rab, rab), 0.0, 1.0);
        minRung = min(minRung, length(p - (rA + rab * rt)));
    }
    float strandDensity = 1.0 - smoothstep(DNA_STRAND_THICK, DNA_STRAND_THICK + 0.02, minStrand);
    float rungDensity   = 1.0 - smoothstep(DNA_RUNG_THICK,   DNA_RUNG_THICK + 0.010,  minRung);
    float density = max(strandDensity, rungDensity * 0.7);
    float shape = max(0.0, 1.0 - length(pInput) / 2.0);
    return density * shape * 5.0;
}

// 5) Hypercube — proper 4D tesseract projected into 3D. Sixteen
//    4D vertices ((±1,±1,±1,±1)), rotated in two 4D planes (XW and
//    YW) at different rates, then projected to 3D via perspective
//    projection from a viewer point at w=2.5. The 32 edges connect
//    every pair of vertices that differ in exactly ONE bit (the
//    edges of the 4D hypercube). As the 4D rotation progresses,
//    the projected 3D structure morphs — vertices appear to pass
//    "through" each other and the outer/inner cube relationship
//    is constantly shifting, which is the visual signature of a
//    rotating tesseract that two-fixed-cubes-with-spokes can't
//    reproduce.
float hcubeEdgeDist(vec3 p, vec3 a, vec3 b) {
    vec3 ab = b - a;
    float t = clamp(dot(p - a, ab) / dot(ab, ab), 0.0, 1.0);
    return length(p - (a + ab * t));
}

// Vertex i of the 4D unit hypercube: bits of i pick ±1 on each axis.
vec4 hcube4DVert(int i) {
    return vec4(
        float((i >> 0) & 1),
        float((i >> 1) & 1),
        float((i >> 2) & 1),
        float((i >> 3) & 1)
    ) * 2.0 - 1.0;
}

// 4D rotations in two planes: XW and YW. Together they sweep the
// vertices through the whole 4D rotational space over time.
vec4 hcube4DRotate(vec4 v, float t) {
    float c1 = cos(t); float s1 = sin(t);
    v = vec4(c1 * v.x - s1 * v.w, v.y, v.z, s1 * v.x + c1 * v.w);
    float c2 = cos(t * 0.7); float s2 = sin(t * 0.7);
    v = vec4(v.x, c2 * v.y - s2 * v.w, v.z, s2 * v.y + c2 * v.w);
    return v;
}

// Perspective projection from 4D to 3D. Viewer at w = HCUBE_VIEWER_W;
// each vertex's xyz is scaled by 1 / (viewer_w - vertex.w). Vertices
// with w near viewer_w project to "infinity" — guarded with max().
vec3 hcubeProject(vec4 v) {
    const float HCUBE_VIEWER_W = 2.5;
    float w = 1.0 / max(0.5, HCUBE_VIEWER_W - v.w);
    return v.xyz * w * 1.4;
}

float mapHypercube(in vec3 p) {
    vec3 pInput = p;
    float t = GlobalTime * 0.4;
    // Precompute all 16 projected vertex positions.
    vec3 verts[16];
    for (int i = 0; i < 16; ++i) {
        verts[i] = hcubeProject(hcube4DRotate(hcube4DVert(i), t));
    }
    // 32 edges: every pair (i, j) where popcount(i XOR j) == 1.
    // Iterate `i` over all 16 vertices, then for each of the 4 bit
    // positions check the partner `j = i XOR (1 << b)`. The `j > i`
    // guard counts each edge exactly once.
    float minDist = 1e10;
    for (int i = 0; i < 16; ++i) {
        for (int b = 0; b < 4; ++b) {
            int j = i ^ (1 << b);
            if (j > i) {
                minDist = min(minDist, hcubeEdgeDist(p, verts[i], verts[j]));
            }
        }
    }
    float lineThickness = 0.045;
    float density = 1.0 - smoothstep(lineThickness, lineThickness * 2.2, minDist);
    float shape = max(0.0, 1.0 - length(pInput) / 2.0);
    return density * shape * 5.0;
}

// 6) Alligator — sharp bumps with high-contrast valleys between
//    them, like alligator hide. Built by SUMMING multiple octaves of
//    Worley peaks at different scales: the coarse octave gives the
//    big bump structure, finer octaves add bumps-on-bumps texture.
//    Each octave uses `pow(1 - F1, exponent)` to make a sharp peak
//    at the feature point that falls off quickly — that's the
//    "scale" look. Subtracting cell-boundary trace then carves
//    visible dark seams between scales.
float mapAlligator(in vec3 p) {
    vec3 pInput = p;
    float t = GlobalTime * 0.06;
    vec3 q = p + vec3(0.0, t, 0.0);
    // Three octaves of sharp Worley peaks. Concentrate the weight in
    // the coarse octave for the dominant scale pattern, with the
    // finer octaves modulating texture on top.
    vec2 ff1 = worleyF1F2(q * 1.4);
    vec2 ff2 = worleyF1F2(q * 3.0 + vec3(7.7));
    vec2 ff3 = worleyF1F2(q * 6.0 + vec3(13.3, 0.0, 19.7));
    float peak1 = pow(max(0.0, 1.0 - ff1.x * 1.6), 2.5);
    float peak2 = pow(max(0.0, 1.0 - ff2.x * 1.8), 2.5);
    float peak3 = pow(max(0.0, 1.0 - ff3.x * 2.0), 2.5);
    float scales = peak1 * 0.55 + peak2 * 0.30 + peak3 * 0.15;
    // Carve sharp dark seams between scales using the coarse-octave
    // cell-boundary trace.
    float seam = 1.0 - smoothstep(0.0, 0.05, ff1.y - ff1.x);
    float pattern = clamp(scales - seam * 0.9, 0.0, 1.0);
    float shape = max(0.0, 1.0 - length(pInput) / 2.0);
    return pattern * shape * 5.0;
}

// 7) Eye — iris + pupil that always faces the camera in WORLD
//    frame. The iris basis is anchored to world-up (transformed
//    into mesh frame each fragment) so the iris pattern doesn't
//    spin with body rotation — only the eye's POSITION on the
//    orb's surface tracks the camera (because g_meshCamDir does
//    rotate with the body to keep pointing at the world camera).
//
//    Iris pattern adapted from the Shadertoy example: FBM-warped
//    angular striations, FBM-darkened secondary stripes, radial
//    gradient bright-near-pupil / dim-toward-rim, plus an off-
//    centre highlight spot. Pupil is rendered as zero density so
//    the cubemap reflection shows through dark.
float mapEye(in vec3 p) {
    vec3 pInput = p;
    // Analytically undo the raymarch's yaw rotation on `p`. The
    // raymarch applied `R_y(yaw)` before calling map(); we apply
    // `R_y(-yaw)` here so the eye sees an un-yawed mesh-frame
    // sample, which we then transform to world frame.
    float cy_e = cos(g_yaw);
    float sy_e = sin(g_yaw);
    vec3 pUnyaw = vec3(p.x * cy_e + p.z * sy_e, p.y, -p.x * sy_e + p.z * cy_e);

    vec3 pWorld    = MeshToWorld * pUnyaw;
    vec3 viewWorld = normalize(MeshToWorld * g_meshCamDir);

    vec3 worldUp = vec3(0.0, 1.0, 0.0);
    vec3 ref = abs(dot(viewWorld, worldUp)) > 0.95
        ? vec3(1.0, 0.0, 0.0)
        : worldUp;
    vec3 right = normalize(cross(viewWorld, ref));
    vec3 up    = normalize(cross(right, viewWorld));

    float depth = dot(pWorld, viewWorld);
    vec3 inPlane = pWorld - viewWorld * depth;
    float x = dot(inPlane, right);
    float y = dot(inPlane, up);
    // Normalised iris coords (q ∈ [-0.85, 0.85] across the orb's
    // visible disc) — the iris band, FBM frequencies, and highlight
    // position are all calibrated for this space.
    vec2 q = vec2(x, y) / 1.5;
    float r = length(q);
    float a = atan(q.y, q.x);

    // Slab depth is in shader scale (orb radius 2). (0.9, 1.4)
    // catches ~35 samples through the orb so the accumulated iris
    // density is clearly visible.
    float slab = 1.0 - smoothstep(0.9, 1.4, abs(depth));
    if (slab <= 0.0) return 0.0;

    // PRECESSION via PUPIL DILATION rather than yaw rotation: the
    // pupil radius animates between contracted and dilated on a
    // slow random walk. Per-block FBM seed phase keeps neighbouring
    // orbs out of lockstep, matching the per-block yaw phase the
    // other fractal patterns use. `smoothstep(0.25, 0.75, ...)`
    // biases the FBM output toward its extremes so the pupil
    // spends time HELD at small/wide rather than always drifting
    // through mid-range, giving a more eye-like contract/dilate
    // feel.
    float pupilT  = GlobalTime * 0.18 + vBlockSeed.x * 8.5;
    float pupilN  = fbm(vec3(pupilT, pupilT * 0.7, vBlockSeed.x * 3.3));
    float pupilR  = mix(0.18, 0.45, smoothstep(0.25, 0.75, pupilN));

    if (r > 0.85)   return 0.0;
    if (r < pupilR) return 0.0;

    // ALIEN COMPOUND-EYE iris. Worley cells tile the iris with
    // insect-like facets, each with a bright "lens" highlight at
    // its centre and a dark seam at its boundary. The cell field
    // SLOWLY evolves (z-coord ramps with time) so facets visibly
    // drift, dissolve, and reform — like the iris isn't sure what
    // shape it wants to be.
    vec2  ff       = worleyF1F2(vec3(q * 7.0, GlobalTime * 0.12 + vBlockSeed.x * 13.7));
    // Bright lens centre per facet — F1 is small at the cell's
    // feature point (the centre); `1 - F1 * k` peaks there.
    float facetCtr = pow(max(0.0, 1.0 - ff.x * 1.6), 1.8);
    // Dark border between facets — F2-F1 is near zero on cell
    // boundaries (two features equidistant), so smoothstepping
    // away from 0 carves the seams.
    float facetSeam = smoothstep(0.0, 0.05, ff.y - ff.x);
    // Fine interior texture inside each facet — high-frequency
    // FBM modulation. Without it the facet centres look flat and
    // plastic; with it they look like wet biological lenses.
    float fineMod   = 0.6 + 0.7 * fbm(vec3(q * 22.0, GlobalTime * 0.25 + vBlockSeed.x * 5.5));
    // Radial dark bands at irregular intervals — like nested
    // sphincter folds in the iris. Three bands at golden-ratio
    // offsets give it an "extra structure underneath" feel.
    float bands = (1.0 - smoothstep(0.02, 0.05, abs(r - 0.42)))
                + (1.0 - smoothstep(0.02, 0.05, abs(r - 0.62)))
                + (1.0 - smoothstep(0.02, 0.05, abs(r - 0.78)));
    bands = clamp(bands * 0.7, 0.0, 1.0);

    float irisPattern = facetSeam * (0.45 + 0.6 * facetCtr) * fineMod;
    irisPattern *= (1.0 - 0.55 * bands);

    float irisMask = smoothstep(pupilR, pupilR + 0.05, r) * (1.0 - smoothstep(0.75, 0.85, r));
    // 0.45 base + 0.85 pattern: the facet contrast is strong, and
    // a slightly lower base lets the dark seams read as truly dark
    // (the previous 0.55 base flattened them).
    float radialGrad = 1.0 - 0.45 * smoothstep(pupilR, 0.85, r);
    float iris = irisMask * (0.45 + 0.85 * irisPattern) * radialGrad;

    vec2 hCenter = vec2(0.24, 0.20);
    float hDist = length(q - hCenter);
    float highlight = (1.0 - smoothstep(0.0, 0.18, hDist)) * 0.6;

    float density = (iris + highlight) * slab;
    float shape = max(0.0, 1.0 - length(pInput) / 2.0);
    return density * shape * 6.0;
}

// Per-projector fractal selector. `vFractalType` arrives as Color.a
// normalised to [0, 1] — the BER packs the BlockState's `fractal_type`
// integer there. `int(... * 255 + 0.5)` recovers the original byte
// value. Same across every fragment of one orb (the BER colours every
// vertex with the same alpha), so the branch is uniform control flow
// — the GPU runs only one path per orb, no per-pixel divergence.
//
// To add another fractal: write a new `mapXxx()` above, append an
// `else if (type == N) return mapXxx(p);` here, and bump
// `FRACTAL_TYPE_COUNT` in `FractalProjectorBlock.kt` to match.
float map(in vec3 p) {
    int type = int(vFractalType * 255.0 + 0.5);
    if (type == 0)      return mapGuillitte(p);
    else if (type == 1) return mapMenger(p);
    else if (type == 2) return mapHypertexture(p);
    else if (type == 3) return mapF1Worley(p);
    else if (type == 4) return mapDNAHelix(p);
    else if (type == 5) return mapHypercube(p);
    else if (type == 6) return mapAlligator(p);
    else                return mapEye(p);
}

// Forward declaration — `sky()` is defined further down the file
// (after the cubemap helpers) but `raymarchVoxel()` below needs it
// for the empty-cell fallback. GLSL won't compile a call to an
// undeclared function.
vec3 sky(vec3 dir);

// 3D-DDA voxel traversal. Walks the ray through a uniform grid in
// shader-space coordinates one cell at a time; at each cell the
// fractal density `map()` is evaluated — first cell to cross
// `SOLID_THRESHOLD` returns the voxel's colour shaded by the face the
// ray entered through. If the ray exits the orb without ever
// crossing the threshold, the sky cubemap shows through (glassy
// behaviour: empty cells are transparent).
//
// `map()` is the same fractal density function the smooth raymarch
// uses — only the rendering changes, the interior volumetric shape is
// preserved exactly.
//
// The fractal interior animates via the `yaw` parameter: each cell's
// sample point is rotated about Y by that angle BEFORE being passed
// to `map()`. This keeps the voxel grid itself fixed in world space
// (so cubic faces stay anchored frame-to-frame and don't shimmer)
// while still letting the fractal pattern rotate inside the grid.
vec3 raymarchVoxel(in vec3 ro, in vec3 rd, in vec2 tminmax, in float yaw) {
    const float CELL_SIZE = 0.03125;     // shader units per voxel (128 across the orb diameter)
    const float SOLID_THRESHOLD = 0.5;   // map() value above which a cell counts as solid
    const int MAX_STEPS = 256;           // ~sqrt(3) · 128 ≈ 222 — 256 has headroom for diagonal traversal

    float maxT = tminmax.y - tminmax.x;
    vec3 startPos = ro + tminmax.x * rd;
    vec3 cell = floor(startPos / CELL_SIZE);

    vec3 stepDir = vec3(
        rd.x >= 0.0 ? 1.0 : -1.0,
        rd.y >= 0.0 ? 1.0 : -1.0,
        rd.z >= 0.0 ? 1.0 : -1.0
    );
    vec3 tDelta = abs(CELL_SIZE / rd);
    vec3 nextBoundary = (cell + max(stepDir, 0.0)) * CELL_SIZE;
    vec3 tMax = (nextBoundary - startPos) / rd;

    float cy = cos(yaw);
    float sy = sin(yaw);

    vec3 face = vec3(0.0, -1.0, 0.0);  // initial value — overwritten on first step

    for (int i = 0; i < MAX_STEPS; i++) {
        vec3 cellCenter = (cell + 0.5) * CELL_SIZE;
        // Yaw-rotate the sample input so the fractal spins inside the
        // fixed voxel grid.
        float rx = cellCenter.x * cy - cellCenter.z * sy;
        float rz = cellCenter.x * sy + cellCenter.z * cy;
        vec3 sampleP = vec3(rx, cellCenter.y, rz);
        float c = map(sampleP);

        if (c >= SOLID_THRESHOLD) {
            // MC-style per-face shading: top brightest, bottom darkest,
            // ±X slightly brighter than ±Z (matches vanilla's directional
            // light gradient).
            float shade;
            if (face.y >  0.5)         shade = 1.00;   // top
            else if (face.y < -0.5)    shade = 0.45;   // bottom
            else if (abs(face.x) > 0.5) shade = 0.72;  // east/west
            else                       shade = 0.62;   // north/south
            // Density-driven palette: vec3(c, c, c³) — R=G high, B suppressed → yellow.
            return vec3(c, c, c*c*c) * shade;
        }

        // Step to the next cell along whichever axis has the smallest tMax.
        float minT;
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            minT = tMax.x;
            cell.x += stepDir.x;
            face = vec3(-stepDir.x, 0.0, 0.0);
            tMax.x += tDelta.x;
        } else if (tMax.y < tMax.z) {
            minT = tMax.y;
            cell.y += stepDir.y;
            face = vec3(0.0, -stepDir.y, 0.0);
            tMax.y += tDelta.y;
        } else {
            minT = tMax.z;
            cell.z += stepDir.z;
            face = vec3(0.0, 0.0, -stepDir.z);
            tMax.z += tDelta.z;
        }

        if (minT > maxT) break;
    }
    // Ray exited the orb without ever hitting a solid voxel — show
    // the sky cubemap through the empty interior. Gives the orb its
    // "filled with floating cubes" look rather than reading as a
    // solid black shell with embedded voxels.
    return sky(rd);
}

vec3 raymarch(in vec3 ro, vec3 rd, vec2 tminmax, in float yaw) {
    float t = tminmax.x;
    float dt = 0.02;
    vec3 col = vec3(0.0);
    float c = 0.0;
    // Yaw rotation pre-computed once. Applied to each sample point
    // INSIDE this function (not to ro/rd globally) — keeping ro/rd in
    // world frame is what lets the fresnel cubemap reflection in main()
    // stay anchored to the actual environment instead of spinning with
    // the animation.
    float cy = cos(yaw);
    float sy = sin(yaw);
    // EVEN-WEIGHT accumulation (changed from the original's
    // `col = 0.99*col + 0.08*sample` decay). The decay heavily
    // front-weights the integral — samples near the camera dominate,
    // samples near the exit fade. In our setup the ray enters at the
    // visible front of the orb and exits at the back, so the decay made
    // the orb look like only the front HEMISPHERE was full of content;
    // the back half visibly vanished. Adding samples with equal weight
    // makes both halves contribute equally to each visible pixel, so
    // the orb reads as a true volumetric sphere rather than a dome.
    //
    // Channel palette: `vec3(c, c, c*c*c)` — R and G both take the
    // raw `c` value (the largest of c/c²/c³ for c<1), B takes c³ and
    // is suppressed. R≈G gives yellow; B≈0 keeps it from washing out
    // toward white. (The original Shadertoy used `vec3(c², c, c³)`
    // which puts c on the green channel only → that's why the orb
    // read as green before.)
    for (int i = 0; i < 64; i++) {
        t += dt * exp(-2.0 * c);
        if (t > tminmax.y) break;
        vec3 p = ro + t * rd;
        // Stash the un-yawed mesh-frame sample for maps that need a
        // precession-free coordinate frame (see [g_meshSamplePos]).
        g_meshSamplePos = p;
        // Yaw-rotate the sample input so the fractal spins inside the
        // world-anchored orb without dragging the reflection along.
        float rx = p.x * cy - p.z * sy;
        float rz = p.x * sy + p.z * cy;
        p.x = rx;
        p.z = rz;
        c = map(p);
        // Yellow tint at low density, pulling toward white at high
        // density so the brightest spots read as truly bright rather
        // than just "more saturated yellow". The blend factor ramps
        // from 0 at c≈1.5 up to 1 at c≈5, gradually adding blue/red
        // contributions to match green and approach equal RGB.
        vec3 yellowTint = vec3(1.0, 0.9, 0.3);
        float whiteBlend = clamp((c - 1.5) * 0.3, 0.0, 1.0);
        vec3 tint = mix(yellowTint, vec3(1.0), whiteBlend);
        col += 0.04 * c * tint;
    }
    return col;
}

// Substitute for the original's `iChannel0` cubemap lookup. MC core
// shaders don't bind a sky sampler, but `FogColor` carries the world's
// current sky tint, and the Sselith sky's stars are procedural anyway
// (see `SselithRepertorySky.buildStars()`), so we replicate the look
// here per-fragment instead of sampling a texture: FogColor-driven
// horizon→zenith gradient, a hash-cell starfield matching
// `SselithRepertorySky`'s ~800-star density and ~0.003-rad angular
// size, plus a per-slot shooting-star streak.

// 1-D hash on a vec2 cell id — small angle of `sin` keeps the LSB
// chaos that the standard hash trick depends on.
float starHash1(vec2 c) {
    return fract(sin(dot(c, vec2(127.1, 311.7))) * 43758.5453);
}
vec2 starHash2(vec2 c) {
    return fract(sin(vec2(
        dot(c, vec2(127.1, 311.7)),
        dot(c, vec2(269.5, 183.3))
    )) * 43758.5453);
}

// Equirectangular UV from a unit direction (in [0,1]² spanning the sphere).
vec2 dirToEquirect(vec3 d) {
    return vec2(
        atan(d.z, d.x) / 6.2831853 + 0.5,
        asin(clamp(d.y, -1.0, 1.0)) / 3.14159265 + 0.5
    );
}

// Square-pixel sky discretisation. Direction → equirectangular UV →
// floor onto a fixed grid. Each cell is one "sky pixel"; a star (or a
// shooting-star segment) lights up exactly one cell at a time, so the
// look is pixelated like the real Sselith sky (whose stars are tiny
// square billboards on the star sphere).
//
// Grid resolution: 1024×512 cells over the full sphere → per-cell
// angular size ≈ 0.35°, comparable to `SselithRepertorySky`'s
// STAR_SIZE_MIN/SPHERE_R = 0.003 rad ≈ 0.17° (one of our pixels covers
// roughly a real star's width; visually indistinguishable at projector
// scale).
const vec2 SKY_PIXEL_GRID = vec2(1024.0, 512.0);

vec2 dirToSkyPixel(vec3 dir) {
    return floor(dirToEquirect(dir) * SKY_PIXEL_GRID);
}

// Static starfield. Each sky-pixel cell independently rolls a hash; a
// small fraction (~0.15%) crosses the threshold and becomes a star.
// 0.15% × 1024 × 512 ≈ 786 stars across the full sphere — matches the
// post-rejection count from `SselithRepertorySky.buildStars()`'s
// 1500-sample budget. Stars are exactly one sky-pixel wide because the
// "is this pixel a star" test runs per-cell with no smoothing.
float proceduralStars(vec3 dir) {
    vec2 pixel = dirToSkyPixel(dir);
    float h = starHash1(pixel);
    if (h < 0.9985) return 0.0;
    // Twinkle envelope at the same 0.05 rad/tick rate as the real sky
    // (≈ 1 rad/s at 20 TPS). Per-star phase from the hash so neighbours
    // don't pulse in unison.
    float phase = starHash1(pixel + vec2(31.7, 89.3)) * 6.2831853;
    float twinkle = 0.55 + 0.45 * sin(GlobalTime + phase);
    return twinkle;
}

// Shooting star with a real pixel trail. Each slot picks a random
// great-circle pair (a, b); the head walks from a → b across the slot.
// To render the trail, we sample N points along the arc behind the
// head — each sample maps to a sky-pixel cell, and a fragment lights
// up if its own cell matches one of those samples. Closer to the head
// = brighter; the tail fades to nothing. Result is a discrete line of
// square pixels with a brightness gradient, like a low-res streak.
//
// Sample count is tuned for the typical arc traversed per slot. At
// trailLen = 0.35 of the great-circle distance between a and b
// (average ≈ π/2 rad), the trail covers ~30°. With 80 samples that's
// ~0.4° per sample — finer than one sky pixel (≈ 0.35°), so consecutive
// samples mostly hit adjacent pixels with no gaps.
float proceduralShootingStar(vec3 dir) {
    float slotPeriod = 2.5;
    float slot = floor(GlobalTime / slotPeriod);
    float slotT = fract(GlobalTime / slotPeriod);

    vec3 a = normalize(vec3(
        starHash1(vec2(slot, 0.0)) * 2.0 - 1.0,
        starHash1(vec2(slot, 1.0)),
        starHash1(vec2(slot, 2.0)) * 2.0 - 1.0
    ));
    vec3 b = normalize(vec3(
        starHash1(vec2(slot, 10.0)) * 2.0 - 1.0,
        starHash1(vec2(slot, 11.0)),
        starHash1(vec2(slot, 12.0)) * 2.0 - 1.0
    ));

    float headT = smoothstep(0.0, 1.0, slotT);
    float window = smoothstep(0.0, 0.15, slotT) * (1.0 - smoothstep(0.85, 1.0, slotT));
    const float trailLen = 0.35;

    vec2 dirPixel = dirToSkyPixel(dir);
    float best = 0.0;

    const int TRAIL_SAMPLES = 80;
    for (int i = 0; i < TRAIL_SAMPLES; i++) {
        float t = headT - (float(i) / float(TRAIL_SAMPLES - 1)) * trailLen;
        if (t < 0.0) break;
        vec3 segPos = normalize(mix(a, b, t));
        vec2 segPixel = dirToSkyPixel(segPos);
        if (segPixel.x == dirPixel.x && segPixel.y == dirPixel.y) {
            // Fade: 1.0 at the head, 0.0 at the tail. Squared so the
            // head dot reads "brighter than rest of trail" without the
            // tail vanishing into noise.
            float fade = 1.0 - float(i) / float(TRAIL_SAMPLES - 1);
            best = max(best, fade * fade);
        }
    }
    return best * window;
}

// SGA WORDS projected into the cubemap, sampled from MC's vanilla
// `ascii_sga.png` font texture (bound as Sampler6). Each of the 6
// GLYPH_SLOTS has its current word's first MAX_WORD_LEN char codes
// uploaded by the BER in the `WordLetters` uniform; the BER mirrors
// TomeSummonOverlay's hash-derived word-index pick so the same
// vocabulary cycles through both effects.
//
// Per slot, the word's head direction walks a great-circle from a → b
// over its lifetime (same slot/cycle pattern as the shooting star).
// Each letter is laid out along a local "right" axis at LETTER_SPACING
// rad of angular offset from the next, and the fragment shader samples
// the font texture to figure out whether a fragment falls inside the
// glyph's pixel mask.
const int GLYPH_SLOTS = 6;
const int MAX_WORD_LEN = 8;
const float LETTER_HALF_W = 0.0085;   // rad (~half letter angular width)
const float LETTER_HALF_H = 0.0110;   // rad (~half letter angular height)
const float LETTER_SPACING = 0.020;   // rad, letter centre-to-centre
uniform float WordLetters[GLYPH_SLOTS * MAX_WORD_LEN];

// Sample the SGA font texture for codepoint `code` at glyph-local
// `(u, v)` in [0, 1] × [0, 1]. The texture is a 16×16 grid of cells;
// each ASCII char's glyph lives in the upper-LEFT 8×8 of its 16×16
// cell, so we squash `u, v` into the cell's first half before sampling.
// Returns 1.0 if the glyph mask is set at this point, 0.0 otherwise.
float sgaGlyphMask(int code, vec2 uv) {
    int col = code - 16 * (code / 16);  // code % 16, GLSL has no %.
    int row = code / 16;
    vec2 cellBase = vec2(float(col), float(row)) / 16.0;
    vec2 cellUV = uv * (0.5 / 16.0);  // 0..1 maps to 0..8/256 of the cell.
    return texture(Sampler6, cellBase + cellUV).a;
}

float proceduralGlyphs(vec3 dir) {
    float total = 0.0;
    for (int g = 0; g < GLYPH_SLOTS; ++g) {
        float gid = float(g);
        float lifeJitter = 0.75 + starHash1(vec2(gid, 99.0)) * 0.5;
        float lifeSec = 7.0 * lifeJitter;
        float phaseSec = starHash1(vec2(gid, 100.0)) * lifeSec;
        float progress = fract((GlobalTime + phaseSec) / lifeSec);
        float cycle = floor((GlobalTime + phaseSec) / lifeSec);

        vec3 a = normalize(vec3(
            starHash1(vec2(gid, cycle * 7.0 + 1.0)) * 2.0 - 1.0,
            starHash1(vec2(gid, cycle * 7.0 + 2.0)) * 2.0 - 1.0,
            starHash1(vec2(gid, cycle * 7.0 + 3.0)) * 2.0 - 1.0
        ));
        vec3 b = normalize(vec3(
            starHash1(vec2(gid, cycle * 7.0 + 4.0)) * 2.0 - 1.0,
            starHash1(vec2(gid, cycle * 7.0 + 5.0)) * 2.0 - 1.0,
            starHash1(vec2(gid, cycle * 7.0 + 6.0)) * 2.0 - 1.0
        ));
        vec3 head = normalize(mix(a, b, progress));

        // Build a stable orthonormal (right, up) basis at the head.
        // Fall back to world +X for "up" when the head is near the
        // pole so the basis doesn't collapse.
        vec3 worldUp = abs(head.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 right = normalize(cross(worldUp, head));
        vec3 up    = normalize(cross(head, right));

        float fadeIn  = smoothstep(0.00, 0.15, progress);
        float fadeOut = 1.0 - smoothstep(0.85, 1.00, progress);
        float envelope = fadeIn * fadeOut;
        if (envelope < 0.01) continue;

        // Word length: count non-zero codes so we can centre the word.
        int wordLen = 0;
        for (int li = 0; li < MAX_WORD_LEN; ++li) {
            if (int(WordLetters[g * MAX_WORD_LEN + li] + 0.5) == 0) break;
            wordLen++;
        }
        if (wordLen == 0) continue;

        for (int li = 0; li < MAX_WORD_LEN; ++li) {
            int code = int(WordLetters[g * MAX_WORD_LEN + li] + 0.5);
            if (code == 0) break;

            float lo = (float(li) - (float(wordLen) - 1.0) * 0.5) * LETTER_SPACING;
            vec3 letterDir = normalize(head + right * lo);

            // Angular offset from the letter centre, projected onto the
            // word-local (right, up) basis. Small-angle approximation
            // — `dir - letterDir` lies in the tangent plane for nearby
            // directions, and dot-with-basis gives the local 2D coords.
            vec3 delta = dir - letterDir;
            float du = dot(delta, right);
            float dv = dot(delta, up);
            if (abs(du) > LETTER_HALF_W || abs(dv) > LETTER_HALF_H) continue;

            // Normalise to [0, 1] glyph-local UV. v is flipped because
            // texture V grows downward but our `up` axis grows upward.
            float u = (du + LETTER_HALF_W) / (2.0 * LETTER_HALF_W);
            float v = 1.0 - (dv + LETTER_HALF_H) / (2.0 * LETTER_HALF_H);

            float mask = sgaGlyphMask(code, vec2(u, v));
            if (mask < 0.5) continue;

            total += envelope;
        }
    }
    return clamp(total, 0.0, 1.0);
}

// Cubemap sampler. Picks the face whose axis dominates the direction,
// then unwraps the remaining two components to a face-local UV. Image
// V convention: MC's NativeImage uploads textures with the PNG's
// top-left at uv=(0,0), so v increases DOWNWARD — looking up (dir.y > 0)
// should sample SMALLER v, which is why every face's "vertical" UV is
// `-dir.y/abs(ad)` rather than `+dir.y/abs(ad)`.
vec3 sampleCubemap(vec3 dir) {
    // Horizontal mirror correction. Panoramica's capture orientation
    // is left-right flipped relative to MC's world axis convention —
    // visible as "elements that should be on the left are on the right"
    // when looking at any face. Flipping dir.x before sampling
    // mirrors every face on the X axis to compensate.
    dir.x = -dir.x;
    vec3 ad = abs(dir);
    vec2 uv;
    if (ad.x >= ad.y && ad.x >= ad.z) {
        if (dir.x > 0.0) {
            // EAST face (+X) — facing east, right = -Z (north), up = +Y.
            uv = vec2(-dir.z, -dir.y) / ad.x;
            return texture(Sampler3, uv * 0.5 + 0.5).rgb;
        } else {
            // WEST face (-X) — facing west, right = +Z (south), up = +Y.
            uv = vec2(dir.z, -dir.y) / ad.x;
            return texture(Sampler1, uv * 0.5 + 0.5).rgb;
        }
    } else if (ad.y >= ad.z) {
        if (dir.y > 0.0) {
            // UP face (+Y) — top panorama row faces -Z (north).
            uv = vec2(dir.x, dir.z) / ad.y;
            return texture(Sampler4, uv * 0.5 + 0.5).rgb;
        } else {
            // DOWN face (-Y).
            uv = vec2(dir.x, -dir.z) / ad.y;
            return texture(Sampler5, uv * 0.5 + 0.5).rgb;
        }
    } else {
        if (dir.z > 0.0) {
            // SOUTH face (+Z) — facing south, right = -X (west), up = +Y.
            uv = vec2(-dir.x, -dir.y) / ad.z;
            return texture(Sampler0, uv * 0.5 + 0.5).rgb;
        } else {
            // NORTH face (-Z) — facing north, right = +X (east), up = +Y.
            uv = vec2(dir.x, -dir.y) / ad.z;
            return texture(Sampler2, uv * 0.5 + 0.5).rgb;
        }
    }
}

vec3 sky(vec3 dir) {
    // Sample the Panoramica cubemap as the actual sky / environment
    // map. Replaces the old FogColor-based gradient and the procedural
    // starfield — the cubemap already contains the real Sselith sky
    // (stars baked in, captured from in-world).
    vec3 base = sampleCubemap(dir);
    // Static cubemaps can't carry shooting stars, so the procedural
    // ones get layered on top.
    base += proceduralShootingStar(dir) * vec3(1.0, 0.95, 0.85) * 1.4;
    // SGA-style rune glyphs drift through the cubemap, fading in and
    // out — same warm yellow as the SGA HUD overlay (#E8C84A).
    base += proceduralGlyphs(dir) * vec3(0.91, 0.78, 0.29);
    return base;
}

void main() {
    // STEP 1: rotate `vViewPos` from view-space back into world-space
    // (camera-relative). `vViewPos` is view-space because MC bakes the
    // camera_view rotation into the pose stack — see the vertex shader
    // comment. `ViewToWorld` is the renderer-supplied `camera.rotation()`
    // mat3 (object->world; since the camera's local frame IS view space
    // that's exactly view->world).
    vec3 worldCamRel = ViewToWorld * vViewPos;

    // STEP 2: convert the world-frame camera-relative position into the
    // BODY-LOCAL (mesh) frame. For axis-aligned orbs MeshToWorld is
    // identity and this collapses to the legacy behaviour; for rotated
    // VS-Body orbs WorldToMesh undoes the body's rotation so the
    // raymarch + fractal density sample are taken in coordinates
    // anchored to the body itself. Result: the cubic structure rotates
    // with the orb instead of slipping past it.
    mat3 WorldToMesh = transpose(MeshToWorld);
    vec3 meshCamRel = WorldToMesh * worldCamRel;
    vec3 rdMesh = normalize(meshCamRel);

    // STEP 3: front-hemisphere clamp. vObjNormal is already mesh-local
    // (no rotation applied to the Normal attribute), so the dot test
    // is meaningful directly against rdMesh.
    if (dot(rdMesh, vObjNormal) >= 0.0) discard;

    // STEP 4: camera position in mesh-local block-centred coords.
    //   block centre - camera = vObjNormal * 1.5 - meshCamRel
    // where vObjNormal * 1.5 is the fragment's mesh-local position
    // (radius 1.5 sphere) and meshCamRel is the camera->fragment
    // vector in mesh-local frame.
    vec3 cameraMesh = vObjNormal * 1.5 - meshCamRel;
    // Cache camera direction for map functions that need it (e.g.
    // the eye map's face-the-camera projection). Direction from orb
    // origin TOWARD camera.
    g_meshCamDir = normalize(cameraMesh);

    // Scale into the shader's virtual-sphere coordinate system — the
    // original Shadertoy `map()` is tuned for a radius-2 sphere at the
    // origin, ours is mesh-local radius 1.5, so the linear scale 4/3
    // matches one to the other. ro/rd stay in MESH frame here so the
    // fractal `map(p)` evaluates the structure anchored to the body.
    float scale = 2.0 / 1.5;
    vec3 ro = cameraMesh * scale;
    vec3 rd = rdMesh;

    // Slow time-based yaw of the fractal interior, with per-block
    // phase so neighbouring projectors aren't in lockstep.
    float yaw = 0.1 * GlobalTime + vBlockSeed.x * 6.2831853;
    g_yaw = yaw;

    // Intersect the virtual sphere. For mesh radius 1.5 the camera ray
    // always hits — `tmm.x` is the camera-to-fragment distance in
    // shader-scale, `tmm.y` is the camera-to-far-side distance.
    vec2 tmm = iSphere(ro, rd, vec4(0.0, 0.0, 0.0, 2.0));
    if (tmm.y <= 0.0) discard;
    tmm.x = max(tmm.x, 0.0);

    // Smooth raymarch — even-weight density accumulation through the
    // orb's interior. The yaw is applied to each sample point INSIDE
    // `raymarch()` so ro/rd stay in world frame and the cubemap
    // reflection below remains anchored to the actual environment.
    // `raymarchVoxel()` above is still defined for an easy swap to the
    // DDA voxel look.
    vec3 col = raymarch(ro, rd, tmm, yaw);

    // Fresnel sky-reflection at the entry surface. The raymarch ran in
    // MESH frame, so `nor` and `ref` come out in mesh frame too.
    // `sky(...)` samples the world-anchored cubemap, so rotate the
    // reflection direction into world space before sampling — the
    // environment stays put while the body rotates inside it.
    vec3 entry  = ro + tmm.x * rd;             // mesh
    vec3 nor    = normalize(entry);            // mesh
    vec3 ref    = reflect(rd, nor);            // mesh
    vec3 refW   = MeshToWorld * ref;           // world
    float fre   = pow(0.5 + clamp(dot(ref, rd), 0.0, 1.0), 3.0) * 1.1;
    col += sky(refW) * fre;

    // Thin white rim fresnel — `pow(1 - dot(N, V), 24)` only lights up
    // within ~10° of grazing. Frame-invariant (rotation preserves the
    // dot product) so it works equally well in mesh or world frame.
    float ndv = max(-dot(nor, rd), 0.0);
    float rim = pow(1.0 - ndv, 24.0);
    col += vec3(1.0) * rim;

    // Phong highlight from the world-fixed Sselith sun. SUN_DIR is in
    // WORLD frame; rotate the mesh-frame normal into world before the
    // half-vector dot product so the highlight stays put on the orb's
    // surface as the body rotates.
    const vec3 SUN_DIR = vec3(-0.4661, 0.5664, 0.6792);
    vec3 norWorld = MeshToWorld * nor;
    vec3 rdWorld  = MeshToWorld * rd;
    vec3 viewDirWorld = -rdWorld;
    vec3 halfDir = normalize(SUN_DIR + viewDirWorld);
    float spec = pow(max(dot(norWorld, halfDir), 0.0), 64.0);
    col += vec3(1.0, 0.85, 0.25) * spec * 1.5;

    col = 0.5 * log(1.0 + col);
    col = clamp(col, 0.0, 1.0);
    fragColor = vec4(col, 1.0);
}

/*
=========================================================================
  PREVIOUS FRACTAL SHADER (preserved for rollback)
  Six-channel procession sampled at the mesh-local surface normal —
  Mandelbulb / Quaternion Julia / Apollonian / Menger / Newton /
  Burning Ship with a short crossfade. Unwrap this block + remove the
  new main() above to flip back.
-------------------------------------------------------------------------

  const float PI  = 3.14159265359;
  const float TAU = 6.28318530718;
  const float FRACTAL_DURATION = 8.0;
  const float BLEND_DURATION   = 0.6;

  vec3 palette(float t) {
      t = clamp(t, 0.0, 1.0);
      vec3 c0 = vec3(0.04, 0.00, 0.10);
      vec3 c1 = vec3(0.30, 0.05, 0.20);
      vec3 c2 = vec3(0.80, 0.20, 0.10);
      vec3 c3 = vec3(1.00, 0.55, 0.08);
      vec3 c4 = vec3(1.00, 0.92, 0.40);
      vec3 c5 = vec3(0.50, 1.00, 0.85);
      vec3 c6 = vec3(1.00, 1.00, 1.00);
      if (t < 0.16) return mix(c0, c1, t / 0.16);
      if (t < 0.33) return mix(c1, c2, (t - 0.16) / 0.17);
      if (t < 0.50) return mix(c2, c3, (t - 0.33) / 0.17);
      if (t < 0.67) return mix(c3, c4, (t - 0.50) / 0.17);
      if (t < 0.83) return mix(c4, c5, (t - 0.67) / 0.16);
      return mix(c5, c6, (t - 0.83) / 0.17);
  }

  ... (mandelbulb / quaternionJulia / apollonian / menger / newton /
       burningShip / fractalValue fns plus a main() that did channel
       procession, sampled at normalize(vObjNormal), and palette-mapped
       the result. See git history for the full body.)
=========================================================================
*/
