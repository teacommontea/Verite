package teacommontea.clisto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Localiser {

    public static final class Bundle {
        private final Map<String, String> comments;
        private final Map<String, String> values;

        Bundle(Map<String, String> comments, Map<String, String> values) {
            this.comments = comments;
            this.values = values;
        }

        public Map<String, String> comments() {
            return comments;
        }

        public Map<String, String> values() {
            return values;
        }
    }

    private Localiser() {}

    public static Bundle parse(String text) {
        Map<String, String> comments = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : YamlText.splitLines(text)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf(" = ");
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String body = line.substring(eq + 3);
            boolean isValue = key.startsWith("value:");
            if (isValue) {
                key = key.substring("value:".length()).trim();
            }
            String path = normalisePath(key);
            if (path.isEmpty()) {
                continue;
            }
            if (isValue) {
                values.put(path, body.trim());
            } else {
                comments.put(path, body);
            }
        }
        return new Bundle(comments, values);
    }

    private static String normalisePath(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split(">")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(YamlDoc.SEP);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    public static String apply(String bundledText, Bundle bundle) {
        List<String> lines = YamlText.splitLines(bundledText);
        Map<Integer, String> pathByLine = new LinkedHashMap<>();
        for (YamlDoc.Entry e : YamlDoc.parse(lines).entries()) {
            pathByLine.put(e.keyLine(), e.path());
        }

        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String path = pathByLine.get(i);
            if (path == null) {
                out.add(lines.get(i));
                continue;
            }
            String translated = bundle.comments.get(path);
            if (translated != null) {
                trimTrailing(out, i - commentStartFor(lines, i));
                out.addAll(renderComment(translated, YamlText.indentOf(lines.get(i))));
            }
            out.add(localiseValue(lines.get(i), bundle.values.get(path)));
        }
        return YamlText.join(out, YamlText.eolOf(bundledText), YamlText.endsWithNewline(bundledText));
    }

    private static void trimTrailing(List<String> out, int count) {
        for (int n = 0; n < count && !out.isEmpty(); n++) {
            String last = out.get(out.size() - 1).trim();
            if (!last.startsWith("#") && !last.isEmpty()) {
                break;
            }
            out.remove(out.size() - 1);
        }
    }

    private static List<String> renderComment(String translated, int indent) {
        String pad = YamlText.spaces(indent);
        List<String> rendered = new ArrayList<>();
        for (String part : splitCommentLines(translated)) {
            String body = part.trim();
            rendered.add(body.isEmpty() ? pad + "#" : pad + "# " + body);
        }
        return rendered;
    }

    public static List<String> splitCommentLines(String translated) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < translated.length(); i++) {
            char c = translated.charAt(i);
            if (c == '\\' && i + 1 < translated.length() && translated.charAt(i + 1) == 'n') {
                boolean spaceBefore = i > 0 && translated.charAt(i - 1) == ' ';
                if (spaceBefore) {
                    cur.append("\\n");
                    i++;
                    continue;
                }
                parts.add(cur.toString());
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    private static String localiseValue(String line, String translated) {
        if (translated == null) {
            return line;
        }
        String existing = YamlText.scalarValue(line);
        if (existing == null || !ValueShape.same(existing, translated)) {
            return line;
        }
        return YamlText.replaceScalarValue(line, translated);
    }

    private static int commentStartFor(List<String> lines, int keyLine) {
        int start = keyLine;
        int j = keyLine - 1;
        while (j >= 0) {
            String t = lines.get(j).trim();
            if (t.startsWith("#")) {
                start = j;
                j--;
                continue;
            }
            if (t.isEmpty()) {
                int k = j;
                while (k >= 0 && lines.get(k).trim().isEmpty()) {
                    k--;
                }
                if (k >= 0 && lines.get(k).trim().startsWith("#")) {
                    j = k;
                    continue;
                }
            }
            break;
        }
        return start;
    }
}
