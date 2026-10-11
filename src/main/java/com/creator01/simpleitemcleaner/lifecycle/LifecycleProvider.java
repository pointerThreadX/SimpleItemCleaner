package com.creator01.simpleitemcleaner.lifecycle;

import com.creator01.simpleitemcleaner.cleanup.ItemRules;
import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

import javax.annotation.Nullable;

/** Owned by the entity, with no global entity collection or client synchronization. */
public final class LifecycleProvider implements ICapabilitySerializable<NBTTagCompound> {
    @CapabilityInject(LifecycleState.class)
    public static final Capability<LifecycleState> CAPABILITY = null;

    private final EntityItem owner;
    private final LifecycleState state = new LifecycleState();
    private NBTTagCompound unreadableData;

    public LifecycleProvider(EntityItem owner) {
        this.owner = owner;
    }

    public static void register() {
        CapabilityManager.INSTANCE.register(LifecycleState.class,
                new Capability.IStorage<LifecycleState>() {
                    @Override
                    public NBTBase writeNBT(Capability<LifecycleState> capability, LifecycleState instance,
                                            EnumFacing side) {
                        throw new UnsupportedOperationException("Use the entity-bound provider serializer");
                    }

                    @Override
                    public void readNBT(Capability<LifecycleState> capability, LifecycleState instance,
                                        EnumFacing side, NBTBase nbt) {
                        throw new UnsupportedOperationException("Use the entity-bound provider serializer");
                    }
                }, LifecycleState::new);
    }

    @Override
    public boolean hasCapability(Capability<?> capability, @Nullable EnumFacing facing) {
        return CAPABILITY != null && capability == CAPABILITY;
    }

    @Override
    @Nullable
    public <T> T getCapability(Capability<T> capability, @Nullable EnumFacing facing) {
        return CAPABILITY != null && capability == CAPABILITY ? CAPABILITY.cast(state) : null;
    }

    @Override
    public NBTTagCompound serializeNBT() {
        if (unreadableData != null) {
            return unreadableData.copy();
        }
        NBTTagCompound tag = new NBTTagCompound();
        if (owner.world == null || owner.world.isRemote) {
            return tag;
        }
        if (ItemRules.protectsExpiration(owner)) {
            state.track();
        }
        if (!state.needsAgePersistence()) {
            return tag;
        }
        // Read the CURRENT full int at every serialization, not the age at last expiration.
        LifecycleState.Snapshot snapshot = state.snapshot(EntityItemAccess.readAge(owner), owner.lifespan);
        return LifecycleNbt.write(snapshot);
    }

    @Override
    public void deserializeNBT(NBTTagCompound tag) {
        if (tag.hasNoTags()) {
            return;
        }
        LifecycleState.Snapshot snapshot = LifecycleNbt.read(tag);
        if (snapshot == null) {
            unreadableData = tag.copy();
            state.markConflict();
            SimpleItemCleaner.log().warn("Unrecognized item lifecycle data; preserving it without intervention.");
            return;
        }
        state.load(snapshot);
        // Entity.readFromNBT can still read vanilla Age afterwards. Restore in EntityJoinWorldEvent.
    }
}
