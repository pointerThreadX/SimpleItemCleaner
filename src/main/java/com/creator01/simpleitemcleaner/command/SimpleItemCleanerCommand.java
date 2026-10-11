package com.creator01.simpleitemcleaner.command;

import com.creator01.simpleitemcleaner.cleanup.CleanupController;
import com.creator01.simpleitemcleaner.cleanup.CleanupResult;
import com.creator01.simpleitemcleaner.config.ModConfig;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextFormatting;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

public final class SimpleItemCleanerCommand extends CommandBase {
    private final CleanupController controller;
    public SimpleItemCleanerCommand(CleanupController controller) { this.controller = controller; }
    @Override public String getName() { return "simpleitemcleaner"; }
    @Override public String getUsage(ICommandSender sender) { return "/simpleitemcleaner <status|clear>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }

    public static boolean canClear(ICommandSender sender) { return sender.canUseCommand(2, "simpleitemcleaner"); }

    @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1) { throw new WrongUsageException(getUsage(sender)); }
        if (args[0].equalsIgnoreCase("status")) {
            String text = controller.isActive()
                    ? "Next cleanup in " + controller.remainingSeconds() + "s of server tick time; minimum age: "
                        + ModConfig.current().minimumAgeSeconds + "s."
                    : "Cleanup and protection are disabled, or the server has not started.";
            sender.sendMessage(CleanupController.message("[SimpleItemCleaner] " + text, TextFormatting.GOLD));
        } else if (args[0].equalsIgnoreCase("clear")) {
            if (!canClear(sender)) { throw new CommandException("commands.generic.permission"); }
            if (!controller.isActive()) { throw new CommandException("SimpleItemCleaner is disabled or has not started yet."); }
            CleanupResult result = controller.clear(server, true);
            sender.sendMessage(CleanupController.message(result.format(ModConfig.current().resultMessage)
                    + " Cleanup interval reset.", ModConfig.current().resultColor));
        } else { throw new WrongUsageException(getUsage(sender)); }
    }

    @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                                   @Nullable BlockPos targetPos) {
        if (args.length != 1) { return Collections.emptyList(); }
        return getListOfStringsMatchingLastWord(args, canClear(sender) ? new String[] {"status", "clear"} : new String[] {"status"});
    }
}
