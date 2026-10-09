package com.skinsplus.plugin.skin;

import com.skinsplus.plugin.SkinsPlusPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkinService {

    private static final Pattern ID_PATTERN = Pattern.compile(
            "\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\""
    );
    private static final Pattern TEXTURES_VALUE_PATTERN = Pattern.compile(
            "\"name\"\\s*:\\s*\"textures\"[\\s\\S]*?\"value\"\\s*:\\s*\"([^\"]+)\""
    );
    private static final Pattern SKIN_URL_PATTERN = Pattern.compile(
            "\"SKIN\"\\s*:\\s*\\{[\\s\\S]*?\"url\"\\s*:\\s*\"([^\"]+)\""
    );

    private final SkinsPlusPlugin plugin;
    private final Map<String, PlayerTextures> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SkinService(SkinsPlusPlugin plugin) {
        this.plugin = plugin;
    }

    public void applyByName(Player player, String skinName, Consumer<Result> callback) {
        if (player == null || skinName == null || !skinName.matches("[A-Za-z0-9_]{1,16}")) {
            callback.accept(Result.INVALID_NAME);
            return;
        }

        String key = skinName.toLowerCase(Locale.ROOT);
        PlayerTextures cached = cache.get(key);
        if (cached != null && !cached.isEmpty()) {
            applyTextures(player, cached);
            callback.accept(Result.SUCCESS);
            return;
        }

        PlayerProfile lookup;
        try {
            lookup = Bukkit.createProfile(skinName);
        } catch (IllegalArgumentException exception) {
            callback.accept(Result.INVALID_NAME);
            return;
        }

        lookup.update().whenComplete((updated, error) -> {
            if (error == null && updated != null && !updated.getTextures().isEmpty()) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    PlayerTextures textures = updated.getTextures();
                    cache.put(key, textures);
                    applyTextures(player, textures);
                    callback.accept(Result.SUCCESS);
                });
                return;
            }

            // Em servidores offline-mode o resolvedor interno do Paper pode não
            // completar alguns perfis por nome. Fazemos um fallback direto aos
            // serviços oficiais da Mojang para resolver UUID e textura.
            CompletableFuture
                    .supplyAsync(() -> fetchOfficialTextures(skinName))
                    .whenComplete((textures, fallbackError) ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!player.isOnline()) return;

                                if (fallbackError != null || textures == null || textures.isEmpty()) {
                                    callback.accept(Result.NOT_FOUND);
                                    return;
                                }

                                cache.put(key, textures);
                                applyTextures(player, textures);
                                callback.accept(Result.SUCCESS);
                            })
                    );
        });
    }

    private PlayerTextures fetchOfficialTextures(String skinName) {
        try {
            String profileJson = get("https://api.mojang.com/users/profiles/minecraft/" + skinName);
            Matcher idMatcher = ID_PATTERN.matcher(profileJson);
            if (!idMatcher.find()) return null;

            String uuid = idMatcher.group(1);
            String sessionJson = get(
                    "https://sessionserver.mojang.com/session/minecraft/profile/"
                            + uuid + "?unsigned=false"
            );

            Matcher valueMatcher = TEXTURES_VALUE_PATTERN.matcher(sessionJson);
            if (!valueMatcher.find()) return null;

            byte[] decoded = Base64.getDecoder().decode(valueMatcher.group(1));
            String textureJson = new String(decoded, StandardCharsets.UTF_8);

            Matcher skinMatcher = SKIN_URL_PATTERN.matcher(textureJson);
            if (!skinMatcher.find()) return null;

            URL skinUrl = URI.create(skinMatcher.group(1)).toURL();
            PlayerProfile textureHolder = Bukkit.createProfile(UUID.randomUUID(), "SkinsPlus");
            PlayerTextures textures = textureHolder.getTextures();
            textures.setSkin(skinUrl);
            return textures;
        } catch (Exception exception) {
            plugin.getLogger().warning(
                    "Falha ao buscar skin oficial para " + skinName + ": " + exception.getMessage()
            );
            return null;
        }
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "SkinsPlus/1.0")
                .GET()
                .build();

        HttpResponse<String> response = http.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }

        return response.body();
    }

    public void reset(Player player, Consumer<Result> callback) {
        PlayerProfile lookup;
        try {
            lookup = Bukkit.createProfile(player.getName());
        } catch (IllegalArgumentException exception) {
            clearSkin(player);
            callback.accept(Result.SUCCESS);
            return;
        }

        lookup.update().whenComplete((updated, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    if (error == null && updated != null && !updated.getTextures().isEmpty()) {
                        applyTextures(player, updated.getTextures());
                    } else {
                        clearSkin(player);
                    }
                    callback.accept(Result.SUCCESS);
                })
        );
    }

    private void applyTextures(Player player, PlayerTextures textures) {
        PlayerProfile target = player.getPlayerProfile();
        target.setTextures(textures);
        player.setPlayerProfile((com.destroystokyo.paper.profile.PlayerProfile) (Object) target);
    }

    private void clearSkin(Player player) {
        PlayerProfile target = player.getPlayerProfile();
        target.setTextures(null);
        player.setPlayerProfile((com.destroystokyo.paper.profile.PlayerProfile) (Object) target);
    }

    public enum Result {
        SUCCESS,
        INVALID_NAME,
        NOT_FOUND
    }
}
