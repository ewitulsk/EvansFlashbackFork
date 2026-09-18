package com.moulberry.flashback.ext;

import net.minecraft.world.item.ItemStack;

public interface FirstPersonHandsAndItemsExt {

    ItemStack flashback$getMainHandItem();

    ItemStack flashback$getOffHandItem();

    float flashback$getMainHandHeight();

    float flashback$getOldMainHandHeight();

    float flashback$getOffHandHeight();

    float flashback$getOldOffHandHeight();

}
