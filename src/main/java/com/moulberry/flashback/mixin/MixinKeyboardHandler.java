package com.moulberry.flashback.mixin;

import com.moulberry.flashback.editor.ui.ReplayUI;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.PreeditEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class MixinKeyboardHandler {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    public void keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        var imguiSdl = ReplayUI.imguiSdl();
        if (imguiSdl != null && imguiSdl.isReady() && !imguiSdl.isDispatchingToGame()) {
            imguiSdl.keyCallback(window, action, event);
            ci.cancel();
        }
    }

    @Inject(method = "textInput", at = @At("HEAD"), cancellable = true)
    public void textInput(long window, String text, CallbackInfo ci) {
        var imguiSdl = ReplayUI.imguiSdl();
        if (imguiSdl != null && imguiSdl.isReady() && !imguiSdl.isDispatchingToGame()) {
            imguiSdl.textInputCallback(window, text);
            ci.cancel();
        }
    }

    @Inject(method = "textEditing", at = @At("HEAD"), cancellable = true)
    public void textEditing(long window, PreeditEvent event, CallbackInfo ci) {
        var imguiSdl = ReplayUI.imguiSdl();
        if (imguiSdl != null && imguiSdl.isReady() && !imguiSdl.isDispatchingToGame()) {
            imguiSdl.textEditingCallback(window, event);
            ci.cancel();
        }
    }

}
