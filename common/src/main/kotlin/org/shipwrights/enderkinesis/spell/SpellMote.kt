package org.shipwrights.enderkinesis.spell

/** Each tick, vertical speed loses 0.04 × [gravity], then all speed is multiplied by [friction], as in vanilla. */
enum class SpellMote(
    val id: String,
    val rgb: Int,
    val size: Float,
    val life: Int,
    val gravity: Float = 0f,
    val friction: Float = 0.96f,
    val grow: Float = 1f,
    val fadeTo: Float = 0.2f,
    val alpha: Float = 1f,
    val glows: Boolean = false,
    val animated: Boolean = true,
    val motion: Motion = Motion.PLAIN,
    val rgbAlt: Int = rgb,
    val altEvery: Int = 0,
) {
    FLAME("spell_flame", 0xFFFFFF, 0.2f, 12, glows = true, animated = false),
    EMBER_FLAME("spell_ember_flame", 0xFFFFFF, 0.13f, 12, glows = true, animated = false),
    AREA_FLAME("spell_area_flame", 0xFFFFFF, 0.3f, 18, gravity = -0.075f, friction = 0.825f, fadeTo = 0f, glows = true, animated = false),
    HEAT_DUST("spell_pyre_dust", Element.PYRE.color, 0.15f, 14, fadeTo = 0.6f),
    SPRAY_SMOKE("spell_spray_smoke", 0x4A4648, 0.22f, 18, grow = 1.03f, alpha = 0.85f),
    HEAT_SMOKE("spell_pyre_smoke", 0x3D3A40, 0.7f, 20, friction = 0.9f, grow = 1.02f, fadeTo = 0f, alpha = 0.8f),
    HEAT_EMBER("spell_pyre_ember", Element.PYRE.color, 0.14f, 28, gravity = 0.6f, friction = 1f, fadeTo = 0.4f, rgbAlt = 0xFFC070, altEvery = 2),
    CLOAK_FLAME("spell_cloak_flame", 0xFFFFFF, 0.16f, 16, gravity = -0.05f, friction = 0.9f, fadeTo = 0f, glows = true, animated = false, motion = Motion.ORBIT),

    FROST("spell_frost", 0xF6FCFF, 0.14f, 18, gravity = 0.15f),
    FROST_ASH("spell_frost_ash", 0xC9D2D8, 0.08f, 20, animated = false),
    STEAM("spell_steam", 0xE8EEF2, 0.35f, 20, friction = 0.9f, grow = 1.04f, fadeTo = 0f, alpha = 0.6f),
    ASH("spell_ash", 0x231C17, 0.07f, 18, gravity = 0.6f, fadeTo = 0f, animated = false, rgbAlt = 0x4A4440, altEvery = 2),
    SNOW_PUFF("spell_snow_puff", 0xF4FBFF, 0.16f, 28, friction = 1f, fadeTo = 0.3f),
    CLOAK_SPARK("spell_cloak_spark", 0xF7C4DA, 0.12f, 10, friction = 0.9f, fadeTo = 0f, glows = true, motion = Motion.ORBIT),
    CLOAK_BLOOD("spell_cloak_blood", 0x7A0C14, 0.18f, 18, friction = 0.9f, fadeTo = 0.2f, alpha = 0.9f, rgbAlt = 0xA8141E, altEvery = 3, motion = Motion.ORBIT),
    CLOAK_SPORE("spell_cloak_spore", Element.CORRUPTION.color, 0.14f, 22, friction = 0.9f, fadeTo = 0.1f, rgbAlt = 0x5E8A2E, altEvery = 2, motion = Motion.ORBIT),
    CLOAK_VOID("spell_cloak_void", 0x1C0C2C, 0.18f, 20, friction = 0.9f, fadeTo = 0.2f, alpha = 0.9f, motion = Motion.ORBIT),
    CLOAK_VOID_GLINT("spell_cloak_void_glint", Element.VOID.color, 0.08f, 16, friction = 0.9f, fadeTo = 0f, glows = true, animated = false, motion = Motion.ORBIT),
    CLOAK_SNOW("spell_cloak_snow", 0xF6FCFF, 0.14f, 22, friction = 0.9f, fadeTo = 0.1f, motion = Motion.ORBIT),
    CLOAK_GLINT("spell_cloak_glint", 0xD4ECFA, 0.08f, 20, friction = 0.9f, fadeTo = 0f, glows = true, animated = false, motion = Motion.ORBIT),

    GROWTH_GLINT("spell_genesis_glint", 0x8CE06A, 0.14f, 14, animated = false),

    PURITY_GLITTER("spell_quintessence_glitter", 0xFFFFFF, 0.14f, 16, glows = true),
    /** Stays white here because `EffectFlows` tints it with the colour of the effect. */
    EFFECT_SWIRL("spell_effect_swirl", 0xFFFFFF, 0.22f, 10),
    CRYSTAL_SHARD("crystal_shard", 0xFFFFFF, 0.18f, 20, gravity = 0.6f, glows = true, animated = false),

    CHAOS_GLYPH("spell_chaos_glyph", 0xFFD0E4, 0.15f, 14, animated = false, motion = Motion.JITTER, rgbAlt = Element.CHAOS.color, altEvery = 2),
    AREA_GLYPH("spell_area_glyph", 0xFFD0E4, 0.24f, 36, gravity = 0.015f, friction = 0.825f, fadeTo = 0f, animated = false,
        motion = Motion.JITTER, rgbAlt = Element.CHAOS.color, altEvery = 2),
    FIZZLE_GLYPH("spell_fizzle_glyph", 0x8A8A8A, 0.12f, 30, gravity = 0.5f, fadeTo = 0f, animated = false),
    CHAOS_CRIT("spell_chaos_crit", 0xFFFFFF, 0.12f, 8, glows = true, animated = false),

    BLOOD_MIST("blood_mist", 0x6E0A10, 0.42f, 34, friction = 0.9f, grow = 1.025f, fadeTo = 0.35f, alpha = 0.9f),
    BLOOD_DRIP("blood_drip", Element.BLOOD.color, 0.16f, 60, gravity = 0.9f, fadeTo = 0.6f, animated = false),
    BLOOD_DROP("spell_blood_drop", 0x8A0F18, 0.17f, 90, gravity = 1f, friction = 1f, fadeTo = 1f, animated = false,
        motion = Motion.STAIN, rgbAlt = Element.BLOOD.color, altEvery = 3),
    BLOOD_HAZE("spell_blood_haze", 0x6E0A10, 0.45f, 60, friction = 1f, grow = 1.0085f, fadeTo = 0f, alpha = 0.8f),

    POISON_MIST("poison_mist", 0x5E8A2E, 0.46f, 20, friction = 0.9f, grow = 1.04f, alpha = 0.85f),
    ROT_SPORE("rot_spore", Element.CORRUPTION.color, 0.13f, 40, gravity = -0.02f),
    POISON_SWIRL("spell_corruption_swirl", Element.CORRUPTION.color, 0.2f, 28, friction = 0.98f, fadeTo = 0.5f, rgbAlt = 0x5E8A2E, altEvery = 2),

    SUNFIRE_SPARK("spell_sunfire_spark", Element.SUNFIRE.color, 0.15f, 14, glows = true, rgbAlt = 0xFFF0B0, altEvery = 3),
    SUNFIRE_EMBER("spell_sunfire_ember", Element.SUNFIRE.color, 0.18f, 28, friction = 0.9f, fadeTo = 0f, glows = true,
        motion = Motion.FLOAT, rgbAlt = 0xFFF0B0, altEvery = 3),
    SUNFIRE_DUST("spell_sunfire_dust", Element.SUNFIRE.color, 0.12f, 40, friction = 1f, glows = true, rgbAlt = 0xFFF0B0, altEvery = 2),

    GRAVITY_DUST("spell_gravity_dust", 0x2A1840, 0.14f, 16, gravity = 1.2f),
    LAND_DUST("spell_land_dust", 0x7A7268, 0.3f, 16, friction = 0.9f, fadeTo = 0f),
    GRAVITY_MOTE("spell_gravity_mote", 0x7A3FB0, 0.14f, 17, friction = 1f, fadeTo = 0.5f, rgbAlt = 0x4A2A70, altEvery = 2),

    VOID_MOTE("void_mote", 0x8A4AC0, 0.12f, 16, glows = true),
    VOID_INWARD("spell_void_inward", 0xB06AE0, 0.14f, 18, friction = 1f, fadeTo = 0.5f, alpha = 0.8f, rgbAlt = 0x5A2A80, altEvery = 2),
    YGANN_EYE("spell_ygann_eye", 0xFFFFFF, 0.18f, 18, fadeTo = 1f),
    MASS_RISE("spell_mass_rise", 0xFFFFFF, 0.09f, 32, gravity = -0.03f, fadeTo = 0f, animated = false),
    MASS_DUST("spell_mass_dust", 0xFFFFFF, 0.1f, 20, gravity = 0.3f, animated = false);

    companion object {
        /** Radians per tick. Keep equal to the cloak turn rate. */
        const val ORBIT_SPIN = 0.17
    }

    enum class Motion {
        PLAIN,
        JITTER,
        FLOAT,
        STAIN,
        /** Spawn it at distance `r` from the center, with speed `ORBIT_SPIN × r` at 90° to the center. */
        ORBIT,
    }
}
