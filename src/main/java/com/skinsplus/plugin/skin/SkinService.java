package com.skinsplus.plugin.skin;

import com.skinsplus.plugin.SkinsPlusPlugin;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
    private static final Pattern TEXTURES_SIGNATURE_PATTERN = Pattern.compile(
            "\"name\"\\s*:\\s*\"textures\"[\\s\\S]*?\"signature\"\\s*:\\s*\"([^\"]+)\""
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
    private final Map<String, SkinData> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SkinService(SkinsPlusPlugin plugin) {
        this.plugin = plugin;
    }

    public void applyRandom(Player player, BiConsumer<Result, String> callback) {
        if (player == null || !player.isOnline()) return;

        CompletableFuture
                .supplyAsync(this::findRandomNameMcSkin)
                .whenComplete((candidate, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;

                            if (error != null || candidate == null) {
                                callback.accept(Result.NOT_FOUND, null);
                                return;
                            }

                            cache.put(candidate.name().toLowerCase(Locale.ROOT), candidate.skin());
                            applySkinData(player, candidate.skin());
                            callback.accept(Result.SUCCESS, candidate.name());
                        })
                );
    }

    private RandomSkinCandidate findRandomNameMcSkin() {
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

            java.util.Collections.shuffle(skinIds);
            int attempts = Math.min(12, skinIds.size());

            for (int i = 0; i < attempts; i++) {
                String skinPage;
                try {
                    skinPage = get("https://namemc.com/skin/" + skinIds.get(i));
                } catch (Exception ignored) {
                    continue;
                }

                Matcher profileMatcher = NAMEMC_PROFILE_PATTERN.matcher(skinPage);
                while (profileMatcher.find()) {
                    String name = profileMatcher.group(1);
                    if (name == null || !name.matches("[A-Za-z0-9_]{1,16}")) continue;

                    // No /skin random, falhas temporárias de rede não poluem o console:
                    // simplesmente tentamos o próximo perfil/skin do catálogo.
                    SkinData skin = fetchOfficialSkinData(name, true);
                    if (skin != null && !isSlimModel(skin)) {
                        return new RandomSkinCandidate(name, skin);
                    }
                }
            }

            plugin.getLogger().warning("Não foi possível obter uma skin clássica do NameMC após "
                    + attempts + " tentativas.");
            return null;
        } catch (HttpTimeoutException exception) {
            plugin.getLogger().warning("Timeout ao consultar o catálogo do NameMC para /skin random.");
            return null;
        } catch (Exception exception) {
            plugin.getLogger().warning("Falha ao procurar skin aleatória no NameMC: " + exception.getMessage());
            return null;
        }
    }

    public void applyOfficialByName(Player player, String skinName, Consumer<Result> callback) {
        if (player == null || skinName == null || !skinName.matches("[A-Za-z0-9_]{1,16}")) {
            callback.accept(Result.INVALID_NAME);
            return;
        }

        String key = skinName.toLowerCase(Locale.ROOT);

        // Para restaurar a skin original/premium, não reutilizamos o cache:
        // buscamos a texture property atual diretamente da Mojang para refletir
        // imediatamente qualquer skin alterada na conta.
        CompletableFuture
                .supplyAsync(() -> fetchOfficialSkinData(skinName))
                .whenComplete((skin, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;

                            if (error != null || skin == null) {
                                callback.accept(Result.NOT_FOUND);
                                return;
                            }

                            cache.put(key, skin);
                            applySkinData(player, skin);
                            callback.accept(Result.SUCCESS);
                        })
                );
    }

    public void applyByName(Player player, String skinName, Consumer<Result> callback) {
        if (player == null || skinName == null || !skinName.matches("[A-Za-z0-9_]{1,16}")) {
            callback.accept(Result.INVALID_NAME);
            return;
        }

        String key = skinName.toLowerCase(Locale.ROOT);
        SkinData cached = cache.get(key);
        if (cached != null) {
            applySkinData(player, cached);
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
            if (error == null && updated != null) {
                SkinData skin = extractSkinData(updated);
                if (skin != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        cache.put(key, skin);
                        applySkinData(player, skin);
                        callback.accept(Result.SUCCESS);
                    });
                    return;
                }
            }

            // Em servidores offline-mode o resolvedor interno do Paper pode não
            // completar alguns perfis por nome. Fazemos um fallback direto aos
            // serviços oficiais da Mojang para resolver UUID e textura.
            CompletableFuture
                    .supplyAsync(() -> fetchOfficialSkinData(skinName))
                    .whenComplete((skin, fallbackError) ->
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                if (!player.isOnline()) return;

                                if (fallbackError != null || skin == null) {
                                    callback.accept(Result.NOT_FOUND);
                                    return;
                                }

                                cache.put(key, skin);
                                applySkinData(player, skin);
                                callback.accept(Result.SUCCESS);
                            })
                    );
        });
    }

    private SkinData fetchOfficialSkinData(String skinName) {
        return fetchOfficialSkinData(skinName, false);
    }

    private SkinData fetchOfficialSkinData(String skinName, boolean quiet) {
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

            String value = valueMatcher.group(1);
            Matcher signatureMatcher = TEXTURES_SIGNATURE_PATTERN.matcher(sessionJson);
            String signature = signatureMatcher.find() ? signatureMatcher.group(1) : null;

            if ((signature == null || signature.isBlank()) && !quiet) {
                plugin.getLogger().warning("A Mojang retornou uma textura sem assinatura para " + skinName + ".");
            }
            return new SkinData(value, signature);
        } catch (HttpTimeoutException exception) {
            if (!quiet) {
                plugin.getLogger().warning("Timeout ao buscar skin oficial para " + skinName + ".");
            }
            return null;
        } catch (HttpStatusException exception) {
            if (!quiet && exception.statusCode() != 404) {
                String kind = exception.statusCode() == 429 ? "rate limit" : "HTTP " + exception.statusCode();
                plugin.getLogger().warning("Falha ao buscar skin oficial para " + skinName + ": " + kind + ".");
            }
            return null;
        } catch (Exception exception) {
            if (!quiet) {
                plugin.getLogger().warning(
                        "Falha de rede ao buscar skin oficial para " + skinName + ": " + exception.getMessage()
                );
            }
            return null;
        }
    }

    private boolean isSlimModel(SkinData skin) {
        if (skin == null || skin.value() == null || skin.value().isBlank()) return false;
        try {
            String textureJson = new String(
                    Base64.getDecoder().decode(skin.value()),
                    StandardCharsets.UTF_8
            );
            // A Mojang marca braços finos com metadata.model = "slim".
            // Quando o campo não existe, o modelo é o clássico (Steve, 4 px).
            return textureJson.matches("(?s).*\\\"model\\\"\\s*:\\s*\\\"slim\\\".*");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private SkinData extractSkinData(PlayerProfile profile) {
        if (!(profile instanceof com.destroystokyo.paper.profile.PlayerProfile paperProfile)) return null;
        for (ProfileProperty property : paperProfile.getProperties()) {
            if (!"textures".equals(property.getName())) continue;
            String value = property.getValue();
            if (value == null || value.isBlank()) continue;
            return new SkinData(value, property.getSignature());
        }
        return null;
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "SkinsPlus/1.0")
                .GET()
                .build();

        HttpResponse<String> response = http.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode());
        }

        return response.body();
    }

    public void reset(Player player, Consumer<Result> callback) {
        if (player == null) {
            callback.accept(Result.NOT_FOUND);
            return;
        }

        // /skin reset significa voltar para a skin ORIGINAL atual da conta
        // Minecraft deste nickname. Em offline-mode não podemos confiar no
        // PlayerProfile local do Bukkit, pois ele pode conter a skin temporária
        // aplicada anteriormente pelo próprio SkinsPlus.
        applyOfficialByName(player, player.getName(), callback);
    }

    private void applySkinData(Player player, SkinData skin) {
        if (player == null || skin == null) return;

        com.destroystokyo.paper.profile.PlayerProfile target = player.getPlayerProfile();
        target.removeProperty("textures");
        target.setProperty(new ProfileProperty("textures", skin.value(), skin.signature()));
        player.setPlayerProfile(target);

        // O fluxo moderno do Paper (também usado pelo SkinsRestorer) força uma
        // atualização de health/food após setPlayerProfile. Isso faz o próprio
        // cliente concluir o refresh do perfil sem reentrar no estágio de configuração.
        try {
            player.sendHealthUpdate();
        } catch (NoSuchMethodError ignored) {
            // Compatibilidade defensiva caso a API mude.
        }

        // Mantém a nametag customizada sincronizada depois que o Paper re-registra
        // o perfil do jogador para os clientes.
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
        com.destroystokyo.paper.profile.PlayerProfile target = player.getPlayerProfile();
        target.removeProperty("textures");
        player.setPlayerProfile(target);
    }

    private record SkinData(String value, String signature) {}

    private record RandomSkinCandidate(String name, SkinData skin) {}

    private static final class HttpStatusException extends Exception {
        private final int statusCode;

        private HttpStatusException(int statusCode) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    public enum Result {
        SUCCESS,
        INVALID_NAME,
        NOT_FOUND
    }
}
