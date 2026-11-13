package teacommontea.util;

import org.bukkit.plugin.Plugin;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import teacommontea.clisto.Localiser;
import teacommontea.clisto.YamlText;

public final class ConfigLang {

    private ConfigLang() {}

    public static final String DEFAULT = "en";

    private static final List<String> SUPPORTED = List.of("en", "es", "fr", "it", "pt", "de");

    public static boolean supported(String code) {
        return code != null && SUPPORTED.contains(code.toLowerCase(Locale.ROOT));
    }

    public static List<String> supported() {
        return SUPPORTED;
    }

    public static String read(String configText) {
        String raw = readRaw(configText);
        return supported(raw) ? raw.toLowerCase(Locale.ROOT) : DEFAULT;
    }

    public static String read(Plugin plugin, String configText) {
        String raw = readRaw(configText);
        if (supported(raw)) return raw.toLowerCase(Locale.ROOT);
        if (raw != null && !raw.isEmpty()) {
            plugin.getLogger().warning(ConsoleColours.bad(Lang.of("config.language.unsupported",
                    "value", raw, "supported", String.join(", ", SUPPORTED))));
        }
        return DEFAULT;
    }

    static String readRaw(String configText) {
        if (configText == null) return DEFAULT;
        for (String line : YamlText.splitLines(configText)) {
            String t = line.trim();
            if (t.startsWith("#")) continue;
            int colon = t.indexOf(':');
            if (colon < 0) continue;
            if (!t.substring(0, colon).trim().equals("config.language")) continue;
            String v = t.substring(colon + 1);
            int hash = YamlText.commentStart(v);
            return YamlText.unquote((hash >= 0 ? v.substring(0, hash) : v).trim()).trim();
        }
        return DEFAULT;
    }

    public static String localise(Plugin plugin, String bundledText, String code) {
        if (bundledText == null) return null;
        if (code == null || code.equalsIgnoreCase(DEFAULT) || !supported(code)) return bundledText;
        String translations = readResource(plugin, "lang/config_" + code.toLowerCase(Locale.ROOT) + ".txt");
        if (translations == null) return bundledText;
        try {
            return Localiser.apply(bundledText, Localiser.parse(translations));
        } catch (Exception e) {
            plugin.getLogger().warning(ConsoleColours.faint(Lang.of("config.translate.skipped", "code", code)) + Trace.of(e));
            return bundledText;
        }
    }

    private static String readResource(Plugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
