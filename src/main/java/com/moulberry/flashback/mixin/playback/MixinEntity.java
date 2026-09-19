package com.moulberry.flashback.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(Entity.class)
public abstract class MixinEntity {

    @Shadow
    public abstract boolean isInvisible();

    @Shadow
    private Level level;

    @Shadow
    public abstract boolean isInvisibleTo(Player player);

    @Shadow
    public abstract UUID getUUID();

    // Force entities to be able to ride players on servers
    @WrapOperation(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isClientSide()Z"))
    public boolean startRiding_isClientSide(Level instance, Operation<Boolean> original) {
        if (Flashback.isInReplay()) {
            return true; // Always pretend we're clientside so mounting players is allowed
        }
        return original.call(instance);
    }

    // In 26.3, entities that don't override createInterpolationHandler get InterpolationHandler.NO_OP,
    // which turns every position packet into a hard setPos snap with no motion mechanism. Vanilla relies
    // on client-side physics prediction for these, which doesn't exist for packet-driven replay entities.
    // Give them a LinearInterpolationHandler so moveOrInterpolateTo glides over several ticks instead.
    // Entities that override this method (e.g. LivingEntity -> SteppedInterpolationHandler) are unaffected.
    @Inject(method = "createInterpolationHandler", at = @At("HEAD"), cancellable = true)
    public void createInterpolationHandler(CallbackInfoReturnable<InterpolationHandler> cir) {
        if (Flashback.isInReplay()) {
            cir.setReturnValue(LinearInterpolationHandler.create((Entity) (Object) this));
        }
    }

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    public void isInvisibleTo(Player player, CallbackInfoReturnable<Boolean> cir) {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null && player == Minecraft.getInstance().player) {
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null && editorState.isEntityHidden((Entity) (Object) this)) {
                cir.setReturnValue(true);
            }

            int localId = replayServer.getLocalPlayerId();
            if (this.level.getEntity(localId) instanceof Player localPlayer) {
                cir.setReturnValue(this.isInvisibleTo(localPlayer));
            } else {
                cir.setReturnValue(this.isInvisible());
            }
        }
    }


}
