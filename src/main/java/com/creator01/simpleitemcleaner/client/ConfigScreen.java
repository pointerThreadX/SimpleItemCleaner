package com.creator01.simpleitemcleaner.client;

import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import com.creator01.simpleitemcleaner.config.ConfigEditorSession;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.client.config.DummyConfigElement.DummyCategoryElement;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.GuiConfigEntries;
import net.minecraftforge.fml.client.config.GuiMessageDialog;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.function.ToIntFunction;

@SideOnly(Side.CLIENT)
public final class ConfigScreen extends GuiConfig {
    private final ConfigEditorSession editing;

    public ConfigScreen(GuiScreen parent, ConfigEditorSession editing) {
        super(parent, elements(editing), SimpleItemCleaner.MOD_ID, true, false, "SimpleItemCleaner",
                I18n.format("simpleitemcleaner.gui.local"));
        this.editing = editing;
    }

    private static List<IConfigElement> elements(ConfigEditorSession editing) {
        List<IConfigElement> groups = new ArrayList<>();
        for (ConfigEditorSession.Section section : ConfigEditorSession.Section.values()) {
            List<IConfigElement> fields = new ArrayList<>();
            for (String key : section.keys()) {
                String[] parts = key.split("\\.");
                Property property = editing.draft().getCategory(parts[0]).get(parts[1]);
                property.setLanguageKey("simpleitemcleaner.config." + key);
                property.setRequiresMcRestart(false).setRequiresWorldRestart(true);
                if (property.getType() == Property.Type.INTEGER) {
                    property.setMinValue(property.isList() ? Integer.MIN_VALUE : 1);
                    property.setMaxValue(property.isList() ? Integer.MAX_VALUE : 86400);
                    if (!property.isList()) { property.setConfigEntryClass(StrictIntegerEntry.class); }
                } else if (key.endsWith("Color")) {
                    property.setValue(property.getString().trim().toUpperCase(Locale.ROOT));
                    property.setValidValues(Arrays.stream(TextFormatting.values()).filter(TextFormatting::isColor)
                            .map(Enum::name).toArray(String[]::new));
                } else if (key.endsWith("Message")) {
                    property.setValidationPattern(Pattern.compile("[\\s\\S]{0,1024}"));
                    property.setConfigEntryClass(StrictStringEntry.class);
                } else if (key.startsWith("policies.")) {
                    property.setValidationPattern(Pattern.compile("\\s*[a-z0-9_.-]+:[a-z0-9_./-]+(?:@(?:\\*|[0-9]+))?\\s*"));
                }
                fields.add(new ConfigElement(property));
            }
            String name = section.name().toLowerCase(Locale.ROOT);
            groups.add(new DummyCategoryElement(name, "simpleitemcleaner.category." + name, fields, CategoryEntry.class));
        }
        return groups;
    }

    @Override public void initGui() {
        super.initGui();
        for (GuiButton button : new ArrayList<>(buttonList)) {
            if (button.id == 2000) {
                button.displayString = I18n.format("simpleitemcleaner.gui.save");
                button.enabled = !isWorldRunning;
                buttonList.add(new GuiButtonExt(2004, 0, 0, 60, 20, I18n.format("gui.cancel")));
            }
        }
        entryList.bottom = layoutFooter(buttonList, width, height, fontRenderer::getStringWidth);
    }

    @Override protected void actionPerformed(GuiButton button) {
        if (button.id == 2004) {
            mc.displayGuiScreen(parentScreen); // Discard this independent draft, including child-screen edits.
        } else if (button.id == 2000) {
            if (mc.world != null) { return; } // Local main-menu editor only; no changes to a running server.
            try {
                editing.save(() -> entryList.saveConfigElements());
                mc.displayGuiScreen(parentScreen);
            } catch (IOException | RuntimeException exception) {
                SimpleItemCleaner.log().warn("Local configuration was not saved.", exception);
                mc.displayGuiScreen(new GuiMessageDialog(this, "simpleitemcleaner.gui.error",
                        new TextComponentString(I18n.format("simpleitemcleaner.gui.saveError") + "\n" + exception.getMessage()), "gui.done"));
            }
        } else {
            super.actionPerformed(button); // Native Undo, Defaults and Apply Globally behavior.
        }
    }

    /** Reposition native buttons only; wrap at the scaled screen width instead of truncating captions. */
    static int layoutFooter(List<GuiButton> buttons, int width, int height, ToIntFunction<String> measure) {
        List<GuiButton> ordered = new ArrayList<>();
        for (int id : new int[] {2000, 2004, 2002, 2001, 2003}) {
            for (GuiButton button : buttons) {
                if (button.id != id) { continue; }
                if (id == 2001 || id == 2002) {
                    button.displayString = " " + I18n.format(id == 2001 ? "simpleitemcleaner.gui.defaults" : "simpleitemcleaner.gui.undo");
                    button.width = measure.applyAsInt(button.displayString) + 40; // Native Unicode glyph and padding.
                } else if (id != 2003) {
                    button.width = Math.max(60, measure.applyAsInt(button.displayString) + 20);
                }
                ordered.add(button);
            }
        }
        List<List<GuiButton>> rows = new ArrayList<>();
        List<GuiButton> row = new ArrayList<>();
        int rowWidth = 0;
        for (GuiButton button : ordered) {
            if (!row.isEmpty() && rowWidth + 4 + button.width > width - 16) {
                rows.add(row); row = new ArrayList<>(); rowWidth = 0;
            }
            row.add(button); rowWidth += (row.size() == 1 ? 0 : 4) + button.width;
        }
        if (!row.isEmpty()) { rows.add(row); }
        int top = height - 8 - rows.size() * 24;
        int y = top;
        for (List<GuiButton> line : rows) {
            int total = line.stream().mapToInt(button -> button.width).sum() + 4 * (line.size() - 1);
            int x = (width - total) / 2;
            for (GuiButton button : line) {
                button.x = x; button.y = y + (20 - button.height) / 2; x += button.width + 4;
            }
            y += 24;
        }
        return top - 4;
    }

    @SideOnly(Side.CLIENT)
    public static final class CategoryEntry extends GuiConfigEntries.CategoryEntry {
        public CategoryEntry(GuiConfig screen, GuiConfigEntries entries, IConfigElement element) { super(screen, entries, element); }
        @Override protected GuiScreen buildChildScreen() {
            return new CategoryScreen(owningScreen, configElement);
        }
    }

    @SideOnly(Side.CLIENT)
    public static final class CategoryScreen extends GuiConfig {
        CategoryScreen(GuiConfig parent, IConfigElement element) {
            super(parent, element.getChildElements(), parent.modID, true, false, parent.title,
                    I18n.format(element.getLanguageKey()));
        }
        @Override public void initGui() {
            super.initGui();
            entryList.bottom = layoutFooter(buttonList, width, height, fontRenderer::getStringWidth);
        }
    }

    /** Prevent native entries from silently clamping invalid input or replacing it with defaults on save. */
    @SideOnly(Side.CLIENT)
    public static final class StrictIntegerEntry extends GuiConfigEntries.IntegerEntry {
        public StrictIntegerEntry(GuiConfig screen, GuiConfigEntries entries, IConfigElement element) { super(screen, entries, element); }
        @Override public boolean saveConfigElement() {
            if (enabled()) {
                // Native Undo changes the text without refreshing its cached validity flag.
                int value;
                try { value = Integer.parseInt(getCurrentValue().toString().trim()); }
                catch (NumberFormatException exception) { throw new IllegalArgumentException(configElement.getName() + ": 1..86400"); }
                if (value < 1 || value > 86400) { throw new IllegalArgumentException(configElement.getName() + ": 1..86400"); }
                isValidValue = true;
            }
            return super.saveConfigElement();
        }
    }

    @SideOnly(Side.CLIENT)
    public static final class StrictStringEntry extends GuiConfigEntries.StringEntry {
        public StrictStringEntry(GuiConfig screen, GuiConfigEntries entries, IConfigElement element) { super(screen, entries, element); }
        @Override public boolean saveConfigElement() {
            if (enabled()) {
                if (getCurrentValue().toString().length() > 1024) {
                    throw new IllegalArgumentException(configElement.getName() + ": maximum 1024 characters");
                }
                isValidValue = true;
            }
            return super.saveConfigElement();
        }
    }
}
