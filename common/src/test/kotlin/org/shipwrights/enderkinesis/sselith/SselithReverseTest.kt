package org.shipwrights.enderkinesis.sselith

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

class SselithReverseTest {

    private fun reverse(sselith: String) = Sselith.translateToEnglish(sselith, 1.0, java.util.Random(0))

    @Test
    fun `AUG gives great on a noun`() {
        reverse("morthrueh") shouldBe "great-tree"
    }

    @Test
    fun `DIM gives little on a noun and half on a verb`() {
        reverse("velthrueh") shouldBe "little-tree"
        reverse("velthruehelk") shouldBe "little-trees"
        reverse("velvolkh") shouldBe "half-go"
    }

    @Test
    fun `DIR gives -ward on a noun`() {
        reverse("tochsaerkh") shouldBe "homeward"
    }

    @Test
    fun `SACRED gives holy`() {
        reverse("zhethrueh") shouldBe "holy-tree"
    }

    @Test
    fun `the prefix word keeps the case of the token`() {
        reverse("Morthrueh") shouldBe "Great-tree"
    }

    @Test
    fun `a lexicalised prefixed entry gives its own word without a prefix word`() {
        reverse("zhegloumkh") shouldBe "star"
        reverse("tochflankhtorl") shouldBe "sideways"
        reverse("morvolkh") shouldBe "run"
    }

    @Test
    fun `a lexicalised prefixed entry with a suffix gives no prefix word`() {
        reverse("morvolkhacht") shouldBe "ran"
    }

    @Test
    fun `isAllEnglish is false while a Sselith word is left`() {
        Sselith.isAllEnglish("The morthrueh stands.") shouldBe false
        Sselith.isAllEnglish("The great-tree stands.") shouldBe true
    }

    @Test
    fun `every book ships its English source and the source reads as all English`() {
        val names = File("src/main/resources/assets/enderkinesis/texts/sselith/books").list()?.toList().orEmpty()
        names.shouldNotBeEmpty()
        for (name in names) {
            val text = javaClass.getResourceAsStream("/assets/enderkinesis/texts/english/books/$name")
                ?.bufferedReader()?.use { it.readText() }
            checkNotNull(text) { "English source of $name is not bundled" }
            withClue(name) { Sselith.isAllEnglish(text) shouldBe true }
        }
    }
}
