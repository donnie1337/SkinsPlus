package com.skinsplus.plugin.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SkinCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("ajuda") || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        sender.sendMessage(color("&c&lSKINS &8• &fComando ainda não implementado. Use &e/skin ajuda&f."));
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("");
        sender.sendMessage(color("&b&lSKINS &8• &fAjuda"));
        sender.sendMessage("");
        sender.sendMessage(color("&8» &f/skin <nome> &8- &7Aplica uma skin pelo nome."));
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
