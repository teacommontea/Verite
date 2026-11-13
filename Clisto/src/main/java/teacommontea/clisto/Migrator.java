package teacommontea.clisto;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Migrator {

    private static final class Buffer {
        private final String originalText;
        private final List<String> lines;
        private boolean dirty;

        Buffer(String originalText) {
            this.originalText = originalText;
            this.lines = new ArrayList<>(YamlText.splitLines(originalText));
        }
    }

    private final Clisto clisto;
    private final File root;
    private final Map<String, Buffer> buffers = new LinkedHashMap<>();
    private final Set<String> deletedFiles = new LinkedHashSet<>();

    Migrator(Clisto clisto) {
        this.clisto = clisto;
        this.root = clisto.root().toFile();
    }

    void run(String manifest) throws IOException {
        List<String> manifestLines = new ArrayList<>(YamlText.splitLines(manifest));
        Collections.reverse(manifestLines);
        for (String raw : manifestLines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            apply(line.split("\\s+"));
        }

        int changed = 0;
        for (Map.Entry<String, Buffer> e : buffers.entrySet()) {
            Buffer b = e.getValue();
            if (b == null || !b.dirty) {
                continue;
            }
            String text = YamlText.join(b.lines, YamlText.eolOf(b.originalText), true);
            clisto.writeConfig(e.getKey(), b.originalText, text);
            changed++;
        }
        if (changed > 0 || !deletedFiles.isEmpty()) {
            clisto.events().filesChanged(deletedFiles.size(), changed);
        }
    }

    private void apply(String[] tok) {
        String verb = tok[0].toUpperCase(Locale.ROOT);
        ConfigStore store = clisto.store();

        if (verb.equals("MIGRATE") && tok.length >= 5
                && tok[2].equalsIgnoreCase("COLUMN") && tok[4].equalsIgnoreCase("OF")) {
            if (migrateToStore(tok[1], tok[3])) {
                deletedFiles.add(tok[1]);
            }
            return;
        }

        if (verb.equals("DROP") && tok.length >= 4
                && tok[1].equalsIgnoreCase("H2") && tok[2].equalsIgnoreCase("COLUMN")) {
            if (store != null && store.remove(tok[3])) {
                clisto.events().storeRemoved(tok[3]);
            }
            return;
        }

        if (verb.equals("DELETE") && tok.length >= 2) {
            if (guardedByStore(tok) && store == null) {
                return;
            }
            File f = resolveUnder(root, root, tok[1]);
            if (f != null && f.exists() && !f.equals(root) && deleteRecursively(f)) {
                deletedFiles.add(tok[1]);
            }
            return;
        }

        if (verb.equals("MOVE") && tok.length >= 3) {
            if (moveEntry(tok[1], tok[2])) {
                deletedFiles.add(tok[1]);
            }
            return;
        }

        if (verb.equals("RENAME") && tok.length >= 4 && tok[1].equalsIgnoreCase("DIR")) {
            if (renameDir(tok[2], tok[3])) {
                deletedFiles.add(tok[2]);
            }
            return;
        }

        if (verb.equals("RENAME") && tok.length >= 3) {
            String file = fromFile(tok, 3);
            Buffer b = buffer(file);
            if (b != null && renameKey(b.lines, tok[1], tok[2])) {
                b.dirty = true;
            }
            return;
        }

        if (verb.equals("DROP")) {
            boolean parent = tok.length >= 2 && tok[1].equalsIgnoreCase("PARENT");
            int pathIdx = parent ? 2 : 1;
            if (tok.length <= pathIdx) {
                return;
            }
            String file = fromFile(tok, pathIdx + 1);
            Buffer b = buffer(file);
            if (b != null && dropKey(b.lines, tok[pathIdx], parent)) {
                b.dirty = true;
            }
        }
    }

    private static boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        return f.delete();
    }

    private static boolean guardedByStore(String[] tok) {
        for (int i = 2; i + 2 < tok.length; i++) {
            if (tok[i].equalsIgnoreCase("IF") && tok[i + 1].equalsIgnoreCase("H2")
                    && tok[i + 2].equalsIgnoreCase("EXISTS")) {
                return true;
            }
        }
        return false;
    }

    private boolean migrateToStore(String fileName, String column) {
        ConfigStore store = clisto.store();
        if (store == null || store.read(column) != null) {
            return false;
        }
        File src = new File(root, fileName);
        if (!src.isFile()) {
            return false;
        }
        try {
            store.write(column, Files.readAllBytes(src.toPath()));
            clisto.events().movedToStore(fileName);
            return true;
        } catch (IOException e) {
            clisto.events().moveToStoreFailed(fileName, e);
            return false;
        }
    }

    private boolean renameDir(String src, String dst) {
        File from = resolveUnder(root, root, src);
        File to = resolveUnder(root, root, dst);
        if (from == null || to == null || !from.exists() || from.equals(to) || to.exists()) {
            return false;
        }
        try {
            File parent = to.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            Files.move(from.toPath(), to.toPath());
            return true;
        } catch (IOException e) {
            clisto.events().renameFailed(src, dst, e);
            return false;
        }
    }

    private boolean moveEntry(String src, String dst) {
        File from = resolveUnder(root, root, src);
        if (from == null || !from.exists()) {
            return false;
        }
        File to = resolveUnder(root, from.getParentFile(), dst);
        if (to == null) {
            return false;
        }
        if (to.isDirectory()) {
            to = new File(to, from.getName());
        }
        if (from.equals(to)) {
            return false;
        }
        try {
            File parent = to.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            if (to.exists()) {
                return false;
            }
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException atomicFailed) {
            try {
                Files.move(from.toPath(), to.toPath());
                return true;
            } catch (IOException e) {
                clisto.events().moveFailed(src, dst, e);
                return false;
            }
        }
    }

    private static File resolveUnder(File rootDir, File anchorDir, String path) {
        String norm = path.replace('\\', '/');
        Path rootPath = rootDir.toPath().normalize();
        Path anchor;
        if (norm.startsWith("/")) {
            while (norm.startsWith("/")) {
                norm = norm.substring(1);
            }
            anchor = rootPath;
        } else {
            anchor = anchorDir.toPath().normalize();
        }
        Path resolved = anchor.resolve(norm).normalize();
        if (!resolved.startsWith(rootPath)) {
            return null;
        }
        return resolved.toFile();
    }

    private static String fromFile(String[] tok, int i) {
        if (i + 1 < tok.length && tok[i].equalsIgnoreCase("FROM")) {
            return tok[i + 1];
        }
        return "config.yml";
    }

    private Buffer buffer(String file) {
        if (buffers.containsKey(file)) {
            return buffers.get(file);
        }
        File f = new File(root, file);
        Buffer b = null;
        if (f.isFile()) {
            try {
                b = new Buffer(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                b = null;
            }
        }
        buffers.put(file, b);
        return b;
    }

    private static String dslPath(YamlDoc doc, String dotted) {
        YamlDoc.Entry e = doc.find(dotted);
        return e == null ? dotted : e.path();
    }

    private static boolean renameKey(List<String> lines, String oldPath, String newPath) {
        YamlDoc doc = YamlDoc.parse(lines);
        YamlDoc.Entry e = doc.get(dslPath(doc, oldPath));
        if (e == null) {
            return false;
        }
        int lastDot = oldPath.lastIndexOf('.');
        String parent = lastDot < 0 ? null : oldPath.substring(0, lastDot);
        String newLeaf = parent != null && newPath.startsWith(parent + ".")
                ? newPath.substring(parent.length() + 1)
                : newPath.substring(newPath.lastIndexOf('.') + 1);
        String line = lines.get(e.keyLine());
        int colon = YamlText.keyColon(line);
        if (colon < 0) {
            return false;
        }
        lines.set(e.keyLine(), line.substring(0, e.indent()) + newLeaf + line.substring(colon));
        return true;
    }

    private static boolean dropKey(List<String> lines, String path, boolean parent) {
        YamlDoc doc = YamlDoc.parse(lines);
        YamlDoc.Entry e = doc.get(dslPath(doc, path));
        if (e == null) {
            return false;
        }
        int end = parent ? blockEnd(lines, e) : singleKeyEnd(lines, e);
        for (int j = end - 1; j >= e.commentStart(); j--) {
            lines.remove(j);
        }
        return true;
    }

    private static int singleKeyEnd(List<String> lines, YamlDoc.Entry e) {
        int j = e.keyLine() + 1;
        while (j < lines.size()) {
            String t = lines.get(j).trim();
            if (t.isEmpty()) {
                break;
            }
            if (YamlText.indentOf(lines.get(j)) <= e.indent() && !t.startsWith("-")) {
                break;
            }
            j++;
        }
        return j;
    }

    private static int blockEnd(List<String> lines, YamlDoc.Entry e) {
        int end = e.keyLine() + 1;
        int j = e.keyLine() + 1;
        while (j < lines.size()) {
            String t = lines.get(j).trim();
            if (t.isEmpty() || t.startsWith("#")) {
                int k = j;
                while (k < lines.size()) {
                    String tk = lines.get(k).trim();
                    if (tk.isEmpty() || tk.startsWith("#")) {
                        k++;
                        continue;
                    }
                    break;
                }
                if (k >= lines.size() || YamlText.indentOf(lines.get(k)) <= e.indent()) {
                    break;
                }
                j = k;
                continue;
            }
            if (YamlText.indentOf(lines.get(j)) <= e.indent()) {
                break;
            }
            end = j + 1;
            j++;
        }
        return end;
    }
}
