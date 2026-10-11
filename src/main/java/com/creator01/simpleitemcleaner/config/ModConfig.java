package com.creator01.simpleitemcleaner.config;

import com.creator01.simpleitemcleaner.policy.PolicyResolver;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Immutable server-session settings. Configuration changes require reopening the server/world. */
public final class ModConfig {
    public static final String WARNING = "[SimpleItemCleaner] Clearing dropped items in {time}!";
    public static final String RESULT = "[SimpleItemCleaner] Items removed: {count} | {entities} entities | {duration} ms";
    private static final ModConfig DISABLED = new ModConfig(false, 300, 180, new int[0],
            false, false, false, WARNING, RESULT, TextFormatting.RED, TextFormatting.GREEN,
            Collections.emptySet(), new PolicyResolver(new String[0], new String[0], id -> false));
    private static volatile ModConfig current = DISABLED;
    public final boolean enabled, warningsEnabled, resultsEnabled, logStatistics;
    public final int intervalSeconds, minimumAgeSeconds;
    public final String warningMessage, resultMessage;
    public final TextFormatting warningColor, resultColor;
    public final Set<Integer> excludedDimensions;
    public final PolicyResolver policies;
    private final int[] warningSeconds;

    private ModConfig(boolean enabled, int interval, int age, int[] warnings, boolean warn, boolean results,
                      boolean log, String warningMessage, String resultMessage, TextFormatting warningColor,
                      TextFormatting resultColor, Set<Integer> dimensions, PolicyResolver policies) {
        this.enabled = enabled; intervalSeconds = interval; minimumAgeSeconds = age;
        warningSeconds = warnings.clone(); warningsEnabled = warn; resultsEnabled = results; logStatistics = log;
        this.warningMessage = warningMessage; this.resultMessage = resultMessage;
        this.warningColor = warningColor; this.resultColor = resultColor;
        excludedDimensions = Collections.unmodifiableSet(new HashSet<>(dimensions)); this.policies = policies;
    }

    public static ModConfig current() { return current; }
    public static void disable() { current = DISABLED; }
    public int[] warningSeconds() { return warningSeconds.clone(); }
    public int minimumAgeTicks() { return minimumAgeSeconds * 20; }
    public boolean allowsDimension(int dimension) { return enabled && !excludedDimensions.contains(dimension); }

    /** Opens an independent draft without saving defaults or publishing a server-session snapshot. */
    static Configuration openDraft(File file) throws IOException {
        if (!file.exists()) { return new Configuration(); }
        Files.readAllBytes(file.toPath());
        return new GuardedConfiguration(file);
    }

    public static void load(File file, Logger logger) {
        disable();
        try {
            // Configuration.load logs some IO failures without throwing. Check existing input explicitly.
            if (file.exists()) { Files.readAllBytes(file.toPath()); }
            Configuration config = new GuardedConfiguration(file);
            if (!file.isFile() || !file.canRead()) { throw new IllegalArgumentException("Configuration is not a readable file"); }
            ModConfig parsed = read(config, ForgeRegistries.ITEMS::containsKey);
            if (config.hasChanged()) { config.save(); }
            current = parsed;
            logger.info("SimpleItemCleaner enabled={}, interval={}s, minimumAge={}s, activeWarnings={}",
                    parsed.enabled, parsed.intervalSeconds, parsed.minimumAgeSeconds, parsed.warningSeconds);
            if (parsed.enabled && parsed.policies.hasPermanentRules()) {
                logger.warn("PERMANENT items can accumulate indefinitely, consume memory and reduce TPS. "
                        + "Protection is limited by integer age/lifespan and compatibility with other mods.");
            }
        } catch (RuntimeException | IOException exception) {
            logger.error("Invalid SimpleItemCleaner configuration; cleanup and protection DISABLED for this session. "
                    + "Correct simpleitemcleaner.cfg and reopen the server/world.", exception);
        }
    }

    /** Public for the executable configuration tests; does not publish settings or write files. */
    public static ModConfig read(Configuration c, Predicate<ResourceLocation> exists) {
        c.addCustomCategoryComment("general", "Server settings; changes require reopening the world/server. "
                + "Seconds are measured in server ticks (20 ticks = 1 second). Invalid values disable the session.");
        boolean enabled = bool(c, "general", "enabled", true, "Enable cleanup and natural-expiration protection.");
        int interval = positive(c, "intervalSeconds", 300);
        int age = positive(c, "minimumAgeSeconds", 180);
        Property warningList = c.get("messages", "warningSeconds", new String[] {"600", "300", "180", "60", "30"},
                "Seconds before cleanup. Ignore <=0 and >=interval; duplicates are sent once.", Property.Type.INTEGER);
        warningList.setRequiresWorldRestart(true);
        if (!warningList.isIntList()) { throw new IllegalArgumentException("messages.warningSeconds must contain integers"); }
        TreeSet<Integer> warnings = new TreeSet<>(Collections.reverseOrder());
        for (int value : warningList.getIntList()) { if (value > 0 && value < interval) { warnings.add(value); } }
        Property dims = c.get("general", "excludedDimensions", new String[0],
                "Dimension IDs excluded from cleanup AND expiration protection; existing owned extensions are restored on load.", Property.Type.INTEGER);
        dims.setRequiresWorldRestart(true);
        if (!dims.isIntList()) { throw new IllegalArgumentException("general.excludedDimensions must contain integers"); }
        Set<Integer> dimensions = new HashSet<>();
        for (int dimension : dims.getIntList()) { dimensions.add(dimension); }
        c.addCustomCategoryComment("policies", "CONTROLLED is the default. PERMANENT > VANILLA > CONTROLLED. "
                + "Use namespace:item, namespace:item@* or namespace:item@metadata. Unknown items disable this session. "
                + "PERMANENT can accumulate entities, increase memory usage and reduce TPS; not unlimited or immune to other damage/mods.");
        String[] vanilla = list(c, "vanilla", "Exclude from cleanup; use original natural expiration.");
        String[] permanent = list(c, "permanent", "Exclude from cleanup AND protect natural expiration while enabled.");
        return new ModConfig(enabled, interval, age, warnings.stream().mapToInt(Integer::intValue).toArray(),
                bool(c, "messages", "warningsEnabled", true, "Broadcast countdown warnings."),
                bool(c, "messages", "resultsEnabled", true, "Broadcast result, including zero removed entities."),
                bool(c, "general", "logStatistics", true, "Log cycle statistics and lifecycle session counters."),
                string(c, "warningMessage", WARNING, "Literal message; {time} becomes seconds remaining (e.g. 30s)."),
                string(c, "resultMessage", RESULT, "{count}: removed stack units; {entities}: removed EntityItem entities; "
                        + "{duration}: cleanup milliseconds with three decimal places (comma separator)."),
                color(c, "warningColor", "RED"), color(c, "resultColor", "GREEN"), dimensions,
                new PolicyResolver(vanilla, permanent, exists));
    }

    private static boolean bool(Configuration c, String category, String key, boolean fallback, String comment) {
        // Typed convenience getters silently replace invalid input with defaults. Validate raw values instead.
        Property p = c.get(category, key, Boolean.toString(fallback), comment, Property.Type.BOOLEAN); p.setRequiresWorldRestart(true);
        if (!p.isBooleanValue() || p.isList()) { throw new IllegalArgumentException(category + "." + key + " must be true or false"); }
        return p.getBoolean();
    }
    private static int positive(Configuration c, String key, int fallback) {
        Property p = c.get("general", key, Integer.toString(fallback), "1..86400 seconds.", Property.Type.INTEGER); p.setRequiresWorldRestart(true);
        if (!p.isIntValue() || p.isList() || p.getInt() < 1 || p.getInt() > 86400) {
            throw new IllegalArgumentException("general." + key + " must be 1..86400");
        }
        return p.getInt();
    }
    private static String[] list(Configuration c, String key, String comment) {
        Property p = c.get("policies", key, new String[0], comment); p.setRequiresWorldRestart(true);
        if (!p.isList()) { throw new IllegalArgumentException("policies." + key + " must be a list"); }
        return p.getStringList();
    }
    private static String string(Configuration c, String key, String fallback, String comment) {
        Property p = c.get("messages", key, fallback, comment); p.setRequiresWorldRestart(true);
        if (p.isList() || p.getString().length() > 1024) { throw new IllegalArgumentException("messages." + key + " must be text <=1024 characters"); }
        return p.getString();
    }
    private static TextFormatting color(Configuration c, String key, String fallback) {
        String value = string(c, key, fallback, "Minecraft color name, such as RED, GREEN, GOLD, WHITE.");
        TextFormatting color = TextFormatting.getValueByName(value.trim().toUpperCase(Locale.ROOT));
        if (color == null || !color.isColor()) { throw new IllegalArgumentException("Invalid message color: " + value); }
        return color;
    }

    /** Prevent Forge's constructor from renaming malformed files and silently enabling fresh defaults. */
    private static final class GuardedConfiguration extends Configuration {
        private RuntimeException failure;
        GuardedConfiguration(File file) {
            super(file);
            if (failure != null) { throw failure; }
        }
        @Override public void load() {
            try { super.load(); }
            catch (RuntimeException exception) { failure = exception; }
        }
    }
}
