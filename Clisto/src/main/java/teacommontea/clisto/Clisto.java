package teacommontea.clisto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.UnaryOperator;

public final class Clisto {

    public static final int HISTORY = 5;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");

    private final Path root;
    private final ConfigStore store;
    private final Syntax syntax;
    private final ClistoEvents events;
    private final Markers markers;

    private Clisto(Builder b) {
        this.root = b.root;
        this.store = b.store;
        this.syntax = b.syntax;
        this.events = b.events;
        this.markers = b.markers;
    }

    public static Builder at(Path root) {
        return new Builder(root);
    }

    public static final class Builder {
        private final Path root;
        private ConfigStore store;
        private Syntax syntax;
        private ClistoEvents events = ClistoEvents.NONE;
        private Markers markers = Markers.ENGLISH;

        private Builder(Path root) {
            this.root = root;
        }

        public Builder store(ConfigStore store) {
            this.store = store;
            return this;
        }

        public Builder syntax(Syntax syntax) {
            this.syntax = syntax;
            return this;
        }

        public Builder events(ClistoEvents events) {
            this.events = events == null ? ClistoEvents.NONE : events;
            return this;
        }

        public Builder markers(Markers markers) {
            this.markers = markers == null ? Markers.ENGLISH : markers;
            return this;
        }

        public Clisto build() {
            return new Clisto(this);
        }
    }

    Path root() {
        return root;
    }

    ConfigStore store() {
        return store;
    }

    ClistoEvents events() {
        return events;
    }

    public void migrate(String manifest) throws IOException {
        if (manifest != null) {
            new Migrator(this).run(manifest);
        }
    }

    public boolean validate(String file, UnaryOperator<String> bundleFor) throws IOException {
        return reconcile(file, bundleFor, ConfigMerge.Mode.REPAIR);
    }

    public boolean heal(String file, UnaryOperator<String> bundleFor) throws IOException {
        return reconcile(file, bundleFor, ConfigMerge.Mode.FULL);
    }

    private boolean reconcile(String file, UnaryOperator<String> bundleFor, ConfigMerge.Mode mode) throws IOException {
        Path target = root.resolve(file);
        if (!Files.isRegularFile(target)) {
            return false;
        }
        String deployed = read(target);
        String bundled = bundleFor.apply(deployed);
        if (bundled == null) {
            return false;
        }
        String base = mode == ConfigMerge.Mode.FULL ? readBase(file) : null;
        ConfigMerge.Result result = ConfigMerge.run(file, deployed, bundled, base, mode, syntax, markers);
        switch (result.status()) {
            case UNSAFE:
                for (Action a : result.actions()) {
                    events.action(a);
                }
                events.unsafe(file);
                return false;
            case UNREADABLE:
                if (!deployed.trim().isEmpty()) {
                    String backup = file + ".broken-" + LocalDateTime.now().format(STAMP);
                    snapshot(file, deployed);
                    Files.copy(target, root.resolve(backup), StandardCopyOption.REPLACE_EXISTING);
                    events.replaced(file, backup);
                }
                atomicWrite(target, bundled.getBytes(StandardCharsets.UTF_8));
                if (mode == ConfigMerge.Mode.FULL) {
                    writeBase(file, bundled);
                }
                return true;
            case BROKEN:
                String marked = YamlText.join(result.lines(), YamlText.eolOf(deployed), YamlText.endsWithNewline(deployed));
                boolean rewritten = !marked.equals(deployed);
                if (rewritten) {
                    writeConfig(file, deployed, marked);
                }
                for (Action a : result.actions()) {
                    events.action(a);
                }
                return rewritten;
            case CHANGED:
                String text = YamlText.join(result.lines(), YamlText.eolOf(deployed), YamlText.endsWithNewline(deployed));
                writeConfig(file, deployed, text);
                for (Action a : result.actions()) {
                    events.action(a);
                }
                if (mode == ConfigMerge.Mode.FULL) {
                    writeBase(file, bundled);
                    events.updated(file);
                }
                return true;
            default:
                for (Action a : result.actions()) {
                    events.action(a);
                }
                if (mode == ConfigMerge.Mode.FULL) {
                    writeBase(file, bundled);
                }
                return false;
        }
    }

    public String get(String file, String dotted) throws IOException {
        Path target = root.resolve(file);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        YamlDoc doc = YamlDoc.parse(read(target));
        YamlDoc.Entry e = doc.find(dotted);
        return e == null || e.isParent() ? null : doc.scalar(e);
    }

    public boolean set(String file, String dotted, String value) throws IOException {
        Path target = root.resolve(file);
        if (!Files.isRegularFile(target)) {
            return false;
        }
        String deployed = read(target);
        List<String> lines = new java.util.ArrayList<>(YamlText.splitLines(deployed));
        YamlDoc doc = YamlDoc.parse(lines);
        YamlDoc.Entry e = doc.find(dotted);
        if (e == null || e.isParent() || !doc.valueLines(e).stream().allMatch(l -> l.trim().isEmpty() || l.trim().startsWith("#"))) {
            return false;
        }
        lines.set(e.keyLine(), YamlText.replaceScalarValue(lines.get(e.keyLine()), value));
        String text = YamlText.join(lines, YamlText.eolOf(deployed), YamlText.endsWithNewline(deployed));
        if (!text.equals(deployed)) {
            writeConfig(file, deployed, text);
        }
        return true;
    }

    public int appendMissing(String file, String bundled, BlockFormat format) throws IOException {
        Path target = root.resolve(file);
        if (!Files.isRegularFile(target) || bundled == null) {
            return 0;
        }
        BlockMerge.Appended appended = BlockMerge.missing(read(target), bundled, format);
        if (appended.isEmpty()) {
            return 0;
        }
        Files.write(target, appended.text().getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        events.blocksAdded(file, appended.count());
        return appended.count();
    }

    public int carryOver(String file, String previousFile, BlockFormat format, String notice) throws IOException {
        Path target = root.resolve(file);
        Path previous = root.resolve(previousFile);
        if (!Files.isRegularFile(previous)) {
            return 0;
        }
        try {
            if (!Files.isRegularFile(target)) {
                return 0;
            }
            BlockMerge.Appended appended = BlockMerge.carried(read(target), read(previous), format, notice);
            if (appended.isEmpty()) {
                return 0;
            }
            Files.write(target, appended.text().getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            events.blocksCarried(file, appended.count());
            return appended.count();
        } finally {
            Files.deleteIfExists(previous);
        }
    }

    void writeConfig(String file, String previousText, String text) throws IOException {
        snapshot(file, previousText);
        atomicWrite(root.resolve(file), text.getBytes(StandardCharsets.UTF_8));
    }

    private void snapshot(String file, String previousText) {
        if (store == null || previousText == null) {
            return;
        }
        for (int n = HISTORY; n > 1; n--) {
            byte[] older = store.read(historyKey(file, n - 1));
            if (older != null) {
                store.write(historyKey(file, n), older);
            }
        }
        store.write(historyKey(file, 1), previousText.getBytes(StandardCharsets.UTF_8));
    }

    public static String historyKey(String file, int n) {
        return "config-history:" + file + ":" + n;
    }

    public static String baseKey(String file) {
        return "config-base:" + file;
    }

    private String readBase(String file) {
        if (store == null) {
            return null;
        }
        byte[] stored = store.read(baseKey(file));
        return stored == null ? null : new String(stored, StandardCharsets.UTF_8);
    }

    private void writeBase(String file, String bundled) {
        if (store != null) {
            store.write(baseKey(file), bundled.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
