@file:OptIn(org.valkyrienskies.core.api.util.GameTickOnly::class)

package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.client.model.ClawhodHandAnimations
import org.shipwrights.enderkinesis.client.model.ClawhodHandModel
import org.shipwrights.enderkinesis.construction.HAND_BLEND_TICKS
import org.shipwrights.enderkinesis.construction.PLACE_CLIP_TICKS
import org.shipwrights.enderkinesis.construction.PLACE_SPEED
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3d
import org.shipwrights.enderkinesis.block.ClawhodBlock
import org.shipwrights.enderkinesis.blockentity.ClawhodBlockEntity
import org.shipwrights.enderkinesis.construction.clawhodForward
import org.shipwrights.enderkinesis.construction.clawhodLeftSign
import org.shipwrights.enderkinesis.construction.setClawhodGem
import org.shipwrights.enderkinesis.construction.CLAWHOD_HANDS
import org.shipwrights.enderkinesis.construction.HandMotion
import org.shipwrights.enderkinesis.construction.IDLE_BOB
import org.shipwrights.enderkinesis.construction.IDLE_PERIOD_TICKS
import org.shipwrights.enderkinesis.construction.IDLE_SWAY
import org.shipwrights.enderkinesis.construction.PLACE_DIP_TICKS
import org.shipwrights.enderkinesis.construction.PLACE_REST
import org.shipwrights.enderkinesis.construction.PUNCH_DEPTH
import org.shipwrights.enderkinesis.construction.PUNCH_REST
import org.shipwrights.enderkinesis.construction.SHIFT_TICKS
import org.shipwrights.enderkinesis.construction.TURN_TICKS
import org.shipwrights.enderkinesis.construction.easeInOut
import org.shipwrights.enderkinesis.construction.flightTicks
import org.shipwrights.enderkinesis.construction.legLift
import org.shipwrights.enderkinesis.construction.placeDip
import org.shipwrights.enderkinesis.construction.punchBack
import org.shipwrights.enderkinesis.construction.NO_POS
import org.shipwrights.enderkinesis.construction.PUNCH_TICKS
import org.shipwrights.enderkinesis.spell.Element
import org.shipwrights.enderkinesis.spell.SpellVfx
import org.valkyrienskies.core.api.ships.ClientShip
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.isBlockInShipyard
import kotlin.math.IEEErem
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the two hands (hand 0 left, hand 1 right) with the user's hand models in deepslate and the Chaos arcs from the gem to each
 * hand that is out. A hand flies each leg in the server's flight time, so it is there when the server acts: eased
 * start and stop on a low arc, turned toward where it goes, with a wind-up and follow-through on punches, a small
 * press on a place, and a slow drift beside the gem at rest. Render thread only; no allocation per frame.
 */
class ClawhodRenderer(ctx: BlockEntityRendererProvider.Context) : BlockEntityRenderer<ClawhodBlockEntity> {

    // Hand 0 rests on the left side with the right-handed model, so each thumb points toward the centre.
    private val models = arrayOf(
        ClawhodHandModel(ctx.bakeLayer(ClawhodHandModel.RIGHT_LAYER)),
        ClawhodHandModel(ctx.bakeLayer(ClawhodHandModel.LEFT_LAYER)),
    )

    private val gem = Vector3d()
    private val goal = Vector3d()
    private val goalLocal = Vector3d()
    private val cell = Vector3d()
    private val toGem = Vector3d()
    private val local = Vector3d()
    private val velocity = Vector3d()
    private val offset = Vector3d()
    private var restYaw = 0.0
    private var restRoll = 0f
    private val turn = Quaternionf()
    private val swingTurn = Quaternionf()
    private val rollTurn = Quaternionf()
    private val lightPos = BlockPos.MutableBlockPos()

    override fun render(
        be: ClawhodBlockEntity,
        partialTick: Float,
        ps: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val level = be.level as? ClientLevel ?: return
        val pos = be.blockPos
        val ship = level.getShipManagingPos(pos) as? ClientShip
        toWorld(ship, gem.setClawhodGem(pos, be.blockState.getValue(ClawhodBlock.FACING)))
        val now = level.gameTime + partialTick.toDouble()
        val dt = if (be.clientTime.isNaN()) 0.0 else (now - be.clientTime).coerceIn(0.0, MAX_STEP_TICKS)
        be.clientTime = now
        val arcs = level.gameTime % ARC_EVERY_TICKS == 0L && be.lastArcTick != level.gameTime
        if (arcs) be.lastArcTick = level.gameTime
        val hands = be.clientHands()
        for (i in 0 until CLAWHOD_HANDS) {
            val m = hands[i]
            val target = be.handTarget(i)
            val punching = target != NO_POS && be.handPunches(i)
            val holdWant = if (be.shownItem(i).isEmpty) 0.0 else 1.0
            m.homeHold += (holdWant - m.homeHold) * (1.0 - exp(-dt / HOLD_EASE_TICKS))
            val solid = if (target == NO_POS) {
                homeGoal(be, ship, i, m.homeHold)
                restPose(be, i)
                false
            } else {
                aimAt(level, target, m.openTarget == target, be.handLooseId(i), partialTick)
            }
            if (m.pos.x.isNaN()) startAt(m, ship, target, punching)
            if (target != m.target || punching != m.punch) {
                m.duration = if (target == m.target) SHIFT_TICKS else flightTicks(m.pos.distance(goal)).toDouble()
                toShip(ship, m.from.set(m.pos))
                m.start = now
                m.target = target
                m.punch = punching
                m.placeTarget = NO_POS
                m.openTarget = NO_POS
            }
            val t = if (m.duration <= 0.0) 1.0 else ((now - m.start) / m.duration).coerceIn(0.0, 1.0)
            if (t >= 1.0 && target != NO_POS && !solid) m.openTarget = target
            m.prev.set(m.pos)
            fly(m, ship, t)
            if (t >= 1.0) settle(m, ship, i, target, punching, solid, now)
            turnToward(m, ship, dt, target, if (be.shownIsTool(i)) toolRoll(i) else 0f)
            chooseClip(m, target, punching, t >= 1.0 && target != NO_POS && !punching && !solid && be.handLooseId(i) == ClawhodBlockEntity.NO_LOOSE, !be.shownItem(i).isEmpty, be.shownIsTool(i), now)
            if (arcs && (target != NO_POS || t < 1.0)) {
                addArc(ship, m, pos.hashCode() * 31 + i + level.gameTime.toInt())
            }
            draw(be, m, ship, i, ps, bufferSource)
        }
    }

    /** A Chaos arc from the gem to the hand; on a ship its ends stay in the ship frame, so it moves with the ship. */
    private fun addArc(ship: ClientShip?, m: HandMotion, seed: Int) {
        if (ship == null) {
            SpellVfxClient.addLocal(
                SpellVfx.Kind.ARC, Vec3(gem.x, gem.y, gem.z), Vec3(m.pos.x, m.pos.y, m.pos.z), 1f, Element.CHAOS.color,
                ARC_LIFE_TICKS, seed,
            )
            return
        }
        val worldToShip = ship.renderTransform.worldToShip
        worldToShip.transformPosition(offset.set(gem))
        val from = Vec3(offset.x, offset.y, offset.z)
        worldToShip.transformPosition(offset.set(m.pos))
        SpellVfxClient.addOnShip(
            SpellVfx.Kind.ARC, ship.id, from, Vec3(offset.x, offset.y, offset.z), 1f, Element.CHAOS.color, ARC_LIFE_TICKS, seed,
        )
    }

    private fun startAt(m: HandMotion, ship: ClientShip?, target: Long, punching: Boolean) {
        m.pos.set(goal)
        toShip(ship, m.from.set(goal))
        m.duration = 0.0
        m.target = target
        m.punch = punching
    }

    /**
     * Sets the hand on its leg at [t], with the leg and its arc in the Clawhod's ship frame, so the leg moves and turns
     * with the ship. The end is the live [goal], so a leg to a target in another frame ends exactly on it.
     */
    private fun fly(m: HandMotion, ship: ClientShip?, t: Double) {
        if (t >= 1.0) {
            m.pos.set(goal)
            return
        }
        toShip(ship, goalLocal.set(goal))
        val length = m.from.distance(goalLocal)
        m.pos.set(m.from).lerp(goalLocal, easeInOut(t))
        m.pos.y += legLift(t, length)
        toWorld(ship, m.pos)
    }

    /**
     * Sets [goal] to the cell centre ([cell]) of [target]. A hand stops short of a solid block (a cut,
     * a supply) on the gem side, by [PUNCH_REST]; an open cell it fills stops it [PLACE_REST] short, outside the cell. Returns true for a solid target. An [open] target stays open
     * though the block is placed there, so the hand does not jump back when the block appears.
     */
    private fun aimAt(level: ClientLevel, target: Long, open: Boolean, looseId: Int, partial: Float): Boolean {
        val item = if (looseId == ClawhodBlockEntity.NO_LOOSE) null else level.getEntity(looseId) as? ItemEntity
        if (item != null) {
            cell.set(Mth.lerp(partial.toDouble(), item.xo, item.x), Mth.lerp(partial.toDouble(), item.yo, item.y) + itemLift(item, partial), Mth.lerp(partial.toDouble(), item.zo, item.z))
        } else {
            cell.set(BlockPos.getX(target) + 0.5, BlockPos.getY(target) + 0.5, BlockPos.getZ(target) + 0.5)
        }
        lightPos.set(target)
        if (level.isBlockInShipyard(lightPos)) toWorld(level.getShipManagingPos(lightPos) as? ClientShip, cell)
        toGem.set(gem).sub(cell)
        val len = toGem.length()
        if (len > MIN_LENGTH) toGem.div(len) else toGem.set(0.0, 1.0, 0.0)
        if (looseId != ClawhodBlockEntity.NO_LOOSE) {
            // The hand goes right onto the item, with no stand-off; with the entity gone, onto where it lay in its cell.
            goal.set(cell)
            if (item == null) goal.y -= LOOSE_LOW
            return false
        }
        goal.set(cell)
        val state = level.getBlockState(lightPos)
        val solid = !open && !state.isAir && !state.canBeReplaced()
        goal.fma(if (solid) PUNCH_REST else minOf(PLACE_REST, len), toGem)
        return solid
    }

    /** Sets [restYaw] and [restRoll]: the rest heading, turned outward by [HOME_YAW_OUT], and the outward roll. */
    private fun restPose(be: ClawhodBlockEntity, i: Int) {
        val state = be.blockState
        val facing = state.getValue(ClawhodBlock.FACING)
        val forward = clawhodForward(facing, state.getValue(ClawhodBlock.HEADING))
        val side = forward.clockWise
        val sign = handSign(facing, i)
        val tanOut = tan(HOME_YAW_OUT)
        restYaw = atan2(forward.stepX + tanOut * sign * side.stepX, forward.stepZ + tanOut * sign * side.stepZ)
        val sideX = sign * side.stepX * cos(restYaw) - sign * side.stepZ * sin(restYaw)
        restRoll = if (sideX >= 0.0) -HOME_ROLL_OUT else HOME_ROLL_OUT
    }

    /** +1 when hand [i] (0 is the left hand) rests on the clockwise side of the rest heading, else -1. */
    private fun handSign(facing: Direction, i: Int): Int = if (i == 0) clawhodLeftSign(facing) else -clawhodLeftSign(facing)

    /** The rest point: [hold] 0 to 1 moves a hand holding an item lower (toward the base) and further out. */
    private fun homeGoal(be: ClawhodBlockEntity, ship: ClientShip?, i: Int, hold: Double) {
        val state = be.blockState
        val facing = state.getValue(ClawhodBlock.FACING)
        val side = clawhodForward(facing, state.getValue(ClawhodBlock.HEADING)).clockWise
        val out = (HOME_SIDE + hold * HOLD_OUT) * handSign(facing, i)
        val drop = hold * HOLD_DROP
        goal.setClawhodGem(be.blockPos, facing)
        goal.add(side.stepX * out - facing.stepX * drop, side.stepY * out - facing.stepY * drop, side.stepZ * out - facing.stepZ * drop)
        toWorld(ship, goal)
    }

    /** How far the item renders above its entity origin: the bob, then a quarter of the flat model's height (vanilla). */
    private fun itemLift(item: ItemEntity, partial: Float): Double =
        Mth.sin((item.age + partial) / ITEM_BOB_PERIOD + item.bobOffs) * ITEM_BOB + ITEM_BOB + ITEM_HALF

    /** The extra roll of a tool fist: onto its side, thumb up (the models roll the other way, so the sign differs). */
    private fun toolRoll(i: Int): Float = if (i == 0) -TOOL_FIST_ROLL else TOOL_FIST_ROLL

    /** At the leg's end: a slow drift at home, the punch swing, or the small press of a place, in the ship's axes. */
    @Suppress("LongParameterList") // Plain values from one hand's frame.
    private fun settle(m: HandMotion, ship: ClientShip?, i: Int, target: Long, punching: Boolean, solid: Boolean, now: Double) {
        val since = now - (m.start + m.duration)
        when {
            target == NO_POS -> {
                val fade = (since / IDLE_FADE_TICKS).coerceIn(0.0, 1.0)
                val a = 2 * PI * now / IDLE_PERIOD_TICKS + i * PI
                addShipOffset(m, ship, IDLE_SWAY * fade * cos(a), IDLE_BOB * fade * sin(a * 2), IDLE_SWAY * fade * sin(a))
            }
            punching -> m.pos.set(cell).fma(punchBack((now % PUNCH_TICKS) / PUNCH_TICKS), toGem)
            !solid -> addShipOffset(m, ship, 0.0, -placeDip(since / PLACE_DIP_TICKS), 0.0)
        }
    }

    private fun addShipOffset(m: HandMotion, ship: ClientShip?, x: Double, y: Double, z: Double) {
        offset.set(x, y, z)
        ship?.renderTransform?.shipToWorld?.transformDirection(offset)
        m.pos.add(offset)
    }

    /**
     * Turns the hand so its fingers point along gem to target ([cell], set by [aimAt] this frame), in the frame it is
     * drawn in. With no target it eases to the rest pose ([restYaw], [restRoll]). The clips animate on top of this base turn.
     */
    private fun turnToward(m: HandMotion, ship: ClientShip?, dt: Double, target: Long, extraRoll: Float) {
        if (dt <= 0.0) return
        val k = (1.0 - exp(-dt / TURN_TICKS)).toFloat()
        var wantPitch = 0f
        var wantRoll = 0f
        if (target == NO_POS) {
            m.yaw += ((restYaw - m.yaw).IEEErem(2 * PI) * k).toFloat()
            wantRoll = restRoll
        } else {
            velocity.set(cell).sub(gem)
            ship?.renderTransform?.worldToShip?.transformDirection(velocity)
            val horizontal = sqrt(velocity.x * velocity.x + velocity.z * velocity.z)
            if (horizontal > MIN_LENGTH) {
                val wantYaw = atan2(velocity.x, velocity.z)
                m.yaw += ((wantYaw - m.yaw).IEEErem(2 * PI) * k).toFloat()
            }
            wantPitch = (-atan2(velocity.y, horizontal)).toFloat()
        }
        m.pitch += (wantPitch - m.pitch) * k
        m.roll += (wantRoll + extraRoll - m.roll) * k
    }

    /** Picks the clip for a hand: fist while it punches, place once on arrival, else holding or idle. */
    @Suppress("LongParameterList") // Plain values from one hand's frame.
    private fun chooseClip(
        m: HandMotion, target: Long, punching: Boolean, placing: Boolean, holding: Boolean, tool: Boolean, now: Double,
    ) {
        if (placing && m.placeTarget != target) {
            m.placeTarget = target
            startClip(m, CLIP_PLACE, now)
        }
        val placeRuns = m.clip == CLIP_PLACE && m.placeTarget == target && now - m.clipStart < PLACE_CLIP_TICKS
        val want = when {
            punching -> CLIP_FIST
            placeRuns -> CLIP_PLACE
            tool -> CLIP_FIST
            holding -> CLIP_HOLDING
            else -> CLIP_IDLE
        }
        if (want != m.clip) startClip(m, want, now)
    }

    private fun startClip(m: HandMotion, clip: Int, now: Double) {
        m.prevClip = if (m.clip < 0) clip else m.clip
        m.prevStart = if (m.clip < 0) now else m.clipStart
        m.clip = clip
        m.clipStart = now
        m.switchAt = now
    }

    private fun clipMillis(now: Double, start: Double, clip: Int): Long {
        val speed = if (clip == CLIP_PLACE) PLACE_SPEED else 1.0
        return ((now - start) * MILLIS_PER_TICK * speed).toLong().coerceAtLeast(0L)
    }

    private fun draw(be: ClawhodBlockEntity, m: HandMotion, ship: ClientShip?, i: Int, ps: PoseStack, buffers: MultiBufferSource) {
        val level = be.level ?: return
        val pos = be.blockPos
        local.set(m.pos)
        ship?.renderTransform?.worldToShip?.transformPosition(local)
        val sample = if (inHull(ship, local)) local else m.pos
        val light = LevelRenderer.getLightColor(level, lightPos.set(sample.x, sample.y, sample.z))
        val now = be.clientTime
        val model = models[i]
        val clips = if (i == 0) RIGHT_CLIPS else LEFT_CLIPS
        val k = ((now - m.switchAt) / HAND_BLEND_TICKS).coerceIn(0.0, 1.0).toFloat()
        model.pose(clips[m.prevClip], clipMillis(now, m.prevStart, m.prevClip), clips[m.clip], clipMillis(now, m.clipStart, m.clip), k * k * (3f - 2f * k))
        ps.pushPose()
        ps.translate(local.x - pos.x, local.y - pos.y, local.z - pos.z)
        ps.mulPose(turn.rotationYXZ(m.yaw, m.pitch, 0f))
        if (m.punch && be.shownIsTool(i)) {
            // A chop: the whole fist, tool fixed in it, turns about the wrist behind the palm.
            val v = (PUNCH_REST - punchBack((now % PUNCH_TICKS) / PUNCH_TICKS)) / PUNCH_DEPTH
            val degrees = if (v >= 0.0) v * SWING_STRIKE_DEGREES else v * SWING_WIND_DEGREES
            ps.translate(0.0, 0.0, -WRIST)
            ps.mulPose(swingTurn.rotationX(Math.toRadians(degrees).toFloat()))
            ps.translate(0.0, 0.0, WRIST)
        }
        ps.mulPose(rollTurn.rotationZ(m.roll))
        // The vanilla entity frame (yaw 0 faces +Z): a turn of 180 degrees, then the flip of the model's Y-down space.
        ps.mulPose(HALF_TURN_Y)
        ps.scale(-1f, -1f, 1f)
        ps.translate(0.0, -PALM_CENTER_Y, -PALM_CENTER_Z)
        model.render(ps, buffers.getBuffer(RenderType.entityCutout(DEEPSLATE)), light, OverlayTexture.NO_OVERLAY)
        val shown = be.shownItem(i)
        if (!shown.isEmpty) {
            model.palm.translateAndRotate(ps)
            if (be.shownIsTool(i)) {
                drawTool(shown, i, light, ps, buffers, level, pos.hashCode() + i)
            } else {
                ps.translate(0.0, ITEM_LIFT, PALM_CENTER_Z)
                ps.scale(1f, -1f, -1f) // Undoes the turn and flip above, so the item is upright.
                ps.scale(ITEM_SIZE, ITEM_SIZE, ITEM_SIZE)
                Minecraft.getInstance().itemRenderer.renderStatic(
                    shown, ItemDisplayContext.FIXED, light, OverlayTexture.NO_OVERLAY, ps, buffers, level, pos.hashCode() + i,
                )
            }
        }
        ps.popPose()
    }

    /**
     * A tool gripped in the sideways fist of the fist_idle clip (called in the palm's pose, so it follows the palm).
     * The handle crosses the curled fingers along the thumb axis, the head points up the thumb side and forward,
     * and the sprite lies parallel to the palm. The tool stays fixed in the fist.
     */
    @Suppress("LongParameterList") // Plain values from one hand's frame.
    private fun drawTool(
        tool: ItemStack, i: Int, light: Int, ps: PoseStack, buffers: MultiBufferSource,
        level: Level, seed: Int,
    ) {
        ps.pushPose()
        ps.translate(0.0, TOOL_GRIP_Y, TOOL_GRIP_Z)
        ps.mulPose(TOOL_FRAMES[i])
        ps.scale(TOOL_SIZE, TOOL_SIZE, TOOL_SIZE)
        ps.translate(TOOL_HANDLE, TOOL_HANDLE, 0.0)
        Minecraft.getInstance().itemRenderer.renderStatic(
            tool, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, ps, buffers, level, seed,
        )
        ps.popPose()
    }

    /**
     * True when shipyard point [p] is in [ship]'s block box (max inclusive). There the hand takes the hull's shipyard
     * light, like the ship's own blocks; outside it takes the world light where it is drawn.
     */
    private fun inHull(ship: ClientShip?, p: Vector3d): Boolean {
        val box = ship?.shipAABB ?: return false
        return p.x >= box.minX() && p.x < box.maxX() + 1 &&
            p.y >= box.minY() && p.y < box.maxY() + 1 &&
            p.z >= box.minZ() && p.z < box.maxZ() + 1
    }

    private fun toWorld(ship: ClientShip?, v: Vector3d) {
        ship?.renderTransform?.shipToWorld?.transformPosition(v)
    }

    private fun toShip(ship: ClientShip?, v: Vector3d) {
        ship?.renderTransform?.worldToShip?.transformPosition(v)
    }

    // The hands fly up to 5 blocks out of the block's own box.
    override fun shouldRenderOffScreen(be: ClawhodBlockEntity): Boolean = true

    private companion object {
        val DEEPSLATE = ResourceLocation("minecraft", "textures/block/deepslate.png")
        val HALF_TURN_Y: Quaternionf = Axis.YP.rotationDegrees(180f)
        const val CLIP_IDLE = 0
        const val CLIP_FIST = 1
        const val CLIP_HOLDING = 2
        const val CLIP_PLACE = 3
        val LEFT_CLIPS = arrayOf(
            ClawhodHandAnimations.IDLE_LEFT, ClawhodHandAnimations.FIST_LEFT,
            ClawhodHandAnimations.HOLDING_LEFT, ClawhodHandAnimations.PLACE_LEFT,
        )
        val RIGHT_CLIPS = arrayOf(
            ClawhodHandAnimations.IDLE_RIGHT, ClawhodHandAnimations.FIST_RIGHT,
            ClawhodHandAnimations.HOLDING_RIGHT, ClawhodHandAnimations.PLACE_RIGHT,
        )
        const val MILLIS_PER_TICK = 50.0
        /** The palm box centre in model space (1 px = 1/16): the point that sits on the hand's position. */
        const val PALM_CENTER_Y = 23.0 / 16.0
        const val PALM_CENTER_Z = 0.5 / 16.0
        /** A resting hand sits this far (blocks) to the side of the gem. */
        const val HOME_SIDE = 0.5
        /** At rest each hand turns its fingers this far away from the centre line, and rolls its outer edge down (the thumb edge rises). */
        val HOME_YAW_OUT = Math.toRadians(15.0)
        val HOME_ROLL_OUT = Math.toRadians(20.0).toFloat()
        /** A hand holding an item rests this much lower and further out (blocks); it eases over [HOLD_EASE_TICKS]. */
        const val HOLD_DROP = 0.3
        const val HOLD_OUT = 0.25
        const val HOLD_EASE_TICKS = 6.0
        /** A loose item rests about this far under its cell centre (blocks). */
        const val LOOSE_LOW = 0.3
        /** ItemEntityRenderer: bob = sin(age / 10 + offset) * 0.1 + 0.1, then 0.25 x the ground scale (0.5 for a flat item). */
        const val ITEM_BOB_PERIOD = 10f
        const val ITEM_BOB = 0.1f
        const val ITEM_HALF = 0.125
        const val MAX_STEP_TICKS = 5.0
        const val ARC_EVERY_TICKS = 2L
        const val ARC_LIFE_TICKS = 3
        /** A carried item hangs under the palm, in the curled fingers (model Y is down). */
        const val ITEM_SIZE = 0.35f
        const val ITEM_LIFT = 0.12
        // A held tool, measured on the fist_idle pose: the curled fingers sit 0.5 to 0.9 px under the palm pivot and
        // 0.6 to 0.9 px behind the finger pivots (z -0.8), so the handle passes there. The grip is a third up the sprite's handle
        // from its lower end, so about 2 px of handle stay below the little-finger side.
        const val TOOL_GRIP_Y = 0.7 / 16.0
        const val TOOL_GRIP_Z = -0.8 / 16.0
        const val TOOL_SIZE = 1.2f
        const val TOOL_HANDLE = 0.27
        /** Extra roll of a tool fist, on top of the fist clip's 75 degrees: the tool stands upright. */
        val TOOL_FIST_ROLL = Math.toRadians(20.0).toFloat()
        /** A tool punch turns the whole fist about the wrist, this far behind the palm centre (blocks). */
        const val WRIST = 0.3
        /** Degrees per unit of the jab phase: the strike goes down and forward, the wind-up back and up. */
        const val SWING_STRIKE_DEGREES = 60.0
        const val SWING_WIND_DEGREES = 100.0

        /**
         * Sprite space to palm space for each hand (hand 0 has the right model, thumb at -X). The sprite's handle
         * diagonal becomes the thumb axis, so the handle is straight across the fist and the head is above the thumb
         * side; the sprite's other diagonal becomes the finger axis, so the blade points forward.
         */
        val TOOL_FRAMES: Array<Quaternionf> = arrayOf(toolFrame(-1f), toolFrame(1f))

        private fun toolFrame(thumb: Float): Quaternionf {
            val r = sqrt(0.5).toFloat()
            return Quaternionf().setFromNormalized(
                Matrix3f()
                    .setColumn(0, Vector3f(thumb * r, 0f, r))
                    .setColumn(1, Vector3f(thumb * r, 0f, -r))
                    .setColumn(2, Vector3f(0f, thumb, 0f)),
            )
        }
        /** The idle drift fades in over this time after a hand comes home, so it does not jump. */
        const val IDLE_FADE_TICKS = 10.0
        const val MIN_LENGTH = 1.0e-6
    }
}
