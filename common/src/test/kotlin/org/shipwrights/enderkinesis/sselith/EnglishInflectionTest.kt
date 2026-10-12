package org.shipwrights.enderkinesis.sselith

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EnglishInflectionTest {

    @Test
    fun `regular plurals follow English spelling`() {
        EnglishInflection.plural("star") shouldBe "stars"
        EnglishInflection.plural("box") shouldBe "boxes"
        EnglishInflection.plural("church") shouldBe "churches"
        EnglishInflection.plural("library") shouldBe "libraries"
        EnglishInflection.plural("day") shouldBe "days"
        EnglishInflection.plural("shelf") shouldBe "shelves"
        EnglishInflection.plural("child") shouldBe "children"
    }

    @Test
    fun `regular verbs get -s, -ed and -ing`() {
        EnglishInflection.verbForms("walk") shouldContainAll listOf("walks", "walked", "walking")
        EnglishInflection.verbForms("carry") shouldContainAll listOf("carries", "carried", "carrying")
        EnglishInflection.verbForms("stop") shouldContainAll listOf("stops", "stopped", "stopping")
        EnglishInflection.verbForms("bake") shouldContainAll listOf("bakes", "baked", "baking")
        EnglishInflection.verbForms("die") shouldContainAll listOf("dies", "died", "dying")
        EnglishInflection.verbForms("see") shouldContainAll listOf("sees", "saw", "seen", "seeing")
        EnglishInflection.verbForms("go") shouldContainAll listOf("goes", "went", "gone", "going")
    }

    @Test
    fun `irregular verbs never get a regular past`() {
        EnglishInflection.verbForms("run") shouldNotContain "runned"
        EnglishInflection.verbForms("tell") shouldContainAll listOf("tells", "told", "telling")
        EnglishInflection.verbForms("write") shouldContainAll listOf("writes", "wrote", "written", "writing")
    }

    @Test
    fun `the english vocabulary holds inflections, names, and no passthrough labels`() {
        val english = BookshelfPage.vocabulary(BookshelfPage.Language.ENGLISH)
        english shouldContainAll listOf(
            "tells", "told", "holds", "keeps", "reads", "wrote", "knows", "knew", "sees", "saw", "seen", "came",
            "makes", "finds", "stars", "words", "pages", "shelves", "eyes", "years", "sselith", "owl", "eidetic",
        )
        for (label in listOf("p", "iii", "en")) english shouldNotContain label
    }
}
