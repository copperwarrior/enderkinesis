package sselith.translator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MorphologyEngineTest {

    private static final Set<Character> VOWELS = Set.of('a', 'e', 'i', 'o', 'u');

    @Test
    void suffixNoCollision() {
        assertEquals("regarnelk", MorphologyEngine.attachSuffix("regarn", "-elk", VOWELS));
    }

    @Test
    void suffixVowelCollision() {
        assertEquals("zaeghk", MorphologyEngine.attachSuffix("za", "-aeghk", VOWELS));
    }

    @Test
    void suffixConsonantNoCollision() {
        assertEquals("velkhend", MorphologyEngine.attachSuffix("velkh", "-end", VOWELS));
    }

    @Test
    void suffixEmptyStem() {
        assertEquals("acht", MorphologyEngine.attachSuffix("", "-acht", VOWELS));
    }

    @Test
    void suffixEmptySuffix() {
        assertEquals("regarn", MorphologyEngine.attachSuffix("regarn", "", VOWELS));
    }

    @Test
    void suffixHyphenStripped() {
        // No leading hyphen → still treats as ordinary suffix string.
        assertEquals("regarnelk", MorphologyEngine.attachSuffix("regarn", "elk", VOWELS));
    }

    @Test
    void suffixConsonantCollision() {
        // Stem ends in 't', suffix starts with 't': drop suffix's first char.
        assertEquals("netesk", MorphologyEngine.attachSuffix("net", "-tesk", VOWELS));
    }

    @Test
    void prefixVowelCollision() {
        assertEquals("namielkarn", MorphologyEngine.attachPrefix("amielkarn", "na-", VOWELS));
    }

    @Test
    void prefixNoCollision() {
        assertEquals("kreservocht", MorphologyEngine.attachPrefix("servocht", "kre-", VOWELS));
    }

    @Test
    void prefixEmptyStem() {
        assertEquals("na", MorphologyEngine.attachPrefix("", "na-", VOWELS));
    }

    @Test
    void prefixSingleCharStem() {
        // Stem "a", prefix "na-" → 'a' collides with 'a' → "na" + "" = "na".
        assertEquals("na", MorphologyEngine.attachPrefix("a", "na-", VOWELS));
    }
}
