package com.moulberry.flashback.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
public abstract class MixinCloudRenderer {

    @Unique
    private static boolean shouldSkipClouds() {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && !editorState.replayVisuals.renderSky) {
            return true;
        }
        ExportJob exportJob = Flashback.EXPORT_JOB;
        return exportJob != null && exportJob.getSettings().projection() == ExportProjection.ORTHOGRAPHIC;
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    public void render(CloudStatus cloudStatus, RenderPass renderPass, CallbackInfo ci) {
        if (shouldSkipClouds()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true)
    public void renderOit(CloudStatus cloudStatus, OitStage oitStage, GpuTextureView gpuTextureView,
        OitRenderPassProvider.Parameters parameters, CallbackInfo ci) {
        if (shouldSkipClouds()) {
            ci.cancel();
        }
    }

}
