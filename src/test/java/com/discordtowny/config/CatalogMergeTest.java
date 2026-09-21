package com.discordtowny.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogMergeTest {
    private static final String TOKEN = "token-secreto-de-prueba";
    private static final String PASSWORD = "password-secreto-de-prueba";

    @TempDir
    Path folder;

    private YamlConfiguration configYaml;
    private YamlConfigLoader loader;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void prepare() throws Exception {
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in, "Bundled /config.yml must exist");
            Files.copy(in, folder.resolve("config.yml"));
        }
        configYaml = new YamlConfiguration();
        configYaml.load(folder.resolve("config.yml").toFile());
        configYaml.set("discord.token", TOKEN);
        configYaml.set("discord.guild-id", "123456789012345678");
        configYaml.set("database.password", PASSWORD);
        configYaml.save(folder.resolve("config.yml").toFile());
    }

    /**
     * Requirement: A catalog with some keys deleted, other values edited, its own
     * comments, and a custom key the owner invented comes back with the missing
     * keys added inside their proper sections, every edited value unchanged, every
     * comment still present, and the custom key still there.
     */
    @Test
    void mergePreservesCommentsEditedValuesCustomKeysAndAddsMissingKeysInProperSections() throws Exception {
        String ownerYamlContent = ""
                + "# Custom owner header comment at top of file\n"
                + "prefix: \"&8[&4CustomServer&8] &r\"\n"
                + "\n"
                + "# Custom comment for general section\n"
                + "general:\n"
                + "  # Comment on edited no-permission message\n"
                + "  no-permission: \"&cCustom permission error message\"\n"
                + "  # Custom comment on custom fork key\n"
                + "  owner-custom-key: \"&eCustom key created by server owner\"\n"
                + "\n"
                + "# Custom comment for linking section\n"
                + "linking:\n"
                + "  # Comment on edited link-success message\n"
                + "  link-success: \"&aCustom link success message\"\n"
                + "\n"
                + "space:\n"
                + "  created: \"&aCustom space created\"\n"
                + "\n"
                + "sync:\n"
                + "  finished: \"&aCustom sync finished\"\n"
                + "\n"
                + "discord:\n"
                + "  help-title: \"Custom Help\"\n"
                + "\n"
                + "help:\n"
                + "  description: \"Custom commands\"\n"
                + "\n"
                + "status:\n"
                + "  header: \"Custom status header\"\n";

        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, ownerYamlContent, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String mergedContent = Files.readString(enPath, StandardCharsets.UTF_8);

        // 1. Comments preserved
        assertTrue(mergedContent.contains("# Custom owner header comment at top of file"),
                "Top-level header comment must be preserved");
        assertTrue(mergedContent.contains("# Custom comment for general section"),
                "Section comment must be preserved");
        assertTrue(mergedContent.contains("# Comment on edited no-permission message"),
                "Edited key comment must be preserved");
        assertTrue(mergedContent.contains("# Custom comment on custom fork key"),
                "Custom key comment must be preserved");
        assertTrue(mergedContent.contains("# Custom comment for linking section"),
                "Linking section comment must be preserved");
        assertTrue(mergedContent.contains("# Comment on edited link-success message"),
                "Link success comment must be preserved");

        // 2. Edited values intact
        assertTrue(mergedContent.contains("prefix: \"&8[&4CustomServer&8] &r\""),
                "Edited prefix must remain unchanged");
        assertTrue(mergedContent.contains("no-permission: \"&cCustom permission error message\""),
                "Edited no-permission must remain unchanged");
        assertTrue(mergedContent.contains("link-success: \"&aCustom link success message\""),
                "Edited link-success must remain unchanged");
        assertTrue(mergedContent.contains("created: \"&aCustom space created\""),
                "Edited space.created must remain unchanged");

        // 3. Owner custom key still present
        assertTrue(mergedContent.contains("owner-custom-key: \"&eCustom key created by server owner\""),
                "Owner custom key must still be present");

        // 4. Missing keys inserted inside their proper sections
        int generalIdx = mergedContent.indexOf("general:");
        int residentNotFoundIdx = mergedContent.indexOf("resident-not-found:");
        int linkingIdx = mergedContent.indexOf("linking:");
        int codeInvalidIdx = mergedContent.indexOf("code-invalid:");
        int spaceIdx = mergedContent.indexOf("space:");

        assertTrue(generalIdx != -1, "general: section header must exist");
        assertTrue(residentNotFoundIdx != -1, "resident-not-found key must be added");
        assertTrue(linkingIdx != -1, "linking: section header must exist");
        assertTrue(residentNotFoundIdx > generalIdx && residentNotFoundIdx < linkingIdx,
                "resident-not-found must be inserted inside general section, before linking section");

        assertTrue(codeInvalidIdx != -1, "code-invalid key must be added");
        assertTrue(codeInvalidIdx > linkingIdx && codeInvalidIdx < spaceIdx,
                "code-invalid must be inserted inside linking section, before space section");

        // 5. Valid YAML parsing
        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(mergedContent);

        assertEquals("&8[&4CustomServer&8] &r", parsed.getString("prefix"));
        assertEquals("&cCustom permission error message", parsed.getString("general.no-permission"));
        assertEquals("&eCustom key created by server owner", parsed.getString("general.owner-custom-key"));
        assertEquals("&aCustom link success message", parsed.getString("linking.link-success"));
        assertEquals("&aCustom space created", parsed.getString("space.created"));

        assertNotNull(parsed.getString("general.resident-not-found"));
        assertFalse(parsed.getString("general.resident-not-found").isBlank());
        assertNotNull(parsed.getString("linking.code-invalid"));
        assertFalse(parsed.getString("linking.code-invalid").isBlank());

        // 6. Runtime messages access
        Messages messages = loader.messages();
        // plain() renders the colour codes away, so the raw catalog text is not what
        // comes back. What matters is that the owner's prefix and their own text are
        // the ones in force, not the bundled ones.
        assertEquals("[CustomServer] Custom key created by server owner",
                messages.plain("general.owner-custom-key", Map.of()));
        assertTrue(messages.plain("general.no-permission", Map.of()).contains("Custom permission error message"));
        assertTrue(messages.plain("general.resident-not-found", Map.of("resident", "Player1")).contains("Player1"));

        // 7. Warning logged once
        long warningCount = warnings.stream()
                .filter(w -> w.startsWith("messages_en.yml: added missing key"))
                .count();
        assertEquals(1, warningCount, "Added missing keys must be reported once: " + warnings);
    }

    /**
     * Requirement: A missing Spanish key takes the bundled SPANISH text, not the English one.
     */
    @Test
    void missingSpanishKeyTakesBundledSpanishTextNotEnglish() throws Exception {
        configYaml.set("language", "es");
        configYaml.save(folder.resolve("config.yml").toFile());

        // Spanish owner file missing general.resident-not-found and linking.code-invalid
        String esContent = ""
                + "prefix: \"&8[&bDT-ES&8] &r\"\n"
                + "general:\n"
                + "  no-permission: \"&cNo tienes permiso para esto.\"\n"
                + "linking:\n"
                + "  link-success: \"&aCuenta vinculada exitosamente.\"\n";

        Path esPath = folder.resolve("messages_es.yml");
        Files.writeString(esPath, esContent, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String mergedEs = Files.readString(esPath, StandardCharsets.UTF_8);

        YamlConfiguration bundledEs = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/messages_es.yml")) {
            assertNotNull(in);
            bundledEs.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        YamlConfiguration bundledEn = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            assertNotNull(in);
            bundledEn.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }

        YamlConfiguration parsedEs = new YamlConfiguration();
        parsedEs.loadFromString(mergedEs);

        String residentNotFoundEs = parsedEs.getString("general.resident-not-found");
        assertNotNull(residentNotFoundEs, "general.resident-not-found must be present in messages_es.yml");
        assertEquals(bundledEs.getString("general.resident-not-found"), residentNotFoundEs,
                "A missing Spanish key must take the bundled Spanish text");
        assertNotEquals(bundledEn.getString("general.resident-not-found"), residentNotFoundEs,
                "A missing Spanish key must NOT take the English text");

        String codeInvalidEs = parsedEs.getString("linking.code-invalid");
        assertNotNull(codeInvalidEs, "linking.code-invalid must be present in messages_es.yml");
        assertEquals(bundledEs.getString("linking.code-invalid"), codeInvalidEs,
                "A missing Spanish key must take the bundled Spanish text");
        assertNotEquals(bundledEn.getString("linking.code-invalid"), codeInvalidEs,
                "A missing Spanish key must NOT take the English text");

        // Verify runtime loader.messages() provides the Spanish text
        Messages messages = loader.messages();
        String plainResident = messages.plain("general.resident-not-found", Map.of("resident", "Carlos"));
        assertTrue(plainResident.contains("No existe ning"),
                "Runtime message must be in Spanish: " + plainResident);
        assertFalse(plainResident.contains("There is no resident"),
                "Runtime message must not be in English: " + plainResident);

        // Warning logged for messages_es.yml
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("messages_es.yml: added missing key")),
                "Warning must be logged for messages_es.yml: " + warnings);
    }

    /**
     * Requirement: A whole section deleted is restored.
     */
    @Test
    void wholeSectionDeletedIsRestored() throws Exception {
        String bundled;
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            assertNotNull(in);
            bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Delete the entire help section including its comments
        List<String> lines = new ArrayList<>(List.of(bundled.split("\\r?\\n", -1)));
        int helpCommentIdx = -1;
        int statusSectionIdx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("/dt help, listed per command")) {
                helpCommentIdx = i;
            }
            if (lines.get(i).startsWith("status:")) {
                statusSectionIdx = i;
            }
        }
        assertTrue(helpCommentIdx != -1 && statusSectionIdx != -1, "help and status sections must be located");
        lines.subList(helpCommentIdx, statusSectionIdx).clear();

        String ownerWithoutHelp = String.join("\n", lines);
        // Not contains("help:"): the embed section carries a help-help key, and a
        // substring check matches it, so the precondition passed on a file that still
        // had the section. Only a line that STARTS the section counts.
        assertFalse(ownerWithoutHelp.lines().anyMatch(l -> l.startsWith("help:")),
                "Precondition: help section must be absent");

        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, ownerWithoutHelp, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String restored = Files.readString(enPath, StandardCharsets.UTF_8);

        // Restored section header, comments and keys
        assertTrue(restored.contains("help:"), "help section header must be restored");
        assertTrue(restored.contains("# /dt help, listed per command so a server owner can reword any single line."),
                "Section comment above help must be restored");
        assertTrue(restored.contains("description: \"DiscordTowny in-game commands\""),
                "help.description must be restored");
        assertTrue(restored.contains("cmd-help: \"&f/dt help &7— show this list.\""),
                "help.cmd-help must be restored");

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(restored);
        assertTrue(parsed.isConfigurationSection("help"), "help must be a valid configuration section");
        assertEquals("DiscordTowny in-game commands", parsed.getString("help.description"));
        assertEquals("&f/dt help &7— show this list.", parsed.getString("help.cmd-help"));

        // Verify every key in bundled help section is restored
        YamlConfiguration bundledParsed = new YamlConfiguration();
        bundledParsed.loadFromString(bundled);
        for (String k : bundledParsed.getConfigurationSection("help").getKeys(false)) {
            String fullKey = "help." + k;
            assertNotNull(parsed.getString(fullKey), fullKey + " must be restored in help section");
            assertEquals(bundledParsed.getString(fullKey), parsed.getString(fullKey));
        }

        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("messages_en.yml: added missing keys:")
                && w.contains("help.description")),
                "Warning must report restored keys: " + warnings);
    }

    /**
     * Requirement: A file with a syntax error is left byte-for-byte identical.
     */
    @Test
    void fileWithSyntaxErrorIsLeftByteForByteIdentical() throws Exception {
        String invalidYaml = ""
                + "prefix: '&8[&bDiscordTowny&8] &r'\n"
                + "general:\n"
                + "  broken_list: [unclosed, bracket\n"
                + "  unquoted_colons: :: invalid yaml syntax ::\n"
                + "  quote: \"unclosed string quote\n";

        byte[] originalBytes = invalidYaml.getBytes(StandardCharsets.UTF_8);
        Path enPath = folder.resolve("messages_en.yml");
        Files.write(enPath, originalBytes);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        byte[] afterBytes = Files.readAllBytes(enPath);

        // Byte-for-byte identical assertion
        assertArrayEquals(originalBytes, afterBytes,
                "A file with a syntax error must be left byte-for-byte identical");

        // Ensure no leftover temp files
        try (var stream = Files.list(folder)) {
            List<Path> tmpFiles = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(tmpFiles.isEmpty(), "No temp files should remain: " + tmpFiles);
        }

        // Fallback warning applies
        assertTrue(warnings.stream().anyMatch(w ->
                w.contains("messages_en.yml: expected a readable file with valid YAML syntax; falling back to bundled English")),
                "Syntax error must trigger the fallback warning: " + warnings);

        // Messages fall back to bundled English
        Messages messages = loader.messages();
        assertTrue(messages.plain("general.no-permission", Map.of()).contains("You do not have permission"),
                "Messages must fall back to bundled English when file has syntax error");
    }

    /**
     * Requirement: A file already complete is not rewritten at all.
     */
    @Test
    void fileAlreadyCompleteIsNotRewrittenAtAll() throws Exception {
        byte[] bundledBytes;
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            assertNotNull(in);
            bundledBytes = in.readAllBytes();
        }

        Path enPath = folder.resolve("messages_en.yml");
        Files.write(enPath, bundledBytes);

        // Set last modified time to 10 minutes in the past
        FileTime pastTime = FileTime.from(Instant.now().minusSeconds(600));
        Files.setLastModifiedTime(enPath, pastTime);

        FileTime timeBeforeLoad = Files.getLastModifiedTime(enPath);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        FileTime timeAfterLoad = Files.getLastModifiedTime(enPath);
        byte[] afterBytes = Files.readAllBytes(enPath);

        assertEquals(timeBeforeLoad.toMillis(), timeAfterLoad.toMillis(),
                "File last-modified timestamp must not change if file is complete");
        assertArrayEquals(bundledBytes, afterBytes,
                "File content must remain completely identical");

        assertTrue(warnings.stream().noneMatch(w -> w.contains("messages_en.yml: added missing")),
                "No missing keys warning should be emitted when file is already complete: " + warnings);
    }

    @Test
    void singleMissingKeyReportsSingularFormat() throws Exception {
        String bundled;
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            assertNotNull(in);
            bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> lines = new ArrayList<>(List.of(bundled.split("\\r?\\n", -1)));
        lines.removeIf(line -> line.stripLeading().startsWith("resident-not-found:"));

        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, String.join("\n", lines), StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        assertTrue(warnings.stream().anyMatch(w -> w.equals("messages_en.yml: added missing key general.resident-not-found")),
                "Singular key report must format as 'key <name>': " + warnings);
    }

    @Test
    void preservesWindowsCrlfLineEndings() throws Exception {
        String crlfContent = "# Windows CRLF header\r\nprefix: \"[DT] \"\r\ngeneral:\r\n  no-permission: \"No perm\"\r\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, crlfContent, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("messages_en.yml: added missing")),
                "Must perform an actual merge addition: " + warnings);
        assertTrue(merged.contains("resident-not-found:"), "Missing key must be added");
        assertTrue(merged.contains("\r\n"), "Merged content must preserve CRLF line separators");
        String strippedOfCrlf = merged.replace("\r\n", "");
        assertFalse(strippedOfCrlf.contains("\n"), "All line breaks must be CRLF: " + merged);
        assertTrue(merged.contains("# Windows CRLF header\r\n"), "Original header preserved");
        assertTrue(merged.contains("no-permission: \"No perm\"\r\n"), "Original key preserved");
    }

    @Test
    void invariantsTownNationResidentStayUntranslatedInBothCatalogs() throws Exception {
        // 1. Check bundled catalogs: placeholders and prose
        for (String lang : List.of("en", "es")) {
            String fileName = "messages_" + lang + ".yml";
            YamlConfiguration config = new YamlConfiguration();
            try (var in = getClass().getResourceAsStream("/" + fileName)) {
                assertNotNull(in);
                config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            for (String key : config.getKeys(true)) {
                if (config.isString(key)) {
                    String text = config.getString(key);
                    assertFalse(text.contains("{ciudad}"), fileName + " key " + key + " has translated placeholder {ciudad}");
                    assertFalse(text.contains("{nacion}"), fileName + " key " + key + " has translated placeholder {nacion}");
                    assertFalse(text.contains("{residente}"), fileName + " key " + key + " has translated placeholder {residente}");
                }
            }
        }

        // Check bundled Spanish prose terminology
        YamlConfiguration esBundled = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/messages_es.yml")) {
            assertNotNull(in);
            esBundled.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        assertTrue(esBundled.getString("general.resident-not-found").contains("resident"));
        assertFalse(esBundled.getString("general.resident-not-found").contains("residente"));
        assertTrue(esBundled.getString("general.no-towns-found").contains("town"));
        assertFalse(esBundled.getString("general.no-towns-found").contains("ciudad"));
        assertFalse(esBundled.getString("general.no-towns-found").contains("pueblo"));
        assertTrue(esBundled.getString("space.already-exists").contains("town"));
        assertFalse(esBundled.getString("space.already-exists").contains("ciudad"));

        // 2. Exercise the merger and test restored disk and runtime output
        configYaml.set("language", "es");
        configYaml.save(folder.resolve("config.yml").toFile());

        String esOwnerContent = "prefix: \"&8[&bDT&8] &r\"\ngeneral:\n  no-permission: \"&cNo permiso\"\n";
        Path esPath = folder.resolve("messages_es.yml");
        Files.writeString(esPath, esOwnerContent, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        YamlConfiguration parsedEs = new YamlConfiguration();
        parsedEs.load(esPath.toFile());

        String restoredResidentMsg = parsedEs.getString("general.resident-not-found");
        assertNotNull(restoredResidentMsg);
        assertTrue(restoredResidentMsg.contains("resident"), "Restored message must use 'resident': " + restoredResidentMsg);
        assertFalse(restoredResidentMsg.contains("residente"), "Restored message must not use 'residente': " + restoredResidentMsg);
        assertTrue(restoredResidentMsg.contains("{resident}"), "Restored message must have '{resident}': " + restoredResidentMsg);

        String restoredSpaceExists = parsedEs.getString("space.already-exists");
        assertNotNull(restoredSpaceExists);
        assertTrue(restoredSpaceExists.contains("town"), "Restored message must use 'town': " + restoredSpaceExists);
        assertFalse(restoredSpaceExists.contains("ciudad"), "Restored message must not use 'ciudad': " + restoredSpaceExists);

        // Runtime rendering check
        Messages messages = loader.messages();
        String plainResident = messages.plain("general.resident-not-found", Map.of("resident", "Mario"));
        assertTrue(plainResident.contains("resident"), "Runtime text must preserve 'resident'");
        assertFalse(plainResident.contains("residente"), "Runtime text must not translate to 'residente'");
    }

    @Test
    void secondLoadDoesNotRewriteOrEmitDuplicateWarnings() throws Exception {
        String partial = "prefix: \"[DT] \"\ngeneral:\n  no-permission: \"No perm\"\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, partial, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        long firstLoadWarnings = warnings.stream().filter(w -> w.startsWith("messages_en.yml: added missing")).count();
        assertEquals(1, firstLoadWarnings, "Initial merge should warn once");

        // Verify file is complete after first load
        YamlConfiguration parsedAfterFirst = new YamlConfiguration();
        parsedAfterFirst.load(enPath.toFile());
        YamlConfiguration bundled = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            assertNotNull(in);
            bundled.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        for (String k : bundled.getKeys(true)) {
            if (bundled.isString(k)) {
                assertNotNull(parsedAfterFirst.getString(k), "File must be complete after first load: " + k);
            }
        }

        // Age timestamp by 5 seconds to detect any rewrite
        FileTime pastTime = FileTime.from(Instant.now().minusSeconds(5));
        Files.setLastModifiedTime(enPath, pastTime);
        FileTime timeBeforeSecondLoad = Files.getLastModifiedTime(enPath);
        byte[] bytesAfterFirstLoad = Files.readAllBytes(enPath);

        // Reloading again should see complete file and not rewrite or re-warn
        loader.load();

        long secondLoadWarnings = warnings.stream().filter(w -> w.startsWith("messages_en.yml: added missing")).count();
        assertEquals(1, secondLoadWarnings, "Second load must not emit duplicate missing keys warning");

        FileTime timeAfterSecondLoad = Files.getLastModifiedTime(enPath);
        assertEquals(timeBeforeSecondLoad.toMillis(), timeAfterSecondLoad.toMillis(),
                "Second load must not rewrite the file or update its timestamp");
        assertArrayEquals(bytesAfterFirstLoad, Files.readAllBytes(enPath),
                "File bytes must remain completely identical across second load");
    }

    @Test
    void sectionHeaderInsideQuotedValueIsNotMistakenForSection() throws Exception {
        String fixture = ""
                + "prefix: '[DT] '\n"
                + "owner-note: 'start\n"
                + "general:\n"
                + "end'\n"
                + "general:\n"
                + "  no-permission: 'Mine'\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);

        // Per YAML 1.2 §6.5 / §7.3.2, multiline single-quoted scalars fold line breaks into spaces
        assertEquals("start general: end", parsed.getString("owner-note"),
                "Scalar value must not be corrupted by inserted section keys");
        assertTrue(merged.contains("owner-note: 'start\ngeneral:\nend'"),
                "Literal owner-note scalar must remain untouched in merged text");
        assertEquals("Mine", parsed.getString("general.no-permission"),
                "Existing section value must be preserved");
        assertNotNull(parsed.getString("general.resident-not-found"),
                "Missing key must be inserted into the real general section");
        assertFalse(parsed.getString("general.resident-not-found").isBlank(),
                "Missing key in real general section must resolve to text");

        int noteEndIdx = merged.indexOf("end'");
        int realGeneralIdx = merged.indexOf("general:\n  no-permission:");
        int addedKeyIdx = merged.indexOf("resident-not-found:");

        assertTrue(realGeneralIdx > noteEndIdx, "Real general section must be after owner-note");
        assertTrue(addedKeyIdx > realGeneralIdx, "Added key must be inside real general section, not in owner-note");

        // plain() renders the message with the prefix the owner file declares, so the
        // raw scalar is not what comes back. The parsed value above is the untouched one.
        assertEquals("[DT] Mine", loader.messages().plain("general.no-permission", Map.of()));
        assertNotNull(loader.messages().plain("general.resident-not-found", Map.of("resident", "Player1")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("general.resident-not-found")),
                "Missing key addition must be reported");
    }

    @Test
    void unindentedQuotedContinuationDoesNotEndScanEarly() throws Exception {
        String fixture = ""
                + "prefix: '[DT] '\n"
                + "general:\n"
                + "  no-permission: 'start\n"
                + "continuation'\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);

        // Per YAML 1.2 §6.5 / §7.3.2, multiline single-quoted scalars fold line breaks into spaces
        assertEquals("start continuation", parsed.getString("general.no-permission"),
                "Multiline quoted value must remain intact without insertions inside it");
        assertTrue(merged.contains("  no-permission: 'start\ncontinuation'"),
                "Literal multiline quoted scalar must remain uncorrupted in merged file");
        assertNotNull(parsed.getString("general.resident-not-found"),
                "Missing key must be added");
        assertFalse(parsed.getString("general.resident-not-found").isBlank(),
                "Missing key must resolve to text");

        int continuationIdx = merged.indexOf("continuation'");
        int addedKeyIdx = merged.indexOf("resident-not-found:");
        assertTrue(addedKeyIdx > continuationIdx,
                "Added key must be placed after the quoted continuation, not inside it");

        assertEquals("[DT] start continuation", loader.messages().plain("general.no-permission", Map.of()));
        assertNotNull(loader.messages().plain("general.resident-not-found", Map.of("resident", "Player1")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("general.resident-not-found")),
                "Added key must be reported in warnings");
    }

    @Test
    void blockScalarWithTrailingBlankLinesPreservesBlankLines() throws Exception {
        String fixture = "prefix: '[DT] '\ngeneral:\n  no-permission: |+\n    Owner line\n\n\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);

        YamlConfiguration original = new YamlConfiguration();
        original.loadFromString(fixture);

        // Per YAML 1.2 §8.1.1.2, keep block scalar |+ preserves all trailing newlines (3 newlines = "Owner line\n\n\n")
        assertEquals(original.getString("general.no-permission"), parsed.getString("general.no-permission"),
                "Block scalar trailing blank lines must be preserved exactly without change");
        assertEquals("Owner line\n\n\n", parsed.getString("general.no-permission"),
                "Block scalar trailing blank lines must match exact YAML keep-chomping resolution");
        assertNotNull(parsed.getString("general.resident-not-found"),
                "Missing key must be added");
        assertFalse(parsed.getString("general.resident-not-found").isBlank(),
                "Missing key must resolve to text");

        int ownerLineIdx = merged.indexOf("Owner line");
        int addedKeyIdx = merged.indexOf("resident-not-found:");
        String between = merged.substring(ownerLineIdx + "Owner line".length(), addedKeyIdx);
        assertTrue(between.contains("\n\n\n"),
                "Trailing empty lines must not be stripped or split by insertion: " + between);
        assertTrue(merged.contains("no-permission: |+\n    Owner line\n\n\n"),
                "Original block scalar text and chomping indicator must be preserved byte-for-byte");
    }

    @Test
    void blockScalarWithTwoTrailingNewlinesPreservesBothLines() throws Exception {
        // Example C from review with 2 newlines
        String fixture = "prefix: '[DT] '\ngeneral:\n  no-permission: |+\n    Owner line\n\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);

        assertEquals("Owner line\n\n", parsed.getString("general.no-permission"),
                "Block scalar with |+ and 2 newlines preserves both newlines per Example C");
        assertNotNull(parsed.getString("general.resident-not-found"));
    }

    @Test
    void presentNullOrEmptyKeyIsNotTreatedAsAbsent() throws Exception {
        String fixture = ""
                + "prefix: '[DT] '\n"
                + "general:\n"
                + "  no-permission:\n"
                + "  towny-read-failed: null\n"
                + "  no-towns-found: ~\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        // Check key definition lines without false-positive matching against other sections' keys (e.g. sync.cause-towny-read-failed)
        long noPermCount = merged.lines().filter(l -> l.stripLeading().startsWith("no-permission:")).count();
        long readFailedCount = merged.lines().filter(l -> l.stripLeading().startsWith("towny-read-failed:")).count();
        long noTownsCount = merged.lines().filter(l -> l.stripLeading().startsWith("no-towns-found:")).count();

        assertEquals(1, noPermCount, "Present empty no-permission must not be duplicated");
        assertEquals(1, readFailedCount, "Present null towny-read-failed must not be duplicated");
        assertEquals(1, noTownsCount, "Present tilde no-towns-found must not be duplicated");

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);
        assertNotNull(parsed.getString("general.resident-not-found"),
                "Actually missing keys must still be added");
        assertFalse(parsed.getString("general.resident-not-found").isBlank(),
                "Actually missing keys must resolve to valid text");

        assertTrue(warnings.stream().anyMatch(w -> w.contains("general.resident-not-found")),
                "Actually missing key must be reported in warnings");
        assertTrue(warnings.stream().noneMatch(w -> w.contains("general.no-permission")
                        || w.contains("general.towny-read-failed")
                        || w.contains("general.no-towns-found")),
                "Present null or empty keys must never be reported as added missing keys: " + warnings);
    }

    @Test
    void emptySectionIsNotAppendedAsDuplicateSection() throws Exception {
        String fixture = "prefix: '[DT] '\ngeneral:\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        long generalCount = merged.lines().filter(l -> l.equals("general:")).count();
        assertEquals(1, generalCount, "general section must appear exactly once, not duplicated");

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);
        assertNotNull(parsed.getString("general.no-permission"), "Missing keys must be added under general");
        assertNotNull(parsed.getString("general.resident-not-found"), "Missing keys must be added under general");
    }

    @Test
    void duplicateSectionAbortsMergeLeavesFileUntouchedAndWarns() throws Exception {
        String duplicateFixture = ""
                + "prefix: '[DT] '\n"
                + "general:\n"
                + "  owner-custom-key: 'First block'\n"
                + "general:\n"
                + "  no-permission: 'Active block'\n";
        byte[] originalBytes = duplicateFixture.getBytes(StandardCharsets.UTF_8);
        Path enPath = folder.resolve("messages_en.yml");
        Files.write(enPath, originalBytes);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        byte[] afterBytes = Files.readAllBytes(enPath);
        assertArrayEquals(originalBytes, afterBytes,
                "A file with duplicate sections must be left byte-for-byte identical");

        assertTrue(warnings.stream().anyMatch(w -> w.contains("messages_en.yml: duplicate section 'general'")),
                "Warning must report duplicate section: " + warnings);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("added missing")),
                "No added missing keys report should be emitted on duplicate section abort");
    }

    @Test
    void escapedOrQuotedSectionHeaderIsHandledCorrectly() throws Exception {
        String fixture = ""
                + "prefix: '[DT] '\n"
                + "\"\\x67eneral\":\n"
                + "  no-permission: 'Custom'\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        long generalCount = merged.lines().filter(l -> l.contains("eneral\":") || l.equals("general:")).count();
        assertEquals(1, generalCount, "Escaped section header must not cause a second general section: " + merged);

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);
        assertEquals("Custom", parsed.getString("general.no-permission"));
        assertNotNull(parsed.getString("general.resident-not-found"),
                "Missing key must be inserted under escaped section header");
    }

    @Test
    void preservesMixedLineEndings() throws Exception {
        String mixed = "prefix: '[DT] '\r\ngeneral:\n  no-permission: 'Mine'\r\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, mixed, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        // Line 0 was CRLF, Line 1 was LF, Line 2 was CRLF
        assertTrue(merged.startsWith("prefix: '[DT] '\r\n"), "Line 0 CRLF must be preserved");
        assertTrue(merged.contains("\r\ngeneral:\n"), "Line 1 LF must be preserved");
        assertTrue(merged.contains("\n  no-permission: 'Mine'\r\n"), "Line 2 CRLF must be preserved");
        assertTrue(merged.contains("resident-not-found:"), "Missing key was added");
    }

    @Test
    void fileWithNoFinalNewlineDoesNotGainOneWhenInsertionIsInMiddle() throws Exception {
        String bundled;
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            assertNotNull(in);
            bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }

        // To test that an insertion in the middle does not add an EOF newline,
        // all sections must be present so that no absent sections are appended at EOF.
        // We remove general.resident-not-found so that the insertion lands in the middle (under general).
        String withoutResident = bundled.replace("  resident-not-found: \"&cThere is no resident named &f{resident}&c.\"\n", "");

        // Relocate linking to the end with link-success: 'OK' as the final line without a newline,
        // ensuring no sections are absent, linking has all keys, and no insertion occurs at EOF.
        int linkingStart = withoutResident.indexOf("linking:\n");
        int spaceStart = withoutResident.indexOf("space:\n");
        String linkingSection = withoutResident.substring(linkingStart, spaceStart);
        String withoutLinking = withoutResident.substring(0, linkingStart) + withoutResident.substring(spaceStart);

        String linkSuccessOrig = "  link-success: \"&aAccount successfully linked.\"\n";
        String linkingWithoutSuccess = linkingSection.replace(linkSuccessOrig, "");
        String finalLinking = linkingWithoutSuccess.stripTrailing() + "\n  link-success: 'OK'";

        String noNewline = withoutLinking.stripTrailing() + "\n\n" + finalLinking;

        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, noNewline, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        assertTrue(merged.contains("resident-not-found:"), "Missing key must be added inside general");
        assertFalse(merged.endsWith("\n"), "File without trailing newline must not gain one when insertion is in middle");
        assertFalse(merged.endsWith("\r"), "File without trailing newline must not gain carriage return");
        assertTrue(merged.endsWith("link-success: 'OK'"), "File must end with exact original last line");
    }

    @Test
    void atomicMoveNotSupportedAbandonsMergeAndWarns() throws Exception {
        String fixture = "prefix: '[DT] '\ngeneral:\n  no-permission: 'Mine'\n";
        byte[] originalBytes = fixture.getBytes(StandardCharsets.UTF_8);
        Path enPath = folder.resolve("messages_en.yml");
        Files.write(enPath, originalBytes);

        YamlConfigLoader failingLoader = new YamlConfigLoader(folder, warnings::add, (source, target) -> {
            throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "Atomic move unsupported");
        });

        failingLoader.load();

        byte[] afterBytes = Files.readAllBytes(enPath);
        assertArrayEquals(originalBytes, afterBytes,
                "When ATOMIC_MOVE is unsupported, target file must be left untouched");

        assertTrue(warnings.stream().anyMatch(w -> w.contains("messages_en.yml: atomic move not supported; catalog merge abandoned")),
                "Warning must be emitted for unsupported atomic move: " + warnings);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("added missing")),
                "No added missing keys warning should be emitted when merge is abandoned");

        try (var stream = Files.list(folder)) {
            List<Path> tmpFiles = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(tmpFiles.isEmpty(), "Temporary files must be cleaned up on abandonment: " + tmpFiles);
        }
    }

    @Test
    void writeFailureLeavesTargetUntouchedAndCleansUpTempFile() throws Exception {
        String fixture = "prefix: '[DT] '\ngeneral:\n  no-permission: 'Mine'\n";
        byte[] originalBytes = fixture.getBytes(StandardCharsets.UTF_8);
        Path enPath = folder.resolve("messages_en.yml");
        Files.write(enPath, originalBytes);

        YamlConfigLoader failingLoader = new YamlConfigLoader(folder, warnings::add, (source, target) -> {
            throw new IOException("Simulated disk failure during move");
        });

        failingLoader.load();

        byte[] afterBytes = Files.readAllBytes(enPath);
        assertArrayEquals(originalBytes, afterBytes,
                "On write failure, target file must be left untouched");

        assertTrue(warnings.stream().noneMatch(w -> w.contains("added missing")),
                "No success report should be emitted after write failure");

        try (var stream = Files.list(folder)) {
            List<Path> tmpFiles = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
            assertTrue(tmpFiles.isEmpty(), "Temporary files must be cleaned up on failure: " + tmpFiles);
        }
    }

    @Test
    void legalTabsInQuotedValuesArePreserved() throws Exception {
        String fixture = "prefix: \"[DT]\\t\"\ngeneral:\n  no-permission: \"No\\tpermission\"\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);

        assertTrue(parsed.getString("prefix").contains("\t"), "Legal tab in prefix must be preserved");
        assertTrue(parsed.getString("general.no-permission").contains("\t"), "Legal tab in value must be preserved");
        assertNotNull(parsed.getString("general.resident-not-found"), "Missing key must be restored");
    }

    @Test
    void unusualIndentationFourSpacesIsRespected() throws Exception {
        String fixture = "prefix: '[DT] '\ngeneral:\n    no-permission: 'Custom'\n";
        Path enPath = folder.resolve("messages_en.yml");
        Files.writeString(enPath, fixture, StandardCharsets.UTF_8);

        loader = new YamlConfigLoader(folder, warnings::add);
        loader.load();

        String merged = Files.readString(enPath, StandardCharsets.UTF_8);

        assertTrue(merged.contains("    resident-not-found:"),
                "Inserted keys must adopt the section's 4-space indentation: " + merged);

        YamlConfiguration parsed = new YamlConfiguration();
        parsed.loadFromString(merged);
        assertEquals("Custom", parsed.getString("general.no-permission"));
        assertNotNull(parsed.getString("general.resident-not-found"));
    }
}
