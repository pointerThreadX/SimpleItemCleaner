package com.creator01.simpleitemcleaner.client;

import com.creator01.simpleitemcleaner.config.ConfigEditorSession;
import com.creator01.simpleitemcleaner.config.ModConfig;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.LanguageManager;
import net.minecraft.client.resources.LegacyV2Adapter;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.client.resources.data.PackMetadataSection;
import net.minecraft.client.resources.data.PackMetadataSectionSerializer;
import net.minecraft.init.Bootstrap;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.client.FMLFileResourcePack;
import net.minecraftforge.fml.client.config.DummyConfigElement.DummyCategoryElement;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.GuiConfigEntries.ArrayEntry;
import net.minecraftforge.fml.client.config.GuiConfigEntries.CategoryEntry;
import net.minecraftforge.fml.client.config.GuiConfigEntries.IConfigEntry;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Actual Forge resource loading and native entry data methods, without a window, GL or a running Minecraft. */
public final class GuiQualityTest {
    private static int assertions;
    private static Path directory;

    public static void main(String[] args) throws Exception {
        directory = Files.createTempDirectory(new File("build/simpleitemcleaner").toPath(), "gui-quality-");
        set(FMLInjectionData.class, null, "minecraftHome", directory.toFile());
        Bootstrap.register();
        translations(); reproduceOldFailure(); recovery(); failures();
        System.out.println("GuiQualityTest: " + assertions + " assertions passed (resource pipeline, native Undo/Reset/scopes, retries, footer geometry).");
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) { throw new AssertionError(label); }
    }

    private static void translations() throws Exception {
        File legacy = resourceJar(false);
        try (FMLFileResourcePack pack = pack(legacy)) {
            MetadataSerializer metadata = metadata();
            PackMetadataSection section = pack.getPackMetadata(metadata, "pack");
            check(section.getPackFormat() == 2, "Missing metadata really triggers Forge format-2 fallback");
            loadLanguage(new LegacyV2Adapter(pack), metadata, "pt_br");
            check(I18n.format("simpleitemcleaner.gui.save").equals("simpleitemcleaner.gui.save"), "Legacy adapter reproduces untranslated lowercase resource in ZIP");
        }
        File current = resourceJar(true);
        check(GuiQualityTest.class.getResource("/assets/simpleitemcleaner/lang/pt_br.lang") == null,
                "No redundant language resource; non-English clients use the English fallback");
        try (FMLFileResourcePack pack = pack(current)) {
            MetadataSerializer metadata = metadata();
            PackMetadataSection section = pack.getPackMetadata(metadata, "pack");
            check(section.getPackFormat() == 3, "Explicit format 3 avoids legacy path rewriting");
            for (String language : new String[] {"en_us", "pt_br"}) {
                loadLanguage(pack, metadata, language);
                Properties expected = new Properties();
                try (Reader reader = new InputStreamReader(GuiQualityTest.class.getResourceAsStream(
                        "/assets/simpleitemcleaner/lang/en_us.lang"), StandardCharsets.UTF_8)) { expected.load(reader); }
                for (String key : expected.stringPropertyNames()) {
                    check(I18n.hasKey(key) && !I18n.format(key).equals(key) && I18n.format(key).equals(expected.getProperty(key)), "Native translation resolves " + language + ": " + key);
                }
                check(I18n.format("simpleitemcleaner.gui.save").equals("Save"), "English Save caption with client language " + language);
                for (String key : new String[] {"save", "local", "error", "openError", "saveError", "undo", "defaults"}) {
                    check(expected.containsKey("simpleitemcleaner.gui." + key), "Every GUI message exists: " + key);
                }
                footer(language);
            }
        }
    }

    private static MetadataSerializer metadata() {
        MetadataSerializer serializer = new MetadataSerializer();
        serializer.registerMetadataSectionType(new PackMetadataSectionSerializer(), PackMetadataSection.class);
        return serializer;
    }

    private static FMLFileResourcePack pack(final File jar) {
        ModMetadata metadata = new ModMetadata(); metadata.name = "SimpleItemCleaner"; metadata.modId = "simpleitemcleaner";
        return new FMLFileResourcePack(new DummyModContainer(metadata) {
            @Override public File getSource() { return jar; }
        });
    }

    private static File resourceJar(boolean withMetadata) throws IOException {
        Path jar = directory.resolve(withMetadata ? "current-resources.jar" : "legacy-resources.jar");
        String[] paths = {"pack.mcmeta", "assets/simpleitemcleaner/lang/en_us.lang"};
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String path : paths) {
                if (!withMetadata && path.equals("pack.mcmeta")) { continue; }
                zip.putNextEntry(new ZipEntry(path));
                Files.copy(new File("src/main/resources", path).toPath(), zip);
                zip.closeEntry();
            }
        }
        return jar.toFile();
    }

    private static void loadLanguage(IResourcePack pack, MetadataSerializer metadata, String code) {
        SimpleReloadableResourceManager resources = new SimpleReloadableResourceManager(metadata);
        resources.reloadResources(Collections.singletonList(pack));
        LanguageManager language = new LanguageManager(metadata, code);
        language.onResourceManagerReload(resources);
    }

    private static void footer(String language) {
        // Conservative six-pixel character budget; longer English fixtures also exercise caption wrapping.
        // Native Minecraft/Forge captions follow the client language; actual fonts require runtime checks.
        for (int width : new int[] {320, 427, 640, 854, 1280}) {
            for (boolean root : new boolean[] {true, false}) {
                List<GuiButton> buttons = new ArrayList<>();
                buttons.add(new GuiButtonExt(2000, 0, 0, 100, 20, root ? I18n.format("simpleitemcleaner.gui.save") : (language.equals("pt_br") ? "Finished" : "Done")));
                buttons.add(new GuiButtonExt(2001, 0, 0, 100, 20, ""));
                buttons.add(new GuiButtonExt(2002, 0, 0, 100, 20, ""));
                String global = language.equals("pt_br") ? "Apply to All Groups" : "Apply Globally";
                buttons.add(new GuiButtonExt(2003, 0, 0, global.length() * 6 + 13, 11, global));
                if (root) { buttons.add(new GuiButtonExt(2004, 0, 0, 60, 20, language.equals("pt_br") ? "Cancel Edits" : "Cancel")); }
                int bottom = ConfigScreen.layoutFooter(buttons, width, 240, text -> text.length() * 6);
                boolean fits = bottom > 33;
                for (GuiButton button : buttons) {
                    fits &= button.x >= 0 && button.x + button.width <= width && button.y >= bottom + 4 && button.y + button.height <= 240;
                    fits &= button.width >= button.displayString.length() * 6 + (button.id == 2003 ? 13 : 20);
                    for (GuiButton other : buttons) {
                        if (other == button) { continue; }
                        fits &= button.x + button.width <= other.x || other.x + other.width <= button.x
                                || button.y + button.height <= other.y || other.y + other.height <= button.y;
                    }
                }
                check(fits, "Footer fits without clipped captions/overlap: " + language + "/" + width + "/root=" + root);
            }
        }
    }

    private static ConfigEditorSession session(String name) throws IOException {
        ConfigEditorSession session = new ConfigEditorSession(directory.resolve(name + ".cfg").toFile(), ForgeRegistries.ITEMS::containsKey);
        session.save();
        return session;
    }
    private static Property property(ConfigEditorSession session, String key) {
        String[] parts = key.split("\\."); return session.draft().getCategory(parts[0]).get(parts[1]);
    }
    private static byte[] disk(String name) throws IOException { return Files.readAllBytes(directory.resolve(name + ".cfg")); }

    private static void reproduceOldFailure() throws Exception {
        ConfigEditorSession session = session("old-failure");
        byte[] before = disk("old-failure");
        ArrayEntry entry = array(session, "policies.vanilla");
        entry.setListFromChildScreen(new String[] {"minecraft:iron"});
        entry.saveConfigElement(); // The previous GUI flushed directly, outside a rollback boundary.
        try { session.save(); throw new AssertionError("Expected invalid item"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().contains("minecraft:iron"), "Old failure reproduced with exact user example"); }
        entry.undoChanges(); entry.saveConfigElement();
        check(!entry.isChanged() && property(session, "policies.vanilla").getStringList()[0].equals("minecraft:iron"), "Native Undo makes isChanged=false and skips stale backing property");
        check(Arrays.equals(before, disk("old-failure")), "Even old failure leaves disk intact");
    }

    private static void recovery() throws Exception {
        ConfigEditorSession session = session("retry");
        property(session, "messages.resultMessage").set("My message {count} {duration}"); session.save();
        byte[] before = disk("retry");
        ArrayEntry vanilla = array(session, "policies.vanilla"), permanent = array(session, "policies.permanent");
        ArrayEntry dimensions = array(session, "general.excludedDimensions");
        GuiConfigEntries policies = entries(vanilla, permanent);
        GuiConfigEntries root = entries(category(policies), category(entries(dimensions)));
        dimensions.setListFromChildScreen(new Integer[] {-1, 42});
        vanilla.setListFromChildScreen(new String[] {"minecraft:iron", "minecraft:stick"});
        permanent.setListFromChildScreen(new String[] {"minecraft:iron"});
        reject(session, root, before, "retry", "policies.vanilla");
        check(property(session, "policies.vanilla").getStringList().length == 0, "Failed flush rolls backing list back");
        check(dimensions.getCurrentValues().length == 2 && vanilla.getCurrentValues().length == 2, "Uncommitted controls remain visible after error");
        vanilla.setListFromChildScreen(new String[] {"minecraft:iron_ingot", "minecraft:stick"});
        reject(session, root, before, "retry", "policies.permanent");
        permanent.setToDefault(); // It now compares equal to the opening value; native flush skips it.
        session.save(() -> root.saveConfigElements());
        check(Arrays.equals(property(session, "policies.vanilla").getStringList(), new String[] {"minecraft:iron_ingot", "minecraft:stick"}), "Correction and Reset after successive errors save valid multi-item list");
        check(property(session, "policies.permanent").getStringList().length == 0, "Reset removed invalid value from disk candidate");
        check(Arrays.equals(property(session, "general.excludedDimensions").getIntList(), new int[] {-1, 42}), "Other category edits survive the failed attempts");
        check(property(session, "messages.resultMessage").getString().equals("My message {count} {duration}"), "Configured cleanup message unchanged");
        ConfigEditorSession reopened = new ConfigEditorSession(directory.resolve("retry.cfg").toFile(), ForgeRegistries.ITEMS::containsKey);
        check(Arrays.equals(property(reopened, "policies.vanilla").getStringList(), new String[] {"minecraft:iron_ingot", "minecraft:stick"}), "Corrected lists persist in reopened CFG");

        // Fresh editor, matching the real screen's lifetime after successful Save closes it.
        for (boolean reset : new boolean[] {false, true}) {
            String name = reset ? "reset" : "undo";
            ConfigEditorSession editor = session(name); byte[] good = disk(name);
            ArrayEntry v = array(editor, "policies.vanilla"), p = array(editor, "policies.permanent");
            ArrayEntry dims = array(editor, "general.excludedDimensions");
            GuiConfigEntries child = entries(v, p);
            GuiConfigEntries all = entries(category(child), category(entries(dims)));
            for (int attempt = 0; attempt < 3; attempt++) {
                v.setListFromChildScreen(new String[] {"minecraft:iron"});
                p.setListFromChildScreen(new String[] {"minecraft:iron_ingot"});
                dims.setListFromChildScreen(new Integer[] {-1});
                reject(editor, all, good, name, "policies.vanilla");
                if (reset) { all.setAllToDefault(false); } else { all.undoAllChanges(false); }
                check(v.isChanged(), "Global unchecked leaves subcategories unchanged");
                if (reset) { child.setAllToDefault(false); } else { child.undoAllChanges(false); }
                check(!v.isChanged() && !p.isChanged() && dims.isChanged(), "Local native Undo/Reset affects only selected category");
                v.setListFromChildScreen(new String[] {"minecraft:iron"});
                reject(editor, all, good, name, "policies.vanilla");
                if (reset) { all.setAllToDefault(true); } else { all.undoAllChanges(true); }
                check(!v.isChanged() && !p.isChanged() && !dims.isChanged(), "Apply Globally restores all category controls");
            }
            editor.save(() -> all.saveConfigElements());
            check(property(editor, "policies.vanilla").getStringList().length == 0, "Save after native " + name + " succeeds");
        }
    }

    private static void reject(ConfigEditorSession session, GuiConfigEntries root, byte[] original, String name, String field) throws Exception {
        boolean rejected = false;
        try { session.save(() -> root.saveConfigElements()); }
        catch (IllegalArgumentException expected) { rejected = expected.getMessage().contains(field) && expected.getMessage().contains("minecraft:iron"); }
        check(rejected, "Invalid identifier rejected with field: " + field);
        check(Arrays.equals(original, disk(name)), "Failed attempt preserves original file");
    }

    private static void failures() throws Exception {
        ConfigEditorSession session = session("partial"); byte[] before = disk("partial");
        boolean failed = false;
        try {
            session.save(() -> {
                property(session, "general.enabled").set(false);
                property(session, "policies.vanilla").set(new String[] {"minecraft:iron"});
                throw new IllegalArgumentException("later native field invalid");
            });
        } catch (IllegalArgumentException expected) { failed = true; }
        check(failed && property(session, "general.enabled").getBoolean() && property(session, "policies.vanilla").getStringList().length == 0, "Partial native flush restores scalar and list properties");
        check(Arrays.equals(before, disk("partial")), "Cancel after failed partial flush leaves disk untouched");
        session.save(() -> property(session, "policies.vanilla").set(new String[] {"minecraft:iron_ingot"}));
        check(property(session, "policies.vanilla").getStringList()[0].equals("minecraft:iron_ingot"), "Retry after collection exception succeeds");

        ConfigEditorSession io = session("external");
        byte[] external = "# edited externally\ngeneral {\n B:enabled=false\n}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("external.cfg"), external);
        failed = false;
        try { io.save(() -> property(io, "general.intervalSeconds").set(60)); }
        catch (IOException expected) { failed = true; }
        check(failed && property(io, "general.intervalSeconds").getInt() == 300, "IO failure also restores backing values");
        check(Arrays.equals(external, disk("external")), "External edit remains protected");
        check(!ModConfig.current().enabled, "Editor attempts never activate server settings");
    }

    // Allocate only the data used by native entry methods, bypassing constructors that require Minecraft/GL.
    // No production code uses Unsafe; this test never claims to render or click the GUI.
    private static <T> T bare(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field field = unsafeType.getDeclaredField("theUnsafe"); field.setAccessible(true);
        return type.cast(unsafeType.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }
    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static ArrayEntry array(ConfigEditorSession session, String key) throws Exception {
        ArrayEntry entry = bare(ArrayEntry.class);
        ConfigElement element = new ConfigElement(property(session, key));
        set(GuiConfigEntries.ListEntryBase.class, entry, "configElement", element);
        set(GuiConfigEntries.ListEntryBase.class, entry, "owningScreen", bare(GuiConfig.class));
        set(GuiConfigEntries.ButtonEntry.class, entry, "btnValue", new GuiButtonExt(0, 0, 0, 100, 20, ""));
        set(ArrayEntry.class, entry, "beforeValues", element.getList().clone());
        set(ArrayEntry.class, entry, "currentValues", element.getList().clone());
        return entry;
    }
    private static GuiConfigEntries entries(IConfigEntry... entries) throws Exception {
        GuiConfigEntries list = bare(GuiConfigEntries.class); list.listEntries = Arrays.asList(entries); return list;
    }
    private static CategoryEntry category(GuiConfigEntries children) throws Exception {
        CategoryEntry entry = bare(CategoryEntry.class);
        GuiConfig screen = bare(GuiConfig.class); screen.entryList = children;
        set(CategoryEntry.class, entry, "childScreen", screen);
        set(GuiConfigEntries.ListEntryBase.class, entry, "configElement", new DummyCategoryElement("test", "test", Collections.emptyList()));
        return entry;
    }
}
