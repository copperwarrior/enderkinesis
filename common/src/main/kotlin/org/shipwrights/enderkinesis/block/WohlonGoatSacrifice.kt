package org.shipwrights.enderkinesis.block

import dev.architectury.event.EventResult
import dev.architectury.event.events.common.EntityEvent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.animal.goat.Goat
import net.minecraft.world.phys.Vec3
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKSounds

/** The tree rite fires when a goat dies by a Blessed Razor inside a lit Heart Candle circle. */
object WohlonGoatSacrifice {

    private const val CIRCLE_RADIUS = 3
    private const val CIRCLE_RADIUS_SQ = 3.5 * 3.5
    private const val CIRCLE_DY = 1

    fun init() {
        EntityEvent.LIVING_DEATH.register { entity, source ->
            if (entity is Goat && isRazorKill(source)) {
                (entity.level() as? ServerLevel)?.let { level ->
                    litHeartNear(level, entity)?.let { ignite(level, it, entity.position().add(0.0, entity.bbHeight * 0.5, 0.0)) }
                }
            }
            EventResult.pass()
        }
    }

    private fun isRazorKill(source: DamageSource): Boolean {
        val killer = source.entity as? LivingEntity ?: return false
        return !source.isIndirect && killer.mainHandItem.`is`(EKItems.BLESSED_RAZOR.get())
    }

    private fun litHeartNear(level: ServerLevel, goat: Goat): BlockPos? {
        val feet = goat.blockPosition()
        val cursor = BlockPos.MutableBlockPos()
        for (dy in -CIRCLE_DY..CIRCLE_DY) {
            for (dz in -CIRCLE_RADIUS..CIRCLE_RADIUS) {
                for (dx in -CIRCLE_RADIUS..CIRCLE_RADIUS) {
                    cursor.setWithOffset(feet, dx, dy, dz)
                    val state = level.getBlockState(cursor)
                    if (!state.`is`(EKBlocks.HEART_CANDLE.get()) || !state.getValue(HeartCandleBlock.LIT)) continue
                    val ox = goat.x - (cursor.x + 0.5)
                    val oz = goat.z - (cursor.z + 0.5)
                    if (ox * ox + oz * oz <= CIRCLE_RADIUS_SQ) return cursor.immutable()
                }
            }
        }
        return null
    }

    private fun ignite(level: ServerLevel, heartPos: BlockPos, sacrifice: Vec3) {
        if (!WohlonnogondoniaPortalRitual.checkPattern(level, heartPos)) return
        if (!WohlonnogondoniaPortalRitual.isBiomeValid(level, heartPos)) return
        val portalPos = heartPos.above(2)
        if (!WohlonnogondoniaPortalManager.addPortal(level, portalPos)) return
        WohlonnogondoniaSpreader.startPortalSeed(level, portalPos)
        WohlonnogondoniaTreeGrower.startTree(level, portalPos)
        WohlonRitualNetwork.sendIgnite(level, heartPos, sacrifice)
        WohlonRitualNetwork.sendCall(level, heartPos)
        level.playSound(
            null, heartPos.x + 0.5, heartPos.y + 0.5, heartPos.z + 0.5,
            EKSounds.WOHLON_RITUAL_IGNITE.get(), SoundSource.BLOCKS, 3f, 1f,
        )
    }
}
