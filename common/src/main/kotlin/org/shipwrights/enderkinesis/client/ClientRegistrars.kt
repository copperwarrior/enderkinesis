package org.shipwrights.enderkinesis.client

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.renderer.DimensionSpecialEffects
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.resources.ResourceLocation
import org.shipwrights.enderkinesis.EnderkinesisMod
import org.shipwrights.enderkinesis.client.model.CatalogerModel
import org.shipwrights.enderkinesis.client.model.ConstriliarModel
import org.shipwrights.enderkinesis.client.model.EauhorranaModel
import org.shipwrights.enderkinesis.client.model.HeartOfTheWildModel
import org.shipwrights.enderkinesis.client.model.KekhekhModel
import org.shipwrights.enderkinesis.client.model.MoskuoModel
import org.shipwrights.enderkinesis.client.model.PrismaticGoatModel
import org.shipwrights.enderkinesis.client.model.ShilleagkDeerModel
import org.shipwrights.enderkinesis.client.model.ShulkonautModel
import org.shipwrights.enderkinesis.client.model.SselithMothModel
import org.shipwrights.enderkinesis.client.model.StatueBlackGoatModel
import org.shipwrights.enderkinesis.client.model.StatueCatalogerModel
import org.shipwrights.enderkinesis.client.model.StatueCountingModel
import org.shipwrights.enderkinesis.client.model.StatueOwlModel
import org.shipwrights.enderkinesis.client.model.StatueSteveModel
import org.shipwrights.enderkinesis.client.model.StatueTentacledBeastModel
import org.shipwrights.enderkinesis.client.model.StatueTentaclesModel
import org.shipwrights.enderkinesis.client.model.StatueYellowTowerModel
import org.shipwrights.enderkinesis.client.model.TightRobeArmorModel
import org.shipwrights.enderkinesis.client.model.WeheloStoneCreatureModel
import org.shipwrights.enderkinesis.client.model.WohlemModel

// Each loader registers these lists through its own event, as with EnderkinesisModClient.registerParticleProviders.

fun registerModelLayers(register: (ModelLayerLocation, () -> LayerDefinition) -> Unit) {
    register(CatalogerModel.LAYER_LOCATION, CatalogerModel::createBodyLayer)
    register(WohlemModel.LAYER_LOCATION, WohlemModel::createBodyLayer)
    register(ConstriliarModel.LAYER_LOCATION, ConstriliarModel::createBodyLayer)
    register(HeartOfTheWildModel.LAYER_LOCATION, HeartOfTheWildModel::createBodyLayer)
    register(PrismaticGoatModel.LAYER_LOCATION, PrismaticGoatModel::createBodyLayer)
    register(ShilleagkDeerModel.LAYER_LOCATION, ShilleagkDeerModel::createBodyLayer)
    register(KekhekhModel.LAYER_LOCATION, KekhekhModel::createBodyLayer)
    register(WeheloStoneCreatureModel.LAYER_LOCATION, WeheloStoneCreatureModel::createBodyLayer)
    register(MoskuoModel.LAYER_LOCATION, MoskuoModel::createBodyLayer)
    register(EauhorranaModel.LAYER_LOCATION, EauhorranaModel::createBodyLayer)
    register(TightRobeArmorModel.INNER_LAYER, TightRobeArmorModel::createInnerLayer)
    register(TightRobeArmorModel.OUTER_LAYER, TightRobeArmorModel::createOuterLayer)
    register(TightRobeArmorModel.WITCH_HAT_LAYER, TightRobeArmorModel::createWitchHatLayer)
    register(ShulkonautModel.LAYER_LOCATION, ShulkonautModel::createBodyLayer)
    register(StatueSteveModel.LAYER_LOCATION, StatueSteveModel::createBodyLayer)
    register(StatueCatalogerModel.LAYER_LOCATION, StatueCatalogerModel::createBodyLayer)
    register(StatueTentaclesModel.LAYER_LOCATION, StatueTentaclesModel::createBodyLayer)
    register(StatueTentacledBeastModel.LAYER_LOCATION, StatueTentacledBeastModel::createBodyLayer)
    register(StatueYellowTowerModel.LAYER_LOCATION, StatueYellowTowerModel::createBodyLayer)
    register(StatueBlackGoatModel.LAYER_LOCATION, StatueBlackGoatModel::createBodyLayer)
    register(StatueCountingModel.LAYER_LOCATION, StatueCountingModel::createBodyLayer)
    register(StatueOwlModel.LAYER_LOCATION, StatueOwlModel::createBodyLayer)
    register(SselithMothModel.LAYER_LOCATION, SselithMothModel::createBodyLayer)
}

/** Models that no item or block state uses, so the loader bakes them only on request. */
fun additionalModels(): List<ResourceLocation> = listOf(
    WyllandTomeBEWLR.ICON_MODEL_LOC,
    WyllandTomeBEWLR.SPINE_MODEL_LOC,
    WyllandTomeBEWLR.COVER1_MODEL_LOC,
    WyllandTomeBEWLR.COVER2_MODEL_LOC,
    WyllandTomeBEWLR.PAGE3_MODEL_LOC,
    WyllandTomeBEWLR.PAGE4_MODEL_LOC,
    EchoCannonRenderer.BARREL_MODEL_LOCATION,
    OarscribeRenderer.HAND_MODEL_LOCATION,
    ParallelographBEWLR.BASE_MODEL_LOC,
    ParallelographBEWLR.ARM_MODEL_LOC,
    VoidHookRenderer.CRYSTAL_MODEL_LOCATION,
)

/** Keeps the baked form of an [additionalModels] entry where its renderer reads it. */
fun captureAdditionalModel(id: ResourceLocation, model: BakedModel?) {
    when (id) {
        WyllandTomeBEWLR.ICON_MODEL_LOC -> WyllandTomeBEWLR.iconModel = model
        WyllandTomeBEWLR.SPINE_MODEL_LOC -> WyllandTomeBEWLR.spineModel = model
        WyllandTomeBEWLR.COVER1_MODEL_LOC -> WyllandTomeBEWLR.cover1Model = model
        WyllandTomeBEWLR.COVER2_MODEL_LOC -> WyllandTomeBEWLR.cover2Model = model
        WyllandTomeBEWLR.PAGE3_MODEL_LOC -> WyllandTomeBEWLR.page3Model = model
        WyllandTomeBEWLR.PAGE4_MODEL_LOC -> WyllandTomeBEWLR.page4Model = model
        EchoCannonRenderer.BARREL_MODEL_LOCATION -> EchoCannonRenderer.capturedBarrelModel = model
        OarscribeRenderer.HAND_MODEL_LOCATION -> OarscribeRenderer.handModel = model
        ParallelographBEWLR.BASE_MODEL_LOC -> ParallelographBEWLR.baseModel = model
        ParallelographBEWLR.ARM_MODEL_LOC -> ParallelographBEWLR.armModel = model
        VoidHookRenderer.CRYSTAL_MODEL_LOCATION -> VoidHookRenderer.crystalModel = model
    }
}

/** Our core shaders: id, vertex format, and where the loaded instance goes. */
fun registerCoreShaders(register: (ResourceLocation, VertexFormat, (ShaderInstance) -> Unit) -> Unit) {
    register(EnderkinesisMod.id("moth_cloth"), DefaultVertexFormat.BLOCK) { MothClothRenderType.shader = it }
    register(EnderkinesisMod.id("moth_camo"), DefaultVertexFormat.NEW_ENTITY) { MothCamoRenderType.shader = it }
    register(EnderkinesisMod.id("owl_sight"), DefaultVertexFormat.POSITION) { OwlSightPasses.shader = it }
    register(EnderkinesisMod.id("strike_sketch"), DefaultVertexFormat.POSITION_TEX) { StrikeSketch.shader = it }
    register(EnderkinesisMod.id("strike_normals"), DefaultVertexFormat.NEW_ENTITY) { StrikeSketchRenderTypes.normalsShader = it }
}

/** The dimension_type JSON of each dimension names these ids in its `effects` field. */
fun registerDimensionEffects(register: (ResourceLocation, DimensionSpecialEffects) -> Unit) {
    register(EnderkinesisMod.id("sselith_repertory"), SselithDimensionEffects())
    register(EnderkinesisMod.id("wohlonnogondonia"), WohlonnogondoniaDimensionEffects())
    register(EnderkinesisMod.id("sureibjin"), SureibjinDimensionEffects())
}
