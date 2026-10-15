package com.jiezhimod.command;

import com.jiezhimod.countdown.ServerCountdownManager;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.List;

/**
 * /countdown_continue <name>  继续倒计时
 */
public class CommandCountdownContinue extends CommandTargetBase {
    @Override
    public String getName() {
        return "countdown_continue";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/countdown_continue <name>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new CommandException("usage: %s", getUsage(sender));
        }
        if (!ServerCountdownManager.setPaused(args[0], false)) {
            throw new CommandException("Countdown '%s' does not exist", args[0]);
        }
        sender.sendMessage(new TextComponentString("Resumed countdown '" + args[0] + "'"));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        return completeCountdownNames(args);
    }
}
