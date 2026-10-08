package com.skinsplus.plugin.skin;

import com.skinsplus.plugin.SkinsPlusPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class SkinService {

    private final SkinsPlusPlugin plugin;
    private final Map<String, PlayerTextures> cache = new ConcurrentHashMap<>();

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

        lookup.update().whenComplete((updated, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    if (error != null || updated == null || updated.getTextures().isEmpty()) {
                        callback.accept(Result.NOT_FOUND);
                        return;
                    }

                    PlayerTextures textures = updated.getTextures();
                    cache.put(key, textures);
                    applyTextures(player, textures);
                    callback.accept(Result.SUCCESS);
                })
        );
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
        player.setPlayerProfile(target);
    }

    private void clearSkin(Player player) {
        PlayerProfile target = player.getPlayerProfile();
        target.setTextures(null);
        player.setPlayerProfile(target);
    }

    public enum Result {
        SUCCESS,
        INVALID_NAME,
        NOT_FOUND
    }
}
