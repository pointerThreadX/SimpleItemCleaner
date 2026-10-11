package com.creator01.simpleitemcleaner;

import com.creator01.simpleitemcleaner.lifecycle.ItemLifecycleHandler;
import com.creator01.simpleitemcleaner.lifecycle.LifecycleProvider;
import com.creator01.simpleitemcleaner.config.ModConfig;
import com.creator01.simpleitemcleaner.cleanup.CleanupController;
import com.creator01.simpleitemcleaner.cleanup.CleanupService;
import com.creator01.simpleitemcleaner.command.SimpleItemCleanerCommand;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import org.apache.logging.log4j.Logger;

import java.io.File;

@Mod(modid = SimpleItemCleaner.MOD_ID, name = "SimpleItemCleaner", version = "1.0.0",
        acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*",
        guiFactory = "com.creator01.simpleitemcleaner.client.ConfigGuiFactory")
public final class SimpleItemCleaner {
    public static final String MOD_ID = "simpleitemcleaner";
    private static Logger logger;
    private static File configFile;
    private final ItemLifecycleHandler lifecycle = new ItemLifecycleHandler();
    private final CleanupController cleanup = new CleanupController(new CleanupService(lifecycle));

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        configFile = event.getSuggestedConfigurationFile();
        LifecycleProvider.register();
        MinecraftForge.EVENT_BUS.register(lifecycle);
        MinecraftForge.EVENT_BUS.register(cleanup);
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        lifecycle.startSession();
        cleanup.stop();
        ModConfig.load(configFile, logger);
        lifecycle.verifyAccess();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new SimpleItemCleanerCommand(cleanup));
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        cleanup.start(FMLCommonHandler.instance().getMinecraftServerInstance());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) { cleanup.stop(); }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        lifecycle.finishSession();
        cleanup.stop();
        ModConfig.disable();
    }

    public static Logger log() {
        return logger;
    }

    public static File configFile() { return configFile; }
}
