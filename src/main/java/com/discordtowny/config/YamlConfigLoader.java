package com.discordtowny.config;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.DumperOptions.ScalarStyle;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;
import org.yaml.snakeyaml.nodes.AnchorNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Explicit loading: each load reloads both files and publishes only if everything is valid.
 *
 * <p>The wiring provides the data folder and logger::warning. On startup or
 * reload, it must catch ConfigException and keep the plugin degraded if it
 * lacks a valid configuration. A rejected reload retains previous texts;
 * the consumer also retains its previous PluginConfig.
 *
 * <p>config-version is file metadata, not a PluginConfig field.
 */
public final class YamlConfigLoader implements ConfigLoader {
    private final Path dataFolder;
    private final Consumer<String> warning;
    private final Map<String, String> bundledEnglish;
    private final ReloadableMessages messages;
    // The class is final, so a test cannot override a method to inject a failing move.
    private final Mover mover;
    private final TempWriter tempWriter;

    public YamlConfigLoader(Path dataFolder, Consumer<String> warning) {
        this(dataFolder, warning, (source, target) ->
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    // The constructor already merges the catalogs, so a seam installed afterwards
    // would arrive too late to see the write it is meant to make fail.
    YamlConfigLoader(Path dataFolder, Consumer<String> warning, Mover mover) {
        this(dataFolder, warning, mover, (path, content) -> Files.writeString(path, content, StandardCharsets.UTF_8));
    }

    YamlConfigLoader(Path dataFolder, Consumer<String> warning, Mover mover, TempWriter tempWriter) {
        this.mover = mover;
        this.tempWriter = tempWriter;
        this.dataFolder = dataFolder;
        this.warning = warning;
        this.bundledEnglish = loadBundledEnglish();
        saveDefaultMessages();
        this.messages = new ReloadableMessages(new YamlMessages(Map.of(), bundledEnglish, "messages_en.yml", warning));
    }

    public void saveDefaultMessages() {
        saveDefaultFile("messages_en.yml");
        saveDefaultFile("messages_es.yml");
    }

    private void saveDefaultFile(String name) {
        Path target = dataFolder.resolve(name);
        if (Files.notExists(target)) {
            try {
                Files.createDirectories(dataFolder);
                try (var in = getClass().getResourceAsStream("/" + name)) {
                    if (in != null) {
                        Files.copy(in, target);
                    }
                }
            } catch (IOException ignored) {
            }
            return;
        }
        mergeDefaultFile(name, target);
    }

    private void mergeDefaultFile(String resourceName, Path target) {
        String ownerContent;
        try {
            ownerContent = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }

        Yaml yaml = new Yaml();
        Node ownerRoot;
        try {
            ownerRoot = yaml.compose(new StringReader(ownerContent));
        } catch (Exception e) {
            // A file that fails to parse is left byte-for-byte unchanged.
            return;
        }

        if (ownerRoot != null && !(ownerRoot instanceof MappingNode)) {
            return;
        }

        // R3: detect anchors, aliases, and merge keys.
        // Anchors, aliases and merge keys cannot be reasoned about with marks; refuse the merge.
        try {
            for (Event event : yaml.parse(new StringReader(ownerContent))) {
                if (event instanceof AliasEvent) {
                    warning.accept(resourceName + ": anchors, aliases or merge keys present; catalog merge abandoned");
                    return;
                }
                if (event instanceof NodeEvent nodeEvent && nodeEvent.getAnchor() != null) {
                    warning.accept(resourceName + ": anchors, aliases or merge keys present; catalog merge abandoned");
                    return;
                }
            }
        } catch (Exception ignored) {
        }
        if (hasAnchorAliasOrMergeKey(ownerRoot)) {
            warning.accept(resourceName + ": anchors, aliases or merge keys present; catalog merge abandoned");
            return;
        }

        // F5: detect duplicate top-level sections and duplicate keys
        Map<String, TopLevelSection> ownerSections = new LinkedHashMap<>();
        if (ownerRoot instanceof MappingNode ownerMapping) {
            for (NodeTuple tuple : ownerMapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode keyNode) {
                    String secName = keyNode.getValue();
                    if (ownerSections.containsKey(secName)) {
                        warning.accept(resourceName + ": duplicate section '" + secName + "'; catalog merge aborted");
                        return;
                    }
                    TopLevelSection sec = new TopLevelSection(tuple, keyNode, tuple.getValueNode());
                    if (sec.duplicateKey != null) {
                        warning.accept(resourceName + ": duplicate key '" + secName + "." + sec.duplicateKey + "'; catalog merge aborted");
                        return;
                    }
                    ownerSections.put(secName, sec);
                }
            }
        }

        String bundledContent;
        try (var in = getClass().getResourceAsStream("/" + resourceName)) {
            if (in == null) return;
            bundledContent = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }

        Node bundledRoot;
        try {
            bundledRoot = yaml.compose(new StringReader(bundledContent));
        } catch (Exception e) {
            return;
        }
        if (!(bundledRoot instanceof MappingNode bundledMapping)) {
            return;
        }

        // F3: identify missing keys using node tree presence, not Bukkit contains()
        List<String> missingKeys = new ArrayList<>();
        List<String> absentSections = new ArrayList<>();
        Map<String, List<String>> missingKeysByExistingSection = new LinkedHashMap<>();

        for (NodeTuple secTuple : bundledMapping.getValue()) {
            if (!(secTuple.getKeyNode() instanceof ScalarNode secKeyNode)) continue;
            String secName = secKeyNode.getValue();
            Node secVal = secTuple.getValueNode();

            if (secVal instanceof MappingNode childMapping) {
                TopLevelSection ownerSec = ownerSections.get(secName);
                if (ownerSec == null) {
                    absentSections.add(secName);
                    for (NodeTuple childTuple : childMapping.getValue()) {
                        if (childTuple.getKeyNode() instanceof ScalarNode childKeyNode) {
                            missingKeys.add(secName + "." + childKeyNode.getValue());
                        }
                    }
                } else if (!ownerSec.isMapping() && !ownerSec.isEmptyContainer()) {
                    // R2: Section name in owner has a nonempty scalar value, not a mapping or empty container
                    warning.accept(resourceName + ": section '" + secName + "' has a non-mapping scalar value; catalog merge aborted");
                    return;
                } else {
                    Set<String> ownerKeys = ownerSec.keys;
                    for (NodeTuple childTuple : childMapping.getValue()) {
                        if (childTuple.getKeyNode() instanceof ScalarNode childKeyNode) {
                            String childKey = childKeyNode.getValue();
                            if (!ownerKeys.contains(childKey)) {
                                missingKeys.add(secName + "." + childKey);
                                missingKeysByExistingSection
                                        .computeIfAbsent(secName, k -> new ArrayList<>())
                                        .add(childKey);
                            }
                        }
                    }
                }
            } else {
                if (!ownerSections.containsKey(secName)) {
                    missingKeys.add(secName);
                }
            }
        }

        if (missingKeys.isEmpty()) {
            return;
        }

        // R6: YAML line breaks that splitIntoLines does not recognize (U+0085, U+2028, U+2029) make marks untrustworthy
        if (hasUnsupportedLineBreak(ownerContent)) {
            warning.accept(resourceName + ": unsupported line break; catalog merge abandoned");
            return;
        }

        // R7: detect root mapping keys containing '.' which create ambiguity between YAML mapping keys and Bukkit paths
        String dottedKey = findRootKeyWithDot(ownerRoot);
        if (dottedKey != null) {
            warning.accept(resourceName + ": key '" + dottedKey + "' contains '.'; catalog merge abandoned");
            return;
        }

        BundledCatalog bundledCatalog = new BundledCatalog(bundledContent, bundledMapping);
        String updatedContent = mergeCatalogText(ownerContent, ownerSections, bundledCatalog,
                missingKeys, absentSections, missingKeysByExistingSection);

        if (updatedContent == null || updatedContent.equals(ownerContent)) {
            return;
        }

        YamlConfiguration verification = new YamlConfiguration();
        try {
            verification.loadFromString(updatedContent);
        } catch (InvalidConfigurationException | RuntimeException e) {
            return;
        }

        // Oracle: verify that every key present in original exists in candidate with an equal value
        YamlConfiguration originalConfig = new YamlConfiguration();
        try {
            originalConfig.loadFromString(ownerContent);
        } catch (InvalidConfigurationException | RuntimeException e) {
            warning.accept(resourceName + ": original catalog could not be parsed; catalog merge abandoned");
            return;
        }

        Set<String> scheduledEmptySectionFills = new HashSet<>();
        for (String secName : missingKeysByExistingSection.keySet()) {
            TopLevelSection ownerSec = ownerSections.get(secName);
            if (ownerSec != null && (ownerSec.isEmptyContainer() || ownerSec.keys.isEmpty())) {
                scheduledEmptySectionFills.add(secName);
            }
        }

        if (!verifyPreservation(originalConfig, verification, ownerSections, scheduledEmptySectionFills)) {
            warning.accept(resourceName + ": catalog merge verification failed; original untouched");
            return;
        }

        // F5: report only additions actually made and verified in the written result
        List<String> verifiedAdditions = new ArrayList<>();
        for (String key : missingKeys) {
            if (verification.contains(key) || verification.isSet(key)) {
                verifiedAdditions.add(key);
            }
        }
        if (verifiedAdditions.size() != missingKeys.size() || verifiedAdditions.isEmpty()) {
            return;
        }

        try {
            writeAtomically(target, updatedContent, resourceName);
        } catch (IOException e) {
            return;
        }

        String report = verifiedAdditions.size() == 1
                ? "key " + verifiedAdditions.getFirst()
                : "keys: " + String.join(", ", verifiedAdditions);
        warning.accept(resourceName + ": added missing " + report);
    }

    void writeAtomically(Path target, String content, String resourceName) throws IOException {
        Path tempFile = Files.createTempFile(dataFolder, target.getFileName().toString(), ".tmp");
        try {
            tempWriter.write(tempFile, content);
            moveFile(tempFile, target, resourceName);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    void moveFile(Path source, Path target, String resourceName) throws IOException {
        try {
            mover.move(source, target);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
            // F4: if ATOMIC_MOVE is not supported, do not fall back to non-atomic move.
            // Abandon merge, leave original untouched, warn.
            warning.accept(resourceName + ": atomic move not supported; catalog merge abandoned");
            throw new IOException("Atomic move not supported for " + target, e);
        }
    }

    /** How the finished file replaces the original. A seam, so a test can make the move fail. */
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    /** How the temporary file is written. A seam, so a test can make the temporary write fail. */
    interface TempWriter {
        void write(Path path, String content) throws IOException;
    }



    private String mergeCatalogText(String ownerContent,
                                     Map<String, TopLevelSection> ownerSections,
                                     BundledCatalog bundledCatalog,
                                     List<String> missingKeys,
                                     List<String> absentSections,
                                     Map<String, List<String>> missingKeysByExistingSection) {
        List<Line> originalLines = splitIntoLines(ownerContent);
        String defaultLineBreak = detectDefaultLineBreak(originalLines);

        Map<Integer, List<Line>> insertions = new TreeMap<>();

        // 1. Missing top-level keys (e.g. prefix)
        for (String key : missingKeys) {
            if (!key.contains(".")) {
                List<Line> keyBlock = bundledCatalog.keyBlocks.get(key);
                if (keyBlock != null && !keyBlock.isEmpty()) {
                    int insertIndex = 0;
                    if (!ownerSections.isEmpty()) {
                        TopLevelSection firstSec = ownerSections.values().iterator().next();
                        int firstHeaderLine = firstSec.keyNode.getStartMark().getLine();
                        int commentStart = firstHeaderLine;
                        while (commentStart > 0 && originalLines.get(commentStart - 1).content.trim().startsWith("#")) {
                            commentStart--;
                        }
                        insertIndex = commentStart;
                    }
                    List<Line> formatted = new ArrayList<>();
                    for (Line l : keyBlock) {
                        formatted.add(new Line(l.content, defaultLineBreak));
                    }
                    formatted.add(new Line("", defaultLineBreak));
                    insertions.computeIfAbsent(insertIndex, k -> new ArrayList<>()).addAll(formatted);
                }
            }
        }

        // 2. Missing keys in existing sections
        for (Map.Entry<String, List<String>> entry : missingKeysByExistingSection.entrySet()) {
            String secName = entry.getKey();
            List<String> subkeys = entry.getValue();
            TopLevelSection sec = ownerSections.get(secName);
            if (sec == null) continue;

            int insertIndex;
            String sectionIndent;
            String lineBreakToUse;

            if (sec.hasChildren()) {
                NodeTuple lastChild = sec.getLastChild();
                Node valNode = lastChild.getValueNode() != null ? lastChild.getValueNode() : lastChild.getKeyNode();
                Mark endMark = valNode.getEndMark();
                int lastLine = (endMark.getColumn() == 0 && endMark.getLine() > valNode.getStartMark().getLine())
                        ? endMark.getLine() - 1
                        : endMark.getLine();
                insertIndex = lastLine + 1;
                sectionIndent = sec.detectChildIndent(originalLines);
                lineBreakToUse = (lastLine >= 0 && lastLine < originalLines.size() && !originalLines.get(lastLine).lineBreak.isEmpty())
                        ? originalLines.get(lastLine).lineBreak
                        : defaultLineBreak;
            } else {
                int headerLine = sec.keyNode.getStartMark().getLine();
                insertIndex = headerLine + 1;
                sectionIndent = "  ";
                lineBreakToUse = (headerLine >= 0 && headerLine < originalLines.size() && !originalLines.get(headerLine).lineBreak.isEmpty())
                        ? originalLines.get(headerLine).lineBreak
                        : defaultLineBreak;
            }

            List<Line> sectionInsertions = new ArrayList<>();
            for (String subkey : subkeys) {
                List<Line> keyBlock = bundledCatalog.keyBlocks.get(secName + "." + subkey);
                if (keyBlock != null) {
                    for (Line kl : keyBlock) {
                        String content = kl.content;
                        if (content.startsWith("  ") && !sectionIndent.equals("  ")) {
                            content = sectionIndent + content.substring(2);
                        }
                        sectionInsertions.add(new Line(content, lineBreakToUse));
                    }
                }
            }
            insertions.computeIfAbsent(insertIndex, k -> new ArrayList<>()).addAll(sectionInsertions);
        }

        // 3. Absent sections to append at EOF
        if (!absentSections.isEmpty()) {
            List<Line> eofInsertions = new ArrayList<>();
            for (String secName : absentSections) {
                List<Line> secBlock = bundledCatalog.sectionBlocks.get(secName);
                if (secBlock != null && !secBlock.isEmpty()) {
                    if (!eofInsertions.isEmpty()) {
                        eofInsertions.add(new Line("", defaultLineBreak));
                    }
                    for (Line l : secBlock) {
                        eofInsertions.add(new Line(l.content, defaultLineBreak));
                    }
                }
            }
            if (!eofInsertions.isEmpty()) {
                List<Line> atEof = insertions.computeIfAbsent(originalLines.size(), k -> new ArrayList<>());
                if (!atEof.isEmpty()) {
                    // There are already sibling insertions at EOF.
                    // Separate the sibling insertions from the absent sections with an empty line.
                    eofInsertions.add(0, new Line("", defaultLineBreak));
                } else if (!originalLines.isEmpty()) {
                    // No sibling insertions at EOF, but appending absent sections to the file.
                    Line last = originalLines.get(originalLines.size() - 1);
                    if (!last.content.isBlank() && !isLastNodeBlockScalar(ownerSections)) {
                        eofInsertions.add(0, new Line("", defaultLineBreak));
                    }
                }
                atEof.addAll(eofInsertions);
            }
        }

        // 4. Splice lines
        if (insertions.containsKey(originalLines.size()) && !originalLines.isEmpty()) {
            Line last = originalLines.get(originalLines.size() - 1);
            if (last.lineBreak.isEmpty()) {
                originalLines.set(originalLines.size() - 1, new Line(last.content, defaultLineBreak));
            }
        }

        List<Line> resultLines = new ArrayList<>();
        for (int i = 0; i <= originalLines.size(); i++) {
            List<Line> toInsert = insertions.get(i);
            if (toInsert != null) {
                resultLines.addAll(toInsert);
            }
            if (i < originalLines.size()) {
                resultLines.add(originalLines.get(i));
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Line l : resultLines) {
            sb.append(l.content).append(l.lineBreak);
        }
        return sb.toString();
    }

    private static final class Line {
        final String content;
        final String lineBreak;

        Line(String content, String lineBreak) {
            this.content = content;
            this.lineBreak = lineBreak;
        }
    }

    private static List<Line> splitIntoLines(String text) {
        List<Line> lines = new ArrayList<>();
        int len = text.length();
        int start = 0;
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < len && text.charAt(i + 1) == '\n') {
                    lines.add(new Line(text.substring(start, i), "\r\n"));
                    i += 2;
                } else {
                    lines.add(new Line(text.substring(start, i), "\r"));
                    i++;
                }
                start = i;
            } else if (c == '\n') {
                lines.add(new Line(text.substring(start, i), "\n"));
                i++;
                start = i;
            } else {
                i++;
            }
        }
        if (start < len) {
            lines.add(new Line(text.substring(start, len), ""));
        }
        return lines;
    }

    private static String detectDefaultLineBreak(List<Line> lines) {
        for (Line line : lines) {
            if (!line.lineBreak.isEmpty()) {
                return line.lineBreak;
            }
        }
        return "\n";
    }

    static final class TopLevelSection {
        final NodeTuple tuple;
        final ScalarNode keyNode;
        final Node valueNode;
        final Set<String> keys = new HashSet<>();
        final List<NodeTuple> childTuples = new ArrayList<>();
        String duplicateKey = null;

        TopLevelSection(NodeTuple tuple, ScalarNode keyNode, Node valueNode) {
            this.tuple = tuple;
            this.keyNode = keyNode;
            this.valueNode = valueNode;
            if (valueNode instanceof MappingNode mapping) {
                for (NodeTuple child : mapping.getValue()) {
                    if (child.getKeyNode() instanceof ScalarNode childKey) {
                        String name = childKey.getValue();
                        if (!keys.add(name) && duplicateKey == null) {
                            duplicateKey = name;
                        }
                        childTuples.add(child);
                    }
                }
            }
        }

        boolean hasChildren() {
            return !childTuples.isEmpty();
        }

        boolean isMapping() {
            return valueNode instanceof MappingNode;
        }

        boolean isEmptyContainer() {
            if (valueNode instanceof MappingNode mapping) {
                return mapping.getValue().isEmpty();
            }
            if (valueNode instanceof ScalarNode scalar) {
                String val = scalar.getValue();
                return val == null || val.isEmpty() || "tag:yaml.org,2002:null".equals(scalar.getTag().getValue());
            }
            return valueNode == null;
        }

        NodeTuple getLastChild() {
            return childTuples.get(childTuples.size() - 1);
        }

        String detectChildIndent(List<Line> lines) {
            for (NodeTuple child : childTuples) {
                if (child.getKeyNode() != null && child.getKeyNode().getStartMark() != null) {
                    int lineIdx = child.getKeyNode().getStartMark().getLine();
                    if (lineIdx >= 0 && lineIdx < lines.size()) {
                        String line = lines.get(lineIdx).content;
                        int sp = 0;
                        while (sp < line.length() && line.charAt(sp) == ' ') sp++;
                        if (sp > 0) {
                            return " ".repeat(sp);
                        }
                    }
                }
            }
            return "  ";
        }
    }

    private static boolean isLastNodeBlockScalar(Map<String, TopLevelSection> ownerSections) {
        if (ownerSections == null || ownerSections.isEmpty()) {
            return false;
        }
        TopLevelSection lastSec = null;
        for (TopLevelSection sec : ownerSections.values()) {
            lastSec = sec;
        }
        if (lastSec == null) {
            return false;
        }
        Node valNode;
        if (lastSec.hasChildren()) {
            NodeTuple lastChild = lastSec.getLastChild();
            valNode = lastChild.getValueNode() != null ? lastChild.getValueNode() : lastChild.getKeyNode();
        } else {
            valNode = lastSec.valueNode;
        }
        if (valNode instanceof ScalarNode sn) {
            ScalarStyle style = sn.getScalarStyle();
            return style == ScalarStyle.LITERAL || style == ScalarStyle.FOLDED;
        }
        return false;
    }

    private static boolean hasAnchorAliasOrMergeKey(Node root) {
        if (root == null) return false;
        return checkNodeForAnchorAliasOrMergeKey(root, new HashSet<>());
    }

    private static boolean checkNodeForAnchorAliasOrMergeKey(Node node, Set<Node> visited) {
        if (node == null) return false;
        if (!visited.add(node)) return false;
        if (node.getAnchor() != null) return true;
        if (node instanceof AnchorNode) return true;
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode keyNode) {
                    if ("<<".equals(keyNode.getValue())) return true;
                }
                if (checkNodeForAnchorAliasOrMergeKey(tuple.getKeyNode(), visited)) return true;
                if (checkNodeForAnchorAliasOrMergeKey(tuple.getValueNode(), visited)) return true;
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                if (checkNodeForAnchorAliasOrMergeKey(item, visited)) return true;
            }
        }
        return false;
    }

    private static boolean hasUnsupportedLineBreak(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0085' || c == '\u2028' || c == '\u2029') {
                return true;
            }
        }
        return false;
    }

    private static String findRootKeyWithDot(Node root) {
        if (root instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode keyNode) {
                    String val = keyNode.getValue();
                    if (val != null && val.contains(".")) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    static boolean verifyPreservation(YamlConfiguration original,
                                      YamlConfiguration candidate,
                                      Map<String, TopLevelSection> ownerSections,
                                      Set<String> scheduledEmptySectionFills) {
        // 1. Every key present in original must be present in candidate with an equal value
        for (String key : original.getKeys(true)) {
            if (original.isConfigurationSection(key)) {
                if (!candidate.isConfigurationSection(key)) {
                    return false;
                }
            } else {
                if (!candidate.contains(key) && !candidate.isSet(key)) {
                    return false;
                }
                Object origVal = original.get(key);
                Object candVal = candidate.get(key);
                if (!Objects.equals(origVal, candVal)) {
                    return false;
                }
            }
        }

        // 2. Also verify keys in ownerSections from SnakeYAML (covers present null/empty keys that Bukkit omits from getKeys)
        for (Map.Entry<String, TopLevelSection> entry : ownerSections.entrySet()) {
            String secName = entry.getKey();
            TopLevelSection sec = entry.getValue();
            if (original.isConfigurationSection(secName)) {
                if (!candidate.isConfigurationSection(secName)) {
                    return false;
                }
            } else {
                Object origVal = original.get(secName);
                Object candVal = candidate.get(secName);
                // A header the owner left without children is a container, not a value:
                // every key under it is missing, and filling it is what T23 asks for.
                // A null may become a section only when that name is a bundled mapping section
                // the merge scheduled to fill.
                boolean filledEmptyContainer = origVal == null
                        && sec.keys.isEmpty()
                        && scheduledEmptySectionFills.contains(secName)
                        && candidate.isConfigurationSection(secName);
                if (!filledEmptyContainer && !Objects.equals(origVal, candVal)) {
                    return false;
                }
            }
            for (String childKey : sec.keys) {
                String path = secName + "." + childKey;
                if (original.isConfigurationSection(path)) {
                    if (!candidate.isConfigurationSection(path)) {
                        return false;
                    }
                } else {
                    Object origVal = original.get(path);
                    Object candVal = candidate.get(path);
                    if (!Objects.equals(origVal, candVal)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static final class BundledCatalog {
        final List<Line> lines;
        final Map<String, List<Line>> keyBlocks = new HashMap<>();
        final Map<String, List<Line>> sectionBlocks = new HashMap<>();

        BundledCatalog(String content, MappingNode rootMapping) {
            this.lines = splitIntoLines(content);
            List<NodeTuple> secTuples = rootMapping.getValue();
            for (int s = 0; s < secTuples.size(); s++) {
                NodeTuple secTuple = secTuples.get(s);
                if (!(secTuple.getKeyNode() instanceof ScalarNode secKeyNode)) continue;
                String secName = secKeyNode.getValue();
                Node secVal = secTuple.getValueNode();

                int secHeaderLine = secKeyNode.getStartMark().getLine();
                int secCommentStart = secHeaderLine;
                while (secCommentStart > 0 && lines.get(secCommentStart - 1).content.trim().startsWith("#")) {
                    secCommentStart--;
                }

                int secEndLine;
                if (s + 1 < secTuples.size() && secTuples.get(s + 1).getKeyNode() != null) {
                    int nextHeaderLine = secTuples.get(s + 1).getKeyNode().getStartMark().getLine();
                    int nextCommentStart = nextHeaderLine;
                    while (nextCommentStart > 0 && lines.get(nextCommentStart - 1).content.trim().startsWith("#")) {
                        nextCommentStart--;
                    }
                    int back = nextCommentStart - 1;
                    while (back > secHeaderLine && lines.get(back).content.isBlank()) {
                        back--;
                    }
                    secEndLine = back;
                } else {
                    int back = lines.size() - 1;
                    while (back > secHeaderLine && lines.get(back).content.isBlank()) {
                        back--;
                    }
                    secEndLine = back;
                }

                List<Line> secBlock = new ArrayList<>();
                for (int i = secCommentStart; i <= secEndLine && i < lines.size(); i++) {
                    secBlock.add(lines.get(i));
                }
                sectionBlocks.put(secName, secBlock);

                if (secVal instanceof MappingNode childMapping) {
                    for (NodeTuple child : childMapping.getValue()) {
                        if (!(child.getKeyNode() instanceof ScalarNode childKeyNode)) continue;
                        String childKey = childKeyNode.getValue();
                        Node childVal = child.getValueNode();

                        int childStartLine = childKeyNode.getStartMark().getLine();
                        int childCommentStart = childStartLine;
                        while (childCommentStart > secHeaderLine + 1
                                && lines.get(childCommentStart - 1).content.trim().startsWith("#")) {
                            childCommentStart--;
                        }

                        int childEndLine = childStartLine;
                        if (childVal != null && childVal.getEndMark() != null) {
                            Mark endMark = childVal.getEndMark();
                            childEndLine = (endMark.getColumn() == 0 && endMark.getLine() > childVal.getStartMark().getLine())
                                    ? endMark.getLine() - 1
                                    : endMark.getLine();
                        }

                        List<Line> keyBlock = new ArrayList<>();
                        for (int i = childCommentStart; i <= childEndLine && i < lines.size(); i++) {
                            keyBlock.add(lines.get(i));
                        }
                        keyBlocks.put(secName + "." + childKey, keyBlock);
                    }
                } else {
                    int valEndLine = secHeaderLine;
                    if (secVal != null && secVal.getEndMark() != null) {
                        Mark endMark = secVal.getEndMark();
                        valEndLine = (endMark.getColumn() == 0 && endMark.getLine() > secVal.getStartMark().getLine())
                                ? endMark.getLine() - 1
                                : endMark.getLine();
                    }
                    List<Line> keyBlock = new ArrayList<>();
                    for (int i = secCommentStart; i <= valEndLine && i < lines.size(); i++) {
                        keyBlock.add(lines.get(i));
                    }
                    keyBlocks.put(secName, keyBlock);
                }
            }
        }
    }

    private Map<String, String> loadBundledEnglish() {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var in = getClass().getResourceAsStream("/messages_en.yml")) {
            if (in != null) {
                try (var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    yaml.load(reader);
                }
            }
        } catch (IOException | InvalidConfigurationException e) {
            // Bundled resource is valid and packaged in jar
        }
        Map<String, String> map = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isString(key)) map.put(key, yaml.getString(key));
        }
        return Map.copyOf(map);
    }

    @Override
    public synchronized PluginConfig load() {
        saveDefaultMessages();
        ReadResult readResult = read();
        if (!readResult.problems.isEmpty()) {
            throw new ConfigException(String.join("; ", readResult.problems));
        }
        messages.swap(new YamlMessages(readResult.texts, bundledEnglish, readResult.fileName, warning));
        return readResult.config;
    }

    @Override
    public Messages messages() {
        return messages;
    }

    @Override
    public List<String> validate() {
        return List.copyOf(read().problems);
    }

    private ReadResult read() {
        List<String> problems = new ArrayList<>();
        YamlConfiguration yaml = file("config.yml", problems);

        String language = "en";
        Object rawLang = yaml.get("language");
        if (rawLang != null) {
            if (rawLang instanceof String str && (str.equalsIgnoreCase("en") || str.equalsIgnoreCase("es"))) {
                language = str.toLowerCase(Locale.ROOT);
            } else {
                warning.accept("language: unrecognized value '" + rawLang + "', accepted languages: [en, es]; falling back to en");
                language = "en";
            }
        }

        String messageFileName = "messages_" + language + ".yml";
        Map<String, String> map = loadMessageFile(messageFileName);
        PluginConfig config = new Values(yaml, problems, language).config();
        return new ReadResult(config, map, messageFileName, problems);
    }

    private Map<String, String> loadMessageFile(String name) {
        YamlConfiguration texts = new YamlConfiguration();
        Map<String, String> map = new HashMap<>();
        try (var reader = Files.newBufferedReader(dataFolder.resolve(name), StandardCharsets.UTF_8)) {
            texts.load(reader);
            for (String key : texts.getKeys(true)) {
                if (texts.isString(key)) map.put(key, texts.getString(key));
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            warning.accept(name + ": expected a readable file with valid YAML syntax; falling back to bundled English");
        }
        return Map.copyOf(map);
    }

    private YamlConfiguration file(String name, List<String> problems) {
        YamlConfiguration yaml = new YamlConfiguration();
        try (var reader = Files.newBufferedReader(dataFolder.resolve(name), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        } catch (IOException | InvalidConfigurationException | RuntimeException failure) {
            // The parser may include lines with secrets: never attach its cause or its message.
            problems.add(name + ": expected a readable file with valid YAML syntax");
        }
        return yaml;
    }

    private record ReadResult(PluginConfig config, Map<String, String> texts, String fileName, List<String> problems) {}

    private static final class ReloadableMessages implements Messages {
        private volatile Messages current;

        private ReloadableMessages(Messages initial) {
            this.current = initial;
        }

        private void swap(Messages next) {
            this.current = next;
        }

        @Override
        public Component get(String key, Map<String, String> placeholders) {
            return current.get(key, placeholders);
        }

        @Override
        public Component get(String key) {
            return current.get(key);
        }

        @Override
        public String plain(String key, Map<String, String> placeholders) {
            return current.plain(key, placeholders);
        }

        @Override
        public String label(String key, Map<String, String> placeholders) {
            return current.label(key, placeholders);
        }

        @Override
        public String label(String key) {
            return current.label(key);
        }

        @Override
        public String rawPrefix() {
            Messages m = current;
            return m != null ? m.rawPrefix() : "";
        }

        @Override
        public String catalogPrefix() {
            Messages m = current;
            return m != null ? m.catalogPrefix() : "";
        }

        @Override
        public Component renderedPrefix() {
            Messages m = current;
            return m != null ? m.renderedPrefix() : Component.empty();
        }

        @Override
        public void setCustomPrefix(String prefix) {
            Messages m = current;
            if (m != null) {
                m.setCustomPrefix(prefix);
            }
        }

        @Override
        public void resetPrefix() {
            Messages m = current;
            if (m != null) {
                m.resetPrefix();
            }
        }

        @Override
        public void invalidatePrefix() {
            Messages m = current;
            if (m != null) {
                m.invalidatePrefix();
            }
        }
    }

    private static final class Values {
        private final YamlConfiguration yaml;
        private final List<String> problems;
        private final String language;

        private Values(YamlConfiguration yaml, List<String> problems, String language) {
            this.yaml = yaml;
            this.problems = problems;
            this.language = language;
        }

        private void require(boolean condition, String key, String expected) {
            if (!condition) problems.add(key + ": expected " + expected);
        }

        private String text(String key, boolean optional) {
            Object value = yaml.get(key);
            if (optional && value == null) {
                return "";
            }
            require(value instanceof String, key, "quoted text if it is a number");
            return value instanceof String str ? str : "";
        }

        private String text(String key) {
            return text(key, false);
        }

        private String nonEmpty(String key) {
            String value = text(key);
            require(!value.isBlank(), key, "non-empty text");
            return value;
        }

        private boolean bool(String key) {
            Object value = yaml.get(key);
            require(value instanceof Boolean, key, "true or false");
            return Boolean.TRUE.equals(value);
        }

        private int integer(String key, int min, int max) {
            Object value = yaml.get(key);
            boolean valid = (value instanceof Integer || value instanceof Long)
                    && ((Number) value).longValue() >= min && ((Number) value).longValue() <= max;
            require(valid, key, "an integer between " + min + " and " + max);
            return valid ? ((Number) value).intValue() : min;
        }

        private int positive(String key) {
            return integer(key, 1, Integer.MAX_VALUE);
        }

        private Duration seconds(String key) {
            return Duration.ofSeconds(positive(key));
        }

        private Duration minutes(String key) {
            return Duration.ofMinutes(positive(key));
        }

        private <E extends Enum<E>> E option(String key, Class<E> type) {
            String value = text(key).toUpperCase(Locale.ROOT);
            for (E opt : type.getEnumConstants()) {
                if (opt.name().equals(value)) return opt;
            }
            require(false, key, "one of " + Arrays.toString(type.getEnumConstants()));
            return type.getEnumConstants()[0];
        }

        private Optional<String> optional(String key) {
            String value = text(key, true);
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        }

        private String snowflake(String key, boolean optional) {
            String value = text(key, optional);
            if (optional && value.isEmpty()) return value;
            boolean valid = value.matches("[1-9][0-9]{0,19}");
            if (valid) {
                try {
                    Long.parseUnsignedLong(value);
                } catch (NumberFormatException failure) {
                    valid = false;
                }
            }
            require(valid, key, "a positive decimal snowflake of up to 64 unsigned bits"
                    + (optional ? " or empty text" : ""));
            return value;
        }

        private String name(String key, boolean textChannel, String... placeholders) {
            String value = nonEmpty(key);
            String sample = value;
            for (String placeholder : placeholders) sample = sample.replace(placeholder, "x");
            require(!sample.contains("{") && !sample.contains("}"), key,
                    placeholders.length > 0 ? "only placeholders " + String.join(", ", placeholders) : "a name without placeholders");
            require(sample.length() >= 1 && sample.length() <= 100, key,
                    "a name of 1 to 100 characters, counting at least one per placeholder");
            require(sample.codePoints().noneMatch(Character::isISOControl), key, "a name without control characters");
            if (textChannel) {
                require(sample.matches("[\\p{Ll}\\p{Lo}\\p{M}\\p{N}_-]+"), key,
                        "lowercase letters, numbers, hyphens, or underscores outside placeholders");
            }
            // Real names must be validated again after substituting town and mayor when creating the channel.
            return value;
        }

        private PluginConfig config() {
            String token = nonEmpty("discord.token");
            require(!token.strip().equalsIgnoreCase("PON_AQUI_TU_TOKEN"), "discord.token", "your own token, not the example");
            String guild = snowflake("discord.guild-id", false);
            String log = snowflake("discord.log-channel-id", true);
            String link = snowflake("discord.link-channel-id", true);
            var discord = new PluginConfig.Discord(
                    token,
                    guild,
                    log.isEmpty() ? Optional.empty() : Optional.of(log),
                    link.isEmpty() ? Optional.empty() : Optional.of(link));

            var type = option("database.type", PluginConfig.Database.Type.class);
            String host = text("database.host");
            int port = integer("database.port", 1, 65535);
            String dbName = text("database.name");
            String user = text("database.user");
            String password = text("database.password");
            String prefix = text("database.table-prefix");
            require(prefix.matches("[A-Za-z_][A-Za-z0-9_]*") || prefix.isEmpty(), "database.table-prefix",
                    "an SQL prefix of letters, numbers, and underscores, not starting with a number, or empty");
            if (type != PluginConfig.Database.Type.SQLITE) {
                require(!host.isBlank(), "database.host", "a non-empty host");
                require(!dbName.isBlank(), "database.name", "a non-empty name");
                require(!user.isBlank(), "database.user", "a non-empty user");
            }
            int max = positive("database.pool.maximum-size");
            int min = integer("database.pool.minimum-idle", 0, max);
            var database = new PluginConfig.Database(type, host, port, dbName, user, password,
                    prefix, max, min, seconds("database.pool.connection-timeout-seconds"));

            var structure = new PluginConfig.Structure(name("structure.category-name", false),
                    name("structure.archive-category-name", false), bool("structure.create-text-channel"),
                    bool("structure.create-voice-channel"), name("structure.text-channel-name", true, "{town}", "{mayor}"),
                    name("structure.voice-channel-name", false, "{town}", "{mayor}"));
            Optional<String> color = optional("roles.town-role-color");
            require(color.isEmpty() || color.get().matches("#?[0-9a-fA-F]{6}"), "roles.town-role-color",
                    "a six-digit hexadecimal color or empty");
            var roles = new PluginConfig.Roles(name("roles.mayor-role-name", false),
                    name("roles.town-role-name", false, "{town}"), color, bool("roles.town-role-hoisted"));
            var limits = new PluginConfig.Limits(integer("limits.max-towns", 1, 240), positive("limits.min-residents"),
                    seconds("limits.creation-cooldown-seconds"));
            var lifecycle = new PluginConfig.Lifecycle(option("lifecycle.on-town-deleted", PluginConfig.Lifecycle.Action.class),
                    option("lifecycle.on-town-ruined", PluginConfig.Lifecycle.Action.class),
                    integer("lifecycle.archive-reminder-days", 0, Integer.MAX_VALUE));
            var sync = new PluginConfig.Sync(Duration.ofMinutes(integer("sync.interval-minutes", 0, Integer.MAX_VALUE)), option("sync.mode", PluginConfig.Sync.Mode.class),
                    positive("sync.batch-size"), seconds("sync.batch-pause-seconds"));
            var linking = new PluginConfig.Linking(minutes("linking.code-expiry-minutes"), positive("linking.max-attempts"),
                    minutes("linking.attempt-lockout-minutes"), bool("linking.unlink-on-guild-leave"));
            var logging = new PluginConfig.Logging(seconds("logging.flush-interval-seconds"), positive("logging.queue-size"),
                    option("logging.detail", PluginConfig.Logging.Detail.class));
            var updates = new PluginConfig.Updates(bool("updates.check-enabled"),
                    Duration.ofHours(positive("updates.check-interval-hours")), bool("updates.auto-download"),
                    bool("updates.notify-admins-on-join"));
            var commandsList = new ArrayList<PluginConfig.DiscordCommand>();
            for (String cmd : List.of("town", "residents", "res", "townlist", "mytown", "help")) {
                commandsList.add(new PluginConfig.DiscordCommand(cmd, bool("commands." + cmd + ".enabled"),
                        bool("commands." + cmd + ".ephemeral")));
            }
            var commands = new PluginConfig.Commands(seconds("commands.cooldown-seconds"), List.copyOf(commandsList));
            integer("config-version", 1, 1);
            return new PluginConfig(language, discord, database, structure, roles, limits, lifecycle, sync, linking, logging, updates, commands);
        }
    }
}
