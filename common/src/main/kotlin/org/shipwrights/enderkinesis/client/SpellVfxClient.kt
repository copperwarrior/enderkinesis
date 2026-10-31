package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import dev.architectury.event.events.client.ClientTickEvent
import net.minecraft.client.Minecraft
import net.minecraft.client.animation.KeyframeAnimations
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.TentacleAnimation
import org.shipwrights.enderkinesis.client.model.TentacleModel
import org.shipwrights.enderkinesis.entity.SpellBoltEntity
import org.shipwrights.enderkinesis.item.MysticScrollItem
import org.shipwrights.enderkinesis.registry.EKParticles
import org.shipwrights.enderkinesis.spell.AbyssalPockets
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.Shape
import org.shipwrights.enderkinesis.spell.Spell
import org.shipwrights.enderkinesis.spell.SpellCasting
import org.shipwrights.enderkinesis.spell.SpellMote
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.shipwrights.enderkinesis.spell.SpellVisuals
import org.shipwrights.enderkinesis.client.SpellGeom.easeOut
import org.shipwrights.enderkinesis.client.SpellGeom.smooth
import org.shipwrights.enderkinesis.client.SpellGeom.whiten
import org.shipwrights.enderkinesis.client.SpellGeom.darken
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** An immediate buffer source reuses one builder for each render type, so draw one render type at a time. */
object SpellVfxClient {

    private class Vfx(
        val kind: SpellVfx.Kind, val a: Vec3, val b: Vec3, val size: Float,
        val rgb: Int, val life: Int, val seed: Int, val start: Long,
    ) {
        /** Cache for one frame, so the three passes of a stream use the same strands. */
        var paths: List<List<Vec3>> = emptyList()
        var pathsAt = Float.NaN
    }

    // Render thread only.
    private val active = ArrayList<Vfx>()

    private val TENTACLE_TEX: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_tentacle.png")
    private val TENTACLE_GLOW_TEX: ResourceLocation = EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_tentacle_emissive.png")
    private var tentacleModel: TentacleModel? = null
    private val animVec = Vector3f()

    fun init() {
        ClientTickEvent.CLIENT_LEVEL_POST.register(ClientTickEvent.ClientLevel(::tick))
        RuneVfx.init()
    }

    fun add(kind: Int, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int) {
        val level = Minecraft.getInstance().level ?: return
        val k = SpellVfx.Kind.entries.getOrNull(kind) ?: return
        when (k) {
            SpellVfx.Kind.SIGNATURE -> {
                // Here, life is a delay: the burst of an Area wave waits for the wave.
                Element.entries.getOrNull(rgb)?.let { SpellImpacts.spawn(level, it, a, size.toDouble(), seed, life) }
                return
            }
            SpellVfx.Kind.AREA, SpellVfx.Kind.RUNE_CIRCLE -> return AreaWaves.add(level, k, a, b, size, rgb, seed)
            SpellVfx.Kind.IMBUE, SpellVfx.Kind.GLYPH_BURST, SpellVfx.Kind.FIZZLE, SpellVfx.Kind.IMBUED_SHOT -> return ImbueVfx.add(level, k, a, rgb, seed)
            SpellVfx.Kind.INSCRIBE, SpellVfx.Kind.RUNE_BURST -> return RuneVfx.add(level, k, a, size, rgb, seed)
            SpellVfx.Kind.AEGIS_DEFLECT, SpellVfx.Kind.AEGIS_STRIKE -> return AegisContacts.add(level, k, a, b, rgb, seed)
            SpellVfx.Kind.DEATH -> return DeathStyles.add(level, a, size, rgb, seed)
            SpellVfx.Kind.CLEANSE_DRAIN, SpellVfx.Kind.SHUFFLE_SLING, SpellVfx.Kind.VOID_TETHER -> return EffectFlows.add(level, k, a, b, size, rgb, life, seed)
            SpellVfx.Kind.SELF_CAST -> return SelfCastVisuals.add(level, k, a, b, size, life, seed)
            SpellVfx.Kind.BLOCK_SHIFT -> return BlockShifts.add(level, a, b, Element.entries.getOrNull(size.toInt()), rgb)
            SpellVfx.Kind.BLOCK_SWAP -> return BlockShifts.swap(level, a, b, rgb, size.toInt())
            SpellVfx.Kind.PRESS -> BlockShifts.trackFall(seed, SpellClock.now(level))
            SpellVfx.Kind.BEAM_CHARGE, SpellVfx.Kind.BEAM_PRESS -> return BeamClashes.add(k, a, b, size, rgb, life, seed, SpellClock.now(level))
            SpellVfx.Kind.BEAM_EXPLOSION, SpellVfx.Kind.SHOCKWAVE -> {
                SpellImpacts.blast(level, k, a, size.toDouble(), seed)
                ScrollBeamClient.snap(a)
                // Put the ring on the ground below, if the ground is near.
                val ground = AreaWaves.groundBelow(level, a)?.let { Vec3(a.x, it + 0.03, a.z) } ?: a
                push(Vfx(k, a, ground, size, rgb, life.coerceIn(1, MAX_LIFE), seed, SpellClock.now(level)))
                return
            }
            else -> {}
        }
        push(Vfx(k, a, b, size, rgb, life.coerceIn(1, MAX_LIFE), seed, SpellClock.now(level)))
    }

    fun addLocal(kind: SpellVfx.Kind, a: Vec3, b: Vec3, size: Float, rgb: Int, life: Int, seed: Int) {
        val level = Minecraft.getInstance().level ?: return
        push(Vfx(kind, a, b, size, rgb, life.coerceIn(1, MAX_LIFE), seed, SpellClock.now(level)))
    }

    private fun push(vfx: Vfx) {
        if (active.size >= MAX_ACTIVE) active.removeAt(0)
        active += vfx
    }

    fun reset() {
        SpellClock.forget()
        SpellInHand.reset()
        active.clear()
        SpellImpacts.reset()
        AreaWaves.reset()
        ImbueVfx.reset()
        RuneVfx.reset()
        BlockShifts.reset()
        AegisContacts.reset()
        DeathStyles.reset()
        BeamClashes.reset()
        EffectFlows.reset()
        SelfCastVisuals.reset()
        EnvironmentFeed.reset()
    }

    private fun tick(level: ClientLevel) {
        SpellClock.tick()
        val now = SpellClock.now(level)
        active.removeIf { now - it.start > it.life }
        SpellImpacts.tick(level)
        AreaWaves.tick(level)
        ImbueVfx.tick(level)
        RuneVfx.tick(level)
        BlockShifts.tick(level)
        AegisContacts.tick(level)
        DeathStyles.tick(level)
        BeamClashes.tick(SpellClock.now(level))
        EffectFlows.tick(level)
        SelfCastVisuals.tick(level)
        EnvironmentFeed.tick(level)
        val random = Random(level.random.nextLong())
        for (player in level.players()) {
            if (!player.isUsingItem) continue
            val spell = MysticScrollItem.spellOf(player.useItem) ?: continue
            when (spell.castShape) {
                Shape.SPRAY -> spray(level, player, spell, random)
                Shape.BOLT -> boltCharge(level, player, spell, random)
                else -> {}
            }
        }
        for (entity in level.entitiesForRendering()) if (entity is SpellBoltEntity) boltTrail(level, entity, random)
        for (vfx in active) if (vfx.kind == SpellVfx.Kind.CHAIN) chainPayload(level, vfx, now.toFloat(), random)
        for (vfx in active) if (vfx.kind == SpellVfx.Kind.LASH && now - vfx.start < vfx.life * 0.6) lashMotes(level, vfx, t(vfx, now.toFloat()))
    }

    /** The throw speed lets each particle, slowed by its friction for its life, reach the end of the cone. */
    private fun spray(level: ClientLevel, player: Player, spell: Spell, random: Random) {
        val origin = SpellCasting.handOrigin(player, player.usedItemHand)
        val aim = SpellCasting.handAim(player, origin)
        val reach = SpellCasting.sprayLength(spell.magnitude(spell.shapeElement!!))
        val (s1, s2) = SpellGeom.basis(aim)
        fun jit(): Vec3 = aim.add(SpellGeom.gauss(random) * SPRAY_SPREAD, SpellGeom.gauss(random) * SPRAY_SPREAD, SpellGeom.gauss(random) * SPRAY_SPREAD).normalize()
        fun throwOut(mote: SpellMote, dir: Vec3, speed: Double = 1.0) {
            val v = dir.scale(reach / carry(mote.friction, mote.life) * (0.75 + 0.35 * random.nextDouble()) * speed)
            level.addParticle(EKParticles.mote(mote), origin.x, origin.y, origin.z, v.x, v.y, v.z)
        }
        for (element in spell.magnitudes.keys) when (element) {
            Element.HEAT -> {
                val spin = SpellClock.now(level) * FIRE_SPIN
                for (i in 0 until 8) {
                    val angle = spin + i * (2 * Math.PI / 8)
                    val r = SPRAY_SPREAD * (0.4 + 0.6 * random.nextDouble())
                    throwOut(SpellMote.FLAME, aim.add(s1.scale(cos(angle) * r)).add(s2.scale(sin(angle) * r)).normalize())
                }
                repeat(3) { throwOut(SpellMote.EMBER_FLAME, jit()) }
                repeat(3) { throwOut(SpellMote.HEAT_DUST, jit()) }
                repeat(2) { throwOut(SpellMote.SPRAY_SMOKE, jit(), 0.7) }
            }
            Element.COLD -> {
                repeat(5) { throwOut(SpellMote.FROST, jit()) }
                repeat(3) { throwOut(SpellMote.FROST_ASH, jit()) }
            }
            Element.BLOOD -> {
                repeat(8) { throwOut(SpellMote.BLOOD_MIST, jit()) }
                repeat(2) { throwOut(SpellMote.BLOOD_DRIP, jit()) }
            }
            Element.POISON -> {
                repeat(7) { throwOut(SpellMote.POISON_MIST, jit()) }
                repeat(2) { throwOut(SpellMote.ROT_SPORE, jit(), 0.5) }
            }
            Element.GROWTH -> {
                repeat(4) {
                    val v = jit().scale(reach / carry(LEAF_FRICTION, LEAF_LIFE) * (0.75 + 0.35 * random.nextDouble()))
                    level.addParticle(EKParticles.leafChip(), origin.x, origin.y, origin.z, v.x, v.y, v.z)
                }
                if (random.nextFloat() < 0.5f) throwOut(SpellMote.GROWTH_GLINT, jit(), 0.6)
            }
            Element.PURITY -> repeat(4) { throwOut(SpellMote.PURITY_GLITTER, jit()) }
            Element.CHAOS -> {
                repeat(3) { throwOut(SpellMote.CHAOS_GLYPH, jit()) }
                if (random.nextFloat() < 0.6f) throwOut(SpellMote.CHAOS_CRIT, jit(), 1.2)
            }
            Element.SUNFIRE -> {
                repeat(4) { throwOut(SpellMote.SUNFIRE_SPARK, jit()) }
                repeat(2) { throwOut(SpellMote.EMBER_FLAME, jit()) }
            }
            Element.VOID -> {
                repeat(3) { throwOut(SpellMote.VOID_MOTE, jit()) }
                if (random.nextFloat() < 0.4f) throwOut(SpellMote.YGANN_EYE, jit(), 0.8)
            }
            Element.GRAVITY -> repeat(3) { throwOut(SpellMote.GRAVITY_DUST, jit()) }
            Element.SUNDERING -> repeat(2) { throwOut(SpellMote.EMBER_FLAME, jit()) }
        }
    }

    /** Distance for each unit of start speed, with [friction] applied for [life] ticks. */
    private fun carry(friction: Float, life: Int): Double =
        if (friction >= 0.9999f) life.toDouble() else (1 - friction.toDouble().pow(life)) / (1 - friction)

    /** Only the element motes. [SpellHand] or [SpellInHand] draws the spell itself. */
    private fun boltCharge(level: ClientLevel, player: Player, spell: Spell, random: Random) {
        val hand = heldAt(player, 1f)
        for (element in spell.magnitudes.keys) {
            val off = SpellGeom.spread(random).scale(0.6)
            SpellTrails.emit(level, element, hand.add(off), random, off.scale(-1.0 / CHARGE_MOTE_TICKS))
        }
    }

    private fun boltTrail(level: ClientLevel, bolt: SpellBoltEntity, random: Random) {
        val elements = bolt.elements()
        if (elements.isEmpty()) return
        val p = bolt.position().add(0.0, bolt.bbHeight / 2.0, 0.0)
        val rgb = Spell(elements).tint()
        level.addParticle(
            DustParticleOptions(Vector3f(((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f), 0.9f),
            p.x, p.y, p.z, 0.0, 0.0, 0.0,
        )
        for (element in elements) {
            SpellTrails.emit(level, element, p.add((random.nextDouble() - 0.5) * 0.2, (random.nextDouble() - 0.5) * 0.2, (random.nextDouble() - 0.5) * 0.2), random)
        }
    }

    /** Uses the same zigzag that [arc] draws in this tick. */
    private fun chainPayload(level: ClientLevel, vfx: Vfx, time: Float, random: Random) {
        val path = arcPath(vfx, time).second
        for (element in SpellVfx.elementsOf(vfx.rgb)) {
            if (element == Element.CHAOS) continue
            repeat(3) {
                val f = random.nextDouble() * (path.size - 1)
                val i = f.toInt().coerceAtMost(path.size - 2)
                SpellTrails.emit(level, element, path[i].lerp(path[i + 1], f - i), random)
            }
        }
    }

    @JvmStatic
    fun renderAll(pose: PoseStack, buffers: MultiBufferSource, camX: Double, camY: Double, camZ: Double, partialTick: Float) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val camera = mc.gameRenderer.mainCamera
        val view = SpellView(Vec3(camX, camY, camZ), Vec3(camera.leftVector), Vec3(camera.upVector))
        val time = SpellClock.time(level, partialTick)
        val pockets = AbyssalPockets.clientPhased()
        if (active.isEmpty() && pockets.isEmpty() && othersIdle()) return
        pose.pushPose()
        pose.translate(-camX, -camY, -camZ)
        val matrix = pose.last().pose()

        if (!pockets.isEmpty() && PocketPortalRenderType.isReady()) {
            PocketPortalClient.render(buffers.getBuffer(PocketPortalRenderType.POCKET_PORTAL), matrix, level, view.cam, pockets, partialTick)
        }

        // Dark volumes first, so light drawn after glows over them.
        renderShade(buffers, matrix, view, level, time)
        if (SpellVfxRenderTypes.isReady()) renderTextured(buffers, matrix, level, view, time)

        if (active.any { it.kind == SpellVfx.Kind.RIFT || it.kind == SpellVfx.Kind.LASH } || SpellVisuals.anyClient(SpellVisuals.VOID_CLOAK)) {
            tentacles(pose, buffers, level, time)
        }

        renderGlow(buffers, matrix, view, time)
        if (SpellVfxRenderTypes.isReady()) {
            val light = buffers.getBuffer(SpellVfxRenderTypes.glow(PARTICLE_ATLAS))
            for (vfx in active) if (vfx.kind == SpellVfx.Kind.SUN_FLASH) sunFlash(light, matrix, view, vfx, t(vfx, time))
        }
        renderLines(buffers, pose, time)
        renderSystems(pose, buffers, level, view, time, partialTick)
        pose.popPose()
    }

    private fun othersIdle(): Boolean =
        AreaWaves.isEmpty() && ImbueVfx.isEmpty() && RuneVfx.isEmpty() && BlockShifts.isEmpty() && AegisContacts.isEmpty() &&
            DeathStyles.isEmpty() && BeamClashes.isEmpty() && GasFieldClient.isEmpty() && AltarGlows.isEmpty() &&
            EffectFlows.isEmpty() && SelfCastVisuals.isEmpty() && EnvironmentFeed.isEmpty()

    private fun renderShade(buffers: MultiBufferSource, matrix: Matrix4f, view: SpellView, level: ClientLevel, time: Float) {
        val shade = buffers.getBuffer(SpellVfxRenderTypes.SHADE)
        for (vfx in active) {
            val t = t(vfx, time)
            when (vfx.kind) {
                SpellVfx.Kind.IMPLODE -> SpellShapes.sphereFlat(shade, matrix, vfx.a, vfx.size * (1.0 - t).pow(0.7), darken(vfx.rgb, 0.2f), 0.55f * (1f - t * 0.6f))
                SpellVfx.Kind.STREAM -> streamShade(shade, matrix, view, vfx, t, time)
                SpellVfx.Kind.RIFT -> riftShade(shade, matrix, vfx, t)
                SpellVfx.Kind.LASH -> lashMist(shade, matrix, view, level, vfx, t, time)
                SpellVfx.Kind.PUFFS -> puffs(shade, matrix, view, vfx, t)
                SpellVfx.Kind.PRESS -> press(shade, matrix, vfx, t, time)
                else -> {}
            }
        }
    }

    private fun renderTextured(buffers: MultiBufferSource, matrix: Matrix4f, level: ClientLevel, view: SpellView, time: Float) {
        val blocks = buffers.getBuffer(SpellVfxRenderTypes.sprite(BLOCK_ATLAS))
        for (vfx in active) when (vfx.kind) {
            SpellVfx.Kind.SHARDS -> shards(blocks, matrix, level, vfx, t(vfx, time))
            SpellVfx.Kind.CRUSH -> crushFaces(blocks, matrix, level, vfx, t(vfx, time), time)
            else -> {}
        }
        val drops = buffers.getBuffer(SpellVfxRenderTypes.sprite(PARTICLE_ATLAS))
        for (vfx in active) if (vfx.kind == SpellVfx.Kind.STREAM) streamDrops(drops, matrix, level, view, vfx, t(vfx, time), time)
    }

    private fun renderGlow(buffers: MultiBufferSource, matrix: Matrix4f, view: SpellView, time: Float) {
        val glow = buffers.getBuffer(AegisRenderType.FACE_GLOW)
        for (vfx in active) {
            val t = t(vfx, time)
            when (vfx.kind) {
                SpellVfx.Kind.SHELL -> shell(glow, matrix, view, vfx, t)
                SpellVfx.Kind.IMPLODE -> implodeGlow(glow, matrix, view, vfx, t, time)
                SpellVfx.Kind.AEGIS -> aegisFaces(glow, matrix, vfx, t)
                SpellVfx.Kind.ARC, SpellVfx.Kind.CHAIN -> arc(glow, matrix, view, vfx, t, time)
                SpellVfx.Kind.STREAM -> streamSheen(glow, matrix, view, vfx, t, time)
                SpellVfx.Kind.FLASH -> flash(glow, matrix, view, vfx, t)
                SpellVfx.Kind.SHATTER -> shatterGlow(glow, matrix, view, vfx, t, time)
                SpellVfx.Kind.RIFT -> riftGlow(glow, matrix, view, vfx, t)
                SpellVfx.Kind.BEAM_EXPLOSION -> {
                    if (t < 0.25f) SpellShapes.sphere(glow, matrix, view, vfx.a, vfx.size * easeOut(t / 0.25f).toDouble(), 0xFFFFFF, 0.6f * (1f - t / 0.25f))
                    SpellShapes.ring(glow, matrix, vfx.b, vfx.size * 1.2 * easeOut(minOf(1f, t * 2.5f)), 0.3, vfx.rgb, 0.8f * maxOf(0f, 1f - t * 1.6f), 0.0)
                }
                SpellVfx.Kind.SHOCKWAVE -> {
                    if (t < 0.9f) SpellShapes.sphere(glow, matrix, view, vfx.a, vfx.size * easeOut(t / 0.9f).toDouble(), vfx.rgb, 0.7f * (1f - t / 0.9f))
                    SpellShapes.ring(glow, matrix, vfx.b, vfx.size * 1.1 * easeOut(minOf(1f, t * 1.4f)), 0.25, vfx.rgb, 0.8f * maxOf(0f, 1f - t * 1.1f), 0.0)
                }
                else -> {}
            }
        }
    }

    private fun renderLines(buffers: MultiBufferSource, pose: PoseStack, time: Float) {
        val lines = buffers.getBuffer(RenderType.lines())
        for (vfx in active) {
            val t = t(vfx, time)
            when (vfx.kind) {
                SpellVfx.Kind.AEGIS -> SpellShapes.wire(lines, pose.last(), aegisCorners(vfx, t), whiten(vfx.rgb, 0.3f), 1f - t)
                SpellVfx.Kind.CRUSH -> for (p in crushCubes(vfx, t, time)) SpellShapes.wire(lines, pose.last(), p, darken(vfx.rgb, 0.35f), 0.9f * (1f - t * t))
                SpellVfx.Kind.SHATTER -> shatterWire(lines, pose.last(), vfx, t, time)
                else -> {}
            }
        }
    }

    private fun renderSystems(pose: PoseStack, buffers: MultiBufferSource, level: ClientLevel, view: SpellView, time: Float, partialTick: Float) {
        AreaWaves.render(pose, buffers, level, view, time)
        // After the translucent blocks of the altars.
        AltarGlows.flush(buffers)
        ImbueVfx.render(pose, buffers, level, view, time, partialTick)
        RuneVfx.render(pose, buffers, level, view, time, partialTick)
        BlockShifts.render(pose, buffers, time)
        AegisContacts.render(pose, buffers, view, time)
        DeathStyles.render(pose, buffers, level, view, time)
        BeamClashes.render(pose, buffers, view, time)
        EffectFlows.render(pose.last().pose(), buffers, view, level, time)
        SelfCastVisuals.render(pose.last().pose(), buffers, view, level, time)
        EnvironmentFeed.render(pose.last().pose(), buffers, view, partialTick)
    }

    private fun t(vfx: Vfx, time: Float): Float = ((time - vfx.start) / vfx.life).coerceIn(0f, 1f)

    private fun easeOut(t: Float): Float = easeOut(t.toDouble()).toFloat()

    private fun shell(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float) {
        val r = vfx.size * (0.15f + 0.85f * easeOut(minOf(1f, t * 1.4f)))
        val fade = (1f - t).let { it * it }
        SpellShapes.sphere(c, m, view, vfx.a, r.toDouble(), vfx.rgb, 0.75f * fade)
        SpellShapes.sphere(c, m, view, vfx.a, r * 0.6, whiten(vfx.rgb, 0.4f), 0.35f * fade)
        SpellShapes.ring(c, m, vfx.a, r * 1.1, r * 0.08, vfx.rgb, 0.6f * fade, 0.0)
    }

    private fun implodeGlow(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float, time: Float) {
        val r = vfx.size * (1f - t)
        SpellShapes.sphere(c, m, view, vfx.a, r.toDouble(), vfx.rgb, 0.5f * (1f - t))
        for (i in 0 until 3) {
            val tilt = (i * 60.0 + time * 6.0) * Mth.DEG_TO_RAD
            SpellShapes.ring(c, m, vfx.a, r * (0.8 + 0.25 * i), r * 0.05, vfx.rgb, 0.7f * (1f - t), tilt)
        }
    }

    private fun aegisCorners(vfx: Vfx, t: Float): Array<Vec3> =
        SpellGeom.cubeCorners(vfx.a, vfx.size * 0.5 * (0.4 + 0.6 * easeOut(t)), t * Math.PI / 2 + vfx.seed % 7, 0.0)

    private fun aegisFaces(c: VertexConsumer, m: Matrix4f, vfx: Vfx, t: Float) =
        SpellShapes.glowCubeFaces(c, m, aegisCorners(vfx, t), vfx.rgb, 0.3f * (1f - t))

    private fun crushCubes(vfx: Vfx, t: Float, time: Float): List<Array<Vec3>> {
        val squeeze = 1f - t * t * 0.85f
        return List(CRUSH_CUBES) { k ->
            val half = vfx.size * 0.5 * (0.45 + 0.3 * k) * squeeze
            val dir = if (k % 2 == 0) 1.0 else -1.0
            val yaw = dir * (time * 0.05 + t * 1.2) + vfx.seed % 5 + k
            val pitch = (k * 25.0 + t * 30.0 * dir) * Mth.DEG_TO_RAD
            SpellGeom.cubeCorners(vfx.a, half, yaw, pitch)
        }
    }

    private fun crushFaces(c: VertexConsumer, m: Matrix4f, level: ClientLevel, vfx: Vfx, t: Float, time: Float) {
        val fade = 1f - t * t
        val light = SpellShapes.lightAt(level, vfx.a)
        for ((k, p) in crushCubes(vfx, t, time).withIndex()) {
            val sprite = SpellArt.block(if (k == 0) "crying_obsidian" else "obsidian")
            SpellShapes.texBox(c, m, p, sprite, 0xFFFFFF, (0.32f - 0.07f * k) * fade * 1.6f, light, vfx.seed + k)
        }
    }

    private fun shards(c: VertexConsumer, m: Matrix4f, level: ClientLevel, vfx: Vfx, t: Float) {
        val r = Random(vfx.seed)
        val light = SpellShapes.lightAt(level, vfx.a)
        for (i in 0 until 12) {
            val d = Vec3(SpellGeom.gauss(r), Math.abs(SpellGeom.gauss(r)) * 0.5, SpellGeom.gauss(r)).normalize()
            val dist = vfx.size * (0.5 + 0.7 * r.nextDouble()) * easeOut(minOf(1.0, t * 3.0))
            val alpha = 1f - smooth((t - 0.6f) / 0.4f)
            val w = 0.06 + 0.05 * r.nextDouble()
            val p = SpellGeom.prismCorners(vfx.a.add(d.scale(dist * 0.4)), d, 0.35 + 0.35 * r.nextDouble(), w, r.nextDouble() * 6)
            SpellShapes.texBox(c, m, p, SpellArt.block(if (i % 3 != 0) "packed_ice" else "blue_ice"), 0xFFFFFF, 0.9f * alpha, light, vfx.seed * 7 + i)
        }
    }

    private fun shatterPieces(vfx: Vfx, t: Float, time: Float): List<Pair<Array<Vec3>, Float>> {
        val out = ArrayList<Pair<Array<Vec3>, Float>>()
        if (t < 0.18f) out += SpellGeom.cubeCorners(vfx.a, vfx.size * 0.35 * easeOut(t / 0.18f), time * 0.1, 0.4) to -1f
        if (t >= 0.15f) {
            val r = Random(vfx.seed)
            val u = ((t - 0.15f) / 0.85f).coerceIn(0f, 1f)
            for (i in 0 until 22) {
                val d = Vec3(SpellGeom.gauss(r), SpellGeom.gauss(r) * 0.8 + 0.2, SpellGeom.gauss(r)).normalize()
                val dist = vfx.size * (0.5 + 0.9 * r.nextDouble()) * easeOut(u)
                val p = vfx.a.add(d.scale(dist)).add(0.0, -1.2 * u * u * r.nextDouble(), 0.0)
                val size = vfx.size * (0.05 + 0.06 * r.nextDouble()) * (1 - u * 0.5)
                val cube = SpellGeom.cubeCorners(p, size, r.nextDouble() * 6 + time * 0.2 * (r.nextDouble() - 0.5), r.nextDouble() * 6 + time * 0.15, r.nextDouble() * 6)
                out += cube to (1f - u).pow(1.3f)
            }
        }
        return out
    }

    private fun shatterGlow(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float, time: Float) {
        for ((cube, a) in shatterPieces(vfx, t, time)) {
            if (a < 0f) SpellShapes.glowCubeFaces(c, m, cube, vfx.rgb, 0.5f) else SpellShapes.glowCubeFaces(c, m, cube, vfx.rgb, 0.7f * a, 0.5)
        }
        SpellShapes.disc(c, m, view, vfx.a, vfx.size * 0.8, whiten(vfx.rgb, 0.6f), maxOf(0f, 0.9f - Math.abs(t - 0.17f) * 9f))
    }

    private fun shatterWire(c: VertexConsumer, pose: PoseStack.Pose, vfx: Vfx, t: Float, time: Float) {
        for ((cube, a) in shatterPieces(vfx, t, time)) {
            if (a < 0f) SpellShapes.wire(c, pose, cube, whiten(vfx.rgb, 0.5f), 1f) else SpellShapes.wire(c, pose, cube, whiten(vfx.rgb, 0.4f), a)
        }
    }

    private fun riftRadius(vfx: Vfx, t: Float): Double = vfx.size * 0.42 * easeOut(minOf(1f, t * 4f))

    private fun riftFade(vfx: Vfx, t: Float): Float = 1f - smooth((t - (0.62f + 0.12f * Random(vfx.seed).nextFloat())) / 0.22f)

    private fun riftShade(c: VertexConsumer, m: Matrix4f, vfx: Vfx, t: Float) =
        SpellShapes.sphereFlat(c, m, vfx.a, riftRadius(vfx, t), 0x07030C, 0.92f * riftFade(vfx, t))

    private fun riftGlow(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float) =
        SpellShapes.sphere(c, m, view, vfx.a, riftRadius(vfx, t) * 1.05, 0x6A2FA0, 0.45f * riftFade(vfx, t))

    private fun tentacles(pose: PoseStack, buffers: MultiBufferSource, level: ClientLevel, time: Float) {
        val model = tentacleModel ?: TentacleModel(TentacleModel.createMesh().bakeRoot()).also { tentacleModel = it }
        // Bodies, then tips: one render type at a time.
        for (tips in listOf(false, true)) {
            val c = buffers.getBuffer(if (tips) RenderType.entityTranslucentEmissive(TENTACLE_GLOW_TEX) else RenderType.entityTranslucent(TENTACLE_TEX))
            if (SpellVisuals.anyClient(SpellVisuals.VOID_CLOAK)) {
                for (entity in level.entitiesForRendering()) {
                    if (SpellVisuals.clientBits(entity.id) and SpellVisuals.VOID_CLOAK != 0) tendrils(model, pose, c, level, entity, time, tips)
                }
            }
            for (vfx in active) {
                if (vfx.kind == SpellVfx.Kind.LASH) {
                    lash(model, pose, c, level, vfx, t(vfx, time), tips)
                    continue
                }
                if (vfx.kind != SpellVfx.Kind.RIFT) continue
                val t = t(vfx, time)
                val r = Random(vfx.seed * 13 + 5)
                val rift = riftRadius(vfx, t)
                val light = SpellShapes.lightAt(level, vfx.a)
                for (i in 0 until RIFT_TENTACLES) {
                    val angle = i * Math.PI / 3 + r.nextDouble() * 0.6
                    val dir = Vec3(cos(angle), 0.15 + 0.7 * r.nextDouble(), sin(angle)).normalize()
                    val born = 0.03f + 0.15f * r.nextFloat()
                    val grow = easeOut(((t - born) / 0.25f).coerceIn(0f, 1f))
                    val gone = 1f - smooth((t - (0.4f + 0.35f * r.nextFloat())) / 0.18f)
                    val length = vfx.size * (0.9 + 0.5 * r.nextDouble()) * grow
                    val phase = r.nextFloat() * 2000f
                    if (length < 0.02 || gone < 0.01f) continue
                    val base = vfx.a.add(dir.scale(rift * 0.3))
                    model.resetPose()
                    KeyframeAnimations.animate(model, TentacleAnimation.IDLE_WAVE, (time * 50f + phase).toLong(), 1f, animVec)
                    pose.pushPose()
                    pose.translate(base.x, base.y, base.z)
                    pose.mulPose(Quaternionf().rotationTo(0f, 1f, 0f, dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat()))
                    val s = (length / TENTACLE_HEIGHT).toFloat()
                    pose.scale(s, s, s)
                    // Entity model frame: flip, then move down by the 24-pixel ground offset of the model.
                    pose.scale(-1f, -1f, 1f)
                    pose.translate(0f, -1.501f, 0f)
                    if (tips) model.renderTip(pose, c, SpellShapes.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, gone)
                    else model.renderToBuffer(pose, c, light, OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, gone)
                    pose.popPose()
                }
            }
        }
    }

    /** Null when the target is directly above or below the hips. */
    private fun lashBase(level: ClientLevel, vfx: Vfx): Vec3? {
        val wearer = level.getEntity(vfx.seed)
        val feet = wearer?.getPosition(Minecraft.getInstance().frameTime) ?: vfx.a
        val hips = feet.add(0.0, vfx.size * 0.45, 0.0)
        val flat = Vec3(vfx.b.x - hips.x, 0.0, vfx.b.z - hips.z)
        if (flat.lengthSqr() < 1.0e-4) return null
        return hips.add(flat.normalize().scale((wearer?.bbWidth ?: 0.6f) * 0.5 + LASH_ROOT))
    }

    private fun lashSpan(vfx: Vfx, base: Vec3, t: Float): Vec3 {
        val target = vfx.b.subtract(base)
        val ticks = t * vfx.life
        val dir = target.normalize()
        val r = Random(vfx.start * 31 + vfx.seed)
        val a1 = dir.cross(if (Math.abs(dir.y) > 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)).normalize()
        val a2 = dir.cross(a1)
        val side = r.nextDouble() * 2 * Math.PI
        val axis = a1.scale(cos(side)).add(a2.scale(sin(side)))
        val swing = LASH_SWING * exp(-ticks / LASH_SETTLE_TICKS) * cos(ticks * LASH_SWING_RATE)
        // Rodrigues rotation by [swing] about [axis].
        val c = cos(swing); val sn = sin(swing)
        val turned = target.scale(c).add(axis.cross(target).scale(sn)).add(axis.scale(axis.dot(target) * (1 - c)))
        return turned.scale(easeOut(minOf(1f, ticks / LASH_OUT_TICKS)).toDouble())
    }

    private fun lashRoll(vfx: Vfx): Float = Random(vfx.start * 17 + vfx.seed + 3).nextFloat() * 6.2832f

    private fun lash(model: TentacleModel, pose: PoseStack, c: VertexConsumer, level: ClientLevel, vfx: Vfx, t: Float, tips: Boolean) {
        val base = lashBase(level, vfx) ?: return
        val fade = 1f - smooth((t - 0.4f) / 0.6f)
        if (fade < 0.01f) return
        val ticks = t * vfx.life
        val writhe = if (ticks < LASH_SLOW_FROM) ticks else LASH_SLOW_FROM + (1f - exp(-(ticks - LASH_SLOW_FROM) / LASH_SLOW_TICKS)) * LASH_SLOW_TICKS
        tentacle(model, pose, c, level, base, lashSpan(vfx, base, t), lashRoll(vfx), (writhe * LASH_WRITHE).toLong() + vfx.start % 1000, fade, tips, LASH_FADE_FROM, LASH_FADE_TO)
    }

    private fun tendrils(model: TentacleModel, pose: PoseStack, c: VertexConsumer, level: ClientLevel, wearer: Entity, time: Float, tips: Boolean) {
        val feet = wearer.getPosition(Minecraft.getInstance().frameTime)
        val count = Math.ceil(TENDRILS * minOf(1f, SelfCastVisuals.castDensity(wearer.id, SpellVisuals.VOID_CLOAK)).toDouble()).toInt()
        for (i in 0 until count) {
            val own = Random(wearer.id * 7919L + i * 104729L)
            val period = TENDRIL_PERIOD * (0.75f + 0.5f * own.nextFloat())
            val phase = time + own.nextFloat() * period
            val u = (phase % period) / period
            val r = Random(wearer.id * 7919L + i * 104729L + (phase / period).toLong() * 31)
            val an = r.nextDouble() * 2 * Math.PI
            val outward = Vec3(cos(an), 0.0, sin(an))
            val across = Vec3(-sin(an), 0.0, cos(an))
            val base = feet.add(outward.scale(wearer.bbWidth * 0.5 + 0.05)).add(0.0, wearer.bbHeight * (0.3 + 0.3 * r.nextDouble()), 0.0)
            val dir = outward.add(0.0, r.nextDouble() * 1.2 - 0.4, 0.0).add(across.scale((r.nextDouble() - 0.5) * 0.6)).normalize()
            val length = TENDRIL_LENGTH * (0.6 + 0.6 * r.nextDouble()) * easeOut(minOf(1f, u / 0.25f))
            val alpha = 1f - smooth((u - 0.6f) / 0.4f)
            if (length < 0.02 || alpha < 0.01f) continue
            tentacle(model, pose, c, level, base, dir.scale(length), r.nextFloat() * 6.2832f, (time * TENDRIL_WRITHE + i * 997).toLong(), alpha, tips, TENDRIL_FADE_FROM, TENDRIL_FADE_TO)
        }
    }

    /** [RootFade] fades each vertex by its distance from [base], so the root has no hard edge in the cloak. */
    private fun tentacle(
        model: TentacleModel, pose: PoseStack, c: VertexConsumer, level: ClientLevel, base: Vec3, span: Vec3,
        roll: Float, ms: Long, alpha: Float, tips: Boolean, fadeFrom: Double, fadeTo: Double,
    ) {
        val length = span.length()
        if (length < 0.02) return
        val dir = span.normalize()
        // Use the output vertex space of the model, because RootFade compares transformed vertices.
        val m = pose.last().pose()
        val root = m.transformPosition(Vector3f(base.x.toFloat(), base.y.toFloat(), base.z.toFloat()))
        val out = m.transformDirection(Vector3f(dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat())).normalize()
        model.resetPose()
        KeyframeAnimations.animate(model, TentacleAnimation.IDLE_WAVE, ms, 1f, animVec)
        pose.pushPose()
        pose.translate(base.x, base.y, base.z)
        pose.mulPose(Quaternionf().rotationTo(0f, 1f, 0f, dir.x.toFloat(), dir.y.toFloat(), dir.z.toFloat()))
        pose.mulPose(Quaternionf().rotationY(roll))
        val s = (length / TENTACLE_HEIGHT).toFloat()
        // Clamp the width, so a long lash stays thin.
        pose.scale(minOf(s, LASH_THICKNESS), s, minOf(s, LASH_THICKNESS))
        pose.scale(-1f, -1f, 1f)
        pose.translate(0f, -1.501f, 0f)
        if (tips) model.renderTip(pose, c, SpellShapes.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, alpha)
        else {
            val faded = RootFade(c, root, out, fadeFrom, fadeTo)
            model.renderToBuffer(pose, faded, SpellShapes.lightAt(level, base), OverlayTexture.NO_OVERLAY, LASH_TINT_R, LASH_TINT_G, LASH_TINT_B, alpha)
        }
        pose.popPose()
    }

    /** Models write each vertex through this bulk vertex() overload, so fade only this one. */
    private class RootFade(
        private val inner: VertexConsumer, private val root: Vector3f, private val out: Vector3f,
        private val from: Double, private val to: Double,
    ) : VertexConsumer by inner {
        override fun vertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float, u: Float, v: Float, overlay: Int, light: Int, nx: Float, ny: Float, nz: Float) {
            val along = (x - root.x) * out.x + (y - root.y) * out.y + (z - root.z) * out.z
            inner.vertex(x, y, z, r, g, b, a * smooth(((along - from) / (to - from)).toFloat()), u, v, overlay, light, nx, ny, nz)
        }
    }

    private fun lashMist(c: VertexConsumer, m: Matrix4f, view: SpellView, level: ClientLevel, vfx: Vfx, t: Float, time: Float) {
        val base = lashBase(level, vfx) ?: return
        val span = lashSpan(vfx, base, t)
        val fade = 1f - smooth((t - 0.3f) / 0.7f)
        if (fade < 0.01f || span.lengthSqr() < 1.0e-4) return
        val dir = span.normalize()
        val side = dir.cross(Vec3(0.0, 1.0, 0.0)).let { if (it.lengthSqr() < 1.0e-4) Vec3(1.0, 0.0, 0.0) else it.normalize() }
        val up = side.cross(dir)
        for (i in 0 until LASH_MIST_PUFFS) {
            val f = i / (LASH_MIST_PUFFS - 1.0) * LASH_MIST_REACH
            val turn = time * 0.25 + i * 1.9
            val p = base.add(span.scale(f)).add(side.scale(0.07 * cos(turn))).add(up.scale(0.07 * sin(turn)))
            val r = 0.42 * (1 - f / LASH_MIST_REACH * 0.7)
            SpellShapes.puff(c, m, view, p, r, if (i % 2 == 0) 0x1C0C2C else 0x4A2468, 0.85f * fade * (1f - (f / LASH_MIST_REACH).toFloat() * 0.6f))
        }
    }

    private fun lashMotes(level: ClientLevel, vfx: Vfx, t: Float) {
        val base = lashBase(level, vfx) ?: return
        val span = lashSpan(vfx, base, t)
        val r = level.random
        repeat(LASH_MOTES) { k ->
            val along = if (k == 0) 0.0 else r.nextDouble() * r.nextDouble()
            val p = base.add(span.scale(along)).add((r.nextDouble() - 0.5) * 0.25, (r.nextDouble() - 0.5) * 0.25, (r.nextDouble() - 0.5) * 0.25)
            level.addParticle(EKParticles.mote(if (k == LASH_MOTES - 1) SpellMote.CLOAK_VOID_GLINT else SpellMote.CLOAK_VOID), p.x, p.y, p.z, 0.0, 0.0, 0.0)
        }
    }

    private fun press(c: VertexConsumer, m: Matrix4f, vfx: Vfx, t: Float, time: Float) {
        val at = vfx.a.add(0.0, 0.52, 0.0)
        val half = 0.6 + 0.1 * sin(time * 1.0)
        val a = 1f - t
        val corners = arrayOf(1.0 to 1.0, -1.0 to 1.0, -1.0 to -1.0, 1.0 to -1.0)
        fun p(x: Double, z: Double) = at.add(x, 0.0, z)
        for ((width, rgb, alpha) in arrayOf(Triple(0.14, 0x14101C, a * 0.5f), Triple(0.05, darken(Element.GRAVITY.color, 0.25f), a * 0.86f))) {
            for (k in 0 until 4) {
                val (x0, z0) = corners[k]; val (x1, z1) = corners[(k + 1) % 4]
                SpellShapes.quad(c, m, p(x0 * (half - width), z0 * (half - width)), p(x1 * (half - width), z1 * (half - width)), p(x1 * half, z1 * half), p(x0 * half, z0 * half), rgb, 0f, 0f, alpha, alpha)
                SpellShapes.quad(c, m, p(x0 * half, z0 * half), p(x1 * half, z1 * half), p(x1 * (half + width), z1 * (half + width)), p(x0 * (half + width), z0 * (half + width)), rgb, alpha, alpha, 0f, 0f)
            }
        }
    }

    private fun puffs(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float) {
        val r = Random(vfx.seed)
        for (i in 0 until 10) {
            val d = Vec3(SpellGeom.gauss(r), Math.abs(SpellGeom.gauss(r)) * 0.4, SpellGeom.gauss(r)).normalize()
            val p = vfx.a.add(d.scale(vfx.size * 0.9 * easeOut(t) * (0.4 + 0.6 * r.nextDouble()))).add(0.0, t * 0.3, 0.0)
            SpellShapes.puff(c, m, view, p, vfx.size * (0.3 + 0.25 * r.nextDouble()) * (0.5 + t), if (i % 3 != 0) 0x4F7A2A else 0x2F4A18, 0.4f * (1f - t * 0.7f))
        }
    }

    private fun sunFlash(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float) =
        SpellShapes.sprite(
            c, m, view, vfx.a, vfx.size * 1.6 * sin(Math.PI * minOf(1f, t * 4f)), SpellArt.particle("flash"),
            vfx.rgb, maxOf(0f, 0.9f - t * 4f), SpellShapes.FULL_BRIGHT,
        )

    /** [arc] and [chainPayload] both use this, so the payload follows the zigzag that shows. */
    private fun arcPath(vfx: Vfx, time: Float): Pair<Random, List<Vec3>> {
        val random = Random(vfx.seed * 31 + (time / ARC_RESTRIKE_TICKS).toInt())
        // Skip the value that arc() uses for its flicker, so that the flicker and the zigzag do not agree.
        random.nextFloat()
        return random to SpellGeom.jagged(random, vfx.a, vfx.b, ARC_JAGGEDNESS)
    }

    private fun arc(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float, time: Float) {
        val flicker = 0.55f + 0.45f * Random(vfx.seed * 31 + (time / ARC_RESTRIKE_TICKS).toInt()).nextFloat()
        val (random, main) = arcPath(vfx, time)
        val rgb = if (vfx.kind == SpellVfx.Kind.CHAIN) Element.CHAOS.color else vfx.rgb
        val fade = (1f - t) * flicker
        fun taper(n: Int, from: Double = 1.0): (Int) -> Double = { i -> from * (1 - 0.92 * (i.toDouble() / (n - 1)).pow(1.4)) }
        fun tipFade(n: Int) = FloatArray(n) { i -> 1f - 0.7f * (i.toFloat() / (n - 1)).pow(2) }
        val mw = taper(main.size)
        SpellShapes.strip(c, m, view, main, { ARC_HALO_WIDTH * mw(it) }, rgb, fade * 0.45f, true, tipFade(main.size))
        SpellShapes.strip(c, m, view, main, { ARC_CORE_WIDTH * mw(it) }, whiten(rgb, 0.75f), fade, false, tipFade(main.size))
        val span = vfx.b.subtract(vfx.a)
        repeat(2) {
            val at = 1 + random.nextInt((main.size - 2).coerceAtLeast(1))
            val dir = span.normalize().add(random.nextDouble() - 0.5, random.nextDouble() - 0.5, random.nextDouble() - 0.5).normalize()
            val branch = SpellGeom.jagged(random, main[at], main[at].add(dir.scale(span.length() * (0.2 + 0.25 * random.nextDouble()))), ARC_JAGGEDNESS)
            val bw = taper(branch.size, 0.6 * mw(at))
            SpellShapes.strip(c, m, view, branch, { ARC_HALO_WIDTH * bw(it) }, rgb, fade * 0.3f, true, tipFade(branch.size))
            SpellShapes.strip(c, m, view, branch, { ARC_CORE_WIDTH * bw(it) }, whiten(rgb, 0.75f), fade * 0.7f, false, tipFade(branch.size))
        }
    }

    private fun streamPaths(vfx: Vfx, time: Float): List<List<Vec3>> {
        if (vfx.pathsAt != time) {
            vfx.paths = makeStreamPaths(vfx, time)
            vfx.pathsAt = time
        }
        return vfx.paths
    }

    private fun makeStreamPaths(vfx: Vfx, time: Float): List<List<Vec3>> {
        val random = Random(vfx.seed)
        val span = vfx.b.subtract(vfx.a)
        val length = span.length()
        val (side1, side2) = SpellGeom.basis(if (length < 1.0e-6) SpellGeom.UP else span.scale(1.0 / length))
        return List(STREAM_STRANDS) {
            val bowAngle = random.nextDouble() * 2 * Math.PI
            val bow = side1.scale(cos(bowAngle)).add(side2.scale(sin(bowAngle))).scale(length * (0.04 + 0.08 * random.nextDouble()))
            val control = vfx.a.lerp(vfx.b, 0.5).add(bow).add(0.0, length * 0.12, 0.0)
            val freq = 1.0 + random.nextDouble()
            val speed = 0.12 + 0.12 * random.nextDouble()
            val phase = random.nextDouble() * 10
            val amp = length * (0.012 + 0.018 * random.nextDouble())
            List(STREAM_STEPS + 1) { i ->
                val u = i.toDouble() / STREAM_STEPS
                val envelope = sin(Math.PI * u)
                val w1 = sin(u * freq * Math.PI + time * speed + phase) * amp * envelope
                val w2 = cos(u * freq * 1.7 * Math.PI - time * speed * 1.3 + phase) * amp * envelope
                SpellGeom.bezier(vfx.a, control, vfx.b, u).add(side1.scale(w1)).add(side2.scale(w2))
            }
        }
    }

    private fun streamShade(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float, time: Float) {
        val fade = 1f - t * t
        for ((k, path) in streamPaths(vfx, time).withIndex()) {
            val w = if (k == 0) 0.08 else 0.045
            SpellShapes.strip(c, m, view, path, { w }, darken(vfx.rgb, 0.35f), 0.7f * fade, false)
        }
    }

    private fun streamSheen(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float, time: Float) {
        for (path in streamPaths(vfx, time)) SpellShapes.strip(c, m, view, path, { 0.16 }, darken(vfx.rgb, 0.6f), 0.12f * (1f - t * t), true)
    }

    private fun streamDrops(c: VertexConsumer, m: Matrix4f, level: ClientLevel, view: SpellView, vfx: Vfx, t: Float, time: Float) {
        val fade = 1f - t * t
        val sprite = SpellArt.particle("drip_hang")
        for ((k, path) in streamPaths(vfx, time).withIndex()) {
            for (i in 0 until STREAM_DROPS) {
                val u = (t * STREAM_SPEED + (i + k * 0.37f) / STREAM_DROPS) % 1f
                val p = path[(u * STREAM_STEPS).toInt().coerceIn(0, STREAM_STEPS)]
                SpellShapes.sprite(c, m, view, p, 0.16, sprite, Element.BLOOD.color, 0.9f * fade, SpellShapes.lightAt(level, p))
            }
        }
    }

    private fun flash(c: VertexConsumer, m: Matrix4f, view: SpellView, vfx: Vfx, t: Float) {
        val r = vfx.size * (0.6 + 0.8 * t)
        val a = (1f - t) * (1f - t)
        SpellShapes.disc(c, m, view, vfx.a, r, vfx.rgb, 0.8f * a)
        SpellShapes.disc(c, m, view, vfx.a, r * 0.4, 0xFFFFFF, a)
    }

    /** For other players, use the last position [SpellInHand] saw. Else, use a point a little ahead of the hand. */
    fun heldAt(player: Player, partialTick: Float): Vec3 {
        if (SpellHand.firstPerson(player)) return SpellHand.palmInWorld(if (player.usedItemHand == InteractionHand.MAIN_HAND) player.mainArm else player.mainArm.opposite)
        SpellInHand.heldAt(player)?.let { return it }
        val hand = SpellCasting.handOrigin(player, player.usedItemHand, partialTick)
        return hand.add(SpellCasting.handAim(player, hand, partialTick).scale(HELD_AHEAD))
    }

    /** Far longer than any visual the server sends (the longest is 20 ticks). */
    private const val MAX_LIFE = 200
    /** Far more than play makes at once. */
    private const val MAX_ACTIVE = 2048
    private const val CRUSH_CUBES = 3
    private const val RIFT_TENTACLES = 6
    /** Rift tentacles use 50. */
    private const val LASH_WRITHE = 240f
    private const val LASH_SLOW_FROM = 4f
    private const val LASH_SLOW_TICKS = 4f
    /** Blocks out from the side of the wearer. Keep it inside the ring of the cloak. */
    private const val LASH_ROOT = 0.1
    private const val LASH_TINT_R = 0.72f
    private const val LASH_TINT_G = 0.56f
    private const val LASH_TINT_B = 0.9f
    /** Blocks along the lash: the start of the fade-in, and where it is fully solid. */
    private const val LASH_FADE_FROM = 0.1
    private const val LASH_FADE_TO = 1.1
    /** Out and settle times in ticks; swing in radians. */
    private const val LASH_OUT_TICKS = 2f
    private const val LASH_SWING = 1.1
    private const val LASH_SWING_RATE = 1.4
    private const val LASH_SETTLE_TICKS = 1.8
    /** Period in ticks; length and fade distances in blocks. */
    private const val TENDRILS = 6
    private const val TENDRIL_PERIOD = 40f
    private const val TENDRIL_LENGTH = 0.7
    private const val TENDRIL_WRITHE = 70f
    private const val TENDRIL_FADE_FROM = 0.03
    private const val TENDRIL_FADE_TO = 0.35
    private const val LASH_MIST_PUFFS = 8
    private const val LASH_MIST_REACH = 0.6
    private const val LASH_MOTES = 3
    private const val LASH_THICKNESS = 0.6f
    /** In blocks, at scale 1. */
    private const val TENTACLE_HEIGHT = 90.0 / 16.0
    private const val ARC_RESTRIKE_TICKS = 2f
    private const val ARC_JAGGEDNESS = 0.12
    private const val ARC_CORE_WIDTH = 0.045
    private const val ARC_HALO_WIDTH = 0.22
    private const val STREAM_STEPS = 20
    private const val STREAM_STRANDS = 3
    private const val STREAM_DROPS = 3
    private const val STREAM_SPEED = 1.6f
    private const val SPRAY_SPREAD = 0.22
    /** Radians per tick the fire spiral turns. */
    private const val FIRE_SPIN = 0.12
    private const val CHARGE_MOTE_TICKS = 8.0
    private const val HELD_AHEAD = 0.3
    private const val LEAF_FRICTION = 0.9f
    private const val LEAF_LIFE = 26
}
