package org.shipwrights.enderkinesis.item

import dev.architectury.registry.registries.RegistrySupplier
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.sselith.SselithEclipse

/** A held cast: 1 XP each second in survival, half a heart each second when the XP is gone; free in creative.
 *  [handColour] tints the idle hand motes on the client. */
abstract class IncantationItem(
    properties: Properties,
    val handColour: Int,
    private val startSound: RegistrySupplier<SoundEvent>,
    private val stopSound: RegistrySupplier<SoundEvent>,
    val loopSound: RegistrySupplier<SoundEvent>,
) : Item(properties) {

    /** Runs on the server each tick of the cast. */
    protected abstract fun cast(level: ServerLevel, player: Player)

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        player.startUsingItem(hand)
        if (!level.isClientSide) playAtCaster(level, player, startSound.get())
        return InteractionResultHolder.consume(stack)
    }

    override fun releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, timeLeft: Int) {
        if (!level.isClientSide) playAtCaster(level, entity, stopSound.get())
    }

    private fun playAtCaster(level: Level, caster: LivingEntity, sound: SoundEvent) {
        level.playSound(null, caster.x, caster.y, caster.z, sound, SoundSource.PLAYERS, 1f, 1f)
    }

    override fun getUseDuration(stack: ItemStack): Int = Int.MAX_VALUE
    override fun getUseAnimation(stack: ItemStack): UseAnim = UseAnim.NONE

    override fun onUseTick(level: Level, entity: LivingEntity, stack: ItemStack, remainingUseDuration: Int) {
        if (level !is ServerLevel) return
        if (entity !is Player) return
        if (!entity.abilities.instabuild) {
            val used = getUseDuration(stack) - remainingUseDuration
            if (used > 0 && used % TICKS_PER_SECOND == 0) {
                if (hasXp(entity)) {
                    entity.giveExperiencePoints(-1)
                } else {
                    SselithEclipse.applyLightDamage(level, entity, NO_XP_HEALTH_COST)
                }
            }
        }
        cast(level, entity)
    }

    private fun hasXp(player: Player): Boolean =
        player.experienceLevel > 0 || player.experienceProgress > 0f

    companion object {
        private const val TICKS_PER_SECOND = 20
        private const val NO_XP_HEALTH_COST = 1.0f

        /** ServerPlayers need the explicit motion packet, or the client never feels the push. */
        fun markPushed(entity: Entity) {
            entity.hasImpulse = true
            if (entity is ServerPlayer) {
                entity.hurtMarked = true
                entity.connection.send(ClientboundSetEntityMotionPacket(entity))
            }
        }
    }
}
