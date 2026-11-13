package teacommontea.clisto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BlockMerge {

    public record Appended(String text, int count) {

        public boolean isEmpty() {
            return count == 0;
        }
    }

    private record Block(List<String> lines, String key, boolean section, String realm) {}

    private BlockMerge() {}

    public static String normalise(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t.replaceAll("\\s+", " "));
        }
        return sb.toString();
    }

    private static List<Block> blocks(List<String> lines, BlockFormat format) {
        List<Block> blocks = new ArrayList<>();
        String currentRealm = null;
        int i = 0;
        while (i < lines.size()) {
            String raw = lines.get(i);
            String t = raw.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                i++;
                continue;
            }
            if (format.isSection(t)) {
                currentRealm = t;
                blocks.add(new Block(List.of(raw), normalise(List.of(t)), true, currentRealm));
                i++;
                continue;
            }
            List<String> block = new ArrayList<>();
            block.add(raw);
            int j = i + 1;
            while (j < lines.size()) {
                String next = lines.get(j);
                String nt = next.trim();
                if (nt.isEmpty() || format.startsBlock(nt)) {
                    break;
                }
                block.add(next);
                j++;
            }
            blocks.add(new Block(block, normalise(block), false, currentRealm));
            i = j;
        }
        return blocks;
    }

    public static Appended missing(String deployed, String bundled, BlockFormat format) {
        List<Block> deployedBlocks = blocks(YamlText.splitLines(deployed), format);
        Set<String> deployedKeys = new LinkedHashSet<>();
        Set<String> deployedRealms = new LinkedHashSet<>();
        for (Block b : deployedBlocks) {
            deployedKeys.add(b.key);
            if (b.section) {
                deployedRealms.add(b.key);
            }
        }
        List<Block> missing = new ArrayList<>();
        for (Block b : blocks(YamlText.splitLines(bundled), format)) {
            if (!b.section && !deployedKeys.contains(b.key)) {
                missing.add(b);
            }
        }
        if (missing.isEmpty()) {
            return new Appended("", 0);
        }
        StringBuilder append = new StringBuilder();
        if (!deployed.isEmpty() && !deployed.endsWith("\n")) {
            append.append('\n');
        }
        String lastRealm = null;
        for (Block b : missing) {
            if (b.realm != null && !b.realm.equals(lastRealm) && !deployedRealms.contains(b.realm)) {
                append.append('\n').append(b.realm).append('\n');
                deployedRealms.add(b.realm);
            }
            lastRealm = b.realm;
            append.append('\n');
            for (String line : b.lines) {
                append.append(line).append('\n');
            }
        }
        return new Appended(append.toString(), missing.size());
    }

    public static Appended carried(String current, String previous, BlockFormat format, String notice) {
        Set<String> currentKeys = new LinkedHashSet<>();
        for (Block b : blocks(YamlText.splitLines(current), format)) {
            if (!b.section) {
                currentKeys.add(format.identity(b.lines));
            }
        }
        List<Block> ownerOnly = new ArrayList<>();
        for (Block b : blocks(YamlText.splitLines(previous), format)) {
            if (!b.section && !currentKeys.contains(format.identity(b.lines))) {
                ownerOnly.add(b);
            }
        }
        if (ownerOnly.isEmpty()) {
            return new Appended("", 0);
        }
        StringBuilder append = new StringBuilder();
        if (!current.isEmpty() && !current.endsWith("\n")) {
            append.append('\n');
        }
        append.append('\n').append(notice).append('\n');
        String lastRealm = null;
        for (Block b : ownerOnly) {
            if (b.realm != null && !b.realm.equals(lastRealm)) {
                append.append('\n').append(b.realm).append('\n');
                lastRealm = b.realm;
            }
            append.append('\n');
            for (String line : b.lines) {
                append.append(line).append('\n');
            }
        }
        return new Appended(append.toString(), ownerOnly.size());
    }
}
