package com.configpresets.mixin;

import com.configpresets.config.ModConfig;
import com.configpresets.screen.IconButton;
import com.configpresets.screen.PresetScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the small Config Presets icon button to the title screen.
 *
 * <p>The mixin extends {@link Screen} so it can call the protected
 * {@code addDrawableChild} on the target {@link TitleScreen}.</p>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void configpresets$addButton(CallbackInfo ci) {
        if (!ModConfig.get().showButtonOnTitleScreen) {
            return;
        }
        int[] pos = PresetScreen.computeButtonPosition(this.width, this.height, false);
        IconButton button = new IconButton(
                pos[0], pos[1], 20, 20,
                Text.literal("Config Presets"),
                b -> {
                    if (this.client != null) {
                        this.client.setScreen(new PresetScreen((Screen) (Object) this));
                    }
                });
        button.setTooltip(Tooltip.of(Text.literal("Config Presets")));
        this.addDrawableChild(button);
    }
}
