package com.skinsplus.plugin.skin;

import com.skinsplus.plugin.SkinsPlusPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * Restaura automaticamente a skin oficial para sessões premium verificadas pelo LoginPlus,
 * mesmo com o servidor em offline-mode.
 */
public final class PremiumSkinListener implements Listener {

    private final SkinsPlusPlugin plugin;
    private final SkinService skins;

    public PremiumSkinListener(SkinsPlusPlugin plugin, SkinService skins) {
        this.plugin = plugin;
        this.skins = skins;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // O LoginPlus conclui a autenticação premium após o PlayerJoinEvent.
        // Tentamos por alguns segundos para evitar corrida entre os dois plugins.
        tryApplyPremiumSkin(player, 0);
    }

    private void tryApplyPremiumSkin(Player player, int attempt) {
        if (!player.isOnline()) return;

        if (isVerifiedPremiumSession(player)) {
            // Aguarda mais 1 tick para aplicar por cima do perfil offline criado pelo
            // Paper e de quaisquer ajustes de login executados no mesmo tick.
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                skins.applyOfficialByName(player, player.getName(), result -> {
                    if (result == SkinService.Result.SUCCESS) {
                        plugin.getLogger().fine("Skin oficial restaurada automaticamente para " + player.getName() + ".");
                    } else {
                        plugin.getLogger().warning("Não foi possível restaurar automaticamente a skin oficial de "
                                + player.getName() + ": " + result);
                    }
                });
            });
            return;
        }

        // Até 10 segundos aguardando a sessão premium ser marcada pelo LoginPlus.
        if (attempt >= 20) return;
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> tryApplyPremiumSkin(player, attempt + 1),
                10L
        );
    }

    private boolean isVerifiedPremiumSession(Player player) {
        Plugin loginPlus = plugin.getServer().getPluginManager().getPlugin("LoginPlus");
        if (loginPlus == null || !loginPlus.isEnabled()) return false;

        try {
            Method getSessionManager = loginPlus.getClass().getMethod("getSessionManager");
            Object sessions = getSessionManager.invoke(loginPlus);
            if (sessions == null) return false;

            Method isAuthenticated = sessions.getClass().getMethod("isAuthenticated", Player.class);
            Method isPremium = sessions.getClass().getMethod("isPremium", Player.class);

            Object authenticated = isAuthenticated.invoke(sessions, player);
            Object premium = isPremium.invoke(sessions, player);
            return Boolean.TRUE.equals(authenticated) && Boolean.TRUE.equals(premium);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("Falha ao consultar sessão premium do LoginPlus: " + exception.getMessage());
            return false;
        }
    }
}
