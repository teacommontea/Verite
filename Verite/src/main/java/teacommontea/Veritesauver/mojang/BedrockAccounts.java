package teacommontea.veritesauver.mojang;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BedrockAccounts {

    private static final String ENDPOINT = "https://api.geysermc.org/v2/xbox/xuid/";
    private static final String DEFAULT_PREFIX = ".";
    private static final Pattern XUID = Pattern.compile("\"xuid\"\\s*:\\s*\"?(\\d+)");
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private static final int MAX_CACHE = 2048;
    private static final long NEGATIVE_TTL_MS = 10L * 60L * 1000L;
    private static final long BACKOFF_MS = 60L * 1000L;

    private record Cached(SauverMojang.Profile profile, long expires) {}

    private static final ConcurrentHashMap<String, Cached> CACHE = new ConcurrentHashMap<>();
    private static volatile long blockedUntil;

    private BedrockAccounts() {}

    public static String prefix() {
        String fromFloodgate = floodgatePrefix();
        return fromFloodgate != null ? fromFloodgate : DEFAULT_PREFIX;
    }

    private static String floodgatePrefix() {
        try {
            Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return null;
            }
            Object prefix = api.getMethod("getPlayerPrefix").invoke(instance);
            return prefix == null ? "" : prefix.toString();
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    public static boolean isBedrock(String name) {
        String prefix = prefix();
        return name != null && !prefix.isEmpty() && name.startsWith(prefix)
                && validGamertag(name.substring(prefix.length()));
    }

    public static boolean unprefixed() {
        String fromFloodgate = floodgatePrefix();
        return fromFloodgate != null && fromFloodgate.isEmpty();
    }

    static boolean validGamertag(String gamertag) {
        if (gamertag.isEmpty() || gamertag.length() > 16) {
            return false;
        }
        for (int i = 0; i < gamertag.length(); i++) {
            char c = gamertag.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == ' ';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static SauverMojang.Profile lookup(String name) {
        String prefix = prefix();
        String gamertag = name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        if (!validGamertag(gamertag)) {
            return SauverMojang.Profile.NOT_FOUND;
        }
        String key = gamertag.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        Cached cached = CACHE.get(key);
        if (cached != null) {
            if (cached.expires() == 0L || cached.expires() > now) {
                return cached.profile();
            }
            CACHE.remove(key);
        }
        if (now < blockedUntil) {
            return SauverMojang.Profile.RATE_LIMITED;
        }

        SauverMojang.Profile result = query(gamertag, prefix + gamertag.replace(' ', '_'));
        if (result.status() == SauverMojang.Status.RATE_LIMITED) {
            blockedUntil = now + BACKOFF_MS;
            return result;
        }
        if (result.status() == SauverMojang.Status.FOUND) {
            store(key, new Cached(result, 0L));
        } else if (result.status() == SauverMojang.Status.NOT_FOUND) {
            store(key, new Cached(result, now + NEGATIVE_TTL_MS));
        }
        return result;
    }

    private static void store(String key, Cached value) {
        if (CACHE.size() >= MAX_CACHE) {
            CACHE.clear();
        }
        CACHE.put(key, value);
    }

    private static SauverMojang.Profile query(String gamertag, String displayName) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT + encode(gamertag.replace('_', ' '))))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code == 404 || code == 204) {
                return SauverMojang.Profile.NOT_FOUND;
            }
            if (code == 429) {
                return SauverMojang.Profile.RATE_LIMITED;
            }
            if (code != 200) {
                return SauverMojang.Profile.UNKNOWN;
            }
            Matcher m = XUID.matcher(resp.body() == null ? "" : resp.body());
            if (!m.find()) {
                return SauverMojang.Profile.NOT_FOUND;
            }
            return SauverMojang.Profile.found(new UUID(0L, Long.parseLong(m.group(1))), displayName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SauverMojang.Profile.UNKNOWN;
        } catch (Exception e) {
            return SauverMojang.Profile.UNKNOWN;
        }
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
