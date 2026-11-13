package org.shipwrights.enderkinesis.ritual

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.matchers.shouldBe
import java.io.File
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.Tag
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CandleBlock
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.math.hypot
import kotlin.math.roundToInt

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CapturingLayoutTest {

    /** A template block as the matcher sees it: block id and candle count ("" for none). */
    private data class Cell(val block: String, val candles: String)

    private class Template(val center: Cell, val cells: Map<Pair<Int, Int>, Cell>)

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun readTemplate(name: String, isCenter: (Cell) -> Boolean): Template {
        val stream = requireNotNull(javaClass.getResourceAsStream("/data/enderkinesis/structures/rituals/$name.nbt")) { "missing $name" }
        val tag = stream.use(NbtIo::readCompressed)
        val palette = tag.getList("palette", Tag.TAG_COMPOUND.toInt()).map {
            val entry = it as CompoundTag
            Cell(entry.getString("Name"), entry.getCompound("Properties").getString("candles"))
        }
        val placed = tag.getList("blocks", Tag.TAG_COMPOUND.toInt()).map {
            val block = it as CompoundTag
            val pos = block.getList("pos", Tag.TAG_INT.toInt())
            Triple(pos.getInt(0), pos.getInt(2), palette[block.getInt("state")])
        }
        val centers = placed.filter { isCenter(it.third) }
        centers.size shouldBe 1
        val (cx, cz, center) = centers.single()
        return Template(center, placed.filter { it !== centers.single() }.associate { (x, z, cell) -> (x - cx to z - cz) to cell })
    }

    /** The Twin Ring of the capture preview: green pairs around the centre, a wide ring of light blue singles, lime triples at 8 points. */
    private fun twinRing(): Map<Pair<Int, Int>, Cell> {
        val octants = listOf(0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1)
        val tips = octants.map { (dx, dz) -> if (dx != 0 && dz != 0) dx * 3 to dz * 3 else dx * 4 to dz * 4 }.toSet()
        val cells = HashMap<Pair<Int, Int>, Cell>()
        for (o in octants) cells[o] = Cell("minecraft:green_candle", "2")
        for (x in -4..4) for (z in -4..4) {
            if (hypot(x.toDouble(), z.toDouble()).roundToInt() != 4) continue
            cells[x to z] = if ((x to z) in tips) Cell("minecraft:lime_candle", "3") else Cell("minecraft:light_blue_candle", "1")
        }
        return cells
    }

    private fun capture() = readTemplate("capturing") { it.block == "enderkinesis:heart_candle" }

    @Test
    fun `the capturing template is the Twin Ring around a Heart Candle`() {
        val template = capture()
        template.center shouldBe Cell("enderkinesis:heart_candle", "")
        template.cells shouldBe twinRing()
        template.cells.size shouldBe 40
    }

    @Test
    fun `the eight lime triples are the anchors, one in each direction`() {
        val anchors = capture().cells.filterValues { it == Cell("minecraft:lime_candle", "3") }.keys
        anchors shouldBe setOf(0 to -4, 3 to -3, 4 to 0, 3 to 3, 0 to 4, -3 to 3, -4 to 0, -3 to -3)
    }

    @Test
    fun `the json names the template, the projectile ship trigger and the lime anchors`() {
        val json = ritualJson("capturing")
        json.get("layout").asString shouldBe "enderkinesis:rituals/capturing"
        json.getAsJsonObject("center").get("block").asString shouldBe "enderkinesis:heart_candle"
        val trigger = json.getAsJsonObject("trigger")
        trigger.get("type").asString shouldBe "enderkinesis:projectile_at_ship"
        trigger.get("radius").asDouble shouldBe 5.0
        trigger.get("range").asDouble shouldBe 512.0
        trigger.get("height").asDouble shouldBe 512.0
        json.getAsJsonObject("effect").getAsJsonArray("anchor_candles").map { it.asString } shouldBe listOf("minecraft:lime_candle")
    }

    @Test
    fun `no other rite with the same centre fires inside a Twin Ring, nor the Twin Ring inside another`() {
        val ring = capture()
        val dir = File(requireNotNull(javaClass.getResource("/data/enderkinesis/rituals")).toURI())
        for (file in requireNotNull(dir.listFiles()).filter { it.extension == "json" && it.nameWithoutExtension != "capturing" }) {
            val json = JsonParser.parseString(file.readText()).asJsonObject
            val centerJson = json.getAsJsonObject("center")
            val centerBlock = centerJson.get("block").asString
            if (centerBlock != ring.center.block) continue
            val layout = json.get("layout").asString.substringAfter("rituals/")
            val other = readTemplate(layout) { it.block == centerBlock }
            for (turn in 0 until 4) {
                val turned = other.cells.mapKeys { (offset, _) -> rotate(offset, turn) }
                turned.all { (offset, cell) -> ring.cells[offset] == cell } shouldBe false
                ring.cells.all { (offset, cell) -> turned[offset] == cell } shouldBe false
            }
        }
    }

    @Test
    fun `an anchor matches only a lit lime candle of three`() {
        val template = Blocks.LIME_CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3)
        val block = LayoutBlock(BlockPos(0, 0, -4), template, null)
        block.matches(template.setValue(CandleBlock.LIT, true)) shouldBe true
        block.matches(template) shouldBe false
        block.matches(template.setValue(CandleBlock.CANDLES, 2).setValue(CandleBlock.LIT, true)) shouldBe false
        block.matches(Blocks.GREEN_CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true)) shouldBe false
    }

    @Test
    fun `a ship hit counts within 512 blocks on the level and up to 512 above the centre, with no lower limit`() {
        val trigger = RitualTrigger.ProjectileAtShip(5.0, 512.0, 512.0)
        val center = Vec3(0.5, 64.5, 0.5)
        trigger.inRange(center, Vec3(512.5, 80.0, 0.5)) shouldBe true
        trigger.inRange(center, Vec3(0.5, 576.5, 0.5)) shouldBe true
        trigger.inRange(center, Vec3(0.5, -60.0, 0.5)) shouldBe true
        trigger.inRange(center, Vec3(513.0, 70.0, 0.5)) shouldBe false
        trigger.inRange(center, Vec3(0.5, 577.0, 0.5)) shouldBe false
    }

    private fun ritualJson(name: String): JsonObject {
        val stream = requireNotNull(javaClass.getResourceAsStream("/data/enderkinesis/rituals/$name.json")) { "missing $name" }
        return stream.use { JsonParser.parseString(it.readBytes().decodeToString()).asJsonObject }
    }

    private fun rotate(offset: Pair<Int, Int>, turns: Int): Pair<Int, Int> {
        var (x, z) = offset
        repeat(turns) { val t = x; x = -z; z = t }
        return x to z
    }
}
