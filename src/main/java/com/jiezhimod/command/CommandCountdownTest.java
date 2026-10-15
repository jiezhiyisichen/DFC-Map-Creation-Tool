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
 * /countdown_test <name> <min> <max>
 * 检测倒计时剩余秒数是否在 [min, max] 区间内。
 * 在区间内：指令正常返回（成功次数 1）→ 命令方块被红石比较器监测时输出红石信号；
 * 不在区间内：抛 CommandException（成功次数 0）→ 比较器无信号。
 */
public class CommandCountdownTest extends CommandTargetBase {
    @Override
    public String getName() {
        return "countdown_test";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/countdown_test <name> <min> <max>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 3) {
            throw new CommandException("usage: %s", getUsage(sender));
        }
        long min = (long) parseInt(args[1], 0, Integer.MAX_VALUE);
        long max = (long) parseInt(args[2], 0, Integer.MAX_VALUE);
        if (min > max) {
            long tmp = min;
            min = max;
            max = tmp;
        }

        long remaining = ServerCountdownManager.getRemainingSeconds(args[0]);
        if (remaining < 0) {
            throw new CommandException("Countdown '%s' does not exist", args[0]);
        }

        if (remaining >= min && remaining <= max) {
            sender.sendMessage(new TextComponentString(
                    "Countdown '" + args[0] + "' in range: " + remaining + "s [" + min + ", " + max + "]"));
        } else {
            throw new CommandException(
                    "Countdown '" + args[0] + "' NOT in range: " + remaining + "s [" + min + ", " + max + "]");
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        return completeCountdownNames(args);
    }
}
