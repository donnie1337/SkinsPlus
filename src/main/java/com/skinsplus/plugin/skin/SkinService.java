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
import java.util.function.BiConsumer;
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
    private static final Pattern NAMEMC_SKIN_LINK_PATTERN = Pattern.compile(
            "href=\\\"/skin/([0-9a-fA-F]{16})\\\""
    );
    private static final Pattern NAMEMC_PROFILE_PATTERN = Pattern.compile(
            "href=\\\"/profile/([A-Za-z0-9_]{1,16})\\.[0-9]+\\\""
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

    public void applyRandom(Player player, BiConsumer<Result, String> callback) {
        if (player == null || !player.isOnline()) return;

        CompletableFuture
                .supplyAsync(this::findRandomNameMcProfile)
                .whenComplete((skinName, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;

                            if (error != null || skinName == null || skinName.isBlank()) {
                                callback.accept(Result.NOT_FOUND, null);
                                return;
                            }

                            applyByName(player, skinName, result ->
                                    callback.accept(result, result == Result.SUCCESS ? skinName : null)
                            );
                        })
                );
    }

    private String findRandomNameMcProfile() {
        try {
            String catalogHtml = get("https://namemc.com/minecraft-skins");

            java.util.List<String> skinIds = new java.util.ArrayList<>();
            Matcher skinMatcher = NAMEMC_SKIN_LINK_PATTERN.matcher(catalogHtml);
            while (skinMatcher.find()) {
                String id = skinMatcher.group(1);
                if (!skinIds.contains(id)) skinIds.add(id);
            }

            if (skinIds.isEmpty()) {
                plugin.getLogger().warning("O catálogo do NameMC não retornou skins para o /skin random.");
                return null;
            }

            // Tenta alguns resultados aleatórios do catálogo até encontrar um perfil
            // Minecraft válido associado àquela skin.
            java.util.Collections.shuffle(skinIds);
            int attempts = Math.min(8, skinIds.size());

            for (int i = 0; i < attempts; i++) {
                String skinPage = get("https://namemc.com/skin/" + skinIds.get(i));
                Matcher profileMatcher = NAMEMC_PROFILE_PATTERN.matcher(skinPage);

                while (profileMatcher.find()) {
                    String name = profileMatcher.group(1);
                    if (name != null && name.matches("[A-Za-z0-9_]{1,16}")) {
                        return name;
                    }
                }
            }

            return null;
        } catch (Exception exception) {
            plugin.getLogger().warning("Falha ao procurar skin aleatória no NameMC: " + exception.getMessage());
            return null;
        }
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

        // setPlayerProfile re-registra o jogador para os clientes. Isso pode
        // invalidar visualmente entidades passageiras usadas por outros plugins,
        // como a nametag customizada do CargoPlus. Recriamos essa nametag logo
        // depois para que ela continue acompanhando o jogador.
        // Não usamos reenterConfiguration() para forçar refresh da própria skin:
        // durante o estágio de configuração, plugins como CargoPlus podem enviar
        // pacotes de jogo (ex.: scoreboard teams), o que desconecta o cliente.
        // setPlayerProfile() já re-registra o perfil para os clientes de forma segura.
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshCargoNametag(player), 2L);
    }

    private void refreshCargoNametag(Player player) {
        if (player == null || !player.isOnline()) return;
        var cargoPlus = Bukkit.getPluginManager().getPlugin("CargoPlus");
        if (cargoPlus == null || !cargoPlus.isEnabled()) return;

        try {
            Object nicknameColors = cargoPlus.getClass().getMethod("nicknameColors").invoke(cargoPlus);
            Object groups = cargoPlus.getClass().getMethod("groups").invoke(cargoPlus);
            Object permissions = cargoPlus.getClass().getMethod("permissions").invoke(cargoPlus);
            Object group = permissions.getClass()
                    .getMethod("getGroup", UUID.class)
                    .invoke(permissions, player.getUniqueId());

            Class<?> groupServiceClass = Class.forName("com.cargoplus.service.GroupService");
            nicknameColors.getClass()
                    .getMethod("refreshAfterProfileUpdate", Player.class, String.class, groupServiceClass)
                    .invoke(nicknameColors, player, group == null ? null : String.valueOf(group), groups);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("Não foi possível atualizar a nametag do CargoPlus após trocar a skin de "
                    + player.getName() + ": " + exception.getMessage());
        }
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
