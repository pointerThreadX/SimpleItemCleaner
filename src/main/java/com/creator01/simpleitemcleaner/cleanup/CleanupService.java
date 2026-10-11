package com.creator01.simpleitemcleaner.cleanup;

import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import com.creator01.simpleitemcleaner.config.ModConfig;
import com.creator01.simpleitemcleaner.lifecycle.EntityItemAccess;
import com.creator01.simpleitemcleaner.lifecycle.ItemLifecycleHandler;
import com.creator01.simpleitemcleaner.lifecycle.LifecycleProvider;
import com.creator01.simpleitemcleaner.lifecycle.LifecycleState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.List;

/** Synchronous, temporary candidate lists only. Never asks for or generates an unloaded chunk/world. */
public final class CleanupService {
    private final ItemLifecycleHandler lifecycle;

    public CleanupService(ItemLifecycleHandler lifecycle) { this.lifecycle = lifecycle; }

    public CleanupResult clear(MinecraftServer server, ModConfig config, boolean manual) {
        if (!server.isCallingFromMinecraftThread()) { throw new IllegalStateException("Cleanup requires the server thread"); }
        if (!config.enabled) { return new CleanupResult(0, 0, 0); }
        // Measure dimension traversal, candidate selection, lifecycle reconciliation and removal.
        // Do not include logging, result formatting, broadcasting, or any other work in the server tick.
        long started = System.nanoTime();
        long units = 0;
        long entities = 0;
        long examined = 0;
        int dimensions = 0;
        // Forge maintains this array with already-loaded worlds (including dynamic dimensions).
        for (WorldServer world : server.worlds) {
            if (world == null || world.isRemote || !config.allowsDimension(world.provider.getDimension())) { continue; }
            dimensions++;
            Sweep result = sweep(world, config);
            examined += result.examined;
            units += result.units;
            entities += result.entities;
        }
        CleanupResult result = new CleanupResult(units, entities, System.nanoTime() - started);
        if (config.logStatistics) {
            SimpleItemCleaner.log().info("Cleanup cause={}, loadedDimensions={}, examinedDrops={}, removedUnits={}, removedEntities={}, elapsedMs={}",
                    manual ? "manual" : "scheduled", dimensions, examined, result.units, result.entities, result.durationMillis());
        }
        return result;
    }

    /** Called only from clear on the server thread; package-visible for isolated mapped-world tests. */
    Sweep sweep(World world, ModConfig config) {
        Sweep result = new Sweep();
        if (world.isRemote || !config.allowsDimension(world.provider.getDimension())) { return result; }
        List<EntityItem> candidates = new ArrayList<>();
        for (Entity entity : world.loadedEntityList) {
            if (entity instanceof EntityItem) { candidates.add((EntityItem) entity); }
        }
        for (EntityItem item : candidates) {
            result.examined++;
            if (!ItemRules.isServerItem(item) || item.world != world || !item.addedToChunk) { continue; }
            // WorldServer.isBlockLoaded uses chunkExists (containsKey), without canceling queued unloads.
            if (!world.isBlockLoaded(new BlockPos(item.posX, 64, item.posZ), false)) { continue; }
            LifecycleState state = item.getCapability(LifecycleProvider.CAPABILITY, null);
            if (state == null) { continue; }
            lifecycle.reconcile(item, state);
            if (ItemRules.eligible(config, world.provider.getDimension(), ItemRules.policy(item, config),
                    EntityItemAccess.readAge(item), state.hasConflict())) {
                // Snapshot before setDead: subclasses may clear their stack while removing the entity.
                int count = item.getItem().getCount();
                if (remove(item)) {
                    result.entities++;
                    result.units += count;
                }
            }
        }
        return result;
    }

    static final class Sweep { long examined; long units; long entities; }

    /** Report only a real live-to-dead transition; the caller captures stack units before removal. */
    public static boolean remove(EntityItem item) {
        if (!ItemRules.isServerItem(item)) { return false; }
        item.setDead();
        return item.isDead;
    }
}
