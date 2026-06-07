package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferBuilder
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.animation.KeyframeAnimations
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.TentacleAnimation
import org.shipwrights.enderkinesis.client.model.TentacleModel
import org.shipwrights.enderkinesis.dimension.YgannAbyss
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The **Writhing Sea** of [YgannAbyss] — an uncomfortable, churning expanse of dark matter
 * far, far, far below everything else in the void, filling the whole lower hemisphere down to
 * the horizon whenever the player looks off the edge of a ship.
 *
 *  ## Where it sits, and why it's a dome
 *
 *  Rendered in the **sky pass**, immediately after the End sky cube (see
 *  [org.shipwrights.enderkinesis.mixin.LevelRendererYgannAbyssEyesMixin]), in the same
 *  camera-relative pose as [YgannAbyssWatchingEyes]: the pose carries the camera *rotation*
 *  but not its *translation*. The pass runs with `depthMask(false)` and the depth test off, so
 *  whatever we draw is a pure backdrop that any real geometry (the ship under your feet) paints
 *  over later in the frame.
 *
 *  Because the depth test is off, **only the screen direction of each vertex matters** — its
 *  distance is irrelevant. That sidesteps the one hard constraint here: the world projection's
 *  far clip plane in this pass is just `renderDistance × 64` blocks (≈768 at 12 chunks), so a
 *  genuine horizon-spanning flat plane would be sliced into a hard circle. Instead the surface
 *  is a **lower-hemisphere dome** of [RINGS] rings × [SECTORS] sectors at a fixed safe radius
 *  ([domeRadius], a fraction of the live far plane): every vertex stays inside the frustum, and
 *  the outermost ring sits a hair below the horizon line, so the sea always *reaches the
 *  horizon* at any render distance.
 *
 *  To stop the dome reading as a bowl, texture and waves are mapped as if it were a flat sea:
 *  each ring's screen depression `θ` comes from a notional flat-plane radius `r` via
 *  `θ = atan2(depth, r)` (depth tracks camera altitude — see below), and the UV / wave phase
 *  are sampled at the world point
 *  `(camX + r·cosφ, camZ + r·sinφ)`. Texture density compresses toward the horizon exactly as a
 *  real ocean's would.
 *
 *  ## Two layers: a flat backdrop + a world-anchored wave grid
 *
 *  The sea is drawn in two passes (see [drawSea]). The **backdrop** ([drawBackdrop]) is the polar
 *  dome above, kept *flat* — it reaches the horizon and hides the End-sky nebula, but carries no
 *  waves. The **foreground** ([drawForeground]) is a separate grid that carries the real 3D waves.
 *
 *  Why two meshes: the dome's vertices sample the world at *camera-relative* offsets
 *  (`camX + r·dir`), so each vertex holds a fixed direction from the eye — the surface has zero
 *  translational parallax and feels glued to the camera. (The texture only *looked* world-pinned
 *  because its UV image slides over that fixed mesh; the geometry can't slide.) The foreground grid
 *  fixes this by pinning its vertices to **fixed world positions** (`gx, gz` on a lattice), snapped
 *  to the cell size so the grid only ever shifts by whole cells as the camera moves — seamless,
 *  because the wave/texture are world-sampled. As the camera translates, each world vertex's
 *  direction changes, so the whole surface — waves and all — flows past you, world-anchored.
 *
 *  Each foreground vertex is placed in the true direction from the eye to its displaced world point
 *  (`(gx−camX, (SEA_Y+h)−camY, gz−camZ)` normalised to the dome radius), so a crest rises *and*
 *  swings outward — the faithful projection of a real 3D wave — never a camera-space heave. The grid
 *  feathers its wave amplitude and alpha into the flat backdrop at its rim. [surfaceHeight]-keyed
 *  shading is layered on so the relief reads from far above (there are no normals/lighting here).
 *
 *  ## World-anchored, not glued to the camera
 *
 *  `depth` is the camera's height above the sea (two blocks above the void kill plane, see
 *  [SEA_ABOVE_KILLPLANE]), clamped — **not** a constant — so the sea recedes as you climb and looms
 *  as you fall to your death, anchoring it to the world vertically. Horizontal pinning is automatic:
 *  the rings sample world coordinates
 *  `camX/Z + r·dir`, so a crest holds its world position and slides beneath you as you move. The
 *  ring tables are rebuilt each frame ([buildRingTables]) for the live depth.
 *
 *  ## Time base — client wall-clock, not game time
 *
 *  All animation is driven by [Util.getMillis] (monotonic client time), **not** `level.gameTime`.
 *  Game time advances in server-tick lockstep and visibly stutters whenever the server lags; the
 *  sea would judder with it. Wall-clock time keeps the swell perfectly smooth regardless of TPS.
 *
 *  ## The writhing — fluid, shifting, twisting, *not* rhythmic
 *
 *  [surfaceHeight] sums slow travelling swells, a twist wave whose crest direction rotates over
 *  time, and a beating pair of detuned medium waves — all fed through a slow **domain warp** so
 *  the surface never settles into a clean, recognisable sine rhythm. There is deliberately no
 *  fast chop layer: short-wavelength, high-speed waves read as a mechanical shimmer from this
 *  far away. Crests show the faint-green texture at full brightness; troughs crush toward black,
 *  so the relief reads from far above without real normals.
 *
 *  ## The eyes
 *
 *  [writhing_mass_eye.png] (a 4-frame open→blink animation) carpets the whole sea as a procedural
 *  **noise field** ([drawEyes]): a world-anchored lattice, hashed so a fraction of cells hold a
 *  jittered, independently-blinking eye — no spawn state, recomputed each frame from the hash. They
 *  are **submerged** — laid flat on a sheet [EYE_SUBMERGE] blocks *below* the waves and drawn
 *  between the backdrop and the (translucent) wave grid, so the dark water renders in front of them
 *  and they read as glimpsed beneath the surface, dim and a little shifted.
 *
 *  ## The tentacles
 *
 *  A sparser world-anchored lattice ([renderTentacles]) of the artist's [TentacleModel]s, posed by
 *  [TentacleAnimation], rises from the **eye-void** patches (where the eye-density field is low, so
 *  eyes and tentacles never share a spot), in drifting clouds. They are real animated models, but
 *  rendered here in the **sky pass** like the sea — a [DomeTentacleConsumer] intercepts each model
 *  vertex and dome-projects it — so they are free of the world far-clip plane and the distant ones
 *  tower all the way to the **horizon** (the megalophobia silhouette). Size grows with distance: near
 *  ones are modest, far ones are sized so their tips crest the horizon line.
 */
object YgannAbyssWrithingSea {

    private val SEA_TEX: ResourceLocation = EnderkinesisMod.id("textures/ygann_abyss/writhing_mass_sea.png")
    private val EYE_TEX: ResourceLocation = EnderkinesisMod.id("textures/ygann_abyss/writhing_mass_eye.png")
    private val TENT_TEX: ResourceLocation = EnderkinesisMod.id("textures/ygann_abyss/writhing_tentacle.png")
    private val TENT_EMISSIVE_TEX: ResourceLocation = EnderkinesisMod.id("textures/ygann_abyss/writhing_tentacle_emissive.png")

    // --- Dome geometry -------------------------------------------------------------------

    /** Number of angular slices in the dome. */
    private const val SECTORS: Int = 80

    /** Number of rings from the nadir cap out to the horizon. */
    private const val RINGS: Int = 64

    // The foreshorten depth — how far below the eye the flat sea sits — drives the screen
    // depression of each ring (`θ = atan2(depth, r)`). It is NOT a constant: it tracks the camera's
    // height above the sea's world Y (two blocks above the kill plane, [SEA_ABOVE_KILLPLANE], clamped),
    // so climbing makes the sea recede and descending makes it loom — i.e. the sea is anchored in the
    // world vertically instead of being glued to the camera. Horizontal world-pinning is automatic
    // (the rings sample world `camX/Z + r·dir`). Clamp keeps the look in the good shallow band
    // and avoids degeneracy when the camera passes below the notional level.

    /** Blocks above the void kill plane the sea sits at. The kill plane is `minBuildHeight − 64`
     *  (see `YgannAbyssVoidFade`); the sea is two blocks above it, so a falling player touches the
     *  surface just before the kill plane takes them. Resolved per-frame from the live level's
     *  `minBuildHeight` (which the dimension redirects to the overworld's), so datapack height
     *  overrides carry. The sea is still a depth-disabled backdrop foreshortened to *look* anchored
     *  here, not collidable geometry. */
    private const val SEA_ABOVE_KILLPLANE: Double = 2.0

    /** Lower bound on the live foreshorten depth (blocks): keeps the swell from crossing the horizon
     *  when the camera nears the surface near death (must exceed the wave amplitude ~17). */
    private const val DEPTH_MIN: Double = 24.0

    /** The depth ceiling is `min(domeRadius × DEPTH_DOME_FRAC, DEPTH_ABS_MAX)`. Tying it to the live
     *  dome radius (rather than a fixed value) keeps the nadir vertex inside the draw radius while
     *  letting the depth — and thus the sea's apparent distance — keep growing with altitude until
     *  very high up; a fixed ceiling clamped at a low y and made the sea follow the camera. The
     *  absolute cap stops the sea flattening to an invisible line at huge render distances. */
    private const val DEPTH_ABS_MAX: Double = 400.0
    private const val DEPTH_DOME_FRAC: Double = 0.9

    /** Innermost flat-plane radius (blocks) — the nadir cap, straight down. */
    private const val RING_INNER_R: Double = 2.5

    /** Screen depression (degrees) of the outermost ring — just shy of the true horizon (0°).
     *  The thin uncovered sliver below it is a few pixels of near-black End sky behind a
     *  near-black sea: invisible. Pushed no smaller to keep horizon texture aliasing in check. */
    private const val HORIZON_DEG: Double = 0.30

    /** Degrees above the horizon over which the sea's alpha feathers from 0 → 1, so its rim
     *  melts into the sky rather than ending on a hard line. */
    private const val HORIZON_FEATHER_DEG: Double = 2.2

    /** Dome draw radius as a fraction of the live far clip plane. Only scales positions
     *  uniformly (angles, UV, waves are all radius-independent), so this just keeps the whole
     *  dome comfortably inside the frustum. */
    private const val DOME_RADIUS_FRAC: Double = 0.72

    /** Hard cap on the dome radius so a very high render distance doesn't push vertices out to
     *  absurd magnitudes (and the attendant float error). Angles are unaffected. */
    private const val DOME_RADIUS_MAX: Double = 2400.0

    /** The far clip plane is `renderDistance(chunks) × this` blocks in 1.20.1
     *  (`GameRenderer.getDepthFar()`). */
    private const val FAR_PLANE_PER_CHUNK: Double = 64.0

    /** Biome fog colour (`fog_color` 1114152 = 0x110028) the surface tints toward as it nears
     *  the horizon, so the far sea greys into the void haze. */
    private const val FOG_R: Float = 0x11 / 255f
    private const val FOG_G: Float = 0x00 / 255f
    private const val FOG_B: Float = 0x28 / 255f

    /** Blocks of sea texture per 16×16 tile. */
    private const val TEX_SCALE: Double = 12.0

    // --- Writhe shape: domain-warped fBm -------------------------------------------------
    //
    // A SUM OF SINUSOIDS ALWAYS TILES — it's a regular interference lattice (a grid), and a periodic
    // warp only shifts that grid. The standard NON-TILING technique (Inigo Quilez domain warping,
    // https://iquilezles.org/articles/warp/) is fractional Brownian motion: sum several octaves of
    // VALUE NOISE, each octave ROTATED relative to the last — the rotation decorrelates the value-noise
    // lattice axes so no grid ever shows — then displace the whole domain by ANOTHER fBm: fbm(p+fbm(p)).
    // Noise is non-periodic, so the surface never repeats. We drift the warp slowly so the sheet churns
    // (the 'writhe') rather than scrolling rigidly.

    /** Wavelength (blocks) of the COARSEST fBm octave; finer octaves halve it. Smaller = smaller,
     *  denser waves (a big base read as too sparse). 100→50→25 for 3 octaves; the 25 finest is sampled
     *  at FG_CELL=9. */
    private const val SEA_BASE_LEN: Double = 100.0
    /** Octaves of the height fBm. Finest ≈ SEA_BASE_LEN / 2^(octaves−1) — keep it ≥ ~2·[FG_CELL] or it
     *  aliases. Fewer octaves = cheaper, and a coarser finest octave lets [FG_CELL] grow → fewer verts. */
    private const val SEA_OCTAVES: Int = 3
    /** Per-octave amplitude falloff. >0.5 makes the finer octaves more prominent → busier, less sparse
     *  surface (rather than one big swell dominating). */
    private const val FBM_GAIN: Double = 0.58
    /** Octaves of the low-frequency domain-warp fBm. One is enough for a smooth curl (and cheap). */
    private const val WARP_OCTAVES: Int = 1
    /** How far the warp fBm displaces the height domain (coarse-octave units). Bigger = more swirl,
     *  which folds the forms into more structure (also helps the surface not read as sparse). */
    private const val WARP_GAIN: Double = 3.2
    /** Vertical amplitude scale — fBm output (≈ ±0.45) × this = wave height in blocks. Keep the peak
     *  under DEPTH_MIN (~24) or crests cross the horizon. */
    private const val SEA_HEIGHT: Double = 30.0
    /** Slow overall ADVECTION of the field (coarse-octave units / s) — the sea travels. fBm has no
     *  aligned crests, so this reads as organic drift, not the directional swell-march of sinusoids. */
    private const val ADV_X: Double = 0.006
    private const val ADV_Z: Double = 0.004
    /** Active drift of the warp fBm — the surface CHURNS and reshapes as the warp evolves. This is the
     *  dynamism knob (the previous value was far too slow); raise for livelier roil, lower for calmer. */
    private const val WARP_FLOW: Double = 0.05

    /** fBm output peaks near ±0.5; this normalizes the wave-keyed shade (= SEA_HEIGHT × that peak). */
    private const val TOTAL_AMP: Double = SEA_HEIGHT * 0.5

    /** Per-octave transform: ROTATE (cos 0.8 / sin 0.6 — a pure rotation, det 1, ≈37°) then scale ×2
     *  (lacunarity). The rotation is THE anti-grid trick: it stops the noise lattice axes lining up
     *  across octaves, which is what made the summed field look gridlike. */
    private const val FBM_ROT_C: Double = 0.80
    private const val FBM_ROT_S: Double = 0.60
    private val SEA_BASE_INV_LEN = 1.0 / SEA_BASE_LEN

    // --- Eyes: a procedural noise field across the whole sea -----------------------------
    //
    // The sea is carpeted with eyes. A world-anchored lattice ([EYE_SPACING]) is thresholded against
    // a smooth VALUE-NOISE density field ([eyeDensity]) — not a flat per-cell probability — so the
    // eyes gather into organic clumps and leave voids rather than spreading uniformly. Each present
    // eye is jittered off the grid and blinks on its own phase and rate. Being world-anchored, the
    // field flows past you with the sea (no spawn state, no ticking — recomputed each frame from the
    // hash). The eyes are submerged (drawn behind the translucent waves) and their alpha is
    // compensated for the wave grid's occlusion, so they read at a consistent dim glimmer whether
    // they sit under the near waves or far out on the flat backdrop — covering the whole sea.

    /** Edge length (blocks) of an eye, in flat-sea world units. */
    private const val EYE_SIZE: Double = 4.0

    /** World spacing of the eye lattice (blocks). */
    private const val EYE_SPACING: Double = 21.0

    /** Wavelength (blocks) of the density value-noise — the size of the eye clumps/voids. */
    private const val EYE_NOISE_SCALE: Double = 78.0

    /** The density noise is contrast-curved through this band: cells where the noise is below
     *  [EYE_CLUMP_LO] are void, above [EYE_CLUMP_HI] are a full clump, a gradient between. */
    private const val EYE_CLUMP_LO: Double = 0.44
    private const val EYE_CLUMP_HI: Double = 0.72

    /** How far an eye is jittered off its lattice point, as a fraction of [EYE_SPACING], so the
     *  field never reads as a regular grid. */
    private const val EYE_JITTER: Double = 0.85

    /** Horizontal radius (blocks) the field covers, and the width over which it feathers out at
     *  that rim. Reaches well past the wavy foreground so eyes carpet the whole visible sea. */
    private const val EYE_FIELD_R: Double = 460.0
    private const val EYE_FIELD_FADE: Double = 120.0

    /** The density noise drifts through the world at this velocity (blocks/second), so the clumps
     *  migrate and eyes wink in and out over time rather than sitting in a fixed pattern. */
    private const val EYE_DRIFT_X: Double = 4.0
    private const val EYE_DRIFT_Z: Double = 2.6

    /** Density band over which an eye fades in/out as the drifting noise crosses its threshold — so
     *  eyes appear and vanish smoothly instead of popping. */
    private const val EYE_PRESENCE_BAND: Double = 0.13

    /** Animation: 4 stacked 16×16 frames (wide-open, open, half, closed). Per-eye phase keeps them
     *  out of lockstep; the frame duration is driven by a smooth noise field (see below). */
    private const val EYE_FRAME_MS: Long = 150L
    private const val EYE_FRAMES: Int = 4

    /** The per-eye blink rate comes from a smooth value-noise field sampled at the eye's position
     *  ([EYE_RATE_NOISE_SCALE] = its wavelength), so the sea has coherent regions of fast- and
     *  slow-blinking eyes rather than uniform-random rates. The noise maps the frame duration
     *  across [EYE_RATE_FAST]×..[EYE_RATE_SLOW]× of [EYE_FRAME_MS]. Sampled time-independently so
     *  each eye's duration is constant and the blink cycle never jumps. */
    private const val EYE_RATE_NOISE_SCALE: Double = 130.0
    private const val EYE_RATE_FAST: Double = 0.45
    private const val EYE_RATE_SLOW: Double = 2.4

    private const val EYE_MAX_ALPHA: Float = 0.9f

    /** Blocks the eyes sit below the sea-level — beneath the waves (larger than the wave amplitude
     *  ~17 so they never poke through a trough). The translucent wave grid drawn in front is what
     *  makes them read as submerged. */
    private const val EYE_SUBMERGE: Double = 22.0

    // --- Tentacles -----------------------------------------------------------------------
    //
    // A sparse world-anchored lattice of writhing tentacles — the artist's [TentacleModel] posed by
    // [TentacleAnimation] — rooted at the submerged eye plane and rising out of the sea. They appear
    // only in the eye-VOID patches (where the eye density field is low), so eyes and tentacles never
    // share a spot; random yaw, scale, and animation rate per lattice cell. Rendered as REAL models
    // ([renderTentacles]) in the SKY pass, dome-projected by [DomeTentacleConsumer] — so they escape
    // the world far-clip plane and the distant ones reach the horizon.

    private const val TENT_SPACING: Double = 40.0
    private const val TENT_JITTER: Double = 0.9
    private const val TENT_FILL: Double = 0.65             // fraction of clump cells that hold one (center density)

    /** Max distance a jitter can move a tentacle off its cell centre (both axes at the extreme). Used
     *  by the placement loop's cheap pre-reject as a safety margin so it never skips a cell that the
     *  jitter could pull inside the density radius. */
    private val TENT_JITTER_MAG: Double = 0.5 * TENT_SPACING * TENT_JITTER * Math.sqrt(2.0)

    /** Extra half-angle (radians, ~20°) added to the real view-frustum cone when culling off-screen
     *  sky-pass geometry (tentacles, sea grid, backdrop) — covers tentacle sway, dynamic-FOV change,
     *  per-quad span, and float slop, so anything peeking in at the screen edge is NEVER culled
     *  (off-screen culling must be invisible). */
    private const val TENT_CULL_MARGIN: Double = 0.35

    // Density is a straight line from the center out, then a faint floor. Three independent knobs:
    //   TENT_FILL          = density right at the camera (the center value).
    //   TENT_DENSITY_FAR_R = how FAR the field thins over — SMALLER pulls the thinning inward, so the
    //                        whole mid-field gets sparser; LARGER spreads the same drop over more
    //                        distance, leaving the mid denser. This is the "how much in the middle" knob.
    //   TENT_FAR_FILL      = the faint far FLOOR (× TENT_FILL) it settles to past TENT_DENSITY_FAR_R,
    //                        out to the rim — a thin scatter of horizon giants. ~0 = no far ones.
    // density(d) = TENT_FILL × (TENT_FAR_FILL + (1 − TENT_FAR_FILL) × max(0, 1 − (d − TENT_DENSE_R)/(TENT_DENSITY_FAR_R − TENT_DENSE_R)))
    private const val TENT_DENSE_R: Double = 0.0
    private const val TENT_FAR_FILL: Double = 0.007

    /** See the density knob notes above — this is the thinning distance (and is decoupled from the
     *  field reach [TENT_FIELD_R], so changing reach doesn't reshape the density). */
    private const val TENT_DENSITY_FAR_R: Double = 750.0

    /** Desired horizontal reach (blocks). Large so the far tentacles sit out near where the sea meets
     *  the sky and interrupt the horizon — the dome render frees them from the world far-clip plane,
     *  so this can extend well past it. Beyond [TENT_DENSITY_FAR_R] it's a sparse tail of far giants. */
    private const val TENT_FIELD_R: Double = 6000.0
    private const val TENT_FIELD_FADE: Double = 180.0

    /** Voidness curve: tentacles fade in as the eye-density falls below [TENT_VOID_HI] and are full
     *  below [TENT_VOID_LO], so they smoothly cede ground as eye clumps drift over them. */
    private const val TENT_VOID_LO: Double = 0.12
    private const val TENT_VOID_HI: Double = 0.34

    // Tentacles cluster in drifting CLOUDS (like the eyes): a value-noise field, contrast-curved,
    // decides where clusters sit; as it drifts the clusters migrate, so tentacles rise and sink over
    // time. The clouds are large and densely filled so each reads as a thicket, not one or two.
    private const val TENT_NOISE_SCALE: Double = 170.0
    private const val TENT_CLUMP_LO: Double = 0.40
    private const val TENT_CLUMP_HI: Double = 0.62
    private const val TENT_DRIFT_X: Double = 2.5
    private const val TENT_DRIFT_Z: Double = 1.7
    private const val TENT_PRESENCE_BAND: Double = 0.16

    /** Alpha fades over only this much of `life` at each end of the cycle — so a tentacle is fully
     *  opaque while it rises and sinks, and the fade is just a quick dissolve at the very start and
     *  very end. */
    private const val TENT_FADE: Double = 0.15

    /** Blocks a tentacle sinks straight down as it leaves (life → 0) — large enough to drop the
     *  whole column out of view while its alpha fades, so it withdraws into the sea instead of
     *  popping out of existence. */
    private const val TENT_SINK: Double = 55.0

    // Size scales with DISTANCE, not uniformly: tentacles near the camera are modest, and they grow
    // toward the field rim into horizon-height giants. So the distant ones are the large
    // horizon-clipping silhouettes (megalophobia from afar), while the near sea isn't a wall.

    /** Scale of a tentacle right under the camera (the model's base→tip is ~[TENT_MODEL_HEIGHT]
     *  blocks at scale 1, so this is ≈34 blocks tall) — the floor of the size ramp, kept big enough
     *  that even the nearest columns read as substantial from the deep-sea vantage. */
    private const val TENT_NEAR_SCALE: Double = 6.0

    /** Blocks from the planted base to the tip at scale 1 — used to size the far tentacles so their
     *  tips reach eye level (and thus the horizon) from the current camera height. */
    private const val TENT_MODEL_HEIGHT: Double = 5.6

    /** Far tentacles are sized so their tip reaches this fraction of the way to eye level — well
     *  over 1 so they tower clear above the horizon line. Capped by [TENT_FAR_SCALE_MAX] so a very
     *  high vantage doesn't ask for absurd columns. */
    private const val TENT_HORIZON_FRAC: Double = 1.9
    private const val TENT_FAR_SCALE_MAX: Double = 105.0

    /** SIZE ramp radii (its own LINEAR ramp, separate from both density and rate): a tentacle is
     *  [TENT_NEAR_SCALE] within [TENT_SIZE_NEAR_R], then grows steadily to the far cap by
     *  [TENT_SIZE_FAR_R]. NEAR_R holds just the immediate area at near-scale; the mid grows from there
     *  toward horizon-height (NEAR_R=0 made the mid too big, 600 made it too small — ~300 is medium). */
    private const val TENT_SIZE_NEAR_R: Double = 300.0
    private const val TENT_SIZE_FAR_R: Double = 2400.0

    /** Distant tentacles fade to pure black: full brightness within [TENT_DARK_NEAR_R], crushed to a
     *  black silhouette by [TENT_DARK_FAR_R]. Both the body AND the emissive tip fade (the glow dims
     *  out too). Only the color is scaled, not alpha, so the body stays a solid silhouette. */
    private const val TENT_DARK_NEAR_R: Double = 400.0
    private const val TENT_DARK_FAR_R: Double = 1100.0

    /** Animation-rate multiplier, distance-driven AT BIRTH then FROZEN per tentacle (see
     *  [tentRateCache]): a column born beyond [TENT_RATE_FAR_R] writhes slowly ([TENT_RATE_FAR]); one
     *  born at the center writhes faster ([TENT_RATE_NEAR]). Freezing it at birth is essential — the
     *  phase is `wallClockMs × rate`, so a *live* distance-driven rate would retroactively rescale all
     *  elapsed time as the player moved and lurch. Frozen, it advances smoothly forever.
     *
     *  This is a SEPARATE ramp from size ([TENT_RATE_NEAR_R]→[TENT_RATE_FAR_R]) — size and rate must
     *  be decoupled because the mid-field wants to be small AND slow, which one shared ramp can't do
     *  (small=near=fast on a shared ramp). RATE_FAR_R is small so the rate reaches slow by the mid. */
    private const val TENT_RATE_NEAR: Double = 0.5
    private const val TENT_RATE_FAR: Double = 0.1
    private const val TENT_RATE_NEAR_R: Double = 0.0
    private const val TENT_RATE_FAR_R: Double = 600.0
    private const val TENT_ALPHA: Float = 1.0f

    // --- Tentacle diffuse ----------------------------------------------------------------------
    // The dome render uses position_color_tex, which has NO lighting stage — so the model comes out
    // flat-lit (the entity pipeline's per-face diffuse is bypassed). Re-add it here: shade each
    // vertex by its surface normal against a fixed key-light (half-Lambert, floored at TENT_AMBIENT
    // so shadows don't crush to black) and MULTIPLY the texture by it. Lit faces keep the full
    // texture; faces angled away darken — that gradient is the column's round form.
    private const val TENT_AMBIENT: Float = 0.35f
    /** Key-light direction (world space, normalized). Mostly sideways so a vertical column gets a
     *  distinct lit/shadow side; a little from above so tops catch more. Flip the sign if the lit
     *  side comes out on the wrong face. */
    private const val TENT_LIGHT_X: Float = 0.645f
    private const val TENT_LIGHT_Y: Float = 0.484f
    private const val TENT_LIGHT_Z: Float = 0.591f

    // --- Dome tables ---------------------------------------------------------------------
    //
    // Sector angles are constant. The ring tables depend on the live foreshorten depth, so they
    // are rebuilt each frame by [buildRingTables]; cheap (RINGS trig ops) and what makes the sea
    // recede/loom with camera altitude.

    private val sectorCos = DoubleArray(SECTORS)
    private val sectorSin = DoubleArray(SECTORS)
    private val ringR = DoubleArray(RINGS)          // flat-plane radius
    private val ringCosTheta = DoubleArray(RINGS)   // cos of screen depression
    private val ringSinTheta = DoubleArray(RINGS)   // sin of screen depression
    private val ringTrueDist = DoubleArray(RINGS)   // sqrt(r² + depth²); ringTrueDist[0]==depth, used in the render guard
    private val ringAlpha = FloatArray(RINGS)       // horizon feather

    /** Live foreshorten depth for the current frame (set in [renderSky]); the vertical drop from
     *  the eye to the notional flat sea, used to build each vertex's displaced world direction. */
    private var seaDepth = 0.0

    // Per-frame off-screen cull (set in [renderSky]). The sky pass draws backdrop/sea/eyes/tentacles a
    // full 360° around the camera, but only the FOV is on screen — so geometry whose direction is
    // outside the view cone is skipped. Everything here is dome-projected (|p| = domeR), so a point is
    // "in the cone" when dot(cullLk, p) ≥ cullCos·domeR. cullCos comes from the real projection matrix.
    private var cullLkX = 0.0
    private var cullLkY = 0.0
    private var cullLkZ = 0.0
    private var cullCos = -1.0

    init {
        for (s in 0 until SECTORS) {
            val a = s.toDouble() / SECTORS * 2.0 * Math.PI
            sectorCos[s] = cos(a)
            sectorSin[s] = sin(a)
        }
    }

    /**
     * (Re)build the ring tables for the given foreshorten [depth]. Ring 0 is the true nadir point
     * (r = 0, straight down): all its sectors collapse to one spot, so the quad band to ring 1
     * degenerates into a triangle fan that *closes the cap* — without it the innermost ring sits
     * a couple of degrees off vertical and leaves a circular hole directly below the player.
     * Rings 1..N-1 are geometric from [RING_INNER_R] out to the horizon radius — dense near the
     * nadir and (in screen-angle terms) near the horizon too, matching real foreshortening.
     */
    private fun buildRingTables(depth: Double) {
        val rMax = depth / tan(Math.toRadians(HORIZON_DEG))
        val growth = Math.pow(rMax / RING_INNER_R, 1.0 / (RINGS - 2))
        var r = RING_INNER_R
        for (i in 0 until RINGS) {
            val rr = when (i) {
                0 -> 0.0
                RINGS - 1 -> rMax
                else -> r
            }
            ringR[i] = rr
            val theta = atan2(depth, rr)   // screen depression, radians
            ringCosTheta[i] = cos(theta)
            ringSinTheta[i] = sin(theta)
            ringTrueDist[i] = sqrt(rr * rr + depth * depth)
            ringAlpha[i] = smoothstep(0.0, HORIZON_FEATHER_DEG, Math.toDegrees(theta)).toFloat()
            if (i >= 1) r *= growth
        }
    }

    /** Kept for the client bootstrap to call. The eye field is procedural (recomputed per frame
     *  from the world hash), so there is no spawn/expire state to tick. */
    fun init() {}

    // --- Writhe ---------------------------------------------------------------------------

    /**
     * Vertical displacement of the surface at world (`wx`, `wz`) and time `t` seconds. Slow
     * travelling swells + a direction-rotating twist + a beating detuned pair, all fed through
     * a low-frequency domain warp so nothing reads as a clean sine. World-space so the swell is
     * pinned to the world and flows beneath you as you move.
     *
     * `detail` (0..1) scales only the short detune pair — the components that shimmer when they
     * shrink to sub-pixel far out. The long swells (which stay screen-resolvable at distance) are
     * always full, so fading `detail` to 0 leaves the distant sea wavy but shimmer-free.
     */
    private fun surfaceHeight(wx: Double, wz: Double, t: Double, detail: Double): Double {
        // Slow overall advection: the whole field travels (organic drift, not a directional swell).
        val bx = wx * SEA_BASE_INV_LEN + t * ADV_X
        val bz = wz * SEA_BASE_INV_LEN + t * ADV_Z
        // Domain warp (IQ `fbm(p + fbm(p))`) that DRIFTS ACTIVELY: the warp evolving stirs the height
        // domain so the surface churns and reshapes (the dynamism). The two warp components drift in
        // opposite axes so the curl swirls in 2-D rather than sliding one way.
        val qx = fbm(bx + t * WARP_FLOW, bz, WARP_OCTAVES, 1.0)
        val qz = fbm(bx, bz - t * WARP_FLOW, WARP_OCTAVES, 1.0)
        val h = fbm(bx + WARP_GAIN * qx, bz + WARP_GAIN * qz, SEA_OCTAVES, detail)
        return h * SEA_HEIGHT
    }

    /**
     * Fractional Brownian motion in [-~0.5, ~0.5]: [octaves] of [valueNoise], each ROTATED (cos
     * [FBM_ROT_C] / sin [FBM_ROT_S]) and doubled in frequency, with amplitude halved. The per-octave
     * rotation decorrelates the noise lattice so the sum never reads as a grid — the whole point.
     * [detail] (0..1) fades the finest, shimmer-prone octave with distance (the frequency LOD).
     */
    private fun fbm(x0: Double, z0: Double, octaves: Int, detail: Double): Double {
        var x = x0
        var z = z0
        var amp = 0.5
        var sum = 0.0
        for (i in 0 until octaves) {
            val a = if (i == octaves - 1) amp * detail else amp
            sum += a * (valueNoise(x, z, 70 + i) - 0.5)
            val rx = (FBM_ROT_C * x + FBM_ROT_S * z) * 2.0
            val rz = (-FBM_ROT_S * x + FBM_ROT_C * z) * 2.0
            x = rx
            z = rz
            amp *= FBM_GAIN
        }
        return sum
    }

    // --- Render ---------------------------------------------------------------------------

    fun renderSky(poseStack: PoseStack, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (level.dimension() != YgannAbyss.LEVEL_KEY) return

        val cam = mc.gameRenderer.mainCamera.position
        // Client wall-clock seconds — smooth regardless of server TPS (see class docs).
        val t = Util.getMillis() / 1000.0

        val farPlane = mc.options.effectiveRenderDistance * FAR_PLANE_PER_CHUNK
        val domeR = (farPlane * DOME_RADIUS_FRAC).coerceAtMost(DOME_RADIUS_MAX)

        // Foreshorten depth tracks camera height above the sea, so the sea is world-anchored
        // vertically (recedes as you climb, looms as you fall to your death) instead of being glued
        // to the eye. The sea sits two blocks above the void kill plane (`minBuildHeight − 64`).
        // The depth ceiling tracks the live dome radius (the nadir vertex must stay inside it) so it
        // only clamps at extreme altitude — a fixed ceiling clamped at a low y and made the sea
        // follow the camera vertically above it.
        val seaWorldY = level.minBuildHeight - 64.0 + SEA_ABOVE_KILLPLANE
        val depthMax = (domeR * DEPTH_DOME_FRAC).coerceAtMost(DEPTH_ABS_MAX)
        val depth = (cam.y - seaWorldY).coerceIn(DEPTH_MIN, depthMax)
        seaDepth = depth
        buildRingTables(depth)

        // View-cone for off-screen culling, from the ACTUAL projection matrix (real FOV incl. aspect +
        // dynamic FOV): tan(hFov/2)=1/m00, tan(vFov/2)=1/m11; the frustum corner is the widest in-view
        // angle. A generous margin ([TENT_CULL_MARGIN]) absorbs animated sway, dynamic-FOV change, and
        // the small per-quad span, so on-screen geometry is never wrongly culled.
        val proj = RenderSystem.getProjectionMatrix()
        val tanH = 1.0 / Math.abs(proj.m00().toDouble())
        val tanV = 1.0 / Math.abs(proj.m11().toDouble())
        cullCos = Math.cos(Math.atan(Math.sqrt(tanH * tanH + tanV * tanV)) + TENT_CULL_MARGIN)
        val look = mc.gameRenderer.mainCamera.lookVector
        cullLkX = look.x().toDouble()
        cullLkY = look.y().toDouble()
        cullLkZ = look.z().toDouble()

        RenderSystem.enableBlend()
        RenderSystem.blendFunc(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        )
        RenderSystem.depthMask(false)
        RenderSystem.disableCull()
        RenderSystem.disableDepthTest()
        RenderSystem.setShader { GameRenderer.getPositionColorTexShader() }

        val pose = poseStack.last().pose()
        // Layer order matters: the eyes are drawn BETWEEN the flat backdrop and the wave grid, so
        // the (slightly translucent) waves render in front of them — the eyes are genuinely
        // *submerged*, glimpsed dimly through the dark water rather than sitting on top of it.
        bindSeaTexture()
        drawBackdrop(pose, cam.x, cam.z, domeR, t)
        drawEyes(pose, cam.x, cam.z, domeR, t)
        bindSeaTexture()
        drawForeground(pose, cam.x, cam.z, domeR, t)
        // Tentacles — real animated models, dome-projected here in the sky pass so they reach the
        // horizon like the sea (free of the world far-clip plane).
        renderTentacles(pose, cam.x, cam.y, cam.z, domeR, t)

        RenderSystem.enableDepthTest()
        RenderSystem.enableCull()
        RenderSystem.depthMask(true)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
    }

    private fun bindSeaTexture() {
        RenderSystem.setShaderTexture(0, SEA_TEX)
        // World-space UVs run far outside [0,1] so the source must tile; SimpleTexture defaults to
        // CLAMP, which would smear one edge texel across the whole sheet. Force REPEAT and LINEAR
        // (the latter tames moiré on the tiled noise toward the horizon). Cheap to set each frame
        // and survives the texture object being rebuilt on a resource reload.
        val seaTex = Minecraft.getInstance().textureManager.getTexture(SEA_TEX)
        RenderSystem.bindTexture(seaTex.id)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
    }

    // --- Backdrop: flat polar dome to the horizon ----------------------------------------

    /** One ring's baked per-vertex data (pose-space position, colour, uv), indexed by sector. */
    private class Ring {
        val x = FloatArray(SECTORS); val y = FloatArray(SECTORS); val z = FloatArray(SECTORS)
        val r = FloatArray(SECTORS); val g = FloatArray(SECTORS); val b = FloatArray(SECTORS); val a = FloatArray(SECTORS)
        val u = FloatArray(SECTORS); val v = FloatArray(SECTORS)
    }

    private val ringA = Ring()
    private val ringB = Ring()

    private fun drawBackdrop(pose: Matrix4f, camX: Double, camZ: Double, domeR: Double, t: Double) {
        val ts = Tesselator.getInstance()
        val bb = ts.builder
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX)
        val thr = cullCos * domeR
        var inner = ringA
        var outer = ringB
        fillBackdropRing(inner, 0, camX, camZ, domeR, t)
        for (ri in 0 until RINGS - 1) {
            fillBackdropRing(outer, ri + 1, camX, camZ, domeR, t)
            for (s in 0 until SECTORS) {
                val sNext = (s + 1) % SECTORS
                // Off-screen cull: skip rear sectors (behind the camera hides nothing visible).
                if (cullLkX * inner.x[s] + cullLkY * inner.y[s] + cullLkZ * inner.z[s] < thr &&
                    cullLkX * inner.x[sNext] + cullLkY * inner.y[sNext] + cullLkZ * inner.z[sNext] < thr &&
                    cullLkX * outer.x[sNext] + cullLkY * outer.y[sNext] + cullLkZ * outer.z[sNext] < thr &&
                    cullLkX * outer.x[s] + cullLkY * outer.y[s] + cullLkZ * outer.z[s] < thr
                ) continue
                emit(bb, pose, inner, s)
                emit(bb, pose, inner, sNext)
                emit(bb, pose, outer, sNext)
                emit(bb, pose, outer, s)
            }
            val tmp = inner; inner = outer; outer = tmp
        }
        ts.end()
    }

    private fun fillBackdropRing(ring: Ring, ri: Int, camX: Double, camZ: Double, domeR: Double, t: Double) {
        val r = ringR[ri]
        val alpha = ringAlpha[ri]
        val fogMix = (1f - alpha)   // grey toward fog at the horizon rim
        val horizY = (-ringSinTheta[ri] * domeR).toFloat()
        val horizR = ringCosTheta[ri] * domeR
        // Constant flat shade. The backdrop is the all-far field (the near sea is the opaque
        // foreground in front of it), so a wave-keyed shade here would just under-sample the swell
        // and shimmer. A flat dark value — greyed toward fog at the horizon — reads as the calm
        // distant sea and matches the foreground's LOD-faded shade at the seam.
        val shade = 0.5f
        val cr = lerp(shade, FOG_R, fogMix)
        val cg = lerp(shade, FOG_G, fogMix)
        val cb = lerp(shade, FOG_B, fogMix)
        for (s in 0 until SECTORS) {
            val sc = sectorCos[s]
            val ss = sectorSin[s]
            ring.x[s] = (horizR * sc).toFloat()
            ring.y[s] = horizY
            ring.z[s] = (horizR * ss).toFloat()
            ring.r[s] = cr
            ring.g[s] = cg
            ring.b[s] = cb
            ring.a[s] = alpha
            ring.u[s] = ((camX + r * sc) / TEX_SCALE).toFloat()
            ring.v[s] = ((camZ + r * ss) / TEX_SCALE).toFloat()
        }
    }

    private fun emit(bb: BufferBuilder, pose: Matrix4f, ring: Ring, s: Int) {
        bb.vertex(pose, ring.x[s], ring.y[s], ring.z[s])
            .color(ring.r[s], ring.g[s], ring.b[s], ring.a[s])
            .uv(ring.u[s], ring.v[s])
            .endVertex()
    }

    // --- Foreground: world-anchored wave grid (parallax-correct) --------------------------
    //
    // The dome's vertices sample the world at *camera-relative* offsets (`camX + r·dir`), so every
    // vertex holds a fixed direction from the eye — the surface has zero translational parallax and
    // feels glued to the camera (the texture only looked world-pinned because its UV image slides
    // over that fixed mesh). This grid instead pins its vertices to *fixed world positions*: as the
    // camera moves, each vertex's direction changes, so the whole surface — waves and all — flows
    // past you. The grid is snapped to its cell size so that when the camera crosses a cell the grid
    // shifts by exactly one cell, leaving the (world-sampled) image seamless. It projects to the
    // dome radius like the backdrop, so it never clips, and fades into the backdrop at its rim.

    /** World cell size of the foreground grid (blocks). Sized to sample the FINEST fBm octave
     *  (≈ [SEA_BASE_LEN]/2^([SEA_OCTAVES]−1) ≈ 25 blk) at ~3 points/wavelength → ≈9. Smaller waves
     *  need a finer cell, which costs vertices — the density/perf trade lives here and in [FG_HALF]. */
    private const val FG_CELL: Double = 9.0

    /** Half the grid span in cells. The grid covers ±`FG_HALF·FG_CELL` = ±576 blocks (the reach kept
     *  constant). Perf knob: (2·FG_HALF+1)² `surfaceHeight` calls (several `valueNoise` each) per frame
     *  — ~16.6k vertices here, still far below the ~37k that ran slow. */
    private const val FG_HALF: Int = 64

    /** Blocks over which the grid edge feathers (alpha + wave-amplitude) into the flat backdrop. */
    private const val FG_FADE_WIDTH: Double = 48.0

    /** Interior opacity of the wave grid. Below 1 so the submerged eyes drawn behind it glimmer up
     *  through the dark water. The 1−this fraction reveals the flat backdrop (also dark sea) behind,
     *  so the sea still reads as solid. */
    private const val FG_OPACITY: Float = 0.8f

    // Wave-frequency level-of-detail. The short detune waves (~18–21 block wavelength) shrink to
    // sub-pixel on screen far out and shimmer as they animate; the long swells (~33–78 block) stay
    // many pixels wide even far away and don't. So rather than damping *all* relief with distance
    // (which left the far sea too calm), only the short detune pair is faded out past
    // [DETAIL_NEAR]→[DETAIL_FAR] — the long swells keep full amplitude, so the distant sea stays
    // genuinely wavy without the shimmer.
    private const val DETAIL_NEAR: Double = 150.0   // full detune detail within this radius
    private const val DETAIL_FAR: Double = 360.0    // detune detail gone by here (long swells remain)

    private val FG_VERTS = 2 * FG_HALF + 1
    private val FG_FADE_END = FG_HALF * FG_CELL              // inscribed-circle rim radius
    private val FG_FADE_START = FG_FADE_END - FG_FADE_WIDTH

    /** One grid row's baked per-vertex data, indexed by column. */
    private class GridRow(n: Int) {
        val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n); val a = FloatArray(n)
        val u = FloatArray(n); val v = FloatArray(n)
    }

    private val fgRowA = GridRow(FG_VERTS)
    private val fgRowB = GridRow(FG_VERTS)

    private fun drawForeground(pose: Matrix4f, camX: Double, camZ: Double, domeR: Double, t: Double) {
        val depth = seaDepth
        // Snap the grid origin to the cell lattice so the world-anchored vertices only ever shift by
        // whole cells as the camera moves — the seam-free condition.
        val anchorX = Math.floor(camX / FG_CELL) * FG_CELL
        val anchorZ = Math.floor(camZ / FG_CELL) * FG_CELL

        val ts = Tesselator.getInstance()
        val bb = ts.builder
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX)

        val thr = cullCos * domeR
        var prev = fgRowA
        var cur = fgRowB
        fillGridRow(prev, 0, anchorX, anchorZ, camX, camZ, domeR, depth, t)
        for (j in 0 until FG_VERTS - 1) {
            fillGridRow(cur, j + 1, anchorX, anchorZ, camX, camZ, domeR, depth, t)
            for (i in 0 until FG_VERTS - 1) {
                // Skip quads entirely past the fade rim (all four corners fully transparent).
                if (prev.a[i] == 0f && prev.a[i + 1] == 0f && cur.a[i + 1] == 0f && cur.a[i] == 0f) continue
                // Off-screen cull: skip quads whose four (dome-projected) corners are all outside the
                // view cone — the ~half of the 360° grid behind the camera. Look-preserving.
                if (cullLkX * prev.x[i] + cullLkY * prev.y[i] + cullLkZ * prev.z[i] < thr &&
                    cullLkX * prev.x[i + 1] + cullLkY * prev.y[i + 1] + cullLkZ * prev.z[i + 1] < thr &&
                    cullLkX * cur.x[i + 1] + cullLkY * cur.y[i + 1] + cullLkZ * cur.z[i + 1] < thr &&
                    cullLkX * cur.x[i] + cullLkY * cur.y[i] + cullLkZ * cur.z[i] < thr
                ) continue
                emitGridVert(bb, pose, prev, i)
                emitGridVert(bb, pose, prev, i + 1)
                emitGridVert(bb, pose, cur, i + 1)
                emitGridVert(bb, pose, cur, i)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        ts.end()
    }

    private fun fillGridRow(
        row: GridRow, j: Int, anchorX: Double, anchorZ: Double,
        camX: Double, camZ: Double, domeR: Double, depth: Double, t: Double,
    ) {
        val gz = anchorZ + (j - FG_HALF) * FG_CELL
        for (i in 0 until FG_VERTS) {
            val gx = anchorX + (i - FG_HALF) * FG_CELL
            val ddx = gx - camX
            val ddz = gz - camZ
            val rH = sqrt(ddx * ddx + ddz * ddz)
            // Rim feather: 1 in the interior, 0 past FG_FADE_END. Tapers the wave amplitude to flat
            // and the alpha to zero so the grid melts into the (flat) backdrop with no hard seam.
            val fade = (1.0 - smoothstep(FG_FADE_START, FG_FADE_END, rH))
            // Frequency LOD: fade out only the short detune detail far out (it shimmers); the long
            // swells stay full so the distant sea is still wavy.
            val detail = 1.0 - smoothstep(DETAIL_NEAR, DETAIL_FAR, rH)
            val hRaw = surfaceHeight(gx, gz, t, detail)
            val dy = hRaw * fade - depth
            val scale = domeR / sqrt(ddx * ddx + dy * dy + ddz * ddz)
            row.x[i] = (ddx * scale).toFloat()
            row.y[i] = (dy * scale).toFloat()
            row.z[i] = (ddz * scale).toFloat()
            // Full-contrast shade keyed to the (frequency-LOD'd) height: far out only the long swells
            // remain, which the screen resolves, so the shade reads the waves without shimmering.
            val shade = (0.5 + 0.62 * (hRaw / TOTAL_AMP)).toFloat().coerceIn(0.12f, 1.0f)
            row.r[i] = shade
            row.g[i] = shade
            row.b[i] = shade
            row.a[i] = (fade * FG_OPACITY).toFloat()
            row.u[i] = (gx / TEX_SCALE).toFloat()
            row.v[i] = (gz / TEX_SCALE).toFloat()
        }
    }

    private fun emitGridVert(bb: BufferBuilder, pose: Matrix4f, row: GridRow, i: Int) {
        bb.vertex(pose, row.x[i], row.y[i], row.z[i])
            .color(row.r[i], row.g[i], row.b[i], row.a[i])
            .uv(row.u[i], row.v[i])
            .endVertex()
    }

    // --- Eyes ----------------------------------------------------------------------------

    /** Half-texel inset (texture is 64 px tall) so a quad's frame band samples strictly inside
     *  its own 16-px frame under NEAREST filtering, never the neighbour's edge row. */
    private const val EYE_V_INSET: Float = 0.5f / 64f

    /** Reusable scratch for [projectSubmergedEye] — one render thread, read immediately. */
    private val projScratch = DoubleArray(3)

    /**
     * Project a world point onto the **submerged** eye plane — a flat sheet [EYE_SUBMERGE] blocks
     * below the sea-level, beneath the waves. No wave displacement (the eye lies under them), so the
     * eye renders flat and a touch toward the nadir relative to the surface above it; the wave grid
     * drawn in front (translucent) is what makes it read as glimpsed below the water. Result written
     * to [projScratch].
     */
    private fun projectSubmergedEye(wx: Double, wz: Double, camX: Double, camZ: Double, domeR: Double) {
        val ddx = wx - camX
        val ddz = wz - camZ
        val dy = -(seaDepth + EYE_SUBMERGE)
        val scale = domeR / sqrt(ddx * ddx + dy * dy + ddz * ddz)
        projScratch[0] = ddx * scale
        projScratch[1] = dy * scale
        projScratch[2] = ddz * scale
    }

    private fun drawEyes(pose: Matrix4f, camX: Double, camZ: Double, domeR: Double, t: Double) {
        RenderSystem.setShaderTexture(0, EYE_TEX)
        val ts = Tesselator.getInstance()
        val bb = ts.builder
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX)

        val now = Util.getMillis()
        val half = EYE_SIZE * 0.5
        val cx0 = Math.floor((camX - EYE_FIELD_R) / EYE_SPACING).toInt()
        val cx1 = Math.floor((camX + EYE_FIELD_R) / EYE_SPACING).toInt()
        val cz0 = Math.floor((camZ - EYE_FIELD_R) / EYE_SPACING).toInt()
        val cz1 = Math.floor((camZ + EYE_FIELD_R) / EYE_SPACING).toInt()
        val fieldR2 = EYE_FIELD_R * EYE_FIELD_R
        // The visible glimmer we want every eye to settle at, regardless of how much sea is in
        // front of it. Eyes under the wave grid are dimmed by it; eyes out on the bare backdrop
        // aren't — so each eye's drawn alpha is divided back up by the grid coverage over it.
        val targetVisible = EYE_MAX_ALPHA * (1f - FG_OPACITY)

        for (cz in cz0..cz1) {
            for (cx in cx0..cx1) {
                // Jittered world position so the field isn't a visible grid.
                val ex = cx * EYE_SPACING + (hash01(cx, cz, 1) - 0.5) * EYE_SPACING * EYE_JITTER
                val ez = cz * EYE_SPACING + (hash01(cx, cz, 2) - 0.5) * EYE_SPACING * EYE_JITTER

                // Value-noise density (drifting over time) → organic clumps and voids that migrate.
                // `presence` fades the eye in/out smoothly as the drifting density crosses this
                // cell's threshold, so the pattern shifts and eyes wink in and out.
                val cellRand = hash01(cx, cz, 0)
                val presence = smoothstep(cellRand, cellRand + EYE_PRESENCE_BAND, eyeDensity(ex, ez, t))
                if (presence <= 0.0) continue

                val ddx = ex - camX
                val ddz = ez - camZ
                val d2 = ddx * ddx + ddz * ddz
                if (d2 > fieldR2) continue
                val dist = sqrt(d2)

                // Occlusion compensation: how opaque the wave grid is over this eye (full inside the
                // field, fading out at its rim, zero past it). Dividing the target glimmer by the
                // transmitted fraction makes near (grid-covered) and far (bare-backdrop) eyes match.
                val gridOver = FG_OPACITY * (1.0 - smoothstep(FG_FADE_START, FG_FADE_END, dist)).toFloat()
                val edgeFade = (1.0 - smoothstep(EYE_FIELD_R - EYE_FIELD_FADE, EYE_FIELD_R, dist)).toFloat()
                val perEye = 0.5f + hash01(cx, cz, 5).toFloat() * 0.5f
                val alpha = (targetVisible / (1f - gridOver) * edgeFade * perEye * presence.toFloat()).coerceAtMost(1f)
                if (alpha <= 0.01f) continue

                // Per-eye blink: rate from a smooth spatial noise field (coherent fast/slow regions),
                // phase offset random so neighbours don't pulse in unison.
                val rateNoise = valueNoise(ex / EYE_RATE_NOISE_SCALE, ez / EYE_RATE_NOISE_SCALE, 11)
                val frameDurMs = EYE_FRAME_MS * (EYE_RATE_FAST + rateNoise * (EYE_RATE_SLOW - EYE_RATE_FAST))
                val phaseFrames = (hash01(cx, cz, 4) * EYE_FRAMES).toInt()
                val frame = (((now / frameDurMs).toLong() + phaseFrames) % EYE_FRAMES).toInt()
                val v0 = frame.toFloat() / EYE_FRAMES + EYE_V_INSET
                val v1 = (frame + 1).toFloat() / EYE_FRAMES - EYE_V_INSET

                // Per-eye orientation + size so they don't all face the same way or match in scale.
                // The decal's local axes are rotated by a random angle; (ax,az)=rotated +X·size,
                // (bx,bz)=rotated +Z·size.
                val ang = hash01(cx, cz, 6) * (2.0 * Math.PI)
                val sz = half * (0.75 + hash01(cx, cz, 8) * 0.6)
                val ca = cos(ang) * sz
                val sa = sin(ang) * sz
                val ax = ca; val az = sa
                val bx = -sa; val bz = ca
                emitDecalCorner(bb, pose, ex - ax - bx, ez - az - bz, camX, camZ, domeR, 0f, v1, alpha)
                emitDecalCorner(bb, pose, ex + ax - bx, ez + az - bz, camX, camZ, domeR, 1f, v1, alpha)
                emitDecalCorner(bb, pose, ex + ax + bx, ez + az + bz, camX, camZ, domeR, 1f, v0, alpha)
                emitDecalCorner(bb, pose, ex - ax + bx, ez - az + bz, camX, camZ, domeR, 0f, v0, alpha)
            }
        }
        ts.end()
    }

    // --- Tentacles (real models, dome-projected in the sky pass) -------------------------

    private val tentVecCache = Vector3f()
    private val tentPoseStack = PoseStack()
    private var tentacleModel: TentacleModel? = null

    /** Second vertex stream for the emissive tip pass — only [TentacleModel] `tent_5` (the tip) is
     *  emitted here, textured with the emissive sheet and drawn additively after the shaded main pass
     *  so the tips glow against the dark sea. */
    private val tentEmissiveBuf = BufferBuilder(2097152)

    /** Per-cell animation rate (keyed by packed cell x/z), FROZEN the first frame a tentacle appears
     *  there — so the rate is distance-driven at birth but never shifts under the player's movement.
     *  Each frame, [tentRateAlive] collects the cells still rendering and the cache is swept down to
     *  them, so it only ever holds the currently-visible tentacles (a few hundred at most). */
    private val tentRateCache = HashMap<Long, Double>()
    private val tentRateAlive = HashSet<Long>()

    private fun tentacleModel(): TentacleModel {
        var m = tentacleModel
        if (m == null) {
            m = TentacleModel(TentacleModel.createMesh().bakeRoot())
            tentacleModel = m
        }
        return m
    }

    /**
     * Render the tentacle field as real animated [TentacleModel]s, **dome-projected** into the sky
     * pass exactly like the sea/eyes — so they are free of the world far-clip plane and reach the
     * horizon. The model is posed by [TentacleAnimation], rendered into a fresh placement pose that
     * maps it to camera-relative world coordinates, and each emitted vertex is intercepted by a
     * [DomeTentacleConsumer] that direction-projects it to the dome radius and shades it by its
     * surface normal. Placed on a world-anchored lattice in the eye-void patches, in drifting clouds,
     * with size growing toward the rim so the distant tentacles tower to the horizon while the near
     * ones stay modest.
     */
    fun renderTentacles(pose: Matrix4f, camX: Double, camY: Double, camZ: Double, domeR: Double, t: Double) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val now = Util.getMillis()
        // Base of every tentacle: the submerged eye plane (one wave-amplitude below the sea level,
        // two blocks above the kill plane). True world Y.
        val baseWorldY = level.minBuildHeight - 64.0 + SEA_ABOVE_KILLPLANE - EYE_SUBMERGE
        val drop = camY - baseWorldY
        if (drop <= 0.0) return
        // Scale a far tentacle's tip up to eye level (the horizon), from this camera height.
        val farScale = (drop * TENT_HORIZON_FRAC / TENT_MODEL_HEIGHT).coerceAtMost(TENT_FAR_SCALE_MAX)

        val fieldR = TENT_FIELD_R       // no clip cap — the dome projection has no far plane
        val fieldR2 = fieldR * fieldR
        val fadeStart = fieldR - TENT_FIELD_FADE
        val cx0 = Math.floor((camX - fieldR) / TENT_SPACING).toInt()
        val cx1 = Math.floor((camX + fieldR) / TENT_SPACING).toInt()
        val cz0 = Math.floor((camZ - fieldR) / TENT_SPACING).toInt()
        val cz1 = Math.floor((camZ + fieldR) / TENT_SPACING).toInt()

        val model = tentacleModel()
        // Two streams filled in one pass (the model is posed once per tentacle): the shaded main body
        // into the Tesselator, and the full-bright emissive copy into [tentEmissiveBuf]. Depth test
        // stays OFF (depthMask false) like the rest of the sky pass — the dome projection puts every
        // vertex at the same radius, so depth-testing the flattened faces z-fights and the texture
        // appears to flicker. Backface CULL instead, so each column shows only its front shell.
        RenderSystem.setShader { GameRenderer.getPositionColorTexShader() }
        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.enableCull()
        val ts = Tesselator.getInstance()
        val bb = ts.builder
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX)
        tentEmissiveBuf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX)
        val consumer = DomeTentacleConsumer(
            bb, pose, domeR, TENT_AMBIENT, TENT_LIGHT_X, TENT_LIGHT_Y, TENT_LIGHT_Z,
        )
        // Emissive tip: full-bright (shaded = false), drawn additively below so only tent_5 glows.
        val emissiveConsumer = DomeTentacleConsumer(
            tentEmissiveBuf, pose, domeR, TENT_AMBIENT, TENT_LIGHT_X, TENT_LIGHT_Y, TENT_LIGHT_Z,
            shaded = false,
        )

        // Off-screen cull uses the shared per-frame view cone ([cullLkX/Y/Z], [cullCos]) set in renderSky.
        tentRateAlive.clear()
        for (cz in cz0..cz1) {
            for (cx in cx0..cx1) {
                // CHEAP DENSITY PRE-REJECT (perf, no visual change): the density gate is a FIXED
                // per-cell hash `h` vs `TENT_FILL·distFill`, and distFill falls with distance, so a cell
                // can only pass within a hash-dependent radius `passDist`. Test that against the
                // UN-JITTERED cell centre (+ a jitter margin) before paying for the jitter hashes, the
                // sqrt, and the ramps — skipping the ~99% of far cells that can't pass. Cells that
                // survive run the exact original placement below, so the rendered set is identical.
                val h = hash01(cx, cz, 28)
                if (h >= TENT_FILL) continue                     // fails the gate at every distance
                val passDist = if (h <= TENT_FILL * TENT_FAR_FILL) {
                    fieldR                                       // passes everywhere out to the rim
                } else {
                    TENT_DENSE_R + ((1.0 - h / TENT_FILL) / (1.0 - TENT_FAR_FILL)) *
                        (TENT_DENSITY_FAR_R - TENT_DENSE_R)
                }
                val ccx = cx * TENT_SPACING - camX
                val ccz = cz * TENT_SPACING - camZ
                val reach = passDist + TENT_JITTER_MAG
                if (ccx * ccx + ccz * ccz > reach * reach) continue

                // --- survivors: the exact original placement (unchanged) ---
                val tx = cx * TENT_SPACING + (hash01(cx, cz, 21) - 0.5) * TENT_SPACING * TENT_JITTER
                val tz = cz * TENT_SPACING + (hash01(cx, cz, 22) - 0.5) * TENT_SPACING * TENT_JITTER

                val ddx = tx - camX
                val ddz = tz - camZ
                val d2 = ddx * ddx + ddz * ddz
                if (d2 > fieldR2) continue
                val dist = sqrt(d2)

                // distT drives DENSITY as a straight LINEAR ramp from TENT_DENSE_R to TENT_DENSITY_FAR_R
                // (pinned, NOT the field rim) — a steady, even thinning. Re-uses `h` for the exact gate.
                val distT = ((dist - TENT_DENSE_R) / (TENT_DENSITY_FAR_R - TENT_DENSE_R)).coerceIn(0.0, 1.0)
                val distFill = 1.0 - (1.0 - TENT_FAR_FILL) * distT
                if (h >= TENT_FILL * distFill) continue

                // Cluster presence (drifting cloud) × eye-void gate = the tentacle's life in [0,1].
                val cellRand = hash01(cx, cz, 27)
                val cluster = smoothstep(cellRand, cellRand + TENT_PRESENCE_BAND, tentacleField(tx, tz, t))
                val voidness = 1.0 - smoothstep(TENT_VOID_LO, TENT_VOID_HI, eyeDensity(tx, tz, t))
                val life = cluster * voidness
                if (life <= 0.0) continue

                val edgeFade = 1.0 - smoothstep(fadeStart, fieldR, dist)
                // Alpha is full for almost all of life and dissolves only at the very ends — the sink
                // (below) is what reads as it leaving.
                val alpha = TENT_ALPHA * (smoothstep(0.0, TENT_FADE, life) * edgeFade).toFloat()
                if (alpha <= 0.02f) continue
                val sinkOffset = (1.0 - life) * TENT_SINK

                // sizeT → SIZE, rateT → animation RATE — independent linear distance ramps (computed
                // here, only for tentacles that actually render). distT (DENSITY) is computed above.
                val sizeT = ((dist - TENT_SIZE_NEAR_R) / (TENT_SIZE_FAR_R - TENT_SIZE_NEAR_R)).coerceIn(0.0, 1.0)
                val rateT = ((dist - TENT_RATE_NEAR_R) / (TENT_RATE_FAR_R - TENT_RATE_NEAR_R)).coerceIn(0.0, 1.0)

                val yaw = (hash01(cx, cz, 23) * (2.0 * Math.PI)).toFloat()
                // Small near, growing to horizon-height far, with ±15% per-tentacle variety.
                val baseScale = TENT_NEAR_SCALE + (farScale - TENT_NEAR_SCALE) * sizeT
                val scaleF = (baseScale * (0.85 + hash01(cx, cz, 24) * 0.3)).toFloat()
                // Rate is distance-driven AT BIRTH then FROZEN: the first frame this cell renders we
                // lock a rate from its current distance (far birth → slow), and reuse it forever after
                // so the phase never lurches when the player moves. Swept out of the cache when it dies.
                val cellKey = (cx.toLong() shl 32) or (cz.toLong() and 0xffffffffL)
                tentRateAlive.add(cellKey)
                val rate = tentRateCache.getOrPut(cellKey) {
                    (TENT_RATE_NEAR + (TENT_RATE_FAR - TENT_RATE_NEAR) * rateT) *
                        (0.85 + hash01(cx, cz, 25) * 0.3)
                }
                val animMs = (now * rate).toLong() + (hash01(cx, cz, 26) * TentacleAnimation.LENGTH_MS).toLong()

                // OFF-SCREEN CULL: skip the expensive pose + emit if the whole column (base to tip) is
                // outside the view cone. Off-screen = invisible, so the look is unchanged. The rate
                // cache was updated above, so a tentacle keeps its frozen rate across off-screen spells.
                val byd = (baseWorldY - sinkOffset) - camY
                val tyd = byd + TENT_MODEL_HEIGHT * scaleF
                val dotBase = (cullLkX * ddx + cullLkY * byd + cullLkZ * ddz) / Math.sqrt(ddx * ddx + byd * byd + ddz * ddz)
                val dotTip = (cullLkX * ddx + cullLkY * tyd + cullLkZ * ddz) / Math.sqrt(ddx * ddx + tyd * tyd + ddz * ddz)
                if (Math.max(dotBase, dotTip) < cullCos) continue

                model.root().allParts.forEach { it.resetPose() }
                KeyframeAnimations.animate(model, TentacleAnimation.IDLE_WAVE, animMs, 1f, tentVecCache)

                // A FRESH placement pose (no view rotation — that's applied by the dome consumer's
                // `pose`): it maps the model into camera-relative world coordinates (blocks).
                consumer.alpha = alpha
                emissiveConsumer.alpha = alpha
                // Both body and emissive tip fade out with distance — distant tentacles go fully dark.
                val darkTint = (1.0 - smoothstep(TENT_DARK_NEAR_R, TENT_DARK_FAR_R, dist)).toFloat()
                consumer.tint = darkTint
                emissiveConsumer.tint = darkTint
                tentPoseStack.pushPose()
                tentPoseStack.translate(ddx, (baseWorldY - sinkOffset) - camY, ddz)
                tentPoseStack.mulPose(Quaternionf().rotationY(yaw))
                tentPoseStack.scale(scaleF, scaleF, scaleF)
                // Standard Mojang entity model framing: flip (Y-down→up) then drop the 24-px
                // Blockbench ground (1.5 blocks) so the base sits at this world point.
                tentPoseStack.scale(-1.0f, -1.0f, 1.0f)
                tentPoseStack.translate(0.0f, -1.501f, 0.0f)
                // Pose once, emit twice: the shaded whole-body, plus the full-bright TIP ONLY into the
                // emissive stream (so only tent_5 can light the emissive texture — no edge bleed).
                model.renderToBuffer(
                    tentPoseStack, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    1.0f, 1.0f, 1.0f, alpha,
                )
                model.renderTip(
                    tentPoseStack, emissiveConsumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, alpha,
                )
                tentPoseStack.popPose()
            }
        }
        // Main body — shaded, the dedicated tentacle texture, normal alpha blend.
        bindTentacleTexture(TENT_TEX)
        ts.end()
        // Emissive tips — additive (SRC_ALPHA, ONE) so they glow, full-bright, over the dark body.
        bindTentacleTexture(TENT_EMISSIVE_TEX)
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
        BufferUploader.drawWithShader(tentEmissiveBuf.end())
        RenderSystem.blendFunc(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        )
        // Drop frozen rates for tentacles that no longer rendered this frame, so the cache tracks only
        // the live set (and a tentacle that later reappears is reborn with a fresh birth-distance rate).
        tentRateCache.keys.retainAll(tentRateAlive)
    }

    /** Bind a tentacle texture (UVs are in-range, so CLAMP) with NEAREST filtering and no mipmaps.
     *  LINEAR bled the transparent (black, zero-alpha) pixels surrounding the emissive glow into its
     *  edge — a dark halo — and any mip level would average the same way; NEAREST + MAX_LEVEL 0 keeps
     *  the edges clean. */
    private fun bindTentacleTexture(tex: ResourceLocation) {
        RenderSystem.setShaderTexture(0, tex)
        val t = Minecraft.getInstance().textureManager.getTexture(tex)
        RenderSystem.bindTexture(t.id)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0)
    }

    /**
     * Bridges a Mojang model's vertex output into the sea's `position_color_tex` dome pipeline.
     * [TentacleModel.renderToBuffer] emits each vertex already transformed into camera-relative world
     * coordinates (by the placement pose); this intercepts it, direction-projects it to [domeR]
     * (just like the sea/eyes), and re-emits through the sky [pose]. So the model renders in the sky
     * pass with no far-clip plane — reaching the horizon — instead of the clip-limited world pass.
     *
     * It also re-adds the lighting the dome pipeline lacks: the model passes a surface normal per
     * vertex, which we half-Lambert against [lightX]/[lightY]/[lightZ] (floored at [ambient]) and put
     * in the vertex color so position_color_tex MULTIPLIES the texture by it — a lit side / shadow
     * side that gives the otherwise flat-lit columns their round form. The texture is untouched.
     *
     * When [shaded] is false the shade term is skipped (full bright) — used for the additive emissive
     * tip pass, which must glow at full intensity rather than be darkened by the fake key-light.
     */
    private class DomeTentacleConsumer(
        private val out: BufferBuilder,
        private val pose: Matrix4f,
        private val domeR: Double,
        private val ambient: Float,
        private val lightX: Float,
        private val lightY: Float,
        private val lightZ: Float,
        private val shaded: Boolean = true,
    ) : VertexConsumer {
        /** Per-tentacle alpha (set before each model render); overrides the model's vertex alpha so
         *  the whole column shares the sink-fade value. */
        var alpha: Float = 1.0f

        /** Per-tentacle brightness multiplier on the body color (NOT alpha): 1 shows the full texture,
         *  0 crushes it to pure black. Drives the distance fade to black silhouettes. */
        var tint: Float = 1.0f

        override fun vertex(
            x: Float, y: Float, z: Float,
            red: Float, green: Float, blue: Float, vAlpha: Float,
            u: Float, v: Float, overlay: Int, light: Int,
            normalX: Float, normalY: Float, normalZ: Float,
        ) {
            val dx = x.toDouble(); val dy = y.toDouble(); val dz = z.toDouble()
            val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1.0e-6) return
            val s = domeR / len
            // Diffuse shade from the surface normal, floored at `ambient` so shadows stay readable.
            // Multiplied into the color → the texture (via uv) is darkened on faces angled from the
            // light, leaving a lit side and a shadow side. Skipped (full bright) for the emissive pass.
            val shade = if (shaded) {
                val nl = Math.sqrt((normalX * normalX + normalY * normalY + normalZ * normalZ).toDouble()).toFloat()
                val ndot = if (nl > 1.0e-6f) (normalX * lightX + normalY * lightY + normalZ * lightZ) / nl else 0f
                ambient + (1.0f - ambient) * Math.max(0.0f, ndot)
            } else {
                1.0f
            }
            val c = shade * tint
            out.vertex(pose, (dx * s).toFloat(), (dy * s).toFloat(), (dz * s).toFloat())
                .color(red * c, green * c, blue * c, alpha)
                .uv(u, v)
                .endVertex()
        }

        // ModelPart only calls the bulk vertex above; the rest are unused interface obligations.
        override fun vertex(x: Double, y: Double, z: Double): VertexConsumer = this
        override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer = this
        override fun uv(u: Float, v: Float): VertexConsumer = this
        override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
        override fun uv2(u: Int, v: Int): VertexConsumer = this
        override fun normal(x: Float, y: Float, z: Float): VertexConsumer = this
        override fun endVertex() {}
        override fun defaultColor(r: Int, g: Int, b: Int, a: Int) {}
        override fun unsetDefaultColor() {}
    }

    /** Drifting value-noise cloud field in [0,1] for tentacle placement — clusters of tentacles
     *  (clouds) that migrate over time, contrast-curved into clumps and voids. */
    private fun tentacleField(wx: Double, wz: Double, t: Double): Double {
        val s = TENT_NOISE_SCALE
        val n1 = valueNoise((wx + t * TENT_DRIFT_X) / s, (wz + t * TENT_DRIFT_Z) / s, 30)
        val n2 = valueNoise((wx - t * TENT_DRIFT_Z) / (s * 0.55), (wz + t * TENT_DRIFT_X) / (s * 0.55), 31)
        return smoothstep(TENT_CLUMP_LO, TENT_CLUMP_HI, 0.6 * n1 + 0.4 * n2)
    }

    /** Eye-density field in [0, 1] at world (`wx`, `wz`) and time `t` seconds, contrast-curved into
     *  clumps and voids. Two value-noise octaves drift in different directions and at different
     *  scales, so their sum *morphs* over time — clumps form, merge, split, and dissolve — rather
     *  than rigidly sliding. */
    private fun eyeDensity(wx: Double, wz: Double, t: Double): Double {
        val s = EYE_NOISE_SCALE
        val n1 = valueNoise((wx + t * EYE_DRIFT_X) / s, (wz + t * EYE_DRIFT_Z) / s, 7)
        val n2 = valueNoise((wx - t * EYE_DRIFT_Z) / (s * 0.6), (wz + t * EYE_DRIFT_X) / (s * 0.6), 9)
        return smoothstep(EYE_CLUMP_LO, EYE_CLUMP_HI, 0.62 * n1 + 0.38 * n2)
    }

    /** Bilinearly-interpolated value noise in [0, 1] over the integer lattice of (`px`, `pz`),
     *  corners hashed on `seed`. Smoothstep-weighted so the field is C¹-smooth. */
    private fun valueNoise(px: Double, pz: Double, seed: Int): Double {
        val xi = Math.floor(px).toInt()
        val zi = Math.floor(pz).toInt()
        val fx = px - xi
        val fz = pz - zi
        val u = fx * fx * (3.0 - 2.0 * fx)
        val w = fz * fz * (3.0 - 2.0 * fz)
        val a = hash01(xi, zi, seed).let { it + (hash01(xi + 1, zi, seed) - it) * u }
        val b = hash01(xi, zi + 1, seed).let { it + (hash01(xi + 1, zi + 1, seed) - it) * u }
        return a + (b - a) * w
    }

    private fun emitDecalCorner(
        bb: BufferBuilder, pose: Matrix4f,
        wx: Double, wz: Double, camX: Double, camZ: Double, domeR: Double,
        u: Float, v: Float, alpha: Float,
    ) {
        projectSubmergedEye(wx, wz, camX, camZ, domeR)
        bb.vertex(pose, projScratch[0].toFloat(), projScratch[1].toFloat(), projScratch[2].toFloat())
            .color(1f, 1f, 1f, alpha)
            .uv(u, v)
            .endVertex()
    }

    /** Integer hash → [0, 1). Deterministic per (cell x, cell z, channel), so the eye field is
     *  stable in the world and identical every frame without any stored state. */
    private fun hash01(x: Int, z: Int, seed: Int): Double {
        var h = x * 374761393 + z * 668265263 + seed * 1013904223
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7fffffff).toDouble() / 0x7fffffff.toDouble()
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val tt = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return tt * tt * (3.0 - 2.0 * tt)
    }

    private fun lerp(a: Float, b: Float, tt: Float): Float = a + (b - a) * tt
}
