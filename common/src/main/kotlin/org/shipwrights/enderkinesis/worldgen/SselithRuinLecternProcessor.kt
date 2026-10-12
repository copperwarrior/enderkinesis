package org.shipwrights.enderkinesis.worldgen

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.network.chat.Component
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.LecternBlock
import org.shipwrights.enderkinesis.registry.EKBlocks
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.shipwrights.enderkinesis.item.SselithBookItem
import org.shipwrights.enderkinesis.item.TomeLore
import org.shipwrights.enderkinesis.registry.EKItems
import org.shipwrights.enderkinesis.registry.EKProcessors

/**
 * Seeds every lectern in a Sselith Ruin with a [SselithBookItem] copy of the
 * Sselith-translated "Passage Into Sselith" book. Author is picked
 * deterministically per-block from a Sselith research-circle cast.
 */
object SselithRuinLecternProcessor : StructureProcessor() {

    val CODEC: Codec<SselithRuinLecternProcessor> = Codec.unit(SselithRuinLecternProcessor)

    private const val BOOK_TITLE = "On the Communion of Sselith"
    private const val BOOK_SOURCE = "passage_into_sselith"
    private const val LECTERN_BE_ID = "minecraft:lectern"

    private val AUTHORS = listOf(
        "The Seeker",
        "The Scholar",
        "The Researcher",
        "The Lorekeeper",
    )

    override fun processBlock(
        level: LevelReader,
        offset: BlockPos,
        pos: BlockPos,
        rawInfo: StructureTemplate.StructureBlockInfo,
        info: StructureTemplate.StructureBlockInfo,
        settings: StructurePlaceSettings,
    ): StructureTemplate.StructureBlockInfo {
        // The ruin templates were authored with vanilla lecterns
        // (`minecraft:lectern`). Match the vanilla block in the source NBT,
        // then rewrite the output state to the Sselith lectern. Vanilla
        // [LecternBlock.FACING] / [LecternBlock.HAS_BOOK] / [LecternBlock.POWERED]
        // are shared with [org.shipwrights.enderkinesis.block.SselithLecternBlock]
        // (it extends LecternBlock), so the source state's facing copies straight
        // across via `setValue` and only the block changes.
        if (!info.state.`is`(net.minecraft.world.level.block.Blocks.LECTERN)) return info

        val random = settings.getRandom(info.pos)
        val author = AUTHORS[random.nextInt(AUTHORS.size)]

        // Vanilla `LecternScreen` displays a page cleanly only when it is a bare-string JSON
        // primitive, so each page is flattened to one literal. A book with 0 pages shows as
        // "* Invalid book tag *", so a missing resource gives one placeholder page.
        val parsed = TomeLore.parsePaginatedMarkdown("/assets/enderkinesis/texts/sselith/books/$BOOK_SOURCE.md")
        val pageTexts = parsed.takeIf { it.isNotEmpty() }?.map { it.string }
            ?: listOf("(pages failed to load)")
        val pagesList = ListTag()
        for (text in pageTexts) {
            val flat = Component.literal(text)
            pagesList.add(StringTag.valueOf(Component.Serializer.toJson(flat)))
        }

        val bookTag = CompoundTag().apply {
            putString(SselithBookItem.TITLE_KEY, BOOK_TITLE)
            putString(SselithBookItem.AUTHOR, author)
            put(SselithBookItem.PAGES, pagesList)
        }

        // ItemStack save form — `id` + `Count` + `tag`. Built explicitly so
        // every field's NBT type is known, no round-trip through ItemStack
        // serialization where a wrong-typed cached value could sneak in.
        val bookStack = CompoundTag().apply {
            putString("id", EKItems.SSELITH_BOOK.id.toString())
            putByte("Count", 1)
            put("tag", bookTag)
        }

        val beNbt = CompoundTag().apply {
            put("Book", bookStack)
            putInt("Page", 0)
            putString("id", LECTERN_BE_ID)
        }
        // Rewrite the block from vanilla lectern → Sselith lectern, carrying
        // the source FACING across. HAS_BOOK is on because we're handing the
        // BE a book; vanilla LecternBlockEntity reads the HAS_BOOK
        // state on load and would otherwise discard the book at the next BE
        // tick.
        val sourceFacing = info.state.getValue(LecternBlock.FACING)
        val withBook = EKBlocks.SSELITH_LECTERN.get().defaultBlockState()
            .setValue(LecternBlock.FACING, sourceFacing)
            .setValue(LecternBlock.HAS_BOOK, true)
        return StructureTemplate.StructureBlockInfo(info.pos, withBook, beNbt)
    }

    override fun getType(): StructureProcessorType<*> = EKProcessors.SSELITH_RUIN_LECTERN.get()
}
