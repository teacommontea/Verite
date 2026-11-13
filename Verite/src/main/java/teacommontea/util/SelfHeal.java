package teacommontea.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.logging.Logger;

import teacommontea.clisto.Action;
import teacommontea.clisto.Clisto;
import teacommontea.clisto.ClistoEvents;
import teacommontea.clisto.ConfigStore;
import teacommontea.clisto.Markers;
import teacommontea.clisto.YamlText;

public final class SelfHeal {

    private static final String CONFIG = "config.yml";

    private SelfHeal() {}

    public static Clisto clisto(Plugin plugin) {
        return Clisto.at(plugin.getDataFolder().toPath())
                .store(store())
                .syntax(text -> new YamlConfiguration().loadFromString(text))
                .events(new Events(plugin.getLogger()))
                .markers(new Markers(
                        Lang.of("config.marker.unknown"),
                        Lang.of("config.marker.duplicate"),
                        Lang.of("config.marker.replaced"),
                        Lang.of("config.marker.failed.start"),
                        Lang.of("config.marker.failed.end"),
                        Lang.of("config.marker.flag.start"),
                        Lang.of("config.marker.expects.boolean"),
                        Lang.of("config.marker.expects.number"),
                        Lang.of("config.marker.expects.text"),
                        Lang.of("config.marker.expects.list"),
                        Lang.of("config.marker.expects.braces"),
                        Lang.of("config.marker.expects.section"),
                        Lang.of("config.marker.expects.value"),
                        Lang.of("config.marker.misplaced"),
                        Lang.of("config.marker.misplaced.top"),
                        Lang.of("config.marker.misspelt")))
                .build();
    }

    private static ConfigStore store() {
        VeriteH2 h2 = VeriteH2.active();
        if (h2 == null) {
            return null;
        }
        return new ConfigStore() {
            @Override
            public byte[] read(String name) {
                return h2.read(name);
            }

            @Override
            public void write(String name, byte[] bytes) {
                h2.write(name, bytes);
            }

            @Override
            public boolean remove(String name) {
                return h2.remove(name);
            }
        };
    }

    public static void healSettings(Plugin plugin) {
        if (!updatesEnabled(plugin)) return;
        try {
            clisto(plugin).heal(CONFIG, deployed -> bundledConfig(plugin, deployed, true));
        } catch (Exception e) {
            plugin.getLogger().warning(ConsoleColours.faint(Lang.of("config.heal.skipped")) + Trace.of(e));
        }
    }

    public static void migrate(Plugin plugin) {
        if (!updatesEnabled(plugin)) return;
        try {
            clisto(plugin).migrate(readResource(plugin, "config_migrations.txt"));
        } catch (Exception e) {
            plugin.getLogger().warning(ConsoleColours.faint(Lang.of("config.migrate.skipped")) + Trace.of(e));
        }
    }

    public static void validate(Plugin plugin) {
        if (updatesEnabled(plugin)) return;
        try {
            clisto(plugin).validate(CONFIG, deployed -> bundledConfig(plugin, deployed, false));
        } catch (Exception e) {
            plugin.getLogger().warning(ConsoleColours.faint(Lang.of("config.validate.skipped")) + Trace.of(e));
        }
    }

    public static void reconcileEve(Plugin plugin, String[] eveConfigs) {
        try {
            Clisto clisto = clisto(plugin);
            String notice = Lang.of("filter.rules.carried");
            int preserved = 0;
            for (String config : eveConfigs) {
                preserved += clisto.carryOver(config, config + ".bak", EveFormat.INSTANCE, notice);
            }
            if (preserved > 0) {
                plugin.getLogger().info(Lang.counted("filter.rules.preserved.total", preserved,
                        "count", ConsoleColours.note(preserved)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning(ConsoleColours.faint(Lang.of("filter.update.skipped")) + Trace.of(e));
        }
    }

    private static boolean updatesEnabled(Plugin plugin) {
        try {
            File deployed = new File(plugin.getDataFolder(), CONFIG);
            if (!deployed.isFile()) return true;
            for (String line : YamlText.splitLines(new String(Files.readAllBytes(deployed.toPath()),
                    StandardCharsets.UTF_8))) {
                String t = line.trim();
                if (t.startsWith("#")) continue;
                int colon = t.indexOf(':');
                if (colon < 0) continue;
                if (!t.substring(0, colon).trim().equals("auto.update.config")) continue;
                String v = t.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
                return !(v.equals("false") || v.equals("no") || v.equals("off"));
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static String bundledConfig(Plugin plugin, String deployedText, boolean report) {
        String bundledText = readResource(plugin, CONFIG);
        if (bundledText == null) return null;
        String code = report ? ConfigLang.read(plugin, deployedText) : ConfigLang.read(deployedText);
        return ConfigLang.localise(plugin, bundledText, code);
    }

    private static String readResource(Plugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Events implements ClistoEvents {
        private final Logger log;

        Events(Logger log) {
            this.log = log;
        }

        @Override
        public void action(Action a) {
            String message = Lang.of("config.repair." + a.kind().name().toLowerCase(Locale.ROOT).replace('_', '.'),
                    "file", a.file(), "line", a.line(), "key", ConsoleColours.note(a.key()),
                    "target", ConsoleColours.note(a.target()));
            if (a.isWarning()) {
                log.warning(ConsoleColours.bad(message));
            } else {
                log.info(message);
            }
        }

        @Override
        public void updated(String file) {
            log.info(Lang.of("config.updated", "file", ConsoleColours.note(file)));
        }

        @Override
        public void unsafe(String file) {
            log.warning(ConsoleColours.bad(Lang.of("config.repair.unsafe", "file", file)));
        }

        @Override
        public void replaced(String file, String backup) {
            log.warning(ConsoleColours.bad(Lang.of("config.repair.replaced", "file", file, "backup", backup)));
        }

        @Override
        public void storeRemoved(String name) {
            log.info(Lang.of("config.store.removed", "name", ConsoleColours.note(name)));
        }

        @Override
        public void movedToStore(String file) {
            log.info(Lang.of("config.moved.database", "file", ConsoleColours.note(file)));
        }

        @Override
        public void moveToStoreFailed(String file, Exception error) {
            log.warning(ConsoleColours.bad(Lang.of("config.move.database.failed", "file", file)) + Trace.of(error));
        }

        @Override
        public void renameFailed(String from, String to, Exception error) {
            log.warning(ConsoleColours.bad(Lang.of("config.rename.failed", "from", from, "to", to)) + Trace.of(error));
        }

        @Override
        public void moveFailed(String from, String to, Exception error) {
            log.warning(ConsoleColours.bad(Lang.of("config.move.failed", "from", from, "to", to)) + Trace.of(error));
        }

        @Override
        public void filesChanged(int removed, int rewritten) {
            log.info(Lang.counted("config.files.removed", removed, "count", ConsoleColours.note(removed)));
            log.info(Lang.counted("config.files.rewrote", rewritten, "count", rewritten));
        }

        @Override
        public void blocksCarried(String file, int count) {
            log.info(Lang.counted("filter.rules.preserved", count, "file", file, "count", ConsoleColours.note(count)));
        }
    }
}
