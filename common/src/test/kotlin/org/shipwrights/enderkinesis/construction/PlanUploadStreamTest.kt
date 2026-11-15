package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class PlanUploadStreamTest : FunSpec({
    val dir = Files.createTempDirectory("plan-upload-test")
    afterSpec { dir.toFile().deleteRecursively() }

    val data = ByteArray(PlanUploadStream.CHUNK * 2 + 123) { (it * 31).toByte() }
    val hash = PlanUploadStream.sha256Hex(data)

    fun chunks() = List(PlanUploadStream.chunkCount(data.size.toLong()).toInt()) {
        data.copyOfRange(it * PlanUploadStream.CHUNK, minOf(data.size, (it + 1) * PlanUploadStream.CHUNK))
    }

    fun stream(size: Long = data.size.toLong()) = PlanUploadStream("a", size, hash, Files.createTempFile(dir, "u", ".part"))

    test("chunks in order fill the file and match the hash") {
        val upload = stream()
        val parts = chunks()
        parts.dropLast(1).forEachIndexed { i, part -> upload.accept(i, part) shouldBe PlanUploadStream.Step.MORE }
        upload.accept(2, parts.last()) shouldBe PlanUploadStream.Step.DONE
        upload.chunksWritten shouldBe 3
        upload.hashMatches() shouldBe true
        Files.readAllBytes(upload.file).contentEquals(data) shouldBe true
    }

    test("a changed byte fails the hash") {
        val upload = stream()
        chunks().forEachIndexed { i, part -> upload.accept(i, if (i == 1) part.copyOf().also { it[0]++ } else part) }
        upload.hashMatches() shouldBe false
    }

    test("out-of-order and repeated chunks are refused") {
        val upload = stream()
        val parts = chunks()
        upload.accept(1, parts[1]) shouldBe PlanUploadStream.Step.BAD_SEQUENCE
        upload.accept(0, parts[0]) shouldBe PlanUploadStream.Step.MORE
        upload.accept(0, parts[0]) shouldBe PlanUploadStream.Step.BAD_SEQUENCE
        upload.close()
    }

    test("chunks past the size, empty chunks and oversize chunks are refused") {
        stream(10).use { it.accept(0, ByteArray(11)) shouldBe PlanUploadStream.Step.TOO_MANY_BYTES }
        stream().use {
            it.accept(0, ByteArray(0)) shouldBe PlanUploadStream.Step.BAD_CHUNK
            it.accept(0, ByteArray(PlanUploadStream.CHUNK + 1)) shouldBe PlanUploadStream.Step.BAD_CHUNK
        }
        val done = stream(5)
        done.accept(0, ByteArray(5)) shouldBe PlanUploadStream.Step.DONE
        done.accept(1, ByteArray(1)) shouldBe PlanUploadStream.Step.BAD_SEQUENCE
    }

    test("a configured packet size refuses larger chunks and counts chunks by that size") {
        PlanUploadStream.chunkCount(1000, 256) shouldBe 4
        PlanUploadStream("a", 1000, hash, Files.createTempFile(dir, "u", ".part"), maxChunk = 256).use {
            it.accept(0, ByteArray(257)) shouldBe PlanUploadStream.Step.BAD_CHUNK
            it.accept(0, ByteArray(256)) shouldBe PlanUploadStream.Step.MORE
        }
        shouldThrow<IllegalArgumentException> {
            PlanUploadStream("a", 5, hash, dir.resolve("y.part"), maxChunk = PlanUploadStream.CHUNK + 1)
        }
    }

    test("a sizeless upload, a bad hash and an early hash check throw") {
        shouldThrow<IllegalStateException> { stream(5).use { it.hashMatches() } }
        shouldThrow<IllegalArgumentException> { stream(0) }
        shouldThrow<IllegalArgumentException> { PlanUploadStream("a", 5, "../x", dir.resolve("x.part")) }
    }
})
