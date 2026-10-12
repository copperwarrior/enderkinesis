package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.Block

// Forge attaches item renderers from the `Item` constructor, before `BlockItem.block` is set.
// A test on the block fails there; a test on this class does not.
class StatueBlockItem(block: Block, properties: Properties) : BlockItem(block, properties)
