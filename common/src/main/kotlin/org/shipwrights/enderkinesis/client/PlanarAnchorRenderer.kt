package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import org.shipwrights.enderkinesis.registry.EKParticles
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.PlanarAnchorBlockEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Renders the Planar Anchor's chain (block centre ↔ cloud) and seeds the green-ender particle
 * cloud at the outboard end. The BE itself owns the cloud position; this renderer is a pure
 * consumer of synced state.
 *
 * Chain geometry is the classic "two crossed quads tiled by 1-block units" — the same shape
 * vanilla uses for its chain block. The vanilla `block/chain` sprite is reused (no new texture).
 *
 * Particles: vanilla [ParticleTypes.PORTAL] (purple-ender swirling). The wording is "green
 * swirling ender particles" but vanilla has no green-swirling particle; PORTAL gets the *swirling
 * ender* feel and the user can later ask for a custom green particle type.
 */
class PlanarAnchorRenderer(
    @Suppress("UNUSED_PARAMETER") ctx: BlockEntityRendererProvider.Context,
) : BlockEntityRenderer<PlanarAnchorBlockEntity> {

    override fun render(
        be: PlanarAnchorBlockEntity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        if (!be.isActive) return
        val storedCloud = be.cloudWorld ?: return
        val level = be.level ?: return

        // Re-derive the cloud's current world position each frame so the chain stays connected
        // through ship motion. Two tracking sources in priority order:
        //  1. Anchor target — look up the target anchor's block CENTRE in whichever frame it
        //     sits in (its own ship, our ship, the world) and transform to world. Covers any
        //     anchor-to-anchor chain regardless of ships.
        //  2. Foreign-ship body pose — for ship hits with no nearby anchor (the older path).
        // Falls back to the stored static world position if both lookups fail.
        val pos = be.blockPos
        val ship = level.getLoadedShipManagingPos(pos)
        val targetAnchor = be.chainTargetAnchorPos
        val cloud = if (targetAnchor != null) {
            val v = Vector3d(targetAnchor.x + 0.5, targetAnchor.y + 0.5, targetAnchor.z + 0.5)
            level.getLoadedShipManagingPos(targetAnchor)
                ?.transform?.shipToWorld?.transformPosition(v)
            Vec3(v.x, v.y, v.z)
        } else if (be.tetheredToShip && be.tetheredBodyPose != null) {
            val bp = be.tetheredBodyPose!!
            val otherShip = level.shipObjectWorld.allShips.getById(be.tetheredShipId)
            if (otherShip != null) {
                val v = Vector3d(bp.x, bp.y, bp.z)
                otherShip.transform.shipToWorld.transformPosition(v)
                Vec3(v.x, v.y, v.z)
            } else storedCloud
        } else storedCloud

        // The BER's PoseStack origin is the block corner *in shipyard space* — VS2's mixin has
        // already pre-multiplied the matrix by shipToWorld for ship-resident BEs. The cloud
        // position is in world space, so we convert it back to the ship's frame before computing
        // the chain endpoint, or the delta would be ~10^7 blocks long (and OOM the vertex buffer
        // trying to tile it segment-by-segment). For an anchor that isn't on a ship the
        // worldToShip path is skipped and world == shipyard.
        val cloudLocal = Vector3d(cloud.x, cloud.y, cloud.z)
        ship?.transform?.worldToShip?.transformPosition(cloudLocal)

        val dx = cloudLocal.x - (pos.x + 0.5)
        val dy = cloudLocal.y - (pos.y + 0.5)
        val dz = cloudLocal.z - (pos.z + 0.5)
        val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-3) return
        // Hard ceiling — a frame mismatch (like the original ship-vs-world bug) is what crashed
        // the vertex buffer last time; refuse to draw a chain longer than this and let it show as
        // missing instead of as an OOM.
        if (len > MAX_CHAIN_LEN) return

        // Animation: one phase (extend or retract) plays from `clientPhaseStartTick` over
        // [ANIMATION_TICKS]. Extending → leading edge walks portal→block. Retracting → block→
        // portal (and once it reaches the portal the server clears the visual). `scrollOffset` is
        // derived from the leading edge alone, so the same chain-link silhouette rides the tip in
        // either direction — reads as one chain end physically advancing or pulling back.
        val phaseStart = be.clientPhaseStartTick
        if (phaseStart < 0L) return
        val elapsed = (level.gameTime - phaseStart).toFloat() + partialTick
        val progress = (elapsed / ANIMATION_TICKS).coerceIn(0f, 1f)
        // Solid-chain mode: any concrete target — another anchor anywhere (same ship, other
        // ship, world) OR a non-anchor ship block. Free-space and world-block hits at non-anchor
        // blocks keep the portal/fade treatment (EXTEND_BEHIND tail + fade-to-portal alpha).
        val solid = be.tetheredToShip || be.chainTargetAnchorPos != null
        val behind = if (solid) 0.0 else EXTEND_BEHIND
        val totalLength = len + behind
        val leadingEdge = if (be.isRetracting) {
            totalLength * progress           // 0 (block) → totalLength (back of tail): pull in
        } else {
            totalLength * (1.0 - progress)   // totalLength → 0: emerges from behind the portal
        }
        if (leadingEdge >= totalLength) return    // retract finished / extend not yet emerged
        // Texture phase at the leading edge is constant — `totalLength mod 1` — for both
        // directions, so the same chain-link silhouette rides the advancing or retreating tip.
        val scrollOffset = (totalLength - leadingEdge).mod(1.0)

        poseStack.pushPose()
        poseStack.translate(0.5, 0.5, 0.5)

        // Rotate +Y so it points along (block → cloud).
        val q = Quaternionf().rotationTo(
            0f, 1f, 0f,
            (dx / len).toFloat(), (dy / len).toFloat(), (dz / len).toFloat(),
        )
        poseStack.mulPose(q)

        val sprite = Minecraft.getInstance()
            .getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
            .apply(CHAIN_TEXTURE)
        // BER-time translucent on the block atlas: `RenderType.translucent()` is the *chunk*
        // translucent layer and renders at the wrong pipeline stage when used in a BER (chain
        // becomes invisible). `entityTranslucentCull(BLOCK_ATLAS)` flushes alongside other BERs
        // and supports per-vertex alpha for the fade.
        val builder = bufferSource.getBuffer(
            RenderType.entityTranslucentCull(TextureAtlas.LOCATION_BLOCKS)
        )
        // Emissive brightness is computed per-vertex inside drawChain (pulse-scroll), so we no
        // longer pass a single FULL_BRIGHT — we pass the current game time (in ticks, with
        // sub-tick interpolation) and let the pulse function modulate per-y. Same overlay as the
        // host block to avoid hit/freeze flicker bleeding into the chain.
        val timeTicks = (level.gameTime + partialTick).toDouble()
        drawChain(builder, poseStack, totalLength, leadingEdge, scrollOffset, solid, timeTicks, sprite, OverlayTexture.NO_OVERLAY)

        poseStack.popPose()

        // No portal particles when the chain has a concrete target — the chain is a physical
        // tether, not a portal effect.
        if (solid) return

        // Portal disc: spawn particles on the circular plane perpendicular to the chain in
        // *world* space, and let vanilla `PORTAL` animate them inward.  Critical: it's `PORTAL`,
        // NOT `REVERSE_PORTAL`. `PortalParticle.tick` is `x = xStart + xd · f` where f walks
        // 1→1.125→0 over the lifetime, so the particle spawns at `xStart + xd`, overshoots a
        // touch outward, then converges back to `xStart` and dies — the inward pull-to-centre we
        // want. `ReversePortalParticle` overrides tick to `x += xd · (age/lifetime)` — an
        // accelerating *outward* drift, which looked like radiation from a point.
        // BER `render()` is called every frame, including while the game is paused. The particle
        // engine's `tick()` does NOT run while paused, so `addParticle` calls just queue up — the
        // queue drains on un-pause and the entire backlog spawns as a single cloud. Skip while
        // paused. (`isPaused` returns true on the single-player pause menu; false in multiplayer
        // / unpaused single-player.)
        if (Minecraft.getInstance().isPaused) return
        val rng = level.random
        if (rng.nextFloat() < CLOUD_SPAWN_CHANCE_PER_FRAME) {
            // Chain direction in world space — convert the ship-local direction we already have
            // via the ship's rotation. (Without a ship, ship-local already == world.)
            val worldDir = Vector3d(dx / len, dy / len, dz / len)
            ship?.transform?.shipToWorldRotation?.transform(worldDir)
            // Build two unit tangents on the plane perpendicular to worldDir. Pick a helper axis
            // not parallel to worldDir (Y unless the chain itself is mostly vertical, in which
            // case X) so the cross product is well-conditioned.
            val helper = if (Math.abs(worldDir.y) > 0.9) Vector3d(1.0, 0.0, 0.0)
                         else Vector3d(0.0, 1.0, 0.0)
            val u = Vector3d(worldDir).cross(helper).normalize()
            val v = Vector3d(worldDir).cross(u).normalize()
            // Spiral pattern: a slowly-rotating base angle, paired with multiple coordinated
            // emissions per spawn event at `2π / SPIRAL_ARMS` offsets, makes [SPIRAL_ARMS] arms
            // visible in the swarm. The rotation rate is chosen so the base angle rotates only
            // a fraction of a turn during a single particle's ~50-tick lifetime — that's what
            // makes consecutive spawns form a coherent arm instead of looking like noise.
            // Plus each particle's `xd` has a tangential component on top of its radial one, so
            // its straight-line trajectory is chord-like rather than spoke-like.
            // `PortalParticle.tick` is `x = xStart + xd · f` (straight line) — individual
            // particles can't curve; the spiral is an emergent pattern of the rotating arm
            // positions overlaid with the tangential skew.
            val baseAngle = (level.gameTime + partialTick).toDouble() * SPIRAL_ANGULAR_RATE
            for (armIdx in 0 until SPIRAL_ARMS) {
                val r = DISC_RADIUS * Math.sqrt(rng.nextDouble())
                val armOffset = armIdx.toDouble() * 2.0 * Math.PI / SPIRAL_ARMS
                val angle = baseAngle + armOffset + (rng.nextDouble() - 0.5) * SPIRAL_SPREAD
                val cosA = Math.cos(angle); val sinA = Math.sin(angle)
                // Radial direction on the perpendicular plane (outward from cloud centre).
                val radialX = cosA * u.x + sinA * v.x
                val radialY = cosA * u.y + sinA * v.y
                val radialZ = cosA * u.z + sinA * v.z
                // Tangential (90° rotation on the plane).
                val tangX = -sinA * u.x + cosA * v.x
                val tangY = -sinA * u.y + cosA * v.y
                val tangZ = -sinA * u.z + cosA * v.z
                val offX = r * (radialX + SPIRAL_TANGENT_FACTOR * tangX)
                val offY = r * (radialY + SPIRAL_TANGENT_FACTOR * tangY)
                val offZ = r * (radialZ + SPIRAL_TANGENT_FACTOR * tangZ)
                level.addParticle(
                    EKParticles.planarSpiral(),
                    cloud.x, cloud.y, cloud.z,
                    offX, offY, offZ,
                )
            }
        }
    }

    /**
     * Mirrors vanilla `block/chain` precisely: two 3-px-wide planes rotated 45° around the chain
     * axis. Plane 1 samples cols 0–2 of the chain sprite, plane 2 samples cols 3–5 (different
     * chain-link silhouettes per face — that's what makes the chain read as a 3D link rather than
     * two flat ribbons). Each plane emits both its front and back winding so neither limb of the
     * X disappears under culling.
     *
     * Renders the chain in `[leadingEdge, length]` — the block-side end *is* the leading edge,
     * advancing from the portal (`length`) down to the block (`0`) during the one-shot reveal.
     * [scrollOffset] (in `[0, 1)` chain-link units) shifts the texture's tile boundaries; the
     * caller picks `scrollOffset = (length · progress) mod 1` so the texture-phase at the leading
     * edge stays constant — the same chain-link silhouette rides the advancing tip.
     *
     * Per-vertex alpha fades from **1.0 at the block** (`y = 0`) to **0.0 at the portal**
     * (`y = length`), so the chain dissolves into the portal.
     */
    private fun drawChain(
        builder: VertexConsumer,
        ps: PoseStack,
        length: Double,
        leadingEdge: Double,
        scrollOffset: Double,
        solid: Boolean,
        timeTicks: Double,
        sprite: TextureAtlasSprite,
        packedOverlay: Int,
    ) {
        val matrix = ps.last().pose()
        val normal = ps.last().normal()
        val w = CHAIN_HALF_WIDTH
        // Vanilla chain texture: cols 0–2 (plane 1) and cols 3–5 (plane 2) of a 16-wide tile.
        val uSpan = sprite.u1 - sprite.u0
        val uA0 = sprite.u0
        val uA1 = sprite.u0 + uSpan * 3f / 16f
        val uB0 = sprite.u0 + uSpan * 3f / 16f
        val uB1 = sprite.u0 + uSpan * 6f / 16f
        val v0 = sprite.v0
        val v1 = sprite.v1

        // Scroll-aware tiling. The effective tile boundaries are at `(n − scrollOffset)` for
        // integer n, so as `scrollOffset` rises the texture shifts toward the block.
        var y = leadingEdge.coerceAtLeast(0.0)
        while (y < length) {
            val yScrolled = y + scrollOffset
            val tileFloor = Math.floor(yScrolled)
            // Strict next boundary so we don't loop forever when `yScrolled` lands exactly on an int.
            val yEnd = (tileFloor + 1.0 - scrollOffset).coerceAtMost(length)
            if (yEnd <= y + 1e-9) break

            // Texture v: frac 0 → v1 (texture bottom), frac 1 → v0 (texture top).
            val frac0 = (yScrolled - tileFloor).toFloat()
            val frac1 = ((yEnd + scrollOffset) - tileFloor).toFloat()
            val vAtY0 = v1 - (v1 - v0) * frac0
            val vAtY1 = v1 - (v1 - v0) * frac1

            // Per-vertex alpha along chain length. Free-floating/world anchors fade into the
            // portal at the far end; ship-to-ship anchors stay fully solid end-to-end.
            val alphaY0 = if (solid) 1f else alphaAt(y, length)
            val alphaY1 = if (solid) 1f else alphaAt(yEnd, length)

            // Per-vertex emissive pulse: sinusoidal brightness wave that scrolls along +y
            // (anchor → portal) with time. Consecutive segments share their boundary y, so the
            // pulse function returns the same value at the boundary in both segments — the
            // shader's linear interpolation across segments stays continuous.
            val lightY0 = pulseEmissiveLight(y, timeTicks)
            val lightY1 = pulseEmissiveLight(yEnd, timeTicks)

            val y0f = y.toFloat()
            val y1f = yEnd.toFloat()

            // Plane A — diagonal from (-w, y, -w) to (+w, y, +w), texture cols 0–2.
            doubleSidedQuad(
                builder, matrix, normal,
                -w, y0f, -w,   w, y0f,  w,
                 w, y1f,  w,  -w, y1f, -w,
                uA0, uA1, vAtY0, vAtY1, alphaY0, alphaY1,
                lightY0, lightY1, packedOverlay,
            )
            // Plane B — perpendicular diagonal, texture cols 3–5.
            doubleSidedQuad(
                builder, matrix, normal,
                -w, y0f,  w,   w, y0f, -w,
                 w, y1f, -w,  -w, y1f,  w,
                uB0, uB1, vAtY0, vAtY1, alphaY0, alphaY1,
                lightY0, lightY1, packedOverlay,
            )
            y = yEnd
        }
    }

    /** Sinusoidal brightness pulse that scrolls along the chain's +y axis (anchor → portal end).
     *  `factor` ∈ [0, 1]; maps to `blockLight ∈ [PULSE_BASE_LIGHT, PULSE_PEAK_LIGHT]`. SkyLight is
     *  pinned to 15 (the chain is an ender effect, not subject to ambient shadowing). */
    private fun pulseEmissiveLight(y: Double, timeTicks: Double): Int {
        val phase = (y - PULSE_SCROLL_SPEED * timeTicks) / PULSE_WAVELENGTH
        val factor = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * phase)
        val blockLight = (PULSE_BASE_LIGHT + (PULSE_PEAK_LIGHT - PULSE_BASE_LIGHT) * factor)
            .toInt()
            .coerceIn(0, 15)
        return LightTexture.pack(blockLight, 15)
    }

    private fun alphaAt(y: Double, length: Double): Float {
        // Solid for the bulk of the chain; only the last [FADE_BAND] blocks closest to the portal
        // taper to 0. For chains shorter than [FADE_BAND], the band collapses to the chain's full
        // length, keeping the block end at full opacity and the portal end at 0.
        val band = Math.min(FADE_BAND, length)
        if (band <= 1e-6) return 1f
        val fadeStart = length - band
        if (y <= fadeStart) return 1f
        return ((length - y) / band).coerceIn(0.0, 1.0).toFloat()
    }

    /** Emit a quad with both its front and back faces so culling can't hide either limb.
     *  `packedLightBottom` is applied to the two `y0`-side vertices, `packedLightTop` to the two
     *  `y1`-side vertices — the shader linearly interpolates across the quad and (because
     *  consecutive segments share boundary y values) across segment seams. */
    private fun doubleSidedQuad(
        builder: VertexConsumer,
        matrix: org.joml.Matrix4f,
        normal: org.joml.Matrix3f,
        x0: Float, y0: Float, z0: Float,    // bottom-left  → (uLeft,  vBottom, alphaBottom, lightBottom)
        x1: Float, y1: Float, z1: Float,    // bottom-right → (uRight, vBottom, alphaBottom, lightBottom)
        x2: Float, y2: Float, z2: Float,    // top-right    → (uRight, vTop,    alphaTop,    lightTop)
        x3: Float, y3: Float, z3: Float,    // top-left     → (uLeft,  vTop,    alphaTop,    lightTop)
        uLeft: Float, uRight: Float,
        vBottom: Float, vTop: Float,
        alphaBottom: Float, alphaTop: Float,
        packedLightBottom: Int, packedLightTop: Int,
        packedOverlay: Int,
    ) {
        // Front face — CCW from front: 0 → 1 → 2 → 3
        v(builder, matrix, normal, x0, y0, z0, uLeft,  vBottom, alphaBottom, packedLightBottom, packedOverlay)
        v(builder, matrix, normal, x1, y1, z1, uRight, vBottom, alphaBottom, packedLightBottom, packedOverlay)
        v(builder, matrix, normal, x2, y2, z2, uRight, vTop,    alphaTop,    packedLightTop,    packedOverlay)
        v(builder, matrix, normal, x3, y3, z3, uLeft,  vTop,    alphaTop,    packedLightTop,    packedOverlay)
        // Back face — vertices in reverse so this side's normal points the other way; UVs
        // travel with each vertex so the texture stays right-side-up to a viewer on the back.
        v(builder, matrix, normal, x3, y3, z3, uLeft,  vTop,    alphaTop,    packedLightTop,    packedOverlay)
        v(builder, matrix, normal, x2, y2, z2, uRight, vTop,    alphaTop,    packedLightTop,    packedOverlay)
        v(builder, matrix, normal, x1, y1, z1, uRight, vBottom, alphaBottom, packedLightBottom, packedOverlay)
        v(builder, matrix, normal, x0, y0, z0, uLeft,  vBottom, alphaBottom, packedLightBottom, packedOverlay)
    }

    private fun v(
        builder: VertexConsumer,
        matrix: org.joml.Matrix4f,
        normal: org.joml.Matrix3f,
        x: Float, y: Float, z: Float,
        u: Float, vCoord: Float,
        alpha: Float,
        packedLight: Int, packedOverlay: Int,
    ) {
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        builder.vertex(matrix, x, y, z).color(255, 255, 255, a)
            .uv(u, vCoord).overlayCoords(packedOverlay).uv2(packedLight)
            .normal(normal, 0f, 0f, 1f).endVertex()
    }

    override fun shouldRenderOffScreen(be: PlanarAnchorBlockEntity): Boolean = true

    /** Override the default 64-block BE render cutoff. The chain can extend up to
     *  [MAX_CHAIN_LEN] (256 blocks) and is a deliberate visual landmark in structures
     *  like the Ygann Anchor — culling at 64 blocks hides the chain long before the
     *  block model goes invisible at its own (chunk-driven) distance. 192 keeps the
     *  chain visible at the rough far-render edge of normal play without lifting the
     *  cap so high that we render off-camera anchors most players never see. */
    override fun getViewDistance(): Int = VIEW_DISTANCE

    companion object {
        // Same column layout as vanilla `block/chain` (content in cols 0–5, transparent rest),
        // so the X-cross UV math is unchanged.
        private val CHAIN_TEXTURE = ResourceLocation("enderkinesis", "block/ancrite_chain")
        /** Axis-aligned half-extent of each diagonal plane in the chain's local XZ frame.
         *  Matches vanilla `block/chain`: a 3-px-wide element (`from 6.5 to 9.5`) rotated 45°
         *  around Y → half-extent = `1.5 / 16 / sqrt(2)`. */
        private const val CHAIN_HALF_WIDTH = 0.066291265f  // 1.5 / (16 * sqrt(2))
        /** Pulse-emissive shaping. Per-vertex blockLight is computed as a sinusoid of
         *  `(y − PULSE_SCROLL_SPEED · timeTicks) / PULSE_WAVELENGTH`, mapped into
         *  `[PULSE_BASE_LIGHT, PULSE_PEAK_LIGHT]`. The pulse travels in +y (anchor → portal). */
        private const val PULSE_WAVELENGTH = 4.0          // blocks per cycle
        private const val PULSE_SCROLL_SPEED = 0.4        // blocks/tick (= 8 blocks/sec)
        private const val PULSE_BASE_LIGHT = 4            // trough blockLight (dim)
        private const val PULSE_PEAK_LIGHT = 15           // peak blockLight (full bright)
        /** ≈ 12 spawn-events/sec at 60 fps; each event emits [SPIRAL_ARMS] particles, so this
         *  yields ~36 particles/sec — enough to make the arms read without crowding. */
        private const val CLOUD_SPAWN_CHANCE_PER_FRAME = 0.20f
        /** Number of simultaneous spiral arms emitted per spawn event. */
        private const val SPIRAL_ARMS = 3
        /** Radius of the portal disc — particles spawn area-uniformly inside this on the plane
         *  perpendicular to the chain, and animate inward to the cloud centre before expiring. */
        private const val DISC_RADIUS = 2.0
        /** Base-angle rotation rate of the spiral, in radians per game-tick. Tuned so the angle
         *  rotates only a fraction of a turn during one particle's ~50-tick lifetime — that's
         *  what makes the rotating arms read as a spiral rather than randomised positions. */
        private const val SPIRAL_ANGULAR_RATE = 0.10
        /** How much tangential (perpendicular-on-plane) offset each particle gets relative to its
         *  radial offset. 0 = pure radial spokes; 1 = 45° tangential skew (clearly curved arms). */
        private const val SPIRAL_TANGENT_FACTOR = 1.0
        /** Tight jitter on each spawn's angle — small enough to keep arms coherent. */
        private const val SPIRAL_SPREAD = 0.15
        /** Refuse to render any chain longer than this — guards against frame-mismatch OOMs. */
        private const val MAX_CHAIN_LEN = 256.0

        /** BER cull radius (blocks). Vanilla defaults to 64 via [BlockEntity.VIEWING_RANGE];
         *  override here so anchor chains stay visible at the distance landmark structures
         *  are usually first sighted. Vanilla beacon uses 256 with the same reasoning. */
        private const val VIEW_DISTANCE = 192
        /** Ticks for one full animation phase (extend or retract) of the chain. */
        private const val ANIMATION_TICKS = 6f
        /** Blocks of chain that always extend past the cloud (portal) end. The chain renders
         *  `[leadingEdge, len + EXTEND_BEHIND]`, so a tail sits behind the portal at all times,
         *  not just during the start animation. With the [FADE_BAND] equal to this, the tail is
         *  exactly the fade region — dissolving into the portal from the back. */
        private const val EXTEND_BEHIND = 2.0
        /** Width of the alpha-fade band at the portal end of the chain, in blocks. The bulk of
         *  the chain stays at alpha 1.0; only this last band fades to 0 at the portal. */
        private const val FADE_BAND = 4.0
    }
}
