package teacommontea.veritedoux;

import org.bukkit.plugin.Plugin;
import teacommontea.veritedoux.preprocess.EveLexicon;
import teacommontea.veritedoux.preprocess.EveSegment;

public final class EveCommands {

    private EveCommands() {}

    public static void loadAll(Plugin plugin) {
        EveLexicon.load(plugin);
        EveSegment.load(plugin);
        EveEntry.load(plugin);
    }
}
