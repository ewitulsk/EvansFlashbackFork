package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.ext.ClientClockManagerExt;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.HashMap;
import java.util.Map;

@Mixin(ClientClockManager.class)
public class MixinClientClockManager implements ClientClockManagerExt {

    @Shadow
    @Final
    private Map<Holder<WorldClock>, ClientClockManager.ClientClockInstance> clocks;

    public Map<Holder<WorldClock>, ClockNetworkState> flashback$encodeClockUpdates() {
        Map<Holder<WorldClock>, ClockNetworkState> data = new HashMap<>();

        for (Map.Entry<Holder<WorldClock>, ClientClockManager.ClientClockInstance> entry : this.clocks.entrySet()) {
            var clock = entry.getValue();
            data.put(entry.getKey(), new ClockNetworkState(
                clock.totalTicks(),
                clock.partialTick(),
                clock.rate()
            ));
        }

        return data;
    }

    @ModifyReturnValue(method = "getInstance(Lnet/minecraft/core/Holder;)Lnet/minecraft/client/ClientClockManager$ClientClockInstance;", at = @At("RETURN"))
    public ClientClockManager.ClientClockInstance flashback$overrideTotalTicks(ClientClockManager.ClientClockInstance original,
        Holder<WorldClock> definition) {
        if (!definition.is(WorldClocks.OVERWORLD)) {
            return original;
        }
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null || editorState.replayVisuals.overrideTimeOfDay < 0) {
            return original;
        }
        long overrideTicks = editorState.replayVisuals.overrideTimeOfDay;
        return new ClientClockManager.ClientClockInstance() {
            @Override
            public long totalTicks() {
                return overrideTicks;
            }
            @Override
            public float partialTick() {
                return original.partialTick();
            }
            @Override
            public float rate() {
                return original.rate();
            }
            @Override
            public boolean isPaused() {
                return original.isPaused();
            }
        };
    }

}
