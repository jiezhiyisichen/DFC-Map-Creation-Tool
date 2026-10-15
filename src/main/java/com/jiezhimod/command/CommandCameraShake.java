package com.jiezhimod.command;

import com.jiezhimod.network.ModNetwork;
import com.jiezhimod.network.msg.MessageCameraShake;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import java.util.Arrays;
import java.util.List;

public class CommandCameraShake extends CommandTargetBase {
    /** /shake 只给 ticks 时使用的默认抖动强度（度） */
    private static final float DEFAULT_INTENSITY = 4.0F;

    @Override
    public String getName() {
        return "shake";
    }

    @Override
    public List<String> getAliases() {
        return Arrays.asList("cshake");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/shake [@target] <ticks> [intensity]";
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
        float intensity = parsed[1];

        for (EntityPlayerMP player : targets) {
            ModNetwork.CHANNEL.sendTo(new MessageCameraShake(ticks, intensity), player);
        }

        String message;
        if (targets.size() == 1 && sender instanceof EntityPlayerMP && targets.get(0).getUniqueID().equals(((EntityPlayerMP) sender).getUniqueID())) {
            message = "Applied camera shake to yourself: ticks=" + ticks + ", intensity=" + intensity;
        } else if (targets.size() == server.getPlayerList().getPlayers().size()) {
            message = "Applied camera shake to all online players: ticks=" + ticks + ", intensity=" + intensity;
        } else {
            message = "Applied camera shake to " + targets.size() + " targets: ticks=" + ticks + ", intensity=" + intensity;
        }
        sender.sendMessage(new TextComponentString(message));
    }

    /**
     * 解析 /shake 的数值参数：{ticks, intensity}。
     * 单参数时按文档语义解释为 ticks（旧实现把它当成 intensity，与 /shake &lt;ticks&gt; [intensity] 不符），
     * 强度取默认值。
     */
    public static float[] resolve(String[] params) throws CommandException {
        if (params == null || params.length < 1) {
            throw new CommandException("usage: /shake [@target] <ticks> [intensity]");
        }
        int ticks = parseInt(params[0], 1, 2000);
        float intensity = params.length >= 2 ? (float) parseDouble(params[1], 0.0D, 90.0D) : DEFAULT_INTENSITY;
        return new float[] { (float) ticks, intensity };
    }
}
