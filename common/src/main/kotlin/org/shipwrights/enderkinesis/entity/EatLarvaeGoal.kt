package org.shipwrights.enderkinesis.entity

import com.mojang.logging.LogUtils
import java.util.EnumSet
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.goal.Goal
import org.shipwrights.enderkinesis.block.MoskuoLarvaeBlock

private val LOG = LogUtils.getLogger()

/** Swims a fish under the nearest [MoskuoLarvaeBlock] and eats one larva. */
class EatLarvaeGoal(
    private val fish: PathfinderMob,
    private val range: Double,
    private val speed: Double,
    private val wants: () -> Boolean,
    private val onEat: () -> Unit,
) : Goal() {
    private var patch: BlockPos? = null
    private var searchCooldown = 0
    private var swimTicks = 0
    private val cursor = BlockPos.MutableBlockPos()

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        if (--searchCooldown > 0 || !wants()) return false
        searchCooldown = SEARCH_INTERVAL_TICKS
        val level = fish.level() as? ServerLevel ?: return false
        patch = MoskuoLarvaeBlock.findNear(level, fish.blockPosition(), range.toInt(), SCAN_Y, cursor)
        return patch != null
    }

    override fun canContinueToUse(): Boolean = patch != null && swimTicks < SWIM_TICKS && wants()

    override fun requiresUpdateEveryTick(): Boolean = true

    override fun start() {
        swimTicks = 0
    }

    override fun tick() {
        val at = patch ?: return
        val level = fish.level() as? ServerLevel ?: return
        // The larvae sit on the surface; the fish feeds from the water block under them.
        if (fish.distanceToSqr(at.x + HALF, at.y - HALF, at.z + HALF) < EAT_DISTANCE * EAT_DISTANCE) {
            if (MoskuoLarvaeBlock.eatOne(level, at)) {
                onEat()
                LOG.info("[FishDebug] {} ate a Moskuo larva at {}", fish.type.toShortString(), at)
            }
            patch = null
            return
        }
        if (swimTicks++ % REPATH_TICKS == 0) fish.navigation.moveTo(at.x + HALF, at.y - HALF, at.z + HALF, speed)
    }

    override fun stop() {
        patch = null
        fish.navigation.stop()
    }

    private companion object {
        const val SEARCH_INTERVAL_TICKS = 40
        const val SWIM_TICKS = 200
        const val REPATH_TICKS = 20
        const val EAT_DISTANCE = 1.2
        const val HALF = 0.5
        const val SCAN_Y = 4
    }
}
