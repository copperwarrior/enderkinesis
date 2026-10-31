package org.shipwrights.enderkinesis.spell

import com.mojang.brigadier.StringReader
import com.mojang.serialization.Codec
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import org.shipwrights.enderkinesis.registry.EKParticles

class SpellGlyphOptions(val rgb: Int) : ParticleOptions {
    override fun getType(): ParticleType<SpellGlyphOptions> = EKParticles.spellGlyph()

    override fun writeToNetwork(buf: FriendlyByteBuf) {
        buf.writeInt(rgb)
    }

    override fun writeToString(): String = "${BuiltInRegistries.PARTICLE_TYPE.getKey(type)} $rgb"

    class Type : ParticleType<SpellGlyphOptions>(false, DESERIALIZER) {
        override fun codec(): Codec<SpellGlyphOptions> = CODEC
    }

    companion object {
        val CODEC: Codec<SpellGlyphOptions> = Codec.INT.xmap(::SpellGlyphOptions, SpellGlyphOptions::rgb)

        @Suppress("DEPRECATION") // ParticleType in 1.20.1 still requires the deprecated Deserializer.
        val DESERIALIZER = object : ParticleOptions.Deserializer<SpellGlyphOptions> {
            override fun fromCommand(type: ParticleType<SpellGlyphOptions>, reader: StringReader): SpellGlyphOptions {
                reader.expect(' ')
                return SpellGlyphOptions(reader.readInt())
            }

            override fun fromNetwork(type: ParticleType<SpellGlyphOptions>, buf: FriendlyByteBuf): SpellGlyphOptions = SpellGlyphOptions(buf.readInt())
        }
    }
}
