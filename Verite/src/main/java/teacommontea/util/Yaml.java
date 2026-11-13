package teacommontea.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.bukkit.configuration.file.YamlConfiguration;

import teacommontea.clisto.YamlText;

public final class Yaml {

    private Yaml() {}

    public static YamlConfiguration loadYaml(File file) {
        if (file == null || !file.exists()) {
            return new YamlConfiguration();
        }
        try {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            YamlConfiguration y = new YamlConfiguration();
            y.loadFromString(YamlText.protectBraces(raw));
            return y;
        } catch (Exception e) {
            return YamlConfiguration.loadConfiguration(file);
        }
    }
}
