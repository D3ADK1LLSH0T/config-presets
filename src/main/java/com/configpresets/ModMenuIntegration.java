package com.configpresets;

import com.configpresets.config.ModConfig;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu entry point: exposes the Cloth Config settings screen under the
 * "Config Presets" mod entry in the Mods list.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ModConfig::createConfigScreen;
    }
}
