package org.shipwrights.enderkinesis.item

import dev.architectury.event.events.common.TickEvent
import java.util.UUID
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.joml.Vector3d
import org.shipwrights.enderkinesis.mixin.ServerGamePacketListenerAccessor
import org.shipwrights.enderkinesis.registry.EKItems
import org.valkyrienskies.core.api.ships.Ship
import org.valkyrienskies.mod.common.shipObjectWorld

/**
 * Server-side companion to [org.shipwrights.enderkinesis.client.CrepusculiteCharmClient].
 *
 * The lift itself runs on the client because vanilla doesn't sync `LivingEntity.jumping`
 * for non-vehicle movement. Position-sync packets carry the lifted Y to the server, but
 * the server's flying-anti-cheat — `aboveGroundTickCount` on
 * [net.minecraft.server.network.ServerGamePacketListenerImpl] — counts ticks the player
 * spends airborne without [net.minecraft.world.entity.player.Abilities.mayfly]; after ~80
 * ticks (4 s) it disconnects them with "Flying is not enabled on this server."
 *
 * To let legitimate charm-use survive that check without granting a blanket fly-anywhere
 * exemption to anyone holding the item, the reset is **gated by the charm's own envelope**:
 * we only zero the counter when the player is at most [MAX_HEIGHT] + [SYNC_TOLERANCE]
 * blocks above the ground directly beneath them. A cheating client at altitude 50 above
 * the nearest ground is outside the envelope, the reset doesn't fire, and the anti-cheat
 * kicks them just as it would without the charm.
 *
 * The accessor is the same one [org.shipwrights.enderkinesis.blockentity.AetherPadBlockEntity]
 * uses to bypass the check for entities in its beam.
 */
object CrepusculiteCharmManager {

    /** Charm's altitude cap (blocks above local ground). Matches the client-side lift
     *  cap so the server-side envelope mirrors the actual mechanic. */
    private const val MAX_HEIGHT: Double = 5.0

    /** Slack added to the envelope to absorb client/server sync lag and per-tick physics
     *  jitter. Without this, a player riding exactly at the cap can briefly read a hair
     *  above on the server tick and lose the exemption. */
    private const val SYNC_TOLERANCE: Double = 1.0

    /** Downward scan range (blocks) for ground search — comfortably above
     *  [MAX_HEIGHT] + [SYNC_TOLERANCE] so we always find the ground for a player at
     *  the cap. */
    private const val GROUND_SCAN: Int = 8

    /** Duration (ticks) of the slow-falling re-application. 20 t = 1 s; the manager
     *  refreshes it every tick the player is in the air and dropping, so the effective
     *  duration matches the descent. After landing the trailing 1 s tail expires on its
     *  own — short enough not to give "free safety" during subsequent movement. */
    private const val SLOW_FALL_DURATION_TICKS: Int = 20

    /** Ticks of airborne in-envelope time between durability deductions. 10 t = 0.5 s;
     *  with [CrepusculiteCharmItem.MAX_DURABILITY] = 500 that's ~4 minutes of continuous
     *  flight before Unbreaking. */
    private const val DRAIN_INTERVAL_TICKS: Int = 10

    /** Per-player tick accumulator for the drain. Keyed by player UUID; reset to 0 when
     *  the interval fires. Entries for offline players age out naturally — `tickPlayer`
     *  is only called for active players. */
    private val drainAccumulator: HashMap<UUID, Int> = HashMap()

    fun init() {
        TickEvent.PLAYER_POST.register(::tickPlayer)
    }

    private fun tickPlayer(player: Player) {
        if (player !is ServerPlayer) return
        val charmStack = findCharmStack(player) ?: return
        val groundY = findGroundY(player) ?: return                 // void below → no exemption
        if (player.y - groundY > MAX_HEIGHT + SYNC_TOLERANCE) return // outside the envelope
        (player.connection as ServerGamePacketListenerAccessor)
            .`enderkinesis$setAboveGroundTickCount`(0)

        // Slow falling while descending from a charm-supported altitude. Re-applied every
        // tick the player is in the air and dropping; vanilla resets `fallDistance` each
        // tick the effect is present, so the landing is damage-free regardless of how
        // long the descent took. Hidden (no GUI icon, no particles) — the lift particles
        // from [org.shipwrights.enderkinesis.client.CrepusculiteCharmClient] are the
        // visible cue.
        if (!player.onGround() && player.deltaMovement.y < 0.0) {
            player.addEffect(
                MobEffectInstance(
                    MobEffects.SLOW_FALLING,
                    SLOW_FALL_DURATION_TICKS,
                    /* amplifier */ 0,
                    /* ambient */ false,
                    /* visible */ false,
                    /* showIcon */ false,
                )
            )
        }

        // Drain a single point of durability every [DRAIN_INTERVAL_TICKS] of in-envelope
        // airborne time. `ItemStack.hurt` rolls the standard Unbreaking check internally
        // (probability of damage decreases as `1 / (level + 1)`), so wear is gated on
        // both ground state and the enchantment without us tracking either explicitly.
        // Mending repairs the stack via XP through the vanilla
        // `ExperienceOrb.repairPlayerItems` path — works for charms in slots
        // `getRandomItemWith` walks (mainhand, offhand, armor, Curios/Trinkets slots).
        if (!player.onGround()) {
            val acc = (drainAccumulator.getOrDefault(player.uuid, 0) + 1)
            if (acc >= DRAIN_INTERVAL_TICKS) {
                drainAccumulator[player.uuid] = 0
                damageCharm(charmStack, player)
            } else {
                drainAccumulator[player.uuid] = acc
            }
        }
    }

    /** Apply a single damage point and remove the stack if it broke. We don't call
     *  `broadcastBreakEvent` because the charm typically isn't in a hand or armor slot —
     *  the visual break would target the wrong slot. */
    private fun damageCharm(stack: ItemStack, player: ServerPlayer) {
        if (stack.hurt(1, player.random, player)) stack.shrink(1)
    }

    private fun findCharmStack(player: Player): ItemStack? {
        val charm = EKItems.CREPUSCULITE_CHARM.get()
        val inv = player.inventory
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (stack.`is`(charm)) return stack
        }
        return null
    }

    private fun findGroundY(player: ServerPlayer): Double? =
        findGroundY(player.serverLevel(), player.blockX, player.blockZ, player.y, GROUND_SCAN)

    /** Shared scan used by both [tickPlayer] (server-side anti-cheat + slow-falling
     *  envelope) and [org.shipwrights.enderkinesis.client.CrepusculiteCharmClient] (lift
     *  cap). Walks straight down from the player's column for up to [scanRange] blocks
     *  and returns the world Y of the first surface the player would land on. "Surface"
     *  is any of:
     *    1. A ship block rendered at that world cell (ship's `worldToShip` transform
     *       maps the world centre into its shipyard chunk; the block there decides).
     *    2. A fluid (water / lava — empty collision shape but non-empty fluid state).
     *       Treats bodies of liquid as ground so a player above a lake hovers at the
     *       cap above the surface.
     *    3. A normal solid world block (non-empty collision shape).
     *
     *  Ships are checked before the world block at the same Y because a ship deck above
     *  the void should win over the empty world cell beneath. */
    internal fun findGroundY(level: Level, px: Int, pz: Int, startWorldY: Double, scanRange: Int): Double? {
        val startY = Math.floor(startWorldY).toInt()
        val cx = px + 0.5
        val cz = pz + 0.5
        val ships = level.shipObjectWorld.allShips.filter { s ->
            val ab = s.worldAABB
            cx in ab.minX()..ab.maxX() && cz in ab.minZ()..ab.maxZ()
        }
        for (dy in 0..scanRange) {
            val y = startY - dy
            for (ship in ships) {
                if (isGroundOnShip(level, ship, px, y, pz)) return (y + 1).toDouble()
            }
            val pos = BlockPos(px, y, pz)
            val state = level.getBlockState(pos)
            if (state.isAir) continue
            if (!state.fluidState.isEmpty) return (y + 1).toDouble()
            if (!state.getCollisionShape(level, pos).isEmpty) return (y + 1).toDouble()
        }
        return null
    }

    private fun isGroundOnShip(level: Level, ship: Ship, worldX: Int, worldY: Int, worldZ: Int): Boolean {
        val ab = ship.worldAABB
        val cy = worldY + 0.5
        if (cy < ab.minY() || cy > ab.maxY()) return false
        val v = Vector3d(worldX + 0.5, cy, worldZ + 0.5)
        ship.transform.worldToShip.transformPosition(v)
        val sPos = BlockPos.containing(v.x, v.y, v.z)
        val sState = level.getBlockState(sPos)
        if (sState.isAir) return false
        if (!sState.fluidState.isEmpty) return true
        return !sState.getCollisionShape(level, sPos).isEmpty
    }
}
