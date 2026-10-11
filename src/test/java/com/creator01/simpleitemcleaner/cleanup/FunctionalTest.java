package com.creator01.simpleitemcleaner.cleanup;

import com.creator01.simpleitemcleaner.SimpleItemCleaner;
import com.creator01.simpleitemcleaner.command.SimpleItemCleanerCommand;
import com.creator01.simpleitemcleaner.config.ModConfig;
import com.creator01.simpleitemcleaner.lifecycle.EntityItemAccess;
import com.creator01.simpleitemcleaner.lifecycle.ItemLifecycleHandler;
import com.creator01.simpleitemcleaner.lifecycle.LifecycleState;
import com.creator01.simpleitemcleaner.lifecycle.LifecycleProvider;
import com.creator01.simpleitemcleaner.policy.ItemPolicy;
import com.creator01.simpleitemcleaner.policy.PolicyResolver;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.item.ItemExpireEvent;
import net.minecraftforge.fml.relauncher.FMLInjectionData;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.Proxy;

/** Actual production rules/handlers against mapped entities and an isolated World stub; no launched game. */
public final class FunctionalTest {
    private static int assertions;
    private static File fixtures;
    private static int fixtureId;
    private static final ResourceLocation DIAMOND = new ResourceLocation("minecraft:diamond");

    public static void main(String[] args) throws Exception {
        fixtures = new File("build/simpleitemcleaner/test-fixtures/" + System.currentTimeMillis());
        if (!fixtures.mkdirs()) { throw new AssertionError("Cannot create test fixtures"); }
        // Forge Configuration normally receives this path from the launcher, absent in a plain-JVM test.
        Field home = FMLInjectionData.class.getDeclaredField("minecraftHome");
        home.setAccessible(true); home.set(null, fixtures.getAbsoluteFile());
        Bootstrap.register();
        Field logger = SimpleItemCleaner.class.getDeclaredField("logger");
        logger.setAccessible(true); logger.set(null, LogManager.getLogger("SimpleItemCleanerTest"));
        identity(); configurations(); policies(); clocks(); mappedWorlds(); permissions(); controller(); counting();
        ModConfig.disable();
        System.out.println("FunctionalTest: " + assertions + " assertions passed (configuration, policies, ticks, mapped entity/world stubs, permissions).");
    }

    private static Configuration fresh() { return new Configuration(new File(fixtures, "case-" + (++fixtureId) + ".cfg")); }
    private static ModConfig read(Configuration c) { return ModConfig.read(c, id -> Items.DIAMOND.getRegistryName().equals(id)
            || Items.STICK.getRegistryName().equals(id) || id.equals(new ResourceLocation("minecraft:wool"))); }
    private static void activate(Configuration c) { c.save(); ModConfig.load(c.getConfigFile(), SimpleItemCleaner.log()); }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) { throw new AssertionError(message); }
    }
    private static void invalid(Configuration c, String message) {
        boolean rejected = false;
        try { read(c); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }

    private static void identity() {
        Mod annotation = SimpleItemCleaner.class.getAnnotation(Mod.class);
        check(SimpleItemCleaner.MOD_ID.equals("simpleitemcleaner") && annotation.modid().equals("simpleitemcleaner"), "Mod ID");
        check(annotation.name().equals("SimpleItemCleaner") && annotation.version().equals("1.0.0"), "Mod name/version");
        check(SimpleItemCleaner.class.getName().equals("com.creator01.simpleitemcleaner.SimpleItemCleaner"), "Public Java namespace");
        check(annotation.acceptableRemoteVersions().equals("*") && !annotation.serverSideOnly(), "Optional handshake and physical-client loading");
    }

    private static void configurations() throws Exception {
        Configuration fresh = fresh();
        ModConfig defaults = read(fresh);
        check(defaults.enabled && defaults.intervalSeconds == 300 && defaults.minimumAgeSeconds == 180, "Functional defaults");
        check(Arrays.equals(defaults.warningSeconds(), new int[] {180, 60, 30}), "Ignore warnings >= interval");
        check(defaults.warningColor == TextFormatting.RED && defaults.resultColor == TextFormatting.GREEN, "Default colors");
        check(defaults.warningsEnabled && defaults.resultsEnabled && defaults.logStatistics, "Default message/log switches");
        Configuration disabled = fresh(); disabled.get("general", "enabled", false);
        check(!read(disabled).enabled, "Explicit opt-out");
        disabled.get("general", "enabled", false).set(true);
        check(read(disabled).enabled, "Explicit opt-in");
        check(read(fresh()).enabled, "Independent fresh configuration is enabled");
        Configuration settings = fresh();
        settings.get("general", "intervalSeconds", 60); settings.get("general", "minimumAgeSeconds", 15);
        settings.get("messages", "warningSeconds", new int[] {60, 90, 30, 10, 10, 0, -1});
        settings.get("general", "excludedDimensions", new int[] {-1, 7});
        settings.get("messages", "warningColor", "gold");
        ModConfig custom = read(settings);
        check(Arrays.equals(custom.warningSeconds(), new int[] {30, 10}) && custom.minimumAgeTicks() == 300, "Custom settings");
        check(!custom.allowsDimension(-1) && !custom.allowsDimension(7) && custom.allowsDimension(0), "Dimension exclusions");
        check(custom.warningColor == TextFormatting.GOLD, "Configurable color");
        int[] copy = custom.warningSeconds(); copy[0] = 59;
        check(custom.warningSeconds()[0] == 30, "Immutable warning settings");
        for (int value : new int[] {-1, 0, 86401, Integer.MAX_VALUE}) {
            Configuration c = fresh(); c.get("general", "intervalSeconds", value); invalid(c, "Reject bad interval " + value);
            c = fresh(); c.get("general", "minimumAgeSeconds", value); invalid(c, "Reject bad age " + value);
        }
        Configuration c = fresh(); c.get("general", "enabled", "perhaps"); invalid(c, "Reject malformed boolean");
        c = fresh(); c.get("general", "intervalSeconds", "NaN"); invalid(c, "Reject non-numeric interval");
        c = fresh(); c.get("messages", "warningSeconds", new String[] {"ten"}); invalid(c, "Reject malformed warning list");
        c = fresh(); c.get("general", "excludedDimensions", new String[] {"moon"}); invalid(c, "Reject malformed dimensions");
        c = fresh(); c.get("messages", "warningColor", "BOLD"); invalid(c, "Formatting is not a color");
        c = fresh(); c.get("policies", "permanent", new String[] {"missing:item"}); invalid(c, "Unknown registry item fails closed");
        c = fresh(); c.get("general", "intervalSeconds", 0); activate(c);
        check(!ModConfig.current().enabled, "Invalid loaded config disables functionality");
        File malformed = new File(fixtures, "malformed.cfg");
        byte[] invalidText = "general {\n B:enabled=true\n ? invalid syntax\n}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(malformed.toPath(), invalidText);
        ModConfig.load(malformed, SimpleItemCleaner.log());
        check(!ModConfig.current().enabled, "Syntax error never enables fresh defaults");
        check(Arrays.equals(invalidText, Files.readAllBytes(malformed.toPath())), "Malformed file neither rewritten nor renamed");
        check(new CleanupResult(0, 0, 52000).format(ModConfig.RESULT)
                .equals("[SimpleItemCleaner] Items removed: 0 | 0 entities | 0,052 ms"), "Result template includes zero");
    }

    private static void policies() {
        PolicyResolver resolver = new PolicyResolver(new String[] {"minecraft:diamond", "minecraft:wool@2"},
                new String[] {"minecraft:diamond@4", "minecraft:wool@*"}, id -> true);
        check(resolver.resolve(DIAMOND, 0) == ItemPolicy.VANILLA, "Wildcard vanilla");
        check(resolver.resolve(DIAMOND, 4) == ItemPolicy.PERMANENT, "Permanent exact beats vanilla wildcard");
        check(resolver.resolve(new ResourceLocation("minecraft:wool"), 2) == ItemPolicy.PERMANENT, "Permanent wildcard beats vanilla exact");
        check(resolver.resolve(new ResourceLocation("minecraft:stick"), 0) == ItemPolicy.CONTROLLED, "Default controlled");
        PolicyResolver exact = new PolicyResolver(new String[] {"minecraft:diamond@4"}, new String[0], id -> true);
        check(exact.resolve(DIAMOND, 3) == ItemPolicy.CONTROLLED && exact.resolve(DIAMOND, 4) == ItemPolicy.VANILLA, "Metadata match only");
        for (String bad : new String[] {"diamond", "1", "Minecraft:diamond", "minecraft:diamond@", "minecraft:diamond@-1",
                "minecraft:diamond@1@2", "minecraft:diamond@2147483648", "minecraft:diamond@x"}) {
            boolean rejected = false;
            try { new PolicyResolver(new String[] {bad}, new String[0], id -> true); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Reject malformed item rule");
        }
        ModConfig cfg = read(fresh());
        check(!ItemRules.eligible(cfg, 0, ItemPolicy.CONTROLLED, 3599, false), "New drops protected");
        check(ItemRules.eligible(cfg, 0, ItemPolicy.CONTROLLED, 3600, false), "Minimum age inclusive");
        check(!ItemRules.eligible(cfg, 0, ItemPolicy.CONTROLLED, -32768, false), "Negative age protected");
        check(!ItemRules.eligible(cfg, 0, ItemPolicy.CONTROLLED, 5000, true), "Conflict protected from cleanup");
        check(!ItemRules.eligible(cfg, 0, ItemPolicy.VANILLA, 5000, false), "Vanilla excluded");
        check(!ItemRules.eligible(cfg, 0, ItemPolicy.PERMANENT, 5000, false), "Permanent excluded");
        check(!ItemRules.eligible(ModConfig.current(), 0, ItemPolicy.CONTROLLED, 5000, false), "Disabled excludes all");
    }

    private static void clocks() {
        CleanupClock clock = new CleanupClock(60, new int[] {60, 30, 10, 10, 0, -1, 90});
        check(clock.remainingSeconds() == 60, "Initial interval");
        int warnings = 0; int due = 0;
        for (int tick = 1; tick <= 1200; tick++) {
            int action = clock.tick();
            if (action > 0) { warnings++; check((tick == 600 && action == 30) || (tick == 1000 && action == 10), "Warning timing"); }
            if (action < 0) { due++; check(tick == 1200, "Exactly one cycle after 1200 ticks"); }
        }
        check(warnings == 2 && due == 1, "No duplicate/invalid warnings");
        clock.reset(); for (int i = 0; i < 999; i++) { clock.tick(); }
        clock.reset(); // Same reset called by controller on completed manual cleanup.
        check(clock.remainingSeconds() == 60, "Manual reset renews full interval");
        boolean quiet = true;
        for (int i = 0; i < 599; i++) { quiet &= clock.tick() == 0; }
        check(quiet, "No stale warnings after manual reset");
        check(clock.tick() == 30, "Warning rescheduled after manual reset");
        clock.reset(); clock.tick(); check(clock.remainingSeconds() == 60, "Status rounds remaining seconds up");
        CleanupClock longClock = new CleanupClock(86400, new int[0]);
        check(longClock.remainingSeconds() == 86400, "Largest configured interval");
    }

    private static void mappedWorlds() throws Exception {
        Configuration c = fresh(); c.get("general", "intervalSeconds", 60); c.get("general", "minimumAgeSeconds", 15);
        c.get("policies", "vanilla", new String[] {"minecraft:stick"});
        c.get("policies", "permanent", new String[] {"minecraft:diamond"});
        c.get("general", "excludedDimensions", new int[] {7}); activate(c);
        check(ModConfig.current().enabled, "Load actual Forge registry rules");
        ItemLifecycleHandler handler = new ItemLifecycleHandler();
        StubWorld server = new StubWorld(false, 0); StubWorld client = new StubWorld(true, 0);
        StubItem diamond = new StubItem(server, Items.DIAMOND, 64, 6000);
        ItemExpireEvent event = new ExpireEvent(diamond, 6000); handler.expire(event);
        check(event.isCanceled() && event.getExtraLife() == 1200, "Permanent natural expiration extended");
        diamond.lifespan += event.getExtraLife();
        StubItem stick = new StubItem(server, Items.STICK, 1, 6000);
        event = new ExpireEvent(stick, 6000); handler.expire(event);
        check(!event.isCanceled() && stick.lifespan == 6000, "Vanilla natural expiration unchanged");
        StubItem controlled = new StubItem(server, Items.APPLE, 64, 6000);
        event = new ExpireEvent(controlled, 6000); handler.expire(event);
        check(event.isCanceled() && event.getExtraLife() == 1200, "Controlled natural expiration extended");
        controlled.lifespan += event.getExtraLife();
        StubItem alreadyCanceled = new StubItem(server, Items.APPLE, 1, 6000);
        event = new ExpireEvent(alreadyCanceled, 777); event.setCanceled(true); handler.expire(event);
        check(event.getExtraLife() == 777 && !alreadyCanceled.state.isTracked(), "Respect prior cancellation");
        StubItem remote = new StubItem(client, Items.APPLE, 1, 6000);
        event = new ExpireEvent(remote, 6000); handler.expire(event);
        check(!event.isCanceled() && !remote.state.isTracked(), "Client expiration untouched");
        AttachCapabilitiesEvent<Entity> remoteAttach = new AttachCapabilitiesEvent<>(Entity.class, remote);
        handler.attach(remoteAttach); check(remoteAttach.getCapabilities().isEmpty(), "No lifecycle provider on client");
        AttachCapabilitiesEvent<Entity> serverAttach = new AttachCapabilitiesEvent<>(Entity.class, controlled);
        handler.attach(serverAttach); check(serverAttach.getCapabilities().size() == 1
                && serverAttach.getCapabilities().containsKey(new ResourceLocation("simpleitemcleaner:lifecycle")), "Only the current capability namespace is attached");
        StubItem fresh = new StubItem(server, Items.APPLE, 1, 299);
        StubItem unloaded = new StubItem(server, Items.APPLE, 1, 1000); unloaded.posX = 1600;
        StubItem conflict = new StubItem(server, Items.APPLE, 1, 8000); conflict.state.markConflict();
        StubItem notAdded = new StubItem(server, Items.APPLE, 1, 1000); notAdded.addedToChunk = false;
        EntityXPOrb xp = new EntityXPOrb(server);
        server.loadedEntityList.addAll(Arrays.asList(diamond, stick, controlled, fresh, unloaded, conflict, notAdded, xp));
        ItemStack inventory = new ItemStack(Items.APPLE, 64);
        CleanupService cleanup = new CleanupService(handler);
        CleanupService.Sweep result = cleanup.sweep(server, ModConfig.current());
        check(result.entities == 1 && result.units == 64 && controlled.isDead, "Count 64 units and one entity separately");
        check(!diamond.isDead && !stick.isDead && !fresh.isDead && !unloaded.isDead && !conflict.isDead && !notAdded.isDead, "All protections enforced during actual sweep");
        check(!xp.isDead && inventory.getCount() == 64, "XP and unrelated ItemStack untouched");
        check(cleanup.sweep(server, ModConfig.current()).entities == 0, "Already dead drop not recounted");
        client.loadedEntityList.add(remote);
        check(cleanup.sweep(client, ModConfig.current()).entities == 0 && !remote.isDead, "Client sweep refused");
        StubWorld excluded = new StubWorld(false, 7);
        StubItem excludedItem = new StubItem(excluded, Items.APPLE, 1, 7000); excluded.loadedEntityList.add(excludedItem);
        event = new ExpireEvent(excludedItem, 6000); handler.expire(event);
        check(!event.isCanceled() && cleanup.sweep(excluded, ModConfig.current()).entities == 0, "Excluded dimension has vanilla behavior");
        check(server.loads == 0 && client.loads == 0 && excluded.loads == 0, "Never load chunks");
        ModConfig.disable(); handler.reconcile(diamond, diamond.state);
        check(diamond.lifespan == 6000 && !diamond.state.isTracked(), "Disable safely reverses owned extension");
        check(cleanup.sweep(server, ModConfig.current()).entities == 0, "Disabled sweep refused");
        StubItem old = new StubItem(server, Items.APPLE, 1, (short) 40000);
        old.lifespan = 100000; old.state.load(new LifecycleState.Snapshot(40000, false, 0, 0, false));
        handler.reconcile(old, old.state);
        check(EntityItemAccess.readAge(old) == 40000 && old.lifespan == 100000 && old.state.needsAgePersistence(), "Full age persists after rollback with long custom life");
        LifecycleState restored = new LifecycleState(); restored.load(old.state.snapshot(45000, old.lifespan));
        check(restored.restoreLoadedAge((short) 45000) == 45000, "Second disabled save/reload retains full age");
        LifecycleProvider provider = new LifecycleProvider(old);
        NBTTagCompound future = new NBTTagCompound(); future.setInteger("Version", 99); future.setString("FutureField", "preserve");
        provider.deserializeNBT(future);
        check(provider.serializeNBT().equals(future), "Unknown capability NBT preserved verbatim");
        LifecycleProvider loadedProvider = new LifecycleProvider(old);
        loadedProvider.deserializeNBT(com.creator01.simpleitemcleaner.lifecycle.LifecycleNbt.write(old.state.snapshot(40000, old.lifespan)));
        check(loadedProvider.serializeNBT().getInteger("FullAge") == 40000, "Actual provider serializes full age even disabled");
    }

    private static void permissions() throws Exception {
        StubSender player = new StubSender(false); StubSender admin = new StubSender(true);
        SimpleItemCleanerCommand command = new SimpleItemCleanerCommand(new CleanupController(new CleanupService(new ItemLifecycleHandler())));
        check(command.getName().equals("simpleitemcleaner") && command.getUsage(player).equals("/simpleitemcleaner <status|clear>"), "Renamed command and usage");
        check(command.getAliases().isEmpty(), "No compatibility command aliases");
        check(command.getRequiredPermissionLevel() == 0, "Status publicly accessible");
        check(!SimpleItemCleanerCommand.canClear(player) && SimpleItemCleanerCommand.canClear(admin), "Clear requires permission level 2");
        command.execute(null, player, new String[] {"status"}); check(player.message != null, "Status works while disabled");
        boolean denied = false;
        try { command.execute(null, player, new String[] {"clear"}); } catch (CommandException expected) { denied = true; }
        check(denied, "Non-admin manual cleanup rejected");
        check(!command.getTabCompletions(null, player, new String[] {""}, null).contains("clear"), "Non-admin completion excludes clear");
    }

    private static void controller() throws Exception {
        Configuration config = fresh(); config.get("general", "intervalSeconds", 60);
        config.get("messages", "warningSeconds", new int[] {30, 10}); activate(config);
        StubServer server = new StubServer();
        CleanupController controller = new CleanupController(new CleanupService(new ItemLifecycleHandler()));
        controller.start(server);
        for (int i = 0; i < 600; i++) { controller.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.START)); }
        check(controller.remainingSeconds() == 60 && server.messages.isEmpty(), "START does not advance scheduler");
        for (int i = 0; i < 600; i++) { controller.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END)); }
        check(controller.remainingSeconds() == 30 && server.messages.size() == 1, "END advances one server clock");
        check(server.messages.get(0).getUnformattedText().contains("30s") && server.messages.get(0).getStyle().getColor() == TextFormatting.RED, "Actual warning broadcast");
        SimpleItemCleanerCommand command = new SimpleItemCleanerCommand(controller);
        StubSender admin = new StubSender(true); command.execute(server, admin, new String[] {"clear"});
        check(controller.remainingSeconds() == 60 && admin.message.getUnformattedText().contains("Cleanup interval reset"), "Actual manual command resets controller");
        check(server.messages.size() == 2 && server.messages.get(1).getUnformattedText().matches("\\[SimpleItemCleaner\\] Items removed: 0 \\| 0 entities \\| [0-9]+,[0-9]{3} ms")
                && server.messages.get(1).getStyle().getColor() == TextFormatting.GREEN, "Actual zero-result broadcast");
        for (int i = 0; i < 1200; i++) { controller.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END)); }
        check(controller.remainingSeconds() == 60 && server.messages.size() == 5, "Automatic cycle resets after two warnings and one result");
        server.mainThread = false;
        boolean rejected = false;
        try { controller.clear(server, true); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "World operations reject other threads"); server.mainThread = true;
        controller.stop(); controller.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
        check(!controller.isActive() && controller.remainingSeconds() == 0 && server.messages.size() == 5, "Session stop clears scheduler");
        config.get("messages", "resultMessage", "").set("Units={count}; entities={entities}; duration={duration} ms; {count}");
        config.get("messages", "resultColor", "GREEN").set("GOLD"); activate(config);
        controller.start(server);
        command.execute(server, admin, new String[] {"clear"});
        check(server.messages.get(5).getUnformattedText().matches("Units=0; entities=0; duration=[0-9]+,[0-9]{3} ms; 0"), "Custom CFG message expands all placeholders");
        check(server.messages.get(5).getStyle().getColor() == TextFormatting.GOLD
                && admin.message.getStyle().getColor() == TextFormatting.GOLD, "Configured color applies to result and command feedback");
        check(admin.message.getUnformattedText().equals(server.messages.get(5).getUnformattedText() + " Cleanup interval reset."), "Command and broadcast share measured result");
        controller.stop();
        config.get("messages", "warningsEnabled", false); config.get("messages", "resultsEnabled", false); activate(config);
        controller.start(server); for (int i = 0; i < 1200; i++) { controller.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END)); }
        check(server.messages.size() == 6, "Disabled messages emit nothing");
        ModConfig.disable(); controller.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
        check(!controller.isActive() && controller.remainingSeconds() == 60, "Disabled config freezes scheduler");
        controller.stop();
    }

    private static void counting() {
        Configuration config = fresh(); config.get("general", "minimumAgeSeconds", 15);
        config.get("policies", "vanilla", new String[] {"minecraft:stick"});
        config.get("policies", "permanent", new String[] {"minecraft:gold_ingot"}); activate(config);
        StubWorld world = new StubWorld(false, 0);
        StubItem one = new StubItem(world, Items.DIAMOND, 1, 300);
        StubItem five = new StubItem(world, Items.DIAMOND, 5, 300);
        StubItem ten = new StubItem(world, Items.DIAMOND, 10, 300);
        StubItem young = new StubItem(world, Items.DIAMOND, 64, 299);
        StubItem vanilla = new StubItem(world, Items.STICK, 64, 400);
        StubItem permanent = new StubItem(world, Items.GOLD_INGOT, 64, 400);
        StubItem refusesRemoval = new StubItem(world, Items.DIAMOND, 64, 400) {
            @Override public void setDead() { /* Simulate a custom entity refusing removal. */ }
        };
        StubItem empty = new StubItem(world, Items.DIAMOND, 0, 400);
        world.loadedEntityList.addAll(Arrays.asList(one, five, ten, one, young, vanilla, permanent, refusesRemoval, empty));
        CleanupService service = new CleanupService(new ItemLifecycleHandler());
        CleanupService.Sweep counted = service.sweep(world, ModConfig.current());
        check(counted.units == 16 && counted.entities == 3, "1 + 5 + 10 = 16 units, 3 entities; duplicated reference counted once");
        check(!young.isDead && !vanilla.isDead && !permanent.isDead && !refusesRemoval.isDead, "Protected/failed removals excluded from both totals");
        counted = service.sweep(world, ModConfig.current());
        check(counted.units == 0 && counted.entities == 0, "Repeated cleanup does not recount dead drops");
        StubWorld largeWorld = new StubWorld(false, 0);
        largeWorld.loadedEntityList.add(new StubItem(largeWorld, Items.DIAMOND, Integer.MAX_VALUE, 400));
        largeWorld.loadedEntityList.add(new StubItem(largeWorld, Items.DIAMOND, Integer.MAX_VALUE, 400));
        counted = service.sweep(largeWorld, ModConfig.current());
        check(counted.units == 4294967294L && counted.entities == 2, "Unit accumulation exceeds int without overflow");
        StubWorld changedStackWorld = new StubWorld(false, 0);
        changedStackWorld.loadedEntityList.add(new StubItem(changedStackWorld, Items.DIAMOND, 10, 400) {
            @Override public void setDead() { setItem(ItemStack.EMPTY); super.setDead(); }
        });
        counted = service.sweep(changedStackWorld, ModConfig.current());
        check(counted.units == 10 && counted.entities == 1, "Units captured before successful removal clears stack");
        ModConfig.disable();
    }

    private static final class StubWorld extends World {
        int loads;
        StubWorld(boolean remote, int dimension) {
            super(new SaveHandlerMP(), new WorldInfo(new NBTTagCompound()), new WorldProviderSurface(), new Profiler(), remote);
            provider.setDimension(dimension);
            chunkProvider = new IChunkProvider() {
                private final Chunk loaded = new Chunk(StubWorld.this, 0, 0);
                public Chunk getLoadedChunk(int x, int z) { return x == 0 && z == 0 ? loaded : null; }
                public Chunk provideChunk(int x, int z) { loads++; throw new AssertionError("Unexpected chunk load"); }
                public boolean tick() { return false; }
                public String makeString() { return "isolated test"; }
                public boolean isChunkGeneratedAt(int x, int z) { return x == 0 && z == 0; }
            };
        }
        @Override protected IChunkProvider createChunkProvider() { return chunkProvider; }
        @Override protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) { return x == 0 && z == 0; }
    }
    /** In-game Forge's event transformer supplies this override for @Cancelable events. */
    private static final class ExpireEvent extends ItemExpireEvent {
        ExpireEvent(EntityItem item, int extraLife) { super(item, extraLife); }
        @Override public boolean isCancelable() { return true; }
    }
    private static class StubItem extends EntityItem {
        final LifecycleState state = new LifecycleState();
        StubItem(World world, net.minecraft.item.Item item, int count, int age) {
            super(world); setItem(new ItemStack(item, count)); EntityItemAccess.restoreAge(this, age); addedToChunk = true;
        }
        @Override @SuppressWarnings("unchecked") public <T> T getCapability(Capability<T> capability, EnumFacing side) { return (T) state; }
    }
    private static final class StubSender implements ICommandSender {
        final boolean admin; ITextComponent message;
        StubSender(boolean admin) { this.admin = admin; }
        public String getName() { return "test"; }
        public boolean canUseCommand(int level, String command) { return level <= (admin ? 2 : 0); }
        public World getEntityWorld() { return null; }
        public MinecraftServer getServer() { return null; }
        public void sendMessage(ITextComponent text) { message = text; }
    }
    private static final class StubServer extends MinecraftServer {
        final List<ITextComponent> messages = new ArrayList<>();
        boolean mainThread = true;
        private final PlayerList players;
        StubServer() {
            super(fixtures, Proxy.NO_PROXY, null, null, null, null, null);
            players = new PlayerList(this) {
                @Override public void sendMessage(ITextComponent text) { messages.add(text); }
            };
        }
        @Override public PlayerList getPlayerList() { return players; }
        @Override public ServerCommandManager createCommandManager() { return null; }
        @Override public boolean isCallingFromMinecraftThread() { return mainThread; }
        public boolean init() { return false; }
        public boolean canStructuresSpawn() { return false; }
        public GameType getGameType() { return GameType.SURVIVAL; }
        public EnumDifficulty getDifficulty() { return EnumDifficulty.NORMAL; }
        public boolean isHardcore() { return false; }
        public int getOpPermissionLevel() { return 2; }
        public boolean shouldBroadcastRconToOps() { return false; }
        public boolean shouldBroadcastConsoleToOps() { return false; }
        public boolean isDedicatedServer() { return false; }
        public boolean shouldUseNativeTransport() { return false; }
        public boolean isCommandBlockEnabled() { return false; }
        public String shareToLAN(GameType type, boolean cheats) { return null; }
    }
}
