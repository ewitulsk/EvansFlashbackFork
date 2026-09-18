package com.moulberry.flashback.mixin;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.CustomImGuiImplSdl;
import com.moulberry.flashback.editor.ui.ReplayUI;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MouseHandler.class)
public class MixinMouseHandler {

    @Inject(method = "isMouseGrabbed", at=@At("HEAD"), cancellable = true)
    public void isMouseGrabbed(CallbackInfoReturnable<Boolean> cir) {
        var imguiSdl = ReplayUI.imguiSdl();
        if (ReplayUI.isActive() && imguiSdl != null) {
            cir.setReturnValue(imguiSdl.getMouseHandledBy() == CustomImGuiImplSdl.MouseHandledBy.GAME);
        } else if (Flashback.isExporting()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    public void onButton(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (Flashback.isExporting()) {
            ci.cancel();
            return;
        }
        var imguiSdl = ReplayUI.imguiSdl();
        if (imguiSdl != null && imguiSdl.isReady() && !imguiSdl.isDispatchingToGame()) {
            imguiSdl.mouseButtonCallback(window, info, action);
            ci.cancel();
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    public void onScroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        if (Flashback.isExporting()) {
            ci.cancel();
            return;
        }
        var imguiSdl = ReplayUI.imguiSdl();
        if (imguiSdl != null && imguiSdl.isReady() && !imguiSdl.isDispatchingToGame()) {
            imguiSdl.scrollCallback(window, xOffset, yOffset);
            ci.cancel();
        }
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    public void onMove(long window, double x, double y, double xrel, double yrel, CallbackInfo ci) {
        if (Flashback.isExporting()) {
            ci.cancel();
            return;
        }
        var imguiSdl = ReplayUI.imguiSdl();
        if (imguiSdl != null && imguiSdl.isReady() && !imguiSdl.isDispatchingToGame()) {
            imguiSdl.cursorPosCallback(window, x, y, xrel, yrel);
            ci.cancel();
        }
    }

    @Inject(method = "grabMouse", at=@At("HEAD"), cancellable = true)
    public void grabMouse(CallbackInfo ci) {
        if (ReplayUI.isActive() || Flashback.isExporting()) {
            ci.cancel();
        }
    }

    @Inject(method = "releaseMouse", at=@At("HEAD"), cancellable = true)
    public void releaseMouse(CallbackInfo ci) {
        if (ReplayUI.isActive()) {
            ci.cancel();
        }
    }

}
