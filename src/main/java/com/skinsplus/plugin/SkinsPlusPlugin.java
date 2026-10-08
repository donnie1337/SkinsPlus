package com.skinsplus.plugin;

import com.skinsplus.plugin.commands.SkinCommand;
import com.skinsplus.plugin.skin.SkinService;
import org.bukkit.plugin.java.JavaPlugin;

public final class SkinsPlusPlugin extends JavaPlugin {

    private SkinService skinService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.skinService = new SkinService(this);

        SkinCommand skinCommand = new SkinCommand(skinService);
        getCommand("skin").setExecutor(skinCommand);
        getCommand("skin").setTabCompleter(skinCommand);

        getLogger().info("SkinsPlus ativado com sucesso.");
    }

    public SkinService getSkinService() {
        return skinService;
    }
}
