package sselith.translator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public final class TranslatorMain {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            usageAndExit();
        }
        String mode = args[0];
        switch (mode) {
            case "books"   -> runBooks(args);
            case "langJson" -> runLangJson(args);
            default -> {
                System.err.println("Unknown mode: " + mode);
                usageAndExit();
            }
        }
    }

    private static void usageAndExit() {
        System.err.println("Usage:");
        System.err.println("  TranslatorMain books <sourceDir> <outputDir> <dictPath> "
                + "<posModelPath> <lemmaDictPath>");
        System.err.println("  TranslatorMain langJson <mcJar> <vanillaOut> <modIn> <modOut> "
                + "<dictPath> <posModelPath> <lemmaDictPath>");
        System.exit(2);
    }

    private static void runBooks(String[] args) throws IOException {
        if (args.length < 6) {
            usageAndExit();
        }
        Path sourceDir = Paths.get(args[1]);
        Path outputDir = Paths.get(args[2]);
        Path dictPath = Paths.get(args[3]);
        Path posModelPath = Paths.get(args[4]);
        Path lemmaDictPath = Paths.get(args[5]);

        requireExists(sourceDir, "source directory");
        requireExists(dictPath, "Sselith dictionary");
        requireModelFile(posModelPath, "POS model");
        requireModelFile(lemmaDictPath, "lemmatizer dictionary");

        SselithDictionary dict = SselithDictionary.load(dictPath);
        Translator translator = new Translator(dict, posModelPath, lemmaDictPath);

        Files.createDirectories(outputDir);
        try (Stream<Path> files = Files.walk(sourceDir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> {
                     String name = p.getFileName().toString().toLowerCase();
                     return name.endsWith(".md") || name.endsWith(".txt");
                 })
                 .forEach(source -> translateFile(source, sourceDir, outputDir, translator));
        }
    }

    /**
     * Translate both the vanilla Minecraft {@code en_us.json} (extracted from
     * the merged client jar that Loom caches at
     * {@code ~/.gradle/caches/fabric-loom/&lt;mc&gt;/minecraft-merged.jar})
     * and the mod's own {@code en_us.json}, writing them as
     * {@code en_se.json} into the gradle-generated resource tree. The
     * output is shipped in the jar so Minecraft's language picker offers
     * "Sselith" as a selectable language with full vanilla + mod coverage.
     */
    private static void runLangJson(String[] args) throws IOException {
        if (args.length < 8) {
            usageAndExit();
        }
        Path mcJar = Paths.get(args[1]);
        Path vanillaOut = Paths.get(args[2]);
        Path modIn = Paths.get(args[3]);
        Path modOut = Paths.get(args[4]);
        Path dictPath = Paths.get(args[5]);
        Path posModelPath = Paths.get(args[6]);
        Path lemmaDictPath = Paths.get(args[7]);

        requireExists(mcJar, "Minecraft merged client jar (Loom cache)");
        requireExists(modIn, "mod en_us.json");
        requireExists(dictPath, "Sselith dictionary");
        requireModelFile(posModelPath, "POS model");
        requireModelFile(lemmaDictPath, "lemmatizer dictionary");

        SselithDictionary dict = SselithDictionary.load(dictPath);
        Translator translator = new Translator(dict, posModelPath, lemmaDictPath);
        LangJsonTranslator langJson = new LangJsonTranslator(translator);

        System.out.println("Translating vanilla assets/minecraft/lang/en_us.json → "
                + vanillaOut.getFileName());
        langJson.translateJarEntry(mcJar, "assets/minecraft/lang/en_us.json", vanillaOut);

        System.out.println("Translating mod " + modIn.getFileName() + " → "
                + modOut.getFileName());
        langJson.translateFile(modIn, modOut);
    }

    private static void translateFile(Path source, Path sourceRoot, Path outputRoot,
                                       Translator translator) {
        try {
            Path relative = sourceRoot.relativize(source);
            Path target = outputRoot.resolve(relative);
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            String english = Files.readString(source);
            String sselith = translator.translate(english);
            Files.writeString(target, sselith);
            System.out.println("Translated: " + relative);
        } catch (IOException e) {
            throw new RuntimeException("Failed to translate " + source, e);
        }
    }

    private static void requireExists(Path p, String label) {
        if (!Files.exists(p)) {
            throw new IllegalStateException("Missing " + label + ": " + p.toAbsolutePath());
        }
    }

    private static void requireModelFile(Path p, String label) {
        if (!Files.exists(p)) {
            throw new IllegalStateException(
                    "Missing " + label + ": " + p.toAbsolutePath()
                    + ". Run `./gradlew :common:downloadSselithModels` to fetch the OpenNLP "
                    + "model files (en-pos-maxent.bin, en-lemmatizer.dict).");
        }
    }
}
