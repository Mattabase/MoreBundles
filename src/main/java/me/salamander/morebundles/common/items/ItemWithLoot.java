package me.salamander.morebundles.common.items;

import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContext;

public interface ItemWithLoot {
    void process(ItemStack itemStack, LootContext lootContext);
}
