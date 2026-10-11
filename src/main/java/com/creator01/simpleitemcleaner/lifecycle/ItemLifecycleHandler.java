package com.creator01.simpleitemcleaner.lifecycle;

import com.creator01.simpleitemcleaner.config.ModConfig;
import com.creator01.simpleitemcleaner.cleanup.ItemRules;
import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.item.ItemExpireEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public final class ItemLifecycleHandler {
    private static final ResourceLocation KEY = new ResourceLocation(SimpleItemCleaner.MOD_ID, "lifecycle");
    private long extensions;
    private long ageRestorations;
    private long lifespanRestorations;
    private long conflicts;

    public void startSession() {
        extensions = ageRestorations = lifespanRestorations = conflicts = 0;
    }

    public void verifyAccess() {
        // Needed even when disabled to restore extensions from earlier sessions of this mod.
        EntityItemAccess.initialize();
    }

    public void finishSession() {
        if (ModConfig.current().logStatistics) { SimpleItemCleaner.log().info("Lifecycle session: extensions={}, fullAgeRestorations={}, "
                        + "lifespanRestorations={}, conflicts={}",
                extensions, ageRestorations, lifespanRestorations, conflicts); }
        startSession();
    }

    @SubscribeEvent
    public void attach(AttachCapabilitiesEvent<Entity> event) {
        Entity entity = event.getObject();
        if (entity instanceof EntityItem && entity.world != null && !entity.world.isRemote) {
            // Also needed when disabled, to read and undo previously saved extensions.
            event.addCapability(KEY, new LifecycleProvider((EntityItem) entity));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void join(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote || !(event.getEntity() instanceof EntityItem)) {
            return;
        }
        EntityItem item = (EntityItem) event.getEntity();
        LifecycleState state = item.getCapability(LifecycleProvider.CAPABILITY, null);
        if (state == null) {
            return;
        }
        reconcile(item, state);
        if (ItemRules.protectsExpiration(item)) {
            state.track();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void expire(ItemExpireEvent event) {
        EntityItem item = event.getEntityItem();
        if (item.world == null || item.world.isRemote || item.isDead || item.getItem().isEmpty()) {
            return;
        }
        LifecycleState state = item.getCapability(LifecycleProvider.CAPABILITY, null);
        if (state == null) {
            return;
        }
        reconcile(item, state);
        if (!ItemRules.protectsExpiration(item) || event.isCanceled() || state.hasConflict()) {
            return;
        }
        int increment = state.extend(EntityItemAccess.readAge(item), item.lifespan);
        if (increment > 0) {
            event.setExtraLife(increment);
            event.setCanceled(true);
            extensions++;
            SimpleItemCleaner.log().debug("Extended item {} by {} ticks (lifespan before={})",
                    item.getUniqueID(), increment, item.lifespan);
        }
        reportConflict(item, state);
    }

    public void reconcile(EntityItem item, LifecycleState state) {
        if (!ItemRules.isServerItem(item)) { return; }
        if (!state.isTracked()) {
            return;
        }
        int observedAge = EntityItemAccess.readAge(item);
        int restoredAge = state.restoreLoadedAge(observedAge);
        if (restoredAge != observedAge) {
            EntityItemAccess.restoreAge(item, restoredAge);
            ageRestorations++;
            SimpleItemCleaner.log().debug("Restored full age of item {}: {} -> {}",
                    item.getUniqueID(), observedAge, restoredAge);
        }
        state.observeLifespan(item.lifespan);
        if (!ItemRules.protectsExpiration(item)) {
            int restoredLifespan = state.release(item.lifespan);
            if (restoredLifespan != item.lifespan) {
                SimpleItemCleaner.log().debug("Restored lifespan of item {}: {} -> {}",
                        item.getUniqueID(), item.lifespan, restoredLifespan);
                item.lifespan = restoredLifespan;
                lifespanRestorations++;
            }
        }
        reportConflict(item, state);
    }

    private void reportConflict(EntityItem item, LifecycleState state) {
        if (state.reportConflictOnce()) {
            conflicts++;
            SimpleItemCleaner.log().warn("Lifecycle conflict/unsafe numeric boundary for item {} ({}). "
                            + "Leaving lifespan untouched; protection, cleanup and rollback are suspended "
                            + "for this entity. age={}, lifespan={}",
                    item.getUniqueID(), item.getClass().getName(), EntityItemAccess.readAge(item), item.lifespan);
        }
    }
}
