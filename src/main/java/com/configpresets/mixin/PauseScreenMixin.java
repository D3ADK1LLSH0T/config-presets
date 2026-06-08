package com.configpresets.mixin;

import com.configpresets.config.ModConfig;
import com.configpresets.screen.IconButton;
import com.configpresets.screen.PresetScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the small Config Presets icon button to the pause / game menu screen,
 * placed next to the Advancements and Statistics buttons (see reference image).
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    private PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void configpresets$addButton(CallbackInfo ci) {
        if (!ModConfig.get().showButtonOnPauseScreen) {
            return;
        }
        int[] pos = PresetScreen.computeButtonPosition(this.width, this.height, true);
        int size = 20;

        // Single custom-icon button -> opens the Config Presets manager.
        IconButton presetsButton = new IconButton(
                pos[0], pos[1], size, size,
                Component.literal("Config Presets"),
                b -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new PresetScreen((Screen) (Object) this));
                    }
                });
        presetsButton.setTooltip(Tooltip.create(Component.literal("Config Presets")));
        this.addRenderableWidget(presetsButton);
    }
}
