package com.jiezhimod.command;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageWhiteScreen;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.Arrays;
import java.util.List;

public class CommandWhiteScreen extends CommandTargetBase {
    /** /white 只给 ticks 时使用的默认白屏不透明度 */
    private static final float DEFAULT_ALPHA = 1.0F;

    @Override
    public String getName() {
        return "white";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("whitescreen");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/white <@target> <ticks> <alpha>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new CommandException("usage: %s", getUsage(sender));
        }

        List<EntityPlayerMP> targets = resolveTargets(server, sender, args);
        String[] params = args;
        if (args[0].startsWith("@")) {
            params = Arrays.copyOfRange(args, 1, args.length);
        }

        if (params.length < 1) {
            throw new CommandException("usage: %s", getUsage(sender));
        }

        float[] parsed = resolve(params);
        int ticks = (int) parsed[0];
        float alpha = parsed[1];

        for (EntityPlayerMP player : targets) {
            ModNetwork.CHANNEL.sendTo(new MessageWhiteScreen(ticks, alpha), player);
        }

        String message;
        if (targets.size() == 1 && sender instanceof EntityPlayerMP && targets.get(0).getUniqueID().equals(((EntityPlayerMP) sender).getUniqueID())) {
            message = "Applied white screen to yourself: ticks=" + ticks + ", alpha=" + alpha;
        } else if (targets.size() == server.getPlayerList().getPlayers().size()) {
            message = "Applied white screen to all online players: ticks=" + ticks + ", alpha=" + alpha;
        } else {
            message = "Applied white screen to " + targets.size() + " targets: ticks=" + ticks + ", alpha=" + alpha;
        }
        sender.sendMessage(new TextComponentString(message));
    }

    /**
     * 解析 /white 的数值参数：{ticks, alpha}。
     * 单参数时解释为 ticks（旧实现当成 alpha，导致 /white 40 直接报"数字过大"而不可用），
     * alpha 取默认 1.0。
     */
    public static float[] resolve(String[] params) throws CommandException {
        if (params == null || params.length < 1) {
            throw new CommandException("usage: /white [@target] <ticks> <alpha>");
        }
        int ticks = parseInt(params[0], 1, 2000);
        float alpha = params.length >= 2 ? (float) parseDouble(params[1], 0.0D, 1.0D) : DEFAULT_ALPHA;
        return new float[] { (float) ticks, alpha };
    }
}
