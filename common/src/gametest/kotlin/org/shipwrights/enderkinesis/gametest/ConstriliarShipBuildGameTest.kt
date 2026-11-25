package org.shipwrights.enderkinesis.gametest

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3

/** The only time limit of the ship build test: 20 in-game minutes, set-up included. */
const val BUILD_TIME_LIMIT_TICKS = 20 * 60 * 20

/** Real-time limit. Ticks cannot catch a server thread that stalls inside one tick. */
const val WALL_CLOCK_LIMIT_SECONDS = 30L * 60

private const val TEMPLATE = "enderkinesis_gametest:build_yard"

/**
 * The build yard, in test-relative cells. GameTest y 1 is the bottom layer of the template. The plan box is x 19..31,
 * z 14..42, y 2..25; the supplies, table and builder stand west of it, the walker wakes east of it.
 */
internal object Yard {
    const val SIZE_X = 48
    const val SIZE_Y = 40
    const val SIZE_Z = 56
    const val FLOOR_Y = 1
    val TABLE = BlockPos(15, 2, 28)

    // The walker follows its owner, so it also stays within the crew reach (1.5 plan radii) near the builder.
    val BUILDER = Vec3(8.5, 2.0, 28.5)
    val PLAN_OFFSET = Vec3i(10, 0, 0)
    val WALKER_BODY = BlockPos(38, 2, 28)
    const val SUPPLY_X = 11

    // One free cell between chests, so no two single chests join into a double chest.
    val SUPPLY_Z = listOf(28, 26, 30, 24, 32, 22, 34)
    val PLAN_ID = ResourceLocation("enderkinesis", "structures/roaming_end_ship.nbt")
}

internal fun gameTestFail(message: String): Nothing = throw GameTestAssertException(message)

class ConstriliarShipBuildGameTest {

    /** One Constriliar with one Clawhod builds the whole Roaming End ship from a drafting table, with VS2 physics on. */
    @GameTest(template = TEMPLATE, timeoutTicks = BUILD_TIME_LIMIT_TICKS)
    fun buildsRoamingEndShip(helper: GameTestHelper) {
        val run = ShipBuildRun(helper)
        run.setUp()
        helper.startSequence()
            .thenWaitUntil(run::awaitWalkerShip)
            .thenExecute(run::startBuild)
            .thenWaitUntil(run::awaitBuildEnd)
            .thenExecute(run::verify)
            .thenSucceed()
    }
}
