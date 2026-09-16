package com.example.renderfast.client.mixin;

import com.example.renderfast.RenderFastConfigScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void addPreloaderButton(CallbackInfo ci) {
        int x = this.width / 2 + 104;
        int y = this.height / 4 + 48 + -16;

        this.addRenderableWidget(Button.builder(Component.literal("CP"), button -> {
            this.minecraft.setScreenAndShow(RenderFastConfigScreen.create(this));
        }).bounds(x, y, 20, 20).tooltip(Tooltip.create(Component.literal("RenderFast Configuration"))).build());
    }
}
