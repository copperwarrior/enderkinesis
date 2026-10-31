package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.renderer.texture.TextureAtlas
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellDeaths
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A mob killed by a spell element dies that element's way ([SpellDeaths]):
 *
 * - **Heat:** catches fire, chars black, then crumbles away top-down into smoke and falling ash.
 * - **Growth:** roots and vines climb it and drag it down into the earth, leaving fresh grass.
 * - **Purity:** turns to white crystal, then bursts into shards that scatter and skid.
 * - **Gravity:** crushed flat with a ring of dust, then the flattened body fades.
 * - **Chaos:** flickers out of place, then bursts into dust in Chaos's colour that drifts away.
 * - **Blood:** its colour drains out of it into a pool, then it falls, grey.
 * - **Sunfire:** light cracks out of it, a shaft of sunlight slams down, and it bursts apart in
 *   burning dust that rains into a smouldering pile.
 * - **Poison:** sickens green as potion swirls rise off it, falls over, and lets out its gas.
 * - **Void:** the Ygann mass creeps over it texel by texel, eyes opening in it, then it
 *   disintegrates into dust drifting up.
 *
 * The body is drawn through the `rendertype_spell_death` shader ([body], [EntityPasses]); the rest is
 * particles and a little geometry, which outlast the body.
 */
object DeathStyles {
    private class Death(val element: Element, val start: Long, var feet: Vec3, val height: Double, val width: Double)

    // Render thread only.
    private val deaths = HashMap<Int, Death>()
    private var shader: ShaderInstance? = null
    private val types = HashMap<ResourceLocation, RenderType>()
    private val MASS: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_mass_sea.png")

    /** How long a death's particles and geometry go on, in ticks. */
    private const val FX_TICKS = 110

    fun registerShader(instance: ShaderInstance) {
        shader = instance
    }

    fun add(level: ClientLevel, at: Vec3, height: Float, elementOrdinal: Int, entityId: Int) {
        val element = Element.entries.getOrNull(elementOrdinal) ?: return
        SpellDeaths.setClient(entityId, element, level.gameTime)
        val entity = level.getEntity(entityId) as? LivingEntity
        deaths[entityId] = Death(element, SpellClock.now(level), at, height.toDouble(), (entity?.bbWidth ?: 0.6f).toDouble())
        if (element == Element.CHAOS) {
            // Lightning snapping round it as it flickers out of place.
            val r = Random(entityId)
            val c = at.add(0.0, height * 0.5, 0.0)
            repeat(2) { SpellVfxClient.addLocal(SpellVfx.Kind.ARC, c, c.add(SpellGeom.spread(r).scale(1.2)), 1f, Element.CHAOS.color, 6, r.nextInt()) }
        }
        if (element == Element.BLOOD) {
            // The blood draining out of it into a pool.
            val r = Random(entityId)
            repeat(3) {
                val from = at.add((r.nextDouble() - 0.5) * 0.5, height * (0.3 + 0.6 * r.nextDouble()), (r.nextDouble() - 0.5) * 0.5)
                SpellVfxClient.addLocal(SpellVfx.Kind.STREAM, from, at.add((r.nextDouble() - 0.5) * 0.8, 0.02, (r.nextDouble() - 0.5) * 0.8), 1f, Element.BLOOD.color, 32, r.nextInt())
            }
        }
    }

    fun isEmpty(): Boolean = deaths.isEmpty()

    fun reset() {
        deaths.clear()
        SpellDeaths.resetClient()
    }

    // ---- The body --------------------------------------------------------------------------------

    private fun styleId(element: Element): Int = when (element) {
        Element.HEAT -> 1
        Element.GROWTH -> 2
        Element.PURITY -> 3
        Element.GRAVITY -> 4
        Element.CHAOS -> 5
        Element.BLOOD -> 6
        Element.SUNFIRE -> 7
        Element.POISON -> 8
        Element.VOID -> 9
        else -> 0
    }

    fun type(texture: ResourceLocation): RenderType = types.getOrPut(texture) {
        RenderType.create(
            "${EnderkinesisMod.MOD_ID}_spell_death_${texture.path}",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            256,
            true,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.ShaderStateShard { shader })
                .setTextureState(RenderStateShard.TextureStateShard(texture, false, false))
                // The Ygann mass for Void's death, in sampler 3.
                .setTexturingState(RenderStateShard.TexturingStateShard("${EnderkinesisMod.MOD_ID}_ygann_mass", { RenderSystem.setShaderTexture(3, MASS) }, {}))
                .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                .setCullState(RenderStateShard.NO_CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(true),
        )
    }

    /** How a dying body is drawn this frame: its death's style and time in the vertex colours,
     *  how see-through it is, and how it's moved (in its own space, feet at the origin). */
    class Body(val style: Int, val progress: Int, val alpha: Float, val gone: Boolean, val height: Float, val adjust: ((PoseStack) -> Unit)?)

    /** How [entity]'s body is drawn, if it's dying to a spell; null leaves it to vanilla. */
    fun body(entity: LivingEntity, partialTick: Float): Body? {
        val element = SpellDeaths.of(entity) ?: return null
        if (entity.deathTime <= 0 || shader == null) return null
        val d = (entity.deathTime - 1 + partialTick) / 20f
        val time = entity.tickCount + partialTick
        // Gone already: the rest is particles and geometry.
        val gone = when (element) {
            Element.PURITY -> d > 0.9f
            Element.CHAOS -> d >= 0.3f || sin(time * 2.2f) <= -0.3f
            Element.SUNFIRE -> d >= 0.2f
            Element.BLOOD -> d > 2.3f
            Element.POISON -> d > 2.2f
            else -> false
        }
        var fade = 1f
        val adjust: ((PoseStack) -> Unit)? = when (element) {
            Element.GROWTH -> { pose -> pose.translate(0.0, -(entity.bbHeight * 1.08 + 0.05) * ((d - 0.7) / 1.8).coerceIn(0.0, 1.0).pow(1.6), 0.0) }
            Element.GRAVITY -> {
                val slam = (d / 0.3f).coerceIn(0f, 1f)
                val sy = if (d > 0.3f) 0.12f + 0.06f * exp(-(d - 0.3f) * 6f) * sin((d - 0.3f) * 30f) else 1f - 0.88f * slam * slam
                fade = 1f - smooth((d - 2.2f) / 0.8f)
                val squash: (PoseStack) -> Unit = { pose -> pose.scale(1f + (1f - sy) * 0.7f, sy, 1f + (1f - sy) * 0.7f) }
                squash
            }
            Element.SUNFIRE -> { pose -> pose.translate(sin(time * 3.1).toDouble() * 0.04, 0.0, cos(time * 2.7).toDouble() * 0.04) }
            else -> null
        }
        return Body(styleId(element), (d * 40f).toInt().coerceIn(0, 255), fade, gone, entity.bbHeight.coerceAtLeast(0.1f), adjust)
    }

    // ---- Per tick: particles -------------------------------------------------------------------

    fun tick(level: ClientLevel) {
        val now = SpellClock.now(level)
        deaths.values.removeIf { now - it.start > FX_TICKS }
        val random = Random(level.random.nextLong())
        for ((id, death) in deaths) {
            (level.getEntity(id) as? LivingEntity)?.let { death.feet = it.position() }
            tickDeath(level, death, (now - death.start).toFloat(), random)
        }
    }

    /** A random point in the body: at the death's height, within its width. */
    private fun bodyPoint(death: Death, r: Random, sink: Double = 0.0): Vec3 =
        death.feet.add((r.nextDouble() - 0.5) * death.width, r.nextDouble() * death.height - sink, (r.nextDouble() - 0.5) * death.width)

    private fun tickDeath(level: ClientLevel, death: Death, t: Float, r: Random) {
        val d = t / 20f
        fun crossed(at: Float) = d - 0.05f < at && d >= at
        fun add(p: ParticleOptions, at: Vec3, v: Vec3 = Vec3.ZERO) = level.addParticle(p, at.x, at.y, at.z, v.x, v.y, v.z)
        fun mote(m: SpellMote, at: Vec3, v: Vec3 = Vec3.ZERO) = add(EKParticles.mote(m), at, v)
        val f = death.feet
        when (death.element) {
            Element.HEAT -> if (d in 1.0f..2.4f) repeat(2) {
                // Smoke and ash off the top of what's left, as it crumbles down.
                val top = death.height * (1 - smooth((d - 1.0) / 1.4))
                val p = f.add((r.nextDouble() - 0.5) * death.width, top, (r.nextDouble() - 0.5) * death.width)
                if (r.nextInt(3) == 0) mote(SpellMote.ASH, p) else mote(SpellMote.HEAT_SMOKE, p, Vec3(0.005, 0.04, 0.0))
            }
            Element.GROWTH -> if (d in 0.7f..2.5f && r.nextFloat() < 0.4f) {
                // Earth and leaves thrown up as it goes down.
                val an = r.nextDouble() * Math.PI * 2
                val p = f.add(cos(an) * 0.3, 0.05, sin(an) * 0.3)
                val v = Vec3(cos(an) * 0.06, 0.12, sin(an) * 0.06)
                if (r.nextInt(4) == 0) add(EKParticles.leafChip(), p, v)
                else add(BlockParticleOption(ParticleTypes.BLOCK, if (r.nextBoolean()) Blocks.DIRT.defaultBlockState() else Blocks.ROOTED_DIRT.defaultBlockState()), p, v)
            }
            Element.PURITY -> if (d < 0.9f) { if (r.nextFloat() < 0.5f) mote(SpellMote.PURITY_GLITTER, bodyPoint(death, r)) }
            Element.GRAVITY -> if (crossed(0.3f)) repeat(40) { i ->
                // A ring of dust kicked out by the thud.
                val an = i / 40.0 * Math.PI * 2 + r.nextDouble() * 0.2
                mote(SpellMote.SPRAY_SMOKE, f.add(cos(an) * 0.4, 0.08, sin(an) * 0.4), Vec3(cos(an) * 0.08, 0.005, sin(an) * 0.08))
            }
            Element.CHAOS -> if (crossed(0.3f)) repeat(45) { i ->
                // Dust in Chaos's colour, churning outward and drifting.
                val p = bodyPoint(death, r)
                val out = p.subtract(f.add(0.0, death.height * 0.5, 0.0)).normalize()
                val rgb = when (i % 3) { 0 -> SpellGeom.darken(Element.CHAOS.color, 0.55f); else -> Element.CHAOS.color }
                val dust = DustParticleOptions(Vector3f(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f), 1.2f)
                add(dust, p, out.scale(0.03).add((r.nextDouble() - 0.5) * 0.02, (r.nextDouble() - 0.35) * 0.02, (r.nextDouble() - 0.5) * 0.02))
            }
            Element.BLOOD -> {}
            Element.SUNFIRE -> {
                if (d < 0.2f) mote(SpellMote.SUNFIRE_DUST, bodyPoint(death, r))
                if (crossed(0.2f)) repeat(110) { i ->
                    // The blast: burning dust and embers thrown out hard, raining into a pile.
                    val p = bodyPoint(death, r)
                    val dir = p.subtract(f.add(0.0, 0.9, 0.0)).add(SpellGeom.gauss(r) * 0.4, 0.5 + r.nextDouble() * 0.6, SpellGeom.gauss(r) * 0.4).normalize()
                    val v = dir.scale(0.15 + 0.25 * r.nextDouble())
                    when (i % 4) {
                        0 -> mote(SpellMote.SUNFIRE_SPARK, p, v)
                        1 -> mote(SpellMote.FLAME, p, v.scale(0.6))
                        else -> mote(SpellMote.HEAT_EMBER, p, v)
                    }
                }
                // What's left smoulders: flames licking the pile, sparks drifting up.
                if (d > 0.5f && d < 2.6f && r.nextFloat() < 0.5f) {
                    val an = r.nextDouble() * Math.PI * 2
                    val rr = 0.15 + 0.35 * r.nextDouble()
                    val p = f.add(cos(an) * rr, 0.1, sin(an) * rr)
                    if (r.nextBoolean()) mote(SpellMote.EMBER_FLAME, p, Vec3(0.0, 0.01, 0.0)) else mote(SpellMote.SUNFIRE_SPARK, p, Vec3(0.0, 0.05, 0.0))
                }
            }
            Element.POISON -> {
                if (d < 2.2f && r.nextFloat() < 0.6f) mote(SpellMote.POISON_SWIRL, bodyPoint(death, r), Vec3(0.0, 0.03, 0.0))
                if (crossed(2.0f)) SpellVfxClient.addLocal(SpellVfx.Kind.PUFFS, f.add(0.0, 0.3, 0.0), f, 0.8f, Element.POISON.color, 30, r.nextInt())
            }
            Element.VOID -> {
                if (d in 0.3f..1.0f && r.nextFloat() < 0.15f) mote(SpellMote.YGANN_EYE, bodyPoint(death, r))
                if (d in 0.8f..2.6f) repeat(2) {
                    val p = bodyPoint(death, r)
                    if (r.nextInt(4) == 0) mote(SpellMote.VOID_MOTE, p, Vec3(0.0, 0.025, 0.0)) else mote(SpellMote.MASS_RISE, p, Vec3(0.0, 0.02, 0.0))
                }
            }
            else -> {}
        }
    }

    // ---- Geometry that outlasts the body -------------------------------------------------------

    /** [pose] is at the world origin. */
    fun render(pose: PoseStack, buffers: MultiBufferSource, level: ClientLevel, view: SpellView, time: Float) {
        if (deaths.isEmpty()) return
        val m = pose.last().pose()
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (death in deaths.values) {
            val d = (time - death.start) / 20f
            val f = death.feet
            when (death.element) {
                Element.PURITY -> if (d in 0.9f..1.15f) SpellShapes.disc(glow, m, view, f.add(0.0, death.height * 0.5, 0.0), 1.3, 0xFFFFFF, 0.9f * (1f - (d - 0.9f) / 0.25f))
                Element.SUNFIRE -> {
                    val e = d - 0.2f
                    if (e >= 0f) {
                        // A shaft of sunlight slamming down, and a ring along the ground.
                        val beam = maxOf(0f, 1f - e / 0.35f)
                        if (beam > 0f) {
                            val col = listOf(f.add(0.0, 12.0, 0.0), f.add(0.0, 6.0, 0.0), f.add(0.0, 2.0, 0.0), f.add(0.0, 0.02, 0.0))
                            SpellShapes.strip(glow, m, view, col, { 2.4 * beam + 0.3 }, Element.SUNFIRE.color, 0.5f * beam, true)
                            SpellShapes.strip(glow, m, view, col, { 0.7 * beam + 0.1 }, 0xFFF4D0, 0.9f * beam, false)
                        }
                        SpellShapes.ring(glow, m, f.add(0.0, 0.03, 0.0), 3.4 * easeOut(minOf(1.0, e * 3.5)), 0.3, 0xFFD36A, 0.8f * maxOf(0f, 1f - e * 2.5f), 0.0)
                    }
                }
                else -> {}
            }
        }
        if (!SpellVfxRenderTypes.isReady()) return
        val particles = buffers.getBuffer(SpellVfxRenderTypes.glow(TextureAtlas.LOCATION_PARTICLES))
        for (death in deaths.values) if (death.element == Element.SUNFIRE) {
            val e = (time - death.start) / 20f - 0.2f
            if (e >= 0f && e < 0.25f) SpellShapes.sprite(particles, m, view, death.feet.add(0.0, 1.0, 0.0), 5 * (0.6 + 0.4 * minOf(1f, e * 12f)), SpellArt.particle("flash"), 0xFFE2A0, 1f - e * 4f, SpellShapes.FULL_BRIGHT)
        }
        val blocks = buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_BLOCKS))
        for (death in deaths.values) {
            val d = (time - death.start) / 20f
            val f = death.feet
            val light = SpellShapes.lightAt(level, f.add(0.0, 0.5, 0.0))
            when (death.element) {
                Element.PURITY -> if (d > 0.9f) shards(blocks, m, death, d - 0.9f, light)
                Element.GROWTH -> {
                    // A patch of fresh grass where it went down.
                    val grass = smooth((d - 2.2f) / 0.5f)
                    if (grass > 0f) {
                        val s = 0.55 * grass
                        SpellShapes.texFace(blocks, m, f.add(-s, 0.012, s), f.add(s, 0.012, s), f.add(s, 0.012, -s), f.add(-s, 0.012, -s), SpellArt.block("grass_block_top"), 0x7CBD6B, 1f, light, 0.25, 0.25)
                    }
                }
                else -> {}
            }
        }
        val drops = buffers.getBuffer(SpellVfxRenderTypes.sprite(TextureAtlas.LOCATION_PARTICLES))
        for (death in deaths.values) if (death.element == Element.BLOOD) {
            val d = (time - death.start) / 20f
            val pool = 0.55 * smooth(d / 2f)
            val dry = 1f - smooth((d - 4.5f) / 1f)
            val f = death.feet
            if (pool > 0.01 && dry > 0f) SpellShapes.tquad(
                drops, m, f.add(-pool, 0.015, pool), f.add(pool, 0.015, pool), f.add(pool, 0.015, -pool), f.add(-pool, 0.015, -pool),
                SpellArt.particle("drip_land"), floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), 0x5A0610, 0.95f * dry, SpellShapes.lightAt(level, f.add(0.0, 0.5, 0.0)),
            )
        }
    }

    /** Purity's shards: thrown out of the body, falling, skidding across the ground and fading. */
    private fun shards(c: VertexConsumer, m: Matrix4f, death: Death, e: Float, light: Int) {
        val r = Random(death.start.toInt() * 31 + 7)
        val centre = death.feet.add(0.0, death.height * 0.5, 0.0)
        val calcite = SpellArt.block("calcite")
        val a = 1f - smooth((e - 1.6f) / 0.9f)
        if (a <= 0f) return
        for (i in 0 until 90) {
            val p0 = death.feet.add((r.nextDouble() - 0.5) * death.width, r.nextDouble() * death.height, (r.nextDouble() - 0.5) * death.width)
            val out = p0.subtract(centre).add(SpellGeom.gauss(r) * 0.3, 0.4 + r.nextDouble() * 0.4, SpellGeom.gauss(r) * 0.3).normalize()
            val speed = 1.5 + 2.2 * r.nextDouble()
            val vy = out.y * speed
            val g = 9.0
            val h0 = p0.y - death.feet.y
            val tLand = (vy + sqrt(vy * vy + 2 * g * h0)) / g
            val tf = minOf(e.toDouble(), tLand)
            val slide = maxOf(0.0, e - tLand)
            val skid = if (slide > 0) (1 - exp(-slide * 3)) / 3 * 0.4 else 0.0
            val p = Vec3(p0.x + out.x * speed * (tf + skid), maxOf(death.feet.y + 0.03, p0.y + vy * tf - 0.5 * g * tf * tf), p0.z + out.z * speed * (tf + skid))
            val size = 0.035 + 0.04 * r.nextDouble()
            val spin = if (slide > 0) 0.1 else 1.0
            val cube = SpellGeom.cubeCorners(p, size, r.nextDouble() * 6 + e * 6 * spin, r.nextDouble() * 6 + e * 4 * spin, r.nextDouble() * 6)
            SpellShapes.texBox(c, m, cube, calcite, 0xFFFFFF, a, light, i)
        }
    }
}
