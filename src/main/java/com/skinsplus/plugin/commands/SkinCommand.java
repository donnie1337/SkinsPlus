package com.skinsplus.plugin.commands;

import com.skinsplus.plugin.skin.SkinService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SkinCommand implements CommandExecutor, TabCompleter {

    private final SkinService skins;

    public SkinCommand(SkinService skins) {
        this.skins = skins;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda") || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(color("&c&lSKINS &8• &fApenas jogadores podem usar este comando."));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            player.sendMessage(color("&b&lSKINS &8• &fRestaurando sua skin..."));
            skins.reset(player, result -> {
                if (result == SkinService.Result.SUCCESS) {
                    player.sendMessage(color("&a&lSKINS &8• &fSua skin original do Minecraft foi restaurada."));
                } else {
                    player.sendMessage(color("&c&lSKINS &8• &fNão foi possível buscar sua skin original na Mojang."));
                }
            });
            return true;
        }

        if (args[0].equalsIgnoreCase("random")) {
            player.sendMessage(color("&b&lSKINS &8• &fProcurando uma skin aleatória no NameMC..."));
            skins.applyRandom(player, (result, skinName) -> {
                if (result == SkinService.Result.SUCCESS && skinName != null) {
                    player.sendMessage(color("&a&lSKINS &8• &fSkin aleatória do NameMC aplicada: &a" + skinName + "&f."));
                } else {
                    player.sendMessage(color("&c&lSKINS &8• &fNão foi possível encontrar uma skin aleatória agora."));
                }
            });
            return true;
        }

        if (args[0].equalsIgnoreCase("save")
                || args[0].equalsIgnoreCase("favorites")) {
            player.sendMessage(color("&e&lSKINS &8• &fEste recurso será adicionado em seguida."));
            return true;
        }

        String skinName = args[0];
        player.sendMessage(color("&b&lSKINS &8• &fBuscando a skin de &b" + skinName + "&f..."));

        skins.applyByName(player, skinName, result -> {
            switch (result) {
                case SUCCESS -> player.sendMessage(color(
                        "&a&lSKINS &8• &fSkin de &a" + skinName + " &faplicada com sucesso!"
                ));
                case INVALID_NAME -> player.sendMessage(color(
                        "&c&lSKINS &8• &fNickname inválido. Use um nome de Minecraft válido."
                ));
                case NOT_FOUND -> player.sendMessage(color(
                        "&c&lSKINS &8• &fNão foi possível encontrar uma skin para &c" + skinName + "&f."
                ));
            }
        });
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(color("&b&lSKINS &8• &fAjuda"));
        sender.sendMessage("");
        sender.sendMessage(color("&8» &f/skin <nome> &8- &7Aplica uma skin pelo nickname."));
        sender.sendMessage(color("&8» &f/skin reset &8- &7Restaura sua skin padrão."));
        sender.sendMessage(color("&8» &f/skin random &8- &7Aplica uma skin aleatória."));
        sender.sendMessage(color("&8» &f/skin save <nome> &8- &7Salva a skin atual."));
        sender.sendMessage(color("&8» &f/skin favorites &8- &7Mostra suas skins favoritas."));
        sender.sendMessage(color("&8» &f/skin ajuda &8- &7Mostra esta ajuda."));
        sender.sendMessage("");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();

        List<String> options = new ArrayList<>(List.of(
                "ajuda", "help", "reset", "random", "save", "favorites"
        ));
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.startsWith(prefix))
                .toList();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
