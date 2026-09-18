package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.FirstPersonHandsAndItemsExt;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(FirstPersonHandsAndItems.class)
public abstract class MixinFirstPersonHandsAndItems implements FirstPersonHandsAndItemsExt {

    @Shadow
    private ItemStack mainHandItem;

    @Shadow
    private ItemStack offHandItem;

    @Shadow
    private float mainHandHeight;

    @Shadow
    private float oMainHandHeight;

    @Shadow
    private float offHandHeight;

    @Shadow
    private float oOffHandHeight;

    @Unique
    private UUID lastSpectatingPlayer = null;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    public void tick(LocalPlayer player, CallbackInfo ci) {
        AbstractClientPlayer spectatingPlayer = Flashback.getSpectatingPlayer();
        if (spectatingPlayer == null) {
            this.lastSpectatingPlayer = null;
        } else {
            ItemStack newMainHandItem = spectatingPlayer.getMainHandItem();
            ItemStack newOffHandItem = spectatingPlayer.getOffhandItem();

            if (!spectatingPlayer.getUUID().equals(lastSpectatingPlayer)) {
                this.lastSpectatingPlayer = spectatingPlayer.getUUID();
                this.mainHandItem = newMainHandItem;
                this.offHandItem = newOffHandItem;
                this.oMainHandHeight = this.mainHandHeight = 1.0f;
                this.oOffHandHeight = this.offHandHeight = 1.0f;
                ci.cancel();
                return;
            }

            this.oMainHandHeight = this.mainHandHeight;
            this.oOffHandHeight = this.offHandHeight;
            if (ItemStack.matches(this.mainHandItem, newMainHandItem)) {
                this.mainHandItem = newMainHandItem;
            }
            if (ItemStack.matches(this.offHandItem, newOffHandItem)) {
                this.offHandItem = newOffHandItem;
            }
            float str = spectatingPlayer.getAttackStrengthScale(1.0f);
            this.mainHandHeight += Mth.clamp((this.mainHandItem == newMainHandItem ? str * str * str : 0.0f) - this.mainHandHeight, -0.4f, 0.4f);
            this.offHandHeight += Mth.clamp((float)(this.offHandItem == newOffHandItem ? 1 : 0) - this.offHandHeight, -0.4f, 0.4f);
            if (this.mainHandHeight < 0.1f) {
                this.mainHandItem = newMainHandItem;
            }
            if (this.offHandHeight < 0.1f) {
                this.offHandItem = newOffHandItem;
            }
            ci.cancel();
        }
    }

    @Override
    public ItemStack flashback$getMainHandItem() {
        return this.mainHandItem;
    }

    @Override
    public ItemStack flashback$getOffHandItem() {
        return this.offHandItem;
    }

    @Override
    public float flashback$getMainHandHeight() {
        return this.mainHandHeight;
    }

    @Override
    public float flashback$getOldMainHandHeight() {
        return this.oMainHandHeight;
    }

    @Override
    public float flashback$getOffHandHeight() {
        return this.offHandHeight;
    }

    @Override
    public float flashback$getOldOffHandHeight() {
        return this.oOffHandHeight;
    }

}
