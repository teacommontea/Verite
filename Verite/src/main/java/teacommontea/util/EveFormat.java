package teacommontea.util;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import teacommontea.clisto.BlockFormat;
import teacommontea.clisto.BlockMerge;

public final class EveFormat implements BlockFormat {

    public static final EveFormat INSTANCE = new EveFormat();

    private static final Set<String> RULE_MODIFIERS = Set.of(
            "SUFFIX", "PREFIX", "PLURAL", "NEVER", "SKELETON", "BEFORE", "AFTER",
            "FLAG", "GAP", "ALSO", "MAYBE", "SWAP", "LETTER", "LETTERS");

    private EveFormat() {}

    @Override
    public boolean startsBlock(String trimmed) {
        return trimmed.startsWith("HEAR ") || trimmed.equals("HEAR")
                || trimmed.startsWith("FIND ") || trimmed.equals("FIND")
                || trimmed.startsWith("MATCH ") || trimmed.equals("MATCH")
                || trimmed.startsWith("ANYWHERE ") || trimmed.equals("ANYWHERE")
                || isSection(trimmed)
                || trimmed.startsWith("DEFINE ")
                || trimmed.startsWith("LET ");
    }

    @Override
    public boolean isSection(String trimmed) {
        return trimmed.startsWith("REALM ") || trimmed.equals("REALM");
    }

    @Override
    public String identity(List<String> lines) {
        boolean isRule = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("HEAR") || t.startsWith("FIND")
                    || t.startsWith("MATCH") || t.startsWith("ANYWHERE")) {
                isRule = true;
                break;
            }
            if (!t.isEmpty()) {
                break;
            }
        }
        if (!isRule) {
            return BlockMerge.normalise(lines);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String head = t;
            int paren = head.indexOf('(');
            int space = head.indexOf(' ');
            int cut = paren < 0 ? space : (space < 0 ? paren : Math.min(paren, space));
            if (cut > 0) head = head.substring(0, cut);
            if (RULE_MODIFIERS.contains(head.toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(t.replaceAll("\s+", " "));
        }
        return sb.toString();
    }
}
