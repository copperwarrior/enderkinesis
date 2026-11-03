package org.shipwrights.enderkinesis.physics

import com.fasterxml.jackson.databind.node.BinaryNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.valkyrienskies.core.impl.util.serialization.VSJacksonUtil
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

class AttachmentSaveTest : FunSpec({
    val mapper = VSJacksonUtil.defaultMapper

    // FORMAT 1, waterLineY, fluidDensity, waveTime, waveIntensity, blocks [1,2,3]
    val latticeBytes = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use {
            it.writeInt(1)
            it.writeDouble(64.0); it.writeDouble(900.0); it.writeDouble(2.5); it.writeDouble(0.75)
            AttachmentIo.writeIntArray(it, intArrayOf(1, 2, 3))
        }
    }.toByteArray()

    test("a lattice inducer goes through the vs-core mapper and back") {
        val inducer = mapper.treeToValue(BinaryNode(latticeBytes), CrepusculiteLatticeForceInducer::class.java)
        val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(inducer)
        tree.shouldBeInstanceOf<BinaryNode>()
        tree.binaryValue().toList() shouldBe latticeBytes.toList()
    }

    test("a base64 TextNode from a reloaded ship loads") {
        val text = TextNode(Base64.getEncoder().encodeToString(latticeBytes))
        val inducer = mapper.treeToValue(text, CrepusculiteLatticeForceInducer::class.java)
        inducer.saveBytes().toList() shouldBe latticeBytes.toList()
    }

    test("a bad node gives an inert attachment, not an exception") {
        val bad = JsonNodeFactory.instance.objectNode().put("x", 1)
        mapper.treeToValue(bad, CrepusculiteLatticeForceInducer::class.java).waterLineY shouldBe 0.0
        mapper.treeToValue(TextNode("not base64 !!"), ShulkerStrutDamper::class.java)
        mapper.treeToValue(bad, MothClothForceInducer::class.java).positions.size shouldBe 0
    }

    test("the strut, moth cloth and constriliar attachments round-trip") {
        for (value in listOf(ShulkerStrutDamper(), MothClothForceInducer().apply { positions = longArrayOf(7, 8) }, ConstriliarShipController())) {
            val tree = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(value)
            val back = mapper.treeToValue(tree, value.javaClass)
            mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(back) shouldBe tree
        }
    }
})
