package teacommontea.clisto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class YamlDoc {

    public static final char SEP = '\u0001';

    private static final Pattern BLOCK_INDICATOR = Pattern.compile("^[|>][+-]?[0-9]?[+-]?$");

    public static final class Entry {
        private final int index;
        private final String path;
        private final String name;
        private final int indent;
        private final int keyLine;
        private final int parent;
        private boolean hasChildren;
        private int valueEnd;
        private int subtreeEnd;
        private int regionStart;
        private int commentStart;

        Entry(int index, String path, String name, int indent, int keyLine, int parent) {
            this.index = index;
            this.path = path;
            this.name = name;
            this.indent = indent;
            this.keyLine = keyLine;
            this.parent = parent;
        }

        public int index() { return index; }
        public String path() { return path; }
        public String name() { return name; }
        public int indent() { return indent; }
        public int keyLine() { return keyLine; }
        public int parent() { return parent; }
        public boolean isParent() { return hasChildren; }
        public int valueEnd() { return valueEnd; }
        public int subtreeEnd() { return subtreeEnd; }
        public int regionStart() { return regionStart; }
        public int commentStart() { return commentStart; }

        public String dotted() {
            return path.replace(SEP, '.');
        }

        public String parentPath() {
            int cut = path.lastIndexOf(SEP);
            return cut < 0 ? "" : path.substring(0, cut);
        }
    }

    private final List<String> lines;
    private final List<Entry> entries;
    private final Map<String, Entry> byPath;
    private final int eofRegionStart;

    private YamlDoc(List<String> lines, List<Entry> entries, int eofRegionStart) {
        this.lines = lines;
        this.entries = entries;
        this.eofRegionStart = eofRegionStart;
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : entries) {
            map.putIfAbsent(e.path, e);
        }
        this.byPath = map;
    }

    public static YamlDoc parse(String text) {
        return parse(YamlText.splitLines(text));
    }

    public static YamlDoc parse(List<String> source) {
        List<String> lines = Collections.unmodifiableList(new ArrayList<>(source));
        List<Entry> entries = new ArrayList<>();
        List<Entry> stack = new ArrayList<>();
        int opaqueAbove = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = YamlText.indentOf(line);
            if (opaqueAbove >= 0) {
                if (indent > opaqueAbove) {
                    continue;
                }
                opaqueAbove = -1;
            }
            if (trimmed.startsWith("-")) {
                opaqueAbove = indent;
                continue;
            }
            int colon = YamlText.keyColon(line);
            if (colon < 0) {
                continue;
            }
            String name = line.substring(indent, colon).trim();
            if (name.isEmpty()) {
                continue;
            }
            while (!stack.isEmpty() && stack.get(stack.size() - 1).indent >= indent) {
                stack.remove(stack.size() - 1);
            }
            Entry parent = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            String path = parent == null ? name : parent.path + SEP + name;
            Entry e = new Entry(entries.size(), path, name, indent, i, parent == null ? -1 : parent.index);
            if (parent != null) {
                parent.hasChildren = true;
            }
            entries.add(e);
            stack.add(e);
            String value = YamlText.scalarValue(line);
            if (value != null && BLOCK_INDICATOR.matcher(value).matches()) {
                opaqueAbove = indent;
            }
        }

        Set<Integer> keyLines = new HashSet<>();
        for (Entry e : entries) {
            keyLines.add(e.keyLine);
        }
        for (Entry e : entries) {
            e.valueEnd = e.hasChildren ? e.keyLine + 1 : leafEnd(lines, e, keyLines);
            e.subtreeEnd = e.valueEnd;
        }
        for (int k = entries.size() - 1; k >= 0; k--) {
            Entry e = entries.get(k);
            if (e.parent >= 0) {
                Entry p = entries.get(e.parent);
                p.subtreeEnd = Math.max(p.subtreeEnd, e.subtreeEnd);
            }
        }
        int prevEnd = 0;
        for (Entry e : entries) {
            e.regionStart = Math.min(prevEnd, e.keyLine);
            int start = e.keyLine;
            for (int j = e.keyLine - 1; j >= e.regionStart && lines.get(j).trim().startsWith("#"); j--) {
                start = j;
            }
            e.commentStart = start;
            prevEnd = e.valueEnd;
        }
        return new YamlDoc(lines, Collections.unmodifiableList(entries), prevEnd);
    }

    private static int leafEnd(List<String> lines, Entry e, Set<Integer> keyLines) {
        int last = e.keyLine;
        for (int j = e.keyLine + 1; j < lines.size(); j++) {
            String line = lines.get(j);
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (keyLines.contains(j)) {
                break;
            }
            int indent = YamlText.indentOf(line);
            if (indent > e.indent || (t.startsWith("-") && indent >= e.indent)) {
                last = j;
                continue;
            }
            break;
        }
        return last + 1;
    }

    public List<String> lines() {
        return lines;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry entry(int index) {
        return index < 0 ? null : entries.get(index);
    }

    public Entry get(String path) {
        return byPath.get(path);
    }

    public boolean has(String path) {
        return byPath.containsKey(path);
    }

    public Entry find(String dotted) {
        Entry e = byPath.get(dotted);
        if (e != null) {
            return e;
        }
        for (Entry candidate : entries) {
            if (candidate.dotted().equals(dotted)) {
                return candidate;
            }
        }
        return null;
    }

    public int eofRegionStart() {
        return eofRegionStart;
    }

    public List<Entry> children(String parentPath) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.parentPath().equals(parentPath) && byPath.get(e.path) == e) {
                out.add(e);
            }
        }
        return out;
    }

    public List<String> region(Entry e) {
        return lines.subList(e.regionStart, e.keyLine);
    }

    public List<String> eofRegion() {
        return lines.subList(eofRegionStart, lines.size());
    }

    public List<String> valueLines(Entry e) {
        return lines.subList(e.keyLine + 1, e.valueEnd);
    }

    public String scalar(Entry e) {
        return YamlText.scalarValue(lines.get(e.keyLine));
    }

    public String normalisedValue(Entry e) {
        StringBuilder sb = new StringBuilder();
        String scalar = scalar(e);
        sb.append(scalar == null ? "" : scalar);
        for (String line : valueLines(e)) {
            if (line.trim().isEmpty()) {
                continue;
            }
            sb.append('\n').append(YamlText.reindent(line, -e.indent).replaceAll("\\s+$", ""));
        }
        return sb.toString();
    }

    public ValueShape shape(Entry e) {
        String scalar = scalar(e);
        if (scalar == null) {
            for (String line : valueLines(e)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                return t.startsWith("-") ? ValueShape.BLOCK_LIST : ValueShape.BLOCK_TEXT;
            }
            return ValueShape.EMPTY;
        }
        if (BLOCK_INDICATOR.matcher(scalar).matches()) {
            return ValueShape.BLOCK_TEXT;
        }
        return ValueShape.of(scalar);
    }

    public static String normaliseRegion(List<String> region) {
        StringBuilder sb = new StringBuilder();
        for (String line : region) {
            String t = line.trim();
            if (!t.isEmpty()) {
                sb.append(t).append('\n');
            }
        }
        return sb.toString();
    }
}
