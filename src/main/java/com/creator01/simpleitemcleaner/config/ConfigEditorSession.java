package com.creator01.simpleitemcleaner.config;

import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.Property;
import com.creator01.simpleitemcleaner.policy.PolicyResolver;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** A local editing transaction. No client classes, active snapshot updates, world access or network traffic. */
public final class ConfigEditorSession {
    public enum Section {
        GENERAL("general.enabled"),
        CLEANUP("general.intervalSeconds", "general.minimumAgeSeconds", "general.excludedDimensions"),
        WARNINGS("messages.warningsEnabled", "messages.warningSeconds", "messages.warningMessage", "messages.warningColor"),
        RESULTS("messages.resultsEnabled", "messages.resultMessage", "messages.resultColor"),
        POLICIES("policies.vanilla", "policies.permanent"),
        LOGGING("general.logStatistics");

        private final String[] keys;
        Section(String... keys) { this.keys = keys; }
        public String[] keys() { return keys.clone(); }
    }

    private final File file;
    private final Configuration draft;
    private final Predicate<ResourceLocation> itemExists;
    private byte[] original;

    public ConfigEditorSession(File file, Predicate<ResourceLocation> itemExists) throws IOException {
        this.file = file.getAbsoluteFile();
        this.itemExists = itemExists;
        original = existingBytes();
        draft = ModConfig.openDraft(this.file);
        // Shared rules populate all 14 fields and reject invalid input without rewriting it.
        ModConfig.read(draft, itemExists);
    }

    public Configuration draft() { return draft; }

    /** Keep native controls' original-value baseline consistent after any failed collection/save. */
    public void save(Runnable collectEdits) throws IOException {
        Map<Property, Object> before = new LinkedHashMap<>();
        for (Section section : Section.values()) {
            for (String key : section.keys()) {
                String[] parts = key.split("\\.");
                Property property = draft.getCategory(parts[0]).get(parts[1]);
                before.put(property, property.isList() ? property.getStringList().clone() : property.getString());
            }
        }
        boolean saved = false;
        try {
            collectEdits.run();
            save();
            saved = true;
        } finally {
            if (!saved) {
                before.forEach((property, value) -> {
                    if (value instanceof String[]) { property.set((String[]) value); }
                    else { property.set((String) value); }
                });
            }
        }
    }

    public void save() throws IOException {
        // Add field context to policy errors without changing the resolver or its validation rules.
        for (String key : new String[] {"vanilla", "permanent"}) {
            try { new PolicyResolver(draft.getCategory("policies").get(key).getStringList(), new String[0], itemExists); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("policies." + key + ": " + invalid.getMessage(), invalid); }
        }
        ModConfig.read(draft, itemExists); // Validation before any write; never activates the draft.
        if (!Arrays.equals(original, existingBytes())) {
            throw new IOException("Configuration changed on disk. Cancel and reopen the editor.");
        }
        StringWriter text = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(text)) {
            writer.write("# SimpleItemCleaner configuration; applied on the next server session.");
            writer.newLine();
            for (String name : draft.getCategoryNames()) {
                ConfigCategory category = draft.getCategory(name);
                if (!category.isChild()) { category.write(writer, 0); writer.newLine(); }
            }
        }
        byte[] encoded = text.toString().getBytes(StandardCharsets.UTF_8);
        Path target = file.toPath();
        Files.createDirectories(target.getParent());
        Path pending = Files.createTempFile(target.getParent(), "simpleitemcleaner-", ".pending");
        Files.write(pending, encoded);
        // If atomic replacement is unsupported/fails, surface the error and retain the pending file.
        // Do not fall back to truncating the user's configuration.
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        original = encoded;
    }

    private byte[] existingBytes() throws IOException {
        return file.exists() ? Files.readAllBytes(file.toPath()) : null;
    }
}
