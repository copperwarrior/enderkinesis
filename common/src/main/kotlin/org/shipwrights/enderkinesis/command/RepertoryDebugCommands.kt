package org.shipwrights.enderkinesis.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.architectury.event.events.common.CommandRegistrationEvent
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.sqrt
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.shipwrights.enderkinesis.dimension.SselithParallel
import org.shipwrights.enderkinesis.dimension.SselithRepertory
import org.shipwrights.enderkinesis.registry.EKBlocks
import org.shipwrights.enderkinesis.sselith.BookshelfPage

/**
 * Debug commands for the Repertory's bookshelf library. Op-only (`hasPermission(2)`).
 *
 *  - `/repertory parallel [player]` — show the parallel.
 *  - `/repertory parallel set <value> [player]` — set the parallel; the player must be in Sselith.
 *  - `/repertory search <phrase>` / `searchwithin <radius> <phrase>` — find the loaded Sselith Bookshelf near
 *    the player, and the lowest parallel, at which the page starts with the phrase. A full page of
 *    [BookshelfPage.WORDS] words returns its one exact location instead.
 */
object RepertoryDebugCommands {

    private const val DEFAULT_RADIUS = 64
    private const val MAX_RADIUS = 256
    private const val MAX_SHELVES = 200_000
    private val WHITESPACE = Regex("\\s+")

    fun init() {
        CommandRegistrationEvent.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                Commands.literal("repertory")
                    .requires { it.hasPermission(2) }
                    .then(
                        Commands.literal("parallel")
                            .executes { showParallel(it, it.source.playerOrException) }
                            .then(
                                Commands.argument("player", EntityArgument.player())
                                    .executes { showParallel(it, EntityArgument.getPlayer(it, "player")) },
                            )
                            .then(
                                Commands.literal("set").then(
                                    Commands.argument("value", StringArgumentType.word())
                                        .executes { setParallel(it, it.source.playerOrException) }
                                        .then(
                                            Commands.argument("player", EntityArgument.player())
                                                .executes { setParallel(it, EntityArgument.getPlayer(it, "player")) },
                                        ),
                                ),
                            ),
                    )
                    .then(
                        Commands.literal("search").then(
                            Commands.argument("phrase", StringArgumentType.greedyString())
                                .executes { search(it, DEFAULT_RADIUS) },
                        ),
                    )
                    .then(
                        Commands.literal("searchwithin").then(
                            Commands.argument("radius", IntegerArgumentType.integer(1, MAX_RADIUS)).then(
                                Commands.argument("phrase", StringArgumentType.greedyString())
                                    .executes { search(it, IntegerArgumentType.getInteger(it, "radius")) },
                            ),
                        ),
                    ),
            )
        }
    }

    private fun showParallel(ctx: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val parallel = SselithParallel.get(player)
        ctx.source.sendSuccess({ Component.literal("${player.name.string} reads parallel $parallel.") }, false)
        return 1
    }

    private fun setParallel(ctx: CommandContext<CommandSourceStack>, player: ServerPlayer): Int {
        val raw = StringArgumentType.getString(ctx, "value")
        val value = raw.toBigIntegerOrNull()
        if (value == null || value.signum() < 0) {
            ctx.source.sendFailure(Component.literal("Parallel must be a whole number of 0 or more: $raw"))
            return 0
        }
        if (value > BookshelfPage.lastParallel) {
            ctx.source.sendFailure(Component.literal("Past the last parallel, ${BookshelfPage.lastParallel}; the pages there are blank."))
            return 0
        }
        // Leaving Sselith resets the parallel, so a value set outside it would never be read.
        if (player.level().dimension() != SselithRepertory.LEVEL_KEY) {
            ctx.source.sendFailure(Component.literal("${player.name.string} is not in Sselith's Repertory."))
            return 0
        }
        SselithParallel.set(player, value)
        ctx.source.sendSuccess({ Component.literal("${player.name.string} now reads parallel $value.") }, true)
        return 1
    }

    private fun search(ctx: CommandContext<CommandSourceStack>, radius: Int): Int {
        val source = ctx.source
        val player = source.playerOrException
        val phrase = StringArgumentType.getString(ctx, "phrase").lowercase()
            .split(WHITESPACE).filter { it.isNotEmpty() }
        if (phrase.size > BookshelfPage.WORDS) {
            source.sendFailure(Component.literal("A page holds only ${BookshelfPage.WORDS} words."))
            return 0
        }
        val unknown = BookshelfPage.unknownWords(phrase)
        if (unknown.isNotEmpty()) {
            source.sendFailure(Component.literal("Too distant: ${unknown.joinToString(" ")}"))
            return 0
        }
        if (phrase.size == BookshelfPage.WORDS) return exactLocation(ctx, phrase)
        val search = BookshelfPage.search(phrase)
        if (search == null) {
            source.sendFailure(Component.literal("That phrase is too distant. Use words from one language."))
            return 0
        }

        // Falls only raise the parallel, so the nearest reachable match is at or above the current one.
        val current = SselithParallel.get(player)
        val scan = scan(player.serverLevel(), player.blockPosition(), radius, search, current)
        val best = scan.best
        if (best == null) {
            val reason = if (scan.shelves == 0) "No Sselith Bookshelf in loaded chunks within $radius blocks." else "That phrase is too distant."
            source.sendFailure(Component.literal(reason))
            return 0
        }
        source.sendSuccess({ result(best, scan, current) }, false)
        return 1
    }

    /** A full page has exactly one location, wherever the player stands. */
    private fun exactLocation(ctx: CommandContext<CommandSourceStack>, words: List<String>): Int {
        val pages = BookshelfPage.exactPages(words)
        if (pages.isEmpty()) {
            ctx.source.sendFailure(Component.literal("That page is too distant. Use words from one language that fit one page."))
            return 0
        }
        for ((page, location) in pages) {
            val pos = BlockPos(location.x, location.y, location.z)
            ctx.source.sendSuccess({
                Component.literal("The page is at ${pos.toShortString()} on parallel ${location.parallel} ")
                    .append(readLink(location.parallel, page))
            }, false)
        }
        return 1
    }

    private fun readLink(parallel: BigInteger, page: String): Component = Component.literal("[read]").withStyle {
        it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, "/repertory parallel set $parallel"))
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(page)))
    }

    private class Hit(val pos: BlockPos, val parallel: BigInteger, val distSq: Double)

    private class Scan(val best: Hit?, val shelves: Int, val truncated: Boolean)

    /** The shelf with the lowest parallel of [from] or more for [search] in loaded chunks around [center]; the nearest wins ties. */
    private fun scan(
        level: ServerLevel,
        center: BlockPos,
        radius: Int,
        search: BookshelfPage.PhraseSearch,
        from: BigInteger,
    ): Scan {
        val shelf = EKBlocks.SSELITH_BOOKSHELF.get()
        val cursor = BlockPos.MutableBlockPos()
        var best: Hit? = null
        var shelves = 0
        val minCx = SectionPos.blockToSectionCoord(center.x - radius)
        val maxCx = SectionPos.blockToSectionCoord(center.x + radius)
        val minCz = SectionPos.blockToSectionCoord(center.z - radius)
        val maxCz = SectionPos.blockToSectionCoord(center.z + radius)
        for (cx in minCx..maxCx) for (cz in minCz..maxCz) {
            val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
            for ((index, section) in chunk.sections.withIndex()) {
                if (section.hasOnlyAir() || !section.states.maybeHas { it.`is`(shelf) }) continue
                val baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index))
                for (ly in 0 until 16) for (lz in 0 until 16) for (lx in 0 until 16) {
                    if (!section.getBlockState(lx, ly, lz).`is`(shelf)) continue
                    cursor.set(SectionPos.sectionToBlockCoord(cx) + lx, baseY + ly, SectionPos.sectionToBlockCoord(cz) + lz)
                    if (!inCube(cursor, center, radius)) continue
                    if (++shelves > MAX_SHELVES) return Scan(best, MAX_SHELVES, truncated = true)
                    val parallel = search.lowestParallel(cursor.x, cursor.y, cursor.z, from) ?: continue
                    val distSq = cursor.distSqr(center)
                    val current = best
                    if (current == null || parallel < current.parallel ||
                        (parallel == current.parallel && distSq < current.distSq)
                    ) {
                        best = Hit(cursor.immutable(), parallel, distSq)
                    }
                }
            }
        }
        return Scan(best, shelves, truncated = false)
    }

    private fun inCube(pos: BlockPos, center: BlockPos, radius: Int): Boolean =
        abs(pos.x - center.x) <= radius && abs(pos.y - center.y) <= radius && abs(pos.z - center.z) <= radius

    private fun result(hit: Hit, scan: Scan, current: BigInteger): Component {
        val read = readLink(hit.parallel, BookshelfPage.text(hit.pos.x, hit.pos.y, hit.pos.z, hit.parallel))
        val searched = if (scan.truncated) "the first ${scan.shelves}" else "${scan.shelves}"
        val falls = hit.parallel - current
        val where = if (falls.signum() == 0) {
            "your current parallel ${hit.parallel}"
        } else {
            "parallel ${hit.parallel} ($falls falls down)"
        }
        return Component.literal(
            "Shelf ${hit.pos.toShortString()} (${sqrt(hit.distSq).toInt()} blocks away) starts with the phrase on $where ",
        ).append(read).append(Component.literal(" — searched $searched shelves."))
    }
}
