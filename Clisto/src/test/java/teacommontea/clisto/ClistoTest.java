package teacommontea.clisto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClistoTest {

    private static final String FILE = "config.yml";

    @TempDir
    Path root;

    private final MemoryStore store = new MemoryStore();
    private final List<Action> actions = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();

    private static String fixture(String name) {
        try (InputStream in = ClistoTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String BUNDLE = fixture("verite-config.yml");

    private Clisto clisto() {
        return Clisto.at(root)
                .store(store)
                .syntax(text -> new org.yaml.snakeyaml.Yaml().load(text))
                .events(new ClistoEvents() {
                    @Override
                    public void action(Action a) {
                        actions.add(a);
                    }

                    @Override
                    public void updated(String file) {
                        notices.add("updated");
                    }

                    @Override
                    public void unsafe(String file) {
                        notices.add("unsafe");
                    }

                    @Override
                    public void replaced(String file, String backup) {
                        notices.add("replaced " + backup);
                    }
                })
                .build();
    }

    private void deploy(String text) throws IOException {
        Files.writeString(root.resolve(FILE), text);
    }

    private String deployed() throws IOException {
        return Files.readString(root.resolve(FILE));
    }

    private void base(String text) {
        store.write(Clisto.baseKey(FILE), text.getBytes(StandardCharsets.UTF_8));
    }

    private boolean heal(String bundle) throws IOException {
        return clisto().heal(FILE, d -> bundle);
    }

    private static String value(String text, String dotted) {
        YamlDoc doc = YamlDoc.parse(text);
        YamlDoc.Entry e = doc.find(dotted);
        return e == null ? null : doc.normalisedValue(e);
    }

    private boolean has(Action.Kind kind, String key) {
        for (Action a : actions) {
            if (a.kind() == kind && key.equals(a.key())) {
                return true;
            }
        }
        return false;
    }

    private static void parsesAsYaml(String text) {
        assertNotNull(new org.yaml.snakeyaml.Yaml().load(YamlText.protectBraces(text)));
    }

    @Test
    void identicalConfigIsLeftAlone() throws IOException {
        deploy(BUNDLE);
        base(BUNDLE);
        assertFalse(heal(BUNDLE));
        assertEquals(BUNDLE, deployed());
        assertTrue(actions.isEmpty());
    }

    @Test
    void bundleParsesIntoEveryKnownPath() {
        YamlDoc doc = YamlDoc.parse(BUNDLE);
        assertEquals("true", value(BUNDLE, "general.auto.update.config"));
        assertEquals("0.30", value(BUNDLE, "chat.filter.advanced.use.profanity.confidence.profanity.confidence.threshold"));
        assertTrue(doc.find("moderation.tree.categories.harassment") != null);
        assertTrue(value(BUNDLE, "moderation.muted.command.blacklist").contains("- \"mail\""));
        assertEquals("en", value(BUNDLE, "config.language"));
    }

    @Test
    void editedListSurvivesAnUpdate() throws IOException {
        String owner = BUNDLE.replace("    - \"mail\"\n", "    - \"mail\"\n    - \"shout\"\n").replace("    - \"me\"\n", "");
        String next = BUNDLE.replace("  client.inspection: once\n", "  client.inspection: once\n\n  brand.new.key: 7\n");
        deploy(owner);
        base(BUNDLE);
        assertTrue(heal(next));
        String out = deployed();
        assertTrue(value(out, "moderation.muted.command.blacklist").contains("- \"shout\""));
        assertFalse(value(out, "moderation.muted.command.blacklist").contains("- \"me\""));
        assertEquals("7", value(out, "moderation.brand.new.key"));
        parsesAsYaml(out);
    }

    @Test
    void untouchedDefaultsFollowTheBundleAndEditsStay() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "  port: 9100");
        String next = BUNDLE.replace("  port: 9092", "  port: 9200")
                .replace("  auto.server: false", "  auto.server: true");
        deploy(owner);
        base(BUNDLE);
        heal(next);
        String out = deployed();
        assertEquals("9100", value(out, "database.port"));
        assertEquals("true", value(out, "database.auto.server"));
    }

    @Test
    void unknownKeyIsCommentedOutNotWiped() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "  port: 9100\n  zzqqxx.custom: 5");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertEquals("9100", value(out, "database.port"));
        assertTrue(out.contains("# " + Markers.ENGLISH.unknown()));
        assertTrue(out.contains("# zzqqxx.custom: 5"));
        assertTrue(has(Action.Kind.QUARANTINED, "database.zzqqxx.custom"));
        parsesAsYaml(out);
    }

    @Test
    void typoIsFlaggedNotRenamed() throws IOException {
        String owner = BUNDLE.replace("    keep.chat.readable: false", "    keep.chat.readabel: true");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertTrue(out.contains("    " + ConfigMerge.marker(Markers.ENGLISH.flagStart()) + "\n    keep.chat.readabel: true\n    "
                + ConfigMerge.marker("UNKNOWN KEY, DID YOU MEAN keep.chat.readable?") + "\n"));
        assertNull(YamlDoc.parse(out).find("chat.filter.advanced.keep.chat.readable"));
        assertTrue(has(Action.Kind.FLAGGED, "chat.filter.advanced.keep.chat.readabel"));
        parsesAsYaml(out);
    }

    @Test
    void misplacedKeyIsFlaggedNotMoved() throws IOException {
        String owner = BUNDLE.replace("  dashboard.url: \"https://verite.gg\"\n", "")
                + "\ndashboard.url: \"https://mine.example\"\n";
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertTrue(out.contains(ConfigMerge.marker(Markers.ENGLISH.flagStart()) + "\ndashboard.url: \"https://mine.example\"\n"
                + ConfigMerge.marker("INDENTED UNDER THE WRONG SECTION, IT BELONGS UNDER general")));
        assertNull(YamlDoc.parse(out).find("general.dashboard.url"));
        assertTrue(has(Action.Kind.FLAGGED, "dashboard.url"));
        parsesAsYaml(out);
    }

    @Test
    void duplicateKeepsTheLastCopy() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "  port: 1111\n  port: 2222");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertEquals("2222", value(out, "database.port"));
        assertTrue(out.contains("# port: 1111"));
        assertTrue(has(Action.Kind.DUPLICATE, "database.port"));
        parsesAsYaml(out);
    }

    @Test
    void brokenSyntaxIsRepairedLineByLine() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "\tport: 9100")
                .replace("  host.address: \"127.0.0.1:9092\"", "  host.address: \"10.0.0.1:9092")
                .replace("  mode: embedded", "  mode:host");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertEquals("9100", value(out, "database.port"));
        assertEquals("\"10.0.0.1:9092\"", value(out, "database.host.address"));
        assertEquals("host", value(out, "database.mode"));
        parsesAsYaml(out);
    }

    @Test
    void unrepairableLinesAreMarkedInPlace() throws IOException {
        String owner = BUNDLE.replace("  mode: embedded\n", "  mode: embedded\n      stray: 1\n")
                .replace("  shared.ip.scan.limit: 20\n", "  shared.ip.scan.limit: 20\n  this is junk\n");
        deploy(owner);
        base(BUNDLE);
        assertTrue(clisto().validate(FILE, d -> BUNDLE));
        String out = deployed();
        String start = ConfigMerge.marker(Markers.ENGLISH.failedStart());
        String end = ConfigMerge.marker(Markers.ENGLISH.failedEnd());
        assertTrue(out.contains("      " + start + "\n      stray: 1\n      " + end + "\n"));
        assertTrue(out.contains("  " + start + "\n  this is junk\n  " + end + "\n"));
        assertEquals(2, actions.stream().filter(a -> a.kind() == Action.Kind.UNPARSABLE).count());
        String stored = new String(store.read(Clisto.baseKey(FILE)), StandardCharsets.UTF_8);
        assertEquals(BUNDLE, stored);

        actions.clear();
        assertFalse(heal(BUNDLE));
        assertEquals(out, deployed());
        assertEquals(2, actions.size());

        actions.clear();
        assertFalse(clisto().validate(FILE, d -> BUNDLE));
        assertEquals(out, deployed());
        assertEquals(2, actions.size());

        deploy(out.replace("      stray: 1\n", "").replace("  this is junk\n", ""));
        actions.clear();
        heal(BUNDLE);
        String fixed = deployed();
        assertFalse(fixed.contains(start));
        assertFalse(fixed.contains(end));
        assertEquals(BUNDLE, fixed);
    }

    @Test
    void wrongKindIsFlaggedAndLeftAlone() throws IOException {
        String owner = BUNDLE.replace("    keep.chat.readable: false", "    keep.chat.readable: banana");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertTrue(out.contains("    keep.chat.readable: banana\n    " + ConfigMerge.marker(Markers.ENGLISH.expectsBoolean()) + "\n"));
        assertTrue(has(Action.Kind.FLAGGED, "chat.filter.advanced.keep.chat.readable"));
        parsesAsYaml(out);
    }

    @Test
    void sectionWrittenAsValueIsFlagged() throws IOException {
        String section = "  exempt:\n    use.group.weights: false # Allow acting on an equal-weight target when use.group.weights is on.\n    permit.same.weight: true\n";
        String owner = BUNDLE.replace(section, "  exempt: true\n");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertTrue(out.contains("  exempt: true\n  " + ConfigMerge.marker(Markers.ENGLISH.expectsSection()) + "\n"));
        assertNull(YamlDoc.parse(out).find("moderation.exempt.permit.same.weight"));
        parsesAsYaml(out);
    }

    @Test
    void quotedNumbersAreCoercedAndPlainTextIsAccepted() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "  port: \"9100\"")
                .replace("  dashboard.url: \"https://verite.gg\"", "  dashboard.url: https://mine.example");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String out = deployed();
        assertEquals("9100", value(out, "database.port"));
        assertEquals("https://mine.example", value(out, "general.dashboard.url"));
        assertTrue(has(Action.Kind.COERCED, "database.port"));
    }

    @Test
    void retiredDefaultIsRemovedButRetiredEditIsKept() throws IOException {
        String old = BUNDLE.replace("  auto.server: false", "  auto.server: false\n\n  # Old\n  legacy.one: 1\n  legacy.two: 2");
        String owner = old.replace("  legacy.two: 2", "  legacy.two: 3");
        deploy(owner);
        base(old);
        heal(BUNDLE);
        String out = deployed();
        assertFalse(out.contains("legacy.one"));
        assertTrue(out.contains("# legacy.two: 3"));
        assertTrue(has(Action.Kind.REMOVED, "database.legacy.one"));
    }

    @Test
    void languageSwitchTranslatesUntouchedCommentsAndDefaults() throws IOException {
        String owner = BUNDLE.replace("config.language: en", "config.language: de")
                .replace("  prefix: \"<#808080>[<#B1C7F0>Verité<#808080>]<reset>\"", "  prefix: \"[Mine]\"");
        String german = Localiser.apply(BUNDLE, Localiser.parse(fixture("verite-config_de.txt")));
        deploy(owner);
        base(BUNDLE);
        heal(german);
        String out = deployed();
        assertTrue(out.contains("# Das öffentlich sichtbare Präfix in jeder Verité-Nachricht"));
        assertEquals("\"[Mine]\"", value(out, "general.prefix"));
        assertEquals("de", value(out, "config.language"));
        assertEquals(value(german, "chat.filter.block.message"), value(out, "chat.filter.block.message"));
        assertTrue(out.contains("Hinweis bei einer Blockierung wegen Selbstverletzung"));
        assertFalse(out.contains("# Self-harm block message notice"));
        parsesAsYaml(out);
    }

    @Test
    void ownerCommentsSurviveBundleCommentChanges() throws IOException {
        String owner = BUNDLE.replace("  # TCP port\n", "  # My own note about the port\n");
        String next = BUNDLE.replace("  # TCP port\n", "  # The TCP port\n")
                .replace("  # Options for database mode: embedded, host, remote\n", "  # Database mode\n");
        deploy(owner);
        base(BUNDLE);
        heal(next);
        String out = deployed();
        assertTrue(out.contains("  # My own note about the port\n"));
        assertTrue(out.contains("  # Database mode\n"));
    }

    @Test
    void healIsIdempotentAndKeepsNagging() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "  port: 1111\n  port: 2222\n  zzqqxx.custom: 5")
                .replace("    keep.chat.readable: false", "    keep.chat.readable: banana");
        deploy(owner);
        base(BUNDLE);
        heal(BUNDLE);
        String once = deployed();
        actions.clear();
        assertFalse(heal(BUNDLE));
        assertEquals(once, deployed());
        assertEquals(1, actions.size());
        assertEquals(Action.Kind.FLAGGED, actions.get(0).kind());

        deploy(once.replace("keep.chat.readable: banana", "keep.chat.readable: true"));
        actions.clear();
        heal(BUNDLE);
        assertFalse(deployed().contains("!!!"));
        assertEquals("true", value(deployed(), "chat.filter.advanced.keep.chat.readable"));
        assertTrue(actions.isEmpty());
    }

    @Test
    void crlfFilesStayCrlf() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "  port: 9100").replace("\n", "\r\n");
        String next = BUNDLE.replace("  client.inspection: once\n", "  client.inspection: once\n  brand.new.key: 7\n");
        deploy(owner);
        base(BUNDLE);
        heal(next);
        String out = deployed();
        assertFalse(out.replace("\r\n", "").contains("\n"));
        assertEquals("7", value(out, "moderation.brand.new.key"));
    }

    @Test
    void unreadableFileIsKeptAsABackup() throws IOException {
        deploy("this is not a config at all\n");
        base(BUNDLE);
        assertTrue(heal(BUNDLE));
        assertEquals(BUNDLE, deployed());
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).startsWith("replaced config.yml.broken-"));
        try (Stream<Path> files = Files.list(root)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("config.yml.broken-")));
        }
    }

    @Test
    void everyWriteIsSnapshotted() throws IOException {
        deploy(BUNDLE);
        base(BUNDLE);
        Clisto c = clisto();
        for (int i = 1; i <= 7; i++) {
            assertTrue(c.set(FILE, "database.port", String.valueOf(9000 + i)));
        }
        assertEquals("9007", value(deployed(), "database.port"));
        assertEquals("9006", value(new String(store.read(Clisto.historyKey(FILE, 1)), StandardCharsets.UTF_8), "database.port"));
        assertEquals("9002", value(new String(store.read(Clisto.historyKey(FILE, 5)), StandardCharsets.UTF_8), "database.port"));
        assertNull(store.read(Clisto.historyKey(FILE, 6)));
    }

    @Test
    void setFindsDottedKeysAtAnyIndent() throws IOException {
        deploy(BUNDLE.replace("\n  ", "\n    ").replace("\r\n", "\n"));
        Clisto c = clisto();
        assertTrue(c.set(FILE, "general.auto.update.config", "false"));
        assertEquals("false", c.get(FILE, "general.auto.update.config"));
        assertFalse(c.set(FILE, "moderation.muted.command.blacklist", "x"));
        assertFalse(c.set(FILE, "no.such.key", "x"));
    }

    @Test
    void repairModeOnlyFixesWhatBreaksLoading() throws IOException {
        String owner = BUNDLE.replace("  port: 9092", "  port: 1111\n  port: 2222\n  zzqqxx.custom: 5")
                .replace("  auto.server: false\n", "");
        deploy(owner);
        assertTrue(clisto().validate(FILE, d -> BUNDLE));
        String out = deployed();
        assertEquals("2222", value(out, "database.port"));
        assertEquals("5", value(out, "database.zzqqxx.custom"));
        assertNull(YamlDoc.parse(out).find("database.auto.server"));
    }

    @Test
    void migrationKeepsLineEndingsAndSnapshots() throws IOException {
        deploy(BUNDLE.replace("\n", "\r\n"));
        clisto().migrate("RENAME database.port database.tcp.port FROM config.yml\nDROP PARENT invsee FROM config.yml\n");
        String out = deployed();
        assertEquals("9092", value(out, "database.tcp.port"));
        assertNull(YamlDoc.parse(out).find("invsee"));
        assertFalse(out.replace("\r\n", "").contains("\n"));
        assertNotNull(store.read(Clisto.historyKey(FILE, 1)));
    }

    @Test
    void carriedBlocksAreAppendedWithTheNotice() throws IOException {
        BlockFormat format = new BlockFormat() {
            @Override
            public boolean startsBlock(String trimmed) {
                return trimmed.startsWith("RULE ") || isSection(trimmed);
            }

            @Override
            public boolean isSection(String trimmed) {
                return trimmed.startsWith("SECTION ");
            }
        };
        Files.writeString(root.resolve("board.txt"), "SECTION a\n\nRULE one\n\nRULE two\n");
        Files.writeString(root.resolve("board.txt.bak"), "SECTION a\n\nRULE one\n\nRULE mine\n  extra\n");
        assertEquals(1, clisto().carryOver("board.txt", "board.txt.bak", format, "# carried"));
        String out = Files.readString(root.resolve("board.txt"));
        assertTrue(out.endsWith("\n# carried\n\nSECTION a\n\nRULE mine\n  extra\n"));
        assertFalse(Files.exists(root.resolve("board.txt.bak")));
    }

    @Test
    void commentDetectionFollowsYamlRules() {
        assertEquals("&#FFFF55hello", YamlText.scalarValue("  msg: &#FFFF55hello"));
        assertEquals("don't stop", YamlText.scalarValue("  msg: don't stop # note"));
        assertEquals("\"a # b\"", YamlText.scalarValue("  msg: \"a # b\" # note"));
        assertEquals("{a, b # c}", YamlText.scalarValue("  t: {a, b # c} # note"));
        assertEquals("# note", YamlText.trailingComment("  msg: don't stop # note"));
    }

    @Test
    void distanceIsLevenshtein() {
        assertEquals(2, ConfigMerge.distance("readabel", "readable"));
        assertEquals(0, ConfigMerge.distance("port", "port"));
        assertEquals(2, ConfigMerge.distance("prot", "port"));
    }

    private static final class MemoryStore implements ConfigStore {
        private final Map<String, byte[]> rows = new HashMap<>();

        @Override
        public byte[] read(String name) {
            return rows.get(name);
        }

        @Override
        public void write(String name, byte[] bytes) {
            rows.put(name, bytes);
        }

        @Override
        public boolean remove(String name) {
            return rows.remove(name) != null;
        }
    }
}
