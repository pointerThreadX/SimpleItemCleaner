package com.creator01.simpleitemcleaner.lifecycle;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Real mapped EntityItem, isolated from any world; no FML server launch is implied. */
public final class EntityItemAccessTest {
    public static void main(String[] args) {
        EntityItemAccess.initialize();
        Bootstrap.register();
        EntityItem item = new EntityItem(null);
        int assertions = 0;
        for (int age : new int[] {0, 1, 32767, 32768, 40000, 65536, -32768}) {
            EntityItemAccess.restoreAge(item, age);
            if (EntityItemAccess.readAge(item) != age) {
                throw new AssertionError("Actual EntityItem full int read failed for " + age);
            }
            assertions++;
        }
        if (item.lifespan != 6000) {
            throw new AssertionError("Age access changed lifespan");
        }
        assertions++;

        // Exercise the real item-specific NBT methods. Full Entity.readFromNBT needs a world.
        EntityItemAccess.restoreAge(item, 40000);
        item.lifespan = 50000;
        item.setItem(new ItemStack(Items.DIAMOND, 3));
        LifecycleState state = new LifecycleState();
        state.track();
        NBTTagCompound snapshot = LifecycleNbt.write(state.snapshot(40000, item.lifespan));
        NBTTagCompound saved = new NBTTagCompound();
        item.writeEntityToNBT(saved);
        if (saved.getShort("Age") != (short) 40000 || saved.getInteger("Lifespan") != 50000) {
            throw new AssertionError("Unexpected actual vanilla serialization");
        }
        assertions++;
        EntityItem loaded = new EntityItem(null);
        loaded.readEntityFromNBT(saved);
        if (EntityItemAccess.readAge(loaded) != (short) 40000) {
            throw new AssertionError("Test did not reproduce vanilla age truncation");
        }
        assertions++;
        LifecycleState loadedState = new LifecycleState();
        loadedState.load(LifecycleNbt.read(snapshot));
        EntityItemAccess.restoreAge(loaded, loadedState.restoreLoadedAge(EntityItemAccess.readAge(loaded)));
        if (EntityItemAccess.readAge(loaded) != 40000 || loaded.lifespan != 50000) {
            throw new AssertionError("Actual entity restoration changed age or custom lifespan");
        }
        assertions++;
        if (loaded.getItem().getItem() != Items.DIAMOND || loaded.getItem().getCount() != 3) {
            throw new AssertionError("Actual entity restoration changed the item stack");
        }
        assertions++;
        System.out.println("EntityItemAccessTest: " + assertions + " assertions passed (mapped Forge class, no world).");
    }
}
