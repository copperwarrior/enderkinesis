package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import dev.architectury.event.events.common.PlayerEvent
import dev.architectury.event.events.common.TickEvent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentHelper
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.registry.EKItems

/**
 * Sureibjin exit: in the dream coast, lethal damage of any kind doesn't
 * kill the player — it wakes them up. Drown, fall, lava, void, `/kill`,
 * any custom mod damage — all routed to [wakeUp]: full player state
 * restored from the [SureibjinEntry] snapshot, teleported back to the
 * bed they fell asleep in.
 *
 * <p>Non-lethal damage is allowed through, so the cause of the wake-up
 * (the air-meter draining, the lava sound, the fall) still reads as the
 * cause. Only the killing hit gets intercepted.
 *
 * <p>If a player ends up in Sureibjin without entry data (e.g. via
 * `/execute in`) and would die, they still wake up — heal and drop them
 * at Overworld spawn so they're not stuck in the dream.
 */
object SureibjinExit {

    private val CROWN_UNTESTED_RECIPE: ResourceLocation = EnderkinesisMod.id("crown_untested")

    fun init() {
        // Some deaths skip `actuallyHurt` (PlayerSureibjinWakeMixin). Any death that gets to `die` wakes the player.
        EntityEvent.LIVING_DEATH.register { entity, _ ->
            if (entity is ServerPlayer && entity.level().dimension() == Sureibjin.LEVEL_KEY) {
                wakeUp(entity)
                EventResult.interruptFalse()
            } else {
                EventResult.pass()
            }
        }
        // Health set to zero with no `die` call. A player whose `die` ran is left to the respawn restore.
        TickEvent.PLAYER_POST.register { player ->
            if (player is ServerPlayer && player.isDeadOrDying && !diedSinceLastAliveTick(player) &&
                player.level().dimension() == Sureibjin.LEVEL_KEY
            ) {
                wakeUp(player)
            }
        }
        PlayerEvent.PLAYER_JOIN.register(::unlockCrownRecipe)
        // A command or a mod teleport can take a player out of the dream with no death.
        PlayerEvent.CHANGE_DIMENSION.register { player, from, to ->
            if (from == Sureibjin.LEVEL_KEY && to != Sureibjin.LEVEL_KEY) restoreIfDreaming(player)
        }
        // A death that another mod forces past the death hook ends in a normal respawn.
        PlayerEvent.PLAYER_RESPAWN.register { player, _ ->
            if (player.level().dimension() != Sureibjin.LEVEL_KEY) restoreIfDreaming(player)
        }
    }

    // `ServerPlayer.die` does not set `dead`. It resets this stat, and `Player.tick` adds to it only when alive.
    private fun diedSinceLastAliveTick(player: ServerPlayer): Boolean =
        player.stats.getValue(Stats.CUSTOM.get(Stats.TIME_SINCE_DEATH)) == 0

    // [wakeUp] removes the snapshot before its teleport, so this acts only on an exit that it did not start.
    private fun restoreIfDreaming(player: ServerPlayer) {
        val snapshot = SureibjinEntry.takeReturnData(player.server, player.uuid) ?: return
        SureibjinEntry.markExit(player)
        restoreState(player, snapshot)
    }

    /** Vanilla's `readAdditionalSaveData` rebuilds inventory, ender chest, food, XP, effects and abilities. */
    private fun restoreState(player: ServerPlayer, snapshot: SureibjinEntry.ReturnSnapshot) {
        val crown = player.getItemBySlot(EquipmentSlot.HEAD).takeIf { it.`is`(EKItems.CROWN_UNTESTED.get()) }?.copy()
        player.inventory.clearContent()
        // `readAdditionalSaveData` adds the saved effects to the current ones, so remove the dream's effects first.
        player.removeAllEffects()
        player.readAdditionalSaveData(snapshot.playerStateTag)
        if (crown != null) wearDarkStarCrown(player, crown)
    }

    private fun wearDarkStarCrown(player: ServerPlayer, untested: ItemStack) {
        val crown = ItemStack(EKItems.CROWN_OF_THE_DARK_STAR.get())
        if (untested.hasCustomHoverName()) crown.setHoverName(untested.hoverName)
        EnchantmentHelper.setEnchantments(EnchantmentHelper.getEnchantments(untested), crown)
        if (player.getItemBySlot(EquipmentSlot.HEAD).isEmpty) player.setItemSlot(EquipmentSlot.HEAD, crown)
        else player.inventory.placeItemBackInInventory(crown)
    }

    /** Called with the final damage, after difficulty, armor, absorption and both loaders' damage events. */
    @JvmStatic
    fun wakeInsteadOfLethalHit(player: ServerPlayer): Boolean {
        if (player.level().dimension() != Sureibjin.LEVEL_KEY) return false
        wakeUp(player)
        return true
    }

    /** Restore the snapshotted player state and teleport back to the bed.
     *  Public so a debug command could force-wake a stuck player. */
    fun wakeUp(player: ServerPlayer) {
        val server = player.server
        // Awarded regardless of whether we can restore — dying in the
        // dream is the trigger, not the successful bed return.
        SureibjinEntry.grantAdvancement(player, SureibjinEntry.ADV_KNOWLEDGE_OF_DEATH)
        val snapshot = SureibjinEntry.takeReturnData(server, player.uuid)
        SureibjinEntry.markExit(player)

        // Heal first so the lethal hit that triggered this doesn't kill
        // them on the next tick before the snapshot restores.
        healMinimal(player)
        if (snapshot != null) restoreState(player, snapshot)

        // No entry record, or the source dim is gone (datapack swap?) — overworld spawn.
        val targetLevel = snapshot?.let { server.getLevel(it.dimension) }
        if (snapshot == null || targetLevel == null) {
            val overworld = server.overworld()
            val spawn = overworld.sharedSpawnPos
            player.teleportTo(
                overworld,
                spawn.x + 0.5, spawn.y.toDouble(), spawn.z + 0.5,
                player.yRot, player.xRot,
            )
            return
        }

        player.teleportTo(
            targetLevel,
            snapshot.x, snapshot.y, snapshot.z,
            snapshot.yRot, snapshot.xRot,
        )
    }

    // Players who got Knowledge of Death before the advancement gave this recipe as a reward.
    private fun unlockCrownRecipe(player: ServerPlayer) {
        val advancement = player.server.advancements.getAdvancement(SureibjinEntry.ADV_KNOWLEDGE_OF_DEATH) ?: return
        if (!player.advancements.getOrStartProgress(advancement).isDone) return
        if (player.recipeBook.contains(CROWN_UNTESTED_RECIPE)) return
        player.awardRecipesByKey(arrayOf(CROWN_UNTESTED_RECIPE))
    }

    /** Minimal heal to survive the next tick — the snapshot restore (if
     *  any) sets the final values right after. */
    private fun healMinimal(player: ServerPlayer) {
        player.health = player.maxHealth
        player.foodData.foodLevel = 20
        player.airSupply = player.maxAirSupply
        player.fallDistance = 0.0f
        player.clearFire()
    }
}
