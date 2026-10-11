package com.creator01.simpleitemcleaner.cleanup;

import com.creator01.simpleitemcleaner.config.ModConfig;
import com.creator01.simpleitemcleaner.policy.ItemPolicy;
import net.minecraft.entity.item.EntityItem;

public final class ItemRules {
    private ItemRules() { }

    public static boolean isServerItem(EntityItem item) {
        return item.world != null && !item.world.isRemote && !item.isDead && !item.getItem().isEmpty();
    }

    public static ItemPolicy policy(EntityItem item, ModConfig config) {
        return config.policies.resolve(item.getItem().getItem().getRegistryName(), item.getItem().getMetadata());
    }

    public static boolean protectsExpiration(EntityItem item) {
        ModConfig config = ModConfig.current();
        return isServerItem(item) && config.allowsDimension(item.world.provider.getDimension())
                && policy(item, config).preventsExpiration();
    }

    public static boolean eligible(ModConfig config, int dimension, ItemPolicy policy, int age, boolean conflict) {
        return config.allowsDimension(dimension) && policy.allowsCleanup() && !conflict
                && age >= 0 && age >= config.minimumAgeTicks();
    }
}
