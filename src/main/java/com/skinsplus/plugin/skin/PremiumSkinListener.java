package com.skinsplus.plugin.skin;

import com.skinsplus.plugin.SkinsPlusPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Força a skin oficial da Mojang sempre que o nickname conectado pertencer
 * a uma conta Minecraft original, inclusive em sessões cracked/offline-mode.
 *
 * A autenticação continua sendo responsabilidade do LoginPlus; este listener
 * cuida apenas da aparência visual do perfil.
 */
public final class PremiumSkinListener implements Listener {

    private static final long[] APPLY_DELAYS = {1L, 20L, 60L};

    private final SkinsPlusPlugin plugin;
    private final SkinService skins;

    public PremiumSkinListener(SkinsPlusPlugin plugin, SkinService skins) {
        this.plugin = plugin;
        this.skins = skins;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Reaplica em alguns momentos após o join para vencer alterações tardias
        // feitas pelo perfil offline/Paper ou por integrações que atualizam o perfil.
        // Se o nickname não existir na Mojang, applyOfficialByName retorna NOT_FOUND
        // e nenhuma skin é substituída.
        for (long delay : APPLY_DELAYS) {
            plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    () -> applyOfficialNicknameSkin(player),
                    delay
            );
        }
    }

    private void applyOfficialNicknameSkin(Player player) {
        if (player == null || !player.isOnline()) return;

        String nickname = player.getName();
        skins.applyOfficialByName(player, nickname, result -> {
            if (result == SkinService.Result.SUCCESS) {
                plugin.getLogger().fine(
                        "Skin oficial de " + nickname + " aplicada por prioridade de nickname premium."
                );
            }
        });
    }
}
