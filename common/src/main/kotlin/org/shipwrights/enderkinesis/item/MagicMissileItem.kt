package org.shipwrights.enderkinesis.item

import net.minecraft.world.item.Item

/**
 * Magic missile — ammunition item for the Magic Missile Launcher block. There's no
 * `use` override here: the canonical (and only) firing path is loading the missile
 * into a launcher slot via right-click and powering the block with redstone. Bare
 * right-click was previously a developer affordance for testing homing without
 * setting up a launcher; the launcher is the production interaction so the bare-hand
 * fire path is gone.
 */
class MagicMissileItem(properties: Properties) : Item(properties)
