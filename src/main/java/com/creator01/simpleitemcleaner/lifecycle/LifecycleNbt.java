package com.creator01.simpleitemcleaner.lifecycle;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;

/** Versioned, strictly typed NBT; unknown/corrupt data is not interpreted as a deadline. */
public final class LifecycleNbt {
    private LifecycleNbt() { }

    public static NBTTagCompound write(LifecycleState.Snapshot snapshot) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("Version", LifecycleState.FORMAT_VERSION);
        tag.setInteger("FullAge", snapshot.age);
        tag.setBoolean("OwnsLifespan", snapshot.ownsLifespan);
        tag.setInteger("OriginalLifespan", snapshot.originalLifespan);
        tag.setInteger("ExpectedLifespan", snapshot.expectedLifespan);
        tag.setBoolean("Conflict", snapshot.conflict);
        return tag;
    }

    @Nullable
    public static LifecycleState.Snapshot read(NBTTagCompound tag) {
        if (!tag.hasKey("Version", Constants.NBT.TAG_INT)
                || tag.getInteger("Version") != LifecycleState.FORMAT_VERSION
                || !tag.hasKey("FullAge", Constants.NBT.TAG_INT)
                || !tag.hasKey("OwnsLifespan", Constants.NBT.TAG_BYTE)
                || !tag.hasKey("OriginalLifespan", Constants.NBT.TAG_INT)
                || !tag.hasKey("ExpectedLifespan", Constants.NBT.TAG_INT)
                || !tag.hasKey("Conflict", Constants.NBT.TAG_BYTE)) {
            return null;
        }
        boolean owns = tag.getBoolean("OwnsLifespan");
        int original = tag.getInteger("OriginalLifespan");
        int expected = tag.getInteger("ExpectedLifespan");
        if (owns && (expected <= original || expected < 0)) {
            return null;
        }
        return new LifecycleState.Snapshot(tag.getInteger("FullAge"), owns, original, expected,
                tag.getBoolean("Conflict"));
    }
}
