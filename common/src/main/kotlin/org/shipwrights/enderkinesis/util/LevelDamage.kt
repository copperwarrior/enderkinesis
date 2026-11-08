package org.shipwrights.enderkinesis.util

import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.level.Level

/** A damage source of [type] with no entity. Vanilla's `DamageSources.source(key)` is private; only Fabric API widens it. */
fun Level.damageSource(type: ResourceKey<DamageType>): DamageSource =
    DamageSource(registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(type))
