package com.creator01.simpleitemcleaner.cleanup;

import com.creator01.simpleitemcleaner.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** A single server reference for the active session; no retained entity or world references. */
public final class CleanupController {
    private final CleanupService service;
    private MinecraftServer server;
    private CleanupClock clock;
    private boolean running;

    public CleanupController(CleanupService service) { this.service = service; }
    public void start(MinecraftServer server) {
        this.server = server;
        ModConfig config = ModConfig.current();
        clock = new CleanupClock(config.intervalSeconds, config.warningSeconds());
        running = true;
    }
    public void stop() { running = false; server = null; clock = null; }
    public boolean isActive() { return running && ModConfig.current().enabled; }
    public long remainingSeconds() { return clock == null ? 0 : clock.remainingSeconds(); }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isActive()) { return; }
        if (!server.isCallingFromMinecraftThread()) { throw new IllegalStateException("Unexpected server tick thread"); }
        int action = clock.tick();
        ModConfig config = ModConfig.current();
        if (action < 0) { clear(server, false); }
        else if (action > 0 && config.warningsEnabled) {
            broadcast(message(config.warningMessage.replace("{time}", action + "s"), config.warningColor));
        }
    }

    public CleanupResult clear(MinecraftServer requestingServer, boolean manual) {
        if (requestingServer != server || !isActive()) { throw new IllegalStateException("Cleanup is disabled or server is not ready"); }
        ModConfig config = ModConfig.current();
        CleanupResult result = service.clear(server, config, manual);
        clock.reset(); // Both manual and automatic cleanup start a complete new interval after completion.
        if (config.resultsEnabled) {
            broadcast(message(result.format(config.resultMessage), config.resultColor));
        }
        return result;
    }

    private void broadcast(ITextComponent text) { server.getPlayerList().sendMessage(text); }
    public static ITextComponent message(String text, TextFormatting color) {
        TextComponentString component = new TextComponentString(text);
        component.getStyle().setColor(color);
        return component;
    }
}
