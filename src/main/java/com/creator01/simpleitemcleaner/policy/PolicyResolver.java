package com.creator01.simpleitemcleaner.policy;

import net.minecraft.util.ResourceLocation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Registry names, never numeric item IDs. Rules are compiled once per server session. */
public final class PolicyResolver {
    private final Map<ResourceLocation, Set<Integer>> vanilla;
    private final Map<ResourceLocation, Set<Integer>> permanent;

    public PolicyResolver(String[] vanilla, String[] permanent, Predicate<ResourceLocation> exists) {
        this.vanilla = compile(vanilla, exists);
        this.permanent = compile(permanent, exists);
    }

    private static Map<ResourceLocation, Set<Integer>> compile(String[] entries, Predicate<ResourceLocation> exists) {
        Map<ResourceLocation, Set<Integer>> result = new HashMap<>();
        for (String entry : entries) {
            String[] parts = entry.trim().split("@", -1);
            if (parts.length > 2 || !parts[0].matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid item rule: " + entry + "; use namespace:item@metadata or @*");
            }
            ResourceLocation id = new ResourceLocation(parts[0]);
            if (!exists.test(id)) { throw new IllegalArgumentException("Unknown item registry name: " + id); }
            int metadata = -1;
            if (parts.length == 2 && !parts[1].equals("*")) {
                if (!parts[1].matches("[0-9]+")) { throw new IllegalArgumentException("Invalid metadata: " + entry); }
                try { metadata = Integer.parseInt(parts[1]); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("Metadata out of range: " + entry); }
            }
            result.computeIfAbsent(id, key -> new HashSet<>()).add(metadata);
        }
        return result;
    }

    public ItemPolicy resolve(ResourceLocation id, int metadata) {
        if (matches(permanent, id, metadata)) { return ItemPolicy.PERMANENT; }
        if (matches(vanilla, id, metadata)) { return ItemPolicy.VANILLA; }
        return ItemPolicy.CONTROLLED;
    }

    private static boolean matches(Map<ResourceLocation, Set<Integer>> rules, ResourceLocation id, int metadata) {
        Set<Integer> values = rules.get(id);
        return values != null && (values.contains(-1) || values.contains(metadata));
    }

    public boolean hasPermanentRules() { return !permanent.isEmpty(); }
}
