package org.shipwrights.enderkinesis.sselith

/** Regular English inflections, with tables for the irregular forms a rule would get wrong. */
internal object EnglishInflection {

    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
    private val SIBILANT = Regex("(s|x|z|ch|sh)$")
    // One short syllable ending consonant-vowel-consonant doubles its last letter: stop, stopped, stopping.
    private val SHORT_CVC = Regex("^[^aeiou]*[aeiou][^aeiouwxy]$")

    fun plural(noun: String): String = IRREGULAR_PLURALS[noun] ?: when {
        // Compounds of an irregular noun: bookshelf, businessman, werewolf.
        noun.endsWith("shelf") || noun.endsWith("wolf") || noun.endsWith("leaf") || noun.endsWith("knife") ||
            noun.endsWith("life") || noun.endsWith("wife") -> noun.dropLast(if (noun.endsWith("fe")) 2 else 1) + "ves"
        noun.endsWith("man") && noun !in MAN_WITH_REGULAR_PLURAL -> noun.dropLast(3) + "men"
        noun.endsWith("sis") -> noun.dropLast(2) + "es"
        SIBILANT.containsMatchIn(noun) -> noun + "es"
        endsConsonantY(noun) -> noun.dropLast(1) + "ies"
        else -> noun + "s"
    }

    fun verbForms(verb: String): List<String> {
        IRREGULAR_VERBS[verb]?.let { return listOf(thirdPerson(verb), presentParticiple(verb)) + it }
        return listOf(thirdPerson(verb), presentParticiple(verb), past(verb))
    }

    private fun thirdPerson(verb: String): String = when (verb) {
        "be" -> "is"
        "have" -> "has"
        else -> when {
            SIBILANT.containsMatchIn(verb) -> verb + "es"
            endsConsonantY(verb) -> verb.dropLast(1) + "ies"
            verb.length > 1 && verb.last() == 'o' && verb[verb.length - 2] !in VOWELS -> verb + "es"
            else -> verb + "s"
        }
    }

    private fun past(verb: String): String = when {
        verb.endsWith("e") -> verb + "d"
        endsConsonantY(verb) -> verb.dropLast(1) + "ied"
        SHORT_CVC.matches(verb) -> verb + verb.last() + "ed"
        else -> verb + "ed"
    }

    private fun presentParticiple(verb: String): String = when {
        verb.endsWith("ie") -> verb.dropLast(2) + "ying"
        verb.endsWith("e") && verb.length > 2 && !verb.endsWith("ee") && !verb.endsWith("ye") && !verb.endsWith("oe") ->
            verb.dropLast(1) + "ing"
        SHORT_CVC.matches(verb) -> verb + verb.last() + "ing"
        else -> verb + "ing"
    }

    /** True for an irregular plural or past form in the tables ("men", "began"). */
    fun isIrregularForm(word: String): Boolean = word in IRREGULAR_FORMS

    private fun endsConsonantY(word: String): Boolean =
        word.length > 1 && word.last() == 'y' && word[word.length - 2] !in VOWELS

    private val MAN_WITH_REGULAR_PLURAL = setOf("human", "shaman", "talisman", "ottoman", "caiman", "roman", "german")

    private val IRREGULAR_PLURALS = mapOf(
        "man" to "men", "woman" to "women", "child" to "children", "foot" to "feet", "tooth" to "teeth",
        "mouse" to "mice", "goose" to "geese", "person" to "people", "ox" to "oxen", "louse" to "lice",
        "shelf" to "shelves", "leaf" to "leaves", "wolf" to "wolves", "knife" to "knives", "life" to "lives",
        "wife" to "wives", "half" to "halves", "self" to "selves", "elf" to "elves", "calf" to "calves",
        "loaf" to "loaves", "thief" to "thieves", "scarf" to "scarves", "dwarf" to "dwarves", "hoof" to "hooves",
        "sheep" to "sheep", "fish" to "fish", "deer" to "deer", "cactus" to "cacti", "fungus" to "fungi",
    )

    // Past and past participle; the rule supplies -s and -ing.
    private val IRREGULAR_VERBS: Map<String, List<String>> = mapOf(
        "be" to listOf("are", "was", "were", "been"), "have" to listOf("had"), "do" to listOf("did", "done"),
        "go" to listOf("went", "gone"), "say" to listOf("said"), "make" to listOf("made"),
        "get" to listOf("got", "gotten"), "know" to listOf("knew", "known"), "think" to listOf("thought"),
        "take" to listOf("took", "taken"), "see" to listOf("saw", "seen"), "come" to listOf("came"),
        "give" to listOf("gave", "given"), "find" to listOf("found"), "tell" to listOf("told"),
        "become" to listOf("became"), "leave" to listOf("left"), "feel" to listOf("felt"),
        "bring" to listOf("brought"), "begin" to listOf("began", "begun"), "keep" to listOf("kept"),
        "hold" to listOf("held"), "write" to listOf("wrote", "written"), "stand" to listOf("stood"),
        "hear" to listOf("heard"), "let" to listOf("let"), "mean" to listOf("meant"), "set" to listOf("set"),
        "meet" to listOf("met"), "run" to listOf("ran"), "pay" to listOf("paid"), "sit" to listOf("sat"),
        "speak" to listOf("spoke", "spoken"), "lie" to listOf("lay", "lain"), "lead" to listOf("led"),
        "read" to listOf("read"), "grow" to listOf("grew", "grown"), "lose" to listOf("lost"),
        "fall" to listOf("fell", "fallen"), "send" to listOf("sent"), "build" to listOf("built"),
        "understand" to listOf("understood"), "draw" to listOf("drew", "drawn"), "break" to listOf("broke", "broken"),
        "spend" to listOf("spent"), "cut" to listOf("cut"), "rise" to listOf("rose", "risen"),
        "drive" to listOf("drove", "driven"), "buy" to listOf("bought"), "wear" to listOf("wore", "worn"),
        "choose" to listOf("chose", "chosen"), "seek" to listOf("sought"), "throw" to listOf("threw", "thrown"),
        "catch" to listOf("caught"), "deal" to listOf("dealt"), "win" to listOf("won"),
        "forget" to listOf("forgot", "forgotten"), "lay" to listOf("laid"), "sell" to listOf("sold"),
        "fight" to listOf("fought"), "teach" to listOf("taught"), "eat" to listOf("ate", "eaten"),
        "sing" to listOf("sang", "sung"), "swim" to listOf("swam", "swum"), "drink" to listOf("drank", "drunk"),
        "fly" to listOf("flew", "flown"), "hide" to listOf("hid", "hidden"), "bite" to listOf("bit", "bitten"),
        "ride" to listOf("rode", "ridden"), "shake" to listOf("shook", "shaken"), "steal" to listOf("stole", "stolen"),
        "strike" to listOf("struck"), "swear" to listOf("swore", "sworn"), "tear" to listOf("tore", "torn"),
        "wake" to listOf("woke", "woken"), "weave" to listOf("wove", "woven"), "bind" to listOf("bound"),
        "blow" to listOf("blew", "blown"), "freeze" to listOf("froze", "frozen"), "hang" to listOf("hung"),
        "shine" to listOf("shone"), "shoot" to listOf("shot"), "sleep" to listOf("slept"), "slide" to listOf("slid"),
        "spin" to listOf("spun"), "spread" to listOf("spread"), "stick" to listOf("stuck"), "sting" to listOf("stung"),
        "swing" to listOf("swung"), "dig" to listOf("dug"), "feed" to listOf("fed"), "flee" to listOf("fled"),
        "forgive" to listOf("forgave", "forgiven"), "hit" to listOf("hit"), "hurt" to listOf("hurt"),
        "kneel" to listOf("knelt"), "light" to listOf("lit"), "put" to listOf("put"), "quit" to listOf("quit"),
        "ring" to listOf("rang", "rung"), "shut" to listOf("shut"), "sink" to listOf("sank", "sunk"),
        "slay" to listOf("slew", "slain"), "spit" to listOf("spat"), "split" to listOf("split"),
        "wind" to listOf("wound"), "beat" to listOf("beaten"), "bear" to listOf("bore", "borne"),
        "bend" to listOf("bent"), "bleed" to listOf("bled"), "breed" to listOf("bred"), "cast" to listOf("cast"),
        "cling" to listOf("clung"), "creep" to listOf("crept"), "dwell" to listOf("dwelt"),
        "forbid" to listOf("forbade", "forbidden"), "grind" to listOf("ground"), "lend" to listOf("lent"),
        "shrink" to listOf("shrank", "shrunk"), "sweep" to listOf("swept"), "weep" to listOf("wept"),
        "sew" to listOf("sewn"), "sow" to listOf("sown"), "show" to listOf("shown"), "stride" to listOf("strode"),
        "arise" to listOf("arose", "arisen"), "awake" to listOf("awoke", "awoken"),
        "spring" to listOf("sprang", "sprung"),
    )

    // A regular plural that is equal to its singular ("sheep") is not an inflected form.
    private val IRREGULAR_FORMS: Set<String> =
        IRREGULAR_VERBS.flatMap { (verb, forms) -> forms.filter { it != verb } }.toSet() +
            IRREGULAR_PLURALS.filter { (singular, plural) -> singular != plural }.values
}
