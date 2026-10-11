package com.creator01.simpleitemcleaner.client;

import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import com.creator01.simpleitemcleaner.config.ConfigEditorSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiMessageDialog;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

@SideOnly(Side.CLIENT)
public final class ConfigGuiFactory implements IModGuiFactory {
    @Override public void initialize(Minecraft minecraft) { }
    @Override public boolean hasConfigGui() { return true; }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return Collections.emptySet(); }

    @Override public GuiScreen createConfigGui(GuiScreen parent) {
        try {
            return new ConfigScreen(parent, new ConfigEditorSession(SimpleItemCleaner.configFile(), ForgeRegistries.ITEMS::containsKey));
        } catch (IOException | RuntimeException exception) {
            SimpleItemCleaner.log().warn("Cannot open local configuration editor.", exception);
            return new GuiMessageDialog(parent, "simpleitemcleaner.gui.error",
                    new TextComponentString(I18n.format("simpleitemcleaner.gui.openError") + "\n" + exception.getMessage()), "gui.done");
        }
    }
}
