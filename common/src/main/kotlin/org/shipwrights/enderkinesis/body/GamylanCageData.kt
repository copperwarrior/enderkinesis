package org.shipwrights.enderkinesis.body

import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import org.joml.Quaterniond
import org.joml.Quaterniondc
import org.joml.Vector3d
import org.joml.Vector3dc

/**
 * Per-cage data record. One instance per VS body that was created by a
 * Wand of Ga'Mylan capture. The cage isn't an [net.minecraft.world.entity.Entity];
 * it's a (body + this record) pair tracked in [GamylanCageRegistry] and
 * shipped to clients via [GamylanCageNetwork].
 *
 * Fields are all immutable except [hp] — every gameplay-relevant
 * value is decided at capture-time and doesn't change. HP is what
 * survives a hit; it drains 1 per attack, breaks at 0.
 */
data class GamylanCageData(
    /** VS Body the cage anchors to. Body is static; the body's
     *  world position is the cage's hover destination. */
    val bodyId: Long,
    /** Vanilla `Entity.saveAsPassenger` snapshot of the captured
     *  entity, including the `id` discriminator. Client rebuilds
     *  this for the frozen render. */
    val capturedTag: CompoundTag,
    /** Non-NBT pose snapshot (rotations / attackAnim / swingTime /
     *  walk-anim speed) captured at the same moment, applied to the
     *  reconstructed render entity each frame so the pose stays
     *  anchored to the moment of capture. */
    val animationTag: CompoundTag,
    /** Captured entity's custom name was "Tribulla" — flips the
     *  collision compound to [SoderShape] (matching the renderer's
     *  bottle model) instead of [GamylanCageShape]'s hex bipyramid.
     *  Resolved at capture time from `target.customName`. */
    val isTribulla: Boolean,
    /** World position the captured entity occupied PRE-capture.
     *  Client renderer lerps the frozen entity from here up to
     *  the hover position during the fade-in animation. */
    val captureOrigin: Vector3d,
    /** World position the cage hovers at (= the VS body's static
     *  position). Renderer uses this as the crystal centre. Mutable so
     *  the pedestal-home flow ([GamylanCagePedestals]) can retarget the
     *  hover point at a new pedestal without rebuilding the record. */
    @Volatile var hoverPos: Vector3d,
    /** Server `gameTime` tick the cage was added — drives the fade-in
     *  + rotation envelopes on the client. */
    val addedAtGameTime: Long,
    /** Crystal max HP. 20 to match the spec's "20 health points". */
    val maxHp: Int,
    /** Current HP. Mutated by [onHit] / damage flow; broadcast back to
     *  clients via [GamylanCageNetwork.broadcastHp]. */
    var hp: Int,
    /** Crystal shape dims packed as (rx, midH, tipLen):
     *   - `rx`     — equatorial hex circumradius (vertex distance from
     *                the apex axis), matching the renderer's `rx`.
     *   - `midH`   — half-height of the middle prism.
     *   - `tipLen` — pyramid height of each terminus.
     *  Drives both the compound collision shape built in
     *  [GamylanCageShape] and the visual crystal in the renderer, so
     *  collision footprint and visual mesh stay in lockstep. */
    val shapeDims: Vector3d,
    /** Captured entity's culling AABB size (xSize, ySize, zSize) at
     *  capture time. Preserves the mob's real dimensions independently
     *  of the crystal envelope so [SoderShape] can fit-scale the bottle
     *  to the mob rather than to the crystal (which is now diagonal-
     *  sized, not width-sized). Falls back to
     *  `Vector3d(2*shapeDims.x, 2*shapeDims.y, 2*shapeDims.x)` when
     *  hydrated from a pre-migration save. */
    val entityCullSize: Vector3d,
    /** VS2 dimension-id string (the tagged
     *  `minecraft:dimension:<namespace>:<path>` form). Cached on the
     *  data so the phys-tick force loop can filter cages without
     *  routing through the MC server thread. */
    val dimId: String,
    /** Tick count past which the body stops being held in place and is
     *  allowed to fall under gravity. See [GamylanCageBodyForce]. */
    val fadeEndGameTime: Long,
    /** Set to true on the server thread once `level.gameTime >=
     *  fadeEndGameTime`. The phys-tick force loop reads this Volatile-
     *  ly to decide whether to keep the body locked or let it fall. */
    @Volatile var fadeComplete: Boolean = false,
    /** Server `gameTime` of the most recent attack; -1 if the crystal
     *  hasn't taken damage since spawn (or since the last regen). Read
     *  by the per-tick regen check, which resets [hp] to [maxHp] once
     *  enough ticks pass without further damage. */
    @Volatile var lastDamageTick: Long = -1L,
    /** Captured target was a player — UUID for the client to look up
     *  the GameProfile (skin / cape). Null for non-player captures.
     *  When set, the client builds a `RemotePlayer` ghost rather than
     *  reconstructing the entity from [capturedTag] (which is empty
     *  in this path, since `Player.saveAsPassenger` refuses). */
    val playerProfileUuid: UUID? = null,
    /** Display name used to seed the client-side GameProfile alongside
     *  [playerProfileUuid]. */
    val playerName: String? = null,
    /** Player customization byte (`Player.DATA_PLAYER_MODE_CUSTOMISATION`).
     *  Bit flags for cape / jacket / left+right sleeve / left+right
     *  pants / hat visibility. Applied to the client `RemotePlayer`
     *  ghost so skin layers render like the source player's. */
    val playerCustomizationByte: Byte = 0,
    /** Six-slot equipment snapshot in [net.minecraft.world.entity.EquipmentSlot]
     *  index order (MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD) taken
     *  at capture time. Empty list for non-player captures; otherwise
     *  applied to the ghost `RemotePlayer`'s slots so the render shows
     *  the player wearing / holding what they had. */
    val playerEquipment: List<ItemStack> = emptyList(),
    /** Item displayed inside the crystal for pedestal-conjured ITEM cages
     *  (empty for entity captures). Rendered in place of the frozen
     *  entity; dropped at the break position when the crystal shatters. */
    val displayedItem: ItemStack = ItemStack.EMPTY,
    /** Block position of the Crystal Pedestal this cage has adopted as
     *  its home, or null if no pedestal claim is active. Set when the
     *  cage drifts above a pedestal via [GamylanCagePedestals]; cleared
     *  on pedestal break or when the cage leaves the pedestal's column.
     *  Frame follows [homePedestalShipId]: world coords when null,
     *  shipyard coords otherwise. While set (and the pedestal is
     *  unpowered), the phys-tick force loop drives the crystal toward
     *  `hoverPos` + `homeRotTarget`. */
    @Volatile var homePedestalPos: BlockPos? = null,
    /** VS ship id whose shipyard contains [homePedestalPos], or null if
     *  the pedestal is a plain world block. Determines whether the weld
     *  is cage-to-world or cage-to-ship, and drives the per-tick
     *  refresh of [hoverPos] / [homeRotTarget] so a pedestal mounted on
     *  a moving ship carries its crystal along. */
    @Volatile var homePedestalShipId: Long? = null,
    /** World rotation the phys-tick force loop steers the crystal
     *  toward while [homePedestalPos] is set. Identity for a world
     *  pedestal; the host ship's `shipToWorldRotation` for a ship
     *  pedestal. Recomputed each server tick from the ship's live
     *  transform so the crystal drifts back into alignment as the
     *  ship moves. Transient — not persisted. */
    @Volatile var homeRotTarget: Quaterniondc = Quaterniond(),
    /** VS Core joint id welding this cage to its pedestal, or null if no
     *  weld is active. Transient — dropped on world reload; the drift-
     *  and-align loop re-welds once the crystal settles above its
     *  pedestal again. */
    @Volatile var jointId: Int? = null,
    /** Items to drop at the cage's world position when it breaks. Set
     *  from the captured player's inventory only when the vanilla drop
     *  path would have run (keepInventory OFF, no totem, no wik'lak
     *  save). Empty otherwise. Server-only — clients don't need this. */
    @Volatile var droppedItems: MutableList<ItemStack> = java.util.ArrayList(),
) {

    fun originAsVector(): Vector3dc = captureOrigin
    fun hoverAsVector(): Vector3dc = hoverPos
}
