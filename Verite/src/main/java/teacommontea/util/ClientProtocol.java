package teacommontea.util;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class ClientProtocol {

    private static final String VIA = "ViaVersion";

    private static volatile ClassLoader viaLoader;
    private static volatile Method viaGetApi;
    private static volatile Method viaPlayerVersion;

    private ClientProtocol() {}

    public static int of(Player player) {
        int via = fromVia(player.getUniqueId());
        return via > 0 ? via : fromServer(player);
    }

    private static int fromServer(Player player) {
        try {
            Object v = Player.class.getMethod("getProtocolVersion").invoke(player);
            return v instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int fromVia(UUID player) {
        Plugin via = Bukkit.getPluginManager().getPlugin(VIA);
        if (via == null || !via.isEnabled()) {
            return 0;
        }
        try {
            ClassLoader loader = via.getClass().getClassLoader();
            if (loader != viaLoader) {
                Class<?> entry = Class.forName("com.viaversion.viaversion.api.Via", true, loader);
                Class<?> api = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, loader);
                viaGetApi = entry.getMethod("getAPI");
                viaPlayerVersion = api.getMethod("getPlayerVersion", UUID.class);
                viaLoader = loader;
            }
            Object version = viaPlayerVersion.invoke(viaGetApi.invoke(null), player);
            return version instanceof Integer i ? i : 0;
        } catch (Throwable t) {
            return 0;
        }
    }
}
