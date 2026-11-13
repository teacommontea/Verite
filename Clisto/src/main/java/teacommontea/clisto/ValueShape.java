package teacommontea.clisto;

import java.util.Locale;
import java.util.Set;

public enum ValueShape {
    EMPTY, BRACES, LIST, BOOLEAN, NUMBER, QUOTED, PLAIN, BLOCK_LIST, BLOCK_TEXT;

    private static final Set<String> BOOLEAN_WORDS = Set.of("true", "false", "yes", "no", "on", "off");

    public static ValueShape of(String value) {
        String t = value.trim();
        if (t.isEmpty()) {
            return EMPTY;
        }
        if (t.startsWith("{") && t.endsWith("}")) {
            return BRACES;
        }
        if (t.startsWith("[") && t.endsWith("]")) {
            return LIST;
        }
        if (isBooleanWord(t)) {
            return BOOLEAN;
        }
        if (isNumber(t)) {
            return NUMBER;
        }
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return QUOTED;
        }
        return PLAIN;
    }

    public static boolean same(String a, String b) {
        return of(a) == of(b);
    }

    public static boolean isBooleanWord(String t) {
        return BOOLEAN_WORDS.contains(t.toLowerCase(Locale.ROOT));
    }

    public static boolean isNumber(String t) {
        boolean dot = false;
        boolean digit = false;
        int i = 0;
        if (i < t.length() && (t.charAt(i) == '-' || t.charAt(i) == '+')) {
            i++;
        }
        for (; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '.') {
                if (dot) {
                    return false;
                }
                dot = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else {
                return false;
            }
        }
        return digit;
    }

    public boolean isText() {
        return this == QUOTED || this == PLAIN || this == BLOCK_TEXT;
    }

    public boolean isList() {
        return this == LIST || this == BLOCK_LIST;
    }

    public boolean accepts(ValueShape owner) {
        if (this == owner || this == EMPTY) {
            return true;
        }
        if (isText()) {
            return owner.isText() || owner == NUMBER || owner == BOOLEAN;
        }
        return isList() && owner.isList();
    }
}
