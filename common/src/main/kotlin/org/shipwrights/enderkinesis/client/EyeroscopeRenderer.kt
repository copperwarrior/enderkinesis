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
import net.minecraft.core.GlobalPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.MapItem
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.shipwrights.enderkinesis.blockentity.EyeroscopeBlockEntity
import org.valkyrienskies.mod.common.getLoadedShipManagingPos
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
            renderMap(compass, level, beta, pose, buffers, packedLight)
        } else if (!compass.isEmpty) {
            val ship = level.getLoadedShipManagingPos(be.blockPos)
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
            val gammaEye = beta - target
            pose.mulPose(Axis.YP.rotationDegrees(Math.toDegrees(gammaEye.toDouble()).toFloat()))
        }
        itemRenderer.renderStatic(
            EYE_STACK,
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

    /** Draws the filled map flat on the eyeroscope via vanilla `MapRenderer.render` —
     *  so the actual world texture and every decoration (X marks, banners, mansion /
     *  monument icons) come along for free. The `false` flag suppresses the player
     *  marker; we counter-rotate by `−β` so the map's painted "north" stays pointing
     *  world-north as the ship turns.
     *
     *  Orientation caveat: `Axis.XP.rotationDegrees(-90f)` alone sends the map texture's
     *  +Y (top / north) toward block-local +Z (south side of the eyeroscope) — so a
     *  player approaching from the south will see the map with its north edge nearest
     *  to them. Achieving "north edge far from approaching player" without a reflection
     *  isn't possible with rotations alone, and `RenderType.text` (which MapRenderer
     *  uses) culls back faces, so a negative-scale workaround would erase the map. */
    private fun renderMap(
        mapStack: ItemStack,
        level: Level,
        beta: Float,
        pose: PoseStack,
        buffers: MultiBufferSource,
        packedLight: Int,
    ) {
        val mapId = MapItem.getMapId(mapStack) ?: return
        val mapData = MapItem.getSavedData(mapStack, level) ?: return
        val mc = Minecraft.getInstance()
        pose.pushPose()
        pose.translate(0.5, MAP_HEIGHT.toDouble(), 0.5)
        // Counter-rotate the ship's contribution to block-local frame so the map's painted
        // cardinal directions stay world-aligned. Without this the map would spin with the
        // ship and a player riding the ship would lose the map's static-world reference.
        val betaDeg = Math.toDegrees(beta.toDouble()).toFloat()
        pose.mulPose(Axis.YP.rotationDegrees(-betaDeg))
        pose.mulPose(Axis.XP.rotationDegrees(-90f))
        pose.scale(MAP_SCALE, MAP_SCALE, MAP_SCALE)
        // MapRenderer's quad spans (0, 0)..(128, 128) in pose-local coords; re-centre so
        // the eyeroscope's centre lines up with the map's centre pixel rather than its
        // corner.
        pose.translate(-64.0, -64.0, 0.0)
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

    /** Target heading in world MC yaw radians, or NaN when there's no target. Compass-pinned
     *  mode recomputes the bearing here client-side (using the same shipToWorld math the
     *  server uses) so the eye updates smoothly between the server's 10-tick refreshes.
     *  Static mode just returns the BE's stored static yaw. */
    private fun currentTargetYawRad(be: EyeroscopeBlockEntity): Float {
        val pin = be.getCompassPin()
        if (pin != null) {
            val level = be.level ?: return be.getStaticTargetYaw()
            val pos = be.blockPos
            val ship = level.getLoadedShipManagingPos(pos)
            val w = Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)
            if (ship != null) ship.transform.shipToWorld.transformPosition(w)
            val dx = (pin.x + 0.5) - w.x
            val dz = (pin.z + 0.5) - w.z
            // Render-side dead-zone matches the BE's logic: directly over the pin the
            // bearing is noise, so fall back to the static cache.
            if (dx * dx + dz * dz < 1.0) return be.getStaticTargetYaw()
            return Math.atan2(-dx, dz).toFloat()
        }
        return be.getStaticTargetYaw()
    }

    /** World MC yaw of ship-local +Z (south at identity), aka β. Cancels the ship-rotation
     *  contribution to my block-local rotations so they land at the right *world* direction.
     *  0 for world-placed eyeroscopes. */
    private fun shipBetaMcYaw(be: EyeroscopeBlockEntity): Float {
        val level = be.level ?: return 0f
        val ship = level.getLoadedShipManagingPos(be.blockPos) ?: return 0f
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

        /** Block-local Y of the flat-laid map. Same height as the compass; either renders,
         *  not both (slot holds one item). */
        private const val MAP_HEIGHT: Float = 0.6f

        /** Map quad is 128 pose-local units wide. Scale so the rendered map covers ~0.75
         *  blocks across — slightly larger than the compass (0.5) because the texture has
         *  small-detail icons (X marks, banners) that need readable size. */
        private const val MAP_SCALE: Float = 0.75f / 128f
    }
}
