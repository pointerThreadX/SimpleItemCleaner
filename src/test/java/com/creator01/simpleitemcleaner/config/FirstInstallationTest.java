package com.creator01.simpleitemcleaner.config;

import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import com.creator01.simpleitemcleaner.policy.ItemPolicy;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import org.apache.logging.log4j.LogManager;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;

/** Exercises the real session entry point with both the CFG and its parent absent. */
public final class FirstInstallationTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory(new File("build/simpleitemcleaner").toPath(), "first-install-").toFile();
        File file = new File(directory, "config/simpleitemcleaner.cfg");
        set(FMLInjectionData.class, "minecraftHome", directory);
        set(SimpleItemCleaner.class, "logger", LogManager.getLogger("FirstInstallationTest"));
        set(SimpleItemCleaner.class, "configFile", file);
        Bootstrap.register();
        check(!file.exists() && !file.getParentFile().exists(), "No precreated file or config directory");
        SimpleItemCleaner mod = new SimpleItemCleaner();
        mod.serverAboutToStart(null);
        check(file.isFile() && file.length() > 0, "Session creates a nonempty CFG");
        defaults(ModConfig.current());
        Configuration disk = new Configuration(file);
        check(disk.getCategory("general").size() + disk.getCategory("messages").size()
                + disk.getCategory("policies").size() == 14, "All 14 options persisted");
        check(Arrays.equals(disk.getCategory("messages").get("warningSeconds").getIntList(),
                new int[] {600, 300, 180, 60, 30}), "Raw default warning list persisted");
        check(disk.getCategory("policies").get("vanilla").getStringList().length == 0
                && disk.getCategory("policies").get("permanent").getStringList().length == 0, "Both policy lists empty");
        byte[] saved = Files.readAllBytes(file.toPath());
        mod.serverStopped(null);
        check(!ModConfig.current().enabled, "Stopping disables the previous snapshot");
        mod.serverAboutToStart(null);
        defaults(ModConfig.current());
        check(Arrays.equals(saved, Files.readAllBytes(file.toPath())), "Reopening preserves generated CFG bytes");
        mod.serverStopped(null);
        System.out.println("FirstInstallationTest: " + assertions + " assertions passed (absent CFG, defaults, persistence, session reopen).");
    }

    private static void defaults(ModConfig c) {
        check(c.enabled, "Default enabled");
        check(c.intervalSeconds == 300 && c.minimumAgeSeconds == 180 && c.minimumAgeTicks() == 3600, "Default timing");
        check(c.excludedDimensions.isEmpty() && c.allowsDimension(0), "Default dimensions");
        check(c.warningsEnabled && c.resultsEnabled && c.logStatistics, "Default switches");
        check(Arrays.equals(c.warningSeconds(), new int[] {180, 60, 30}), "Effective default warnings");
        check(c.warningMessage.equals(ModConfig.WARNING) && c.resultMessage.equals(ModConfig.RESULT), "Default templates");
        check(c.warningColor == TextFormatting.RED && c.resultColor == TextFormatting.GREEN, "Default colors");
        check(!c.policies.hasPermanentRules() && c.policies.resolve(new ResourceLocation("minecraft:diamond"), 0)
                == ItemPolicy.CONTROLLED, "Default policy");
    }

    private static void set(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) { throw new AssertionError(label); }
    }
}
