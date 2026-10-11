package com.creator01.simpleitemcleaner.lifecycle;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Real Minecraft NBT types and gzip serialization, without a game/world or test library. */
public final class LifecycleNbtTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        for (int age : new int[] {0, 32767, 32768, 40000, 65536, 1000000, -32768}) {
            LifecycleState state = new LifecycleState();
            state.track();
            NBTTagCompound entity = new NBTTagCompound();
            entity.setShort("Age", (short) age);
            entity.setInteger("Lifespan", 2000000);
            NBTTagCompound capabilities = new NBTTagCompound();
            capabilities.setTag("simpleitemcleaner:lifecycle", LifecycleNbt.write(state.snapshot(age, 2000000)));
            entity.setTag("ForgeCaps", capabilities);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(entity, output);
            NBTTagCompound decoded = CompressedStreamTools.readCompressed(
                    new ByteArrayInputStream(output.toByteArray()));
            LifecycleState.Snapshot snapshot = LifecycleNbt.read(decoded.getCompoundTag("ForgeCaps")
                    .getCompoundTag("simpleitemcleaner:lifecycle"));
            check(snapshot != null && snapshot.age == age, "Full int survives compressed NBT: " + age);
            LifecycleState loaded = new LifecycleState();
            loaded.load(snapshot);
            check(loaded.restoreLoadedAge(decoded.getShort("Age")) == age,
                    "Restore through the actual vanilla short conversion: " + age);
        }

        LifecycleState extended = new LifecycleState();
        extended.extend(40000, 40000);
        LifecycleState restored = new LifecycleState();
        restored.load(LifecycleNbt.read(LifecycleNbt.write(extended.snapshot(40500, 41200))));
        check(restored.restoreLoadedAge((short) 40500) == 40500, "Extended age round-trip");
        check(restored.release(41200) == 40000, "Custom original life round-trip and rollback");

        NBTTagCompound valid = LifecycleNbt.write(extended.snapshot(40500, 41200));
        NBTTagCompound unknown = valid.copy();
        unknown.setInteger("Version", 2);
        check(LifecycleNbt.read(unknown) == null, "Reject unknown future schema");
        NBTTagCompound shortAge = valid.copy();
        shortAge.setShort("FullAge", (short) 40500);
        check(LifecycleNbt.read(shortAge) == null, "Reject wrongly typed full age");
        NBTTagCompound missing = valid.copy();
        missing.removeTag("FullAge");
        check(LifecycleNbt.read(missing) == null, "Reject absent full age rather than substituting zero");
        NBTTagCompound badOwnership = valid.copy();
        badOwnership.setInteger("OriginalLifespan", 50000);
        check(LifecycleNbt.read(badOwnership) == null, "Reject inconsistent ownership");
        check(LifecycleNbt.read(new NBTTagCompound()) == null, "Empty state is not an age of zero");
        System.out.println("LifecycleNbtTest: " + assertions + " assertions passed.");
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
