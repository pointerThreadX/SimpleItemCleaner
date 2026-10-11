package com.creator01.simpleitemcleaner.config;

import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import com.creator01.simpleitemcleaner.cleanup.CleanupController;
import com.creator01.simpleitemcleaner.cleanup.CleanupClock;
import com.creator01.simpleitemcleaner.cleanup.CleanupResult;
import com.creator01.simpleitemcleaner.client.ConfigGuiFactory;
import com.creator01.simpleitemcleaner.client.ConfigScreen;
import com.creator01.simpleitemcleaner.policy.ItemPolicy;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.apache.logging.log4j.LogManager;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.List;

/** Headless editor/session tests. No GuiScreen is constructed, drawn or clicked. */
public final class ConfigEditorTest {
    private static int assertions;
    private static File directory;

    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory(new File("build/simpleitemcleaner").toPath(), "editor-tests-").toFile();
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); home.set(null, directory);
        Field logger = SimpleItemCleaner.class.getDeclaredField("logger");
        logger.setAccessible(true); logger.set(null, LogManager.getLogger("SimpleItemCleanerEditorTest"));
        Bootstrap.register();
        registration(); editing(); existingMessages(); validation(); sessions(); dedicatedLoading();
        ModConfig.disable();
        System.out.println("ConfigEditorTest: " + assertions + " assertions passed (draft IO, categories, validation, sessions, GUI registration, dedicated class loading).");
    }

    private static ConfigEditorSession open(File file) throws IOException {
        return new ConfigEditorSession(file, ForgeRegistries.ITEMS::containsKey);
    }
    private static Property property(ConfigEditorSession editor, String key) {
        String[] parts = key.split("\\.");
        return editor.draft().getCategory(parts[0]).get(parts[1]);
    }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) { throw new AssertionError(label); }
    }

    private static void registration() throws Exception {
        Mod mod = SimpleItemCleaner.class.getAnnotation(Mod.class);
        check(mod.guiFactory().equals(ConfigGuiFactory.class.getName()), "Factory registered by string in @Mod");
        check(ConfigGuiFactory.class.getAnnotation(SideOnly.class).value() == Side.CLIENT, "Factory is physical-client only");
        ConfigGuiFactory factory = new ConfigGuiFactory(); factory.initialize(null);
        check(factory.hasConfigGui() && factory.runtimeGuiCategories().isEmpty(), "Config enabled, no runtime GUI callbacks");
        check(ConfigEditorSession.Section.values().length == 6, "Six requested UI categories");
        ConfigEditorSession editor = open(new File(directory, "categories.cfg"));
        Set<String> represented = new HashSet<>();
        for (ConfigEditorSession.Section section : ConfigEditorSession.Section.values()) {
            for (String key : section.keys()) {
                check(represented.add(key) && property(editor, key) != null, "Existing option appears exactly once: " + key);
            }
        }
        Set<String> actual = new HashSet<>();
        for (String category : editor.draft().getCategoryNames()) {
            for (String key : editor.draft().getCategory(category).keySet()) { actual.add(category + "." + key); }
        }
        check(represented.equals(actual) && actual.size() == 14, "All 14 options covered; none invented");
        // Build native element descriptors only: no Minecraft instance or screen rendering required.
        Method elements = ConfigScreen.class.getDeclaredMethod("elements", ConfigEditorSession.class);
        elements.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<IConfigElement> groups = (List<IConfigElement>) elements.invoke(null, editor);
        check(groups.size() == 6, "Native GUI exposes six category elements");
        int displayed = 0;
        for (IConfigElement group : groups) {
            for (IConfigElement element : group.getChildElements()) {
                displayed++;
                check(element.requiresWorldRestart() && !element.requiresMcRestart(), "Next session, no Minecraft process restart: " + element.getName());
            }
        }
        check(displayed == 14, "Actual GUI includes every setting");
        check(property(editor, "general.intervalSeconds").getMinValue().equals("1")
                && property(editor, "general.minimumAgeSeconds").getMaxValue().equals("86400"), "GUI scalar bounds");
        check(property(editor, "general.excludedDimensions").getMinValue().equals(Integer.toString(Integer.MIN_VALUE))
                && property(editor, "messages.warningSeconds").getMaxValue().equals(Integer.toString(Integer.MAX_VALUE)), "GUI lists preserve signed integer range");
        check(property(editor, "messages.resultColor").getValidValues().length == 16, "GUI offers the sixteen Minecraft colors");
        check(property(editor, "messages.resultMessage").getValidationPattern().matcher("").matches(), "GUI accepts existing empty-message semantics");
        check(ConfigEditorTest.class.getResource("/assets/simpleitemcleaner/lang/pt_br.lang") == null,
                "Only English translations are bundled; resource-pipeline tests cover fallback");
        for (String locale : new String[] {"en_us"}) {
            Properties language = new Properties();
            try (Reader reader = new InputStreamReader(ConfigEditorTest.class.getResourceAsStream(
                    "/assets/simpleitemcleaner/lang/" + locale + ".lang"), StandardCharsets.UTF_8)) { language.load(reader); }
            boolean complete = true;
            for (String key : actual) {
                complete &= language.containsKey("simpleitemcleaner.config." + key)
                        && language.containsKey("simpleitemcleaner.config." + key + ".tooltip");
            }
            for (ConfigEditorSession.Section section : ConfigEditorSession.Section.values()) {
                complete &= language.containsKey("simpleitemcleaner.category." + section.name().toLowerCase(java.util.Locale.ROOT));
            }
            check(complete, "Localized labels and descriptions: " + locale);
        }
    }

    private static void editing() throws Exception {
        File file = new File(directory, "simpleitemcleaner.cfg");
        ConfigEditorSession unused = open(file);
        property(unused, "general.intervalSeconds").set(60);
        check(!file.exists(), "Open/edit/cancel before first session does not create a file");
        ConfigEditorSession defaults = open(file);
        check(property(defaults, "general.intervalSeconds").getInt() == 300, "Canceled draft not retained");
        defaults.save();
        check(file.isFile(), "Explicit save creates CFG");
        byte[] before = Files.readAllBytes(file.toPath());
        ConfigEditorSession canceled = open(file);
        property(canceled, "policies.permanent").set(new String[] {"minecraft:diamond"});
        check(Arrays.equals(before, Files.readAllBytes(file.toPath())), "Opening and canceling preserves existing bytes");
        ConfigEditorSession editor = open(file);
        property(editor, "general.intervalSeconds").set(60);
        property(editor, "general.minimumAgeSeconds").set(15);
        property(editor, "general.excludedDimensions").set(new int[] {-1, 42});
        property(editor, "messages.warningSeconds").set(new int[] {30, 10});
        property(editor, "messages.resultMessage").set("Action → {count}/{entities} in {duration} ms {unknown}");
        property(editor, "messages.warningMessage").set("Warning {time}");
        property(editor, "policies.vanilla").set(new String[] {"minecraft:stick", "minecraft:diamond@2"});
        property(editor, "policies.permanent").set(new String[] {"minecraft:diamond@3"});
        editor.draft().get("custom", "untouched", "keep me");
        ModConfig snapshot = ModConfig.current();
        editor.save();
        check(ModConfig.current() == snapshot, "Saving does not publish or hot-reload a server snapshot");
        ConfigEditorSession reopened = open(file);
        ModConfig parsed = ModConfig.read(reopened.draft(), ForgeRegistries.ITEMS::containsKey);
        check(parsed.intervalSeconds == 60 && parsed.minimumAgeSeconds == 15, "Numbers persist");
        check(parsed.excludedDimensions.contains(-1) && parsed.excludedDimensions.contains(42), "Signed/unknown integer dimension IDs preserved");
        check(Arrays.equals(parsed.warningSeconds(), new int[] {30, 10}), "Warning list persists");
        check(parsed.policies.resolve(new ResourceLocation("minecraft:diamond"), 2) == ItemPolicy.VANILLA
                && parsed.policies.resolve(new ResourceLocation("minecraft:diamond"), 3) == ItemPolicy.PERMANENT, "Both policies and metadata persist");
        check(new CleanupResult(16, 3, 83000).format(parsed.resultMessage)
                .equals("Action → 16/3 in 0,083 ms {unknown}"), "UTF-8, placeholders and unknown literal survive round trip");
        check(reopened.draft().getCategory("custom").get("untouched").getString().equals("keep me"), "Unrelated properties preserved");
        property(reopened, "general.intervalSeconds").setToDefault(); reopened.save();
        check(property(open(file), "general.intervalSeconds").getInt() == 300, "Explicit restore-default/save persists default");
        ConfigEditorSession stale = open(file);
        byte[] external = "general {\n B:enabled=false\n}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), external);
        boolean rejected = false;
        try { stale.save(); } catch (IOException expected) { rejected = true; }
        check(rejected && Arrays.equals(external, Files.readAllBytes(file.toPath())), "Concurrent manual edits are not overwritten");
    }

    private static void existingMessages() throws Exception {
        File file = new File(directory, "existing-messages.cfg");
        ConfigEditorSession editor = open(file);
        // Character data exercises unrestricted Unicode without bundling a non-English translation.
        String symbols = new String(new int[] {0x03A9, 0x6F22, 0x1F680}, 0, 3);
        String warning = symbols + " {time}";
        String result = symbols + " {count}/{entities} {duration} {unknown}";
        property(editor, "messages.warningMessage").set(warning);
        property(editor, "messages.resultMessage").set(result);
        editor.save();
        byte[] before = Files.readAllBytes(file.toPath());
        ModConfig.load(file, SimpleItemCleaner.log());
        check(ModConfig.current().enabled, "Existing Unicode messages remain valid");
        check(ModConfig.current().warningMessage.equals(warning), "Loading never translates an existing warning");
        check(ModConfig.current().resultMessage.equals(result), "Loading never replaces an existing result with new defaults");
        check(Arrays.equals(before, Files.readAllBytes(file.toPath())), "Loading preserves existing CFG bytes");
        check(new CleanupResult(16, 3, 83000).format(ModConfig.current().resultMessage)
                .equals(symbols + " 16/3 0,083 {unknown}"), "Unicode, placeholders and numeric formatting stay unchanged");
        ConfigEditorSession reopened = open(file);
        property(reopened, "general.intervalSeconds").set(60);
        reopened.save();
        check(property(open(file), "messages.resultMessage").getString().equals(result), "Unrelated GUI edits preserve arbitrary message text");
        check(property(open(file), "messages.warningMessage").getString().equals(warning), "Unrelated GUI edits preserve warning text");
        ModConfig.disable();
    }

    private static void validation() throws Exception {
        File file = new File(directory, "validation.cfg"); open(file).save();
        byte[] good = Files.readAllBytes(file.toPath());
        String[][] invalid = {{"general.intervalSeconds", "0"}, {"general.intervalSeconds", "2147483648"},
                {"general.minimumAgeSeconds", "-1"}, {"general.minimumAgeSeconds", "86401"},
                {"general.enabled", "maybe"}, {"messages.resultColor", "BOLD"}};
        for (String[] pair : invalid) {
            ConfigEditorSession editor = open(file); property(editor, pair[0]).set(pair[1]);
            rejectSave(editor, file, good, pair[0]);
        }
        for (String key : new String[] {"messages.warningSeconds", "general.excludedDimensions"}) {
            ConfigEditorSession editor = open(file); property(editor, key).set(new String[] {"not-an-integer"});
            rejectSave(editor, file, good, key);
        }
        for (String item : new String[] {"diamond", "missing:item", "minecraft:diamond@2147483648"}) {
            ConfigEditorSession editor = open(file); property(editor, "policies.permanent").set(new String[] {item});
            rejectSave(editor, file, good, "Invalid/unknown item");
        }
        ConfigEditorSession tooLong = open(file);
        char[] chars = new char[1025]; Arrays.fill(chars, 'x');
        property(tooLong, "messages.resultMessage").set(new String(chars));
        rejectSave(tooLong, file, good, "Message limit");
        ConfigEditorSession valid = open(file);
        property(valid, "policies.vanilla").set(new String[0]); property(valid, "policies.permanent").set(new String[0]);
        property(valid, "general.excludedDimensions").set(new int[0]); property(valid, "messages.resultMessage").set("");
        property(valid, "messages.warningSeconds").set(new int[] {-1, 0, 300, 30, 30}); valid.save();
        ModConfig parsed = ModConfig.read(open(file).draft(), ForgeRegistries.ITEMS::containsKey);
        check(parsed.resultMessage.isEmpty() && Arrays.equals(parsed.warningSeconds(), new int[] {30}), "Empty text/lists and ignored warning values preserve prior rules");
        property(valid, "messages.warningSeconds").set(new int[0]); valid.save();
        check(ModConfig.read(open(file).draft(), ForgeRegistries.ITEMS::containsKey).warningSeconds().length == 0, "Empty warnings list accepted");
        File malformed = new File(directory, "malformed.cfg");
        byte[] broken = "general {\n ? broken syntax\n}\n".getBytes(StandardCharsets.UTF_8); Files.write(malformed.toPath(), broken);
        boolean rejected = false;
        try { open(malformed); } catch (RuntimeException expected) { rejected = true; }
        check(rejected && Arrays.equals(broken, Files.readAllBytes(malformed.toPath())), "Malformed file is not renamed, reset or rewritten");
        File blocked = new File(directory, "blocked.cfg"); ConfigEditorSession blockedEditor = open(blocked);
        Files.createDirectory(blocked.toPath()); rejected = false;
        try { blockedEditor.save(); } catch (IOException expected) { rejected = true; }
        check(rejected && blocked.isDirectory(), "IO error surfaced without overwriting target");
    }

    private static void rejectSave(ConfigEditorSession editor, File file, byte[] expected, String label) throws Exception {
        boolean rejected = false;
        try { editor.save(); } catch (IllegalArgumentException invalid) { rejected = true; }
        check(rejected && Arrays.equals(expected, Files.readAllBytes(file.toPath())), "Invalid input leaves CFG intact: " + label);
    }

    private static void sessions() throws Exception {
        File file = new File(directory, "sessions.cfg"); ConfigEditorSession editor = open(file);
        property(editor, "general.intervalSeconds").set(60); property(editor, "messages.warningSeconds").set(new int[] {30, 10}); editor.save();
        Field configFile = SimpleItemCleaner.class.getDeclaredField("configFile"); configFile.setAccessible(true); configFile.set(null, file);
        SimpleItemCleaner mod = new SimpleItemCleaner();
        // Invoke the real lifecycle methods; no launcher/world or actual server ticks are started.
        mod.serverAboutToStart(null);
        check(ModConfig.current().intervalSeconds == 60, "AboutToStart rereads editor file");
        Field controllerField = SimpleItemCleaner.class.getDeclaredField("cleanup"); controllerField.setAccessible(true);
        CleanupController controller = (CleanupController) controllerField.get(mod);
        controller.start(null); // Clock initialization itself does not access the server.
        check(controller.remainingSeconds() == 60, "First session interval initialized");
        ModConfig active = ModConfig.current();
        ConfigEditorSession next = open(file); property(next, "general.intervalSeconds").set(20);
        property(next, "messages.warningSeconds").set(new int[] {5, 2}); next.save();
        check(ModConfig.current() == active && controller.remainingSeconds() == 60, "Saving does not alter current session/timer");
        mod.serverStopped(null);
        check(!ModConfig.current().enabled && controller.remainingSeconds() == 0, "Stop clears snapshot and timer");
        mod.serverAboutToStart(null); controller.start(null);
        check(ModConfig.current().intervalSeconds == 20 && controller.remainingSeconds() == 20, "Reentry in same JVM reads new interval");
        Field clockField = CleanupController.class.getDeclaredField("clock"); clockField.setAccessible(true);
        CleanupClock clock = (CleanupClock) clockField.get(controller);
        int last = 0; for (int i = 0; i < 300; i++) { last = clock.tick(); }
        check(last == 5, "Reentry uses new warning schedule");
        mod.serverStopped(null);
        ConfigEditorSession disabled = open(file); property(disabled, "general.enabled").set(false); disabled.save();
        mod.serverAboutToStart(null); controller.start(null);
        check(!controller.isActive() && !ModConfig.current().enabled, "Saved disable applied on next entry");
        mod.serverStopped(null);
    }

    private static void dedicatedLoading() throws Exception {
        String[] paths = System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator));
        URL[] urls = new URL[paths.length];
        for (int i = 0; i < paths.length; i++) { urls[i] = new File(paths[i]).toURI().toURL(); }
        try (URLClassLoader serverLoader = new URLClassLoader(urls, null) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.minecraft.client.") || name.startsWith("net.minecraftforge.fml.client.")
                        || name.startsWith("com.creator01.simpleitemcleaner.client.")) {
                    throw new ClassNotFoundException("Graphical class forbidden in dedicated loading test");
                }
                return super.loadClass(name, resolve);
            }
        }) {
            String[] common = {"SimpleItemCleaner", "config.ModConfig", "config.ConfigEditorSession",
                    "cleanup.CleanupController", "cleanup.CleanupService", "lifecycle.LifecycleProvider"};
            for (String name : common) {
                Class<?> type = Class.forName("com.creator01.simpleitemcleaner." + name, true, serverLoader);
                type.getDeclaredMethods(); type.getDeclaredFields(); type.getDeclaredConstructors();
                check(true, "Common class loads with all graphical classes denied: " + name);
            }
            boolean denied = false;
            try { serverLoader.loadClass("com.creator01.simpleitemcleaner.client.ConfigGuiFactory"); }
            catch (ClassNotFoundException expected) { denied = true; }
            check(denied, "Dedicated loader control actually denies GUI classes");
        }
    }
}
