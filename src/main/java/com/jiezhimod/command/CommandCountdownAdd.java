package com.jiezhimod.command;

import com.jiezhimod.countdown.Countdown;
import com.jiezhimod.countdown.ServerCountdownManager;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * /countdown_add <style 1-6> <name> <seconds> [r] [g] [b]
 * 添加倒计时。rgb 为 0-255 可选，默认白色（样式2 固定红色，忽略 rgb）。
 *   样式1：普通时间 + 横线（rgb）
 *   样式2：斜杠包裹动画 \\\时间///（固定红）
 *   样式3：框框圈住时间（rgb）
 *   样式4：高精度 mm:ss.t（rgb）
 *   样式5：!mm:ss.t! 感叹号包裹（rgb）
 *   样式6：mm:ss.t + 下方进度条（rgb）
 */
public class CommandCountdownAdd extends CommandTargetBase {
    @Override
    public String getName() {
        return "countdown_add";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/countdown_add <style 1-" + Countdown.MAX_STYLE + "> <name> <seconds> [r] [g] [b]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 3) {
            throw new CommandException("usage: %s", getUsage(sender));
        }

        int style = parseInt(args[0], Countdown.MIN_STYLE, Countdown.MAX_STYLE);
        String name = args[1];
        long seconds = (long) parseInt(args[2], 1, 999999);
        int r = args.length >= 6 ? parseInt(args[3], 0, 255) : 255;
        int g = args.length >= 6 ? parseInt(args[4], 0, 255) : 255;
        int b = args.length >= 6 ? parseInt(args[5], 0, 255) : 255;

        boolean replaced = ServerCountdownManager.exists(name);
        ServerCountdownManager.add(name, style, r / 255.0F, g / 255.0F, b / 255.0F, seconds);

        String msg = (replaced ? "Replaced" : "Added") + " countdown '" + name + "': style=" + style
                + " (" + Countdown.styleDescription(style) + "), " + formatSeconds(seconds)
                + (style == 2 ? "" : ", rgb(" + r + "," + g + "," + b + ")");
        sender.sendMessage(new TextComponentString(msg));
    }

    /** 首个参数补全样式编号 1-6 */
    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args,
                                          @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "1", "2", "3", "4", "5", "6");
        }
        return Collections.emptyList();
    }

    private static String formatSeconds(long s) {
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        return h > 0 ? String.format("%02d:%02d:%02d", h, m, sec) : String.format("%02d:%02d", m, sec);
    }
}
