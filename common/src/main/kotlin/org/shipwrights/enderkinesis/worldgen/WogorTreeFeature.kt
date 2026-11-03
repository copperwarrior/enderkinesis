package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration

enum class WogorTreeShape(private val id: String) : StringRepresentable {
    BALD_CYPRESS("bald_cypress"),
    KNEE_CYPRESS("knee_cypress"),
    LEANER("leaner"),
    TIERED_WEEPER("tiered_weeper"),
    BANK_WEEPER("bank_weeper"),
    KNOB_WILLOW("knob_willow");

    override fun getSerializedName() = id

    companion object {
        val CODEC: Codec<WogorTreeShape> = StringRepresentable.fromEnum { values() }
    }
}

class WogorTreeConfiguration(val shape: WogorTreeShape) : FeatureConfiguration {
    companion object {
        val CODEC: Codec<WogorTreeConfiguration> = RecordCodecBuilder.create { i ->
            i.group(WogorTreeShape.CODEC.fieldOf("shape").forGetter(WogorTreeConfiguration::shape))
                .apply(i, ::WogorTreeConfiguration)
        }
    }
}

class WogorTreeFeature(codec: Codec<WogorTreeConfiguration>) : Feature<WogorTreeConfiguration>(codec) {
    override fun place(context: FeaturePlaceContext<WogorTreeConfiguration>): Boolean {
        val tree = WogorTreeBuilder(context.level(), context.random(), context.origin())
        val grown = when (context.config().shape) {
            WogorTreeShape.BALD_CYPRESS -> WogorTreeShapes.cypress(tree, flare = 2.2, knees = false)
            WogorTreeShape.KNEE_CYPRESS -> WogorTreeShapes.cypress(tree, flare = 2.8, knees = true)
            WogorTreeShape.LEANER -> WogorTreeShapes.leaner(tree)
            WogorTreeShape.TIERED_WEEPER -> WogorTreeShapes.tieredWeeper(tree)
            WogorTreeShape.BANK_WEEPER -> WogorTreeShapes.bankWeeper(tree)
            WogorTreeShape.KNOB_WILLOW -> WogorTreeShapes.knobWillow(tree)
        }
        return grown && tree.commit()
    }
}
