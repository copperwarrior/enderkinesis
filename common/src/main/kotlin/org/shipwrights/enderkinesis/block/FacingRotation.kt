package org.shipwrights.enderkinesis.block

import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties

// Vanilla DirectionalBlock has no rotate/mirror; these are the DispenserBlock rules.
fun BlockState.rotateFacing(rotation: Rotation): BlockState =
    setValue(BlockStateProperties.FACING, rotation.rotate(getValue(BlockStateProperties.FACING)))

fun BlockState.mirrorFacing(mirror: Mirror): BlockState =
    rotateFacing(mirror.getRotation(getValue(BlockStateProperties.FACING)))
