package org.shipwrights.enderkinesis.spell

/** Each kind is its own field, but all kinds share one full cell. Colours are linear RGB for the volume shader. */
enum class GasKind(
    val rises: Boolean,
    val poisons: Boolean,
    /** Ticks in the gas before Drake Sickness; 0 is never. The count resets when the entity leaves the gas. */
    val sickensAfterTicks: Int,
    /** Gas density over air density at amount 1. Below 1 the gas rises, above 1 it sinks. */
    val airDensityRatio: Float,
    val darkR: Float, val darkG: Float, val darkB: Float,
    val lightR: Float, val lightG: Float, val lightB: Float,
    /** Share of the difference to its neighbours that a cell evens out in one spread step. Lower is a slower spread. */
    val spreadShare: Float,
    /** Cells that gas climbs (or sinks) in one settle step. */
    val settleCells: Int,
    /** Share of gas that a settle step spreads sideways from the cell it reaches, so a rising or sinking column fans out. 0 is none. */
    val settleSpread: Float,
    /** Share of a flow that stays each tick. The client uses the same value, so both sides drag a flow alike. */
    val flowDrag: Float,
) {
    MIASMA(
        rises = false, poisons = true, sickensAfterTicks = 0, airDensityRatio = 1.5f,
        darkR = 0.14f, darkG = 0.22f, darkB = 0.1f,
        lightR = 0.5f, lightG = 0.68f, lightB = 0.29f,
        spreadShare = 0.5f, settleCells = 1, settleSpread = 0f, flowDrag = 0.88f,
    ),
    DRAKE_BREATH(
        rises = true, poisons = false, sickensAfterTicks = 200, airDensityRatio = 0.2f,
        darkR = 0.86f, darkG = 0.46f, darkB = 0.68f,
        lightR = 1.0f, lightG = 0.95f, lightB = 0.98f,
        spreadShare = 0.5f, settleCells = 2, settleSpread = 0.5f, flowDrag = 0.95f,
    );

    /**
     * Vertical force (N, up is positive) of a ship cell holding [amount], before the air density at the cell: F = W·a·(1 − r).
     * It is linear over 0..2 by user choice, so compressed gas gives more lift. Drake gives 40 kN·a; Miasma gives −25 kN·a.
     */
    fun liftWeight(amount: Float): Float = amount * (1f - airDensityRatio)

    companion object {
        /** Weight in newtons of the air in one full cell at sea level: 40 kN / (1 − 0.2), so Drake keeps 40 kN a cell. */
        const val AIR_WEIGHT_NEWTONS = 50_000.0
    }
}
