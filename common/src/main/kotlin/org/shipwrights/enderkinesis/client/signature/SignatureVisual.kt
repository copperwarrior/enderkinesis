package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.core.api.util.GameTickOnly
import org.valkyrienskies.mod.common.getShipManagingPos
import org.shipwrights.enderkinesis.client.AegisContacts
import org.shipwrights.enderkinesis.client.AegisRenderType
import org.shipwrights.enderkinesis.client.ScrollBeamClient
import org.shipwrights.enderkinesis.client.SpellArt
import org.shipwrights.enderkinesis.client.SpellClock
import org.shipwrights.enderkinesis.client.SpellHand
import org.shipwrights.enderkinesis.client.SpellShapes
import org.shipwrights.enderkinesis.client.SpellView
import org.shipwrights.enderkinesis.client.SpellVfxRenderTypes
import org.shipwrights.enderkinesis.entity.SignatureEntity
import org.shipwrights.enderkinesis.item.BeamSegment
import org.shipwrights.enderkinesis.item.SunderingBeamTrace
import org.shipwrights.enderkinesis.spell.Signature
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.client.BLOCK_ATLAS
import org.shipwrights.enderkinesis.client.PARTICLE_ATLAS

/** One object serves every cast. Keep per-cast client state on [SignatureEntity.clientState]. */
interface SignatureVisual {
    fun tick(e: SignatureEntity) {}

    fun render(d: SignatureDraw) {}

    /** True to draw after translucent blocks. In the entity pass, water, entities and particles
     *  paint over layers that write no depth. */
    val afterTranslucent: Boolean get() = false
}

/** The pose origin is [at], so draw at [local] points. For an entity on a ship, [at] is its world position and
 *  the pose carries the ship rotation [turn], so [local] undoes it.  Get a buffer just before you write to it,
 *  and finish before you get the next one: a buffer switch ends the previous batch. */
class SignatureDraw(
    val e: SignatureEntity, val pose: PoseStack, val buffers: MultiBufferSource, val view: SpellView,
    val at: Vec3, val partialTick: Float, val time: Float, val light: Int, val level: ClientLevel,
    val turn: Quaterniondc? = null,
) {
    val m: Matrix4f get() = pose.last().pose()

    fun local(world: Vec3): Vec3 = world.subtract(at).let { if (turn == null) it else unturn(turn, it) }

    fun glow(): VertexConsumer = buffers.getBuffer(AegisRenderType.FACE_GLOW)

    fun shade(): VertexConsumer = buffers.getBuffer(SpellVfxRenderTypes.SHADE)

    /** Use for glowing shapes that can touch the world, so they have no hard edge there.
     *  Null until the mod shaders load; then use [glow]. */
    fun softGlow(): VertexConsumer? = softGlowBuffer(buffers, time)

    fun softShade(): VertexConsumer? =
        if (SpellVfxRenderTypes.isSoftGlowReady()) Untextured.SHADE.bind(buffers.getBuffer(SpellVfxRenderTypes.softShade(PARTICLE_ATLAS)), time) else null

    /** Draws only for the first [ticks] of the cast. [target] is in world coordinates. */
    fun castFlash(target: Vec3, rgb: Int, ticks: Float = FLASH_TICKS, width: Double = 0.18) {
        if (age >= ticks) return
        val from = hand() ?: return
        val f = 1f - age / ticks
        val c = softGlow() ?: glow()
        val a = local(from); val b = local(target)
        SpellShapes.strip(c, m, view, listOf(a, b), { width * 2.5 * f }, rgb, 0.35f * f, soft = true)
        SpellShapes.strip(c, m, view, listOf(a, b), { width * f }, 0xFFFFFF, 0.8f * f, soft = false)
        SpellShapes.disc(c, m, view, b, 0.8 * f + 0.3, rgb, 0.6f * f)
    }

    /** Null until the mod shaders load, as are the other textured buffers. */
    fun blocks(): VertexConsumer? = textured(BLOCK_ATLAS, Kind.SPRITE)

    fun solidBlocks(): VertexConsumer? = textured(BLOCK_ATLAS, Kind.SOLID)

    fun glowBlocks(): VertexConsumer? = textured(BLOCK_ATLAS, Kind.GLOW)

    fun particles(): VertexConsumer? = textured(PARTICLE_ATLAS, Kind.SPRITE)

    fun glowParticles(): VertexConsumer? = textured(PARTICLE_ATLAS, Kind.GLOW)

    private enum class Kind { SPRITE, SOLID, GLOW }

    private fun textured(texture: ResourceLocation, kind: Kind): VertexConsumer? {
        if (!SpellVfxRenderTypes.isReady()) return null
        return buffers.getBuffer(
            when (kind) {
                Kind.SPRITE -> SpellVfxRenderTypes.sprite(texture)
                Kind.SOLID -> SpellVfxRenderTypes.solid(texture)
                Kind.GLOW -> SpellVfxRenderTypes.glow(texture)
            },
        )
    }

    fun lightAt(world: Vec3): Int = SpellShapes.lightAt(level, world)

    fun hand(): Vec3? = SignatureVisuals.hand(e, partialTick)

    val progress: Float get() = e.progress(partialTick)

    val age: Float get() = e.tickCount + partialTick
}

/** Adapts untextured shapes to the particle vertex format: a white texel at full brightness. */
private class Untextured : VertexConsumer {
    private lateinit var inner: VertexConsumer
    private var u = 0f
    private var v = 0f
    private var boundAt = Float.NaN
    private var r = 255; private var g = 255; private var b = 255; private var a = 255

    /** Looks up the white texel again each frame, because a resource reload can move it. */
    fun bind(inner: VertexConsumer, time: Float): Untextured {
        this.inner = inner
        if (time != boundAt) {
            val white = SpellArt.particle("flash")
            u = (white.u0 + white.u1) / 2
            v = (white.v0 + white.v1) / 2
            boundAt = time
        }
        r = 255; g = 255; b = 255; a = 255
        return this
    }

    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer { inner.vertex(x, y, z); return this }
    override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer { this.r = r; this.g = g; this.b = b; this.a = a; return this }
    override fun uv(u: Float, v: Float): VertexConsumer = this
    override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
    override fun uv2(u: Int, v: Int): VertexConsumer = this
    override fun normal(x: Float, y: Float, z: Float): VertexConsumer = this
    override fun endVertex() { inner.uv(u, v).color(r, g, b, a).uv2(SpellShapes.FULL_BRIGHT).endVertex() }
    override fun defaultColor(r: Int, g: Int, b: Int, a: Int) {}
    override fun unsetDefaultColor() {}

    companion object {
        // Render thread only. SignatureDraw gets one buffer at a time, so one of each is enough.
        val GLOW = Untextured()
        val SHADE = Untextured()
    }
}

const val FLASH_TICKS = 5f

/** Soft glow for untextured shapes outside a [SignatureDraw]. Null until the mod shaders load. Render thread only. */
fun softGlowBuffer(buffers: MultiBufferSource, time: Float): VertexConsumer? =
    if (SpellVfxRenderTypes.isSoftGlowReady()) Untextured.GLOW.bind(buffers.getBuffer(SpellVfxRenderTypes.softGlow(PARTICLE_ATLAS)), time) else null

object SignatureVisuals {
    private val ALL: Map<Signature, SignatureVisual> by lazy {
        ReferenceSignatureVisuals.ALL + BodySignatureVisuals.ALL + WaveSignatureVisuals.ALL + FieldSignatureVisuals.ALL + LinkSignatureVisuals.ALL
    }

    fun of(signature: Signature): SignatureVisual? = ALL[signature]

    // Render thread only: filled in the entity pass, emptied in the spell pass of the same frame.
    private val deferred = ArrayList<SignatureEntity>()
    private const val DEFERRED_MAX = 256

    /** The entity pass already culled [e]. */
    fun defer(e: SignatureEntity) {
        if (deferred.size >= DEFERRED_MAX) deferred.clear()
        deferred += e
    }

    /** Ends the last batch, so that it draws before the volumes at renderLevel TAIL. */
    fun renderDeferred(pose: PoseStack, buffers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, partialTick: Float) {
        if (deferred.isEmpty()) return
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) { deferred.clear(); return }
        val camera = mc.gameRenderer.mainCamera
        val left = Vec3(camera.leftVector); val up = Vec3(camera.upVector)
        val time = SpellClock.time(level, partialTick)
        for (e in deferred) {
            if (e.isRemoved) continue
            val visual = of(e.signature) ?: continue
            val at = e.getPosition(partialTick)
            val view = SpellView(Vec3(camX - at.x, camY - at.y, camZ - at.z), left, up)
            pose.pushPose()
            pose.translate(at.x - camX, at.y - camY, at.z - camZ)
            visual.render(SignatureDraw(e, pose, buffers, view, at, partialTick, time, mc.entityRenderDispatcher.getPackedLightCoords(e, partialTick), level))
            pose.popPose()
        }
        deferred.clear()
        (buffers as? MultiBufferSource.BufferSource)?.endLastBatch()
    }

    fun init() {
        SignatureEntity.clientTick = { e -> of(e.signature)?.tick(e) }
    }

    /** In the local player's first-person view, this is the drawn palm. */
    fun hand(e: SignatureEntity, partialTick: Float): Vec3? {
        val owner = e.owner() ?: return null
        if (owner is Player && SpellHand.firstPerson(owner)) {
            val arm = if (e.hand == InteractionHand.MAIN_HAND) owner.mainArm else owner.mainArm.opposite
            return SpellHand.palmInWorld(arm)
        }
        return SpellCasting.handOrigin(owner, e.hand, partialTick)
    }

    /** Traces as the server does, then cuts where the beam feeds a merged beam. Also lights the
     *  Aegis boxes it touches. For a split or second ray, set [main] false: merges cut only the main beam. */
    fun beamPath(e: SignatureEntity, partialTick: Float, reach: Double, from: Vec3? = null, dir: Vec3? = null, main: Boolean = true): List<BeamSegment>? {
        val owner = e.owner() ?: return null
        val level = e.level()
        val origin = from ?: hand(e, partialTick) ?: return null
        val aim = dir ?: SpellCasting.handAim(owner, origin, partialTick)
        val shields = SunderingBeamTrace.collectWieldedShields(level, owner)
        val traced = SunderingBeamTrace.trace(level, owner, origin, aim, reach, shields).segments
        val segments = if (main) ScrollBeamClient.clip(owner.id, traced) else traced
        AegisContacts.touchedByBeam(segments, Spell(e.signature.glyphs), e.id)
        return segments
    }
}

class SignatureRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<SignatureEntity>(ctx) {

    override fun render(e: SignatureEntity, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val visual = SignatureVisuals.of(e.signature) ?: return
        if (visual.afterTranslucent) { SignatureVisuals.defer(e); return }
        val level = Minecraft.getInstance().level ?: return
        val camera = entityRenderDispatcher.camera
        val placed = placeOnShip(level, e.getPosition(partialTick))
        val at = placed.first
        val turn = placed.second
        fun local(v: Vec3) = if (turn == null) v else unturn(turn, v)
        val view = SpellView(local(camera.position.subtract(at)), local(Vec3(camera.leftVector)), local(Vec3(camera.upVector)))
        visual.render(SignatureDraw(e, pose, buffers, view, at, partialTick, SpellClock.time(level, partialTick), light, level, turn))
    }

    // VS2 draws a shipyard entity at the ship's render transform, so the pose origin is the world point and its axes are the ship's.
    @OptIn(GameTickOnly::class)
    private fun placeOnShip(level: ClientLevel, pos: Vec3): Pair<Vec3, Quaterniondc?> {
        val transform = (level.getShipManagingPos(pos.x, pos.y, pos.z) as? ClientShip)?.renderTransform ?: return pos to null
        val world = transform.shipToWorld.transformPosition(Vector3d(pos.x, pos.y, pos.z))
        return Vec3(world.x, world.y, world.z) to transform.shipToWorldRotation
    }

    /** The visuals reach far past the bounding box, so do not cull by frustum. */
    override fun shouldRender(e: SignatureEntity, frustum: Frustum, x: Double, y: Double, z: Double): Boolean = e.shouldRender(x, y, z)

    override fun getTextureLocation(e: SignatureEntity): ResourceLocation = BLOCK_ATLAS
}

private fun unturn(turn: Quaterniondc, v: Vec3): Vec3 =
    turn.transformInverse(Vector3d(v.x, v.y, v.z)).let { Vec3(it.x, it.y, it.z) }
