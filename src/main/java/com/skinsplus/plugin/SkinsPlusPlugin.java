package com.skinsplus.plugin;

import com.skinsplus.plugin.commands.SkinCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkinsPlusPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();

        SkinCommand skinCommand = new SkinCommand();
        getCommand("skin").setExecutor(skinCommand);
        getCommand("skin").setTabCompleter(skinCommand);

        getLogger().info("SkinsPlus ativado com sucesso.");
    }
}
