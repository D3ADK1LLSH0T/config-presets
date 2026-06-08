package com.configpresets.mixin;

import com.configpresets.config.ModConfig;
import com.configpresets.screen.IconButton;
import com.configpresets.screen.PresetScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the small Config Presets icon button to the pause / game menu screen,
 * placed next to the Advancements and Statistics buttons (see reference image).
 */
@Mixin(GameMenuScreen.class)
public abstract class PauseScreenMixin extends Screen {

    private PauseScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "initWidgets", at = @At("TAIL"))
    private void configpresets$addButton(CallbackInfo ci) {
        if (!ModConfig.get().showButtonOnPauseScreen) {
            return;
        }
        int[] pos = PresetScreen.computeButtonPosition(this.width, this.height, true);
        int size = 20;

        // Single custom-icon button -> opens the Config Presets manager.
        IconButton presetsButton = new IconButton(
                pos[0], pos[1], size, size,
                Text.literal("Config Presets"),
                b -> {
                    if (this.client != null) {
                        this.client.setScreen(new PresetScreen((Screen) (Object) this));
                    }
                });
        presetsButton.setTooltip(Tooltip.of(Text.literal("Config Presets")));
        this.addDrawableChild(presetsButton);
    }
}
