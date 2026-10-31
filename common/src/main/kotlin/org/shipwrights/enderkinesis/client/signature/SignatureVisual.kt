package org.shipwrights.enderkinesis.client.signature

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.shipwrights.enderkinesis.client.AegisClient
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

/**
 * How a [Signature] looks and sounds on the client. One object serves every cast of its
 * signature; a cast's own client state lives on its entity ([SignatureEntity.clientState]).
 */
interface SignatureVisual {
    /** Each client tick: particles, sounds. */
    fun tick(e: SignatureEntity) {}

    /** Each frame it's in view. */
    fun render(d: SignatureDraw) {}
}

/**
 * One frame's drawing of a [SignatureEntity]. The pose is at [at], the entity's position this
 * frame, so draw at [local] points. Get a buffer just before writing to it and finish writing
 * before getting the next: switching buffers ends the batch before.
 */
class SignatureDraw(
    val e: SignatureEntity, val pose: PoseStack, val buffers: MultiBufferSource, val view: SpellView,
    val at: Vec3, val partialTick: Float, val time: Float, val light: Int, val level: ClientLevel,
) {
    val m: Matrix4f get() = pose.last().pose()

    /** [world] in the pose's space. */
    fun local(world: Vec3): Vec3 = world.subtract(at)

    /** Additive, untextured light ([SpellShapes]' untextured shapes). */
    fun glow(): VertexConsumer = buffers.getBuffer(AegisRenderType.FACE_GLOW)

    /** Translucent, untextured: darkness, smoke. */
    fun shade(): VertexConsumer = buffers.getBuffer(SpellVfxRenderTypes.SHADE)

    /** [glow], depth-blended: light that fades out where the scene is just behind it instead of
     *  ending in a hard line where it cuts into the ground or a wall. Takes [SpellShapes]'
     *  untextured shapes. Use it for any glowing shape that can meet the world. Null until the
     *  mod's shaders have loaded; fall back to [glow]. */
    fun softGlow(): VertexConsumer? =
        if (SpellVfxRenderTypes.isSoftGlowReady()) Untextured(buffers.getBuffer(SpellVfxRenderTypes.softGlow(TextureAtlas.LOCATION_PARTICLES))) else null

    /** [shade], depth-blended the same way: shadow and smoke that fade into what they touch. */
    fun softShade(): VertexConsumer? =
        if (SpellVfxRenderTypes.isSoftGlowReady()) Untextured(buffers.getBuffer(SpellVfxRenderTypes.softShade(TextureAtlas.LOCATION_PARTICLES))) else null

    /**
     * A short flash of light from the hand to [target] as a spell that lands somewhere at once
     * is cast: drawn for the first [ticks] of [e]'s life, fading as it goes. [target] is in the
     * world.
     */
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

    /** Translucent block textures ([org.shipwrights.enderkinesis.client.SpellArt.block] sprites);
     *  null until the mod's shaders have loaded. */
    fun blocks(): VertexConsumer? = textured(TextureAtlas.LOCATION_BLOCKS, Kind.SPRITE)

    /** Opaque block textures, written to depth: blocks that stand in the world. */
    fun solidBlocks(): VertexConsumer? = textured(TextureAtlas.LOCATION_BLOCKS, Kind.SOLID)

    /** Block textures as additive light. */
    fun glowBlocks(): VertexConsumer? = textured(TextureAtlas.LOCATION_BLOCKS, Kind.GLOW)

    /** Translucent particle textures ([org.shipwrights.enderkinesis.client.SpellArt.particle]). */
    fun particles(): VertexConsumer? = textured(TextureAtlas.LOCATION_PARTICLES, Kind.SPRITE)

    /** Particle textures as additive light. */
    fun glowParticles(): VertexConsumer? = textured(TextureAtlas.LOCATION_PARTICLES, Kind.GLOW)

    private enum class Kind { SPRITE, SOLID, GLOW }

    @Suppress("DEPRECATION")
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

    /** The world's light at a world point. */
    fun lightAt(world: Vec3): Int = SpellShapes.lightAt(level, world)

    /** The caster's casting hand in the world this frame, or null when the caster is gone. */
    fun hand(): Vec3? = SignatureVisuals.hand(e, partialTick)

    /** 0..1 through its life this frame. */
    val progress: Float get() = e.progress(partialTick)

    /** Ticks since it was cast, this frame. */
    val age: Float get() = e.tickCount + partialTick
}

/** Writes [SpellShapes]' untextured shapes (position and colour) into a textured, lightmapped
 *  buffer (the particle format), on a plain white texel at full brightness. */
private class Untextured(private val inner: VertexConsumer) : VertexConsumer {
    private val white = SpellArt.particle("flash")
    private val u = (white.u0 + white.u1) / 2
    private val v = (white.v0 + white.v1) / 2
    private var r = 255; private var g = 255; private var b = 255; private var a = 255

    override fun vertex(x: Double, y: Double, z: Double): VertexConsumer { inner.vertex(x, y, z); return this }
    override fun color(r: Int, g: Int, b: Int, a: Int): VertexConsumer { this.r = r; this.g = g; this.b = b; this.a = a; return this }
    override fun uv(u: Float, v: Float): VertexConsumer = this
    override fun overlayCoords(u: Int, v: Int): VertexConsumer = this
    override fun uv2(u: Int, v: Int): VertexConsumer = this
    override fun normal(x: Float, y: Float, z: Float): VertexConsumer = this
    override fun endVertex() { inner.uv(u, v).color(r, g, b, a).uv2(SpellShapes.FULL_BRIGHT).endVertex() }
    override fun defaultColor(r: Int, g: Int, b: Int, a: Int) {}
    override fun unsetDefaultColor() {}
}

/** How long [SignatureDraw.castFlash] lasts by default, in ticks. */
const val FLASH_TICKS = 5f

/** Every signature's visual, and the client's hooks into [SignatureEntity]. */
object SignatureVisuals {
    private val ALL: Map<Signature, SignatureVisual> by lazy {
        ReferenceSignatureVisuals.ALL + BodySignatureVisuals.ALL + WaveSignatureVisuals.ALL + FieldSignatureVisuals.ALL + LinkSignatureVisuals.ALL
    }

    fun of(signature: Signature): SignatureVisual? = ALL[signature]

    fun init() {
        SignatureEntity.clientTick = { e -> of(e.signature)?.tick(e) }
    }

    /** Where [e]'s caster's casting hand is: in the local player's own first-person view, the
     *  palm drawn there; otherwise where casts leave a hand. */
    fun hand(e: SignatureEntity, partialTick: Float): Vec3? {
        val owner = e.owner() ?: return null
        if (owner is Player && SpellHand.firstPerson(owner)) {
            val arm = if (e.hand == InteractionHand.MAIN_HAND) owner.mainArm else owner.mainArm.opposite
            return SpellHand.palmInWorld(arm)
        }
        return SpellCasting.handOrigin(owner, e.hand, partialTick)
    }

    /**
     * [e]'s caster's beam as the client draws it: from the hand along their aim, [reach] long,
     * reflected off Aegis boxes and cut at a loop as the server traces it, then cut where it
     * feeds a merged beam. Also lights the Aegis boxes it touches. Null when the caster is gone.
     * From another [from] along [dir] (a split, a second ray), pass [main] false: only the
     * caster's main beam is cut by the server's merges.
     */
    fun beamPath(e: SignatureEntity, partialTick: Float, reach: Double, from: Vec3? = null, dir: Vec3? = null, main: Boolean = true): List<BeamSegment>? {
        val owner = e.owner() ?: return null
        val level = e.level()
        val origin = from ?: hand(e, partialTick) ?: return null
        val aim = dir ?: SpellCasting.handAim(owner, origin, partialTick)
        val shields = SunderingBeamTrace.collectWieldedShields(level, owner) + AegisClient.snapshotDebugBoxes()
        val traced = SunderingBeamTrace.trace(level, owner, origin, aim, reach, shields).segments
        val segments = if (main) ScrollBeamClient.clip(owner.id, traced) else traced
        AegisContacts.touchedByBeam(segments, Spell(e.signature.glyphs))
        return segments
    }
}

/** Draws every [SignatureEntity] through its signature's [SignatureVisual]. */
class SignatureRenderer(ctx: EntityRendererProvider.Context) : EntityRenderer<SignatureEntity>(ctx) {

    override fun render(e: SignatureEntity, yaw: Float, partialTick: Float, pose: PoseStack, buffers: MultiBufferSource, light: Int) {
        val visual = SignatureVisuals.of(e.signature) ?: return
        val level = Minecraft.getInstance().level ?: return
        val camera = entityRenderDispatcher.camera
        val at = e.getPosition(partialTick)
        val view = SpellView(camera.position.subtract(at), Vec3(camera.leftVector), Vec3(camera.upVector))
        visual.render(SignatureDraw(e, pose, buffers, view, at, partialTick, SpellClock.time(level, partialTick), light, level))
    }

    /** Its visuals reach far past its box: drawn whenever it's in range. */
    override fun shouldRender(e: SignatureEntity, frustum: Frustum, x: Double, y: Double, z: Double): Boolean = e.shouldRender(x, y, z)

    @Suppress("DEPRECATION")
    override fun getTextureLocation(e: SignatureEntity): ResourceLocation = TextureAtlas.LOCATION_BLOCKS
}
