package org.shipwrights.enderkinesis.block

import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod

/** The six statue variants. Each maps to its own Blockbench-style entity model +
 *  texture and to a distinct block registration in [org.shipwrights.enderkinesis.registry.EKBlocks].
 *  The block instance carries this enum, so the BER picks the right model by reading
 *  the block at the BE position. */
enum class StatueKind(val id: String, val tooltipKey: String) {
    STEVE("statue_steve", "block.enderkinesis.ulder_statue.tooltip.steve"),
    CATALOGER("statue_cataloger", "block.enderkinesis.ulder_statue.tooltip.cataloger"),
    TENTACLES("statue_tentacles", "block.enderkinesis.ulder_statue.tooltip.tentacles"),
    TENTACLED_BEAST("statue_tentacled_beast", "block.enderkinesis.ulder_statue.tooltip.tentacled_beast"),
    YELLOW_TOWER("statue_yellow_tower", "block.enderkinesis.ulder_statue.tooltip.yellow_tower"),
    BLACK_GOAT("statue_black_goat", "block.enderkinesis.ulder_statue.tooltip.black_goat"),
    COUNTING("statue_counting", "block.enderkinesis.ulder_statue.tooltip.counting"),
    ;

    val texture: ResourceLocation = EnderkinesisMod.id("textures/entity/statue/$id.png")
}
