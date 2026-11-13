package teacommontea.dashboard;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.plugin.Plugin;

import teacommontea.clisto.ValueShape;
import teacommontea.clisto.YamlDoc;
import teacommontea.util.SelfHeal;

public final class DashboardConfig {

    private static final String CONFIG = "config.yml";

    private DashboardConfig() {}

    public static String describe(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), CONFIG);
        if (!file.isFile()) {
            return "null";
        }

        YamlDoc doc;
        try {
            doc = YamlDoc.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "null";
        }

        List<String> keys = new ArrayList<>();
        for (YamlDoc.Entry e : doc.entries()) {
            String value = doc.scalar(e);
            if (value == null) {
                continue;
            }
            keys.add(Json.object(
                    "path", e.dotted(),
                    "name", e.name(),
                    "section", e.parentPath().replace(YamlDoc.SEP, '.'),
                    "value", value,
                    "kind", kind(value),
                    "comment", comment(doc, e)
            ));
        }

        return Json.object(
                "file", CONFIG,
                "keys", Json.raw(Json.array(keys))
        );
    }

    public static boolean set(Plugin plugin, String dotted, String value) throws Exception {
        if (dotted == null || value == null) {
            return false;
        }

        File file = new File(plugin.getDataFolder(), CONFIG);
        if (!file.isFile()) {
            return false;
        }

        YamlDoc doc = YamlDoc.parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        String current = null;
        for (YamlDoc.Entry e : doc.entries()) {
            String v = doc.scalar(e);
            if (v != null && e.dotted().equals(dotted)) {
                current = v;
                break;
            }
        }
        if (current == null) {
            return false;
        }

        if (shape(current) != shape(value)) {
            throw new IllegalArgumentException(
                    teacommontea.util.Lang.of("dashboard.config.wrong.kind", "path", dotted, "kind", kind(current)));
        }

        File backup = new File(plugin.getDataFolder(), CONFIG + ".bak");
        Files.copy(file.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return SelfHeal.clisto(plugin).set(CONFIG, dotted, value);
    }

    private static String kind(String value) {
        return switch (shape(value)) {
            case 1 -> "braces";
            case 2 -> "list";
            case 3 -> "boolean";
            case 4 -> "number";
            default -> "text";
        };
    }

    private static int shape(String value) {
        String t = value.trim();
        if (t.isEmpty()) {
            return 0;
        }
        if (t.startsWith("{") && t.endsWith("}")) {
            return 1;
        }
        if (t.startsWith("[") && t.endsWith("]")) {
            return 2;
        }
        String low = t.toLowerCase(Locale.ROOT);
        if (low.equals("true") || low.equals("false")) {
            return 3;
        }
        if (ValueShape.isNumber(t)) {
            return 4;
        }
        return 6;
    }

    private static String comment(YamlDoc doc, YamlDoc.Entry e) {
        List<String> parts = new ArrayList<>();
        for (int i = e.commentStart(); i < e.keyLine(); i++) {
            String text = doc.lines().get(i).trim().substring(1).trim();
            if (!text.isEmpty() && !text.startsWith("----")) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }
}
