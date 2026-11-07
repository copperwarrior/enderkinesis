package org.shipwrights.enderkinesis.item

import java.util.EnumSet
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.scores.PlayerTeam
import org.shipwrights.enderkinesis.registry.EKItems

// No repair material: only Mending repairs it.
class CrownOfTheDarkStarItem(props: Item.Properties) : CrownItem(props) {
    companion object {
        const val MAX_DURABILITY = 407
    }
}

object CrownOfTheDarkStar {

    private const val TARGET_DROP_RADIUS = 64.0

    @JvmStatic
    fun isWorn(entity: Entity?): Boolean =
        entity is Player && entity.getItemBySlot(EquipmentSlot.HEAD).`is`(EKItems.CROWN_OF_THE_DARK_STAR.get())

    /** No hover, click or insertion, because each of those carries the real name. */
    @JvmStatic
    fun obfuscatedName(player: Player): Component =
        PlayerTeam.formatNameForTeam(player.team, player.name.copy().withStyle(ChatFormatting.OBFUSCATED))

    @JvmStatic
    fun onWornChanged(player: ServerPlayer, worn: Boolean) {
        player.server.playerList.broadcastAll(
            ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME), listOf(player))
        )
        if (worn) dropTargets(player)
    }

    // Goal AI lets go on its next canContinueToUse; this makes it immediate and clears brain and anger state too.
    private fun dropTargets(player: ServerPlayer) {
        val box = player.boundingBox.inflate(TARGET_DROP_RADIUS)
        for (mob in player.serverLevel().getEntitiesOfClass(Mob::class.java, box)) {
            if (mob.target === player) mob.target = null
            val brain = mob.brain
            if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET) &&
                brain.getMemory(MemoryModuleType.ATTACK_TARGET).get() === player
            ) {
                brain.eraseMemory(MemoryModuleType.ATTACK_TARGET)
            }
            if (mob is NeutralMob && mob.persistentAngerTarget == player.uuid) mob.stopBeingAngry()
        }
    }
}
