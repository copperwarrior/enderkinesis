package org.shipwrights.enderkinesis.client

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.dimension.Sureibjin
import org.shipwrights.enderkinesis.dimension.Wohlonnogondonia
import org.shipwrights.enderkinesis.dimension.YgannAbyss

object DimensionLoadingBackground {
    private val TEXTURES: Map<ResourceKey<Level>, ResourceLocation> = mapOf(
        SselithRepertory.LEVEL_KEY to EnderkinesisMod.id("textures/block/sselith_bookshelf.png"),
        YgannAbyss.LEVEL_KEY to EnderkinesisMod.id("textures/environment/ygann_abyss/writhing_mass_sea.png"),
        Sureibjin.LEVEL_KEY to EnderkinesisMod.id("textures/block/dream_sand.png"),
        Wohlonnogondonia.LEVEL_KEY to ResourceLocation("minecraft", "textures/block/mud.png"),
    )

    /** Called from ScreenDimensionLoadingBackgroundMixin. */
    @JvmStatic
    fun textureOr(fallback: ResourceLocation): ResourceLocation =
        Minecraft.getInstance().level?.let { TEXTURES[it.dimension()] } ?: fallback
}
