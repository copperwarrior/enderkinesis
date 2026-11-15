package org.shipwrights.enderkinesis.construction

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.StringReader
import java.nio.file.Files
import java.util.Properties

private fun fileValues(text: String): Map<String, String> =
    Properties().also { it.load(StringReader(text)) }.entries.associate { (k, v) -> k.toString() to v.toString() }

class BuildConfigTest : FunSpec({
    test("the defaults are the user's values") {
        val plans = BuildConfig.DEFAULT.plans
        PlanLimits.QUOTA shouldBe 16
        plans.maxFileMegabytes shouldBe 500
        plans.maxFileBytes shouldBe 500_000_000L
        BuildConfig.DEFAULT.build shouldBe BuildTuning(picksPerTick = 8, maxLinkedSupplies = 8, orderBudget = 4096)
    }

    test("an empty file gives the defaults and lists every key as missing") {
        val parsed = parseBuildConfig(emptyMap())
        parsed.config shouldBe BuildConfig.DEFAULT
        parsed.problems shouldHaveSize 11
        parsed.problems.all { it.startsWith("missing ") } shouldBe true
    }

    test("the written file reads back to the same config with no problems") {
        val config = BuildConfig(PlanLimits(maxFileMegabytes = 20, uploadPacketBytes = 1024), BuildTuning(picksPerTick = 3))
        val parsed = parseBuildConfig(fileValues(config.toFileText()))
        parsed.problems.shouldBeEmpty()
        parsed.config shouldBe config
    }

    test("the plan count is not a config key") {
        BuildConfig.DEFAULT.toFileText().contains("maxPerPlayer") shouldBe false
        parseBuildConfig(fileValues(BuildConfig.DEFAULT.toFileText()) + ("plans.maxPerPlayer" to "3")).problems.single() shouldContain "unknown key"
    }

    test("bad numbers keep the default, out-of-range values clamp, unknown keys are listed") {
        val values = fileValues(BuildConfig.DEFAULT.toFileText()).toMutableMap()
        values["plans.maxFileMegabytes"] = "lots"
        values["build.picksPerTick"] = "0"
        values["plans.uploadPacketBytes"] = "99999"
        values["plans.color"] = "red"
        val parsed = parseBuildConfig(values)
        parsed.config.plans.maxFileMegabytes shouldBe 500
        parsed.config.build.picksPerTick shouldBe 1
        parsed.config.plans.uploadPacketBytes shouldBe PlanUploadStream.CHUNK
        parsed.problems shouldHaveSize 4
        parsed.problems.any { it.contains("plans.color") } shouldBe true
    }

    test("an upload rate below one packet rises to one packet") {
        val values = fileValues(BuildConfig.DEFAULT.toFileText()).toMutableMap()
        values["plans.uploadPacketBytes"] = "4096"
        values["plans.uploadBytesPerTick"] = "1000"
        val parsed = parseBuildConfig(values)
        parsed.config.plans.uploadBytesPerTick shouldBe 4096
        parsed.problems.single() shouldContain "uploadBytesPerTick"
    }

    test("the file names each key with its range and default") {
        val text = BuildConfig.DEFAULT.toFileText()
        text shouldContain "plans.maxFileMegabytes=500"
        text shouldContain "Range 1..1000000, default 500."
    }

    test("the loader writes the defaults when the file is missing and keeps a user's value") {
        val dir = Files.createTempDirectory("build-config-test")
        try {
            val file = dir.resolve(ServerBuildConfig.FILE_NAME)
            ServerBuildConfig.load(file) shouldBe BuildConfig.DEFAULT
            Files.isRegularFile(file) shouldBe true
            Files.writeString(file, Files.readString(file).replace("plans.maxFileMegabytes=500", "plans.maxFileMegabytes=3"))
            ServerBuildConfig.load(file).plans.maxFileMegabytes shouldBe 3
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("upload sizes past the limit are too large, and empty files are bad") {
        val limits = PlanLimits(maxFileMegabytes = 2)
        limits.uploadRefusal(2_000_000) shouldBe null
        limits.uploadRefusal(2_000_001) shouldBe "too_large"
        limits.uploadRefusal(0) shouldBe "bad_file"
        limits.uploadRefusal(2_000_001, limitSize = false) shouldBe null
        limits.uploadRefusal(0, limitSize = false) shouldBe "bad_file"
        PlanLimits.megabytes(2_000_001) shouldBe 3
        PlanLimits.megabytes(1) shouldBe 1
    }

    test("limits that cannot carry one packet a tick are refused") {
        shouldThrow<IllegalArgumentException> { PlanLimits(uploadPacketBytes = 4096, uploadBytesPerTick = 1000) }
        shouldThrow<IllegalArgumentException> { PlanLimits(uploadPacketBytes = 100) }
        shouldThrow<IllegalArgumentException> { BuildTuning(orderBudget = 0) }
    }
})
