package com.moulberry.flashback.mixin.playback;

import com.mojang.blaze3d.vertex.PoseStack;
import com.moulberry.flashback.ext.FirstPersonHandsAndItemsExt;
import com.moulberry.flashback.ext.ItemInHandRendererExt;
import com.moulberry.flashback.ext.RemotePlayerExt;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState.HandRenderSelection;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;

@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class MixinFirstPersonHandsAndItemsRenderer implements ItemInHandRendererExt {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    public abstract void submitHandsWithItems(float partialTick, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
        PlayerRenderState playerRenderState, FirstPersonHandsAndItemsRenderState handsRenderState);

    @Unique
    private static boolean isChargedCrossbow(ItemStack itemStack) {
        return itemStack.is(Items.CROSSBOW) && CrossbowItem.isCharged(itemStack);
    }

    @Unique
    private static HandRenderSelection evaluateWhichHandsToRender(AbstractClientPlayer player) {
        ItemStack mainStack = player.getMainHandItem();
        ItemStack offStack = player.getOffhandItem();
        boolean isHoldingBow = mainStack.is(Items.BOW) || offStack.is(Items.BOW);
        boolean isHoldingCrossbow = mainStack.is(Items.CROSSBOW) || offStack.is(Items.CROSSBOW);
        if (!isHoldingBow && !isHoldingCrossbow) {
            return HandRenderSelection.RENDER_BOTH_HANDS;
        }
        if (player.isUsingItem()) {
            ItemStack useStack = player.getUseItem();
            InteractionHand interactionHand = player.getUsedItemHand();
            if (!useStack.is(Items.BOW) && !useStack.is(Items.CROSSBOW)) {
                return interactionHand == InteractionHand.MAIN_HAND && isChargedCrossbow(player.getOffhandItem())
                    ? HandRenderSelection.RENDER_MAIN_HAND_ONLY : HandRenderSelection.RENDER_BOTH_HANDS;
            }
            return HandRenderSelection.onlyForHand(interactionHand);
        }
        if (isChargedCrossbow(mainStack)) {
            return HandRenderSelection.RENDER_MAIN_HAND_ONLY;
        }
        return HandRenderSelection.RENDER_BOTH_HANDS;
    }

    @Override
    public void flashback$renderHandsWithItems(float partialTick, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
        AbstractClientPlayer clientPlayer, @Nullable Set<InteractionHand> renderableArms) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.isEntityHidden(clientPlayer)) {
            return;
        }
        if (this.minecraft.player == null) {
            return;
        }

        // Extract an avatar render state for the spectated player so the first-person
        // arm is submitted with their skin/pose rather than the local player's
        PlayerRenderState playerRenderState = new PlayerRenderState();
        playerRenderState.hasPlayer = true;
        EntityRenderState extractedState = this.minecraft.getEntityRenderDispatcher()
            .getRenderer(clientPlayer).createRenderState(clientPlayer, partialTick);
        if (extractedState instanceof AvatarRenderState avatarRenderState) {
            playerRenderState.avatarRenderState = avatarRenderState;
        }

        // Populate the hands render state from the spectated player; the equip heights
        // are tracked by the local player's FirstPersonHandsAndItems ticker, which is
        // redirected at the spectated player by MixinFirstPersonHandsAndItems
        FirstPersonHandsAndItemsRenderState handsRenderState = new FirstPersonHandsAndItemsRenderState();
        LivingEntity.SwingDescription swing = clientPlayer.getCurrentSwing();
        handsRenderState.attackHand = swing != null ? swing.hand() : InteractionHand.MAIN_HAND;
        handsRenderState.viewXRot = clientPlayer.getViewXRot(partialTick);
        handsRenderState.viewYRot = clientPlayer.getViewYRot(partialTick);
        if (clientPlayer instanceof RemotePlayerExt remotePlayerExt) {
            handsRenderState.xBob = remotePlayerExt.flashback$getXBob(partialTick);
            handsRenderState.yBob = remotePlayerExt.flashback$getYBob(partialTick);
        } else {
            handsRenderState.xBob = handsRenderState.viewXRot;
            handsRenderState.yBob = handsRenderState.viewYRot;
        }
        handsRenderState.isScoping = clientPlayer.isScoping();
        handsRenderState.useItemRemainingTicks = clientPlayer.getUseItemRemainingTicks();

        HandRenderSelection selection = evaluateWhichHandsToRender(clientPlayer);
        if (renderableArms != null) {
            boolean renderMain = selection.renderMainHand && renderableArms.contains(InteractionHand.MAIN_HAND);
            boolean renderOff = selection.renderOffHand && renderableArms.contains(InteractionHand.OFF_HAND);
            selection = renderMain
                ? (renderOff ? HandRenderSelection.RENDER_BOTH_HANDS : HandRenderSelection.RENDER_MAIN_HAND_ONLY)
                : (renderOff ? HandRenderSelection.RENDER_OFF_HAND_ONLY : null);
        }
        if (selection == null) {
            return;
        }
        handsRenderState.handRenderSelection = selection;

        FirstPersonHandsAndItemsExt ticker = (FirstPersonHandsAndItemsExt) this.minecraft.player.firstPersonHandsAndItems();
        handsRenderState.mainHandItem = ticker.flashback$getMainHandItem();
        handsRenderState.offHandItem = ticker.flashback$getOffHandItem();
        handsRenderState.mainHandHeight = ticker.flashback$getMainHandHeight();
        handsRenderState.oldMainHandHeight = ticker.flashback$getOldMainHandHeight();
        handsRenderState.offHandHeight = ticker.flashback$getOffHandHeight();
        handsRenderState.oldOffHandHeight = ticker.flashback$getOldOffHandHeight();
        handsRenderState.mainHandUseDuration = handsRenderState.mainHandItem.getUseDuration(clientPlayer);
        handsRenderState.offHandUseDuration = handsRenderState.offHandItem.getUseDuration(clientPlayer);
        handsRenderState.mainHandChargeDuration = CrossbowItem.getChargeDuration(handsRenderState.mainHandItem, clientPlayer);
        handsRenderState.offHandChargeDuration = CrossbowItem.getChargeDuration(handsRenderState.offHandItem, clientPlayer);

        ItemModelResolver itemModelResolver = this.minecraft.getItemModelResolver();
        handsRenderState.mainHandSwapScale = itemModelResolver.swapAnimationScale(handsRenderState.mainHandItem);
        handsRenderState.offHandSwapScale = itemModelResolver.swapAnimationScale(handsRenderState.offHandItem);

        boolean rightMainArm = clientPlayer.getMainArm() == HumanoidArm.RIGHT;
        ItemDisplayContext mainContext = rightMainArm ? ItemDisplayContext.FIRST_PERSON_RIGHT_HAND : ItemDisplayContext.FIRST_PERSON_LEFT_HAND;
        ItemDisplayContext offContext = rightMainArm ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        itemModelResolver.updateForTopItem(handsRenderState.mainHandRenderState, handsRenderState.mainHandItem,
            mainContext, clientPlayer.level(), clientPlayer, clientPlayer.getId() + mainContext.ordinal());
        itemModelResolver.updateForTopItem(handsRenderState.offHandRenderState, handsRenderState.offHandItem,
            offContext, clientPlayer.level(), clientPlayer, clientPlayer.getId() + offContext.ordinal());

        handsRenderState.hasMainHandMapData = this.flashback$extractMapRenderState(clientPlayer,
            handsRenderState.mainHandItem, handsRenderState.mainHandMapRenderState);
        handsRenderState.hasOffHandMapData = this.flashback$extractMapRenderState(clientPlayer,
            handsRenderState.offHandItem, handsRenderState.offHandMapRenderState);

        this.submitHandsWithItems(partialTick, poseStack, submitNodeCollector, playerRenderState, handsRenderState);
    }

    @Unique
    private boolean flashback$extractMapRenderState(AbstractClientPlayer player, ItemStack stack, MapRenderState mapRenderState) {
        MapId mapId = stack.get(DataComponents.MAP_ID);
        MapItemSavedData data = mapId == null ? null : MapItem.getSavedData(mapId, player.level());
        if (data == null) {
            return false;
        }
        this.minecraft.getMapRenderer().extractRenderState(mapId, data, mapRenderState);
        return true;
    }

}
