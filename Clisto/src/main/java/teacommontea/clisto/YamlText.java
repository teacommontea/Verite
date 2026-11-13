package teacommontea.clisto;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YamlText {

    private static final Pattern BRACE_VALUE =
            Pattern.compile("^(\\s*[^:#\\s][^:]*:\\s*)(\\{.*\\})(\\s*)$");

    private YamlText() {}

    public static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            out.add(s);
        }
        if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    public static String eolOf(String text) {
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    public static boolean endsWithNewline(String text) {
        return text.endsWith("\n") || text.endsWith("\r");
    }

    public static String join(List<String> lines, String eol, boolean trailingNewline) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append(eol);
            }
        }
        if (trailingNewline && !lines.isEmpty()) {
            sb.append(eol);
        }
        return sb.toString();
    }

    public static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    public static String spaces(int n) {
        return " ".repeat(Math.max(0, n));
    }

    public static String reindent(String line, int delta) {
        if (delta == 0 || line.trim().isEmpty()) {
            return line;
        }
        if (delta > 0) {
            return spaces(delta) + line;
        }
        return line.substring(Math.min(-delta, indentOf(line)));
    }

    public static boolean isComment(String line) {
        return line.trim().startsWith("#");
    }

    public static boolean isBlank(String line) {
        return line.trim().isEmpty();
    }

    public static int keyColon(String line) {
        boolean inq = false;
        char q = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inq) {
                if (c == q) {
                    inq = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inq = true;
                q = c;
                continue;
            }
            if (c == '#') {
                return -1;
            }
            if (c == ':' && (i + 1 >= line.length() || line.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    public static String keyName(String line) {
        int colon = keyColon(line);
        return colon < 0 ? null : line.substring(indentOf(line), colon).trim();
    }

    public static int commentStart(String value) {
        int i = 0;
        while (i < value.length() && value.charAt(i) == ' ') {
            i++;
        }
        if (i < value.length()) {
            char c = value.charAt(i);
            if (c == '"' || c == '\'') {
                int close = closingQuote(value, i);
                if (close < 0) {
                    return -1;
                }
                i = close + 1;
            } else if (c == '{' || c == '[') {
                int close = closingBracket(value, i);
                if (close < 0) {
                    return -1;
                }
                i = close + 1;
            }
        }
        for (; i < value.length(); i++) {
            if (value.charAt(i) == '#' && (i == 0 || value.charAt(i - 1) == ' ' || value.charAt(i - 1) == '\t')) {
                return i;
            }
        }
        return -1;
    }

    private static int closingQuote(String s, int open) {
        char q = s.charAt(open);
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q == '"' && c == '\\') {
                i++;
                continue;
            }
            if (c == q) {
                if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    private static int closingBracket(String s, int open) {
        char o = s.charAt(open);
        char c = o == '{' ? '}' : ']';
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                int close = closingQuote(s, i);
                if (close < 0) {
                    return -1;
                }
                i = close;
                continue;
            }
            if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    public static String scalarValue(String line) {
        int colon = keyColon(line);
        if (colon < 0) {
            return null;
        }
        String after = line.substring(colon + 1);
        int hash = commentStart(after);
        String v = (hash >= 0 ? after.substring(0, hash) : after).trim();
        return v.isEmpty() ? null : v;
    }

    public static String trailingComment(String line) {
        int colon = keyColon(line);
        if (colon < 0) {
            return "";
        }
        String after = line.substring(colon + 1);
        int hash = commentStart(after);
        return hash < 0 ? "" : after.substring(hash);
    }

    public static String replaceScalarValue(String line, String newValue) {
        int colon = keyColon(line);
        if (colon < 0) {
            return line;
        }
        String after = line.substring(colon + 1);
        int hash = commentStart(after);
        String trailing = hash >= 0 ? after.substring(hash) : "";
        int lead = 0;
        while (lead < after.length() && after.charAt(lead) == ' ') {
            lead++;
        }
        String leadSpace = lead == 0 ? " " : after.substring(0, lead);
        return line.substring(0, colon + 1) + leadSpace + newValue + (trailing.isEmpty() ? "" : " " + trailing);
    }

    public static String withTrailingComment(String line, String trailing) {
        int colon = keyColon(line);
        if (colon < 0) {
            return line;
        }
        String after = line.substring(colon + 1);
        int hash = commentStart(after);
        String head = (line.substring(0, colon + 1) + (hash >= 0 ? after.substring(0, hash) : after))
                .replaceAll("\\s+$", "");
        return trailing.isEmpty() ? head : head + " " + trailing;
    }

    public static String valueOf(String line) {
        String rest = scalarValue(line);
        if (rest == null) {
            return keyColon(line) < 0 ? null : "";
        }
        if (rest.length() >= 2 && rest.charAt(0) == '"' && rest.charAt(rest.length() - 1) == '"') {
            rest = rest.substring(1, rest.length() - 1);
        }
        return rest;
    }

    public static String unquote(String v) {
        if (v.length() >= 2) {
            char f = v.charAt(0);
            if ((f == '"' || f == '\'') && v.charAt(v.length() - 1) == f) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    public static String protectBraces(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 64);
        for (String line : raw.split("\n", -1)) {
            Matcher m = BRACE_VALUE.matcher(line);
            if (m.matches() && !m.group(2).contains("'")) {
                out.append(m.group(1)).append('\'').append(m.group(2)).append('\'').append(m.group(3));
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        if (out.length() > 0 && !raw.endsWith("\n")) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }
}
