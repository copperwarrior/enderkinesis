package org.shipwrights.enderkinesis.client

import com.mojang.authlib.GameProfile
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.GlobalPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.Level
import org.joml.Vector3d
import net.minecraft.nbt.Tag
import org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity
import org.shipwrights.enderkinesis.item.LedgerOfHuntingPincersItem
import org.shipwrights.enderkinesis.item.LedgerOfWatchingEyesItem
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.mod.common.dimensionId
import org.valkyrienskies.mod.common.shipObjectWorld
import java.util.Optional
import java.util.UUID

/**
 * Renders the levitating ender-eye and the flat compass on top of the chunk-meshed frame.
 *
 * **Compass needle uses a stub Player as observer.** Vanilla compass model overrides need an
 * Entity; must be a Player subclass (not Entity/ItemEntity) — `CompassItemPropertyFunction`
 * gates the recovery branch on `instanceof Player`. Setting `yHeadRot = β` (ship MC yaw)
 * cancels the ship-rotation contribution baked into the BER pose, so the needle ends up at
 * the world bearing via the chunk transform — only the −90°X "lay flat" is applied manually.
 *
 * **Eye rotation:** `β − α` to cancel ship rotation before applying target. Mesh uses NONE
 * display context + manual orientation; GROUND only translates+scales without reorienting.
 */
class EyeroscopeRenderer : BlockEntityRenderer<EyeroscopeBlockEntity> {

    /** Single shared instance — BERs are singletons; we re-teleport before every compass render. */
    private var observer: Player? = null

    override fun render(
        be: EyeroscopeBlockEntity,
        partialTick: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
        packedOverlay: Int,
    ) {
        val mc = Minecraft.getInstance()
        val level = be.level ?: return
        val itemRenderer = mc.itemRenderer

        val time = level.gameTime + partialTick.toDouble()
        val bobOffset = Math.sin(time * BOB_RATE).toFloat() * BOB_AMPLITUDE
        val target = currentTargetYawRad(be)
        val beta = shipBetaMcYaw(be)

        // --- Slot: compass (vanilla property-fn needle) or filled map (MapRenderer) -------
        val compass = be.compassStack
        if (!compass.isEmpty && compass.`is`(Items.FILLED_MAP)) {
            renderMap(be, compass, level, pose, buffers, packedLight)
        } else if (!compass.isEmpty) {
            val ship = findShip(level, be.blockPos)
            val w = Vector3d(be.blockPos.x + 0.5, be.blockPos.y + 0.5, be.blockPos.z + 0.5)
            if (ship != null) ship.transform.shipToWorld.transformPosition(w)
            val obs = ensureObserver(level)
            obs.setPos(w.x, w.y, w.z)
            val betaDeg = Math.toDegrees(beta.toDouble()).toFloat()
            obs.setYRot(betaDeg)
            obs.yRotO = betaDeg
            // setYHeadRot on LivingEntity sets the actual head-rot field that the property
            // function reads via getYHeadRot(). Mirror yHeadRotO too so vanilla's per-frame
            // entity-rotation interpolation doesn't read the per-frame teleport as a sudden
            // head-snap that the wobble would over-correct.
            obs.setYHeadRot(betaDeg)
            obs.yHeadRotO = betaDeg
            // Recovery-compass hook: the property fn pulls lastDeathLocation off the Player
            // observer, not off the stack — so for recovery we re-write the observer's death
            // history each frame to the BE's captured pin. Doesn't touch the real local
            // player's death state.
            if (compass.`is`(Items.RECOVERY_COMPASS)) {
                val pin = be.getCompassPin()
                obs.setLastDeathLocation(
                    if (pin != null) Optional.of(GlobalPos.of(level.dimension(), pin))
                    else Optional.empty()
                )
            }
            // Belt-and-suspenders entity hand-off: stash on the stack too in case anything
            // else (a downstream mod, the model's own getOverrides chain) consults
            // `stack.getEntityRepresentation()` before the explicit param reaches it.
            compass.setEntityRepresentation(obs)

            pose.pushPose()
            pose.translate(0.5, COMPASS_HEIGHT.toDouble(), 0.5)
            // 180° spin around block-local Y *after* the flatten in vertex flow —
            // which means *before* the X-flatten line in pose-stack code, because
            // PoseStack is right-multiply (the last `mulPose` is the first transform
            // applied to vertices). Placing the Y rotation *after* the X-flatten in
            // code (my first attempt) made it act on the model's pre-flatten frame,
            // where +Y is the model's vertical axis — rotating around vertical mirror-
            // flipped the sprite L↔R but left the needle pointing the same block face.
            // The needle in `compass_00` reads as 180° off the expected bearing because
            // the texture's "needle up" lands at the *opposite* of where my flatten
            // sends sprite +Y; this rotation puts it on the correct face without
            // touching the property function (so the wobble keeps smoothing the true
            // bearing).
            pose.mulPose(Axis.YP.rotationDegrees(180f))
            pose.mulPose(Axis.XP.rotationDegrees(-90f))
            pose.scale(COMPASS_SCALE, COMPASS_SCALE, COMPASS_SCALE)
            // No outer re-centre translate here. `ItemRenderer.render` internally calls
            // `poseStack.translate(-0.5F, -0.5F, -0.5F)` after the display transform to
            // centre the model on its frame origin — adding our own would stack the
            // two and shift the compass half a block toward block-local
            // (+0.25, +0.25, +0.25) in the final XZ → wildly off-centre. The vanilla
            // translate places `item/generated`'s `(0..1, 0..1, 0.46875..0.53125)`
            // model symmetrically around our `(0.5, COMPASS_HEIGHT, 0.5)` anchor.
            //
            // Pass the observer through the entity-accepting overload so
            // `CompassItemPropertyFunction.unclampedCall` receives it as the `entity`
            // parameter directly — not via `stack.getEntityRepresentation()`. The
            // entity-accepting path skips one indirection (no transient-field hand-off),
            // which makes it robust against any mod or mapper that resets the stack's
            // representation between our setter call and renderStatic's getOverrides
            // resolve.
            itemRenderer.renderStatic(
                obs,
                compass,
                ItemDisplayContext.NONE,
                false,
                pose,
                buffers,
                level,
                packedLight,
                OverlayTexture.NO_OVERLAY,
                0,
            )
            pose.popPose()
        }

        // --- Eye: bobbing, pointing along the target heading ----------------------------
        pose.pushPose()
        pose.translate(0.5, EYE_BASE_HEIGHT.toDouble() + bobOffset.toDouble(), 0.5)
        if (!target.isNaN()) {
            if (be.upgraded) {
                // 3-axis pointing. Build the world target direction from (yaw, pitch) in MC
                // convention, transform it through the inverse of the ship rotation to get
                // the direction in the eyeroscope's block-local frame, then decompose into
                // local (yaw, pitch) and apply as JOML pose rotations. JOML yaw flips sign
                // vs MC (CCW-positive vs CW-positive); JOML pitch matches MC (both have
                // positive = looking down). Order MC-equivalent: yaw outer, pitch inner.
                val pitch = currentTargetPitchRad(be)
                val cy = Math.cos(target.toDouble()); val sy = Math.sin(target.toDouble())
                val cp = Math.cos(pitch.toDouble()); val sp = Math.sin(pitch.toDouble())
                val worldDir = Vector3d(-sy * cp, -sp, cy * cp)

                val ship = findShip(level, be.blockPos)
                val localDir = if (ship != null) {
                    val dest = Vector3d()
                    ship.transform.shipToWorldRotation.transformInverse(worldDir, dest)
                    dest
                } else worldDir

                val len = Math.max(localDir.length(), 1e-9)
                val localYaw = Math.atan2(-localDir.x, localDir.z)
                val localPitch = Math.asin((-localDir.y / len).coerceIn(-1.0, 1.0))
                pose.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(-localYaw).toFloat()))
                pose.mulPose(Axis.XP.rotationDegrees(Math.toDegrees(localPitch).toFloat()))
            } else {
                val gammaEye = beta - target
                pose.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(gammaEye.toDouble()).toFloat()))
            }
        }
        itemRenderer.renderStatic(
            if (be.upgraded) EYE_STACK_UPGRADED else EYE_STACK,
            ItemDisplayContext.GROUND,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            pose,
            buffers,
            level,
            0,
        )
        pose.popPose()
    }

    /** Renders the filled map flat on the eyeroscope.
     *
     *  Mirrors vanilla `ItemInHandRenderer.m_109366_` (the held-map render) verbatim
     *  — same YP 180 + ZP 180 chain, same `scale + translate + scale(1/128)`, same
     *  manual parchment quad with vertices at (-7, 135) through (135, -7) using
     *  `RenderType.text("textures/map/map_background.png")`, followed by
     *  `MapRenderer.render`. The only differences:
     *
     *   1. `XP -90` *before* vanilla's chain (in pose code) flattens the map quad from
     *      vertical-facing-camera to horizontal-facing-up. Verified face composition:
     *      ZP180 ∘ YP180 ∘ XP-90 sends quad normal -Z → +Y, so the front face points
     *      up at a player viewing from above. Equivalent to `XP +90` alone but the
     *      decomposed form keeps the parallel with vanilla obvious.
     *
     *   2. `YP -facing.toYRot()` *before* the XP-flatten (in pose code) rotates the
     *      flat sprite in its own plane so the painted north points away from a player
     *      approaching the FACING side. Since this is the outermost rotation in vertex
     *      flow and acts only on the +Y face normal, it can't disturb face direction.
     *
     *  Why not `ItemRenderer.renderStatic` for the parchment? Vanilla's internal
     *  `translate(-0.5, -0.5, -0.5)` inside `ItemRenderer.render` puts the model's -Z
     *  thickness axis at the front face, which after our XP-flatten lands at block-
     *  local +Y ≈ MAP_HEIGHT + 0.4. The parchment ends up floating well above the
     *  eyeroscope and is invisible from a normal viewing angle. Vanilla's manual quad
     *  approach sidesteps the issue entirely. */
    private fun renderMap(
        be: EyeroscopeBlockEntity,
        mapStack: ItemStack,
        level: Level,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
    ) {
        val mapId = MapItem.getMapId(mapStack) ?: return
        val mapData = MapItem.getSavedData(mapStack, level) ?: return
        val mc = Minecraft.getInstance()

        pose.pushPose()
        pose.translate(0.5, MAP_HEIGHT.toDouble(), 0.5)
        // Flat YP 180 (no FACING dependency) spins the laid-flat sprite half-turn around
        // block-Y. Applied *before* the XP-flatten in code = *after* in vertex flow, so
        // the face normal is already +Y by the time this Y-rotation hits it (Y-axis
        // invariant ⇒ face stays up). Result: texture top lands at block-local +Z.
        pose.mulPose(Axis.YP.rotationDegrees(180f))
        // XP -90 flattens to horizontal. YP 180 + ZP 180 is vanilla's V-flip — vanilla
        // `MapRenderer` submits vertex y=0 with UV V=0 (texture top), which is "upside
        // down" by 2D-rendering convention; without the pose-Y flip the texture's top
        // appears at the bottom of the player's view.
        pose.mulPose(Axis.XP.rotationDegrees(-90f))
        pose.mulPose(Axis.YP.rotationDegrees(180f))
        pose.mulPose(Axis.ZP.rotationDegrees(180f))
        pose.scale(MAP_DISPLAY_SIZE, MAP_DISPLAY_SIZE, MAP_DISPLAY_SIZE)
        pose.translate(-0.5, -0.5, 0.0)
        pose.scale(1f / 128f, 1f / 128f, 1f / 128f)

        // Parchment quad — verbatim from vanilla `m_109366_` bytecode at offset 111-310.
        val matrix = pose.last().pose()
        val parchmentVC = buffers.getBuffer(MAP_BACKGROUND_TYPE)
        parchmentVC.vertex(matrix, -7f, 135f, 0f).color(255, 255, 255, 255).uv(0f, 1f).uv2(packedLight).endVertex()
        parchmentVC.vertex(matrix, 135f, 135f, 0f).color(255, 255, 255, 255).uv(1f, 1f).uv2(packedLight).endVertex()
        parchmentVC.vertex(matrix, 135f, -7f, 0f).color(255, 255, 255, 255).uv(1f, 0f).uv2(packedLight).endVertex()
        parchmentVC.vertex(matrix, -7f, -7f, 0f).color(255, 255, 255, 255).uv(0f, 0f).uv2(packedLight).endVertex()

        mc.gameRenderer.mapRenderer.render(pose, buffers, mapId, mapData, false, packedLight)
        pose.popPose()
    }

    /** Lazy-create the observer for [level]. Re-creates on dimension change so its `level()`
     *  matches what the property function will compare against (the cross-dim lodestone
     *  branch checks `globalPos.dimension() == entity.level().dimension()`). */
    private fun ensureObserver(level: Level): Player {
        val existing = observer
        if (existing != null && existing.level() === level) return existing
        val fresh = CompassObserver(level)
        observer = fresh
        return fresh
    }

    /** (dx, dy, dz) from the eyeroscope's world position to the pin's world position, or
     *  null when there's no pin. Mirrors the BE's `refreshPinBearing` math so the eye
     *  updates smoothly between the server's 10-tick bearing refreshes — including the
     *  shipyard-pin shipToWorld transform so the target tracks the live ship as it
     *  sails. Returns world-frame deltas; convert to yaw/pitch at the call site. */
    private fun currentPinDelta(be: EyeroscopeBlockEntity): Vector3d? {
        val pin = be.getCompassPin() ?: return null
        val level = be.level ?: return null
        val pos = be.blockPos
        val ship = findShip(level, pos)
        val w = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        if (ship != null) ship.transform.shipToWorld.transformPosition(w)

        val pinWorld = Vector3d(pin.x + 0.5, pin.y + 0.5, pin.z + 0.5)
        val pinShip = findShip(level, pin)
        if (pinShip != null) pinShip.transform.shipToWorld.transformPosition(pinWorld)

        return Vector3d(pinWorld.x - w.x, pinWorld.y - w.y, pinWorld.z - w.z)
    }

    /** Target heading in world MC yaw radians, or NaN when there's no target. */
    private fun currentTargetYawRad(be: EyeroscopeBlockEntity): Float {
        val d = currentPinDelta(be)
            ?: currentLedgerDelta(be)
            ?: currentHuntingDelta(be)
            ?: return be.getStaticTargetYaw()
        // Render-side dead-zone matches the BE's logic: directly over the pin the
        // bearing is noise, so fall back to the static cache.
        if (d.x * d.x + d.z * d.z < 1.0) return be.getStaticTargetYaw()
        return Math.atan2(-d.x, d.z).toFloat()
    }

    /** Target MC pitch in radians. Non-upgraded mode is always 0 (the eye doesn't tilt
     *  for non-upgraded eyeroscopes regardless of pin elevation). */
    private fun currentTargetPitchRad(be: EyeroscopeBlockEntity): Float {
        if (!be.upgraded) return 0f
        val d = currentPinDelta(be)
            ?: currentLedgerDelta(be)
            ?: currentHuntingDelta(be)
            ?: return be.getStaticTargetPitch()
        val horizDist = Math.sqrt(d.x * d.x + d.z * d.z)
        if (horizDist < 1.0) return be.getStaticTargetPitch()
        return Math.atan2(-d.y, horizDist).toFloat()
    }

    /** (dx, dy, dz) from the eyeroscope's world position to the live world position of
     *  the *closest* loaded entity in the hunting ledger's sighting list, or null if
     *  the slot doesn't hold a hunting ledger or every recorded entity is unloaded.
     *  Mirrors the BE-side `updateHuntingTarget`. Client has to walk
     *  `entitiesForRendering()` since `ClientLevel.getEntity(int)` takes the entity
     *  *network id*, not a UUID. */
    private fun currentHuntingDelta(be: EyeroscopeBlockEntity): Vector3d? {
        val stack = be.compassStack
        if (!stack.`is`(EKItems.LEDGER_OF_HUNTING_PINCERS.get())) return null
        val tag = stack.tag ?: return null
        val list = tag.getList(LedgerOfHuntingPincersItem.TRACKED_ENTITIES_TAG, Tag.TAG_COMPOUND.toInt())
        if (list.isEmpty()) return null
        val level = be.level as? net.minecraft.client.multiplayer.ClientLevel ?: return null

        val placer = be.getHuntingLedgerPlacer()
        val trackedUuids = HashSet<java.util.UUID>(list.size)
        for (i in 0 until list.size) {
            val u = list.getCompound(i).getUUID("id")
            if (u != placer) trackedUuids.add(u)            // defensive: never aim at the placer
        }
        if (trackedUuids.isEmpty()) return null

        val pos = be.blockPos
        val myShip = findShip(level, pos)
        val myWorld = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        myShip?.transform?.shipToWorld?.transformPosition(myWorld)

        var bestEntity: net.minecraft.world.entity.Entity? = null
        var bestDistSq = Double.MAX_VALUE
        for (entity in level.entitiesForRendering()) {
            if (entity !is net.minecraft.world.entity.LivingEntity) continue
            if (entity.uuid !in trackedUuids) continue
            val dx = entity.x - myWorld.x
            val dy = entity.y - myWorld.y
            val dz = entity.z - myWorld.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestEntity = entity
            }
        }
        val target = bestEntity ?: return null
        return Vector3d(target.x - myWorld.x, target.y - myWorld.y, target.z - myWorld.z)
    }

    /** (dx, dy, dz) from the eyeroscope's world position to the live world position of
     *  the *closest* loaded ship in the ledger's sighting list, or null if the slot
     *  doesn't hold a ledger or every recorded ship is unloaded. Mirrors the BE-side
     *  `updateLedgerTarget` — walks the sighting list and keeps the smallest
     *  eye-to-ship squared distance. */
    private fun currentLedgerDelta(be: EyeroscopeBlockEntity): Vector3d? {
        val stack = be.compassStack
        if (!stack.`is`(EKItems.LEDGER_OF_WATCHING_EYES.get())) return null
        val tag = stack.tag ?: return null
        val list = tag.getList(LedgerOfWatchingEyesItem.TRACKED_SHIPS_TAG, Tag.TAG_COMPOUND.toInt())
        if (list.isEmpty()) return null
        val level = be.level ?: return null
        val pos = be.blockPos
        val myShip = findShip(level, pos)
        val myWorld = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
        myShip?.transform?.shipToWorld?.transformPosition(myWorld)

        var bestDx = 0.0; var bestDy = 0.0; var bestDz = 0.0
        var bestDistSq = Double.MAX_VALUE
        for (i in 0 until list.size) {
            val shipId = list.getCompound(i).getLong("id")
            val target = level.shipObjectWorld.allShips.getById(shipId) ?: continue
            val tp = target.transform.positionInWorld
            val dx = tp.x() - myWorld.x
            val dy = tp.y() - myWorld.y
            val dz = tp.z() - myWorld.z
            val distSq = dx * dx + dy * dy + dz * dz
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestDx = dx; bestDy = dy; bestDz = dz
            }
        }
        if (bestDistSq == Double.MAX_VALUE) return null
        return Vector3d(bestDx, bestDy, bestDz)
    }

    /** Whichever ship owns the given chunk, looked up via `allShips` rather than
     *  `getLoadedShipManagingPos`. Matches the BE's `findShipForShipyardPos` — see that
     *  function for why the `loadedShips` filter in the vanilla helper isn't sufficient.
     *  Works on both server (`Ship`) and client (`ClientShip`) since both expose
     *  `Ship.transform`, which is all the call sites need. */
    private fun findShip(level: net.minecraft.world.level.Level, pos: BlockPos):
        org.valkyrienskies.core.api.ships.Ship? {
        val sow = level.shipObjectWorld
        val dim = level.dimensionId
        val cx = pos.x shr 4
        val cz = pos.z shr 4
        if (!sow.isChunkInShipyard(cx, cz, dim)) return null
        return sow.allShips.getByChunkPos(cx, cz, dim)
    }

    /** World MC yaw of ship-local +Z (south at identity), aka β. Cancels the ship-rotation
     *  contribution to my block-local rotations so they land at the right *world* direction.
     *  0 for world-placed eyeroscopes. */
    private fun shipBetaMcYaw(be: EyeroscopeBlockEntity): Float {
        val level = be.level ?: return 0f
        val ship = findShip(level, be.blockPos) ?: return 0f
        val v = Vector3d(0.0, 0.0, 1.0)
        ship.transform.shipToWorldRotation.transform(v)
        return Math.atan2(-v.x, v.z).toFloat()
    }

    /** Minimal Player subclass used as the observer the compass property function reads
     *  for `position`, `getYHeadRot`, and (for recovery compasses) `getLastDeathLocation`.
     *
     *  Why Player and not a lighter Entity:
     *  `CompassItemPropertyFunction.getPosition()` short-circuits on the recovery compass
     *  with `stack.is(Items.RECOVERY_COMPASS) && entity instanceof Player player` — any
     *  non-Player entity falls through and the function returns the spawn position (or
     *  null), making the needle spin. Only a Player instance routes correctly.
     *
     *  Why minimal: Player is abstract — `isSpectator()` and `isCreative()` must be
     *  implemented. The vanilla constructor allocates an InventoryMenu, FoodData,
     *  abilities, and similar machinery; that's done once at first render and the stub is
     *  never ticked, added to the level, or referenced again outside this BER. */
    private class CompassObserver(level: Level) : Player(
        level,
        BlockPos.ZERO,
        0f,
        GameProfile(OBSERVER_UUID, "ek_eyeroscope_observer"),
    ) {
        override fun isSpectator(): Boolean = false
        override fun isCreative(): Boolean = false

        companion object {
            /** Fixed UUID for the stub. Doesn't need to be unique across the game (we never
             *  add this to any tracked entity collection) — a constant just keeps the entity
             *  identifiable in any debug log. */
            private val OBSERVER_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000ec0001")
        }
    }

    private companion object {
        private val EYE_STACK: ItemStack = ItemStack(Items.ENDER_EYE)

        /** Same ender eye, but with a dummy enchantment in NBT so `Item.isFoil(stack)`
         *  returns true and `ItemRenderer.renderStatic` lays down the glint pass. The
         *  enchantment type doesn't matter — vanilla only checks for the presence of an
         *  `Enchantments` list. UNBREAKING(1) is harmless and the BER never shows a
         *  tooltip, so the bogus enchantment is invisible to the player. */
        private val EYE_STACK_UPGRADED: ItemStack = ItemStack(Items.ENDER_EYE).also {
            it.enchant(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING, 1)
        }

        /** Block-local Y of the eye at rest, in blocks. Sits above the new model's two
         *  prongs (which top out at 14.56/16 ≈ 0.91); the GROUND display context adds
         *  another +0.1875 inside `renderStatic`, putting the visible eye centre around
         *  y ≈ 1.24 in block coords. */
        private const val EYE_BASE_HEIGHT: Float = 1.05f

        /** Vertical bob amplitude in blocks. */
        private const val BOB_AMPLITUDE: Float = 0.06f

        /** Bob frequency, radians per game tick. */
        private const val BOB_RATE: Double = 0.12

        /** Block-local Y of the flat-laid compass. The new model's base block top is at
         *  9/16 = 0.5625; this puts the compass a hair above that so its model thickness
         *  doesn't z-fight the base. */
        private const val COMPASS_HEIGHT: Float = 0.6f

        /** Item-model's native scale is 1 block. The gap between the new model's two
         *  prongs is ~10 blocks across, so we can run the compass a touch larger than the
         *  previous frame-fit value. */
        private const val COMPASS_SCALE: Float = 0.5f

        /** Block-local Y of the flat-laid map. Base block top is at 9/16 = 0.5625; a hair
         *  above keeps the parchment from z-fighting the base. */
        private const val MAP_HEIGHT: Float = 0.57f

        /** Total parchment + padding in blocks. Inner-prong gap is ~0.515 wide
         *  (x ≈ 0.241..0.756); 0.45 here lands the parchment at 142/128 × 0.45 ≈ 0.50,
         *  fitting cleanly between the prongs with a hair of margin. */
        private const val MAP_DISPLAY_SIZE: Float = 0.45f

        /** Same RenderType vanilla `ItemInHandRenderer` uses for the held-map parchment
         *  background (the `f_109297_` field). `RenderType.text` with the map_background
         *  texture — translucent, lightmap-aware. */
        private val MAP_BACKGROUND_TYPE: net.minecraft.client.renderer.RenderType =
            net.minecraft.client.renderer.RenderType.text(
                net.minecraft.resources.ResourceLocation("textures/map/map_background.png"))
    }
}
