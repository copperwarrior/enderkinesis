package org.shipwrights.enderkinesis.dimension

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.MobSpawnType
import org.shipwrights.enderkinesis.registry.EKEntities

object WohlonDeathWisp {
    fun init() {
        EntityEvent.LIVING_DEATH.register { entity, _ ->
            if (entity is ServerPlayer && !entity.isSpectator && entity.level().dimension() == Wohlonnogondonia.LEVEL_KEY) {
                leaveWisp(entity)
            }
            EventResult.pass()
        }
    }

    private fun leaveWisp(player: ServerPlayer) {
        val level = player.serverLevel()
        val wisp = EKEntities.WILL_O_THE_WISP.get().create(level) ?: return
        // A void death would put the wisp under the void-damage floor.
        val y = maxOf(player.y + player.bbHeight * 0.5, level.minBuildHeight + 1.0)
        wisp.moveTo(player.x, y, player.z, player.yRot, 0f)
        wisp.finalizeSpawn(level, level.getCurrentDifficultyAt(wisp.blockPosition()), MobSpawnType.TRIGGERED, null, null)
        wisp.customName = Component.literal(corrupt(player.gameProfile.name, level.random))
        wisp.setPersistenceRequired()
        level.addFreshEntity(wisp)
    }

    private const val CHARS_PER_FLAW = 8
    private const val FLAW_TRIES = 4
    private const val VOWELS = "aeiouy"

    // Small enough that the player is still recognisable.
    private fun corrupt(name: String, random: RandomSource): String {
        val chars = StringBuilder(name)
        repeat(1 + name.length / CHARS_PER_FLAW) {
            // Swapping a double letter changes nothing, so try again.
            val before = chars.toString()
            for (attempt in 0 until FLAW_TRIES) {
                flaw(chars, random)
                if (chars.toString() != before) break
            }
        }
        return chars.toString()
    }

    private fun flaw(chars: StringBuilder, random: RandomSource) {
        if (chars.length < 2) {
            chars.append(chars.lastOrNull() ?: return)
            return
        }
        val i = random.nextInt(chars.length - 1)
        when (random.nextInt(4)) {
            0 -> chars[i] = chars[i + 1].also { chars[i + 1] = chars[i] }
            1 -> chars.insert(i, chars[i])
            2 -> if (chars.length > 3) chars.deleteCharAt(i) else chars.insert(i, chars[i])
            else -> {
                val v = (i until chars.length).firstOrNull { chars[it].lowercaseChar() in VOWELS }
                if (v == null) {
                    chars.insert(i, chars[i])
                } else {
                    val other = VOWELS.filter { it != chars[v].lowercaseChar() }[random.nextInt(VOWELS.length - 1)]
                    chars[v] = if (chars[v].isUpperCase()) other.uppercaseChar() else other
                }
            }
        }
    }
}
