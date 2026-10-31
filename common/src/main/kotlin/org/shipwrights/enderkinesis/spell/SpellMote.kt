package org.shipwrights.enderkinesis.spell

/**
 * The Mystic Scroll particles. Each is a vanilla particle sprite (or set, named in its JSON
 * under `assets/enderkinesis/particles`), with its own colour, size, life and motion, drawn by
 * `SpellParticle`. Each is registered as its own particle type, named [id].
 *
 * Sizes are across, in blocks. Gravity and friction work as vanilla's: each tick the vertical
 * speed loses 0.04 × [gravity], then all of it is multiplied by [friction]. Over its life a
 * particle's opacity falls from [alpha] to [alpha] × [fadeTo], and its size is multiplied by
 * [grow] each tick. A particle takes [rgb], or [rgbAlt] one time in [altEvery].
 */
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
    /** Full bright, as vanilla's glowing particles (end rods, sparks) are. */
    val glows: Boolean = false,
    /** Runs through its sprite set over its life; otherwise picks one sprite at random. */
    val animated: Boolean = true,
    val motion: Motion = Motion.PLAIN,
    val rgbAlt: Int = rgb,
    val altEvery: Int = 0,
) {
    // ---- Fire ----
    /** Flames, as vanilla's: a spray's fire, a burning target. */
    FLAME("spell_flame", 0xFFFFFF, 0.2f, 12, glows = true, animated = false),
    /** Smaller flames: trails, beams, Sunfire's spray. */
    EMBER_FLAME("spell_ember_flame", 0xFFFFFF, 0.13f, 12, glows = true, animated = false),
    /** Flames thrown out with an Area wave, slowing and rising a little. */
    AREA_FLAME("spell_area_flame", 0xFFFFFF, 0.3f, 18, gravity = -0.075f, friction = 0.825f, fadeTo = 0f, glows = true, animated = false),
    /** A Heat spray's square dust, fire orange. */
    HEAT_DUST("spell_heat_dust", Element.HEAT.color, 0.15f, 14, fadeTo = 0.6f),
    /** Smoke thrown with a Heat spray. */
    SPRAY_SMOKE("spell_spray_smoke", 0x4A4648, 0.22f, 18, grow = 1.03f, alpha = 0.85f),
    /** Smoke rolling up from a Heat impact. */
    HEAT_SMOKE("spell_heat_smoke", 0x3D3A40, 0.7f, 20, friction = 0.9f, grow = 1.02f, fadeTo = 0f, alpha = 0.8f),
    /** Embers thrown out of a Heat impact, falling. */
    HEAT_EMBER("spell_heat_ember", Element.HEAT.color, 0.14f, 28, gravity = 0.6f, friction = 1f, fadeTo = 0.4f, rgbAlt = 0xFFC070, altEvery = 2),
    /** Flames whirling round a Flame Cloak's wearer, rising a little. */
    CLOAK_FLAME("spell_cloak_flame", 0xFFFFFF, 0.16f, 16, gravity = -0.05f, friction = 0.9f, fadeTo = 0f, glows = true, animated = false, motion = Motion.ORBIT),

    // ---- Cold ----
    /** Snow blown by a Cold spray, settling. */
    FROST("spell_frost", 0xF6FCFF, 0.14f, 18, gravity = 0.15f),
    /** Fine ash in a Cold spray. */
    FROST_ASH("spell_frost_ash", 0xC9D2D8, 0.08f, 20, animated = false),
    /** Steam rising off melting ice and snow, and cooling lava. */
    STEAM("spell_steam", 0xE8EEF2, 0.35f, 20, friction = 0.9f, grow = 1.04f, fadeTo = 0f, alpha = 0.6f),
    /** Ash falling off burnt leaves. */
    ASH("spell_ash", 0x231C17, 0.07f, 18, gravity = 0.6f, fadeTo = 0f, animated = false, rgbAlt = 0x4A4440, altEvery = 2),
    /** Snow puffed out by a Cold impact, drifting down. */
    SNOW_PUFF("spell_snow_puff", 0xF4FBFF, 0.16f, 28, friction = 1f, fadeTo = 0.3f),
    /** Sparks whirling round a Lightning Cloak's wearer, Chaos pink going white. */
    CLOAK_SPARK("spell_cloak_spark", 0xF7C4DA, 0.12f, 10, friction = 0.9f, fadeTo = 0f, glows = true, motion = Motion.ORBIT),
    /** Blood mist whirling close round an Absorption Cloak's wearer. */
    CLOAK_BLOOD("spell_cloak_blood", 0x7A0C14, 0.18f, 18, friction = 0.9f, fadeTo = 0.2f, alpha = 0.9f, rgbAlt = 0xA8141E, altEvery = 3, motion = Motion.ORBIT),
    /** Spores whirling round a Poison Cloak's wearer. */
    CLOAK_SPORE("spell_cloak_spore", Element.POISON.color, 0.14f, 22, friction = 0.9f, fadeTo = 0.1f, rgbAlt = 0x5E8A2E, altEvery = 2, motion = Motion.ORBIT),
    /** Dark motes whirling round a Void Cloak's wearer. */
    CLOAK_VOID("spell_cloak_void", 0x1C0C2C, 0.18f, 20, friction = 0.9f, fadeTo = 0.2f, alpha = 0.9f, motion = Motion.ORBIT),
    /** Violet glints in a Void Cloak. */
    CLOAK_VOID_GLINT("spell_cloak_void_glint", Element.VOID.color, 0.08f, 16, friction = 0.9f, fadeTo = 0f, glows = true, animated = false, motion = Motion.ORBIT),
    /** Snow whirling round a Frost Cloak's wearer. */
    CLOAK_SNOW("spell_cloak_snow", 0xF6FCFF, 0.14f, 22, friction = 0.9f, fadeTo = 0.1f, motion = Motion.ORBIT),
    /** Frost glinting as it whirls round a Frost Cloak's wearer. */
    CLOAK_GLINT("spell_cloak_glint", 0xD4ECFA, 0.08f, 20, friction = 0.9f, fadeTo = 0f, glows = true, animated = false, motion = Motion.ORBIT),

    // ---- Growth ----
    /** Green sparkles. */
    GROWTH_GLINT("spell_growth_glint", 0x8CE06A, 0.14f, 14, animated = false),

    // ---- Purity ----
    /** White end-rod glitter. */
    PURITY_GLITTER("spell_purity_glitter", 0xFFFFFF, 0.14f, 16, glows = true),
    /** A status effect's own swirl, as vanilla gives off round an affected mob, white until
     *  it's tinted the effect's colour (see `EffectFlows`). Unlike vanilla's, it goes where
     *  it's sent: down a Cleansed body, or round and across in a Shuffle. */
    EFFECT_SWIRL("spell_effect_swirl", 0xFFFFFF, 0.22f, 10),
    /** Crystallized's shatter: bright shards that fall. */
    CRYSTAL_SHARD("crystal_shard", 0xFFFFFF, 0.18f, 20, gravity = 0.6f, glows = true, animated = false),

    // ---- Chaos ----
    /** Enchanting glyphs, jittering about. */
    CHAOS_GLYPH("spell_chaos_glyph", 0xFFD0E4, 0.15f, 14, animated = false, motion = Motion.JITTER, rgbAlt = Element.CHAOS.color, altEvery = 2),
    /** Enchanting glyphs thrown out with a Chaos wave, each tumbling off on its own path. */
    AREA_GLYPH("spell_area_glyph", 0xFFD0E4, 0.24f, 36, gravity = 0.015f, friction = 0.825f, fadeTo = 0f, animated = false,
        motion = Motion.JITTER, rgbAlt = Element.CHAOS.color, altEvery = 2),
    /** Glyphs falling grey out of a fizzled imbue. */
    FIZZLE_GLYPH("spell_fizzle_glyph", 0x8A8A8A, 0.12f, 30, gravity = 0.5f, fadeTo = 0f, animated = false),
    /** White crits flying off. */
    CHAOS_CRIT("spell_chaos_crit", 0xFFFFFF, 0.12f, 8, glows = true, animated = false),

    // ---- Blood ----
    /** A Blood spray's mist: dark red puffs that keep the speed they're flung with. */
    BLOOD_MIST("blood_mist", 0x6E0A10, 0.42f, 34, friction = 0.9f, grow = 1.025f, fadeTo = 0.35f, alpha = 0.9f),
    /** Bleeding and the drain: drops that fall. */
    BLOOD_DRIP("blood_drip", Element.BLOOD.color, 0.16f, 60, gravity = 0.9f, fadeTo = 0.6f, animated = false),
    /** Drops thrown out of a Blood impact, which stain where they land and dry away. */
    BLOOD_DROP("spell_blood_drop", 0x8A0F18, 0.17f, 90, gravity = 1f, friction = 1f, fadeTo = 1f, animated = false,
        motion = Motion.STAIN, rgbAlt = Element.BLOOD.color, altEvery = 3),
    /** The thin mist a Blood impact leaves hanging, drifting down. */
    BLOOD_HAZE("spell_blood_haze", 0x6E0A10, 0.45f, 60, friction = 1f, grow = 1.0085f, fadeTo = 0f, alpha = 0.8f),

    // ---- Poison ----
    /** A Poison spray's mist. */
    POISON_MIST("poison_mist", 0x5E8A2E, 0.46f, 20, friction = 0.9f, grow = 1.04f, alpha = 0.85f),
    /** Rot: drifting green spores. */
    ROT_SPORE("rot_spore", Element.POISON.color, 0.13f, 40, gravity = -0.02f),
    /** Potion swirls rising out of a Poison cloud. */
    POISON_SWIRL("spell_poison_swirl", Element.POISON.color, 0.2f, 28, friction = 0.98f, fadeTo = 0.5f, rgbAlt = 0x5E8A2E, altEvery = 2),

    // ---- Sunfire ----
    /** Golden firework sparks. */
    SUNFIRE_SPARK("spell_sunfire_spark", Element.SUNFIRE.color, 0.15f, 14, glows = true, rgbAlt = 0xFFF0B0, altEvery = 3),
    /** Sparks rising out of a Sunfire impact and floating up like embers, swaying. */
    SUNFIRE_EMBER("spell_sunfire_ember", Element.SUNFIRE.color, 0.18f, 28, friction = 0.9f, fadeTo = 0f, glows = true,
        motion = Motion.FLOAT, rgbAlt = 0xFFF0B0, altEvery = 3),
    /** Glitter hanging in a Sunfire beam's light. */
    SUNFIRE_DUST("spell_sunfire_dust", Element.SUNFIRE.color, 0.12f, 40, friction = 1f, glows = true, rgbAlt = 0xFFF0B0, altEvery = 2),

    // ---- Gravity ----
    /** Dark dust that drops. */
    GRAVITY_DUST("spell_gravity_dust", 0x2A1840, 0.14f, 16, gravity = 1.2f),
    /** Dust puffed out in a ring where a block Gravity dropped lands. */
    LAND_DUST("spell_land_dust", 0x7A7268, 0.3f, 16, friction = 0.9f, fadeTo = 0f),
    /** Portal motes dragged in after a Gravity impact's crushing cubes. */
    GRAVITY_MOTE("spell_gravity_mote", 0x7A3FB0, 0.14f, 17, friction = 1f, fadeTo = 0.5f, rgbAlt = 0x4A2A70, altEvery = 2),

    // ---- Void ----
    /** Violet motes. */
    VOID_MOTE("void_mote", 0x8A4AC0, 0.12f, 16, glows = true),
    /** Motes drawn into a Void rift. */
    VOID_INWARD("spell_void_inward", 0xB06AE0, 0.14f, 18, friction = 1f, fadeTo = 0.5f, alpha = 0.8f, rgbAlt = 0x5A2A80, altEvery = 2),
    /** A small Ygann eye opening, looking and closing as it drifts. */
    YGANN_EYE("spell_ygann_eye", 0xFFFFFF, 0.18f, 18, fadeTo = 1f),
    /** Chips of the writhing mass drifting up off a body the Void takes, fading. */
    MASS_RISE("spell_mass_rise", 0xFFFFFF, 0.09f, 32, gravity = -0.03f, fadeTo = 0f, animated = false),
    /** Chips of the writhing mass, falling. */
    MASS_DUST("spell_mass_dust", 0xFFFFFF, 0.1f, 20, gravity = 0.3f, animated = false);

    /** How a particle moves beyond vanilla's gravity and friction. */
    companion object {
        /** How fast [Motion.ORBIT] particles circle, in radians a tick (as the cloaks turn). */
        const val ORBIT_SPIN = 0.17
    }

    enum class Motion {
        PLAIN,
        /** Wanders: a little random push every tick. */
        JITTER,
        /** Sways side to side as it drifts. */
        FLOAT,
        /** Lies flat where it lands, as a stain, and dries away over the rest of its life. */
        STAIN,
        /**
         * Circles a point [ORBIT_SPIN] radians a tick, turning the way its spawn speed points:
         * spawned moving at `ORBIT_SPIN × r` square to the way to the point, `r` from it.
         */
        ORBIT,
    }
}
