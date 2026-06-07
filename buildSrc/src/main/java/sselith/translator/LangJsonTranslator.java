package sselith.translator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Translates a Minecraft 1.13+ lang JSON ({@code {"key": "English value", ...}})
 * through the Sselith {@link Translator}, producing a parallel JSON keyed by
 * the same keys but with values rewritten in Sselith. Used at build time to
 * generate {@code en_se.json} variants of both the vanilla
 * {@code assets/minecraft/lang/en_us.json} (extracted from the cached
 * Minecraft client jar) and the mod's own
 * {@code assets/enderkinesis/lang/en_us.json}.
 *
 * <p>The Translator handles per-word morphology, leaves non-word tokens
 * (placeholders like {@code %s} / {@code %1$s}, format codes like
 * {@code §6}, escape sequences like {@code \n}) untouched as they aren't
 * word-shaped tokens — so existing lang values round-trip correctly.</p>
 *
 * <p>Empty values are preserved as empty strings (Minecraft treats
 * {@code "foo": ""} as an explicit "no translation" entry; running it
 * through the translator would produce nothing and the same empty string
 * comes back).</p>
 */
public final class LangJsonTranslator {

    private final Translator translator;

    public LangJsonTranslator(Translator translator) {
        this.translator = translator;
    }

    /**
     * Translate a lang JSON file. Reads {@code source}, writes the
     * translated equivalent to {@code dest}. Key order is preserved by
     * iterating the parsed JSON via {@link JsonObject#entrySet()} (Gson
     * backs that with a {@link LinkedHashMap}, giving insertion order).
     */
    public void translateFile(Path source, Path dest) throws IOException {
        JsonObject input;
        try (Reader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            input = JsonParser.parseReader(r).getAsJsonObject();
        }
        writeTranslated(input, dest);
    }

    /**
     * Variant for vanilla extraction: the en_us.json lives inside the
     * Minecraft client jar at {@code assets/minecraft/lang/en_us.json}.
     * Open it via a zip reader rather than asking the caller to unzip first.
     */
    public void translateJarEntry(Path jarFile, String entryPath, Path dest) throws IOException {
        JsonObject input;
        try (ZipFile zf = new ZipFile(jarFile.toFile())) {
            ZipEntry entry = zf.getEntry(entryPath);
            if (entry == null) {
                throw new IOException("Entry not found in jar: " + entryPath + " (jar=" + jarFile + ")");
            }
            try (InputStream is = zf.getInputStream(entry);
                 Reader r = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                input = JsonParser.parseReader(r).getAsJsonObject();
            }
        }
        writeTranslated(input, dest);
    }

    private void writeTranslated(JsonObject input, Path dest) throws IOException {
        // Translate first into a LinkedHashMap so we control the JSON
        // output formatting directly — Gson's pretty printer escapes
        // characters we don't want escaped (e.g. §) and adds bracket
        // styling that doesn't match Mojang's own lang files.
        Map<String, String> out = new LinkedHashMap<>(input.size());
        for (Map.Entry<String, JsonElement> e : input.entrySet()) {
            String english = e.getValue().getAsString();
            if (english.isEmpty()) {
                out.put(e.getKey(), "");
                continue;
            }
            String sselith = translator.translate(english);
            out.put(e.getKey(), sselith);
        }
        Path parent = dest.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Writer w = Files.newBufferedWriter(dest, StandardCharsets.UTF_8)) {
            writeJson(w, out);
        }
    }

    /**
     * Write the translated map as a Mojang-style lang JSON: 2-space indent,
     * sorted insertion order, JSON-escape only the minimum required
     * ({@code "}, {@code \}, control chars). In particular we do NOT
     * escape Unicode like {@code §} — those round-trip through UTF-8 just
     * fine and match how Mojang's source files look.
     */
    private static void writeJson(Writer w, Map<String, String> map) throws IOException {
        w.write("{\n");
        Iterator<Map.Entry<String, String>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            w.write("  ");
            writeQuoted(w, e.getKey());
            w.write(": ");
            writeQuoted(w, e.getValue());
            if (it.hasNext()) w.write(',');
            w.write('\n');
        }
        w.write("}\n");
    }

    private static void writeQuoted(Writer w, String s) throws IOException {
        w.write('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> w.write("\\\"");
                case '\\' -> w.write("\\\\");
                case '\n' -> w.write("\\n");
                case '\r' -> w.write("\\r");
                case '\t' -> w.write("\\t");
                case '\b' -> w.write("\\b");
                case '\f' -> w.write("\\f");
                default   -> {
                    if (c < 0x20) {
                        w.write(String.format("\\u%04x", (int) c));
                    } else {
                        w.write(c);
                    }
                }
            }
        }
        w.write('"');
    }
}
