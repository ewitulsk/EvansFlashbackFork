package com.moulberry.flashback.mixin.testing;

import com.moulberry.flashback.testing.HiddenClientScenario;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives the scripted hidden-client scenario each tick and validates the window contract each frame. */
@Mixin(Minecraft.class)
public abstract class MixinHiddenClient {

    @Inject(method = "tick", at = @At("TAIL"))
    private void flashback$hiddenClientTick(CallbackInfo ci) {
        HiddenClientScenario.tick();
    }

    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void flashback$hiddenClientFrame(boolean tick, CallbackInfo ci) {
        HiddenClientScenario.frame();
    }
}
