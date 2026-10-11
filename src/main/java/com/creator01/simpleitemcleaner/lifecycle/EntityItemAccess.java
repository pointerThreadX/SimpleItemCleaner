package com.creator01.simpleitemcleaner.lifecycle;

import net.minecraft.entity.item.EntityItem;

import java.lang.reflect.Field;

/** Avoids the client-only getAge method and repeated reflective field lookup. */
public final class EntityItemAccess {
    private static Field ageField;

    private EntityItemAccess() { }

    public static void initialize() {
        if (ageField == null) {
            // Resolve both actual field names rather than depending on launcher flags.
            // Plain-JVM tests do not initialize FMLLaunchHandler's environment flag.
            for (String name : new String[] {"field_70292_b", "age"}) {
                try {
                    Field candidate = EntityItem.class.getDeclaredField(name);
                    if (candidate.getType() != int.class) {
                        throw new IllegalStateException("Unexpected EntityItem age field type");
                    }
                    candidate.setAccessible(true);
                    ageField = candidate;
                    return;
                } catch (NoSuchFieldException ignored) {
                    // Try the other supported 1.12.2 naming environment.
                }
            }
            throw new IllegalStateException("Cannot locate EntityItem age (SRG or MCP)");
        }
    }

    public static int readAge(EntityItem item) {
        initialize();
        try {
            return ageField.getInt(item);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read EntityItem age", exception);
        }
    }

    public static void restoreAge(EntityItem item, int age) {
        initialize();
        try {
            ageField.setInt(item, age);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot restore EntityItem age", exception);
        }
    }
}
