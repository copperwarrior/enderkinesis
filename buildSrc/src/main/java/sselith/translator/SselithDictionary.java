package sselith.translator;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SselithDictionary {

    public record LexiconEntry(String pos, String sselith, String tag) {}

    public static final class NumeralConfig {
        public int base;
        public Map<String, String> digits;
        public String radixWord;
        public String nonTerminatingMarker;
        public int fractionDigitCap;
        public String joiner;
    }

    public static final class PronounConfig {
        public String subject;
        @SerializedName("object")
        public String objectForm;
        public String possessive;
        public String reflexive;
        public String demonstrative;
    }

    public static final class Morphology {
        public Map<String, String> suffixes;
        public Map<String, String> prefixes;
    }

    public static final class Phonology {
        public List<String> vowels;
    }

    private final Morphology morphology;
    private final NumeralConfig numerals;
    private final PronounConfig pronouns;
    private final Map<String, LexiconEntry> lexicon;
    private final Set<Character> vowelChars;
    private final int maxMultiWordTokens;

    private SselithDictionary(Raw raw) {
        this.morphology = raw.morphology;
        this.numerals = raw.numerals;
        this.pronouns = raw.pronouns;
        // Lowercase the lemma keys for case-insensitive lookup.
        this.lexicon = new LinkedHashMap<>();
        int maxTokens = 1;
        for (Map.Entry<String, LexiconEntry> e : raw.lexicon.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT);
            this.lexicon.put(key, e.getValue());
            int tokens = 1;
            for (int i = 0; i < key.length(); i++) {
                if (key.charAt(i) == ' ') tokens++;
            }
            if (tokens > maxTokens) maxTokens = tokens;
        }
        this.maxMultiWordTokens = maxTokens;
        this.vowelChars = new HashSet<>();
        for (String v : raw.phonology.vowels) {
            for (char c : v.toLowerCase(Locale.ROOT).toCharArray()) {
                vowelChars.add(c);
            }
        }
    }

    public static SselithDictionary load(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path)) {
            Raw raw = new Gson().fromJson(r, Raw.class);
            return new SselithDictionary(raw);
        }
    }

    public LexiconEntry lookup(String lemma) {
        if (lemma == null) return null;
        return lexicon.get(lemma.toLowerCase(Locale.ROOT));
    }

    public NumeralConfig getNumerals() {
        return numerals;
    }

    public PronounConfig getPronouns() {
        return pronouns;
    }

    public Map<String, String> getSuffixes() {
        return morphology.suffixes;
    }

    public Map<String, String> getPrefixes() {
        return morphology.prefixes;
    }

    public Set<Character> getVowels() {
        return Collections.unmodifiableSet(vowelChars);
    }

    public int getMaxMultiWordTokens() {
        return maxMultiWordTokens;
    }

    private static final class Raw {
        Morphology morphology;
        NumeralConfig numerals;
        PronounConfig pronouns;
        Map<String, LexiconEntry> lexicon;
        Phonology phonology;
    }
}
